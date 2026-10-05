package com.example.polarh10activityviewer

import com.example.polarh10activityviewer.ble.checkedDataTypes
import com.example.polarh10activityviewer.ble.ConnectionState
import com.example.polarh10activityviewer.ble.ConnectionStatus
import com.example.polarh10activityviewer.ble.DataReadiness
import com.example.polarh10activityviewer.ble.DataReadinessStatus
import com.example.polarh10activityviewer.ble.HeartRateReading
import com.example.polarh10activityviewer.ble.HeartRateStatistics
import com.example.polarh10activityviewer.ble.PolarBleManager
import com.example.polarh10activityviewer.ble.SavedDevicesState
import com.example.polarh10activityviewer.ble.ScanState
import com.example.polarh10activityviewer.ble.ScanStatus
import com.example.polarh10activityviewer.ble.SubscriptionState
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.chart.LiveChartPanel
import com.example.polarh10activityviewer.heartrate.SessionHeartRateZonePanel
import com.example.polarh10activityviewer.heartrate.HeartRateZoneState
import com.example.polarh10activityviewer.motion.StepState
import com.example.polarh10activityviewer.session.SessionState
import com.example.polarh10activityviewer.session.SessionStatus
import com.example.polarh10activityviewer.session.HeartRateCard
import com.example.polarh10activityviewer.session.MotionCard
import com.example.polarh10activityviewer.session.ActivitySummaryCard
import com.example.polarh10activityviewer.history.RecordingPanel
import com.example.polarh10activityviewer.history.HistoryPanel
import com.example.polarh10activityviewer.history.SavePanel
import com.example.polarh10activityviewer.storage.SaveStatus
import androidx.compose.runtime.saveable.rememberSaveable

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.example.polarh10activityviewer.ui.theme.PolarH10ActivityViewerTheme
import com.example.polarh10activityviewer.ui.theme.PagePadding
import com.example.polarh10activityviewer.ble.DevicesDialog
import com.example.polarh10activityviewer.session.SessionHeader
import com.example.polarh10activityviewer.session.SessionControls
import com.example.polarh10activityviewer.session.SessionScaffold
import com.example.polarh10activityviewer.session.SessionGap
import com.example.polarh10activityviewer.session.startDisabledReason
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import kotlinx.coroutines.delay

