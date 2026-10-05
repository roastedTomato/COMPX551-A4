package com.example.polarh10activityviewer.session

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.polarh10activityviewer.BluetoothAvailability
import com.example.polarh10activityviewer.SessionScreen
import com.example.polarh10activityviewer.ble.*
import com.example.polarh10activityviewer.history.HistoryPanel
import com.example.polarh10activityviewer.storage.*
import com.example.polarh10activityviewer.ui.theme.PolarH10ActivityViewerTheme
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

// Fixtures exercise UI behavior and real SQLite, not H10 acquisition.
class SessionChromeTest {
    @get:Rule val compose = createComposeRule()
    private val session = mutableStateOf(SessionState(SessionStatus.STOPPED, record = databaseFixture("current").record))
    private val availability = mutableStateOf(BluetoothAvailability.READY)
    private val connection = mutableStateOf(ConnectionState(ConnectionStatus.CONNECTED, ConnectionDevice("Controlled H10", "FIXTURE")))
    private val subscriptions = mutableStateOf(checkedDataTypes.associateWith { SubscriptionState() })
    private val dark = mutableStateOf(false)
    private val scale = mutableStateOf<Float?>(1f)
    private var starts = 0
    private var stops = 0
    private var pauses = 0
    private var resumes = 0
    private var retries = 0

