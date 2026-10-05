package com.example.polarh10activityviewer.ble

import com.example.polarh10activityviewer.heartrate.LatestHeartRate
import com.example.polarh10activityviewer.chart.ChartKind
import com.example.polarh10activityviewer.chart.LiveCharts
import com.example.polarh10activityviewer.heartrate.HeartRateZones
import com.example.polarh10activityviewer.history.HrHistory
import com.example.polarh10activityviewer.history.MotionHistory
import com.example.polarh10activityviewer.motion.StepDetector
import com.example.polarh10activityviewer.sensor.AccSampleProcessor
import com.example.polarh10activityviewer.sensor.EcgBuffer
import com.example.polarh10activityviewer.storage.SignalBuffer
import com.example.polarh10activityviewer.storage.RawEcg
import com.example.polarh10activityviewer.session.SessionRecord
import com.example.polarh10activityviewer.sensor.h10EcgSamples
import com.example.polarh10activityviewer.session.SessionController
import com.example.polarh10activityviewer.session.SessionStatus
import com.example.polarh10activityviewer.session.SessionSummary
import com.example.polarh10activityviewer.session.SessionSnapshot
import com.example.polarh10activityviewer.storage.SessionStorage

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresPermission
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.PolarBleDisconnectInfo
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarSensorSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class ScanStatus(val message: String) {
    NOT_STARTED("Scan not started."),
    SCANNING("Scanning for Polar H10 (up to 30 seconds)..."),
    STOPPED("Scan stopped."),
    TIMED_OUT("Scan finished after 30 seconds."),
    INTERRUPTED("Scan stopped because Bluetooth is unavailable."),
    ERROR("Scan failed.")
}

data class ScanState(
    val status: ScanStatus = ScanStatus.NOT_STARTED,
    val devices: List<PolarDeviceInfo> = emptyList(),
    val error: String? = null
)

enum class ConnectionStatus(val message: String) {
    NOT_CONNECTED("Not connected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    DISCONNECTING("Disconnecting")
}

data class ConnectionDevice(val name: String, val deviceId: String)

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.NOT_CONNECTED,
    val device: ConnectionDevice? = null,
    val error: String? = null,
    val message: String? = null,
    val disconnectError: String? = null
)

class PolarBleManager(context: Context) {
    var onBluetoothStateChanged: (() -> Unit)? = null
    private val appContext = context.applicationContext
    internal val storage = SessionStorage.get(appContext)
    private val savedDeviceStore = SavedDeviceStore.get(appContext)
    val savedDevicesState = savedDeviceStore.state
    fun clearSavedDevices() = savedDeviceStore.clear()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var api: PolarBleApi? = null
    private var pendingCleanup: PolarBleApi? = null
    private val scanScope = CoroutineScope(Dispatchers.Main.immediate)
    private var scanJob: Job? = null
    private var scanGeneration = 0
    private val mutableScanState = MutableStateFlow(ScanState())
    val scanState = mutableScanState.asStateFlow()
    private val mutableConnectionState = MutableStateFlow(ConnectionState())
    val connectionState = mutableConnectionState.asStateFlow()
    private val deviceBattery = DeviceBattery()
    val batteryLevel = deviceBattery.level
    private var connectionTimeout: Runnable? = null
    private var connectionConfirmed = false
    private var sdkUsedForConnection = false
    private val readinessScope = CoroutineScope(Dispatchers.Main.immediate)
    private var readinessJob: Job? = null
    private var readinessGeneration = 0
    private val readyFeatures = mutableSetOf<PolarBleSdkFeature>()
    private val unavailableFeatures = mutableSetOf<PolarBleSdkFeature>()
    private val mutableDataReadiness = MutableStateFlow(checkedDataTypes.associateWith { DataReadiness() })
    val dataReadiness = mutableDataReadiness.asStateFlow()
    private val latestHeartRate = LatestHeartRate()
    private val hrHistory = HrHistory()
    private val motionHistory = MotionHistory()
    private val mutableLastSnapshot = MutableStateFlow<SessionSnapshot?>(null)
    internal val lastSnapshot = mutableLastSnapshot.asStateFlow()
    private var previousHrArrival: Long? = null
    val heartRate = latestHeartRate.reading
    val heartRateStatistics = latestHeartRate.statistics
    val heartRateMessage = latestHeartRate.message
    private val heartRateZones = HeartRateZones()
    internal val heartRateZoneState = heartRateZones.state
    private val stepDetector = StepDetector(SystemClock::elapsedRealtime)
    internal val stepState = stepDetector.state
    private val accProcessor = AccSampleProcessor { stepDetector.receive(it) }
    private var signals = SignalBuffer()
    private var checkpointBucket = 0L
    private var checkpointAt = -1000L
    private var discardingRecording = false
    private var pauseContinuations = emptySet<PolarDeviceDataType>()
    private val ecgBuffer = EcgBuffer()
    internal val liveCharts = LiveCharts { ecgBuffer.samples.value }
    private val streamSettingsMutex = Mutex()
    private val dataSubscriptions: DataSubscriptions = DataSubscriptions(
        CoroutineScope(Dispatchers.Main.immediate)
    ) { type, status ->
        val eventTime = SystemClock.elapsedRealtime()
        if (session.checkTimeLimit(eventTime)) return@DataSubscriptions
        if (type == PolarDeviceDataType.HR) hrHistory.onSubscriptionState(status)
        if (type == PolarDeviceDataType.HR && status == SubscriptionStatus.STARTING) previousHrArrival = null
        if (type == PolarDeviceDataType.HR && status != SubscriptionStatus.RECEIVING && session.state.value.ongoing) {
            session.refresh(session.state.value.generation, eventTime)
            heartRateZones.clearCurrent(session.state.value.elapsedMs)
        }
        latestHeartRate.onSubscriptionState(type, status)
        accProcessor.onSubscriptionState(type, status)
        if (type == PolarDeviceDataType.ACC && session.state.value.ongoing) {
            stepDetector.onSubscriptionState(status)
        }
        if (type == PolarDeviceDataType.ACC) motionHistory.onSubscriptionState(status, stepDetector.segment)
        liveCharts.onSubscriptionState(type, status, session.elapsedAt(eventTime), stepDetector.segment)
        if (status != SubscriptionStatus.RECEIVING) {
            if (type == PolarDeviceDataType.ECG) signals.endEcg(session.elapsedAt(eventTime))
        }
        ecgBuffer.onSubscriptionState(type, status)
        session.onSubscriptionState(type, status, eventTime)
    }
    internal val subscriptionStates = dataSubscriptions.states
    private val session: SessionController = SessionController(
        dataSubscriptions,
        SystemClock::elapsedRealtime,
        clearAllReadings = ::resetSessionReadings,
        stopSessionReadings = ::stopSessionReadings,
        readSummary = ::readSessionSummary,
        onSummaryFrozen = ::saveSessionSnapshot,
        canStart = { !storage.saves.state.value.blocksStart && !storage.recording.state.value.blocked },
        onResume = ::resumeSessionHistory,
        onPaused = { record -> checkpointSignals(record, true) }
    )
    init { storage.recording.onFailure = { session.pause() } }
    internal val sessionState = session.state

