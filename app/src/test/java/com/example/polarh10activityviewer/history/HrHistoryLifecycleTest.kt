package com.example.polarh10activityviewer.history

import com.example.polarh10activityviewer.ble.DataSubscriptions
import com.example.polarh10activityviewer.heartrate.LatestHeartRate
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.chart.ChartKind
import com.example.polarh10activityviewer.chart.LiveCharts
import com.example.polarh10activityviewer.heartrate.HeartRateZones
import com.example.polarh10activityviewer.motion.StepState
import com.example.polarh10activityviewer.session.SessionController
import com.example.polarh10activityviewer.session.SessionStatus
import com.example.polarh10activityviewer.session.SessionSummary
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.HR
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.ACC
import com.polar.sdk.api.model.PolarHrData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HrHistoryLifecycleTest {
    private fun batch(vararg bpms: Int) = PolarHrData(bpms.map {
        PolarHrData.PolarHrSample(it, 0, 0, emptyList(), emptyList(), false, true, true)
    })

    private class Fixture(scope: CoroutineScope) {
        var now = 0L
        var wall = 1_000_000L
        val hr = LatestHeartRate()
        val zones = HeartRateZones()
        val history = HrHistory()
        val charts = LiveCharts { emptyList() }
        lateinit var session: SessionController
        val subscriptions = DataSubscriptions(scope) { type, status ->
            if (type == HR) history.onSubscriptionState(status)
            charts.onSubscriptionState(type, status, session.elapsedAt(now))
            if (type == HR && status != SubscriptionStatus.RECEIVING && session.state.value.ongoing) {
                zones.clearCurrent(session.elapsedAt(now))
            }
            hr.onSubscriptionState(type, status)
            session.onSubscriptionState(type, status, now)
        }
        init {
            session = SessionController(subscriptions, { now }, {
                history.reset(session.state.value.record!!.id)
                charts.reset(); zones.reset(); hr.reset()
            }, {
                history.stop()
                charts.stop(session.state.value.elapsedMs)
                zones.clearCurrent(session.state.value.elapsedMs)
                hr.clear()
            }, { elapsed ->
                zones.refresh(elapsed)
                SessionSummary.from(hr.statistics.value, zones.state.value, StepState())
            }, { wall })
        }
        fun hrStream(source: Flow<PolarHrData>): Boolean {
            val generation = session.state.value.generation
            return subscriptions.start(HR, { session.accepts(generation) }, { session.accepts(generation) },
                { source.filter { it.samples.isNotEmpty() } }) { data ->
                if (session.checkTimeLimit(now) || !session.accepts(generation)) return@start
                val valid = hr.receive(data)
                if (valid) session.onValidData(now, wall)
                zones.receive(hr.reading.value, valid, session.elapsedAt(now))
                session.refresh(generation, now)
                if (session.state.value.status == SessionStatus.RUNNING) {
                    charts.receiveHr(session.state.value.elapsedMs, hr.reading.value)
                    history.receive(session.state.value.elapsedMs, hr.reading.value)
                }
            }
        }
        fun start(source: Flow<PolarHrData>, accFirst: Boolean = false) = session.start(true) {
            if (accFirst) {
                val generation = session.state.value.generation
                subscriptions.start(ACC, { true }, { session.accepts(generation) },
                    { flow { emit(1); awaitCancellation() } }, { session.onValidData(now, wall) })
            }
            hrStream(source)
        }
        fun tick(generation: Long = session.state.value.generation) {
            session.refresh(generation, now)
            if (session.accepts(generation)) charts.advance(session.elapsedAt(now))
        }
        fun stop() = session.stop("Stopped by user.", interrupted = false)
    }

    @Test fun batchesUseFinalValidityWhileStatisticsKeepAllValidSamples() = runTest {
        val f = Fixture(this)
        val source = MutableSharedFlow<PolarHrData>()
        f.start(source); runCurrent()
        source.emit(batch()); source.emit(batch(0)); runCurrent()
        assertEquals(SessionStatus.STARTING, f.session.state.value.status)
        assertEquals(0, f.history.snapshot().size)
        f.now = 5000
        source.emit(batch(100, 120, 0)); runCurrent()
        assertNull(f.history.snapshot().single().bpm)
        assertEquals(0L, f.history.snapshot().single().elapsedMs)
        assertEquals(2L, f.hr.statistics.value.count)
        f.now = 5400
        source.emit(batch(130, 140)); runCurrent()
        assertEquals(1, f.history.snapshot().size)
        assertEquals(140, f.history.snapshot().single().bpm)
        assertEquals(400L, f.history.snapshot().single().elapsedMs)
        assertTrue(f.history.snapshot().single().breakBefore)
        assertEquals(122.5, f.hr.statistics.value.average!!, 0.0)
        f.now = 6500
        source.emit(batch(150).copy(samples = batch(150).samples.map { it.copy(contactStatus = false) }))
        runCurrent()
        assertNull(f.history.snapshot().last().bpm)
        assertEquals(4L, f.hr.statistics.value.count)
        assertEquals(1100L, f.zones.state.value.durationsMs.sum())
        f.stop()
    }

    @Test fun accCanStartAxisAndTicksEmptyBatchesAndDateChangesCannotInventHrPoints() = runTest {
        val f = Fixture(this)
        val source = MutableSharedFlow<PolarHrData>()
        f.start(source, accFirst = true); runCurrent()
        assertEquals(SessionStatus.RUNNING, f.session.state.value.status)
        f.now = 2000; f.wall = -500
        source.emit(batch(0)); runCurrent()
        assertEquals(2000L, f.history.snapshot().single().elapsedMs)
        f.now = 3600; source.emit(batch(120)); runCurrent()
        f.now = 3700; source.emit(batch(120)); runCurrent()
        assertEquals(3700L, f.history.snapshot().last().elapsedMs)
        val before = f.history.snapshot()
        f.now = 100_000; f.wall = 9_999_999
        repeat(10) { f.tick(); source.emit(batch()) }; runCurrent()
        assertEquals(before, f.history.snapshot())
        f.stop()
    }

    @Test fun historyRetainsEarlierPointsBeyondLiveWindowEvenWhenOtherChartIsSelected() = runTest {
        val f = Fixture(this)
        val source = MutableSharedFlow<PolarHrData>()
        f.start(source); runCurrent()
        f.charts.select(ChartKind.CADENCE)
        for (second in 0..375) {
            f.now = second * 1000L
            source.emit(batch(120)); runCurrent()
        }
        assertEquals(376, f.history.snapshot().size)
        assertEquals(0L, f.history.snapshot().first().elapsedMs)
        assertEquals(375_000L, f.history.snapshot().last().elapsedMs)
        val live = f.charts.snapshot(ChartKind.HEART_RATE, 375_000)
        assertTrue(live.points.size <= 301)
        assertTrue(live.points.first().elapsedMs > 0)
        f.stop()
    }

    @Test fun retryKeepsHistoryAndSessionAxisButClearsLiveCacheAndRejectsOldSubscription() = runTest {
        val f = Fixture(this)
        lateinit var old: FlowCollector<PolarHrData>
        val fail = CompletableDeferred<Unit>()
        f.start(object : Flow<PolarHrData> {
            override suspend fun collect(collector: FlowCollector<PolarHrData>) {
                old = collector
                collector.emit(batch(120))
                fail.await()
                error("HR failure")
            }
        }, accFirst = true); runCurrent()
        f.now = 1100; old.emit(batch(125)); runCurrent()
        f.now = 1200; fail.complete(Unit); runCurrent()
        val before = f.history.snapshot()
        assertEquals(2, before.size)
        assertFalse(f.history.frozen)
        val source = MutableSharedFlow<PolarHrData>()
        assertTrue(f.hrStream(source)); runCurrent()
        assertEquals(before, f.history.snapshot())
        assertTrue(f.charts.snapshot(ChartKind.HEART_RATE, 1200).points.isEmpty())
        f.now = 1400; source.emit(batch(130)); runCurrent()
        assertEquals(2, f.history.snapshot().size)
        assertEquals(1400L, f.history.snapshot().last().elapsedMs)
        assertTrue(f.history.snapshot().last().breakBefore)
        val afterRetry = f.history.snapshot()
        runCatching { old.emit(batch(199)) }; runCurrent()
        assertEquals(afterRetry, f.history.snapshot())
        assertEquals(3L, f.hr.statistics.value.count)
        f.stop()
    }

    @Test fun everyEndingFreezesPartialSecondWithoutCleanupOrSyntheticEndpoint() = runTest {
        for (reason in listOf<String?>(null, "Stopped by user.", "Disconnected", "Foreground left")) {
            val f = Fixture(this)
            val end = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            f.start(flow {
                try {
                    emit(batch(120))
                    f.now = 1789
                    emit(batch(125))
                    end.await()
                } finally { withContext(NonCancellable) { cleanup.await() } }
            }); runCurrent()
            f.now = 1999
            if (reason == null) { cleanup.complete(Unit); end.complete(Unit); runCurrent() }
            else { f.session.stop(reason); runCurrent() }
            val frozen = f.history.snapshot()
            assertTrue(f.history.frozen)
            assertEquals(listOf(0L, 1789L), frozen.map { it.elapsedMs })
            f.now = 99_000; f.tick(); f.stop()
            cleanup.complete(Unit); runCurrent()
            assertEquals(frozen, f.history.snapshot())
            assertEquals(1789L, f.history.snapshot().lastOrNull()?.elapsedMs)
        }
    }

    @Test fun rejectedStartsRetainedOwnerAndNewSessionHaveIndependentHistories() = runTest {
        val f = Fixture(this)
        lateinit var old: FlowCollector<PolarHrData>
        val source = object : Flow<PolarHrData> {
            override suspend fun collect(collector: FlowCollector<PolarHrData>) {
                old = collector
                collector.emit(batch(120))
                awaitCancellation()
            }
        }
        f.start(source); runCurrent()
        val generation = f.session.state.value.generation
        val before = f.history.snapshot()
        assertFalse(f.start(source))
        assertFalse(f.hrStream(source))
        // Reusing the retained manager does not create a new history owner.
        assertEquals(before, f.history.snapshot())
        f.stop(); runCurrent()
        val oldSnapshot = f.history.snapshot()
        val next = MutableSharedFlow<PolarHrData>()
        assertTrue(f.start(next)); runCurrent()
        assertTrue(f.history.snapshot().isEmpty())
        assertNotEquals(before.first().sessionId, f.history.sessionId)
        f.now = 10_000; next.emit(batch(140)); runCurrent()
        val fresh = f.history.snapshot()
        assertEquals(0L, fresh.single().elapsedMs)
        runCatching { old.emit(batch(199)) }; f.tick(generation); runCurrent()
        assertEquals(fresh, f.history.snapshot())
        assertEquals(120, oldSnapshot.single().bpm)
        f.stop()
    }

    @Test fun fourHourCutoffNowEndsSessionBeforeAcceptingBoundaryAndLateData() = runTest {
        val f = Fixture(this)
        val source = MutableSharedFlow<PolarHrData>()
        f.start(source); runCurrent()
        source.emit(batch(120)); runCurrent()
        f.now = HrHistory.MAX_ELAPSED_MS
        source.emit(batch(140)); runCurrent()
        val atLimit = f.history.snapshot()
        f.now++
        source.emit(batch(160)); runCurrent()
        assertEquals(atLimit, f.history.snapshot())
        assertEquals(1L, f.hr.statistics.value.count)
        assertEquals(120.0, f.hr.statistics.value.average!!, 0.0)
        assertEquals(SessionStatus.STOPPED, f.session.state.value.status)
        assertTrue(f.history.frozen)
        assertEquals("TIME_LIMIT", f.session.state.value.endReason)
        f.stop()
    }
}
