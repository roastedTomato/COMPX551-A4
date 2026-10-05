package com.example.polarh10activityviewer.heartrate

import com.example.polarh10activityviewer.ble.DataSubscriptions
import com.example.polarh10activityviewer.ble.SubscriptionStatus

import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.HR
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.ACC
import com.polar.sdk.api.model.PolarHrData
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
class HeartRateTest {

    private fun batch(vararg values: Int) = PolarHrData(values.map {
        PolarHrData.PolarHrSample(it, 0, 0, emptyList(), emptyList(), false, true, true)
    })

    private fun DataSubscriptions.startHr(
        latest: LatestHeartRate,
        source: Flow<PolarHrData>,
        current: () -> Boolean = { true }
    ) = start(HR, { true }, current,
        { source.filter { it.samples.isNotEmpty() } },
        { latest.receive(it) })

    @Test fun batchKeepsLastValidSampleAndRepeatedValueUpdatesStatistics() = runTest {
        val latest = LatestHeartRate()
        val subscriptions = DataSubscriptions(this, latest::onSubscriptionState)
        val source = MutableSharedFlow<PolarHrData>()
        assertNull(latest.reading.value)
        subscriptions.startHr(latest, source)
        runCurrent()
        source.emit(batch(70, 71, 72))
        runCurrent()
        assertEquals(HeartRateReading(72), latest.reading.value)
        source.emit(batch(72))
        runCurrent()
        assertEquals(HeartRateReading(72), latest.reading.value)
        val totals = latest.statistics.value
        assertEquals(HeartRateStatistics(4, 285, 70, 72), totals)
        source.emit(batch(0))
        runCurrent()
        assertNull(latest.reading.value)
        assertEquals("Invalid HR sample", latest.message.value)
        assertEquals(totals, latest.statistics.value)
        subscriptions.stopAll()
    }

    @Test fun emptyBatchDoesNotStartReceptionOrReplaceLastReading() = runTest {
        val latest = LatestHeartRate()
        val subscriptions = DataSubscriptions(this, latest::onSubscriptionState)
        val source = MutableSharedFlow<PolarHrData>()
        subscriptions.startHr(latest, source)
        runCurrent()
        source.emit(batch())
        runCurrent()
        assertEquals(SubscriptionStatus.STARTING, subscriptions.states.value.getValue(HR).status)
        assertNull(latest.reading.value)
        source.emit(batch(80))
        runCurrent()
        source.emit(batch())
        runCurrent()
        assertEquals(HeartRateReading(80), latest.reading.value)
        assertEquals(SubscriptionStatus.RECEIVING, subscriptions.states.value.getValue(HR).status)
        subscriptions.stopAll()
    }