    @MainThread
    fun startSession(): Boolean = session.start(
        eligible = connectedForData() && mutableDataReadiness.value.values.any {
            it.status == DataReadinessStatus.READY && it.configurationComplete
        },
        device = mutableConnectionState.value.device
    ) {
        startSessionStreams()
    }

    private fun startSessionStreams() {
        checkedDataTypes.forEach { type ->
            val readiness = mutableDataReadiness.value.getValue(type)
            if (readiness.status == DataReadinessStatus.READY && readiness.configurationComplete) {
                startStream(type)
            } else {
                dataSubscriptions.unavailable(type, readiness.error ?: if (readiness.status == DataReadinessStatus.READY)
                    "$type configuration needs confirmation." else "$type: ${readiness.status.message}.")
            }
        }
    }

    @MainThread
    fun stopSession() = session.stop("Stopped by user.", interrupted = false, reset = true)

    @MainThread
    fun pauseSession() = session.pause()

    @MainThread
    fun resumeSession() = session.resume(!storage.recording.state.value.blocked && connectedForData() &&
        sessionState.value.acceptsDevice(mutableConnectionState.value.device?.deviceId) && mutableDataReadiness.value.values.any {
        it.status == DataReadinessStatus.READY && it.configurationComplete
    }, ::startSessionStreams)

    fun refreshSessionTime(generation: Long) {
        session.refresh(generation)
        if (session.accepts(generation)) {
            stepDetector.refresh()
            if (session.state.value.status == SessionStatus.RUNNING) {
                val elapsed = session.state.value.elapsedMs
                liveCharts.recordMotion(elapsed, stepState.value,
                    warmingUp = stepDetector.isWarmingUp, segment = stepDetector.segment)
                motionHistory.record(elapsed, stepState.value,
                    warmingUp = stepDetector.isWarmingUp, segment = stepDetector.segment)
                liveCharts.advance(elapsed)
                checkpointSignals(session.state.value.record!!)
            }
        }
    }

    private fun resetSessionReadings() {
        pauseContinuations = emptySet()
        signals = SignalBuffer()
        checkpointBucket = 0L
        checkpointAt = -1000L
        hrHistory.reset(session.state.value.record?.id)
        motionHistory.reset(session.state.value.record?.id)
        liveCharts.reset()
        if (session.state.value.status == SessionStatus.IDLE) liveCharts.select(ChartKind.HEART_RATE)
        heartRateZones.reset()
        latestHeartRate.reset()
        previousHrArrival = null
        accProcessor.clear()
        stepDetector.reset()
        ecgBuffer.clear()
    }

