package com.example.polarh10activityviewer.chart

import com.example.polarh10activityviewer.ble.checkedDataTypes
import com.example.polarh10activityviewer.heartrate.HeartRateReading
import com.example.polarh10activityviewer.heartrate.HeartRateStatistics
import com.example.polarh10activityviewer.heartrate.LatestHeartRate
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.heartrate.HeartRateZones
import com.example.polarh10activityviewer.motion.StepDetector
import com.example.polarh10activityviewer.motion.StepState
import com.example.polarh10activityviewer.sensor.AccSampleProcessor
import com.example.polarh10activityviewer.sensor.AccSample
import com.example.polarh10activityviewer.sensor.EcgBuffer

import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.*
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarHrData
import org.junit.Assert.*
import org.junit.Test

class LiveChartsTest {
    @Test fun cadenceSamplesEveryQuarterSecondAndKeepsSkippedGaps() {
        val charts = running()
        charts.recordMotion(0, motion(100.0), false, 1)
        charts.recordMotion(125, motion(110.0), false, 1)
        charts.recordMotion(249, motion(115.0), false, 1)
        assertEquals(1, charts.snapshot(ChartKind.CADENCE, 249).points.size)
        charts.recordMotion(250, motion(120.0), false, 1)
        charts.recordMotion(375, StepState(), false, 1)
        charts.recordMotion(500, motion(130.0), false, 1)
        val points = charts.snapshot(ChartKind.CADENCE, 500).points
        assertEquals(listOf(0.0, 250.0, 500.0), points.map { it.elapsedMs })
        assertEquals(listOf(100.0, 120.0, 130.0), points.map { it.value })
        assertEquals(listOf(true, false, true), points.map { it.breakBefore })
    }

    @Test fun skippedGapBeforePauseMustNotReconnectOnResume() {
        val charts = running()
        charts.recordMotion(0, motion(), false, 1)
        charts.recordMotion(125, StepState(), false, 1)
        charts.stop(125); charts.resume()
        charts.onSubscriptionState(ACC, SubscriptionStatus.STARTING, 125, 2)
        charts.recordMotion(250, motion(), false, 2)
        assertTrue(charts.snapshot(ChartKind.CADENCE, 250).points.last().breakBefore)
    }

    @Test fun resumePreservesFiveMinuteHrAndCadenceAndConnectsBothSegments() {
        val charts = running()
        charts.receiveHr(1000, reading(110))
        charts.recordMotion(1000, motion(), false, 1)
        charts.stop(2000)
        charts.resume()
        charts.onSubscriptionState(HR, SubscriptionStatus.STARTING, 2000)
        charts.onSubscriptionState(ACC, SubscriptionStatus.STARTING, 2000)
        charts.receiveHr(2100, reading(120))
        charts.recordMotion(2250, motion(), false, 2)
        val hr = charts.snapshot(ChartKind.HEART_RATE, 2500)
        val cadence = charts.snapshot(ChartKind.CADENCE, 2500)
        assertEquals(300_000.0, hr.windowMs, 0.0)
        assertEquals(300_000.0, cadence.windowMs, 0.0)
        assertEquals(2, hr.points.size)
        assertEquals(2, cadence.points.size)
        assertFalse(hr.points.last().breakBefore)
        assertFalse(cadence.points.last().breakBefore)
    }
    private fun running(buffer: EcgBuffer = EcgBuffer()) = LiveCharts { buffer.samples.value }.apply {
        checkedDataTypes.forEach { onSubscriptionState(it, SubscriptionStatus.STARTING, 0) }
    }
    private fun reading(bpm: Int) = HeartRateReading(bpm)
    private fun motion(cadence: Double = 120.0) = StepState(cadence = cadence)

    @Test fun hrKeepsActualTimeOfLastBatchPerSecondWithoutAveragingOrChangingStatistics() {
        val charts = running()
        val hr = LatestHeartRate()
        val zones = HeartRateZones()
        fun receive(time: Long, vararg bpms: Int) {
            val valid = hr.receive(PolarHrData(bpms.map {
                PolarHrData.PolarHrSample(it, 0, 0, emptyList(), emptyList(), false, true, true)
            }))
            zones.receive(hr.reading.value, valid, time)
            charts.receiveHr(time, hr.reading.value)
        }
        receive(100, 70, 80)
        receive(900, 90)
        val first = charts.snapshot(ChartKind.HEART_RATE, 950).points.single()
        assertEquals(900.0, first.elapsedMs, 0.0)
        assertEquals(90.0, first.value!!, 0.0)
        assertEquals(HeartRateStatistics(3, 240, 70, 90), hr.statistics.value)
        receive(1100, 90)
        assertEquals(2, charts.snapshot(ChartKind.HEART_RATE, 1100).points.size)
        val before = hr.statistics.value
        charts.advance(15_000)
        repeat(10) { charts.snapshot(ChartKind.HEART_RATE, 15_000) }
        assertEquals(2, charts.snapshot(ChartKind.HEART_RATE, 15_000).points.size)
        assertEquals(before, hr.statistics.value)
        zones.refresh(15_000)
        assertEquals(14_900L, zones.state.value.durationsMs[0])
    }

