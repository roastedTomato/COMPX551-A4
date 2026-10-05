package com.example.polarh10activityviewer.history

import com.example.polarh10activityviewer.session.sessionBlue
import com.example.polarh10activityviewer.session.sessionBorder
import com.example.polarh10activityviewer.session.SessionRecord
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.polarh10activityviewer.heartrate.formatZoneDuration
import java.util.Locale

@Composable
internal fun ColumnScope.SessionSummaryPanel(record: SessionRecord, compact: Boolean) {
    val summary = record.summary
    fun section(weight: Float) = if (compact) Modifier.weight(weight) else Modifier
    SummaryCard(section(1.15f)) {
        SummaryMetrics(listOf(
            SummaryValue("Duration", formatZoneDuration(record.durationMs)),
            SummaryValue("Total Steps", summary.totalSteps?.toString() ?: "--")
        ), enlarged = true)
    }
    // Both sensor cards reserve the same title, label and value space.
    val sensorHeight = with(LocalDensity.current) { 18.sp.toDp() + 13.sp.toDp() + 26.sp.toDp() } + 20.dp
    val sensorModifier = if (compact) Modifier.height(sensorHeight) else Modifier
    SummaryCard(sensorModifier, "Heart Rate") {
        SummaryMetrics(listOf(
            SummaryValue("Mean HR", summaryNumber(summary.meanHr), "bpm"),
            SummaryValue("Range", "${summary.minimumHr ?: "--"}–${summary.maximumHr ?: "--"}", "bpm")
        ))
    }
    SummaryCard(sensorModifier, "Cadence") {
        SummaryMetrics(listOf(
            SummaryValue("Mean", summaryNumber(summary.meanCadence), "steps/min"),
            SummaryValue("Max", summaryNumber(summary.maximumCadence), "steps/min")
        ))
    }
    ActivityMetricCards(record, compact)
}

private data class SummaryValue(val label: String, val value: String, val unit: String? = null)

@Composable
private fun SummaryMetrics(values: List<SummaryValue>, enlarged: Boolean = false) {
    if (!enlarged && LocalDensity.current.fontScale > 1.3f) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEachIndexed { index, value ->
                if (index > 0) HorizontalDivider(color = sessionBorder())
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value.label, fontSize = 10.sp,
                        lineHeight = 13.sp, letterSpacing = 0.sp)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(value.value, fontSize = 21.sp,
                            lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                        value.unit?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEachIndexed { index, value ->
            if (index > 0) VerticalDivider(color = sessionBlue().copy(alpha = 0.45f))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value.label, textAlign = TextAlign.Center, fontSize = if (enlarged) 11.sp else 10.sp,
                    lineHeight = if (enlarged) 14.sp else 13.sp, letterSpacing = 0.sp)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(value.value, Modifier.weight(1f, fill = false), maxLines = 1,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = if (enlarged) 26.sp else 21.sp,
                            lineHeight = if (enlarged) 30.sp else 26.sp,
                            fontWeight = FontWeight.SemiBold),
                        autoSize = if (enlarged) TextAutoSize.StepBased(minFontSize = 18.sp,
                            maxFontSize = 26.sp) else null)
                    value.unit?.let {
                        Text(it, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal,
                            maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SummaryCard(modifier: Modifier = Modifier, title: String? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(5.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, sessionBorder())) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 3.dp),
            verticalArrangement = if (LocalDensity.current.fontScale > 1.3f) Arrangement.spacedBy(6.dp) else Arrangement.SpaceEvenly) {
            title?.let { Text(it, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            content()
        }
    }
}

private fun summaryNumber(value: Double?) =
    value?.let { String.format(Locale.ENGLISH, "%.0f", it) } ?: "--"