    private fun stopSessionReadings() {
        pauseContinuations = if (session.state.value.status == SessionStatus.PAUSING &&
            !storage.recording.state.value.blocked) {
            setOf(PolarDeviceDataType.HR, PolarDeviceDataType.ACC).filter {
                dataSubscriptions.states.value.getValue(it).status == SubscriptionStatus.RECEIVING
            }.toSet()
        } else emptySet()
        hrHistory.stop()
        motionHistory.stop()
        liveCharts.stop(session.state.value.elapsedMs)
        heartRateZones.clearCurrent(session.state.value.elapsedMs)
        latestHeartRate.clear()
        stepDetector.updateSessionTime(session.state.value.elapsedMs)
        stepDetector.stop()
    }

    private fun readSessionSummary(elapsed: Long): SessionSummary {
        heartRateZones.refresh(elapsed)
        stepDetector.updateSessionTime(elapsed)
        return SessionSummary.from(latestHeartRate.statistics.value, heartRateZones.state.value, stepState.value)
    }

    private fun saveSessionSnapshot(record: SessionRecord) {
        if (!discardingRecording) checkpointSignals(record, true)
        val snapshot = SessionSnapshot(record, hrHistory.snapshot(), motionHistory.snapshot())
        mutableLastSnapshot.value = snapshot
        if (!discardingRecording) storage.saves.submit(snapshot)
    }

    private fun resumeSessionHistory() {
        val hr = PolarDeviceDataType.HR in pauseContinuations
        val motion = PolarDeviceDataType.ACC in pauseContinuations
        hrHistory.resume(hr)
        motionHistory.resume(motion)
        liveCharts.resume(hr, motion)
        pauseContinuations = emptySet()
    }

    private fun acceptSignalInput(bytes: Int): Boolean {
        if (storage.recording.state.value.blocked) return false
        if (storage.recording.canAccept(signals.bytes + bytes + 8192)) return true
        session.markMissing(PolarDeviceDataType.ECG)
        session.markMissing(PolarDeviceDataType.HR)
        storage.recording.fail("Recording queue is full. Unaccepted input was not recorded.")
        return false
    }

    private fun checkpointSignals(record: SessionRecord, force: Boolean = false) {
        if (!record.eligibleForSaving) return
        if (!force && record.durationMs - checkpointAt < 1000) return
        if (!force && !storage.recording.canAccept(signals.bytes + 8192)) {
            if (storage.recording.state.value.error == null) storage.recording.fail("Recording queue is full. Recording paused.")
            return
        }
        if (force) signals.boundary(record.durationMs)
        val checkpoint = record.copy(endedAt = record.endedAt ?: System.currentTimeMillis())
        val batch = signals.take(checkpoint, hrHistory.since(checkpointBucket), motionHistory.since(checkpointBucket), force)
        checkpointAt = record.durationMs
        checkpointBucket = record.durationMs / 1000
        storage.recording.enqueue(batch)
    }

    fun discardRecording() {
        val id = session.state.value.record?.id ?: return
        scanScope.launch {
            try {
                storage.recording.discard(id)
                discardingRecording = true
                try { session.stop("Session discarded", reset = true) } finally { discardingRecording = false }
            } catch (error: Exception) { storage.recording.fail(error.message ?: "Unable to discard recording") }
        }
    }

    internal fun chartSnapshot(kind: ChartKind) = liveCharts.snapshot(kind, session.elapsedAt())

    private fun connectedForData() = api != null &&
        mutableConnectionState.value.status == ConnectionStatus.CONNECTED && bluetoothAvailableForData()

    private fun startStream(type: PolarDeviceDataType): Boolean {
        if (type != PolarDeviceDataType.HR && readinessJob != null) {
            dataSubscriptions.unavailable(type, "Settings check in progress. Retry when it finishes.")
            return false
        }
        return when (type) {
            PolarDeviceDataType.HR -> startHr()
            PolarDeviceDataType.ACC -> startAcc()
            PolarDeviceDataType.ECG -> startEcg()
            else -> false
        }
    }

    @MainThread
    private fun startHr(): Boolean = startDataSubscription(
        PolarDeviceDataType.HR,
        stream = { source, identifier ->
            checkFeature(source, identifier, PolarDeviceDataType.HR)
            source.startHrStreaming(identifier).filter { it.samples.isNotEmpty() }
        },
        onData = onData@ { data, receivedTime, receivedDate ->
            if (!acceptSignalInput(0)) return@onData
            val beforeCount = latestHeartRate.statistics.value.count
            val receivedValid = latestHeartRate.receive(data)
            if (latestHeartRate.statistics.value.count - beforeCount < data.samples.size ||
                previousHrArrival?.let { previous -> receivedTime - previous > 3000 } == true) {
                session.markMissing(PolarDeviceDataType.HR)
            }
            previousHrArrival = receivedTime
            if (receivedValid) session.onValidData(receivedTime, receivedDate)
            heartRateZones.receive(latestHeartRate.reading.value, receivedValid, session.elapsedAt(receivedTime))
            session.refresh(session.state.value.generation, receivedTime)
            if (session.state.value.status == SessionStatus.RUNNING) {
                liveCharts.receiveHr(session.state.value.elapsedMs, latestHeartRate.reading.value)
                hrHistory.receive(session.state.value.elapsedMs, latestHeartRate.reading.value)
                checkpointSignals(session.state.value.record!!)
            }
        }
    )

