package com.example.polarh10activityviewer.motion

import com.example.polarh10activityviewer.ble.DataSubscriptions
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.sensor.AccSampleProcessor
import com.example.polarh10activityviewer.sensor.AccSample
import com.example.polarh10activityviewer.session.SessionController
import com.example.polarh10activityviewer.session.SessionSummary
import com.example.polarh10activityviewer.session.SessionStatus

import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.ACC
import com.polar.sdk.api.model.PolarAccelerometerData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StepDetectorTest {
    private fun raw(index: Int, x: Int) = AccSample(index * 10_000_000L, x, 0, 0)
    private fun walking() = (0..303).map { index ->
        raw(index, if (index < 104) 1000 else when ((index - 104) % 50) {
            in 0..9 -> 1800
            in 10..19 -> 800
            else -> 1000
        })
    }

    @Test fun normalWarmupAndConfirmationStayUnknownThenCadenceUsesConfirmedPeaks() {
        val detector = StepDetector { 0L }
        detector.reset()
        detector.onSubscriptionState(SubscriptionStatus.STARTING)
        assertTrue(detector.isWarmingUp)
        assertNull(detector.state.value.cadence)
        walking().take(103).forEach(detector::receive)
        assertTrue(detector.isWarmingUp)
        detector.receive(walking()[103])
        assertFalse(detector.isWarmingUp)
        detector.receivedBatch(1_030_000_000L, 0)
        assertEquals(0L, detector.state.value.totalSteps)
        assertNull(detector.state.value.cadence)
        assertTrue(detector.state.value.cadencePending)
        walking().drop(104).forEach(detector::receive)
        detector.receivedBatch(3_030_000_000L, 0)
        assertEquals(4L, detector.state.value.totalSteps)
        assertEquals(120.0, detector.state.value.cadence!!, 1e-10)
        assertFalse(detector.state.value.cadencePending)
    }

    @Test fun initialQuietZeroRequiresFiveSecondsOfRealPostWarmupSamples() {
        var now = 0L
        val detector = StepDetector { now }
        detector.onSubscriptionState(SubscriptionStatus.STARTING)
        (0..103).forEach { detector.receive(raw(it, 1000)) }
        detector.receivedBatch(1_030_000_000L, now)
        assertNull(detector.state.value.cadence)
        now = 20_000; detector.refresh()
        assertNull(detector.state.value.cadence)
        (104..602).forEach { detector.receive(raw(it, 1000)) }
        detector.receivedBatch(6_020_000_000L, now)
        assertNull(detector.state.value.cadence)
        detector.receive(raw(603, 1000))
        detector.receivedBatch(6_030_000_000L, now)
        assertEquals(0.0, detector.state.value.cadence!!, 0.0)
        assertFalse(detector.state.value.cadencePending)
        assertEquals(0L, detector.totalSteps)
    }

    @Test fun quarterSecondRefreshZerosCadenceWithoutResettingDetectorOrChangingPeaks() = runTest {
        val detector = StepDetector { testScheduler.currentTime }
        detector.onSubscriptionState(SubscriptionStatus.STARTING)
        walking().forEach(detector::receive)
        detector.receivedBatch(3_030_000_000L, 0)
        val segment = detector.segment
        assertFalse(detector.isWarmingUp)
        var ticks = 0
        val ticker = launch {
            while (true) { detector.refresh(); ticks++; delay(250) }
        }
        runCurrent()
        advanceTimeBy(1500)
        runCurrent()
        assertTrue(detector.state.value.cadence!! > 0)
        assertEquals(7, ticks)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(0.0, detector.state.value.cadence!!, 0.0)
        assertEquals(4L, detector.totalSteps)
        assertEquals(segment, detector.segment)
        assertFalse(detector.isWarmingUp)
        // A new batch reanchors display time without losing peaks to the earlier estimate.
        detector.receive(raw(304, 1000))
        detector.receivedBatch(3_040_000_000L, testScheduler.currentTime)
        assertTrue(detector.state.value.cadence!! > 0)
        ticker.cancel()
    }

    @Test fun gapAndRetryShowMissingUntilRewarmedAndRetainTotals() {
        var now = 0L
        val detector = StepDetector { now }
        detector.onSubscriptionState(SubscriptionStatus.STARTING)
        walking().forEach(detector::receive)
        detector.receivedBatch(3_030_000_000L, now)
        detector.receive(raw(308, 1000).copy(gapBeforeNs = 50_000_000L))
        detector.receivedBatch(3_080_000_000L, now)
        assertTrue(detector.isWarmingUp)
        assertNull(detector.state.value.cadence)
        now = 50_000
        detector.refresh()
        assertNull(detector.state.value.cadence)
        assertEquals(4L, detector.state.value.totalSteps)
        for (i in 309..411) detector.receive(raw(i, 1000))
        detector.receivedBatch(4_110_000_000L, now)
        assertFalse(detector.isWarmingUp)
        assertNull(detector.state.value.cadence)
        detector.onSubscriptionState(SubscriptionStatus.FAILED)
        detector.refresh()
        assertNull(detector.state.value.cadence)
        detector.onSubscriptionState(SubscriptionStatus.STARTING)
        detector.refresh()
        assertTrue(detector.isWarmingUp)
        assertNull(detector.state.value.cadence)
        assertEquals(4L, detector.state.value.totalSteps)
        walking().forEach(detector::receive)
        detector.receivedBatch(3_030_000_000L, now)
        assertFalse(detector.isWarmingUp)
        assertEquals(8L, detector.state.value.totalSteps)
        assertEquals(120.0, detector.state.value.cadence!!, 1e-10)
    }

    @Test fun stoppedWithNoAccRemainsMissingButObservedSessionShowsZeroAndNewStartResets() {
        val detector = StepDetector { 0L }
        detector.reset()
        detector.onSubscriptionState(SubscriptionStatus.IDLE)
        assertNull(detector.state.value.cadence)
        detector.stop()
        assertNull(detector.state.value.cadence)
        assertNull(detector.state.value.totalSteps)
        detector.reset()
        detector.onSubscriptionState(SubscriptionStatus.STARTING)
        walking().forEach(detector::receive)
        detector.receivedBatch(3_030_000_000L, 0)
        detector.stop()
        detector.refresh()
        assertEquals(4L, detector.state.value.totalSteps)
        assertEquals(0.0, detector.state.value.cadence!!, 0.0)
        detector.reset()
        assertNull(detector.state.value.totalSteps)
        assertEquals(0L, detector.totalSteps)
        detector.onSubscriptionState(SubscriptionStatus.STARTING)
        assertNull(detector.state.value.cadence)
    }

    @Test fun sessionStopOverridesAccCleanupAndOldSessionTicksCannotChangeNewDisplay() = runTest {
        var now = 0L
        val detector = StepDetector { now }
        lateinit var session: SessionController
        val subscriptions = DataSubscriptions(this) { type, status ->
            if (type == ACC && session.state.value.ongoing) detector.onSubscriptionState(status)
            session.onSubscriptionState(type, status)
        }
        session = SessionController(subscriptions, { now }, detector::reset, {
            detector.updateSessionTime(session.state.value.elapsedMs)
            detector.stop()
        }, { SessionSummary() })
        fun start(): Boolean = session.start(true) {
            detector.onSubscriptionState(SubscriptionStatus.STARTING)
            val generation = session.state.value.generation
            subscriptions.start(ACC, { session.accepts(generation) }, { session.accepts(generation) },
                { flow { emit(walking()); awaitCancellation() } }, { samples ->
                    samples.forEach(detector::receive)
                    detector.receivedBatch(samples.last().timeStamp, now)
                    session.onValidData()
                })
        }
        assertTrue(start())
        runCurrent()
        val previousGeneration = session.state.value.generation
        assertFalse(start())
        assertEquals(4L, detector.state.value.totalSteps)
        assertNull(detector.state.value.meanCadence)
        now = 1000
        session.stop("User stopped")
        assertEquals(240.0, detector.state.value.meanCadence!!, 1e-10)
        assertEquals(0.0, detector.state.value.cadence!!, 0.0)
        val frozen = detector.state.value
        now = 2000
        session.stop("Duplicate stop")
        runCurrent()
        assertEquals(frozen, detector.state.value)
        now = 20_000
        assertTrue(start())
        runCurrent()
        val newDisplay = detector.state.value
        assertEquals(4L, newDisplay.totalSteps)
        assertNull(newDisplay.meanCadence)
        now += 30_000
        session.refresh(previousGeneration)
        if (session.accepts(previousGeneration)) {
            detector.updateSessionTime(session.state.value.elapsedMs)
            detector.refresh()
        }
        assertEquals(newDisplay, detector.state.value)
        session.stop("Done")
        runCurrent()
    }

    @Test fun realProcessingPathCommitsFourOriginalPeakTimesAcrossBatches() {
        val detector = StepDetector()
        val commits = mutableListOf<List<StepCandidate>>()
        val buffer = AccSampleProcessor { sample ->
            val result = detector.receive(sample)
            if (result.isNotEmpty()) commits += result
        }
        walking().chunked(37).forEach { chunk ->
            buffer.receive(PolarAccelerometerData(chunk.map {
                PolarAccelerometerData.PolarAccelerometerDataSample(it.timeStamp, it.x, it.y, it.z)
            }))
        }
        assertEquals(1, commits.size)
        assertEquals(listOf(108, 158, 208, 258).map { it * 10_000_000L }, commits.single().map { it.timeStamp })
        assertEquals(4L, detector.totalSteps)
    }

    @Test fun sensorTimeoutRewarmsOnceAndPreservesTotalWithoutAnyUiTimer() {
        val detector = StepDetector()
        val committed = walking().flatMap(detector::receive)
        val lastPeak = committed.last().timeStamp
        val exactIndex = ((lastPeak + 2_000_000_000L) / 10_000_000L).toInt()
        for (i in 304..exactIndex) detector.receive(raw(i, 1000))
        assertFalse(detector.isWarmingUp)
        detector.receive(raw(exactIndex + 1, 1000))
        assertTrue(detector.isWarmingUp)
        assertEquals(4L, detector.totalSteps)
        for (i in exactIndex + 2..exactIndex + 103) detector.receive(raw(i, 1000))
        assertTrue(detector.isWarmingUp)
        detector.receive(raw(exactIndex + 104, 1000))
        assertFalse(detector.isWarmingUp)
        detector.receive(raw(exactIndex + 105, 1000))
        assertFalse(detector.isWarmingUp)
        assertEquals(4L, detector.totalSteps)
    }

    @Test fun gapDropsPendingSequenceAndRewarmsBeforeConfirmingAgain() {
        val detector = StepDetector()
        walking().take(230).forEach { detector.receive(it) }
        assertEquals(0L, detector.totalSteps)
        detector.receive(raw(234, 1000).copy(gapBeforeNs = 50_000_000L))
        assertTrue(detector.isWarmingUp)
        walking().forEach { detector.receive(it.copy(timeStamp = it.timeStamp + 2_350_000_000L)) }
        assertEquals(4L, detector.totalSteps)
        detector.clearSegment()
        assertEquals(4L, detector.totalSteps)
        assertTrue(detector.isWarmingUp)
        detector.reset()
        assertTrue(detector.isWarmingUp)
        assertEquals(0L, detector.totalSteps)
    }

    @Test fun subscriptionStopRetryAndStaleSourcePreserveOnlyCommittedTotals() = runTest {
        val detector = StepDetector()
        val buffer = AccSampleProcessor { detector.receive(it) }
        val subscriptions = DataSubscriptions(this) { type, status ->
            buffer.onSubscriptionState(type, status)
            if (type == ACC && status != SubscriptionStatus.RECEIVING) detector.clearSegment()
        }
        val source = MutableSharedFlow<PolarAccelerometerData>()
        fun start(input: MutableSharedFlow<PolarAccelerometerData>) =
            subscriptions.start(ACC, { true }, { true }, { input }, buffer::receive)
        val batch = PolarAccelerometerData(walking().map {
            PolarAccelerometerData.PolarAccelerometerDataSample(it.timeStamp, it.x, it.y, it.z)
        })
        assertTrue(start(source))
        runCurrent()
        source.emit(batch)
        runCurrent()
        detector.receivedBatch(batch.samples.last().timeStamp, 0)
        assertEquals(4L, detector.totalSteps)
        assertFalse(start(source))
        assertFalse(detector.isWarmingUp)
        subscriptions.stop(ACC)
        assertTrue(detector.isWarmingUp)
        runCurrent()
        val retry = MutableSharedFlow<PolarAccelerometerData>()
        assertTrue(start(retry))
        runCurrent()
        source.emit(batch)
        assertTrue(detector.isWarmingUp)
        assertEquals(4L, detector.totalSteps)
        retry.emit(batch)
        runCurrent()
        detector.receivedBatch(batch.samples.last().timeStamp, 0)
        assertEquals(8L, detector.totalSteps)
        subscriptions.stop(ACC)
        runCurrent()
    }

    @Test fun streamCompletionAndOverallInterruptionFreezeAverageBeforeCleanup() = runTest {
        for (complete in listOf(false, true)) {
            var now = 0L
            val detector = StepDetector { now }
            lateinit var session: SessionController
            val subscriptions = DataSubscriptions(this) { type, status ->
                if (type == ACC && session.state.value.ongoing) detector.onSubscriptionState(status)
                session.onSubscriptionState(type, status)
            }
            session = SessionController(subscriptions, { now }, detector::reset, {
                detector.updateSessionTime(session.state.value.elapsedMs)
                detector.stop()
                now += 5000 // Cleanup must not enter the settled Running duration.
            }, { SessionSummary() })
            assertTrue(session.start(true) {
                val generation = session.state.value.generation
                subscriptions.start(ACC, { session.accepts(generation) }, { session.accepts(generation) },
                    { flow {
                        emit(walking())
                        now = 10_000
                        if (!complete) awaitCancellation()
                    } }, { samples ->
                        samples.forEach(detector::receive)
                        detector.receivedBatch(samples.last().timeStamp, now)
                        session.onValidData()
                    })
            })
            runCurrent()
            if (!complete) session.stop("Connection interrupted")
            runCurrent()
            val frozen = detector.state.value
            assertEquals(SessionStatus.STOPPED, session.state.value.status)
            assertEquals(10_000L, frozen.durationMs)
            assertEquals(frozen.totalSteps!! * 6.0, frozen.meanCadence!!, 1e-10)
            now += 60_000
            session.stop("Repeated end")
            session.onSubscriptionState(ACC, SubscriptionStatus.STOPPED)
            session.refresh(session.state.value.generation)
            detector.refresh()
            assertEquals(frozen, detector.state.value)
        }
    }
}
