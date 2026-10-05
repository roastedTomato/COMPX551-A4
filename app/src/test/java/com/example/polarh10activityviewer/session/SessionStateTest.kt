package com.example.polarh10activityviewer.session

import com.example.polarh10activityviewer.ble.checkedDataTypes
import com.example.polarh10activityviewer.ble.confirmReadiness
import com.example.polarh10activityviewer.ble.DataSubscriptions
import com.example.polarh10activityviewer.ble.ConnectionDevice
import com.example.polarh10activityviewer.ble.HeartRateStatistics
import com.example.polarh10activityviewer.ble.LatestHeartRate
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.sensor.AccSampleProcessor
import com.example.polarh10activityviewer.sensor.EcgBuffer

import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType.*
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarAccelerometerData
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import com.example.polarh10activityviewer.sensor.AccSample

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStateTest {
    private class Fixture(scope: CoroutineScope) {
        var now = 0L
        var connected = true
        val hr = LatestHeartRate()
        val accSamples = mutableListOf<AccSample>()
        val acc = AccSampleProcessor { accSamples.add(it) }
        val ecg = EcgBuffer()
        val saved = mutableListOf<SessionRecord>()
        lateinit var session: SessionController
        val subscriptions = DataSubscriptions(scope) { type, status ->
            hr.onSubscriptionState(type, status)
            acc.onSubscriptionState(type, status)
            if (type == ACC && status == SubscriptionStatus.STARTING) accSamples.clear()
            ecg.onSubscriptionState(type, status)
            session.onSubscriptionState(type, status)
        }
        init {
            session = SessionController(subscriptions, { now },
                { hr.reset(); acc.clear(); accSamples.clear(); ecg.clear() }, hr::clear,
                { SessionSummary(minimumHr = hr.statistics.value.min, maximumHr = hr.statistics.value.max,
                    meanHr = hr.statistics.value.average, validHrCount = hr.statistics.value.count) },
                onSummaryFrozen = { saved.add(it) })
        }
        val state get() = session.state.value
        fun receive(type: PolarDeviceDataType, value: Int) {
            when (type) {
                HR -> if (hr.receive(PolarHrData(listOf(
                    PolarHrData.PolarHrSample(value, 0, 0, emptyList(), emptyList(), false, true, true)
                )))) session.onValidData()
                ACC -> acc.receive(PolarAccelerometerData(listOf(
                    PolarAccelerometerData.PolarAccelerometerDataSample(now, value, 0, 1000)
                )))
                ECG -> ecg.receive(listOf(EcgSample(now, value)))
                else -> error("Unexpected test type")
            }
            if (type != HR) session.onValidData()
        }
        fun startStream(type: PolarDeviceDataType, source: Flow<Int>): Boolean {
            val generation = state.generation
            return subscriptions.start(type, { connected && session.accepts(generation) },
                { connected && session.accepts(generation) }, { source }, { receive(type, it) })
        }
        fun running(value: Int = 1) = flow { emit(value); awaitCancellation() }
        fun start(types: List<PolarDeviceDataType> = checkedDataTypes) = session.start(connected) {
            types.forEach { startStream(it, running()) }
        }
    }

    @Test fun userStopResetClearsAllThreeStreamsAndAnEmptyAttempt() = runTest {
        val f = Fixture(this)
        f.start(); runCurrent()
        assertTrue(f.ecg.samples.value.isNotEmpty())
        f.session.stop("User Stop", interrupted = false, reset = true); runCurrent()
        assertEquals(SessionStatus.IDLE, f.state.status)
        assertNull(f.state.record); assertEquals(0L, f.state.elapsedMs)
        assertNull(f.hr.reading.value); assertEquals(HeartRateStatistics(), f.hr.statistics.value)
        assertTrue(f.accSamples.isEmpty()); assertTrue(f.ecg.samples.value.isEmpty())
        assertTrue(f.subscriptions.states.value.values.all { it.status == SubscriptionStatus.IDLE })
        assertTrue(f.connected)
        assertTrue(f.session.start(true) { f.startStream(HR, flow { awaitCancellation() }) })
        runCurrent(); assertEquals(SessionStatus.STARTING, f.state.status)
        f.session.stop("Empty Stop", interrupted = false, reset = true); runCurrent()
        assertEquals(SessionStatus.IDLE, f.state.status); assertNull(f.state.record)
    }

    @Test fun userStopAtTheTimeLimitStillResetsAfterFreezing() = runTest {
        val f = Fixture(this)
        f.start(); runCurrent()
        f.now = SessionController.TIME_LIMIT_MS
        f.session.stop("User Stop", interrupted = false, reset = true); runCurrent()
        assertEquals(SessionStatus.IDLE, f.state.status)
        assertEquals(0L, f.state.elapsedMs); assertNull(f.state.record)
    }

    @Test fun stopThenRecheckAllowsAnotherSessionWhenHrNotificationsAreDisabled() = runTest {
        val f = Fixture(this)
        val features = mutableSetOf(
            PolarBleSdkFeature.FEATURE_HR, PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING
        )
        var notificationsEnabled = true
        var starts = 0
        fun recheck() = features.toList().all { feature ->
            features.confirmReadiness(feature) { notificationsEnabled }
        }
        fun start() = f.session.start(f.connected && recheck()) {
            checkedDataTypes.forEach { type ->
                f.startStream(type, flow {
                    val feature = if (type == HR) PolarBleSdkFeature.FEATURE_HR
                        else PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING
                    check(features.confirmReadiness(feature) { notificationsEnabled })
                    if (type == HR) notificationsEnabled = true
                    starts++
                    try { emit(80); awaitCancellation() }
                    finally { if (type == HR) notificationsEnabled = false }
                })
            }
        }
        assertTrue(start())
        runCurrent()
        assertEquals(SessionStatus.RUNNING, f.state.status)
        f.session.stop("Stop")
        runCurrent()
        assertEquals(SessionStatus.STOPPED, f.state.status)
        assertFalse(notificationsEnabled)
        assertTrue(f.connected)
        assertTrue(recheck())
        assertTrue(start())
        runCurrent()
        assertEquals(SessionStatus.RUNNING, f.state.status)
        assertEquals(6, starts)
        assertEquals(2L, f.state.generation)
        f.session.stop("Done")
        runCurrent()
    }

    @Test fun prerequisitesRejectWithoutClearingAndCannotStartStreamsOutsideSession() = runTest {
        val f = Fixture(this)
        f.receive(ACC, 7)
        var attempts = 0
        assertFalse(f.session.start(false) { attempts++ })
        assertFalse(f.startStream(HR, f.running()))
        assertEquals(0, attempts)
        assertEquals(7, f.accSamples.single().x)
        assertEquals(SessionStatus.IDLE, f.state.status)
        assertEquals(0L, f.state.generation)
    }

    @Test fun firstSampleStartsClockOnceAndRefreshUsesElapsedTimeNotTickCount() = runTest {
        val f = Fixture(this)
        val source = MutableSharedFlow<Int>()
        f.session.start(true) { f.startStream(HR, source); f.startStream(ACC, source) }
        runCurrent()
        f.now = 5_000
        f.session.refresh(f.state.generation)
        assertEquals(SessionStatus.STARTING, f.state.status)
        assertEquals(0L, f.state.elapsedMs)
        source.emit(70)
        runCurrent()
        assertEquals(SessionStatus.RUNNING, f.state.status)
        f.now = 6_500
        source.emit(71)
        runCurrent()
        f.session.refresh(f.state.generation)
        assertEquals(1_500L, f.state.elapsedMs)
        f.now = 18_000
        f.session.refresh(f.state.generation)
        assertEquals(13_000L, f.state.elapsedMs)
        f.session.stop("Stop")
    }

    @Test fun repeatedStartAndStopDoNotClearActiveDataOrCountCleanupTime() = runTest {
        val f = Fixture(this)
        val cleanup = CompletableDeferred<Unit>()
        f.session.start(true) {
            f.startStream(HR, flow {
                try { emit(80); awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
            })
            f.startStream(ACC, f.running(9))
        }
        runCurrent()
        val generation = f.state.generation
        f.now = 1_000
        assertFalse(f.start())
        assertEquals(80, f.hr.reading.value?.bpm)
        assertEquals(HeartRateStatistics(1, 80, 80, 80), f.hr.statistics.value)
        assertEquals(9, f.accSamples.single().x)
        f.session.stop("User stopped")
        assertEquals(SessionStatus.STOPPING, f.state.status)
        assertEquals(1_000L, f.state.elapsedMs)
        assertNull(f.hr.reading.value)
        runCurrent()
        f.now = 20_000
        f.session.refresh(generation)
        f.session.stop("Must not replace reason")
        assertFalse(f.start())
        assertFalse(f.startStream(ECG, f.running()))
        assertEquals(1_000L, f.state.elapsedMs)
        assertEquals("User stopped", f.state.endReason)
        assertTrue(f.connected)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(SessionStatus.STOPPED, f.state.status)
        assertEquals(1_000L, f.state.elapsedMs)
        assertEquals(9, f.accSamples.single().x)
        assertEquals(HeartRateStatistics(1, 80, 80, 80), f.hr.statistics.value)
        assertTrue(f.session.start(true) { f.startStream(HR, flow { awaitCancellation() }) })
        assertEquals(generation + 1, f.state.generation)
        assertEquals(0L, f.state.elapsedMs)
        assertTrue(f.accSamples.isEmpty())
        assertEquals(HeartRateStatistics(), f.hr.statistics.value)
        f.session.stop("Done")
    }

    @Test fun newSessionClearsBuffersEvenWhenThoseStreamsCannotStart() = runTest {
        val f = Fixture(this)
        checkedDataTypes.forEach { f.receive(it, 42) }
        assertTrue(f.session.start(true) {
            f.startStream(HR, flow { awaitCancellation() })
            f.subscriptions.unavailable(ACC, "ACC configuration incomplete")
            f.subscriptions.unavailable(ECG, "ECG not ready")
        })
        assertNull(f.hr.reading.value)
        assertTrue(f.accSamples.isEmpty())
        assertTrue(f.ecg.samples.value.isEmpty())
        assertEquals(SessionStatus.STARTING, f.state.status)
        assertEquals("ECG not ready", f.subscriptions.states.value.getValue(ECG).error)
        f.session.stop("Stopped before data")
        runCurrent()
        assertEquals(SessionStatus.STOPPED, f.state.status)
        assertEquals(0L, f.state.elapsedMs)
    }

    @Test fun immediateFailureCannotEndSessionBeforeOtherStartupAttempts() = runTest {
        val f = Fixture(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val attempted = mutableListOf<PolarDeviceDataType>()
        assertTrue(f.session.start(true) {
            for (type in checkedDataTypes) {
                attempted += type
                if (type == ECG) f.startStream(type, f.running(55))
                else f.startStream(type, flow { error("Immediate $type failure") })
            }
        })
        assertEquals(checkedDataTypes, attempted)
        assertEquals(SessionStatus.RUNNING, f.state.status)
        assertEquals(55, f.ecg.samples.value.single().voltage)
        f.session.stop("Done")
    }

    @Test fun allStartupFailuresEndAtZeroAndAllowANewAttempt() = runTest {
        val f = Fixture(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        f.session.start(true) {
            checkedDataTypes.forEach { f.startStream(it, flow { error("Cannot start") }) }
        }
        assertEquals(SessionStatus.STOPPED, f.state.status)
        assertEquals(0L, f.state.elapsedMs)
        assertTrue(f.state.endReason!!.contains("No data"))
        assertTrue(f.start(listOf(HR)))
        assertEquals(SessionStatus.RUNNING, f.state.status)
        f.session.stop("Done")
    }

    @Test fun retryRechecksOneStreamAndPreservesSessionTimeAndOtherReadings() = runTest {
        val f = Fixture(this)
        f.session.start(true) {
            f.startStream(HR, f.running(75))
            f.startStream(ACC, f.running(5))
            f.startStream(ECG, flow { emit(-10); error("ECG failure") })
        }
        runCurrent()
        val generation = f.state.generation
        assertEquals(SessionStatus.RUNNING, f.state.status)
        assertEquals(-10, f.ecg.samples.value.single().voltage)
        assertFalse(f.startStream(HR, f.running()))
        f.now = 4_000
        var settingsChecks = 0
        assertTrue(f.startStream(ECG, flow { settingsChecks++; emit(20); awaitCancellation() }))
        assertTrue(f.ecg.samples.value.isEmpty())
        assertEquals(75, f.hr.reading.value?.bpm)
        assertEquals(5, f.accSamples.single().x)
        runCurrent()
        f.session.refresh(generation)
        assertEquals(1, settingsChecks)
        assertEquals(4_000L, f.state.elapsedMs)
        assertEquals(generation, f.state.generation)
        assertNull(f.subscriptions.states.value.getValue(ECG).error)
        f.session.stop("Done")
    }

    @Test fun naturalCompletionEndsOnlyAfterLastTaskAndFreezesTime() = runTest {
        val f = Fixture(this)
        val end = CompletableDeferred<Unit>()
        f.session.start(true) {
            f.startStream(HR, flow { emit(70) })
            f.startStream(ECG, flow { emit(10); end.await() })
        }
        runCurrent()
        assertEquals(SessionStatus.RUNNING, f.state.status)
        f.now = 2_000
        end.complete(Unit)
        runCurrent()
        assertEquals(SessionStatus.STOPPED, f.state.status)
        assertEquals("All streams ended.", f.state.endReason)
        assertEquals(2_000L, f.state.elapsedMs)
        assertNull(f.hr.reading.value)
        assertEquals(10, f.ecg.samples.value.single().voltage)
        f.now = 9_000
        f.session.refresh(f.state.generation)
        assertEquals(2_000L, f.state.elapsedMs)
    }

    @Test fun oldDataAndTimerCannotAffectStoppedOrNewSession() = runTest {
        val f = Fixture(this)
        lateinit var oldCollector: FlowCollector<Int>
        f.session.start(true) {
            f.startStream(HR, object : Flow<Int> {
                override suspend fun collect(collector: FlowCollector<Int>) {
                    oldCollector = collector
                    collector.emit(70)
                    awaitCancellation()
                }
            })
        }
        runCurrent()
        val oldGeneration = f.state.generation
        f.now = 1_000
        f.session.stop("Stop")
        runCatching { oldCollector.emit(99) }
        runCurrent()
        assertNull(f.hr.reading.value)
        f.now = 2_000
        f.start(listOf(HR))
        runCurrent()
        f.now = 3_000
        f.session.refresh(oldGeneration)
        runCatching { oldCollector.emit(99) }
        runCurrent()
        assertEquals(0L, f.state.elapsedMs)
        assertEquals(1, f.hr.reading.value?.bpm)
        assertEquals(HeartRateStatistics(1, 1, 1, 1), f.hr.statistics.value)
        f.session.refresh(f.state.generation)
        assertEquals(1_000L, f.state.elapsedMs)
        f.session.stop("Done")
    }

    @Test fun interruptionFreezesAndCleansAllStreamsWithoutAutomaticResume() = runTest {
        val f = Fixture(this)
        for (reason in listOf("Disconnected", "Bluetooth unavailable", "Permissions lost")) {
            f.connected = true
            f.start()
            runCurrent()
            f.now += 500
            f.session.stop(reason)
            f.connected = false
            runCurrent()
            assertEquals(SessionStatus.STOPPED, f.state.status)
            assertEquals(reason, f.state.endReason)
            assertEquals(500L, f.state.elapsedMs)
            assertNull(f.hr.reading.value)
            assertEquals(HeartRateStatistics(1, 1, 1, 1), f.hr.statistics.value)
            assertTrue(f.accSamples.isNotEmpty())
            assertTrue(f.ecg.samples.value.isNotEmpty())
            assertTrue(checkedDataTypes.none(f.subscriptions::isActive))
            f.connected = true
            f.now += 1_000
            f.session.refresh(f.state.generation)
            assertEquals(SessionStatus.STOPPED, f.state.status)
            assertEquals(500L, f.state.elapsedMs)
        }
    }

    @Test fun invalidHrIsReceivingButCannotStartTimingUntilValidDataArrives() = runTest {
        val f = Fixture(this)
        val source = MutableSharedFlow<Int>()
        f.session.start(true) { f.startStream(HR, source) }
        runCurrent()
        f.now = 1_000
        source.emit(0)
        runCurrent()
        assertEquals(SubscriptionStatus.RECEIVING, f.subscriptions.states.value.getValue(HR).status)
        assertEquals(SessionStatus.STARTING, f.state.status)
        assertEquals(0L, f.state.elapsedMs)
        assertEquals(0L, f.hr.statistics.value.count)
        f.now = 3_000
        source.emit(80)
        runCurrent()
        assertEquals(SessionStatus.RUNNING, f.state.status)
        f.now = 4_000
        source.emit(-1)
        runCurrent()
        f.session.refresh(f.state.generation)
        assertEquals(SessionStatus.RUNNING, f.state.status)
        assertEquals(1_000L, f.state.elapsedMs)
        assertNull(f.hr.reading.value)
        assertEquals(1L, f.hr.statistics.value.count)
        f.session.stop("Done")
    }

    @Test fun mixedHrBatchCanStartTimingEvenWithInvalidFinalSample() = runTest {
        val f = Fixture(this)
        val source = MutableSharedFlow<PolarHrData>()
        f.session.start(true) {
            val generation = f.state.generation
            f.subscriptions.start(HR, { true }, { f.session.accepts(generation) }, { source }) {
                if (f.hr.receive(it)) f.session.onValidData()
            }
        }
        runCurrent()
        f.now = 5_000
        source.emit(PolarHrData(listOf(80, 0).map {
            PolarHrData.PolarHrSample(it, 0, 0, emptyList(), emptyList(), false, true, true)
        }))
        runCurrent()
        assertEquals(SessionStatus.RUNNING, f.state.status)
        assertNull(f.hr.reading.value)
        assertEquals(HeartRateStatistics(1, 80, 80, 80), f.hr.statistics.value)
        f.now = 6_000
        f.session.refresh(f.state.generation)
        assertEquals(1_000L, f.state.elapsedMs)
        f.session.stop("Done")
    }

    @Test fun accOrEcgCanStartWhileHrIsInvalidAndHrCannotResetTheirClock() = runTest {
        for (type in listOf(ACC, ECG)) {
            val f = Fixture(this)
            val hrSource = MutableSharedFlow<Int>()
            val otherSource = MutableSharedFlow<Int>()
            f.session.start(true) { f.startStream(HR, hrSource); f.startStream(type, otherSource) }
            runCurrent()
            hrSource.emit(0)
            runCurrent()
            assertEquals(SessionStatus.STARTING, f.state.status)
            f.now = 1_000
            otherSource.emit(7)
            runCurrent()
            assertEquals(SessionStatus.RUNNING, f.state.status)
            f.now = 2_000
            hrSource.emit(80)
            runCurrent()
            f.session.refresh(f.state.generation)
            assertEquals(1_000L, f.state.elapsedMs)
            f.session.stop("Done")
            runCurrent()
        }
    }

    @Test fun failedHrRetryRetainsTotalsAndNewSessionWithoutHrResetsThem() = runTest {
        val f = Fixture(this)
        f.session.start(true) {
            f.startStream(HR, flow { emit(80); error("HR failure") })
            f.startStream(ACC, f.running(9))
        }
        runCurrent()
        assertNull(f.hr.reading.value)
        assertEquals(1L, f.hr.statistics.value.count)
        assertEquals(SessionStatus.RUNNING, f.state.status)
        f.now = 1_000
        assertTrue(f.startStream(HR, f.running(100)))
        runCurrent()
        assertEquals(HeartRateStatistics(2, 180, 80, 100), f.hr.statistics.value)
        assertFalse(f.startStream(HR, f.running()))
        assertEquals(2L, f.hr.statistics.value.count)
        assertEquals(9, f.accSamples.single().x)
        f.session.stop("Done")
        runCurrent()
        assertEquals(2L, f.hr.statistics.value.count)
        assertTrue(f.start(listOf(ACC)))
        runCurrent()
        assertEquals(HeartRateStatistics(), f.hr.statistics.value)
        f.session.stop("Done")
    }

    @Test fun pauseExcludesTimeAndResumeKeepsIdentityStatisticsAndOriginalStart() = runTest {
        val f = Fixture(this)
        assertFalse(f.session.pause())
        assertFalse(f.session.resume(true) { error("Not paused") })
        f.start(listOf(HR)); runCurrent()
        val record = f.state.record!!
        f.now = 2500
        assertTrue(f.session.pause())
        assertEquals(SessionStatus.PAUSING, f.state.status)
        assertFalse(f.session.accepts(f.state.generation))
        assertNull(f.state.record!!.endedAt)
        assertFalse(f.start())
        assertFalse(f.session.resume(true) { error("Cleanup still owns the stream") })
        runCurrent()
        assertEquals(SessionStatus.PAUSED, f.state.status)
        f.now = 100_000
        f.session.refresh(f.state.generation)
        assertEquals(2500L, f.state.elapsedMs)
        assertFalse(f.startStream(HR, f.running()))
        assertFalse(f.session.resume(false) { error("Disconnected") })
        assertTrue(f.session.resume(true) { f.startStream(HR, f.running(90)) })
        runCurrent()
        assertEquals(record.id, f.state.record!!.id)
        assertEquals(record.startedAt, f.state.record!!.startedAt)
        assertEquals(2L, f.hr.statistics.value.count)
        f.now = 101_500
        f.session.refresh(f.state.generation)
        assertEquals(4000L, f.state.elapsedMs)
        f.session.stop("Stop", interrupted = false); runCurrent()
        assertEquals(4000L, f.state.record!!.durationMs)
        assertEquals(2L, f.state.record!!.summary.validHrCount)
    }

    @Test fun explicitStopOfPausedSessionFreezesOnlyActiveTime() = runTest {
        val f = Fixture(this)
        f.start(listOf(HR)); runCurrent()
        f.now = 1000; f.session.pause(); runCurrent()
        f.now = 999_000
        f.session.stop("Stopped by user.", interrupted = false)
        assertEquals(SessionStatus.STOPPED, f.state.status)
        assertEquals(1000L, f.state.record!!.durationMs)
        assertEquals("Stopped by user.", f.state.record!!.endReason)
        assertFalse(f.session.resume(true) { error("Session ended") })
    }

    @Test fun resumeWaitsForRealDataAndAllFailedResumeAttemptsEndExistingSession() = runTest {
        val f = Fixture(this)
        f.start(listOf(HR)); runCurrent()
        f.now = 1000; f.session.pause(); runCurrent()
        val id = f.state.record!!.id
        val source = MutableSharedFlow<Int>()
        f.now = 10_000
        f.session.resume(true) { f.startStream(HR, source) }; runCurrent()
        f.now = 20_000; f.session.refresh(f.state.generation)
        assertEquals(1000L, f.state.elapsedMs)
        source.emit(80); runCurrent()
        f.now = 21_000; f.session.refresh(f.state.generation)
        assertEquals(2000L, f.state.elapsedMs)
        f.session.pause(); runCurrent()
        f.session.resume(true) { f.startStream(HR, flow { error("Resume failed") }) }; runCurrent()
        assertEquals(SessionStatus.STOPPED, f.state.status)
        assertEquals(id, f.state.record!!.id)
        assertEquals(2000L, f.state.record!!.durationMs)
    }

    @Test fun resumedSessionStillEndsAtFourHoursOfActiveTime() = runTest {
        val f = Fixture(this)
        f.start(listOf(HR)); runCurrent()
        f.now = SessionController.TIME_LIMIT_MS - 1000
        f.session.pause(); runCurrent()
        f.now += 999_000
        f.session.resume(true) { f.startStream(HR, f.running()) }; runCurrent()
        f.now += 1000
        f.session.refresh(f.state.generation); runCurrent()
        assertEquals("TIME_LIMIT", f.state.endReason)
        assertEquals(SessionController.TIME_LIMIT_MS, f.state.record!!.durationMs)
    }

    @Test fun invalidOnlyHrCompletionEndsAtZeroWithoutStatistics() = runTest {
        val f = Fixture(this)
        f.session.start(true) { f.startStream(HR, flow { emit(0); emit(-1) }) }
        runCurrent()
        assertEquals(SessionStatus.STOPPED, f.state.status)
        assertEquals(0L, f.state.elapsedMs)
        assertEquals(HeartRateStatistics(), f.hr.statistics.value)
        assertNull(f.hr.message.value)
    }

    @Test fun leavingDuringInitialStartPausesWithoutSavingAndContinuesSameId() = runTest {
        val f = Fixture(this)
        f.session.start(true) { f.startStream(HR, MutableSharedFlow()) }
        val id = f.state.record!!.id
        assertTrue(f.session.pause())
        runCurrent()
        assertEquals(SessionStatus.PAUSED, f.state.status)
        assertEquals(0L, f.state.elapsedMs)
        assertNull(f.state.record!!.startedAt)
        assertTrue(f.saved.isEmpty())
        f.now = 100_000
        assertTrue(f.session.resume(true) { f.startStream(HR, f.running(80)) })
        runCurrent()
        assertEquals(SessionStatus.RUNNING, f.state.status)
        assertEquals(id, f.state.record!!.id)
        assertEquals(0L, f.state.elapsedMs)
        f.session.stop("Done"); runCurrent()
    }

    @Test fun leavingDuringContinueRetainsPreviousActiveTime() = runTest {
        val f = Fixture(this)
        f.start(); runCurrent()
        f.now = 2000; f.session.pause(); runCurrent()
        val record = f.state.record!!
        f.now = 90_000
        f.session.resume(true) { f.startStream(HR, MutableSharedFlow()) }
        runCurrent()
        assertTrue(f.session.pause()); runCurrent()
        assertEquals(SessionStatus.PAUSED, f.state.status)
        assertEquals(2000L, f.state.elapsedMs)
        assertEquals(record.id, f.state.record!!.id)
        assertEquals(record.startedAt, f.state.record!!.startedAt)
        assertTrue(f.saved.isEmpty())
        f.session.stop("Done"); runCurrent()
    }

    @Test fun connectionLossWhilePausingOrPausedKeepsSessionUntilOneExplicitSave() = runTest {
        val f = Fixture(this)
        f.start(); runCurrent()
        f.now = 1500
        f.session.pause()
        f.session.connectionUnavailable("Disconnect during cleanup")
        runCurrent()
        val paused = f.state
        assertEquals(SessionStatus.PAUSED, paused.status)
        f.connected = false
        f.now = 800_000
        f.session.connectionUnavailable("Bluetooth off")
        f.session.connectionUnavailable("Reconnect failed")
        assertFalse(f.session.pause())
        f.session.refresh(f.state.generation)
        assertEquals(paused, f.state)
        assertTrue(f.saved.isEmpty())
        assertFalse(f.session.resume(false) { error("Cannot resume disconnected") })
        f.connected = true
        f.session.resume(true) { f.startStream(HR, f.running(80)) }; runCurrent()
        f.now += 1000
        f.session.stop("Stopped by user.", interrupted = false, reset = true)
        f.session.stop("Stopped by user.", interrupted = false, reset = true)
        runCurrent()
        assertEquals(1, f.saved.size)
        assertEquals(paused.record!!.id, f.saved.single().id)
        assertEquals(2500L, f.saved.single().durationMs)
        assertFalse(f.saved.single().interrupted)
        assertEquals(SessionStatus.IDLE, f.state.status)
    }

    @Test fun lateStreamDataAfterPauseDoesNotChangeStatistics() = runTest {
        val f = Fixture(this)
        lateinit var collector: FlowCollector<Int>
        f.session.start(true) { f.startStream(HR, flow {
            collector = this
            emit(80)
            awaitCancellation()
        }) }
        runCurrent()
        val generation = f.state.generation
        f.now = 1000; f.session.pause()
        runCatching { collector.emit(190) }
        runCurrent()
        f.now = 99_000; f.session.refresh(generation)
        assertEquals(HeartRateStatistics(1, 80, 80, 80), f.hr.statistics.value)
        assertEquals(1000L, f.state.elapsedMs)
        assertEquals(SessionStatus.PAUSED, f.state.status)
        assertTrue(f.saved.isEmpty())
        f.session.stop("Done"); runCurrent()
    }

    @Test fun connectionLossDuringRunningStillEndsAndSaves() = runTest {
        val f = Fixture(this)
        f.start(); runCurrent()
        f.now = 500
        f.session.connectionUnavailable("Connection ended")
        runCurrent()
        assertEquals(SessionStatus.STOPPED, f.state.status)
        assertEquals(500L, f.saved.single().durationMs)
        assertTrue(f.saved.single().interrupted)
    }

    @Test fun openSessionOnlyAcceptsItsOriginalDevice() {
        val record = SessionRecord("same-session", 0, ConnectionDevice("H10", "ORIGINAL"))
        for (status in listOf(SessionStatus.STARTING, SessionStatus.RUNNING, SessionStatus.PAUSING, SessionStatus.PAUSED)) {
            val state = SessionState(status = status, record = record)
            assertTrue(state.acceptsDevice("ORIGINAL"))
            assertFalse(state.acceptsDevice("OTHER"))
            assertFalse(state.acceptsDevice(null))
        }
        assertTrue(SessionState().acceptsDevice("OTHER"))
        assertTrue(SessionState(SessionStatus.STOPPED, record = record).acceptsDevice("OTHER"))
    }
}
