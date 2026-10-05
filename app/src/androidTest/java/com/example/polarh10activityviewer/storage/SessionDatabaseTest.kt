package com.example.polarh10activityviewer.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.polarh10activityviewer.ble.ConnectionDevice
import com.example.polarh10activityviewer.ble.checkedDataTypes
import com.example.polarh10activityviewer.heartrate.HeartRateReading
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.chart.ChartPoint
import com.example.polarh10activityviewer.chart.chartSegments
import com.example.polarh10activityviewer.history.HrHistory
import com.example.polarh10activityviewer.history.MotionHistory
import com.example.polarh10activityviewer.motion.StepState
import com.example.polarh10activityviewer.session.HrHistoryPoint
import com.example.polarh10activityviewer.session.MotionHistoryPoint
import com.example.polarh10activityviewer.session.SessionRecord
import com.example.polarh10activityviewer.session.SessionSnapshot
import com.example.polarh10activityviewer.session.SessionSummary
import com.example.polarh10activityviewer.session.StreamObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

internal fun databaseFixture(id: String = UUID.randomUUID().toString(), started: Long = 1000) = SessionSnapshot(
    SessionRecord(id, started - 100, ConnectionDevice("Test H10", "test-device"), started, started + 2500, 2500,
        "Stopped by user.", summary = SessionSummary(80, 140, 110.123456789, 9,
            listOf(100, 200, 300, 400, 500), 1000, 0, 0.0, 120.25),
        streams = checkedDataTypes.associateWith { StreamObservation(true, missing = true, failed = false) }),
    listOf(HrHistoryPoint(id, 0, 750, 120, true), HrHistoryPoint(id, 1, 1750, null, true),
        HrHistoryPoint(id, 2, 2345, 140, true)),
    listOf(MotionHistoryPoint(id, 0, 500, null, true), MotionHistoryPoint(id, 1, 1500, 0.0, true),
        MotionHistoryPoint(id, 2, 2499, 123.456789, false))
)