    @Test fun invalidPointAndItsBreakSurviveReplacementByValidSampleInSameBucket() {
        val charts = running()
        charts.receiveHr(100, reading(100))
        charts.receiveHr(1100, reading(120))
        assertFalse(charts.snapshot(ChartKind.HEART_RATE, 1100).points.last().breakBefore)
        charts.receiveHr(1200, null)
        assertNull(charts.snapshot(ChartKind.HEART_RATE, 1200).points.last().value)
        charts.receiveHr(1900, reading(125))
        val replaced = charts.snapshot(ChartKind.HEART_RATE, 1900).points.last()
        assertTrue(replaced.breakBefore)
        assertEquals(125.0, replaced.value!!, 0.0)
        charts.receiveHr(2100, reading(126))
        assertFalse(charts.snapshot(ChartKind.HEART_RATE, 2100).points.last().breakBefore)
    }

    @Test fun hrGapUsesRealBatchTimesBeforeReductionAndStrictThreeSecondBoundary() {
        val charts = running()
        charts.receiveHr(0, reading(100))
        charts.receiveHr(900, reading(100))
        charts.receiveHr(3900, reading(100))
        assertFalse(charts.snapshot(ChartKind.HEART_RATE, 3900).points.last().breakBefore)
        charts.receiveHr(6901, reading(100))
        assertTrue(charts.snapshot(ChartKind.HEART_RATE, 6901).points.last().breakBefore)
        charts.receiveHr(6999, reading(100))
        assertTrue(charts.snapshot(ChartKind.HEART_RATE, 6999).points.last().breakBefore)
    }

    @Test fun hrAndMotionStayBoundedAndRemoveExpiredDataWithoutPadding() {
        val charts = running()
        for (time in 0L..600_000L step 10) {
            charts.receiveHr(time, reading(100))
            charts.recordMotion(time, motion(), false, 1)
            charts.advance(time)
        }
        val hr = charts.snapshot(ChartKind.HEART_RATE, 600_000)
        val cadence = charts.snapshot(ChartKind.CADENCE, 600_000)
        assertEquals(301, hr.points.size)
        assertEquals(1200, cadence.points.size)
        assertTrue(hr.points.all { it.elapsedMs > 300_000 })
        assertTrue(cadence.points.all { it.elapsedMs > 300_000 })
        assertEquals(600_000.0, cadence.points.last().elapsedMs, 0.0)
        charts.advance(1_000_000)
        assertTrue(charts.snapshot(ChartKind.HEART_RATE, 600_000).points.isEmpty())
        assertTrue(charts.snapshot(ChartKind.CADENCE, 600_000).points.isEmpty())
    }

    @Test fun motionRecordsOnlyAtRefreshAndSeparatesWarmupMissingAndMeasuredZero() {
        val charts = running()
        charts.recordMotion(0, motion(), true, 1)
        charts.recordMotion(100, motion(), false, 1)
        charts.recordMotion(500, motion(0.0), false, 1)
        charts.recordMotion(1000, motion(), false, 1)
        charts.recordMotion(1000, motion(200.0), false, 1)
        charts.recordMotion(1500, StepState(), false, 1)
        charts.recordMotion(2000, motion(), false, 2)
        val cadence = charts.snapshot(ChartKind.CADENCE, 2000).points
        assertEquals(listOf(null, 0.0, 120.0, null, 120.0), cadence.map { it.value })
        assertEquals(listOf(true, true, false, true, true), cadence.map { it.breakBefore })
    }

