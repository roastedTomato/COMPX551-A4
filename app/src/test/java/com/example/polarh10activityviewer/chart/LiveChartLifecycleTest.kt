package com.example.polarh10activityviewer.chart

import com.example.polarh10activityviewer.ble.DataSubscriptions
import com.example.polarh10activityviewer.heartrate.HeartRateReading
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.session.SessionController
import com.example.polarh10activityviewer.session.SessionStatus
import com.example.polarh10activityviewer.session.SessionSummary

import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.HR
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
class LiveChartLifecycleTest {
    private class Fixture(scope: CoroutineScope) {
        var now = 0L
        val charts = LiveCharts { emptyList() }
        lateinit var session: SessionController
        val subscriptions = DataSubscriptions(scope) { type, status ->
            val eventTime = now
            charts.onSubscriptionState(type, status, session.elapsedAt(eventTime))
            session.onSubscriptionState(type, status, eventTime)
        }
        init {
            session = SessionController(subscriptions, { now }, charts::reset,
                { charts.stop(session.state.value.elapsedMs) }, { SessionSummary() })
        }
        fun startStream(source: Flow<Int>): Boolean {
            val generation = session.state.value.generation
            return subscriptions.start(HR, { session.accepts(generation) }, { session.accepts(generation) },
                { source }) { bpm ->
                if (bpm > 0) session.onValidData(now)
                session.refresh(generation, now)
                if (session.state.value.status == SessionStatus.RUNNING) {
                    charts.receiveHr(session.state.value.elapsedMs, if (bpm > 0) HeartRateReading(bpm) else null)
                }
            }
        }
        fun start(source: Flow<Int>) = session.start(true) { startStream(source) }
        fun tick(generation: Long = session.state.value.generation) {
            session.refresh(generation)
            if (session.accepts(generation)) charts.advance(session.state.value.elapsedMs)
        }
        fun snapshot() = charts.snapshot(ChartKind.HEART_RATE, session.elapsedAt())
    }

    @Test fun rejectedStartRetryAndOldSourceCannotChangeNewSessionChartOrTimeAxis() = runTest {
        val f = Fixture(this)
        lateinit var old: FlowCollector<Int>
        val source = object : Flow<Int> {
            override suspend fun collect(collector: FlowCollector<Int>) {
                old = collector
                collector.emit(120)
                awaitCancellation()
            }
        }
        f.start(source)
        runCurrent()
        val generation = f.session.state.value.generation
        f.now = 1000
        f.tick()
        val before = f.snapshot()
        assertFalse(f.start(source))
        assertFalse(f.startStream(source))
        assertEquals(before, f.snapshot())
        f.session.stop("End")
        runCurrent()
        val next = MutableSharedFlow<Int>()
        assertTrue(f.start(next))
        assertTrue(f.snapshot().points.isEmpty())
        runCurrent()
        f.now = 5000
        next.emit(130)
        runCurrent()
        val fresh = f.snapshot()
        assertEquals(0.0, fresh.points.single().elapsedMs, 0.0)
        runCatching { old.emit(160) }
        f.tick(generation)
        assertEquals(fresh, f.snapshot())
        f.now = 7000
        f.tick()
        assertEquals(2000.0, f.snapshot().endMs, 0.0)
        assertEquals(0.0, f.snapshot().points.single().elapsedMs, 0.0)
        f.session.stop("Done")
    }

    @Test fun endingFreezesAtEventTimeWithoutCleanupScrollOrTrailingZero() = runTest {
        for (reason in listOf("Stopped by user", "Disconnected", "Foreground left")) {
            val f = Fixture(this)
            val cleanup = CompletableDeferred<Unit>()
            f.start(flow {
                try { emit(120); awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
            })
            runCurrent()
            f.now = 1750
            f.session.stop(reason)
            runCurrent()
            val frozen = f.snapshot()
            assertEquals(1750.0, frozen.endMs, 0.0)
            assertEquals(listOf(120.0), frozen.points.map { it.value })
            f.now = 200_000
            f.tick()
            f.session.stop("Duplicate")
            cleanup.complete(Unit)
            runCurrent()
            val after = f.snapshot()
            assertEquals(frozen.points, after.points)
            assertEquals(frozen.endMs, after.endMs, 0.0)
            assertEquals(SessionStatus.STOPPED, f.session.state.value.status)
        }
    }

    @Test fun normalCompletionFreezesAndRebindingSelectionDoesNotRestartData() = runTest {
        val f = Fixture(this)
        val end = CompletableDeferred<Unit>()
        var starts = 0
        f.start(flow { starts++; emit(120); end.await() })
        runCurrent()
        f.charts.select(ChartKind.CADENCE)
        val retainedOwner = f.charts
        assertEquals(ChartKind.CADENCE, retainedOwner.selection.value)
        retainedOwner.select(ChartKind.HEART_RATE)
        f.now = 3500
        end.complete(Unit)
        runCurrent()
        val frozen = f.snapshot()
        assertEquals(SubscriptionStatus.STOPPED, frozen.status)
        assertEquals(3500.0, frozen.endMs, 0.0)
        f.now = 100_000
        repeat(10) { retainedOwner.snapshot(ChartKind.HEART_RATE, f.session.elapsedAt()) }
        assertEquals(frozen, f.snapshot())
        assertEquals(1, starts)
    }
}