    @Composable private fun Fixture(controller: SessionSaveController? = null, db: SessionDatabase? = null) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides (scale.value?.let { Density(density.density, it) } ?: density)) {
            PolarH10ActivityViewerTheme(darkTheme = dark.value) {
                var history by rememberSaveable { mutableStateOf(false) }
                val save = controller?.state?.collectAsState()?.value ?: SaveState()
                val readiness = checkedDataTypes.associateWith { DataReadiness(DataReadinessStatus.READY, configurationComplete = true) }
                val disabled = startDisabledReason(availability.value, true, connection.value, session.value,
                    save.blocksStart, subscriptions.value.values.toList(), readiness.values)
                val status: @Composable () -> Unit = {
                    SessionStatusPanel(session.value, disabled)
                    controller?.let { SavePanel(save, it, session.value.record?.id) }
                }
                SessionScaffold(history, { history = it },
                    controls = { SessionControls(disabled == null, session.value.open, { starts++ }, { stops++ },
                        canPause = session.value.status == SessionStatus.RUNNING,
                        canResume = session.value.status == SessionStatus.PAUSED && connection.value.status == ConnectionStatus.CONNECTED,
                        paused = session.value.status in listOf(SessionStatus.PAUSING, SessionStatus.PAUSED),
                        onPause = { pauses++ }, onResume = { resumes++ }) },
                    historyContent = {
                        if (db != null) HistoryPanel(db, save.sessionId.takeIf { save.status == SaveStatus.SAVED },
                            { history = false }, status)
                        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text("Controlled UI fixture — no H10 data")
                            status()
                        }
                    },
                    sessionContent = {
                        Column {
                            Text("Controlled UI fixture — no H10 data")
                            SessionScreen(availability.value, true, null, {}, ScanState(), {}, {}, connection.value, {},
                                SavedDevicesState(loading = false), {}, {}, readiness, {},
                                batteryLevel = 72, session = session.value, disabledReason = disabled,
                                saveStatus = { if (save.status in setOf(SaveStatus.SAVING, SaveStatus.FAILED)) controller?.let { SavePanel(save, it, session.value.record?.id, showRetry = false) } },
                                hrSubscription = subscriptions.value.getValue(PolarDeviceDataType.HR),
                                accSubscription = subscriptions.value.getValue(PolarDeviceDataType.ACC),
                                ecgSubscription = subscriptions.value.getValue(PolarDeviceDataType.ECG),
                                charts = { Text("Bottom chart fixture", Modifier.padding(vertical = 30.dp)) })
                        }
                    })
            }
        }
    }
    private fun mount(controller: SessionSaveController? = null, db: SessionDatabase? = null) = compose.setContent { Fixture(controller, db) }
    private fun visible(text: String) {
        val details = compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        if (details) compose.onNodeWithContentDescription("Open Devices").performClick()
        compose.onNodeWithText(text).reveal().assertIsDisplayed()
        if (details) compose.onNodeWithText("Close").performClick()
    }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "step84a-visual").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun noOverflow(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertFalse(layout.multiParagraph.didExceedMaxLines)
        repeat(layout.lineCount) { line ->
            assertTrue(layout.getLineRight(line) <= layout.size.width + 1)
            assertTrue(layout.getLineLeft(line) >= -1)
            assertTrue(layout.getLineBottom(line) <= layout.size.height + 1)
        }
    }

    @Test fun fixedTabsAndControlsLeaveTheLastCardVisibleAndKeepScrollOnReentry() {
        mount()
        compose.onNodeWithText("Session").assertIsSelected()
        visible("Bottom chart fixture")
        compose.onNodeWithContentDescription("Start").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("History").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Start").assertDoesNotExist()
        compose.onNodeWithText("Session").performClick()
        compose.onNodeWithText("Bottom chart fixture").assertIsDisplayed()
        assertEquals(0, starts); assertEquals(0, stops); assertEquals(0, retries)
    }

    @Test fun pauseContinueAndStopUseActualSessionStateWithoutStartingAnotherSession() {
        session.value = session.value.copy(status = SessionStatus.RUNNING)
        mount()
        compose.onNodeWithContentDescription("Pause").assertIsEnabled().performClick()
        assertEquals(1, pauses)
        compose.onNodeWithContentDescription("Start").assertIsNotEnabled()
        compose.runOnIdle { session.value = session.value.copy(status = SessionStatus.PAUSING) }
        compose.onNodeWithContentDescription("Continue").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Stop").assertIsEnabled()
        compose.runOnIdle { session.value = session.value.copy(status = SessionStatus.PAUSED) }
        compose.onNodeWithContentDescription("Pause").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Continue").assertIsEnabled().performClick()
        assertEquals(1, resumes)
        assertEquals(0, starts)
        compose.runOnIdle { connection.value = ConnectionState() }
        compose.onNodeWithContentDescription("Continue").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Stop").performClick()
        assertEquals(1, stops)
    }

    @Test fun startingCanStopAndStoppingCannotDuplicateStart() {
        session.value = session.value.copy(status = SessionStatus.STARTING)
        mount()
        compose.onNodeWithContentDescription("Start").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Stop").assertIsEnabled().performClick()
        assertEquals(1, stops)
        compose.runOnIdle { session.value = session.value.copy(status = SessionStatus.STOPPING) }
        compose.onNodeWithContentDescription("Start").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Stop").assertIsNotEnabled()
        visible("Waiting for streams to stop.")
        compose.runOnIdle { session.value = session.value.copy(status = SessionStatus.STOPPED, endReason = "TIME_LIMIT") }
        visible("Session time limit reached.")
        compose.onNodeWithContentDescription("Start").assertIsEnabled().performClick()
        assertEquals(1, starts)
    }

    @Test fun readyIsNotReceivingAndEveryStreamDisplaysActualSubscriptionState() {
        mount()
        SubscriptionStatus.entries.forEach { state ->
            compose.runOnIdle { subscriptions.value = checkedDataTypes.associateWith { SubscriptionState(state) } }
            checkedDataTypes.forEach { type ->
                compose.onNodeWithContentDescription("${type.name}: ${state.name.lowercase().replaceFirstChar { it.uppercase() }}")
                    .reveal().assertIsDisplayed()
            }
        }
    }

    @Test fun connectionAndGearOpenTheExistingDialogWithoutChangingSession() {
        mount()
        compose.onNodeWithContentDescription("Open Devices").performClick()
        compose.onNodeWithText("Current device").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("Devices").performClick()
        compose.onNodeWithText("Close").performClick()
        assertEquals(SessionStatus.STOPPED, session.value.status)
        assertEquals(0, starts); assertEquals(0, stops)
    }

    @Test fun unavailableConnectionProvidesReasonAndNeverStarts() {
        mount()
        listOf(BluetoothAvailability.PERMISSIONS_NEEDED, BluetoothAvailability.BLUETOOTH_OFF).forEach { state ->
            compose.runOnIdle { availability.value = state }
            visible(state.message)
            compose.onNodeWithContentDescription("Start").assertIsNotEnabled()
        }
        compose.runOnIdle { availability.value = BluetoothAvailability.READY; connection.value = ConnectionState() }
        compose.onNodeWithContentDescription("Open Devices").performClick()
        compose.onNodeWithText("Saved devices").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertEquals(0, starts)
    }

    @Test fun previousSavedStateIsHiddenForANewSessionAndIdleHasNoPersistentNotice() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = SessionSaveController(scope) {}
        try {
            compose.runOnIdle { controller.submit(databaseFixture("previous")) }
            mount(controller)
            compose.onNodeWithText("Save: Saved").assertDoesNotExist()
            compose.onNodeWithText("Save session ID:", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Save: No session to save").assertDoesNotExist()
            compose.runOnIdle { session.value = session.value.copy(record = databaseFixture("previous").record) }
            compose.onNodeWithContentDescription("Open Devices").performClick()
            compose.onNodeWithText("Save: Saved").assertDoesNotExist()
            compose.onNodeWithText("Close").performClick()
        } finally { scope.cancel() }
    }

    @Test fun failedSaveIsRecoverableOnBothPagesAndDiscardRequiresConfirmation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var writes = 0
        val controller = SessionSaveController(scope) { writes++; throw IllegalStateException("Controlled save failure. ".repeat(12)) }
        try {
            compose.runOnIdle { controller.submit(databaseFixture("current")) }
            mount(controller)
            visible("Save: Save failed")
            compose.onNodeWithContentDescription("Start").assertIsNotEnabled()
            compose.onNodeWithText("History").performClick()
            visible("Retry save")
            compose.onNodeWithText("Retry save").performClick()
            compose.waitForIdle(); assertEquals(2, writes)
            compose.onNodeWithText("Discard session").reveal().performClick()
            compose.onNodeWithText("Discard unsaved session?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(SaveStatus.FAILED, controller.state.value.status)
            compose.onNodeWithText("Session").performClick()
            compose.onNodeWithContentDescription("Open Devices").performClick()
            compose.onAllNodesWithText("Retry save").assertCountEquals(0)
            compose.onNodeWithText("Discard session").reveal().performClick()
            compose.onNodeWithText("Discard").performClick()
            assertEquals(SaveStatus.DISCARDED, controller.state.value.status)
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithContentDescription("Start").assertIsEnabled()
        } finally { scope.cancel() }
    }

    @Test fun savingIsVisibleOnBothPagesAndBlocksNewStartUntilCommit() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val committed = CompletableDeferred<Unit>()
        val controller = SessionSaveController(scope) { committed.await() }
        try {
            compose.runOnIdle { controller.submit(databaseFixture("current")) }
            mount(controller)
            visible("Save: Saving…")
            compose.onNodeWithContentDescription("Start").assertIsNotEnabled()
            compose.onNodeWithText("History").performClick()
            visible("Save: Saving…")
            compose.runOnIdle { committed.complete(Unit) }
            visible("Save: Saved")
            compose.onNodeWithText("Session").performClick()
            compose.onNodeWithContentDescription("Start").assertIsEnabled()
        } finally { scope.cancel() }
    }

    @Test fun historyDetailLayerSurvivesTabSwitchAndStateRestoration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "step84a-${UUID.randomUUID()}.db"
        val db = SessionDatabase(context, name)
        try {
            runBlocking { db.save(databaseFixture()) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { Fixture(db = db) }
            compose.onNodeWithText("History").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Duration", substring = false).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Duration", substring = false).reveal().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Delete session").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Session").performClick()
            compose.onNodeWithText("History").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Delete session").fetchSemanticsNodes().isNotEmpty() }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Delete session").fetchSemanticsNodes().isNotEmpty() }
            visible("Delete session")
            compose.onNodeWithText("Delete session").performClick()
            compose.onNodeWithText("Delete this session?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(1, runBlocking { db.page().size })
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun controlledLightDarkFontLayoutsKeepFixedControlsAndContentReachable() {
        mount()
        listOf(1f).forEach { font ->
            listOf(false, true).forEach { night ->
                compose.runOnIdle { scale.value = font; dark.value = night }
                compose.onNodeWithContentDescription("Open Devices").assertIsDisplayed()
                checkedDataTypes.forEach { compose.onNodeWithContentDescription("${it.name}: Idle").reveal().assertIsDisplayed() }
                val prefix = "controlled-${if (night) "dark" else "light"}-font$font"
                screenshot("$prefix-header")
                visible("Bottom chart fixture")
                compose.onNodeWithContentDescription("Start").assertIsDisplayed()
                compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
                noOverflow("Session"); noOverflow("History")
                listOf("Start", "Stop").forEach { compose.onNodeWithContentDescription(it)
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
                screenshot("$prefix-bottom")
            }
        }
    }

    @Test fun actualSystemFontsKeepTabsControlsAndRecoveryReachable() {
        scale.value = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = SessionSaveController(scope) { throw IllegalStateException("Controlled long save error. ".repeat(16)) }
        try {
            compose.runOnIdle { controller.submit(databaseFixture("current")) }
            mount(controller)
            listOf(false, true).forEach { night ->
                compose.runOnIdle { dark.value = night }
                compose.onNodeWithContentDescription("Open Devices").assertIsDisplayed()
                screenshot("system-${if (night) "dark" else "light"}-header")
                compose.onNodeWithContentDescription("Open Devices").performClick()
                compose.onAllNodesWithText("Retry save").assertCountEquals(0)
                compose.onNodeWithText("Discard session").reveal().performClick()
                compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
                compose.onNodeWithText("Close").performClick()
                visible("Bottom chart fixture")
                noOverflow("Session"); noOverflow("History")
                listOf("Start", "Stop").forEach { compose.onNodeWithContentDescription(it)
                    .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
                screenshot("system-${if (night) "dark" else "light"}-bottom")
                compose.onNodeWithText("History").performClick()
                visible("Retry save")
                screenshot("system-${if (night) "dark" else "light"}-history")
                compose.onNodeWithText("Session").performClick()
            }
        } finally { scope.cancel() }
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