    @Test fun accThirtyMillisecondBoundaryDrivesChartSegmentsWithoutExtraReset() {
        val detector = StepDetector { 0 }
        val charts = running()
        val buffer = AccSampleProcessor { detector.receive(it) }
        detector.onSubscriptionState(SubscriptionStatus.STARTING)
        fun feed(time: Long) = buffer.receive(com.polar.sdk.api.model.PolarAccelerometerData(listOf(
            com.polar.sdk.api.model.PolarAccelerometerData.PolarAccelerometerDataSample(time, 1000, 0, 0))))
        (0..104).forEach { feed(it * 10_000_000L) }
        detector.receivedBatch(1_040_000_000, 0)
        val initial = detector.segment
        charts.recordMotion(0, detector.state.value, false, initial)
        feed(1_070_000_000)
        assertEquals(initial, detector.segment)
        charts.recordMotion(500, detector.state.value, false, detector.segment)
        feed(1_100_000_001)
        assertTrue(detector.segment > initial)
        charts.recordMotion(1000, detector.state.value, true, detector.segment)
        (1..104).forEach { feed(1_100_000_001 + it * 10_000_000L) }
        detector.receivedBatch(2_140_000_001, 0)
        charts.recordMotion(1500, detector.state.value, false, detector.segment)
        val points = charts.snapshot(ChartKind.CADENCE, 1500).points
        assertNull(points[1].value)
        assertTrue(points[1].breakBefore)
        assertNull(points[2].value)
        assertTrue(points[3].breakBefore)
        val before = detector.state.value
        charts.snapshot(ChartKind.CADENCE, 1500)
        assertEquals(before, detector.state.value)
    }

    @Test fun stepSequenceTimeoutBreaksChartEvenIfWarmupFinishesBetweenRefreshes() {
        val charts = running()
        val detector = StepDetector { 0 }
        detector.onSubscriptionState(SubscriptionStatus.STARTING)
        (0..303).forEach { index ->
            val x = if (index < 104) 1000 else when ((index - 104) % 50) {
                in 0..9 -> 1800
                in 10..19 -> 800
                else -> 1000
            }
            detector.receive(AccSample(index * 10_000_000L, x, 0, 0))
        }
        detector.receivedBatch(3_030_000_000, 0)
        charts.recordMotion(3030, detector.state.value, false, detector.segment)
        val previousSegment = detector.segment
        (304..600).forEach { detector.receive(AccSample(it * 10_000_000L, 1000, 0, 0)) }
        detector.receivedBatch(6_000_000_000, 0)
        assertTrue(detector.segment > previousSegment)
        assertFalse(detector.isWarmingUp)
        charts.recordMotion(6000, detector.state.value, false, detector.segment)
        val last = charts.snapshot(ChartKind.CADENCE, 6000).points.last()
        assertTrue(last.breakBefore)
        assertEquals(0.0, last.value!!, 0.0)
    }

    @Test fun ecgAnchorIsFixedAcrossBatchesAndNegativeTimesAreOmittedWithSignedValues() {
        val buffer = EcgBuffer()
        val charts = running(buffer)
        val base = 900_000_000_000_000_000L
        val first = listOf(EcgSample(base - 20_000_000, -50), EcgSample(base - 10_000_000, -30), EcgSample(base, 40))
        buffer.receive(first)
        charts.receiveEcg(first, 10, 100)
        val points = charts.snapshot(ChartKind.ELECTROCARDIOGRAM, 10).points
        assertEquals(listOf(0.0, 10.0), points.map { it.elapsedMs })
        assertEquals(listOf(-30.0, 40.0), points.map { it.value })
        val next = listOf(EcgSample(base + 10_000_000, -20))
        buffer.receive(next)
        charts.receiveEcg(next, 1000, 100)
        val later = charts.snapshot(ChartKind.ELECTROCARDIOGRAM, 1000).points
        assertEquals(listOf(0.0, 10.0, 20.0), later.map { it.elapsedMs })
        assertEquals(-20.0, later.last().value!!, 0.0)
    }

    @Test fun ecgFiveSecondSubsetKeepsEveryVisibleSampleAndReusesBoundedRawBuffer() {
        val buffer = EcgBuffer()
        val charts = running(buffer)
        val first = listOf(EcgSample(0, 0))
        buffer.receive(first)
        charts.receiveEcg(first, 0, 130)
        val rest = (1..1560).map { EcgSample(it * 1_000_000_000L / 130, it % 31 - 15) }
        rest.chunked(91).forEach { chunk ->
            buffer.receive(chunk)
            charts.receiveEcg(chunk, chunk.last().timeStamp / 1_000_000, 130)
        }
        assertEquals(1300, buffer.samples.value.size)
        val visible = charts.snapshot(ChartKind.ELECTROCARDIOGRAM, 12_000).points
        val expected = buffer.samples.value.filter { it.timeStamp > 7_000_000_000 && it.timeStamp <= 12_000_000_000 }
        assertEquals(650, visible.size)
        assertEquals(expected.map { it.voltage.toDouble() }, visible.map { it.value })
        assertEquals(expected.map { it.timeStamp / 1_000_000.0 }, visible.map { it.elapsedMs })
    }

