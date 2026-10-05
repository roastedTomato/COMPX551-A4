package com.example.polarh10activityviewer.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.polarh10activityviewer.heartrate.HeartRateZoneRows
import com.example.polarh10activityviewer.session.SessionRecord
import com.example.polarh10activityviewer.session.SummaryCard
import com.example.polarh10activityviewer.session.SessionSnapshot
import com.example.polarh10activityviewer.session.SessionSummaryPanel
import com.example.polarh10activityviewer.session.sessionBackground
import com.example.polarh10activityviewer.session.sessionBlue
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun HistoryDetailLayout(snapshot: SessionSnapshot?, loading: Boolean, error: String?, deleteError: String?,
    date: DateTimeFormatter, canGoBack: Boolean, onBack: () -> Unit, onRetry: () -> Unit,
    onDelete: () -> Unit, sessionStatus: @Composable () -> Unit, allowCompact: Boolean = true,
    database: com.example.polarh10activityviewer.storage.SessionDatabase? = null) {
    val typography = MaterialTheme.typography.copy(
        titleMedium = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
        titleSmall = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, lineHeight = 14.sp),
        bodySmall = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 13.sp)
    )
    MaterialTheme(typography = typography) {
        BoxWithConstraints(Modifier.fillMaxSize().background(sessionBackground())) {
            // Normal phone text fits one viewport. Keep enlarged text reachable without disabling scaling.
            val compact = allowCompact && deleteError == null && LocalDensity.current.fontScale <= 1f &&
                maxWidth >= 350.dp && maxHeight >= 680.dp
            val screenHeight = maxHeight
            Column(Modifier.fillMaxSize().testTag("history-detail-scroll").verticalScroll(rememberScrollState())) {
                Column(Modifier.fillMaxWidth().then(if (compact) Modifier.height(screenHeight) else Modifier)
                    .then(snapshot?.let { Modifier.testTag("history-detail-${it.record.id}") } ?: Modifier)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    val accent = sessionBlue()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, enabled = canGoBack,
                            modifier = Modifier.size(28.dp).semantics { contentDescription = "Back" }) {
                            Canvas(Modifier.size(18.dp)) {
                                val tip = Offset(size.width * 0.15f, size.height * 0.5f)
                                drawLine(accent, Offset(size.width * 0.85f, size.height * 0.5f), tip, 2.dp.toPx(), StrokeCap.Round)
                                drawLine(accent, Offset(size.width * 0.45f, size.height * 0.2f), tip, 2.dp.toPx(), StrokeCap.Round)
                                drawLine(accent, Offset(size.width * 0.45f, size.height * 0.8f), tip, 2.dp.toPx(), StrokeCap.Round)
                            }
                        }
                        Text("Activity Summary", Modifier.weight(1f).padding(horizontal = 6.dp),
                            fontSize = 18.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold)
                        snapshot?.record?.let { record ->
                            val shortDate = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH).withZone(date.zone)
                            val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(date.zone)
                            Column(Modifier.widthIn(max = 110.dp).heightIn(min = 48.dp)
                                .padding(start = 4.dp), verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.End) {
                                Text(record.startedAt?.let { shortDate.format(Instant.ofEpochMilli(it)) } ?: "--",
                                    textAlign = TextAlign.End, fontSize = 12.sp, lineHeight = 16.sp)
                                Text(record.startedAt?.let { time.format(Instant.ofEpochMilli(it)) } ?: "--",
                                    textAlign = TextAlign.End, fontSize = 14.sp, lineHeight = 18.sp,
                                    fontWeight = FontWeight.Medium, color = accent)
                            }
                        }
                    }
                    sessionStatus()
                    when {
                        loading && snapshot == null -> Unit
                        error != null -> {
                            Text(error, color = MaterialTheme.colorScheme.error)
                            Button(onClick = onRetry, enabled = !loading) { Text("Retry query") }
                        }
                        snapshot == null -> Text("Session not found")
                        else -> {
                            SessionSummaryPanel(snapshot.record, compact)
                            HistoryCharts(snapshot, if (compact) Modifier.weight(3.1f)
                                else Modifier.height(400.dp * LocalDensity.current.fontScale), database)
                            // Reserve all five rows instead of squeezing them into a weighted remainder.
                            HistoryZones(snapshot.record, if (compact) Modifier.height(
                                if (snapshot.record.summary.receivedValidHr) 140.dp else 156.dp) else Modifier)
                            deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            OutlinedButton(onClick = onDelete, enabled = !loading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp), shape = RoundedCornerShape(5.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Text("Delete session", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryZones(record: SessionRecord, modifier: Modifier = Modifier) {
    val summary = record.summary
    SummaryCard(modifier, title = "HR Zones") {
        if (!summary.receivedValidHr) Text("No valid heart rate data", style = MaterialTheme.typography.bodySmall)
        HeartRateZoneRows(summary.zoneDurationsMs, record.durationMs, summary.receivedValidHr)
    }
}