    @MainThread
    private fun startAcc(): Boolean {
        return startDataSubscription(
            PolarDeviceDataType.ACC,
            stream = { source, identifier ->
                val settings = currentStreamSettings(source, identifier, PolarDeviceDataType.ACC)
                source.startAccStreaming(identifier, settings)
                    .filter { it.samples.isNotEmpty() }
            },
            onData = { data, receivedAt, receivedDate ->
                accProcessor.receive(data)
                stepDetector.receivedBatch(data.samples.last().timeStamp, receivedAt)
                if (stepState.value.incompleteAcc) session.markMissing(PolarDeviceDataType.ACC)
                session.onValidData(receivedAt, receivedDate)
                session.refresh(session.state.value.generation, receivedAt)
            }
        )
    }

    @MainThread
    private fun startEcg(): Boolean {
        return startDataSubscription(
            PolarDeviceDataType.ECG,
            stream = { source, identifier ->
                val settings = currentStreamSettings(source, identifier, PolarDeviceDataType.ECG)
                source.startEcgStreaming(identifier, settings).h10EcgSamples()
            },
            onData = onData@ { data, receivedTime, receivedDate ->
                if (!acceptSignalInput(data.size * 100)) return@onData
                val sampleRate = mutableDataReadiness.value.getValue(PolarDeviceDataType.ECG)
                    .selected.getValue(PolarSensorSetting.SettingType.SAMPLE_RATE)
                var previous = ecgBuffer.samples.value.lastOrNull()?.timeStamp
                data.forEach { sample ->
                    if (previous?.let { time -> (sample.timeStamp - time).toDouble() * sampleRate > 3_000_000_000.0 } == true) {
                        session.markMissing(PolarDeviceDataType.ECG)
                    }
                    previous = sample.timeStamp
                }
                ecgBuffer.receive(data)
                session.onValidData(receivedTime, receivedDate)
                session.refresh(session.state.value.generation, receivedTime)
                liveCharts.receiveEcg(data, session.elapsedAt(receivedTime),
                    sampleRate)
                signals.receiveEcg(data.map { RawEcg(it.timeStamp, it.voltage) }, session.elapsedAt(receivedTime), sampleRate)
                checkpointSignals(session.state.value.record!!)
            }
        )
    }

    private fun checkFeature(source: PolarBleApi, identifier: String, type: PolarDeviceDataType) {
        val feature = if (type == PolarDeviceDataType.HR) PolarBleSdkFeature.FEATURE_HR
            else PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING
        if (!readyFeatures.confirmReadiness(feature) { source.isFeatureReady(identifier, feature) }) {
            setDataReadiness(type, DataReadiness(DataReadinessStatus.WAITING))
            error("$type feature is not ready. Retry when available.")
        }
        if (type == PolarDeviceDataType.HR) {
            setDataReadiness(type, DataReadiness(DataReadinessStatus.READY, configurationComplete = true))
        }
    }

