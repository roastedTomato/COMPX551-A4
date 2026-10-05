package com.example.polarh10activityviewer.chart

import com.example.polarh10activityviewer.heartrate.HeartRateReading
import com.example.polarh10activityviewer.ble.SubscriptionStatus.*
import com.example.polarh10activityviewer.history.HrHistory
import com.example.polarh10activityviewer.history.MotionHistory
import com.example.polarh10activityviewer.motion.StepState
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.*
import org.junit.Assert.*
import org.junit.Test

class PauseChartContinuityTest {
    private class Fixture {
        val live = LiveCharts { emptyList() }
        val hr = HrHistory().apply { reset("pause") }
        val motion = MotionHistory().apply { reset("pause") }
        init {
            live.onSubscriptionState(HR, STARTING, 0)
            live.onSubscriptionState(ACC, STARTING, 0)
            motion.onSubscriptionState(RECEIVING)
            heart(1000); cadence(1000); heart(2000); cadence(2000)
        }
        fun heart(time: Long, value: Int? = 120) {
            val reading = value?.let { HeartRateReading(it) }
            live.receiveHr(time, reading); hr.receive(time, reading)
        }
        fun cadence(time: Long, warming: Boolean = false, segment: Long = 1) {
            val value = StepState(receivedAcc = true, cadence = 120.0)
            live.recordMotion(time, value, warming, segment)
            motion.record(time, value, warming, segment)
        }
        fun pauseAndResume(connect: Boolean = true) {
            hr.stop(); motion.stop(); live.stop(2200)
            hr.onSubscriptionState(STOPPED); motion.onSubscriptionState(STOPPED)
            hr.resume(connect); motion.resume(connect); live.resume(connect, connect)
            hr.onSubscriptionState(STARTING); motion.onSubscriptionState(STARTING, 2)
            live.onSubscriptionState(HR, STARTING, 2200)
            live.onSubscriptionState(ACC, STARTING, 2200, 2)
            motion.onSubscriptionState(RECEIVING)
        }
        fun assertHeartBreak(expected: Boolean, time: Long) {
            assertEquals(expected, hr.snapshot().last().breakBefore)
            assertEquals(expected, live.snapshot(ChartKind.HEART_RATE, time).points.last().breakBefore)
        }
        fun assertMotionBreak(expected: Boolean, time: Long) {
            assertEquals(expected, motion.snapshot().last().breakBefore)
            assertEquals(expected, live.snapshot(ChartKind.CADENCE, time).points.last().breakBefore)
        }
    }

    @Test fun pauseConnectsRealReadingsAndOmitsOnlyResumeWarmupWithoutPadding() {
        val f = Fixture()
        val before = f.motion.snapshot()
        f.pauseAndResume()
        f.heart(2400, 130)
        f.cadence(2500, warming = true, segment = 2)
        f.cadence(3500, warming = true, segment = 2)
        assertEquals(before, f.motion.snapshot())
        assertEquals(2, f.live.snapshot(ChartKind.CADENCE, 3500).points.size)
        f.cadence(4500, segment = 2)
        f.assertHeartBreak(false, 4500); f.assertMotionBreak(false, 4500)
        assertEquals(listOf(1000L, 2000L, 4500L), f.motion.snapshot().map { it.elapsedMs })
        assertEquals(1, chartSegments(f.live.snapshot(ChartKind.CADENCE, 4500).points).size)
    }

    @Test fun disconnectOrFailedStreamAtPauseKeepsBothBoundariesBroken() {
        val f = Fixture()
        f.pauseAndResume(connect = false)
        f.heart(3000); f.cadence(3000, segment = 2)
        f.assertHeartBreak(true, 3000); f.assertMotionBreak(true, 3000)
    }

    @Test fun resumeStepConfirmationStaysPendingWithoutBreakingThePauseConnection() {
        val f = Fixture()
        f.pauseAndResume()
        val pending = StepState(receivedAcc = true, cadencePending = true)
        f.live.recordMotion(3000, pending, false, 2)
        f.motion.record(3000, pending, false, 2)
        assertEquals(2, f.motion.snapshot().size)
        assertTrue(f.live.snapshot(ChartKind.CADENCE, 3000).detectingSteps)
        f.cadence(4500, segment = 2)
        f.assertMotionBreak(false, 4500)
    }

    @Test fun invalidHeartSamplesBeforeOrAfterPauseRemainGaps() {
        for (before in listOf(true, false)) {
            val f = Fixture()
            if (before) f.heart(2100, null)
            f.pauseAndResume()
            if (!before) f.heart(2300, null)
            f.heart(3000)
            f.assertHeartBreak(true, 3000)
        }
    }

    @Test fun activeTimeHeartGapStillBreaksAfterResume() {
        val f = Fixture()
        f.pauseAndResume()
        f.heart(5001)
        f.assertHeartBreak(true, 5001)
    }

    @Test fun newAccGapDuringResumeWarmupCancelsTheConnection() {
        val f = Fixture()
        f.pauseAndResume()
        f.cadence(2500, warming = true, segment = 2)
        f.cadence(3000, warming = true, segment = 3)
        f.cadence(4500, segment = 3)
        f.assertMotionBreak(true, 4500)
    }

    @Test fun accGapBeforeFirstRefreshAfterResumeStillBreaks() {
        val f = Fixture()
        f.pauseAndResume()
        f.cadence(3000, segment = 3)
        f.assertMotionBreak(true, 3000)
    }

    @Test fun subscriptionFailureDuringResumeStillBreaksAndRetryKeepsHistory() {
        val f = Fixture()
        f.pauseAndResume()
        f.hr.onSubscriptionState(FAILED); f.motion.onSubscriptionState(FAILED)
        f.live.onSubscriptionState(HR, FAILED, 2300); f.live.onSubscriptionState(ACC, FAILED, 2300)
        f.hr.onSubscriptionState(STARTING); f.motion.onSubscriptionState(STARTING)
        f.live.onSubscriptionState(HR, STARTING, 2400); f.live.onSubscriptionState(ACC, STARTING, 2400)
        f.motion.onSubscriptionState(RECEIVING)
        f.heart(3000); f.cadence(3000, segment = 2)
        f.assertHeartBreak(true, 3000); f.assertMotionBreak(true, 3000)
        assertEquals(3, f.hr.snapshot().size); assertEquals(3, f.motion.snapshot().size)
    }

    @Test fun normalAccWarmupAndLaterSegmentChangesStillBreak() {
        val f = Fixture()
        f.pauseAndResume()
        f.cadence(3000, segment = 2)
        f.assertMotionBreak(false, 3000)
        f.cadence(4000, warming = true, segment = 2)
        f.cadence(5000, segment = 2)
        f.assertMotionBreak(true, 5000)
        f.cadence(6000, segment = 3)
        f.assertMotionBreak(true, 6000)
    }
}
