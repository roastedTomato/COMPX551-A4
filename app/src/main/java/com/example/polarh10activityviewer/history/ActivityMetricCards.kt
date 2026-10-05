package com.example.polarh10activityviewer.history

import com.example.polarh10activityviewer.session.ActivityMetricsCalculator
import com.example.polarh10activityviewer.session.SessionRecord
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun ColumnScope.ActivityMetricCards(record: SessionRecord, compact: Boolean) {
    val metrics = record.summary.activityMetrics
    fun number(value: Double?) = value?.let { String.format(Locale.ENGLISH, "%.1f", it) }
    val titles = listOf("Intensity", "Cardio Load", "Cadence Stability")
    val values = listOf(ActivityMetricsCalculator.intensityRating(metrics.intensity)?.toString() ?: "--",
        ActivityMetricsCalculator.cardioLoadRating(metrics.cardioLoad)?.toString() ?: "--",
        number(metrics.cadenceCvPercent)?.let { "$it%" } ?: "--")
    val cardHeight = with(LocalDensity.current) { 32.sp.toDp() + 30.sp.toDp() } + 10.dp
    if (compact) Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        titles.indices.forEach { index ->
            ActivityMetricCard(titles[index], values[index], Modifier.weight(1f).fillMaxHeight(),
                maximum = if (index < 2) 10 else null)
        }
    } else titles.indices.forEach { index ->
        ActivityMetricCard(titles[index], values[index], Modifier.heightIn(min = cardHeight),
            maximum = if (index < 2) 10 else null)
    }
    ActivityMetricCard("Session Strain", number(metrics.sessionStrainScore) ?: "--",
        if (compact) Modifier.height(48.dp) else Modifier.heightIn(min = cardHeight), maximum = 100)
}

@Composable
private fun ActivityMetricCard(title: String, value: String, modifier: Modifier, maximum: Int? = null) {
    val formatted = buildAnnotatedString {
        append(value)
        if (maximum != null && value != "--") {
            append(" ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) {
                append("/ $maximum")
            }
        }
    }
    SummaryCard(modifier) {
        Text(title, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Text(formatted, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    }
}
