package com.example.polarh10activityviewer.session

import com.example.polarh10activityviewer.ble.ConnectionDevice
import com.example.polarh10activityviewer.ble.DataSubscriptions
import com.example.polarh10activityviewer.ble.LatestHeartRate
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.chart.ChartKind
import com.example.polarh10activityviewer.chart.LiveCharts
import com.example.polarh10activityviewer.heartrate.HeartRateZones
import com.example.polarh10activityviewer.history.HrHistory
import com.example.polarh10activityviewer.history.MotionHistory
import com.example.polarh10activityviewer.motion.StepDetector
import com.example.polarh10activityviewer.sensor.AccSampleProcessor
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.ACC
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.HR
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarAccelerometerData.PolarAccelerometerDataSample
import com.polar.sdk.api.model.PolarHrData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSnapshotTest {
    private fun samples(startNs: Long = 0, count: Int = 604, moving: Boolean = true) =
        PolarAccelerometerData((0 until count).map { index ->
            val x = if (!moving || index < 104) 1000 else when ((index - 104) % 50) {
                in 0..9 -> 1800
                in 10..19 -> 800
                else -> 1000
            }
            PolarAccelerometerDataSample(startNs + index * 10_000_000L, x, 0, 0)
        })

    private class Fixture(scope: CoroutineScope) {
        var now = 5000L
        var wall = 1_000_000L
        val hr = LatestHeartRate()
        val zones = HeartRateZones()
        val detector = StepDetector { now }
        val acc = AccSampleProcessor { detector.receive(it) }
        val hrHistory = HrHistory()
        val motionHistory = MotionHistory()
        val charts = LiveCharts { emptyList() }
        var snapshot: SessionSnapshot? = null
        var freezes = 0
        var onFreeze: (SessionSnapshot) -> Unit = {}
        var allowStart: () -> Boolean = { true }
        lateinit var session: SessionController
        val subscriptions = DataSubscriptions(scope) { type, status ->
            if (type == HR) hrHistory.onSubscriptionState(status)
            if (type == ACC) motionHistory.onSubscriptionState(status)
            charts.onSubscriptionState(type, status, session.elapsedAt(now))
            if (type == HR && status != SubscriptionStatus.RECEIVING && session.state.value.ongoing) {
                zones.clearCurrent(session.elapsedAt(now))
            }
            hr.onSubscriptionState(type, status)
            acc.onSubscriptionState(type, status)
            if (type == ACC && session.state.value.ongoing) detector.onSubscriptionState(status)
            session.onSubscriptionState(type, status, now)
        }
        init {
            session = SessionController(subscriptions, { now }, {
                val id = session.state.value.record?.id
                hrHistory.reset(id); motionHistory.reset(id)
                charts.reset(); hr.reset(); zones.reset(); detector.reset(); acc.clear()
            }, {
                hrHistory.stop(); motionHistory.stop()
                charts.stop(session.state.value.elapsedMs)
                zones.clearCurrent(session.state.value.elapsedMs); hr.clear()
                detector.updateSessionTime(session.state.value.elapsedMs); detector.stop()
            }, { elapsed ->
                zones.refresh(elapsed); detector.updateSessionTime(elapsed)
                SessionSummary.from(hr.statistics.value, zones.state.value, detector.state.value)
            }, { wall }, { record ->
                assertTrue(hrHistory.frozen)
                assertTrue(motionHistory.frozen)
                assertSame(session.state.value.record, record)
                freezes++
                snapshot = SessionSnapshot(record, hrHistory.snapshot(), motionHistory.snapshot())
                onFreeze(snapshot!!)
            }, canStart = { allowStart() }, onResume = { hrHistory.resume(); motionHistory.resume(); charts.resume() })
        }

        fun start(source: Flow<PolarAccelerometerData>, heartRates: Flow<Int>? = flow { awaitCancellation() }) =
            session.start(true, ConnectionDevice("Polar H10", "12345678")) {
                if (heartRates != null) {
                    val generation = session.state.value.generation
                    subscriptions.start(HR, { session.accepts(generation) }, { session.accepts(generation) },
                        { heartRates }) { bpm ->
                        if (session.checkTimeLimit(now) || !session.accepts(generation)) return@start
                        val data = PolarHrData(listOf(PolarHrData.PolarHrSample(
                            bpm, 0, 0, emptyList(), emptyList(), false, true, true)))
                        val valid = hr.receive(data)
                        if (valid) session.onValidData(now, wall)
                        zones.receive(hr.reading.value, valid, session.elapsedAt(now))
                        session.refresh(generation, now)
                        if (session.state.value.status == SessionStatus.RUNNING) {
                            hrHistory.receive(session.state.value.elapsedMs, hr.reading.value)
                        }
                    }
                }
                accStream(source)
            }

        fun accStream(source: Flow<PolarAccelerometerData>): Boolean {
            val generation = session.state.value.generation
            return subscriptions.start(ACC, { session.accepts(generation) }, { session.accepts(generation) },
                { source }) { data ->
                if (session.checkTimeLimit(now) || !session.accepts(generation)) return@start
                acc.receive(data)
                detector.receivedBatch(data.samples.last().timeStamp, now)
                if (detector.state.value.incompleteAcc) session.markMissing(ACC)
                session.onValidData(now, wall)
                session.refresh(generation, now)
            }
        }

        fun tick(generation: Long = session.state.value.generation) {
            session.refresh(generation, now)
            if (session.accepts(generation)) {
                detector.refresh()
                if (session.state.value.status == SessionStatus.RUNNING) {
                    val elapsed = session.state.value.elapsedMs
                    charts.recordMotion(elapsed, detector.state.value, detector.isWarmingUp, detector.segment)
                    motionHistory.record(elapsed, detector.state.value, detector.isWarmingUp, detector.segment)
                    charts.advance(elapsed)
                }
            }
        }

        fun stop() = session.stop("Stopped by user.", interrupted = false)
    }

    @Test fun userStopResetWaitsForCleanupAndPreservesFailedSaveForRetry() = runTest {
        val f = Fixture(this)
        val cleanup = CompletableDeferred<Unit>()
        val writeReady = CompletableDeferred<Unit>()
        val attempts = mutableListOf<SessionSnapshot>()
        val saves = com.example.polarh10activityviewer.storage.SessionSaveController(this) {
            attempts.add(it)
            writeReady.await()
            if (attempts.size == 1) error("Controlled save failure")
        }
        f.onFreeze = { saves.submit(it) }
        f.allowStart = { !saves.state.value.blocksStart }
        val hrs = MutableSharedFlow<Int>()
        lateinit var old: FlowCollector<PolarAccelerometerData>
        f.start(object : Flow<PolarAccelerometerData> {
            override suspend fun collect(collector: FlowCollector<PolarAccelerometerData>) {
                old = collector
                try { collector.emit(samples()); awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
            }
        }, hrs)
        runCurrent(); hrs.emit(130); runCurrent()
        f.now = 6250; f.tick()
        val oldGeneration = f.session.state.value.generation
        f.session.stop("Stopped by user.", interrupted = false, reset = true)
        runCurrent()
        val frozen = f.snapshot!!
        assertTrue(frozen.record.eligibleForSaving)
        assertTrue(frozen.hrPoints.isNotEmpty()); assertTrue(frozen.motionPoints.isNotEmpty())
        assertEquals(10L, frozen.record.summary.totalSteps)
        assertEquals(SessionStatus.STOPPING, f.session.state.value.status)
        f.session.stop("Repeated Stop", interrupted = false, reset = true)
        assertEquals(1, f.freezes)
        assertFalse(f.start(flow { awaitCancellation() }))
        cleanup.complete(Unit); runCurrent()
        assertEquals(SessionState(generation = oldGeneration + 1), f.session.state.value)
        assertTrue(f.subscriptions.states.value.values.all { it.status == SubscriptionStatus.IDLE })
        assertEquals(com.example.polarh10activityviewer.ble.HeartRateStatistics(), f.hr.statistics.value)
        assertEquals(com.example.polarh10activityviewer.motion.StepState(), f.detector.state.value)
        assertEquals(com.example.polarh10activityviewer.heartrate.HeartRateZoneState(), f.zones.state.value)
        assertNull(f.hrHistory.sessionId); assertNull(f.motionHistory.sessionId)
        assertTrue(f.hrHistory.snapshot().isEmpty()); assertTrue(f.motionHistory.snapshot().isEmpty())
        assertTrue(f.charts.snapshot(ChartKind.HEART_RATE, 0).points.isEmpty())
        assertTrue(f.charts.snapshot(ChartKind.CADENCE, 0).points.isEmpty())
        runCatching { old.emit(samples(40_000_000_000)) }; f.now = 90_000; f.tick(oldGeneration); runCurrent()
        assertEquals(0L, f.detector.totalSteps); assertEquals(0L, f.session.state.value.elapsedMs)
        assertSame(frozen, attempts.single()); assertEquals(10L, frozen.record.summary.totalSteps)
        writeReady.complete(Unit); runCurrent()
        assertEquals(com.example.polarh10activityviewer.storage.SaveStatus.FAILED, saves.state.value.status)
        assertFalse(f.start(flow { awaitCancellation() }))
        assertTrue(saves.retry()); runCurrent()
        assertEquals(com.example.polarh10activityviewer.storage.SaveStatus.SAVED, saves.state.value.status)
        assertEquals(2, attempts.size); assertSame(frozen, attempts.last())
        assertTrue(f.start(flow { awaitCancellation() })); runCurrent()
        assertNotEquals(frozen.record.id, f.session.state.value.record!!.id)
        assertEquals(0L, f.session.state.value.elapsedMs)
        f.stop(); runCurrent()
    }

    @Test fun stopFromPausedResetsImmediatelyWithoutLosingTheFrozenSession() = runTest {
        val f = Fixture(this)
        f.start(flow { emit(samples()); awaitCancellation() }); runCurrent()
        f.now = 6500; f.tick()
        assertTrue(f.session.pause()); runCurrent()
        assertEquals(SessionStatus.PAUSED, f.session.state.value.status)
        f.now = 90_000
        f.session.stop("Stopped by user.", interrupted = false, reset = true)
        assertEquals(SessionStatus.IDLE, f.session.state.value.status)
        assertNull(f.session.state.value.record)
        assertEquals(0L, f.session.state.value.elapsedMs)
        assertEquals(1500L, f.snapshot!!.record.durationMs)
        assertEquals(10L, f.snapshot!!.record.summary.totalSteps)
        assertTrue(f.snapshot!!.motionPoints.isNotEmpty())
        assertTrue(f.motionHistory.snapshot().isEmpty())
        assertEquals(1, f.freezes)
    }

    @Test fun summaryAndBothHistoriesFreezeOnceWithSameIdAfterSettlementAndBeforeCleanup() = runTest {
        val f = Fixture(this)
        val cleanup = CompletableDeferred<Unit>()
        val hrs = MutableSharedFlow<Int>()
        f.start(flow {
            try { emit(samples()); awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await() } }
        }, hrs); runCurrent()
        hrs.emit(120); runCurrent()
        f.now = 5250; f.tick()
        f.now = 5789; f.wall = -1000; f.tick()
        hrs.emit(140); runCurrent()
        val before = f.motionHistory.snapshot()
        assertTrue(before.last().cadence!! > 0)
        f.now = 5999; f.stop(); runCurrent()
        val frozen = f.snapshot!!
        assertEquals(1, f.freezes)
        assertEquals(999L, frozen.record.durationMs)
        assertEquals(-1000L, frozen.record.endedAt)
        assertEquals("12345678", frozen.record.device!!.deviceId)
        assertEquals(before, frozen.motionPoints)
        assertEquals(789L, frozen.motionPoints.single().elapsedMs)
        assertEquals(140, frozen.hrPoints.single().bpm)
        assertEquals(130.0, frozen.record.summary.meanHr!!, 0.0)
        assertEquals(2L, frozen.record.summary.validHrCount)
        assertEquals(10L, frozen.record.summary.totalSteps)
        assertEquals(10 * 60_000.0 / 999, frozen.record.summary.meanCadence!!, 0.0)
        assertTrue(frozen.record.eligibleForSaving)
        assertTrue(frozen.hrPoints.all { it.sessionId == frozen.record.id })
        assertTrue(frozen.motionPoints.all { it.sessionId == frozen.record.id })
        assertEquals(0.0, f.detector.state.value.cadence!!, 0.0)
        f.now = 99_000; f.tick(); f.stop()
        cleanup.complete(Unit); runCurrent()
        assertSame(frozen, f.snapshot)
        assertEquals(1, f.freezes)
        assertEquals(SessionStatus.STOPPED, f.session.state.value.status)
    }

    @Test fun interruptionAndTerminationOfAllStreamsFreezeExistingPartialBucket() = runTest {
        for (reason in listOf<String?>(null, "Disconnected", "Foreground left")) {
            val f = Fixture(this)
            val end = CompletableDeferred<Unit>()
            f.start(flow { emit(samples()); end.await() }, heartRates = null); runCurrent()
            f.now = 6789; f.tick()
            val before = f.motionHistory.snapshot()
            f.now = 6999
            if (reason == null) { end.complete(Unit); runCurrent() } else f.session.stop(reason)
            val frozen = f.snapshot!!
            assertEquals(before, frozen.motionPoints)
            assertEquals(1789L, frozen.motionPoints.single().elapsedMs)
            assertEquals(1999L, frozen.record.durationMs)
            assertEquals(reason ?: "All streams ended.", frozen.record.endReason)
            assertEquals(reason != null, frozen.record.interrupted)
            f.now = 100_000; f.tick(); f.stop(); runCurrent()
            assertSame(frozen, f.snapshot)
            assertEquals(1, f.freezes)
        }
    }

    @Test fun retryAndNewStartRejectOldStreamAndTickWhileRetainedSnapshotStaysUnchanged() = runTest {
        val f = Fixture(this)
        lateinit var old: FlowCollector<PolarAccelerometerData>
        val fail = CompletableDeferred<Unit>()
        val source = object : Flow<PolarAccelerometerData> {
            override suspend fun collect(collector: FlowCollector<PolarAccelerometerData>) {
                old = collector; collector.emit(samples()); fail.await(); error("ACC failure")
            }
        }
        f.start(source); runCurrent()
        f.tick(); f.now = 6100; f.tick()
        val before = f.motionHistory.snapshot()
        val generation = f.session.state.value.generation
        assertFalse(f.start(source))
        assertFalse(f.accStream(source))
        // Reusing the retained owner does not alter the stored history.
        assertEquals(before, f.motionHistory.snapshot())
        f.now = 6200; fail.complete(Unit); runCurrent()
        val next = MutableSharedFlow<PolarAccelerometerData>()
        assertTrue(f.accStream(next)); runCurrent()
        assertEquals(before, f.motionHistory.snapshot())
        assertTrue(f.charts.snapshot(ChartKind.CADENCE, 1200).points.isEmpty())
        f.now = 6300; f.tick()
        assertNull(f.motionHistory.snapshot().last().cadence)
        next.emit(samples(8_000_000_000L)); runCurrent()
        f.now = 6400; f.tick()
        assertTrue(f.motionHistory.snapshot().last().breakBefore)
        assertEquals(before.first(), f.motionHistory.snapshot().first())
        val afterRetry = f.motionHistory.snapshot()
        val count = f.detector.totalSteps
        runCatching { old.emit(samples(30_000_000_000L)) }; runCurrent()
        assertEquals(count, f.detector.totalSteps)
        assertEquals(afterRetry, f.motionHistory.snapshot())
        f.stop(); runCurrent()
        val frozen = f.snapshot!!
        assertTrue(f.start(next)); runCurrent()
        assertSame(frozen, f.snapshot)
        assertTrue(f.motionHistory.snapshot().isEmpty())
        assertTrue(f.hrHistory.snapshot().isEmpty())
        assertNotEquals(frozen.record.id, f.session.state.value.record!!.id)
        f.now = 10_000; next.emit(samples()); runCurrent(); f.tick()
        val fresh = f.motionHistory.snapshot()
        assertEquals(0L, fresh.single().elapsedMs)
        runCatching { old.emit(samples(60_000_000_000L)) }; f.tick(generation); runCurrent()
        assertEquals(fresh, f.motionHistory.snapshot())
        assertEquals(afterRetry, frozen.motionPoints)
        assertSame(frozen, f.snapshot)
        f.stop()
    }

    @Test fun realAccGapThresholdAndDetectionResetBreakHistoryIncludingRecoveryBetweenTicks() = runTest {
        val f = Fixture(this)
        val source = MutableSharedFlow<PolarAccelerometerData>()
        f.start(source); runCurrent()
        source.emit(samples(count = 105, moving = false)); runCurrent(); f.tick()
        val segment = f.detector.segment
        f.now = 6000
        source.emit(samples(1_070_000_000L, 1, false)); runCurrent(); f.tick()
        assertEquals(segment, f.detector.segment)
        assertTrue(f.motionHistory.snapshot().last().breakBefore)
        assertNull(f.motionHistory.snapshot().last().cadence)
        source.emit(samples(1_101_000_000L, 1, false)); runCurrent()
        assertTrue(f.detector.isWarmingUp)
        f.now = 6250; f.tick()
        assertNull(f.motionHistory.snapshot().last().cadence)
        source.emit(samples(1_111_000_000L, 105, false)); runCurrent()
        f.now = 6500; f.tick()
        assertTrue(f.motionHistory.snapshot().last().breakBefore)
        f.now = 7000; f.tick()
        assertTrue(f.motionHistory.snapshot().last().breakBefore)
        assertNull(f.motionHistory.snapshot().last().cadence)
        // A whole gap and warm-up can occur between refreshes; segment identity retains the break.
        source.emit(samples(3_000_000_000L, 105, false)); runCurrent()
        f.now = 7250; f.tick()
        assertTrue(f.motionHistory.snapshot().last().breakBefore)
        source.emit(samples(4_050_000_000L)); runCurrent()
        f.now = 8000; f.tick()
        assertTrue(f.motionHistory.snapshot().last().breakBefore)
        assertNotNull(f.motionHistory.snapshot().last().cadence)
        f.now = 9000; f.tick()
        assertFalse(f.motionHistory.snapshot().last().breakBefore)
        val beforeExpiry = f.detector.segment
        source.emit(samples(10_090_000_000L, 301, false)); runCurrent()
        assertTrue(f.detector.segment > beforeExpiry)
        f.now = 9250; f.tick()
        assertTrue(f.motionHistory.snapshot().last().breakBefore)
        f.stop()
    }

    @Test fun twoSecondDisplayZeroIsRecordedWithoutChangingAccumulatedStatistics() = runTest {
        val f = Fixture(this)
        f.start(flow { emit(samples()); awaitCancellation() }); runCurrent(); f.tick()
        assertTrue(f.motionHistory.snapshot().last().cadence!! > 0)
        val previous = f.detector.state.value
        val segment = f.detector.segment
        f.now += 2000; f.tick()
        assertEquals(0.0, f.motionHistory.snapshot().last().cadence!!, 0.0)
        assertEquals(previous.totalSteps, f.detector.totalSteps)
        assertEquals(previous.maximumCadence, f.detector.state.value.maximumCadence)
        assertEquals(segment, f.detector.segment)
        f.stop()
    }

    @Test fun pauseResumeKeepsHistoryAndTotalsAndFreezesOnlyWhenFinallyStopped() = runTest {
        val f = Fixture(this)
        f.start(flow { emit(samples()); awaitCancellation() }); runCurrent()
        f.tick()
        f.now += 1000; f.tick()
        val id = f.session.state.value.record!!.id
        val before = f.detector.state.value
        val points = f.motionHistory.snapshot()
        f.session.pause(); runCurrent()
        assertNull(f.snapshot)
        assertEquals(0, f.freezes)
        f.now += 100_000; f.tick()
        assertEquals(points, f.motionHistory.snapshot())
        f.session.resume(true) {
            f.accStream(flow { emit(samples(startNs = 900_000_000_000, count = 40)); awaitCancellation() })
        }
        runCurrent(); f.now += 250; f.tick()
        assertEquals(id, f.session.state.value.record!!.id)
        assertEquals(before.totalSteps, f.detector.state.value.totalSteps)
        assertTrue(f.detector.isWarmingUp)
        assertEquals(points, f.motionHistory.snapshot())
        f.stop(); runCurrent()
        assertEquals(1, f.freezes)
        assertEquals(1250L, f.snapshot!!.record.durationMs)
        assertEquals(id, f.snapshot!!.record.id)
        assertEquals(f.motionHistory.snapshot(), f.snapshot!!.motionPoints)
    }

    @Test fun historiesKeepEarlyDataAndIndependentCountsAndFreezeAtSharedDeadline() = runTest {
        val f = Fixture(this)
        val hrs = MutableSharedFlow<Int>()
        f.start(flow { emit(samples()); awaitCancellation() }, hrs); runCurrent()
        hrs.emit(120); runCurrent()
        for (second in 0..375) { f.now = 5000 + second * 1000L; f.tick() }
        assertEquals(376, f.motionHistory.snapshot().size)
        assertEquals(1, f.hrHistory.snapshot().size)
        assertEquals(0L, f.motionHistory.snapshot().first().elapsedMs)
        assertTrue(f.charts.snapshot(ChartKind.CADENCE, 375_000).points.first().elapsedMs > 0)
        f.now = 5000 + HrHistory.MAX_ELAPSED_MS
        hrs.emit(130); runCurrent(); f.tick()
        assertEquals(375_000L, f.motionHistory.snapshot().last().elapsedMs)
        assertEquals(0L, f.hrHistory.snapshot().last().elapsedMs)
        val motion = f.motionHistory.snapshot()
        val hr = f.hrHistory.snapshot()
        f.now++; hrs.emit(140); runCurrent(); f.tick()
        assertEquals(motion, f.motionHistory.snapshot())
        assertEquals(hr, f.hrHistory.snapshot())
        assertEquals(1L, f.hr.statistics.value.count)
        assertEquals(SessionStatus.STOPPED, f.session.state.value.status)
        assertEquals(HrHistory.MAX_ELAPSED_MS, f.snapshot!!.record.durationMs)
        assertEquals("TIME_LIMIT", f.snapshot!!.record.endReason)
        f.stop()
    }

    @Test fun noDataAttemptFreezesEmptyIneligibleSnapshotAndHrOnlyRecordsUnknownMotion() = runTest {
        val f = Fixture(this)
        val source = MutableSharedFlow<PolarAccelerometerData>()
        f.start(source); runCurrent(); f.tick(); f.stop(); runCurrent()
        val empty = f.snapshot!!
        assertTrue(empty.hrPoints.isEmpty()); assertTrue(empty.motionPoints.isEmpty())
        assertFalse(empty.record.eligibleForSaving)
        val hrs = MutableSharedFlow<Int>()
        f.start(source, hrs); runCurrent(); hrs.emit(120); runCurrent()
        f.now += 250; f.tick(); f.stop(); runCurrent()
        val hrOnly = f.snapshot!!
        assertTrue(hrOnly.record.eligibleForSaving)
        assertNull(hrOnly.motionPoints.single().cadence)
        assertEquals(250L, hrOnly.motionPoints.single().elapsedMs)
        assertNull(hrOnly.record.summary.totalSteps)
        assertTrue(empty.motionPoints.isEmpty())
    }
}