@RunWith(AndroidJUnit4::class)
class SessionDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var db: SessionDatabase

    @Before fun open() { name = "step62-test-${UUID.randomUUID()}.db"; db = SessionDatabase(context, name) }
    @After fun close() { db.close(); context.deleteDatabase(name) }

    @Test fun pauseConnectionsAndActualGapsSurviveSaveAndReopen() = runBlocking {
        val base = databaseFixture()
        val hr = HrHistory().apply { reset(base.record.id) }
        val motion = MotionHistory().apply { reset(base.record.id); onSubscriptionState(SubscriptionStatus.RECEIVING) }
        val steps = StepState(receivedAcc = true, cadence = 120.0)
        for (time in listOf(0L, 1000L)) {
            hr.receive(time, HeartRateReading(120))
            motion.record(time, steps, false, 1)
        }
        hr.stop(); motion.stop()
        hr.resume(); motion.resume()
        hr.onSubscriptionState(SubscriptionStatus.STARTING)
        motion.onSubscriptionState(SubscriptionStatus.STARTING, 2)
        motion.onSubscriptionState(SubscriptionStatus.RECEIVING)
        motion.record(2000, steps, true, 2)
        hr.receive(3000, HeartRateReading(130))
        motion.record(3000, steps, false, 2)
        assertFalse(hr.snapshot().last().breakBefore)
        assertFalse(motion.snapshot().last().breakBefore)
        hr.receive(4000, null)
        hr.receive(5000, HeartRateReading(140))
        motion.record(5000, steps, false, 3)
        val snapshot = base.copy(record = base.record.copy(durationMs = 5000,
            endedAt = base.record.startedAt!! + 5000), hrPoints = hr.snapshot(), motionPoints = motion.snapshot())
        db.save(snapshot)
        withContext(Dispatchers.IO) { db.close() }
        db = SessionDatabase(context, name)
        val saved = db.detail(base.record.id)!!
        assertEquals(snapshot, saved)
        val heartSegments = chartSegments(saved.hrPoints.map { ChartPoint(it.elapsedMs.toDouble(), it.bpm?.toDouble(), it.breakBefore) })
        val motionSegments = chartSegments(saved.motionPoints.map { ChartPoint(it.elapsedMs.toDouble(), it.cadence, it.breakBefore) })
        assertEquals(listOf(3, 1), heartSegments.map { it.size })
        assertEquals(listOf(3, 1), motionSegments.map { it.size })
    }

    @Test fun roundTripAfterCloseReopenPreservesEveryFieldNullZeroPrecisionAndBreak() = runBlocking {
        val snapshot = databaseFixture()
        db.save(snapshot)
        withContext(Dispatchers.IO) { db.close() }
        db = SessionDatabase(context, name)
        assertEquals(snapshot, db.detail(snapshot.record.id))
        assertEquals(listOf(snapshot.record), db.page())
        withContext(Dispatchers.IO) {
            db.readableDatabase.rawQuery("SELECT incomplete, receivedValidHr FROM sessions", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals(1, it.getInt(1))
            }
        }
    }

    @Test fun duplicatePointFailureRollsBackAllTablesThenSameIdRetryAndDuplicateSaveAreSafe() = runBlocking {
        val valid = databaseFixture()
        val broken = valid.copy(motionPoints = valid.motionPoints + valid.motionPoints.last())
        assertTrue(runCatching { db.save(broken) }.isFailure)
        assertNull(db.detail(valid.record.id)); assertTrue(db.page().isEmpty())
        withContext(Dispatchers.IO) {
            for (table in listOf("sessions", "hr_points", "motion_points")) {
                db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                    it.moveToFirst(); assertEquals(0, it.getInt(0))
                }
            }
        }
        db.save(valid); db.save(valid)
        db.save(valid.copy(record = valid.record.copy(summary = valid.record.summary.copy(totalSteps = 999))))
        assertEquals(valid, db.detail(valid.record.id))
        assertEquals(1, db.page().size)
    }

    @Test fun noDataIsRejectedButZeroDurationAccAndUnknownHrAreStored() = runBlocking {
        val valid = databaseFixture()
        val empty = valid.copy(record = SessionRecord("empty", 1, null, endedAt = 2), hrPoints = emptyList(), motionPoints = emptyList())
        assertTrue(runCatching { db.save(empty) }.isFailure)
        val acc = valid.copy(record = valid.record.copy(durationMs = 0, endedAt = valid.record.startedAt,
            summary = SessionSummary(totalSteps = 0)), hrPoints = emptyList(), motionPoints = emptyList())
        db.save(acc)
        assertEquals(acc, db.detail(acc.record.id))
        assertNull(db.detail("empty"))
    }

    @Test fun pagingUsesTenRowsDescendingWithTieBreakerAndNoDuplicatesAfterNewInsert() = runBlocking {
        val all = (0..44).map { databaseFixture("session-${it.toString().padStart(2, '0')}", (it / 3).toLong()) }
        all.forEach { db.save(it) }
        val first = db.page()
        assertEquals(10, first.size)
        db.save(databaseFixture("new", 9999))
        val second = db.page(first.last())
        val third = db.page(second.last())
        val fourth = db.page(third.last())
        val fifth = db.page(fourth.last())
        assertEquals(listOf(10, 10, 10, 10, 5), listOf(first, second, third, fourth, fifth).map { it.size })
        assertTrue(db.page(fifth.last()).isEmpty())
        assertEquals(all.map { it.record }.sortedWith(compareByDescending<SessionRecord> { it.startedAt }.thenByDescending { it.id }),
            first + second + third + fourth + fifth)
        assertEquals("new", db.page().first().id)
        assertNull(db.detail("absent"))
    }

    @Test fun deleteRollsBackOnFailureThenDeletesOnlySelectedSessionAndBothSeries() = runBlocking {
        val one = databaseFixture()
        val other = databaseFixture()
        db.save(one); db.save(other)
        withContext(Dispatchers.IO) {
            db.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON motion_points BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        }
        assertTrue(runCatching { db.delete(one.record.id) }.isFailure)
        assertEquals(one, db.detail(one.record.id))
        withContext(Dispatchers.IO) { db.writableDatabase.execSQL("DROP TRIGGER fail_delete") }
        db.delete(one.record.id)
        assertNull(db.detail(one.record.id)); assertEquals(other, db.detail(other.record.id))
        withContext(Dispatchers.IO) {
            for (table in listOf("hr_points", "motion_points")) db.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM $table WHERE sessionId = ?", arrayOf(one.record.id)).use {
                it.moveToFirst(); assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test fun saveControllerRetriesSameFrozenSnapshotAfterActualSqliteTransactionFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val owner = SessionSaveController(scope, db::save)
            val snapshot = databaseFixture()
            withContext(Dispatchers.IO) {
                db.writableDatabase.execSQL("CREATE TRIGGER fail_save BEFORE INSERT ON motion_points BEGIN SELECT RAISE(ABORT, 'test full disk'); END")
            }
            withContext(Dispatchers.Main) { assertTrue(owner.submit(snapshot)) }
            withTimeout(10_000) { owner.state.first { it.status == SaveStatus.FAILED } }
            assertNull(db.detail(snapshot.record.id))
            assertTrue(owner.state.value.blocksStart)
            withContext(Dispatchers.IO) { db.writableDatabase.execSQL("DROP TRIGGER fail_save") }
            withContext(Dispatchers.Main) { assertTrue(owner.retry()); assertFalse(owner.retry()) }
            withTimeout(10_000) { owner.state.first { it.status == SaveStatus.SAVED } }
            assertFalse(owner.state.value.blocksStart)
            assertEquals(snapshot, db.detail(snapshot.record.id))
        } finally { scope.cancel() }
    }
}
