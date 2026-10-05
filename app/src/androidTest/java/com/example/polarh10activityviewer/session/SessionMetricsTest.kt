package com.example.polarh10activityviewer.session

import com.example.polarh10activityviewer.heartrate.HeartRateStatistics
import com.example.polarh10activityviewer.heartrate.HeartRateReading
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.example.polarh10activityviewer.BluetoothAvailability
import com.example.polarh10activityviewer.SessionScreen
import com.example.polarh10activityviewer.ble.*
import com.example.polarh10activityviewer.heartrate.HeartRateZoneState
import com.example.polarh10activityviewer.heartrate.HeartRateZone
import com.example.polarh10activityviewer.motion.StepState
import com.example.polarh10activityviewer.storage.databaseFixture
import com.example.polarh10activityviewer.storage.SessionDatabase
import com.example.polarh10activityviewer.history.HistoryPanel
import com.example.polarh10activityviewer.ui.theme.PolarH10ActivityViewerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.semantics.ProgressBarRangeInfo

// Controlled states validate rendering and callback routing, not H10 acquisition.
class SessionMetricsTest {
    @get:Rule val compose = createComposeRule()
    private val reading = mutableStateOf<HeartRateReading?>(HeartRateReading(123))
    private val statistics = mutableStateOf(HeartRateStatistics(3, 361, 110, 130))
    private val motion = mutableStateOf(StepState(totalSteps = 121, cadence = 123.6, maximumCadence = 168.0,
        receivedAcc = true, durationMs = 60_000))
    private val zones = mutableStateOf(HeartRateZoneState(listOf(500, 1000, 1500, 2000, 2500),
        current = HeartRateZone.LIGHT, receivedValidHr = true, unclassifiedMs = 1300))
    private val session = mutableStateOf(SessionState(SessionStatus.RUNNING, elapsedMs = 60_000))
    private val connected = mutableStateOf(true)
    private val checking = mutableStateOf(false)
    private val hr = mutableStateOf(SubscriptionState(SubscriptionStatus.RECEIVING))
    private val acc = mutableStateOf(SubscriptionState(SubscriptionStatus.RECEIVING))
    private val message = mutableStateOf<String?>(null)
    private val dark = mutableStateOf(false)
    private val scale = mutableStateOf<Float?>(1f)

