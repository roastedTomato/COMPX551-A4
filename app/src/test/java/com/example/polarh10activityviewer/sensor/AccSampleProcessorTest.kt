package com.example.polarh10activityviewer.sensor

import com.example.polarh10activityviewer.ble.DataSubscriptions
import com.example.polarh10activityviewer.ble.SubscriptionStatus

import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.ACC
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.HR
import com.polar.sdk.api.model.PolarAccelerometerData
import kotlinx.coroutines.CompletableDeferred
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
class AccSampleProcessorTest {
    private fun batch(vararg times: Long) = PolarAccelerometerData(times.map {
        PolarAccelerometerData.PolarAccelerometerDataSample(it, -123, 456, 1000)
    })
    private fun DataSubscriptions.startAcc(buffer: AccSampleProcessor, source: Flow<PolarAccelerometerData>,
        current: () -> Boolean = { true }) = start(ACC, { true }, current,
        { source.filter { it.samples.isNotEmpty() } }, buffer::receive)

    @Test fun forwardsEveryRawSampleAndMarksOnlyGapsStrictlyAbove30msAcrossBatches() {
        val samples = mutableListOf<AccSample>()
        val buffer = AccSampleProcessor { samples.add(it) }
        buffer.receive(batch(0, 10_000_000, 40_000_000))
        buffer.receive(batch(70_000_001, 80_000_001))
        assertEquals(listOf(0L, 10_000_000L, 40_000_000L, 70_000_001L, 80_000_001L), samples.map { it.timeStamp })
        assertEquals(listOf(null, null, null, 30_000_001L, null), samples.map { it.gapBeforeNs })
        samples.forEach { assertEquals(Triple(-123, 456, 1000), Triple(it.x, it.y, it.z)) }
    }

    @Test fun emptyBatchDoesNotMarkReceiving() = runTest {
        val samples = mutableListOf<AccSample>()
        val buffer = AccSampleProcessor { samples.add(it) }
        val subscriptions = DataSubscriptions(this, buffer::onSubscriptionState)
        val source = MutableSharedFlow<PolarAccelerometerData>()
        subscriptions.startAcc(buffer, source)
        runCurrent()
        source.emit(batch())
        runCurrent()
        assertEquals(SubscriptionStatus.STARTING, subscriptions.states.value.getValue(ACC).status)
        assertTrue(samples.isEmpty())
        source.emit(batch(1))
        runCurrent()
        assertEquals(SubscriptionStatus.RECEIVING, subscriptions.states.value.getValue(ACC).status)
        subscriptions.stopAll()
    }

    @Test fun duplicateAndStoppingDoNotForwardExtraSamplesAndRestartResetsGapTracking() = runTest {
        val samples = mutableListOf<AccSample>()
        val buffer = AccSampleProcessor { samples.add(it) }
        val subscriptions = DataSubscriptions(this, buffer::onSubscriptionState)
        val cleanup = CompletableDeferred<Unit>()
        val source = flow {
            try { emit(batch(0, 40_000_000)); awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await() } }
        }
        assertTrue(subscriptions.startAcc(buffer, source))
        runCurrent()
        val snapshot = samples.toList()
        assertFalse(subscriptions.startAcc(buffer, source))
        assertEquals(snapshot, samples)
        subscriptions.stop(ACC)
        runCurrent()
        assertFalse(subscriptions.startAcc(buffer, source))
        assertEquals(snapshot, samples)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(SubscriptionStatus.STOPPED, subscriptions.states.value.getValue(ACC).status)
        assertEquals(snapshot, samples)
        val next = MutableSharedFlow<PolarAccelerometerData>()
        samples.clear()
        assertTrue(subscriptions.startAcc(buffer, next))
        assertTrue(samples.isEmpty())
        runCurrent()
        next.emit(batch(1_000_000_000))
        runCurrent()
        assertNull(samples.single().gapBeforeNs)
        subscriptions.stopAll()
    }

    @Test fun completionFailureAndConnectionCleanupLeaveHrIndependent() = runTest {
        val samples = mutableListOf<AccSample>()
        val buffer = AccSampleProcessor { samples.add(it) }
        val subscriptions = DataSubscriptions(this, buffer::onSubscriptionState)
        subscriptions.start(HR, { true }, { true }, { flow { emit(80); awaitCancellation() } }, {})
        for (fails in listOf(false, true)) {
            samples.clear()
            subscriptions.startAcc(buffer, flow {
                emit(batch(10))
                if (fails) error("controlled ACC failure")
            })
            runCurrent()
            assertEquals(10L, samples.single().timeStamp)
            assertEquals(if (fails) SubscriptionStatus.FAILED else SubscriptionStatus.STOPPED,
                subscriptions.states.value.getValue(ACC).status)
            assertEquals(SubscriptionStatus.RECEIVING, subscriptions.states.value.getValue(HR).status)
        }
        samples.clear()
        var connected = true
        subscriptions.startAcc(buffer, flow { emit(batch(20)); awaitCancellation() }, { connected })
        runCurrent()
        connected = false
        subscriptions.stopAll()
        subscriptions.stopAll()
        runCurrent()
        connected = true
        runCurrent()
        assertEquals(20L, samples.single().timeStamp)
        assertEquals(SubscriptionStatus.STOPPED, subscriptions.states.value.getValue(ACC).status)
    }

    @Test fun settingsTaskPreventsDuplicatesAndIsCancelledBeforeRetry() = runTest {
        val samples = mutableListOf<AccSample>()
        val buffer = AccSampleProcessor { samples.add(it) }
        val subscriptions = DataSubscriptions(this, buffer::onSubscriptionState)
        val query = CompletableDeferred<Unit>()
        var queries = 0
        var streams = 0
        val factory: suspend () -> Flow<PolarAccelerometerData> = {
            queries++
            query.await()
            streams++
            flow { emit(batch(1)); awaitCancellation() }
        }
        fun start() = subscriptions.start(ACC, { true }, { true }, factory, buffer::receive)
        assertTrue(start())
        runCurrent()
        assertTrue(subscriptions.isActive(ACC))
        assertFalse(start())
        assertEquals(1, queries)
        subscriptions.stop(ACC)
        runCurrent()
        assertEquals(0, streams)
        assertNull(subscriptions.states.value.getValue(ACC).error)
        query.complete(Unit)
        assertTrue(start())
        runCurrent()
        assertEquals(2, queries)
        assertEquals(1, streams)
        subscriptions.stopAll()
    }

    @Test fun oldConnectionAndOldTaskCannotForwardSamples() = runTest {
        val samples = mutableListOf<AccSample>()
        val buffer = AccSampleProcessor { samples.add(it) }
        val subscriptions = DataSubscriptions(this, buffer::onSubscriptionState)
        lateinit var oldCollector: FlowCollector<PolarAccelerometerData>
        val oldSource = object : Flow<PolarAccelerometerData> {
            override suspend fun collect(collector: FlowCollector<PolarAccelerometerData>) {
                oldCollector = collector
                collector.emit(batch(1))
            }
        }
        subscriptions.startAcc(buffer, oldSource)
        runCurrent()
        var current = true
        val source = MutableSharedFlow<PolarAccelerometerData>()
        samples.clear()
        subscriptions.startAcc(buffer, source, { current })
        runCurrent()
        source.emit(batch(2))
        runCurrent()
        runCatching { oldCollector.emit(batch(3)) }
        current = false
        source.emit(batch(4))
        runCurrent()
        assertEquals(listOf(2L), samples.map { it.timeStamp })
        subscriptions.stopAll()
    }
}
