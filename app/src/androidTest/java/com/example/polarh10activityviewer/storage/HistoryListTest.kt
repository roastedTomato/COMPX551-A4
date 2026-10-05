package com.example.polarh10activityviewer.storage

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.polarh10activityviewer.history.HistoryPanel
import com.example.polarh10activityviewer.session.SavePanel
import com.example.polarh10activityviewer.session.SessionScaffold
import com.example.polarh10activityviewer.ui.theme.PolarH10ActivityViewerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HistoryListTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "history-list-test-${UUID.randomUUID()}.db"
    private lateinit var db: SessionDatabase
    private val originalZone = TimeZone.getDefault()
    @Before fun open() { db = SessionDatabase(context, name) }
    @After fun close() { TimeZone.setDefault(originalZone); db.close(); context.deleteDatabase(name) }

    private fun seed(count: Int) = runBlocking {
        repeat(count) { index -> db.save(databaseFixture(id(index), (index / 3).toLong())) }
    }
    private fun id(index: Int) = "session-${index.toString().padStart(2, '0')}"
    private fun tag(index: Int) = "history-row-${id(index)}"
    private fun list() = compose.onNodeWithTag("history-list")
    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitRow(recordId: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-row-$recordId").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun reveal(recordId: String) {
        // A scroll may reach the old end before the asynchronous next page arrives.
        compose.waitUntil(10_000) {
            runCatching { list().performScrollToNode(hasTestTag("history-row-$recordId")) }.isSuccess
        }
        compose.onNodeWithTag("history-row-$recordId").assertIsDisplayed()
    }
    private fun click(text: String) { if (text == "Back") compose.onNodeWithContentDescription("Back").performClick() else compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun rename(unavailable: Boolean) = runBlocking {
        withContext(Dispatchers.IO) {
            db.writableDatabase.execSQL(if (unavailable) "ALTER TABLE sessions RENAME TO unavailable_sessions"
                else "ALTER TABLE unavailable_sessions RENAME TO sessions")
        }
    }
    private fun end() {
        list().performScrollToNode(hasTestTag("history-list-status"))
    }

    @Test fun emptyDatabaseShowsEmptyStateWithoutLoadMore() {
        compose.setContent { MaterialTheme { HistoryPanel(db, null, {}) } }
        awaitText("No saved sessions")
        compose.onNodeWithText("No saved sessions").assertIsDisplayed()
        compose.onNodeWithText("Load more").assertDoesNotExist()
    }

    @Test fun oneRecordHasShortDateDurationAndOpensActualId() {
        seed(1)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        compose.setContent { MaterialTheme { HistoryPanel(db, null, {}) } }
        awaitRow(id(0))
        compose.onNodeWithText("01 Jan 1970").assertIsDisplayed()
        compose.onNodeWithText("00:00").assertIsDisplayed()
        compose.onNodeWithText("00:02").assertIsDisplayed()
        compose.onNodeWithText("Incomplete").assertDoesNotExist()
        compose.onNodeWithText("Load more").assertDoesNotExist()
        compose.onNodeWithTag(tag(0)).performClick()
        awaitText("Delete session")
        compose.onNodeWithTag("history-detail-${id(0)}").assertExists()
    }

    @Test fun reopenedDatabaseLoadsFortyFiveTiedTimeRowsAutomaticallyInStableOrder() {
        seed(45)
        db.close(); db = SessionDatabase(context, name)
        compose.setContent { MaterialTheme { HistoryPanel(db, null, {}) } }
        awaitRow(id(44))
        val visited = mutableListOf<String>()
        for (index in 44 downTo 0) {
            reveal(id(index))
            visited += id(index)
            val visible = compose.onAllNodes(SemanticsMatcher("History card") {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("history-row-")
            }).fetchSemanticsNodes().filter {
                compose.onNodeWithTag(it.config[SemanticsProperties.TestTag]).isDisplayed()
            }
                .sortedBy { it.boundsInRoot.top }.map { it.config[SemanticsProperties.TestTag] }
            assertEquals(visible.distinct(), visible)
            assertEquals(visible.sortedDescending(), visible)
        }
        assertEquals(45, visited.distinct().size)
        end()
        compose.onNodeWithText("Load more").assertDoesNotExist()
        compose.onNodeWithText("Loading…").assertDoesNotExist()
    }

    @Test fun exactlyTenRecordsStopsAfterEmptyPageAndEleventhAppearsAfterRefresh() {
        seed(10)
        val saved = mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme { HistoryPanel(db, saved.value, {}) } }
        awaitRow(id(9)); reveal(id(0)); end(); compose.waitForIdle()
        // Once exhaustion is known, further scrolling must not issue queries.
        rename(true)
        list().performScrollToIndex(0); end()
        compose.onNodeWithText("Retry query").assertDoesNotExist()
        rename(false)
        runBlocking { db.save(databaseFixture(id(10), 1000)) }
        compose.runOnIdle { saved.value = id(10) }
        awaitRow(id(10)); reveal(id(0)); end()
        compose.onNodeWithText("Load more").assertDoesNotExist()
        compose.onNodeWithText("Loading…").assertDoesNotExist()
    }

    @Test fun paginationSqliteFailureRetainsCardsAndRetriesSameCursor() {
        seed(25)
        compose.setContent { MaterialTheme { HistoryPanel(db, null, {}) } }
        awaitRow(id(24))
        rename(true)
        list().performScrollToNode(hasTestTag(tag(15)))
        end(); awaitText("Retry query")
        reveal(id(24))
        compose.onNodeWithTag(tag(24)).assertIsDisplayed()
        end()
        rename(false)
        click("Retry query")
        for (index in 14 downTo 0) reveal(id(index))
        end()
        compose.onNodeWithText("Retry query").assertDoesNotExist()
        compose.onNodeWithText("Load more").assertDoesNotExist()
    }

    @Test fun initialSqliteFailureRetryUsesCurrentTimezone() {
        seed(1)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        rename(true)
        compose.setContent { MaterialTheme { HistoryPanel(db, null, {}) } }
        awaitText("Retry query")
        compose.onNodeWithText("No saved sessions").assertDoesNotExist()
        rename(false)
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        click("Retry query"); awaitRow(id(0))
        compose.onNodeWithText("01 Jan 1970").assertIsDisplayed()
        compose.onNodeWithText("12:00").assertIsDisplayed()
    }

    @Test fun committedSaveRefreshesFirstPageButDoesNotReloadVisibleDetail() {
        seed(25)
        val savedId = mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme { HistoryPanel(db, savedId.value, {}) } }
        awaitRow(id(24)); reveal(id(12))
        runBlocking { db.save(databaseFixture("newest", 9999)) }
        compose.runOnIdle { savedId.value = "newest" }
        awaitRow("newest")
        compose.onNodeWithTag("history-row-newest").assertIsDisplayed().performClick()
        awaitText("Delete session")
        runBlocking { db.save(databaseFixture("another", 10000)) }
        rename(true)
        compose.runOnIdle { savedId.value = "another" }
        compose.waitForIdle()
        compose.onNodeWithText("Delete session").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry query").assertDoesNotExist()
        rename(false)
        click("Back"); awaitRow("another")
    }

    @Test fun pendingSaveRemainsRetryableFromRealListAndCommitRefreshesCards() {
        seed(25)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = SessionSaveController(scope, db::save)
        val snapshot = databaseFixture("pending", 9999)
        try {
            runBlocking { withContext(Dispatchers.IO) {
                db.writableDatabase.execSQL("CREATE TRIGGER fail_save BEFORE INSERT ON motion_points BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            } }
            compose.setContent { MaterialTheme {
                val save by controller.state.collectAsState()
                HistoryPanel(db, save.sessionId.takeIf { save.status == SaveStatus.SAVED }, {},
                    sessionStatus = { SavePanel(save, controller, snapshot.record.id) }, allowCompact = !save.blocksStart)
            } }
            compose.runOnIdle { controller.submit(snapshot) }
            compose.waitUntil(10_000) { controller.state.value.status == SaveStatus.FAILED }
            assertTrue(controller.state.value.blocksStart)
            awaitRow(id(24)); reveal(id(0)); end()
            compose.onNodeWithText("Retry save").assertIsDisplayed()
            click("Discard session"); compose.onNodeWithText("Cancel").performClick()
            assertEquals(SaveStatus.FAILED, controller.state.value.status)
            runBlocking { withContext(Dispatchers.IO) { db.writableDatabase.execSQL("DROP TRIGGER fail_save") } }
            click("Retry save")
            awaitRow("pending")
            assertFalse(controller.state.value.blocksStart)
            assertEquals(snapshot, runBlocking { db.detail("pending") })
            compose.onNodeWithTag("history-row-pending").assertIsDisplayed()
        } finally { scope.cancel() }
    }

    @Test fun failedSaveRetryFromDetailPreservesSelectedSessionAndRefreshesOnBack() {
        seed(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = SessionSaveController(scope, db::save)
        val pending = databaseFixture("pending-detail", 9999)
        try {
            compose.setContent { MaterialTheme {
                val save by controller.state.collectAsState()
                HistoryPanel(db, save.sessionId.takeIf { save.status == SaveStatus.SAVED }, {},
                    sessionStatus = { if (save.blocksStart) SavePanel(save, controller, pending.record.id) },
                    allowCompact = !save.blocksStart)
            } }
            awaitRow(id(0)); compose.onNodeWithTag(tag(0)).performClick(); awaitText("Delete session")
            runBlocking { withContext(Dispatchers.IO) {
                db.writableDatabase.execSQL("CREATE TRIGGER fail_detail_save BEFORE INSERT ON motion_points BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            } }
            compose.runOnIdle { controller.submit(pending) }
            awaitText("Retry save")
            assertTrue(controller.state.value.blocksStart)
            click("Discard session"); compose.onNodeWithText("Cancel").performClick()
            assertEquals(SaveStatus.FAILED, controller.state.value.status)
            compose.onNodeWithTag("history-detail-${id(0)}").assertExists()
            compose.onNodeWithText("Delete session").performScrollTo().assertIsDisplayed()
            noTextOverflow()
            runBlocking { withContext(Dispatchers.IO) { db.writableDatabase.execSQL("DROP TRIGGER fail_detail_save") } }
            click("Retry save")
            compose.waitUntil(10_000) { controller.state.value.status == SaveStatus.SAVED }
            compose.onNodeWithTag("history-detail-${id(0)}").assertExists()
            assertEquals(pending, runBlocking { db.detail(pending.record.id) })
            click("Back"); awaitRow(pending.record.id)
        } finally { scope.cancel() }
    }

    @Test fun detailReturnReentryAndStateRestorationReloadFirstPageAtTop() {
        seed(25)
        val visible = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            if (visible.value) HistoryPanel(db, null, { visible.value = false })
            else Button(onClick = { visible.value = true }) { Text("Open History") }
        } }
        awaitRow(id(24)); reveal(id(12))
        compose.onNodeWithTag(tag(12)).performClick(); awaitText("Delete session")
        click("Back"); awaitRow(id(24))
        compose.onNodeWithText("History Activities").assertIsDisplayed()
        reveal(id(12)); restoration.emulateSavedInstanceStateRestore()
        awaitRow(id(24))
        compose.onNodeWithText("History Activities").assertIsDisplayed()
        reveal(id(12)); Espresso.pressBack()
        compose.onNodeWithText("Open History").performClick(); awaitRow(id(24))
        compose.onNodeWithText("History Activities").assertIsDisplayed()
    }

    @Test fun leavingDuringBlockedPageQueryRejectsLateRowsAndDuplicateLoads() {
        seed(25)
        val visible = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (visible.value) HistoryPanel(db, null, { visible.value = false })
            else Button(onClick = { visible.value = true }) { Text("Open History") }
        } }
        awaitRow(id(24))
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val blocker = executor.submit {
            db.writableDatabase.beginTransaction()
            try { acquired.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
            finally { db.writableDatabase.endTransaction() }
        }
        try {
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            list().performScrollToNode(hasTestTag(tag(15))); end(); compose.waitForIdle()
            repeat(3) { list().performTouchInput { swipeUp() } }
            Espresso.pressBack(); compose.onNodeWithText("Open History").performClick()
            release.countDown(); blocker.get(5, TimeUnit.SECONDS)
            awaitRow(id(24))
            compose.onNodeWithText("History Activities").assertIsDisplayed()
            for (index in 24 downTo 0) reveal(id(index))
            end(); compose.onNodeWithText("Retry query").assertDoesNotExist()
        } finally { release.countDown(); blocker.get(5, TimeUnit.SECONDS); executor.shutdownNow() }
    }

    @Test fun referenceCardsAndScrollbarFitBothThemesAtActualSystemFont() {
        val dark = mutableStateOf(false)
        val savedId = mutableStateOf<String?>(null)
        val started = Instant.parse("2026-10-02T21:10:00Z").toEpochMilli()
        runBlocking { repeat(25) { index ->
            val fixture = databaseFixture("visual-$index", started - index * 3600000L)
            db.save(fixture.copy(record = fixture.record.copy(durationMs = if(index == 1) 14_400_000 else 1_200_000,
                interrupted = index == 2,
                streams = fixture.record.streams.mapValues { it.value.copy(missing = false) })))
        } }
        compose.setContent { PolarH10ActivityViewerTheme(darkTheme = dark.value) {
            SessionScaffold(true, {}, {}, {}, {
                Column {
                    Text("UI TEST DATA · no H10", fontSize = 10.sp, lineHeight = 12.sp)
                    Box(Modifier.weight(1f)) { HistoryPanel(db, savedId.value, {}) }
                }
            })
        } }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night; savedId.value = night.toString() }
            awaitRow("visual-0")
            compose.onNodeWithText("History Activities").assertIsDisplayed()
            noTextOverflow()
            capture("${if(night) "dark" else "light"}-top")
            reveal("visual-1"); noTextOverflow()
            reveal("visual-11"); noTextOverflow()
            capture("${if(night) "dark" else "light"}-next-page")
            reveal("visual-24"); end(); noTextOverflow()
            capture("${if(night) "dark" else "light"}-end")
        }
    }

    private fun noTextOverflow() {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        repeat(nodes.fetchSemanticsNodes().size) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.forEach { layout ->
                assertFalse("Truncated: ${layout.layoutInput.text}", layout.multiParagraph.didExceedMaxLines)
                assertTrue("Vertical overflow: ${layout.layoutInput.text}", layout.size.height >= layout.multiParagraph.height - 1)
                repeat(layout.lineCount) { line ->
                    // Aligned text uses paragraph coordinates, not the intrinsic Text box's origin.
                    assertTrue("Horizontal overflow: ${layout.layoutInput.text}",
                        layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1)
                }
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val dir = File(context.getExternalFilesDir(null), "step85a-captures").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
