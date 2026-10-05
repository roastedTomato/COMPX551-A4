package com.example.polarh10activityviewer.session

import androidx.compose.ui.semantics.SemanticsActions
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.example.polarh10activityviewer.BluetoothAvailability
import com.example.polarh10activityviewer.SessionScreen
import com.example.polarh10activityviewer.ble.ConnectionDevice
import com.example.polarh10activityviewer.ble.ConnectionState
import com.example.polarh10activityviewer.ble.ConnectionStatus
import com.example.polarh10activityviewer.ble.DataReadiness
import com.example.polarh10activityviewer.ble.DataReadinessStatus
import com.example.polarh10activityviewer.heartrate.HeartRateReading
import com.example.polarh10activityviewer.ble.SavedDevice
import com.example.polarh10activityviewer.ble.SavedDevicesState
import com.example.polarh10activityviewer.ble.ScanState
import com.example.polarh10activityviewer.ble.ScanStatus
import com.example.polarh10activityviewer.ble.SubscriptionState
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.ble.checkedDataTypes
import com.example.polarh10activityviewer.heartrate.HeartRateZone
import com.example.polarh10activityviewer.heartrate.HeartRateZoneState
import com.example.polarh10activityviewer.ui.theme.PolarH10ActivityViewerTheme
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarSensorSetting.SettingType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class SessionHeaderTest {
    @get:Rule val compose = createComposeRule()
    private val availability = mutableStateOf(BluetoothAvailability.READY)
    private val connection = mutableStateOf(ConnectionState())
    private val battery = mutableStateOf<Int?>(null)
    private val scan = mutableStateOf(ScanState())
    private val saved = mutableStateOf(SavedDevicesState(loading = false))
    private val readiness = mutableStateOf(checkedDataTypes.associateWith {
        DataReadiness(DataReadinessStatus.READY, configurationComplete = true)
    })
    private val zones = mutableStateOf(HeartRateZoneState())
    private val session = mutableStateOf(SessionState(SessionStatus.RUNNING, elapsedMs = 10_000))
    private val hr = mutableStateOf(SubscriptionState(SubscriptionStatus.RECEIVING))
    private val acc = mutableStateOf(SubscriptionState())
    private val message = mutableStateOf<String?>(null)
    private val dark = mutableStateOf(false)
    private val fontScale = mutableStateOf(1f)
    private var scansStarted = 0
    private var scansStopped = 0
    private var disconnects = 0
    private var retries = 0
    private var bluetoothActions = 0
    private var rechecks = 0
    private var starts = 0
    private var stops = 0
    private var connectedId: String? = null
    private val nearby = PolarDeviceInfo("TEST1234", "00:00:00:00:00:01", -55,
        "Controlled scan fixture", true, true, true, false)

    @Composable
    private fun Fixture() {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
            PolarH10ActivityViewerTheme(darkTheme = dark.value) {
                Scaffold { padding ->
                    Column(Modifier.padding(padding)) {
                        Text("Controlled UI fixture")
                        SessionScreen(
                            availability = availability.value, actionEnabled = true, errorMessage = null,
                            onBluetoothAction = { bluetoothActions++ },
                            scanState = scan.value,
                            onStartScan = { scansStarted++; scan.value = ScanState(ScanStatus.SCANNING) },
                            onStopScan = { scansStopped++; scan.value = scan.value.copy(status = ScanStatus.STOPPED) },
                            connectionState = connection.value,
                            onConnect = { id ->
                                connectedId = id
                                connection.value = ConnectionState(ConnectionStatus.CONNECTING,
                                    ConnectionDevice("Controlled connecting fixture", id))
                            },
                            savedDevicesState = saved.value,
                            onDisconnect = { disconnects++ }, onRetryDisconnect = { retries++ },
                            dataReadiness = readiness.value, onRecheckData = { rechecks++ },
                            batteryLevel = battery.value, heartRateZones = zones.value,
                            heartRate = zones.value.current?.let {
                                HeartRateReading(listOf(100, 115, 130, 145, 160)[it.ordinal])
                            },
                            heartRateMessage = message.value, hrSubscription = hr.value,
                            accSubscription = acc.value, session = session.value
                        )
                    }
                }
            }
        }
    }

    private fun mount(restoration: StateRestorationTester? = null) {
        if (restoration == null) compose.setContent { Fixture() }
        else restoration.setContent { Fixture() }
    }
    private fun open() = compose.onNodeWithContentDescription("Devices").reveal().performClick()
    private fun click(text: String) = compose.onNodeWithText(text).reveal().performClick()
    private fun screenshot(name: String, dialog: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "step81-controlled-visual").apply { mkdirs() }
        val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        File(directory, "$name.png").outputStream().use {
            node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun openingAndClosingDevicesDoesNotStartOperationsOrEndRunningSession() {
        mount(); open()
        compose.onNodeWithText("Current device").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        assertEquals(SessionStatus.RUNNING, session.value.status)
        compose.onNodeWithText("Session: Running").assertDoesNotExist()
        assertEquals(0, scansStarted); assertEquals(0, scansStopped)
        assertEquals(0, bluetoothActions); assertEquals(0, disconnects)
        assertEquals(0, starts); assertEquals(0, stops); assertEquals(null, connectedId)
    }

    @Test fun closingActiveScanStopsOnceRetainsResultsAndReopeningDoesNotRestart() {
        scan.value = ScanState(ScanStatus.SCANNING, listOf(nearby))
        mount(); open(); compose.onNodeWithText("Close").performClick()
        assertEquals(1, scansStopped); assertEquals(listOf(nearby), scan.value.devices)
        open()
        compose.onNodeWithText(nearby.name).reveal().assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertEquals(1, scansStopped); assertEquals(0, scansStarted); assertEquals(0, stops)
    }

    @Test fun systemBackUsesTheSameScanDismissalWithoutDisconnecting() {
        scan.value = ScanState(ScanStatus.SCANNING, listOf(nearby))
        mount(); open()
        onView(isRoot()).inRoot(RootMatchers.isDialog()).perform(pressBack())
        compose.waitUntil(timeoutMillis = 5_000) { scansStopped == 1 }
        compose.onNodeWithText("Devices").assertDoesNotExist()
        assertEquals(1, scansStopped); assertEquals(0, disconnects); assertEquals(0, stops)
        assertEquals(listOf(nearby), scan.value.devices)
    }

    @Test fun topCloseStopsScanningWithoutDisconnectingOrEndingSession() {
        scan.value = ScanState(ScanStatus.SCANNING, listOf(nearby))
        mount(); open()
        compose.onNodeWithContentDescription("Close devices").performClick()
        compose.onNodeWithText("Devices").assertDoesNotExist()
        assertEquals(1, scansStopped)
        assertEquals(listOf(nearby), scan.value.devices)
        assertEquals(0, disconnects); assertEquals(0, stops)
        assertEquals(SessionStatus.RUNNING, session.value.status)
    }

    @Test fun stateRestorationKeepsDialogAndDoesNotRepeatActiveScan() {
        scan.value = ScanState(ScanStatus.SCANNING, listOf(nearby))
        val restoration = StateRestorationTester(compose)
        mount(restoration); open(); restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Devices").assertIsDisplayed()
        assertEquals(ScanStatus.SCANNING, scan.value.status)
        assertEquals(0, scansStopped); assertEquals(0, scansStarted)
        compose.onNodeWithText("Close").performClick()
        assertEquals(1, scansStopped)
    }

    @Test fun batteryIsHiddenWhenConnectionOrAvailabilityIsInvalid() {
        connection.value = ConnectionState(ConnectionStatus.CONNECTED, ConnectionDevice("Controlled H10", "ABC"))
        battery.value = 68
        mount()
        compose.onNodeWithText("Battery: 68%").assertIsDisplayed()
        compose.runOnIdle { connection.value = connection.value.copy(status = ConnectionStatus.DISCONNECTING) }
        compose.onNodeWithText("Battery: --").assertIsDisplayed()
        compose.runOnIdle {
            connection.value = connection.value.copy(status = ConnectionStatus.CONNECTED)
            availability.value = BluetoothAvailability.BLUETOOTH_OFF
        }
        compose.onNodeWithText("Bluetooth off").assertIsDisplayed()
        compose.onNodeWithText("Battery: --").assertIsDisplayed()
    }

    @Test fun availabilityAndAllConnectionStatesUpdateTheClickableHeader() {
        mount()
        ConnectionStatus.entries.forEach { status ->
            compose.runOnIdle { connection.value = ConnectionState(status) }
            compose.onNodeWithText(status.message).assertIsDisplayed()
        }
        listOf(
            BluetoothAvailability.PERMISSIONS_NEEDED to "Permissions needed",
            BluetoothAvailability.PERMISSION_DENIED to "Permissions needed",
            BluetoothAvailability.SETTINGS_REQUIRED to "Permissions needed",
            BluetoothAvailability.BLUETOOTH_OFF to "Bluetooth off",
            BluetoothAvailability.UNSUPPORTED to "BLE unavailable",
            BluetoothAvailability.SDK_ERROR to "SDK error"
        ).forEach { (state, label) ->
            compose.runOnIdle { availability.value = state }
            compose.onNodeWithText(label).assertIsDisplayed()
            open(); compose.onNodeWithText("Close").performClick()
        }
        assertEquals(0, bluetoothActions); assertEquals(0, scansStarted)
    }

    @Test fun selectingSavedDeviceRoutesTheIdAndDisablesFurtherConnectionAndScan() {
        saved.value = SavedDevicesState(listOf(SavedDevice("Controlled saved fixture", "SAVED123", 1_000)), false)
        mount(); open(); click("Connect")
        assertEquals("SAVED123", connectedId)
        compose.onNodeWithText("Connect").assertDoesNotExist()
        compose.onNodeWithText("Scan").reveal().assertIsNotEnabled()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Connecting").assertIsDisplayed()
        assertEquals(0, stops); assertEquals(0, disconnects)
    }

    @Test fun readinessShowsActionableProblemWithoutParametersAndPreservesBusyRecheckGuard() {
        connection.value = ConnectionState(ConnectionStatus.CONNECTED, ConnectionDevice("Controlled H10", "ABC"))
        readiness.value = readiness.value + (PolarDeviceDataType.ACC to DataReadiness(
            DataReadinessStatus.READY, available = mapOf(SettingType.SAMPLE_RATE to setOf(50)),
            error = "ACC disabled: 100 Hz is unavailable. No alternative rate selected."))
        mount(); open()
        compose.onNodeWithText("Sample rate (Hz): 50").assertDoesNotExist()
        compose.onNodeWithText(readiness.value.getValue(PolarDeviceDataType.ACC).error!!).reveal().assertIsDisplayed()
        click("Recheck"); assertEquals(1, rechecks)
        compose.runOnIdle { acc.value = SubscriptionState(SubscriptionStatus.STARTING) }
        compose.onNodeWithText("Recheck").assertIsNotEnabled()
        compose.runOnIdle {
            acc.value = SubscriptionState()
            readiness.value = checkedDataTypes.associateWith {
                DataReadiness(DataReadinessStatus.READY, configurationComplete = true,
                    available = mapOf(SettingType.SAMPLE_RATE to setOf(100)),
                    selected = mapOf(SettingType.SAMPLE_RATE to 100))
            }
        }
        compose.onNodeWithText("Sample rate (Hz): 100").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
    }

    @Test fun intensityUsesCurrentZoneAndClearsAfterInvalidFailureOrStop() {
        zones.value = HeartRateZoneState(List(5) { 2_000L }, receivedValidHr = true)
        mount()
        HeartRateZone.entries.forEach { zone ->
            compose.runOnIdle { zones.value = zones.value.copy(current = zone) }
            compose.onNodeWithText("${zone.label} · Zone ${zone.ordinal + 1}").reveal().assertIsDisplayed()
        }
        compose.onAllNodesWithText("Heart rate intensity").assertCountEquals(0)
        compose.runOnIdle { zones.value = zones.value.copy(current = null); message.value = "No skin contact." }
        compose.onNodeWithContentDescription("Open Devices").performClick()
        compose.onNodeWithText("No skin contact.").reveal().assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { hr.value = SubscriptionState(SubscriptionStatus.FAILED, "Controlled HR failure") }
        compose.onNodeWithText("HR unavailable").reveal().assertIsDisplayed()
        compose.runOnIdle {
            zones.value = zones.value.copy(current = HeartRateZone.MODERATE)
            hr.value = SubscriptionState(SubscriptionStatus.RECEIVING); message.value = null
        }
        compose.onNodeWithText("Moderate · Zone 3").reveal().assertIsDisplayed()
        compose.runOnIdle { session.value = session.value.copy(status = SessionStatus.STOPPED) }
        compose.onNodeWithText("Stopped").reveal().assertIsDisplayed()
        compose.onNodeWithText("Moderate · Zone 3").assertDoesNotExist()
        assertEquals(List(5) { 2_000L }, zones.value.durationsMs)
    }

    @Test fun controlledLargeFontLightDarkHeadersAndLongDeviceDialogRemainReadable() {
        connection.value = ConnectionState(ConnectionStatus.CONNECTED,
            ConnectionDevice("Controlled long H10 device name for enlarged font visual checking", "CONTROLLED1234"))
        battery.value = 72
        zones.value = HeartRateZoneState(List(5) { 2_000L }, HeartRateZone.MODERATE, receivedValidHr = true)
        fontScale.value = 2f
        mount()
        listOf(false, true).forEach { night ->
            compose.runOnIdle { dark.value = night }
            val prefix = if (night) "controlled-dark-font2" else "controlled-light-font2"
            compose.onNodeWithText("Moderate · Zone 3").reveal().assertIsDisplayed()
            screenshot("$prefix-header")
            open()
            compose.onNodeWithText("Close").assertIsDisplayed()
            compose.onNodeWithText(connection.value.device!!.name).reveal().assertIsDisplayed()
            screenshot("$prefix-device", dialog = true)
            compose.onNodeWithText("Close").performClick()
        }
        assertEquals(0, disconnects); assertEquals(0, stops)
    }
}

private fun SemanticsNodeInteraction.reveal(): SemanticsNodeInteraction {
    var ancestor = fetchSemanticsNode().parent
    while (ancestor != null) {
        if (ancestor.config.contains(SemanticsActions.ScrollBy)) return performScrollTo()
        ancestor = ancestor.parent
    }
    return this
}
