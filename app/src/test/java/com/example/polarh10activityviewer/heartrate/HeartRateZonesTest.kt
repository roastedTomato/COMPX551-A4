package com.example.polarh10activityviewer.heartrate

import com.example.polarh10activityviewer.ble.DataSubscriptions
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.session.SessionController
import com.example.polarh10activityviewer.session.SessionStatus
import com.example.polarh10activityviewer.session.SessionSummary

import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.*
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
class HeartRateZonesTest {
    private fun sample(bpm: Int, supported: Boolean = true, contact: Boolean = true) =
        PolarHrData.PolarHrSample(bpm, 0, 0, emptyList(), emptyList(), false, true, true)
            .copy(contactStatusSupported = supported, contactStatus = contact)
    private fun batch(vararg bpms: Int) = PolarHrData(bpms.map { sample(it) })

    private class Fixture(scope: CoroutineScope) {
        var now = 0L
        var cleanupMs = 0L
        val hr = LatestHeartRate()
        val zones = HeartRateZones()
        lateinit var session: SessionController
        val subscriptions = DataSubscriptions(scope) { type, status ->
            val eventTime = now
            if (type == HR && status != SubscriptionStatus.RECEIVING && session.state.value.ongoing) {
                session.refresh(session.state.value.generation, eventTime)
                zones.clearCurrent(session.state.value.elapsedMs)
            }
            hr.onSubscriptionState(type, status)
            session.onSubscriptionState(type, status, eventTime)
        }
        init {
            session = SessionController(subscriptions, { now }, { hr.reset(); zones.reset() }, {
                zones.clearCurrent(session.state.value.elapsedMs)
                hr.clear()
                now += cleanupMs
            }, { SessionSummary() })
        }
        val state get() = zones.state.value
        fun startHr(source: Flow<PolarHrData>): Boolean {
            val generation = session.state.value.generation
            return subscriptions.start(HR, { session.accepts(generation) }, { session.accepts(generation) },
                { source.filter { it.samples.isNotEmpty() } }) { data ->
                val receivedTime = now
                val valid = hr.receive(data)
                if (valid) session.onValidData(receivedTime)
                session.refresh(generation, receivedTime)
                zones.receive(hr.reading.value, valid, session.state.value.elapsedMs)
            }
        }
        fun start(source: Flow<PolarHrData>, otherFirst: Boolean = false) = session.start(true) {
            if (otherFirst) {
                val generation = session.state.value.generation
                subscriptions.start(ACC, { true }, { session.accepts(generation) },
                    { flow { emit(1); awaitCancellation() } }, { session.onValidData() })
            }
            startHr(source)
        }
        fun refresh(generation: Long = session.state.value.generation) {
            session.refresh(generation)
            if (session.accepts(generation)) zones.refresh(session.state.value.elapsedMs)
        }
        fun stop() = session.stop("Test end")
    }

    @Test fun allZoneBoundariesUseExistingValidityAndKeepOriginalHrStatistics() {
        val hr = LatestHeartRate()
        val zones = HeartRateZones()
        val expected = listOf(109 to 0, 110 to 1, 124 to 1, 125 to 2,
            139 to 2, 140 to 3, 154 to 3, 155 to 4)
        expected.forEachIndexed { index, (bpm, zone) ->
            val valid = hr.receive(batch(bpm))
            zones.receive(hr.reading.value, valid, index * 1000L)
            assertEquals(zone, zones.state.value.current!!.ordinal)
        }
        assertEquals(8L, hr.statistics.value.count)
        assertEquals(109, hr.statistics.value.min)
        assertEquals(155, hr.statistics.value.max)
        val invalid = PolarHrData(listOf(sample(0), sample(-1), sample(120, contact = false)))
        assertFalse(hr.receive(invalid))
        zones.receive(hr.reading.value, false, 8000)
        assertNull(zones.state.value.current)
        assertEquals(8L, hr.statistics.value.count)
        val unsupported = PolarHrData(listOf(sample(120, supported = false, contact = false)))
        val valid = hr.receive(unsupported)
        zones.receive(hr.reading.value, valid, 9000)
        assertEquals(HeartRateZone.LIGHT, zones.state.value.current)
    }

    @Test fun sampleAtTenThenThirteenAssignsThreeSecondsToZoneTwo() {
        val zones = HeartRateZones()
        zones.receive(HeartRateReading(120), true, 10_000)
        zones.receive(HeartRateReading(130), true, 13_000)
        assertEquals(listOf(0L, 3000L, 0L, 0L, 0L), zones.state.value.durationsMs)
        assertEquals(10_000L, zones.state.value.unclassifiedMs)
        zones.refresh(14_250)
        assertEquals(1250L, zones.state.value.durationsMs[2])
        assertEquals(10_000L, zones.state.value.unclassifiedMs)
    }