    // Serialize fresh ACC/ECG settings queries, not the lifetime of their data streams.
    private suspend fun currentStreamSettings(
        source: PolarBleApi, identifier: String, type: PolarDeviceDataType
    ): PolarSensorSetting = streamSettingsMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!readinessMatches(source, identifier) || !bluetoothAvailableForData()) {
            throw CancellationException("$type connection is no longer current.")
        }
        checkFeature(source, identifier, type)
        val supported = source.getAvailableOnlineStreamDataTypes(identifier)
        currentCoroutineContext().ensureActive()
        if (!readinessMatches(source, identifier) || !bluetoothAvailableForData()) {
            throw CancellationException("$type connection is no longer current.")
        }
        if (type !in supported) {
            setDataReadiness(type, DataReadiness(DataReadinessStatus.UNSUPPORTED))
            error("$type is unavailable for online streaming.")
        }
        val settings = source.requestStreamSettings(identifier, type)
        currentCoroutineContext().ensureActive()
        if (!readinessMatches(source, identifier) || !bluetoothAvailableForData()) {
            throw CancellationException("$type connection is no longer current.")
        }
        val checked = checkedSettings(type, settings.settings)
        setDataReadiness(type, checked)
        check(checked.configurationComplete) {
            checked.error ?: "$type settings need confirmation. Confirm the displayed options before starting."
        }
        PolarSensorSetting(checked.selected)
    }

    @MainThread
    private fun <T> startDataSubscription(
        type: PolarDeviceDataType,
        stream: suspend (PolarBleApi, String) -> Flow<T>,
        onData: (T, Long, Long) -> Unit
    ): Boolean {
        val source = api ?: return false
        val identifier = mutableConnectionState.value.device?.deviceId ?: return false
        val generation = sessionState.value.generation
        return dataSubscriptions.start(
            type,
            canStart = { connectedForData() && session.accepts(generation) },
            isCurrent = { session.accepts(generation) && readinessMatches(source, identifier) && bluetoothAvailableForData() },
            stream = { stream(source, identifier) },
            onData = { data ->
                val receivedAt = SystemClock.elapsedRealtime()
                val receivedDate = System.currentTimeMillis()
                if (!session.checkTimeLimit(receivedAt) && session.accepts(generation)) {
                    onData(data, receivedAt, receivedDate)
                }
            }
        )
    }

    @MainThread
    private fun cleanupDataSubscriptions() = dataSubscriptions.stopAll()

    @SuppressLint("MissingPermission")
    private fun bluetoothAvailableForData(): Boolean {
        if (listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).any {
                appContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }) return false
        return try {
            appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }
    }

    var initializationError: String? = null
        private set

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun initialize(retry: Boolean = false): Boolean {
        if (pendingCleanup != null && (!retry || !disposeSdk())) return false
        if (api != null) return true
        if (initializationError != null && !retry) return false
        initializationError = null

        return try {
            val created = PolarBleApiDefaultImpl.defaultImplementation(
                appContext,
                setOf(
                    PolarBleSdkFeature.FEATURE_HR,
                    PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                    PolarBleSdkFeature.FEATURE_BATTERY_INFO
                )
            )
            api = created
            sdkUsedForConnection = false
            created.setAutomaticReconnection(false)
            created.setApiCallback(object : PolarBleApiCallback() {
                override fun blePowerStateChanged(powered: Boolean) {
                    // Re-read current system state on the main thread; ignore old SDK instances.
                    mainHandler.post {
                        if (api === created) {
                            if (!powered) bluetoothUnavailable()
                            onBluetoothStateChanged?.invoke()
                        }
                    }
                }

                override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                    mainHandler.post {
                        if (matches(created, polarDeviceInfo) &&
                            mutableConnectionState.value.status == ConnectionStatus.DISCONNECTING) {
                            requestDisconnect(created, polarDeviceInfo.deviceId)
                        }
                    }
                }

                override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                    mainHandler.post {
                        if (!matches(created, polarDeviceInfo)) return@post
                        connectionConfirmed = true
                        when (mutableConnectionState.value.status) {
                            ConnectionStatus.CONNECTING -> {
                                cancelConnectionTimeout()
                                mutableConnectionState.value = ConnectionState(
                                    ConnectionStatus.CONNECTED,
                                    ConnectionDevice(polarDeviceInfo.name, polarDeviceInfo.deviceId)
                                )
                                clearDataReadiness(DataReadinessStatus.WAITING)
                                // Only this accepted success branch can create or update a record.
                                savedDeviceStore.save(SavedDevice(
                                    polarDeviceInfo.name, polarDeviceInfo.deviceId, System.currentTimeMillis()
                                ))
                            }
                            // A connection that arrives during cancellation must never become Connected.
                            ConnectionStatus.DISCONNECTING -> requestDisconnect(created, polarDeviceInfo.deviceId)
                            else -> Unit
                        }
                    }
                }

                override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo, info: PolarBleDisconnectInfo) {
                    mainHandler.post {
                        if (!matches(created, polarDeviceInfo)) return@post
                        val state = mutableConnectionState.value
                        session.connectionUnavailable("Connection ended: ${info.reason.name.replace('_', ' ')}.")
                        clearDataReadiness()
                        cancelConnectionTimeout()
                        connectionConfirmed = false
                        val reason = if (state.status == ConnectionStatus.DISCONNECTING) state.error else {
                            "Connection ended: ${info.reason.name.replace('_', ' ')}" +
                                (info.gattStatus?.let { " (GATT $it)" } ?: "") + "."
                        }
                        mutableConnectionState.value = ConnectionState(
                            error = reason,
                            message = if (state.status == ConnectionStatus.DISCONNECTING && reason == null) {
                                "Disconnected."
                            } else null
                        )
                    }
                }

                override fun batteryLevelReceived(identifier: String, level: Int) {
                    mainHandler.post {
                        deviceBattery.receive(created, api, identifier, mutableConnectionState.value, level)
                    }
                }

                override fun bleSdkFeatureReady(identifier: String, feature: PolarBleSdkFeature) {
                    mainHandler.post { acceptReadiness(created, identifier, listOf(feature), emptyList()) }
                }

                override fun bleSdkFeaturesReadiness(
                    identifier: String,
                    ready: List<PolarBleSdkFeature>,
                    unavailable: List<PolarBleSdkFeature>
                ) {
                    mainHandler.post { acceptReadiness(created, identifier, ready, unavailable) }
                }

                // Required by SDK 8.3.0; these features are not enabled in this step.
                override fun disInformationReceived(identifier: String, disInfo: DisInfo) = Unit
                override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) = Unit
            })
            true
        } catch (error: Exception) {
            releaseBluetooth()
            initializationError = "SDK initialization failed (${error.javaClass.simpleName}). Please retry."
            Log.e("PolarBleManager", "SDK initialization failed", error)
            false
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun startScan() {
        if (mutableScanState.value.status == ScanStatus.SCANNING ||
            mutableConnectionState.value.status != ConnectionStatus.NOT_CONNECTED) return
        val currentApi = api ?: return
        val previousJob = scanJob
        val generation = ++scanGeneration
        mutableScanState.value = ScanState(status = ScanStatus.SCANNING)

        // Assign the job before starting it, even if the SDK fails synchronously.
        scanJob = scanScope.launch(start = CoroutineStart.LAZY) {
            val devicesById = linkedMapOf<String, PolarDeviceInfo>()
            try {
                val completed = withTimeoutOrNull(30_000L) {
                    // Let the previous scan release its subscription before restarting.
                    previousJob?.join()
                    currentApi.searchForDevice().collect { device ->
                        ensureActive()
                        if (generation != scanGeneration) return@collect
                        // PolarDeviceInfo has no model field; match the complete H10 name token.
                        if ((device.name == "Polar H10" || device.name.startsWith("Polar H10 ")) &&
                            device.deviceId.isNotBlank()) {
                            devicesById[device.deviceId] = device
                            mutableScanState.value = mutableScanState.value.copy(devices = devicesById.values.toList())
                        }
                    }
                    true
                }
                if (generation == scanGeneration) {
                    mutableScanState.value = mutableScanState.value.copy(
                        status = if (completed == null) ScanStatus.TIMED_OUT else ScanStatus.STOPPED
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == scanGeneration) {
                    mutableScanState.value = mutableScanState.value.copy(
                        status = ScanStatus.ERROR,
                        error = "Scan failed (${error.javaClass.simpleName}). Check Bluetooth and permissions, then retry."
                    )
                    Log.e("PolarBleManager", "Scan failed", error)
                }
            } finally {
                if (generation == scanGeneration) scanJob = null
            }
        }
        scanJob?.start()
    }

    fun stopScan(status: ScanStatus = ScanStatus.STOPPED) {
        if (mutableScanState.value.status != ScanStatus.SCANNING) return
        // Invalidate queued results so a cancelled scan cannot change retained or new results.
        scanGeneration++
        scanJob?.cancel()
        mutableScanState.value = mutableScanState.value.copy(status = status)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun connect(deviceId: String) {
        if (mutableConnectionState.value.status != ConnectionStatus.NOT_CONNECTED) return
        if (!sessionState.value.acceptsDevice(deviceId)) {
            mutableConnectionState.value = ConnectionState(
                error = "Reconnect the original H10 to continue, or Stop this session before changing devices.")
            return
        }
        val device = mutableScanState.value.devices.firstOrNull { it.deviceId == deviceId }
            ?.let { ConnectionDevice(it.name, it.deviceId) }
            ?: savedDevicesState.value.devices.firstOrNull { it.deviceId == deviceId }
                ?.let { ConnectionDevice(it.name, it.deviceId) }
            ?: return
        stopScan()
        deviceBattery.clear()
        // The SDK does not tag callbacks with an attempt ID. Never reuse an attempted SDK for retry.
        if (sdkUsedForConnection && !disposeSdk()) return
        if (!initialize()) {
            mutableConnectionState.value = ConnectionState(error = initializationError)
            onBluetoothStateChanged?.invoke()
            return
        }
        val currentApi = api ?: return
        sdkUsedForConnection = true
        connectionConfirmed = false
        mutableConnectionState.value = ConnectionState(ConnectionStatus.CONNECTING, device)
        connectionTimeout = Runnable {
            if (api === currentApi && mutableConnectionState.value.status == ConnectionStatus.CONNECTING) {
                interruptConnection("Connection timed out after 10 seconds.")
            }
        }.also { mainHandler.postDelayed(it, 10_000L) }
        try {
            currentApi.connectToDevice(deviceId)
        } catch (error: Exception) {
            Log.e("PolarBleManager", "Connection request failed", error)
            interruptConnection("Connection failed (${error.javaClass.simpleName}).")
        }
    }

    private fun matches(source: PolarBleApi, device: PolarDeviceInfo): Boolean =
        api === source && mutableConnectionState.value.device?.deviceId == device.deviceId

    private fun readinessMatches(source: PolarBleApi, identifier: String): Boolean =
        api === source && mutableConnectionState.value.status == ConnectionStatus.CONNECTED &&
            mutableConnectionState.value.device?.deviceId == identifier

    private fun setDataReadiness(type: PolarDeviceDataType, state: DataReadiness) {
        mutableDataReadiness.value = mutableDataReadiness.value + (type to state)
    }

    private fun acceptReadiness(
        source: PolarBleApi, identifier: String,
        ready: List<PolarBleSdkFeature>, unavailable: List<PolarBleSdkFeature>
    ) {
        if (!readinessMatches(source, identifier)) return
        val online = PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING
        val onlineWasReady = online in readyFeatures
        readyFeatures.addAll(ready)
        unavailableFeatures.addAll(unavailable)
        unavailableFeatures.removeAll(readyFeatures)
        val hr = PolarBleSdkFeature.FEATURE_HR
        if (hr in readyFeatures) {
            setDataReadiness(PolarDeviceDataType.HR, DataReadiness(DataReadinessStatus.READY, configurationComplete = true))
        } else if (hr in unavailableFeatures) {
            setDataReadiness(PolarDeviceDataType.HR, DataReadiness(DataReadinessStatus.UNSUPPORTED))
        }
        if (online in readyFeatures && !onlineWasReady) queryStreamSettings(source, identifier)
        else if (online in unavailableFeatures) {
            listOf(PolarDeviceDataType.ACC, PolarDeviceDataType.ECG).forEach {
                setDataReadiness(it, DataReadiness(DataReadinessStatus.UNSUPPORTED))
            }
        }
        // Features absent from both callback lists remain unresolved, not unsupported.
    }

    fun recheckDataReadiness() {
        if (readinessJob != null || onlineStreamActive()) return
        val source = api ?: return
        val identifier = mutableConnectionState.value.device?.deviceId ?: return
        if (!readinessMatches(source, identifier)) return
        for (feature in listOf(PolarBleSdkFeature.FEATURE_HR, PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING)) {
            val types = if (feature == PolarBleSdkFeature.FEATURE_HR) listOf(PolarDeviceDataType.HR)
                else listOf(PolarDeviceDataType.ACC, PolarDeviceDataType.ECG)
            try {
                if (readyFeatures.confirmReadiness(feature) { source.isFeatureReady(identifier, feature) }) {
                    unavailableFeatures.remove(feature)
                    if (feature == PolarBleSdkFeature.FEATURE_HR) {
                        setDataReadiness(PolarDeviceDataType.HR, DataReadiness(DataReadinessStatus.READY, configurationComplete = true))
                    } else queryStreamSettings(source, identifier)
                } else {
                    readyFeatures.remove(feature)
                    types.forEach { setDataReadiness(it, DataReadiness(
                        if (feature in unavailableFeatures) DataReadinessStatus.UNSUPPORTED else DataReadinessStatus.WAITING
                    )) }
                }
            } catch (error: Exception) {
                readyFeatures.remove(feature)
                types.forEach { setDataReadiness(it, DataReadiness(DataReadinessStatus.FAILED,
                    error = "Readiness check failed (${error.javaClass.simpleName}). Recheck to retry.")) }
            }
        }
    }

    private fun onlineStreamActive() = dataSubscriptions.isActive(PolarDeviceDataType.ACC) ||
        dataSubscriptions.isActive(PolarDeviceDataType.ECG)

    private fun queryStreamSettings(source: PolarBleApi, identifier: String) {
        if (readinessJob != null || onlineStreamActive() ||
            !readinessMatches(source, identifier)) return
        val generation = readinessGeneration
        val types = listOf(PolarDeviceDataType.ACC, PolarDeviceDataType.ECG)
        types.forEach { setDataReadiness(it, DataReadiness(DataReadinessStatus.CHECKING)) }
        fun current() = generation == readinessGeneration && readinessMatches(source, identifier)
        readinessJob = readinessScope.launch(start = CoroutineStart.LAZY) {
            try {
                val supported = source.getAvailableOnlineStreamDataTypes(identifier)
                ensureActive()
                if (!current()) return@launch
                for (type in types) {
                    if (type !in supported) {
                        setDataReadiness(type, DataReadiness(DataReadinessStatus.UNSUPPORTED))
                        continue
                    }
                    try {
                        val settings = source.requestStreamSettings(identifier, type)
                        ensureActive()
                        if (!current()) return@launch
                        setDataReadiness(type, checkedSettings(type, settings.settings))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (current()) setDataReadiness(type, DataReadiness(DataReadinessStatus.FAILED,
                            error = "Settings check failed (${error.javaClass.simpleName}). Recheck to retry."))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) types.forEach { setDataReadiness(it, DataReadiness(DataReadinessStatus.FAILED,
                    error = "Data type query failed (${error.javaClass.simpleName}). Recheck to retry.")) }
            } finally {
                if (generation == readinessGeneration) {
                    readinessJob = null
                    // Cancellation while still connected must not leave the recheck button blocked.
                    if (current()) types.filter { mutableDataReadiness.value[it]?.status == DataReadinessStatus.CHECKING }
                        .forEach { setDataReadiness(it, DataReadiness(DataReadinessStatus.WAITING)) }
                }
            }
        }
        readinessJob?.start()
    }

    private fun clearDataReadiness(status: DataReadinessStatus = DataReadinessStatus.DISCONNECTED) {
        pauseContinuations = emptySet()
        deviceBattery.clear()
        cleanupDataSubscriptions()
        readinessGeneration++
        readinessJob?.cancel()
        readinessJob = null
        readyFeatures.clear()
        unavailableFeatures.clear()
        mutableDataReadiness.value = checkedDataTypes.associateWith { DataReadiness(status) }
    }

    fun bluetoothUnavailable() {
        stopScan(ScanStatus.INTERRUPTED)
        interruptConnection("Bluetooth is unavailable. Enable Bluetooth and permissions, then retry.")
    }

    fun pauseForNavigation() {
        session.pause()
        stopScan()
        if (mutableConnectionState.value.status == ConnectionStatus.CONNECTING) {
            interruptConnection(reason = null, message = "Connection attempt cancelled when leaving Session.")
        }
    }

    fun disconnect() {
        if (mutableConnectionState.value.status != ConnectionStatus.CONNECTED) return
        interruptConnection(reason = null, message = "Disconnect requested.")
    }

    fun retryDisconnect() {
        val state = mutableConnectionState.value
        if (state.status != ConnectionStatus.DISCONNECTING || state.disconnectError == null) return
        val currentApi = api ?: return
        val device = state.device ?: return
        requestDisconnect(currentApi, device.deviceId)
    }

    private fun interruptConnection(reason: String?, message: String? = null) {
        session.connectionUnavailable(reason ?: message ?: "Connection ended.")
        clearDataReadiness()
        val state = mutableConnectionState.value
        if (state.status == ConnectionStatus.NOT_CONNECTED || state.status == ConnectionStatus.DISCONNECTING) return
        val currentApi = api ?: return
        val device = state.device ?: return
        cancelConnectionTimeout()
        mutableConnectionState.value = state.copy(status = ConnectionStatus.DISCONNECTING, error = reason, message = message)
        requestDisconnect(currentApi, device.deviceId)
        // Cancelling an SDK search can produce no disconnect callback because no session opened.
        // Dispose that unconfirmed attempt before allowing retry; this is not a confirmed disconnect.
        mainHandler.post {
            if (api === currentApi && !connectionConfirmed &&
                mutableConnectionState.value.status == ConnectionStatus.DISCONNECTING && disposeSdk()) {
                mutableConnectionState.value = ConnectionState(
                    error = reason,
                    message = message
                )
                onBluetoothStateChanged?.invoke()
            }
        }
    }

    private fun requestDisconnect(source: PolarBleApi, deviceId: String) {
        val state = mutableConnectionState.value
        if (api !== source || state.device?.deviceId != deviceId ||
            state.status != ConnectionStatus.DISCONNECTING) return
        // A disconnect error is the retry flag. Clear it before sending to block duplicate taps.
        mutableConnectionState.value = state.copy(disconnectError = null)
        try {
            source.disconnectFromDevice(deviceId)
        } catch (error: Exception) {
            Log.e("PolarBleManager", "Cancellation/disconnection failed", error)
            mutableConnectionState.value = mutableConnectionState.value.copy(
                disconnectError = "Unable to disconnect (${error.javaClass.simpleName}). Please retry."
            )
        }
    }

    private fun cancelConnectionTimeout() {
        connectionTimeout?.let { mainHandler.removeCallbacks(it) }
        connectionTimeout = null
    }

    private fun disposeSdk(): Boolean {
        clearDataReadiness()
        cancelConnectionTimeout()
        stopScan(ScanStatus.INTERRUPTED)
        scanJob?.cancel()
        val previous = api ?: pendingCleanup
        // Invalidate queued callbacks before shutdown, including callbacks for the same device ID.
        api = null
        mutableConnectionState.value = mutableConnectionState.value.copy(disconnectError = null)
        return try {
            previous?.shutDown()
            pendingCleanup = null
            true
        } catch (error: Exception) {
            // Retain only for cleanup. Its callbacks stay invalid and initialization is blocked.
            pendingCleanup = previous
            initializationError = "SDK cleanup failed (${error.javaClass.simpleName}). Restart Session."
            mutableConnectionState.value = mutableConnectionState.value.copy(error = initializationError)
            Log.e("PolarBleManager", "SDK cleanup failed", error)
            false
        }
    }

    fun releaseBluetooth() {
        session.connectionUnavailable("Bluetooth access unavailable.")
        val disposed = disposeSdk()
        mainHandler.removeCallbacksAndMessages(null)
        if (disposed) {
            initializationError = null
            connectionConfirmed = false
            // Local SDK ownership ended; a new connection must be confirmed by a fresh SDK callback.
            mutableConnectionState.value = ConnectionState(message = "Bluetooth access released. Reconnect when available.")
        }
    }
}