class SensorActivity : ComponentActivity() {
    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,//Nearby devices
        Manifest.permission.BLUETOOTH_CONNECT
    )
    private val permissionHistory by lazy { getSharedPreferences("bluetooth_permissions", MODE_PRIVATE) }//是否曾经请求过权限
    private lateinit var bleManager: PolarBleManager
    private var availability by mutableStateOf(BluetoothAvailability.PERMISSIONS_NEEDED)
    private var systemRequestPending by mutableStateOf(false)//是否正在等待系统弹窗或设置页返回
    private var errorMessage by mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()//一次请求多个权限
    ) { results ->
        //map：权限名，是否授予
        permissionHistory.edit().apply {
            results.keys.forEach { putBoolean(it, true) }
        }.apply()
        systemRequestPending = false
        refreshAvailability()
    }

    private val bluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()//启动一个系统 Activity，并在它结束后回到当前 App。
    ) {
        systemRequestPending = false
        refreshAvailability()
    }

    //当权限已经被拒绝，而且系统不再正常弹权限申请窗口时，App 需要引导用户去系统设置里手动开启权限。
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        systemRequestPending = false
        refreshAvailability()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        systemRequestPending = savedInstanceState?.getBoolean("systemRequestPending") ?: false//恢复-当前是否正在等待系统权限页、蓝牙开启页、App 设置页返回。
        bleManager = (application as ActivityViewerApplication).bleManager //用ActivityViewerApplication申明的bleManager
        enableEdgeToEdge()
        setContent {
            val scanState by bleManager.scanState.collectAsState()//把StateFlow 转成Compose可以观察的State，这样当状态变化时，Compose UI 会自动刷新
            val connectionState by bleManager.connectionState.collectAsState()
            val batteryLevel by bleManager.batteryLevel.collectAsState()
            val savedDevicesState by bleManager.savedDevicesState.collectAsState()
            val dataReadiness by bleManager.dataReadiness.collectAsState()
            val heartRate by bleManager.heartRate.collectAsState()
            val heartRateStatistics by bleManager.heartRateStatistics.collectAsState()
            val heartRateMessage by bleManager.heartRateMessage.collectAsState()
            val heartRateZones by bleManager.heartRateZoneState.collectAsState()
            val steps by bleManager.stepState.collectAsState()
            val subscriptionStates by bleManager.subscriptionStates.collectAsState()//监听 HR / ACC / ECG 三个数据流的订阅状态。
            val session by bleManager.sessionState.collectAsState()
            val recordingState by bleManager.storage.recording.state.collectAsState()//监听原始信号记录器状态。start, resume, recordingpanel是否可用
            val saveState by bleManager.storage.saves.state.collectAsState()//监听 Session 保存状态。
            var showHistory by rememberSaveable { mutableStateOf(false) }//rememberSaveable记住选择，控制当前显示 Session 页面还是 History 页面。
            LaunchedEffect(session.generation, session.status) {//当 session.generation 或 session.status 改变时，重新启动这个 LaunchedEffect。
                val generation = session.generation//generation相当于版本号，这个刷新事件到底属于旧 Session，还是新 Session？
                if (session.status == SessionStatus.RUNNING) {
                    while (true) {
                        bleManager.refreshSessionTime(generation)
                        delay(250)
                    }
                }
            }
            PolarH10ActivityViewerTheme {
                val disabledReason = startDisabledReason(availability, !systemRequestPending, connectionState,
                    session, saveState.blocksStart || recordingState.blocked, subscriptionStates.values.toList(), dataReadiness.values)
                SessionScaffold(showHistory, {
                    if (it) bleManager.pauseForNavigation()//如果用户正在 Session 中直接进入 History，代码会先暂停当前 Session，避免后台继续采集/刷新，保持状态可控
                    showHistory = it
                },
                    controls = {
                        SessionControls(disabledReason == null, session.open,
                            ::handleStartSession, { bleManager.stopSession() },//点击 Start 按钮后调用 SensorActivity.handleStartSession()，点击 Stop 按钮后，直接调用 PolarBleManager.stopSession()
                            canPause = session.status == SessionStatus.RUNNING,
                            canResume = !recordingState.blocked && session.status == SessionStatus.PAUSED && !systemRequestPending &&
                                availability == BluetoothAvailability.READY && connectionState.status == ConnectionStatus.CONNECTED &&
                                session.acceptsDevice(connectionState.device?.deviceId) &&
                                subscriptionStates.values.none { it.status == SubscriptionStatus.STOPPING } && //没有任何数据流还在停止中。
                                dataReadiness.values.any { it.status == DataReadinessStatus.READY && it.configurationComplete },//至少有一个数据流已经 ready 且配置完整。
                            paused = session.status in listOf(SessionStatus.PAUSED, SessionStatus.PAUSING),//决定中间按钮显示 Start 还是 Continue。
                            onPause = { bleManager.pauseSession() }, onResume = { bleManager.resumeSession() })//函数
                    },
                    historyContent = {
                        HistoryPanel(bleManager.storage.database,
                            saveState.sessionId.takeIf { saveState.status == SaveStatus.SAVED } ?: "storage-ready-${recordingState.ready}",
                            onBack = { showHistory = false }, sessionStatus = {
                                if (!saveState.blocksStart) RecordingPanel(recordingState,
                                    { bleManager.storage.recording.retry() }, { bleManager.discardRecording() }, session.open)
                                if (saveState.blocksStart) {//blocksStart 通常在这些情况为 true,正在保存
                                    SavePanel(saveState, bleManager.storage.saves, session.record?.id)
                                }
                            }, allowCompact = !saveState.blocksStart && !recordingState.blocked)//告诉 HistoryPanel 是否可以使用紧凑布局。
                    },
                    sessionContent = { SessionScreen(
                        availability = availability,
                        actionEnabled = !systemRequestPending,//当前 UI 操作是否可用。
                        errorMessage = errorMessage,
                        onBluetoothAction = ::handleBluetoothAction,
                        scanState = scanState,
                        onStartScan = ::handleStartScan,
                        onStopScan = { bleManager.stopScan() },
                        connectionState = connectionState,
                        batteryLevel = batteryLevel,
                        onConnect = ::handleConnect,
                        savedDevicesState = savedDevicesState,
                        onClearSavedDevices = bleManager::clearSavedDevices,
                        onDisconnect = ::handleDisconnect,
                        onRetryDisconnect = ::handleRetryDisconnect,
                        dataReadiness = dataReadiness,
                        onRecheckData = ::handleRecheckData,
                        heartRate = heartRate,
                        heartRateStatistics = heartRateStatistics,
                        heartRateMessage = heartRateMessage,
                        heartRateZones = heartRateZones,
                        hrSubscription = subscriptionStates.getValue(PolarDeviceDataType.HR),
                        steps = steps,
                        accSubscription = subscriptionStates.getValue(PolarDeviceDataType.ACC),
                        ecgSubscription = subscriptionStates.getValue(PolarDeviceDataType.ECG),
                        session = session,
                        disabledReason = disabledReason,
                        saveStatus = {
                            if (!saveState.blocksStart) RecordingPanel(recordingState,
                                { bleManager.storage.recording.retry() }, { bleManager.discardRecording() }, session.open)
                            if (saveState.status in setOf(SaveStatus.SAVING, SaveStatus.FAILED))
                                SavePanel(saveState, bleManager.storage.saves, session.record?.id, showRetry = true)
                        },
                        charts = { LiveChartPanel(bleManager, dataReadiness) },
                    ) }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bleManager.onBluetoothStateChanged = { refreshAvailability() }//告诉 PolarBleManager：如果蓝牙状态发生变化，就通知 SensorActivity 重新检查 availability。
    }

    override fun onResume() {
        super.onResume()
        refreshAvailability()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("systemRequestPending", systemRequestPending)//它保证系统请求等待状态在 Activity 重建后不会丢失。
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        bleManager.onBluetoothStateChanged = null
        super.onStop()
    }

    override fun onPause() {
        bleManager.pauseForNavigation()
        super.onPause()
    }

    private fun missingPermissions() = permissions.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED //it指每次的permissions中的一个元素BLUETOOTH_SCAN/BLUETOOTH_CONNECT
    }

    @SuppressLint("MissingPermission")//告诉 Android Lint：这里我知道可能涉及权限检查，不要对这个函数报 MissingPermission 警告。
    private fun refreshAvailability(retryInitialization: Boolean = false) {
        if (systemRequestPending) return//如果当前正在等权限弹窗/蓝牙页面/设置页返回，就先不检查。因为系统状态还不稳定。
        errorMessage = null//每次重新检查前，先清掉旧的页面错误。
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter//手机蓝牙硬件/蓝牙开关的访问入口。
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) || adapter == null) {
            bleManager.bluetoothUnavailable() //如果手机不支持 BLE，或者拿不到蓝牙 adapter，就认为蓝牙不可用。
            availability = BluetoothAvailability.UNSUPPORTED
            return
        }

        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            bleManager.releaseBluetooth()
            availability = when {
                missing.any { permissionHistory.getBoolean(it, false) && !shouldShowRequestPermissionRationale(it) } ->
                    BluetoothAvailability.SETTINGS_REQUIRED//可能需要去系统设置页手动打开
                missing.any { permissionHistory.getBoolean(it, false) || shouldShowRequestPermissionRationale(it) } ->
                    BluetoothAvailability.PERMISSION_DENIED//申请过，但被拒绝，可以再试
                else -> BluetoothAvailability.PERMISSIONS_NEEDED //第一次还没申请
            }
            return
        }

        //判断是否可以初始化 Polar SDK
        if (!bleManager.initialize(retryInitialization)) {
            availability = BluetoothAvailability.SDK_ERROR
            errorMessage = bleManager.initializationError
            return
        }
        try {//判断蓝牙是否开启
            availability = if (adapter.isEnabled) BluetoothAvailability.READY else BluetoothAvailability.BLUETOOTH_OFF
            if (availability != BluetoothAvailability.READY) bleManager.bluetoothUnavailable()
        } catch (_: SecurityException) {
            bleManager.releaseBluetooth()
            availability = BluetoothAvailability.PERMISSIONS_NEEDED
        }
    }

    private fun withBluetoothReady(action: () -> Unit) {
        if (systemRequestPending) return
        refreshAvailability()
        if (availability == BluetoothAvailability.READY) action()
    }

    @SuppressLint("MissingPermission")
    private fun handleStartScan() = withBluetoothReady { bleManager.startScan() }

    @SuppressLint("MissingPermission")
    private fun handleConnect(deviceId: String) = withBluetoothReady { bleManager.connect(deviceId) }

    private fun handleDisconnect() = withBluetoothReady { bleManager.disconnect() }

    private fun handleRetryDisconnect() = withBluetoothReady { bleManager.retryDisconnect() }

    private fun handleRecheckData() = withBluetoothReady { bleManager.recheckDataReadiness() }

    private fun handleStartSession() = withBluetoothReady { bleManager.startSession() }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothAction() {
        if (systemRequestPending) return
        refreshAvailability(retryInitialization = availability == BluetoothAvailability.SDK_ERROR)
        try {
            when (availability) {
                //如果缺权限，或者权限被拒后仍可再次请求， 就打开系统权限申请弹窗。
                BluetoothAvailability.PERMISSIONS_NEEDED, BluetoothAvailability.PERMISSION_DENIED -> {
                    systemRequestPending = true
                    permissionLauncher.launch(missingPermissions().toTypedArray())
                }
                //如果权限需要去系统设置里手动打开，就打开当前 App 的设置页。
                BluetoothAvailability.SETTINGS_REQUIRED -> {
                    systemRequestPending = true
                    settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                //如果蓝牙关闭，就请求系统打开蓝牙。
                BluetoothAvailability.BLUETOOTH_OFF -> {
                    systemRequestPending = true
                    bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
                else -> Unit
            }
        } catch (error: Exception) {
            systemRequestPending = false
            refreshAvailability()
            errorMessage = "Unable to open the system request (${error.javaClass.simpleName}). Please retry."
        }
    }
}

enum class BluetoothAvailability(val message: String, val buttonLabel: String) {
    PERMISSIONS_NEEDED("Bluetooth permissions are required.", "Enable Bluetooth"),
    PERMISSION_DENIED("Bluetooth permissions were denied.", "Grant permissions"),
    SETTINGS_REQUIRED("Allow Nearby devices access in app settings.", "Open app settings"),
    BLUETOOTH_OFF("Bluetooth is off.", "Turn on Bluetooth"),
    UNSUPPORTED("This phone does not support Bluetooth Low Energy (BLE).", "Bluetooth unavailable"),
    SDK_ERROR("SDK initialization failed.", "Retry"),
    READY("Bluetooth ready", "Bluetooth ready")
}

@Composable
internal fun SessionScreen(
    availability: BluetoothAvailability,
    actionEnabled: Boolean,
    errorMessage: String?,
    onBluetoothAction: () -> Unit,
    scanState: ScanState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    connectionState: ConnectionState,
    onConnect: (String) -> Unit,
    savedDevicesState: SavedDevicesState,
    onDisconnect: () -> Unit,
    onRetryDisconnect: () -> Unit,
    dataReadiness: Map<PolarDeviceDataType, DataReadiness>,
    onRecheckData: () -> Unit,
    modifier: Modifier = Modifier,
    batteryLevel: Int? = null,
    heartRate: HeartRateReading? = null,
    heartRateStatistics: HeartRateStatistics = HeartRateStatistics(),
    heartRateMessage: String? = null,
    heartRateZones: HeartRateZoneState = HeartRateZoneState(),
    hrSubscription: SubscriptionState = SubscriptionState(),
    steps: StepState = StepState(),
    accSubscription: SubscriptionState = SubscriptionState(),
    ecgSubscription: SubscriptionState = SubscriptionState(),
    session: SessionState = SessionState(),
    disabledReason: String? = null,
    saveStatus: @Composable () -> Unit = {},
    charts: @Composable () -> Unit = {},
    onClearSavedDevices: () -> Unit = {}
) {
    var showDevices by rememberSaveable { mutableStateOf(false) }
    val validBattery = batteryLevel.takeIf {
        availability == BluetoothAvailability.READY && connectionState.status == ConnectionStatus.CONNECTED
    }//只有在蓝牙 READY 且设备 CONNECTED 时，才显示电量。
    fun closeDevices() {
        if (scanState.status == ScanStatus.SCANNING) onStopScan()
        showDevices = false
    }//关闭设备弹窗，先停止扫描
    val accBusy = accSubscription.status in setOf(SubscriptionStatus.STARTING, SubscriptionStatus.RECEIVING, SubscriptionStatus.STOPPING)
    val ecgBusy = ecgSubscription.status in setOf(SubscriptionStatus.STARTING, SubscriptionStatus.RECEIVING, SubscriptionStatus.STOPPING)
    val notices = buildList {
        heartRateMessage?.let { add(it) }
        listOf("HR" to hrSubscription, "ACC" to accSubscription, "ECG" to ecgSubscription).forEach { (name, stream) ->
            stream.error?.let { add("$name: $it") }
        }
    }//收集页面需要显示的提示/错误信息。
    if (showDevices) {
        DevicesDialog(
            availability = availability, actionEnabled = actionEnabled,
            connection = connectionState, batteryLevel = validBattery,
            savedDevices = savedDevicesState, scan = scanState, readiness = dataReadiness,
            canRecheck = actionEnabled && availability == BluetoothAvailability.READY &&
                connectionState.status == ConnectionStatus.CONNECTED && !accBusy && !ecgBusy,
            errorMessage = errorMessage, onBluetoothAction = onBluetoothAction,
            onConnect = onConnect, onDisconnect = onDisconnect, onRetryDisconnect = onRetryDisconnect,
            onStartScan = onStartScan, onStopScan = onStopScan, onRecheck = onRecheckData,
            onClose = ::closeDevices,
            onClearSavedDevices = onClearSavedDevices,
            streamErrors = notices,
            alerts = {
                if (session.status in listOf(SessionStatus.PAUSING, SessionStatus.PAUSED) &&
                    connectionState.status != ConnectionStatus.CONNECTED) {
                    Text("Session paused. Reconnect ${session.record?.device?.name ?: "the original H10"} to continue.")
                }
                if (!session.open && connectionState.status == ConnectionStatus.CONNECTED && availability == BluetoothAvailability.READY)
                    disabledReason?.let { Text(it) }
                if (session.endReason == "TIME_LIMIT") Text("Session time limit reached.")
                saveStatus()
            }
        )
    }
    Column(
        modifier = modifier.fillMaxSize().padding(start = PagePadding, end = PagePadding, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(SessionGap)
    ) {
        SessionHeader(
            availability = availability, connection = connectionState, batteryLevel = validBattery,
            hrSubscription = hrSubscription,
            accSubscription = accSubscription, ecgSubscription = ecgSubscription,
            onOpenDevices = { showDevices = true }
        )
        HeartRateCard(heartRate, heartRateStatistics, heartRateZones,
            stopped = session.status in listOf(SessionStatus.STOPPING, SessionStatus.STOPPED),
            subscription = hrSubscription)
        MotionCard(steps, paused = session.status in listOf(SessionStatus.PAUSED, SessionStatus.PAUSING))
        charts()
        ActivitySummaryCard(session, steps)
        SessionHeartRateZonePanel(heartRateZones)
    }
}
@Preview(showBackground = true)
@Composable
fun SessionPreview() {
    PolarH10ActivityViewerTheme {
        SessionScreen(
            availability = BluetoothAvailability.PERMISSIONS_NEEDED,
            actionEnabled = true,
            errorMessage = null,
            onBluetoothAction = {},
            scanState = ScanState(),
            onStartScan = {},
            onStopScan = {},
            connectionState = ConnectionState(),
            onConnect = {},
            savedDevicesState = SavedDevicesState(loading = false),
            onDisconnect = {},
            onRetryDisconnect = {},
            dataReadiness = checkedDataTypes.associateWith { DataReadiness() },
            onRecheckData = {}
        )
    }
}