    @Test fun stationaryHoldingAndRepeatedRefreshesNeverCommitTwice() {
        val zones = HeartRateZones()
        zones.receive(HeartRateReading(120), true, 0)
        repeat(8) { zones.refresh(1750) }
        assertEquals(1750L, zones.state.value.durationsMs[1])
        zones.receive(HeartRateReading(120), true, 2000)
        zones.refresh(60_999)
        assertEquals(60_999L, zones.state.value.durationsMs[1])
        assertEquals(0L, zones.state.value.unclassifiedMs)
        zones.clearCurrent(61_250)
        zones.clearCurrent(61_250)
        assertEquals(61_250L, zones.state.value.durationsMs[1])
        assertEquals("01:01", formatZoneDuration(61_999))
        assertEquals("00:00", formatZoneDuration(999))
        assertEquals("120:00", formatZoneDuration(7_200_000))
    }

    @Test fun invalidOnlyStartingAndMixedInvalidFinalDoNotInventZoneTime() = runTest {
        val f = Fixture(this)
        val source = MutableSharedFlow<PolarHrData>()
        f.start(source)
        runCurrent()
        f.now = 5000
        source.emit(batch(0, -1))
        f.refresh()
        assertEquals(SessionStatus.STARTING, f.session.state.value.status)
        assertEquals(HeartRateZoneState(), f.state)
        source.emit(batch(120, 130, 0))
        assertEquals(SessionStatus.RUNNING, f.session.state.value.status)
        assertEquals(2L, f.hr.statistics.value.count)
        assertTrue(f.state.receivedValidHr)
        assertNull(f.state.current)
        f.now = 8000
        f.refresh()
        assertEquals(3000L, f.state.unclassifiedMs)
        assertEquals(0L, f.state.durationsMs.sum())
        source.emit(batch(0, 155))
        f.now = 9000
        f.refresh()
        assertEquals(1000L, f.state.durationsMs[4])
        assertEquals(3000L, f.state.unclassifiedMs)
        f.stop()
    }

    @Test fun emptyBatchesDoNotAlterMonotonicHolding() = runTest {
        val f = Fixture(this)
        val source = MutableSharedFlow<PolarHrData>()
        f.start(source)
        runCurrent()
        f.now = 10_000
        source.emit(batch(120))
        val first = f.state
        f.now = 11_000
        source.emit(batch())
        assertEquals(first, f.state)
        assertEquals(1L, f.hr.statistics.value.count)
        f.now = 13_000
        source.emit(batch(130))
        assertEquals(130, f.hr.reading.value!!.bpm)
        assertEquals(3000L, f.state.durationsMs[1])
        f.now = 15_000
        source.emit(batch(130))
        assertEquals(2000L, f.state.durationsMs[2])
        f.stop()
    }

    @Test fun otherStreamStartsFirstAndContactLossRemainsUnclassifiedWithoutBackfill() = runTest {
        val f = Fixture(this)
        val source = MutableSharedFlow<PolarHrData>()
        f.start(source, otherFirst = true)
        runCurrent()
        f.now = 10_000
        source.emit(batch(120))
        assertEquals(10_000L, f.state.unclassifiedMs)
        f.now = 13_000
        source.emit(PolarHrData(listOf(sample(120, contact = false))))
        assertNull(f.state.current)
        assertEquals("No sensor contact", f.hr.message.value)
        f.now = 15_000
        source.emit(batch(130))
        f.now = 16_000
        f.refresh()
        assertEquals(listOf(0L, 3000L, 1000L, 0L, 0L), f.state.durationsMs)
        assertEquals(12_000L, f.state.unclassifiedMs)
        assertEquals(f.session.state.value.elapsedMs, f.state.durationsMs.sum() + f.state.unclassifiedMs)
        f.stop()
    }

    @Test fun completionOrFailureSettlesAndRetryPreservesTotalsWithoutFillingGap() = runTest {
        for (fail in listOf(false, true)) {
            val f = Fixture(this)
            val end = CompletableDeferred<Unit>()
            f.start(flow { emit(batch(120)); end.await(); if (fail) error("Test HR failure") }, otherFirst = true)
            runCurrent()
            f.now = 3000
            end.complete(Unit)
            runCurrent()
            assertNull(f.state.current)
            assertEquals(3000L, f.state.durationsMs[1])
            assertEquals(SessionStatus.RUNNING, f.session.state.value.status)
            assertTrue(f.subscriptions.isActive(ACC))
            val source = MutableSharedFlow<PolarHrData>()
            f.now = 5000
            assertTrue(f.startHr(source))
            assertEquals(2000L, f.state.unclassifiedMs)
            runCurrent()
            f.now = 6000
            source.emit(batch(130))
            val before = f.state
            assertFalse(f.startHr(source))
            assertEquals(before, f.state)
            f.now = 7000
            f.refresh()
            assertEquals(1000L, f.state.durationsMs[2])
            assertEquals(3000L, f.state.unclassifiedMs)
            f.stop()
            runCurrent()
        }
    }

