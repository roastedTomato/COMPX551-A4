package com.example.polarh10activityviewer.history

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.polarh10activityviewer.ble.SubscriptionStatus
import com.example.polarh10activityviewer.chart.ChartKind
import com.example.polarh10activityviewer.chart.ChartPoint
import com.example.polarh10activityviewer.chart.ChartSnapshot
import com.example.polarh10activityviewer.chart.LivePlot
import com.example.polarh10activityviewer.chart.chartScale
import com.example.polarh10activityviewer.session.SessionSnapshot
import com.example.polarh10activityviewer.session.SummaryCard
import com.example.polarh10activityviewer.session.sessionBlue
import com.example.polarh10activityviewer.storage.SessionDatabase
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

@Composable
internal fun HistoryCharts(snapshot: SessionSnapshot, modifier: Modifier = Modifier, database: SessionDatabase? = null) {
    val id = snapshot.record.id
    var kind by rememberSaveable(id) { mutableStateOf(ChartKind.HEART_RATE) }
    var windowStart by rememberSaveable(id, kind) { mutableLongStateOf(0L) }
    var loaded by remember(id, kind, windowStart) { mutableStateOf<List<ChartPoint>>(emptyList()) }
    var queryError by remember(id, kind, windowStart) { mutableStateOf<String?>(null) }
    var loading by remember(id, kind, windowStart) { mutableStateOf(kind == ChartKind.ELECTROCARDIOGRAM) }
    var retry by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, kind, windowStart, retry) {
        if (kind != ChartKind.ELECTROCARDIOGRAM) return@LaunchedEffect
        loading = true
        queryError = null
        try {
            val result = database?.ecgWindow(id, windowStart,
                minOf(windowStart + 5000, snapshot.record.durationMs)).orEmpty()
            ensureActive()
            loaded = result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            queryError = "Chart query failed. Please retry."
        } finally {
            if (isActive) loading = false
        }
    }
    val points = remember(snapshot, kind, loaded) {
        when (kind) {
            ChartKind.HEART_RATE -> snapshot.hrPoints.map {
                ChartPoint(it.elapsedMs.toDouble(), it.bpm?.toDouble(), it.breakBefore)
            }
            ChartKind.CADENCE -> snapshot.motionPoints.map {
                ChartPoint(it.elapsedMs.toDouble(), it.cadence, it.breakBefore)
            }
            ChartKind.ELECTROCARDIOGRAM -> loaded
        }
    }
    val mean = when (kind) {
        ChartKind.HEART_RATE -> snapshot.record.summary.meanHr
        ChartKind.CADENCE -> snapshot.record.summary.meanCadence
        ChartKind.ELECTROCARDIOGRAM -> null
    }
    val end = when (kind) {
        ChartKind.ELECTROCARDIOGRAM -> minOf(windowStart + 5000, snapshot.record.durationMs).toDouble()
        else -> snapshot.record.durationMs.toDouble()
    }
    val windowMs = if (kind == ChartKind.ELECTROCARDIOGRAM) {
        (end - windowStart).coerceAtLeast(0.0)
    } else end
    val chart = ChartSnapshot(points, end, windowMs, SubscriptionStatus.STOPPED)
    val scale = chartScale(points, kind, mean)
    val maximum = (snapshot.record.durationMs - 5000).coerceAtLeast(0)
    fun moveWindow(forward: Boolean): Boolean {
        val next = (windowStart + if (forward) 5000L else -5000L).coerceIn(0, maximum)
        if (next == windowStart) return false
        windowStart = next
        return true
    }
    val swipe = if (kind == ChartKind.ELECTROCARDIOGRAM) Modifier.pointerInput(id, kind, maximum) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragCancel = { distance = 0f },
            onDragEnd = { if (abs(distance) >= size.width * 0.1f) moveWindow(distance < 0) },
            onHorizontalDrag = { change, amount ->
                change.consume()
                distance += amount
            }
        )
    }.semantics {
        stateDescription = "${kind.historyLabel} window start: $windowStart"
        customActions = listOf(
            CustomAccessibilityAction("Previous window") { moveWindow(false) },
            CustomAccessibilityAction("Next window") { moveWindow(true) }
        )
    } else Modifier
    SummaryCard(modifier.testTag("history-chart-card")) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val effectiveWidth = maxWidth / LocalDensity.current.fontScale
            val columns = if (effectiveWidth >= 225.dp) 3 else if (effectiveWidth >= 150.dp) 2 else 1
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChartKind.entries.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { option ->
                            HistoryChartChoice(option.historyLabel, kind == option, Modifier.weight(1f)) {
                                kind = option
                            }
                        }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).semantics {
            contentDescription = if (kind != ChartKind.ELECTROCARDIOGRAM) {
                "${kind.label}, whole session. Dashed line: saved mean ${mean ?: "--"} ${kind.unit}. " +
                    "Gaps are not interpolated."
            } else {
                "${kind.historyLabel} recorded data. Gaps are not interpolated."
            }
        }) {
            val axisHeight = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() } * 2
            LivePlot(chart, kind, mean, statusLabel = null, scale = scale,
                height = (maxHeight - axisHeight).coerceAtLeast(36.dp), maximumTimeTicks = 5,
                plainLine = kind == ChartKind.ELECTROCARDIOGRAM, smoothLine = kind == ChartKind.CADENCE,
                axisWidth = 48.dp * LocalDensity.current.fontScale, modifier = swipe)
            if (queryError != null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(queryError!!, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retry++ }) { Text("Retry query") }
            } else if (!loading) {
                val message = if (snapshot.record.collectionIncomplete) "Data collection incomplete"
                    else if (points.none { it.value != null }) when (kind) {
                        ChartKind.HEART_RATE -> "No recorded heart rate data"
                        ChartKind.CADENCE -> "No recorded cadence data"
                        else -> "No ECG data in this interval"
                    } else null
                message?.let { Text(it, Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

}

@Composable
private fun HistoryChartChoice(label: String, selected: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = if (selected) sessionBlue() else sessionBlue().copy(alpha = 0.08f)
    Box(modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(50))
        .background(background).selectable(selected, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.surface else colors.onSurface)
    }
}

private val ChartKind.historyLabel: String
    get() = when (this) {
        ChartKind.HEART_RATE -> "HR"
        ChartKind.CADENCE -> "Cadence"
        ChartKind.ELECTROCARDIOGRAM -> "ECG"
    }
