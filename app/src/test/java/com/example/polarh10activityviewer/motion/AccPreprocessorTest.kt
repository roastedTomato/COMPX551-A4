package com.example.polarh10activityviewer.motion

import com.example.polarh10activityviewer.ble.DataSubscriptions
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.sensor.AccSampleProcessor
import com.example.polarh10activityviewer.sensor.AccSample

import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.ACC
import com.polar.sdk.api.model.PolarAccelerometerData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class)
class AccPreprocessorTest {
    private fun sample(index: Int, x: Int = 1000, y: Int = 0, z: Int = 0) =
        AccSample(index * 10_000_000L, x, y, z)

    private fun batch(from: Int, through: Int) = PolarAccelerometerData((from..through).map {
        PolarAccelerometerData.PolarAccelerometerDataSample(it * 10_000_000L, 1000, 0, 0)
    })

    @Test fun convertsAllAxesAndRetainsGravityWithoutPartialSmoothing() {
        val processor = AccPreprocessor()
        for (i in 0..3) {
            val result = processor.receive(sample(i, -300, 400, 0))
            assertEquals(4.9, result.magnitude, 1e-10)
            assertNull(result.smoothed)
        }
        val result = processor.receive(sample(4, 0, 0, -1000))
        assertEquals(9.8, result.magnitude, 1e-10)
        assertEquals((4 * 4.9 + 9.8) / 5, result.smoothed!!, 1e-10)
        assertEquals(40_000_000L, result.timeStamp)
        assertEquals((3 * 4.9 + 2 * 9.8) / 5, processor.receive(sample(5)).smoothed!!, 1e-10)
    }

    @Test fun hundredthSmoothedValueOnlyFinishesWarmupAndNextPointUsesOldWindow() {
        val processor = AccPreprocessor()
        for (i in 0..102) {
            assertNull(processor.receive(sample(i)).previousMean)
            assertNull(processor.warmupEndedAt)
        }
        val hundredth = processor.receive(sample(103))
        assertNull(hundredth.previousMean)
        assertEquals(1_030_000_000L, processor.warmupEndedAt)
        val next = processor.receive(sample(104, 4000))
        assertEquals(9.8, next.previousMean!!, 1e-10)
        assertEquals(0.0, next.previousStdDev!!, 1e-10)
        assertEquals(15.68, next.smoothed!!, 1e-10)
        val following = processor.receive(sample(105))
        assertEquals((99 * 9.8 + 15.68) / 100, following.previousMean!!, 1e-10)
        assertEquals(1_030_000_000L, processor.warmupEndedAt)
    }

    @Test fun usesPopulationDeviationAndOnlyLatestHundredSmoothedValues() {
        val processor = AccPreprocessor()
        // With a linear ramp, each five-point mean equals its middle value.
        for (i in 0..203) processor.receive(sample(i, i * 10))
        val result = processor.receive(sample(204, 4000))
        val previous = (102..201).map { it * 0.098 }
        val mean = previous.average()
        val deviation = sqrt(previous.sumOf { (it - mean) * (it - mean) } / 100)
        assertEquals(mean, result.previousMean!!, 1e-10)
        assertEquals(deviation, result.previousStdDev!!, 1e-10)
    }

    @Test fun newBatchesProcessEachArrivalExactlyOnce() {
        val processor = AccPreprocessor()
        val results = mutableListOf<PreparedAcc>()
        val buffer = AccSampleProcessor { results += processor.receive(it) }
        buffer.receive(batch(0, 52))
        buffer.receive(batch(53, 104))
        buffer.receive(PolarAccelerometerData(emptyList()))
        assertEquals(105, results.size)
        assertEquals((0..104).map { it * 10_000_000L }, results.map { it.timeStamp })
        assertEquals(101, results.count { it.smoothed != null })
        assertEquals(1, results.count { it.previousMean != null })
    }

    @Test fun thirtyMillisecondBoundaryAndCrossBatchGapResetWarmup() {
        val processor = AccPreprocessor()
        val buffer = AccSampleProcessor { processor.receive(it) }
        buffer.receive(batch(0, 104))
        fun at(time: Long) = PolarAccelerometerData(listOf(
            PolarAccelerometerData.PolarAccelerometerDataSample(time, 1000, 0, 0)))
        buffer.receive(at(1_070_000_000L))
        assertNotNull(processor.latest!!.previousMean)
        buffer.receive(at(1_100_000_001L))
        assertNull(processor.latest!!.smoothed)
        assertNull(processor.latest!!.previousMean)
        assertNull(processor.warmupEndedAt)
    }

    @Test fun stoppingClearsPreparationAndRestartCannotReceiveOldSourceEvents() = runTest {
        val processor = AccPreprocessor()
        val buffer = AccSampleProcessor { processor.receive(it) }
        val subscriptions = DataSubscriptions(this) { type, status ->
            buffer.onSubscriptionState(type, status)
            if (type == ACC && status != SubscriptionStatus.RECEIVING) processor.clear()
        }
        val old = MutableSharedFlow<PolarAccelerometerData>()
        fun start(source: MutableSharedFlow<PolarAccelerometerData>) =
            subscriptions.start(ACC, { true }, { true }, { source }, buffer::receive)
        assertTrue(start(old))
        runCurrent()
        old.emit(batch(0, 104))
        runCurrent()
        assertNotNull(processor.latest!!.previousMean)
        assertFalse(start(old))
        assertNotNull(processor.latest!!.previousMean)
        subscriptions.stop(ACC)
        assertNull(processor.latest)
        runCurrent()
        val next = MutableSharedFlow<PolarAccelerometerData>()
        assertTrue(start(next))
        runCurrent()
        old.emit(batch(105, 209))
        assertNull(processor.latest)
        next.emit(batch(0, 3))
        runCurrent()
        assertNull(processor.latest!!.smoothed)
        subscriptions.stop(ACC)
        runCurrent()
    }
}