    @Test fun stopAndInterruptionFreezeBeforeDelayedCleanupAndDuplicateEnd() = runTest {
        for (reason in listOf("Stopped by user", "Disconnected", "Foreground left")) {
            val f = Fixture(this)
            val cleanup = CompletableDeferred<Unit>()
            f.start(flow {
                try { emit(batch(120)); awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
            })
            runCurrent()
            f.now = 3000
            f.cleanupMs = 5000
            f.session.stop(reason)
            val frozen = f.state
            assertNull(frozen.current)
            assertEquals(3000L, frozen.durationsMs[1])
            runCurrent()
            f.now = 20_000
            f.refresh()
            f.stop()
            assertEquals(frozen, f.state)
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(SessionStatus.STOPPED, f.session.state.value.status)
            assertEquals(3000L, f.session.state.value.elapsedMs)
            assertEquals(frozen, f.state)
        }
    }

    @Test fun lastStreamCompletionUsesSameInstantAsSessionEndAndRetainsFinalInterval() = runTest {
        val f = Fixture(this)
        val end = CompletableDeferred<Unit>()
        f.start(flow { emit(batch(120)); end.await() })
        runCurrent()
        f.now = 3456
        f.cleanupMs = 9000
        end.complete(Unit)
        runCurrent()
        assertEquals(SessionStatus.STOPPED, f.session.state.value.status)
        assertEquals(3456L, f.session.state.value.elapsedMs)
        assertEquals(3456L, f.state.durationsMs[1])
        assertEquals(0L, f.state.unclassifiedMs)
        assertNull(f.state.current)
        val frozen = f.state
        f.refresh()
        assertEquals(frozen, f.state)
    }

    @Test fun newStartResetsButDuplicateStartAndStaleSourceOrRefreshCannotChangeZones() = runTest {
        val f = Fixture(this)
        lateinit var old: FlowCollector<PolarHrData>
        val oldSource = object : Flow<PolarHrData> {
            override suspend fun collect(collector: FlowCollector<PolarHrData>) {
                old = collector
                collector.emit(batch(120))
                awaitCancellation()
            }
        }
        f.start(oldSource)
        runCurrent()
        val oldGeneration = f.session.state.value.generation
        f.now = 3000
        f.refresh()
        val before = f.state
        assertFalse(f.start(oldSource))
        assertEquals(before, f.state)
        f.stop()
        runCurrent()
        val next = MutableSharedFlow<PolarHrData>()
        assertTrue(f.start(next))
        assertEquals(HeartRateZoneState(), f.state)
        runCurrent()
        f.now = 10_000
        next.emit(batch(130))
        val fresh = f.state
        f.now = 11_000
        runCatching { old.emit(batch(160)) }
        f.refresh(oldGeneration)
        assertEquals(fresh, f.state)
        f.refresh()
        assertEquals(1000L, f.state.durationsMs[2])
        f.stop()
    }

    @Test fun sessionWithoutAnyHrKeepsFiveZerosAndAllRunningTimeUnclassified() = runTest {
        val f = Fixture(this)
        f.start(flow { awaitCancellation() }, otherFirst = true)
        runCurrent()
        f.now = 7500
        f.refresh()
        assertFalse(f.state.receivedValidHr)
        assertEquals(List(5) { 0L }, f.state.durationsMs)
        assertEquals(7500L, f.state.unclassifiedMs)
        f.stop()
        assertEquals(7500L, f.state.unclassifiedMs)
    }

    @Test fun acceptedRetryRejectsOldSubscriptionDataWithinSameSession() = runTest {
        val f = Fixture(this)
        lateinit var old: FlowCollector<PolarHrData>
        f.start(object : Flow<PolarHrData> {
            override suspend fun collect(collector: FlowCollector<PolarHrData>) {
                old = collector
                collector.emit(batch(120))
                awaitCancellation()
            }
        }, otherFirst = true)
        runCurrent()
        f.now = 2000
        f.subscriptions.stop(HR)
        runCurrent()
        val next = MutableSharedFlow<PolarHrData>()
        f.now = 4000
        assertTrue(f.startHr(next))
        runCurrent()
        next.emit(batch(130))
        val before = f.state
        runCatching { old.emit(batch(160)) }
        assertEquals(before, f.state)
        f.now = 5000
        f.refresh()
        assertEquals(listOf(0L, 2000L, 1000L, 0L, 0L), f.state.durationsMs)
        assertEquals(2000L, f.state.unclassifiedMs)
        f.stop()
    }

    @Test fun sessionUsesCapturedReceptionInstantForRunningOriginAndRefresh() = runTest {
        val f = Fixture(this)
        f.start(flow { awaitCancellation() })
        runCurrent()
        f.now = 20_000
        f.session.onValidData(10_000)
        f.session.refresh(f.session.state.value.generation, 13_000)
        assertEquals(3000L, f.session.state.value.elapsedMs)
        f.stop()
    }
}