    @Test fun duplicateStartKeepsReadingAndStopClearsBeforeCleanupCompletes() = runTest {
        val latest = LatestHeartRate()
        val subscriptions = DataSubscriptions(this, latest::onSubscriptionState)
        val cleanup = CompletableDeferred<Unit>()
        val source = flow {
            try { emit(batch(75)); awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await() } }
        }
        assertTrue(subscriptions.startHr(latest, source))
        runCurrent()
        assertFalse(subscriptions.startHr(latest, source))
        assertEquals(HeartRateReading(75), latest.reading.value)
        assertEquals(1L, latest.statistics.value.count)
        subscriptions.stop(HR)
        assertNull(latest.reading.value)
        runCurrent()
        assertFalse(subscriptions.startHr(latest, source))
        assertEquals(SubscriptionStatus.STOPPING, subscriptions.states.value.getValue(HR).status)
        cleanup.complete(Unit)
        runCurrent()
        assertNull(subscriptions.states.value.getValue(HR).error)
        val next = MutableSharedFlow<PolarHrData>()
        assertTrue(subscriptions.startHr(latest, next))
        runCurrent()
        assertNull(latest.reading.value)
        next.emit(batch(85))
        runCurrent()
        assertEquals(HeartRateReading(85), latest.reading.value)
        assertEquals(HeartRateStatistics(2, 160, 75, 85), latest.statistics.value)
        subscriptions.stopAll()
    }

    @Test fun completionAndFailureClearReadingAndAllowManualRetry() = runTest {
        val latest = LatestHeartRate()
        val subscriptions = DataSubscriptions(this, latest::onSubscriptionState)
        for (fails in listOf(false, true)) {
            val finish = CompletableDeferred<Unit>()
            assertTrue(subscriptions.startHr(latest, flow {
                emit(batch(76))
                finish.await()
                if (fails) error("controlled HR failure")
            }))
            runCurrent()
            assertNotNull(latest.reading.value)
            finish.complete(Unit)
            runCurrent()
            assertNull(latest.reading.value)
            assertEquals(if (fails) SubscriptionStatus.FAILED else SubscriptionStatus.STOPPED,
                subscriptions.states.value.getValue(HR).status)
        }
        assertEquals("HR stream failed: controlled HR failure", subscriptions.states.value.getValue(HR).error)
        assertTrue(subscriptions.startHr(latest, flow { emit(batch(81)); awaitCancellation() }))
        runCurrent()
        assertEquals(81, latest.reading.value?.bpm)
        assertEquals(HeartRateStatistics(3, 233, 76, 81), latest.statistics.value)
        assertNull(subscriptions.states.value.getValue(HR).error)
        subscriptions.stopAll()
    }

    @Test fun cleanupAllClearsImmediatelyAndDoesNotResumeAfterReconnection() = runTest {
        val latest = LatestHeartRate()
        val subscriptions = DataSubscriptions(this, latest::onSubscriptionState)
        var current = true
        val source = MutableSharedFlow<PolarHrData>()
        subscriptions.startHr(latest, source, current = { current })
        runCurrent()
        source.emit(batch(90))
        runCurrent()
        current = false
        subscriptions.stopAll()
        subscriptions.stopAll()
        assertNull(latest.reading.value)
        runCurrent()
        current = true
        source.emit(batch(91))
        runCurrent()
        assertNull(latest.reading.value)
        assertEquals(SubscriptionStatus.STOPPED, subscriptions.states.value.getValue(HR).status)
        assertEquals(HeartRateStatistics(1, 90, 90, 90), latest.statistics.value)
    }

    @Test fun oldConnectionEventsCannotUpdateTheReading() = runTest {
        val latest = LatestHeartRate()
        val subscriptions = DataSubscriptions(this, latest::onSubscriptionState)
        var current = true
        val send = CompletableDeferred<Unit>()
        subscriptions.startHr(latest, flow {
            send.await()
            emit(batch(99))
            error("old connection")
        }, current = { current })
        runCurrent()
        current = false
        send.complete(Unit)
        runCurrent()
        assertNull(latest.reading.value)
        assertNull(subscriptions.states.value.getValue(HR).error)
        assertEquals(0L, latest.statistics.value.count)
    }

    @Test fun lateOldEmissionCannotOverwriteRestartedReading() = runTest {
        val latest = LatestHeartRate()
        val subscriptions = DataSubscriptions(this, latest::onSubscriptionState)
        lateinit var oldCollector: FlowCollector<PolarHrData>
        // Intentionally broken producer for a late SDK event; never used in production.
        val oldSource = object : Flow<PolarHrData> {
            override suspend fun collect(collector: FlowCollector<PolarHrData>) {
                oldCollector = collector
                collector.emit(batch(60))
            }
        }
        subscriptions.startHr(latest, oldSource)
        runCurrent()
        assertNull(latest.reading.value)
        subscriptions.startHr(latest, flow { emit(batch(82)); awaitCancellation() })
        runCurrent()
        runCatching { oldCollector.emit(batch(61)) }
        runCurrent()
        assertEquals(HeartRateReading(82), latest.reading.value)
        assertEquals(HeartRateStatistics(2, 142, 60, 82), latest.statistics.value)
        subscriptions.stopAll()
    }

    @Test fun otherStreamStateChangesDoNotClearHeartRate() = runTest {
        val latest = LatestHeartRate()
        val subscriptions = DataSubscriptions(this, latest::onSubscriptionState)
        subscriptions.startHr(latest, flow { emit(batch(88)); awaitCancellation() })
        runCurrent()
        subscriptions.start(ACC, { true }, { true }, { flow<Int> { error("ACC failure") } }, {})
        runCurrent()
        assertEquals(HeartRateReading(88), latest.reading.value)
        subscriptions.stopAll()
    }

    @Test fun mixedBatchCountsEveryValidSampleWithFloatingPointMean() {
        val latest = LatestHeartRate()
        assertNull(latest.statistics.value.average)
        assertTrue(latest.receive(batch(80, 80, 0, 100)))
        assertEquals(HeartRateStatistics(3, 260, 80, 100), latest.statistics.value)
        assertEquals(86.6666667, latest.statistics.value.average!!, 0.000001)
        assertEquals(HeartRateReading(100), latest.reading.value)
        assertNull(latest.message.value)
        assertFalse(latest.receive(batch(-1)))
        assertNull(latest.reading.value)
        assertEquals(3L, latest.statistics.value.count)
    }

    @Test fun invalidLastSampleDoesNotFallBackButEarlierValidSampleStillCounts() {
        val latest = LatestHeartRate()
        assertTrue(latest.receive(batch(90, 0)))
        assertNull(latest.reading.value)
        assertEquals("Invalid HR sample", latest.message.value)
        assertEquals(HeartRateStatistics(1, 90, 90, 90), latest.statistics.value)
        assertEquals(90.0, latest.statistics.value.average!!, 0.0)
        assertTrue(latest.receive(batch(85)))
        assertEquals(HeartRateReading(85), latest.reading.value)
        assertNull(latest.message.value)
    }

    @Test fun contactIsRequiredOnlyWhenSupportedAndNoContactMessageTakesPriority() {
        for (supported in listOf(false, true)) {
            for (contact in listOf(false, true)) {
                val latest = LatestHeartRate()
                val sample = batch(80).samples.single().copy(
                    contactStatusSupported = supported, contactStatus = contact
                )
                val valid = !supported || contact
                assertEquals(valid, latest.receive(PolarHrData(listOf(sample))))
                assertEquals(if (valid) 1L else 0L, latest.statistics.value.count)
                assertEquals(if (valid) null else "No sensor contact", latest.message.value)
            }
        }
        val latest = LatestHeartRate()
        val noContact = batch(0).samples.single().copy(contactStatus = false)
        assertFalse(latest.receive(PolarHrData(listOf(noContact))))
        assertEquals("No sensor contact", latest.message.value)
        assertEquals(HeartRateStatistics(), latest.statistics.value)
    }

    @Test fun invalidOnlyAndEmptyBatchesDoNotInventStatisticsOrClearExistingMessage() {
        val latest = LatestHeartRate()
        assertFalse(latest.receive(batch(0, -10)))
        assertEquals(HeartRateStatistics(), latest.statistics.value)
        assertNull(latest.statistics.value.average)
        assertNull(latest.reading.value)
        assertFalse(latest.receive(batch()))
        assertEquals("Invalid HR sample", latest.message.value)
        assertTrue(latest.receive(batch(1, 300)))
        assertEquals(HeartRateStatistics(2, 301, 1, 300), latest.statistics.value)
    }

    @Test fun clearingCurrentStateRetainsTotalsWhileResetRemovesEverything() {
        val latest = LatestHeartRate()
        latest.receive(batch(80, 100, 0))
        val totals = latest.statistics.value
        latest.clear()
        assertNull(latest.reading.value)
        assertNull(latest.message.value)
        assertEquals(totals, latest.statistics.value)
        latest.reset()
        assertEquals(HeartRateStatistics(), latest.statistics.value)
        assertNull(latest.reading.value)
        assertNull(latest.message.value)
        latest.receive(batch(60))
        assertEquals(HeartRateStatistics(1, 60, 60, 60), latest.statistics.value)
    }
}