    @Test fun ecgGapUsesActualRateAndChecksAcrossBatchesAtStrictBoundary() {
        for (rate in listOf(100, 130)) {
            val buffer = EcgBuffer()
            val charts = running(buffer)
            val threshold = 3_000_000_000L / rate
            var time = 0L
            for (gap in listOf(0L, threshold, threshold + 1)) {
                time += gap
                val batch = listOf(EcgSample(time, 1))
                buffer.receive(batch)
                charts.receiveEcg(batch, time / 1_000_000, rate)
            }
            val points = charts.snapshot(ChartKind.ELECTROCARDIOGRAM, 100).points
            assertEquals(listOf(true, false, true), points.map { it.breakBefore })
        }
    }

    @Test fun retryClearsOnlySelectedStreamAndEcgReanchorsWithoutResettingSessionTime() {
        val buffer = EcgBuffer()
        val charts = running(buffer)
        charts.receiveHr(1000, reading(120))
        charts.recordMotion(1000, motion(), false, 1)
        val first = listOf(EcgSample(123, -10))
        buffer.receive(first)
        charts.receiveEcg(first, 1000, 130)
        charts.onSubscriptionState(ACC, SubscriptionStatus.FAILED, 2000)
        charts.onSubscriptionState(ACC, SubscriptionStatus.STARTING, 3000)
        assertTrue(charts.snapshot(ChartKind.CADENCE, 3000).points.isEmpty())
        assertEquals(1, charts.snapshot(ChartKind.HEART_RATE, 3000).points.size)
        assertEquals(1, charts.snapshot(ChartKind.ELECTROCARDIOGRAM, 3000).points.size)
        charts.onSubscriptionState(ECG, SubscriptionStatus.STARTING, 4000)
        buffer.onSubscriptionState(ECG, SubscriptionStatus.STARTING)
        val retry = listOf(EcgSample(9000, 99))
        buffer.receive(retry)
        charts.receiveEcg(retry, 4500, 130)
        assertEquals(4500.0, charts.snapshot(ChartKind.ELECTROCARDIOGRAM, 4500).points.single().elapsedMs, 0.0)
        charts.onSubscriptionState(HR, SubscriptionStatus.STARTING, 5000)
        assertTrue(charts.snapshot(ChartKind.HEART_RATE, 5000).points.isEmpty())
        assertEquals(1, charts.snapshot(ChartKind.ELECTROCARDIOGRAM, 5000).points.size)
    }

    @Test fun individualFailureAndOverallStopFreezeWindowsWithoutArtificialZeroPoints() {
        val charts = running()
        charts.receiveHr(1000, reading(120))
        charts.recordMotion(1000, motion(), false, 1)
        charts.onSubscriptionState(HR, SubscriptionStatus.FAILED, 2000)
        val hr = charts.snapshot(ChartKind.HEART_RATE, 2000)
        charts.advance(70_000)
        assertEquals(hr, charts.snapshot(ChartKind.HEART_RATE, 70_000))
        charts.recordMotion(70_000, motion(), false, 1)
        charts.stop(71_000)
        val stopped = charts.snapshot(ChartKind.CADENCE, 71_000)
        charts.recordMotion(72_000, motion(0.0), false, 2)
        charts.advance(200_000)
        charts.stop(200_000)
        assertEquals(stopped, charts.snapshot(ChartKind.CADENCE, 200_000))
        assertEquals(listOf(120.0, 120.0), stopped.points.map { it.value })
        assertEquals(71_000.0, stopped.endMs, 0.0)
    }

    @Test fun selectionAndRepeatedSnapshotsDoNotChangeDataAndResetKeepsSelectionOnly() {
        val charts = running()
        charts.receiveHr(1000, reading(120))
        charts.recordMotion(1000, motion(), false, 1)
        val hr = charts.snapshot(ChartKind.HEART_RATE, 1000)
        charts.select(ChartKind.CADENCE)
        charts.select(ChartKind.ELECTROCARDIOGRAM)
        repeat(10) { charts.snapshot(charts.selection.value, 1000) }
        charts.select(ChartKind.CADENCE)
        assertEquals(ChartKind.CADENCE, charts.selection.value)
        assertEquals(hr, charts.snapshot(ChartKind.HEART_RATE, 1000))
        charts.receiveHr(2000, reading(121))
        assertEquals(2000.0, charts.snapshot(ChartKind.HEART_RATE, 2000).points.last().elapsedMs, 0.0)
        charts.reset()
        assertEquals(ChartKind.CADENCE, charts.selection.value)
        ChartKind.entries.forEach { assertTrue(charts.snapshot(it, 3000).points.isEmpty()) }
    }
}