    @Composable private fun Fixture() {
        val density = LocalDensity.current
        val fixtureDensity = scale.value?.let { Density(density.density, it) } ?: density
        CompositionLocalProvider(LocalDensity provides fixtureDensity) {
            PolarH10ActivityViewerTheme(darkTheme = dark.value) {
                Scaffold { padding ->
                    Column(Modifier.padding(padding)) {
                        Text("Controlled UI fixture — no H10 data")
                        SessionScreen(availability = BluetoothAvailability.READY, actionEnabled = true,
                            errorMessage = null, onBluetoothAction = {}, scanState = ScanState(),
                            onStartScan = {}, onStopScan = {},
                            connectionState = if (connected.value) ConnectionState(ConnectionStatus.CONNECTED,
                                ConnectionDevice("Controlled device", "TEST1234")) else ConnectionState(),
                            onConnect = {}, savedDevicesState = SavedDevicesState(loading = false),
                            onDisconnect = {}, onRetryDisconnect = {},
                            dataReadiness = checkedDataTypes.associateWith {
                                DataReadiness(if (checking.value) DataReadinessStatus.CHECKING else DataReadinessStatus.READY,
                                    configurationComplete = true)
                            }, onRecheckData = {}, heartRate = reading.value, heartRateStatistics = statistics.value,
                            heartRateMessage = message.value, steps = motion.value, heartRateZones = zones.value,
                            session = session.value, hrSubscription = hr.value, accSubscription = acc.value,
                            ecgSubscription = SubscriptionState(SubscriptionStatus.RECEIVING),
                            charts = { Text("Controlled chart position") },
                            saveStatus = { Text("Controlled save recovery position") })
                    }
                }
            }
        }
    }
    private fun mount() = compose.setContent { Fixture() }
    private fun visible(text: String) = compose.onNodeWithText(text).reveal().assertIsDisplayed()
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "step84b-controlled-visual").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun noOverflow(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText(text)[0].reveal().performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(results))
        }
        val result = results.single()
        // A wrapping Text can retain a wider paragraph box than its measured content.
        // Check rendered line extents rather than that unused paragraph width.
        assertFalse("Truncated text: $text", result.multiParagraph.didExceedMaxLines)
        for (line in 0 until result.lineCount) {
            assertTrue("Right overflow: $text", result.getLineRight(line) <= result.size.width + 1)
            assertTrue("Left overflow: $text", result.getLineLeft(line) >= -1)
            assertTrue("Vertical overflow: $text", result.getLineBottom(line) <= result.size.height + 1)
        }
    }

    @Test fun validReadingsUseExistingStatisticsAndDisplayOnlyRounding() {
        mount()
        visible("123"); visible("Max HR: 130 bpm"); visible("Max HR: 130 bpm"); visible("Mean HR: 120 bpm")
        visible("124"); visible("Mean: 121 steps/min");
        visible("Max: 168 steps/min")
        visible("01:00"); visible("121")
        compose.onNodeWithText("Estimated Distance").assertDoesNotExist()
        listOf("Min HR:", "Min cadence:", "Estimated speed", "Mean speed:", "Max speed:", "km/h").forEach {
            compose.onAllNodesWithText(it, substring = true).assertCountEquals(0)
        }
        assertEquals(123.6, motion.value.cadence!!, 0.0)
    }

    @Test fun sessionOrdersChartSummaryZonesAndRecoveryAfterMetrics() {
        mount()
        val titles = listOf("Connected", "Max HR: 130 bpm", "Cadence", "Controlled chart position",
            "Duration", "HR Zone")
        visible("Connected")
        val positions = titles.map { compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertTrue("Session sections out of order: $positions", positions.zipWithNext().all { (first, second) -> first < second })
        titles.forEach { visible(it) }
    }

    @Test fun staleZoneCannotShowValidIntensityWithoutAReadingDuringFailureOrAfterStop() {
        zones.value = zones.value.copy(current = HeartRateZone.MODERATE)
        mount(); visible("Moderate · Zone 3")
        compose.runOnIdle { reading.value = null }
        compose.onNodeWithText("Moderate · Zone 3").assertDoesNotExist()
        visible("Mean HR: 120 bpm")
        compose.runOnIdle { reading.value = HeartRateReading(130)
            hr.value = SubscriptionState(SubscriptionStatus.FAILED) }
        compose.onNodeWithText("Moderate · Zone 3").assertDoesNotExist()
        visible("HR unavailable")
        compose.runOnIdle { hr.value = SubscriptionState(SubscriptionStatus.RECEIVING)
            session.value = session.value.copy(status = SessionStatus.STOPPED) }
        compose.onNodeWithText("Moderate · Zone 3").assertDoesNotExist()
        visible("Stopped"); visible("Max HR: 130 bpm")
        compose.onAllNodesWithText("Heart rate intensity").assertCountEquals(0)
    }

    @Test fun zeroDurationZonesShowEmptyProgressAndUnknownPercentage() {
        zones.value = HeartRateZoneState(current = HeartRateZone.LIGHT, receivedValidHr = true)
        mount();
        for (index in 1..5) {
            val bar = compose.onNodeWithContentDescription("Zone $index, cumulative duration 00:00, -- of active time")
            bar.reveal()
            bar.assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
        }
        compose.onAllNodesWithText("No valid HR data").assertCountEquals(0)
        compose.onAllNodesWithText("%", substring = true).assertCountEquals(0)

    }

    @Test fun normalMetricColumnsStackWhenFontScaleDoubles() {
        mount(); visible("123")
        val value = compose.onNodeWithText("123").fetchSemanticsNode().positionInRoot
        val stats = compose.onNodeWithText("Max HR: 130 bpm").fetchSemanticsNode().positionInRoot
        assertTrue("HR statistics should be to the right", stats.x > value.x)
        compose.runOnIdle { scale.value = 2f }
        visible("123")
        val largeValue = compose.onNodeWithText("123").fetchSemanticsNode().positionInRoot
        val largeStats = compose.onNodeWithText("Max HR: 130 bpm").fetchSemanticsNode().positionInRoot
        assertTrue("Large-font statistics should stack", largeStats.y > largeValue.y)
        assertTrue("Stacked statistics must stay within the card", largeStats.x <= largeValue.x)
        noOverflow("Max HR: 130 bpm")
    }

    @Test fun unavailableReadingsRetainPlaceholdersRatherThanInventingZeros() {
        reading.value = null; statistics.value = HeartRateStatistics(); motion.value = StepState()
        zones.value = HeartRateZoneState(); mount()
        compose.onAllNodesWithText("Last Received", substring = true).assertCountEquals(0); visible("Max HR: -- bpm")
        visible("Mean: -- steps/min");
        visible("No valid HR")
        compose.onNodeWithContentDescription("Open Devices").performClick()
        compose.onNodeWithText("No ACC observations. Statistics are unavailable.").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()

        compose.onAllNodesWithText("0").assertCountEquals(0)
        compose.onAllNodesWithText("0.0").assertCountEquals(0)
    }

    @Test fun initialWarmupShowsGenuineCurrentZerosWithoutDialogDetails() {
        motion.value = StepState(cadence = 0.0)
        mount(); visible("0"); visible("Mean: -- steps/min")
        compose.onNodeWithContentDescription("Open Devices").performClick()
        compose.onNodeWithText("Warming up ACC.").assertDoesNotExist()
    }

    @Test fun invalidHrKeepsStatisticsAndFullFailureDetailsWithoutRetry() {
        reading.value = null; message.value = "No skin contact. Adjust the chest strap."
        hr.value = SubscriptionState(SubscriptionStatus.FAILED, "Controlled HR failure.")
        mount(); compose.onAllNodesWithText("Last Received", substring = true).assertCountEquals(0); visible("Mean HR: 120 bpm")
        compose.onAllNodesWithText("Retry", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Open Devices").performClick()
        compose.onNodeWithText(message.value!!, substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("HR: Controlled HR failure.", substring = true).assertIsDisplayed()
    }

    @Test fun gapAndAccFailureKeepTotalsAndActionableErrorsWithoutDetails() {
        motion.value = motion.value.copy(cadence = null, incompleteAcc = true)
        acc.value = SubscriptionState(SubscriptionStatus.FAILED, "Controlled ACC failure.")
        mount(); visible("121")
        compose.onNodeWithText("Estimated Distance").assertDoesNotExist()
        compose.onAllNodesWithText("Retry", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Open Devices").performClick()
        visible("ACC: Controlled ACC failure.")
        compose.onNodeWithText("Incomplete ACC data. Mean cadence may be lower.").assertDoesNotExist()
    }

    @Test fun checkingDisconnectionAndStoppedNeverRestoreCardRetry() {
        hr.value = SubscriptionState(SubscriptionStatus.FAILED)
        acc.value = SubscriptionState(SubscriptionStatus.FAILED); checking.value = true; mount()
        compose.onAllNodesWithText("Retry", substring = true).assertCountEquals(0)
        compose.runOnIdle { checking.value = false; connected.value = false }
        compose.onAllNodesWithText("Retry", substring = true).assertCountEquals(0)
        compose.runOnIdle { connected.value = true; session.value = session.value.copy(status = SessionStatus.STOPPED) }
        compose.onAllNodesWithText("Retry", substring = true).assertCountEquals(0)
        visible("Stopped"); visible("121")
    }

    @Test fun stoppedObservedZerosAndNewSessionStateAreRenderedWithoutCaching() {
        reading.value = null; session.value = session.value.copy(status = SessionStatus.STOPPED)
        motion.value = motion.value.copy(cadence = 0.0)
        mount(); visible("0"); visible("Mean HR: 120 bpm"); visible("121")
        compose.runOnIdle { motion.value = StepState(); statistics.value = HeartRateStatistics()
            zones.value = HeartRateZoneState(); session.value = SessionState(SessionStatus.STARTING) }
        visible("Mean HR: -- bpm");
        compose.onAllNodesWithText("121").assertCountEquals(0)
        compose.onAllNodesWithText("0").assertCountEquals(0)
    }

    @Test fun liveZonePercentagesRoundAndUseActiveTimeIncludingUnclassifiedTime() {
        zones.value = HeartRateZoneState(listOf(0, 500, 1000, 0, 0), receivedValidHr = true)
        mount()
        fun bar(zone: Int, duration: String, percent: String) = compose.onNodeWithContentDescription(
            "Zone $zone, cumulative duration $duration, $percent of active time").reveal()
        bar(1, "00:00", "0%").assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
        bar(2, "00:00", "33%").assertRangeInfoEquals(ProgressBarRangeInfo(1f / 3f, 0f..1f))
        bar(3, "00:01", "67%").assertRangeInfoEquals(ProgressBarRangeInfo(2f / 3f, 0f..1f))
        compose.runOnIdle { zones.value = zones.value.copy(unclassifiedMs = 500) }
        bar(2, "00:00", "25%").assertRangeInfoEquals(ProgressBarRangeInfo(0.25f, 0f..1f))
        bar(3, "00:01", "50%").assertRangeInfoEquals(ProgressBarRangeInfo(0.5f, 0f..1f))
        compose.runOnIdle { zones.value = zones.value.copy(durationsMs = listOf(0, 5, 395, 0, 0), unclassifiedMs = 0) }
        bar(2, "00:00", "1%").assertRangeInfoEquals(ProgressBarRangeInfo(0.0125f, 0f..1f))
        compose.runOnIdle { zones.value = zones.value.copy(durationsMs = listOf(0, 6, 394, 0, 0)) }
        bar(2, "00:00", "2%").assertRangeInfoEquals(ProgressBarRangeInfo(0.015f, 0f..1f))
    }

    @Test fun fiveZoneBarsRenderTheExactPaletteInBothThemes() {
        zones.value = HeartRateZoneState(List(5) { 1000L }, receivedValidHr = true)
        mount()
        val expected = listOf(0xFF22C55E.toInt(), 0xFF3B82F6.toInt(), 0xFFEAB308.toInt(),
            0xFFF97316.toInt(), 0xFFEF4444.toInt())
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            for (index in 0..4) {
                val node = compose.onNodeWithContentDescription("Zone ${index + 1}, cumulative duration 00:01, 20% of active time")
                node.reveal()
                val bitmap = node.captureToImage().asAndroidBitmap()
                assertEquals(expected[index], bitmap.getPixel(bitmap.width / 10, bitmap.height / 2))
            }
            screenshot(if (night) "dark-zone-bars" else "light-zone-bars")
        }
        visible("Z3"); visible("125–139 bpm")
        visible("Z5"); visible("≥155 bpm")
        compose.onNodeWithText("Unclassified", substring = true).assertDoesNotExist()
    }

    @Test fun longValuesFitAndErrorsStayOnOneLineWithoutExpandingCards() {
        session.value = session.value.copy(elapsedMs = 14_400_000)
        motion.value = motion.value.copy(totalSteps = 123456)
        acc.value = SubscriptionState(SubscriptionStatus.FAILED, "Controlled long failure explanation. ".repeat(20))
        mount()
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            noOverflow("240:00"); noOverflow("123456"); compose.onNodeWithText("Estimated Distance").assertDoesNotExist()
            compose.onNodeWithContentDescription("Open Devices").performClick()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("device-error").performScrollTo()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertTrue(layouts.single().isLineEllipsized(0))
            compose.onNodeWithText("Close").performClick()
            visible("Z5")
        }
    }

    @Test fun unnecessarySessionDevelopmentDisplaysAreRemoved() {
        session.value = session.value.copy(record = databaseFixture("controlled-metadata", 1000).record)
        mount()
        compose.onAllNodesWithText("Session ID:", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("development check", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Eligible for saving:", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Steps (development check)").assertCountEquals(0)
        compose.onAllNodesWithText("Heart rate (development check)").assertCountEquals(0)
        compose.onAllNodesWithText("Running duration: 2500 ms").assertCountEquals(0)
        compose.onAllNodesWithText("HR min / max / mean:", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Total steps:", substring = true).assertCountEquals(0)
    }

    @Test fun currentSystemFontKeepsNormalCardsInOrder() {
        scale.value = null; mount()
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            for (title in listOf("Max HR: 130 bpm", "Cadence", "Duration", "HR Zone")) visible(title)
            val prefix = "system-${if (night) "dark" else "light"}"
            noOverflow("123"); screenshot("$prefix-Heart-rate")
            noOverflow("124"); visible("steps/min")
            screenshot("$prefix-Motion")
            noOverflow("01:00"); noOverflow("121")
            compose.onNodeWithText("Estimated Distance").assertDoesNotExist()
            screenshot("$prefix-Activity-summary")
            visible("Z5"); screenshot("$prefix-Heart-rate-zones")
        }
    }

    @Test fun historyKeepsStoredSummaryAndPercentagesWithSharedZoneVisuals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "step82-history-${UUID.randomUUID()}.db"
        val db = SessionDatabase(context, name)
        try {
            val fixture = databaseFixture("controlled-history", 1000)
            runBlocking { db.save(fixture) }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    PolarH10ActivityViewerTheme(darkTheme = dark.value) {
                        Scaffold { padding ->
                            Column(Modifier.padding(padding)) {
                                Text("Controlled SQLite fixture — no H10 data")
                                HistoryPanel(db, null, {})
                            }
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Duration", substring = false)
                .fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Duration", substring = false).reveal().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Delete session").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("history-detail-controlled-history").assertExists()
            visible("80–140")
            for (night in listOf(false, true)) {
                compose.runOnIdle { dark.value = night }
                visible("HR Zones")
                noOverflow("125–139 bpm")
                visible("4%")
                visible("20%")
                compose.onNodeWithText("Unclassified", substring = true).assertDoesNotExist()
                screenshot("history-${if (night) "dark" else "light"}-font2-horizontal-zones")
                visible("Delete session")
            }
            assertEquals(fixture, runBlocking { db.detail("controlled-history") })
        } finally { db.close(); context.deleteDatabase(name) }
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
