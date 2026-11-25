package re.abbot.librecr.app.ui.stats

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.importLibreViewCsv
import re.abbot.librecr.app.stats.AgpBand
import re.abbot.librecr.app.stats.DailyPoint
import re.abbot.librecr.app.stats.GlucoseStatistics
import re.abbot.librecr.app.stats.InsightCode
import re.abbot.librecr.app.stats.InsightSeverity
import re.abbot.librecr.app.stats.PgsLabel
import re.abbot.librecr.app.stats.StatsInsight
import re.abbot.librecr.app.stats.StatsReport
import re.abbot.librecr.app.stats.VariabilityLabel
import re.abbot.librecr.app.stats.VariabilityScores
import re.abbot.librecr.app.stats.computeStatsReport
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.theme.GlucoseColors
import re.abbot.librecr.app.ui.theme.LocalGlucoseColors
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class Period(val shortLabelRes: Int) {
    TODAY(R.string.period_today_short),
    D7(R.string.period_7d_short),
    D14(R.string.period_14d_short),
    D30(R.string.period_30d_short),
    D90(R.string.period_90d_short),
}

private enum class PatternView { AGP, DAILY }

private data class ReportLoadState(
    val loading: Boolean,
    val report: StatsReport?,
)

private data class TirDisplaySegment(
    val label: String,
    val range: String,
    val percent: Float,
    val color: Color,
)

private data class MetricSpec(
    val label: String,
    val value: String,
    val suffix: String?,
    val color: Color,
)

@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    val colors = LocalGlucoseColors.current
    var period by remember { mutableStateOf(Period.D14) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            importMsg = null
            val result = runCatching { importLibreViewCsv(ctx, uri) }
            importing = false
            importMsg = result.fold(
                { ctx.getString(R.string.stats_import_done, it) },
                { ctx.getString(R.string.stats_import_error, it.message ?: it.toString()) },
            )
            refreshKey++
        }
    }

    val sinceMs = remember(period) { periodSince(period) }
    val loadState by produceState(
        initialValue = ReportLoadState(loading = true, report = null),
        period,
        refreshKey,
        settings.targetLow,
        settings.targetHigh,
    ) {
        value = ReportLoadState(loading = true, report = null)
        val report = withContext(Dispatchers.Default) {
            computeStatsReport(
                GlucoRitm.history.samples(sinceMs),
                settings.targetLow,
                settings.targetHigh,
            )
        }
        value = ReportLoadState(loading = false, report = report)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PeriodControlCard(
                selected = period,
                report = loadState.report,
                unit = settings.unit,
                targetLow = settings.targetLow,
                targetHigh = settings.targetHigh,
                onSelected = { period = it },
            )
        }

        when {
            loadState.loading -> item { LoadingCard() }
            loadState.report == null -> item { EmptyStatsCard() }
            else -> {
                val report = loadState.report
                item {
                    TimeInRangeOverview(
                        stats = report!!.stats,
                        colors = colors,
                        unit = settings.unit,
                        targetLow = settings.targetLow,
                        targetHigh = settings.targetHigh,
                    )
                }
                item {
                    MetricsSection(
                        report = report!!,
                        unit = settings.unit,
                        colors = colors,
                        targetLow = settings.targetLow,
                        targetHigh = settings.targetHigh,
                    )
                }
                item { DetailCard(report!!, settings.unit) }
                item {
                    PatternsCard(
                        agp = report!!.agp,
                        daily = report!!.daily,
                        colors = colors,
                        unit = settings.unit,
                        targetLow = settings.targetLow,
                        targetHigh = settings.targetHigh,
                    )
                }
                item { InsightsCard(report!!.insights, colors) }
            }
        }

        item {
            ImportCard(
                importing = importing,
                message = importMsg,
                onImport = { importer.launch(arrayOf("*/*")) },
            )
        }
    }
}

@Composable
private fun PeriodControlCard(
    selected: Period,
    report: StatsReport?,
    unit: GlucoseUnit,
    targetLow: Int,
    targetHigh: Int,
    onSelected: (Period) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 28.dp),
    ) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(3.dp),
            ) {
                Period.entries.forEach { period ->
                    val isSelected = period == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent,
                            )
                            .clickable { onSelected(period) }
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(period.shortLabelRes),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                        )
                    }
                }
            }

            report?.let {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.stats_readings_coverage,
                            it.stats.count,
                            spanText(it.stats),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.stats_target_compact,
                                unit.format(targetLow),
                                unit.format(targetHigh),
                                unit.label,
                            ),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.stats_loading), style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EmptyStatsCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.stats_empty_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.stats_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ---- Time in range ------------------------------------------------------------------------------

@Composable
private fun TimeInRangeOverview(
    stats: GlucoseStatistics,
    colors: GlucoseColors,
    unit: GlucoseUnit,
    targetLow: Int,
    targetHigh: Int,
) {
    val tir = stats.tir
    val segments = listOf(
        TirDisplaySegment(
            stringResource(R.string.tir_very_high),
            "> ${unit.format(250)}",
            tir.veryHighPct,
            colors.veryHigh,
        ),
        TirDisplaySegment(
            stringResource(R.string.tir_high),
            "${unit.format(targetHigh)}–${unit.format(250)}",
            tir.highPct,
            colors.high,
        ),
        TirDisplaySegment(
            stringResource(R.string.tir_in_range),
            "${unit.format(targetLow)}–${unit.format(targetHigh)}",
            tir.inRangePct,
            colors.inRange,
        ),
        TirDisplaySegment(
            stringResource(R.string.tir_low),
            "${unit.format(54)}–${unit.format(targetLow)}",
            tir.lowPct,
            colors.low,
        ),
        TirDisplaySegment(
            stringResource(R.string.tir_very_low),
            "< ${unit.format(54)}",
            tir.veryLowPct,
            colors.veryLow,
        ),
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 34.dp),
    ) {
        Column(
            Modifier.padding(start = 16.dp, top = 16.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.stats_tir_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    unit.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 340.dp
                if (compact) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        TirRing(tir.inRangePct, segments, Modifier.size(176.dp))
                        TirRows(segments)
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TirRing(tir.inRangePct, segments, Modifier.size(148.dp))
                        Box(Modifier.weight(1f)) { TirRows(segments) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TirRing(
    inRangePercent: Float,
    segments: List<TirDisplaySegment>,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val track = MaterialTheme.colorScheme.surfaceContainerHighest
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 15.dp.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset(center.x - diameter / 2f, center.y - diameter / 2f)
            val arcSize = Size(diameter, diameter)
            drawCircle(
                color = track,
                radius = diameter / 2f,
                center = center,
                style = Stroke(width = stroke),
            )

            var startAngle = -90f
            segments.asReversed().forEach { segment ->
                val sweep = 360f * (segment.percent.coerceAtLeast(0f) / 100f)
                if (sweep > 0.6f) {
                    val gap = 1.2f
                    drawArc(
                        color = segment.color,
                        startAngle = startAngle + gap / 2f,
                        sweepAngle = (sweep - gap).coerceAtLeast(0.2f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                }
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "%.0f%%".format(Locale.US, inRangePercent),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = when {
                    inRangePercent >= 70f -> LocalGlucoseColors.current.inRange
                    inRangePercent >= 55f -> LocalGlucoseColors.current.high
                    else -> LocalGlucoseColors.current.veryHigh
                },
            )
            Text(
                stringResource(R.string.stats_tir_short),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TirRows(segments: List<TirDisplaySegment>) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(25.dp)
                        .clip(CircleShape)
                        .background(segment.color),
                )
                Text(
                    segment.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    segment.range,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
                Text(
                    "%.1f%%".format(Locale.US, segment.percent),
                    modifier = Modifier.width(49.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = segment.color,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---- Metrics ------------------------------------------------------------------------------------

@Composable
private fun MetricsSection(
    report: StatsReport,
    unit: GlucoseUnit,
    colors: GlucoseColors,
    targetLow: Int,
    targetHigh: Int,
) {
    val stats = report.stats
    val variability = report.variability
    val metrics = listOf(
        MetricSpec(
            stringResource(R.string.stat_mean),
            unit.format(stats.meanMgDl),
            unit.label,
            glucoseTone(stats.meanMgDl.toFloat(), targetLow, targetHigh, colors),
        ),
        MetricSpec(
            stringResource(R.string.stats_gmi_short),
            "%.1f".format(Locale.US, stats.gmiPercent),
            "%",
            if (stats.gmiPercent <= 7.0) colors.inRange else colors.veryHigh,
        ),
        MetricSpec(
            stringResource(R.string.stat_median),
            unit.format(report.medianMgDl.toInt()),
            unit.label,
            glucoseTone(report.medianMgDl, targetLow, targetHigh, colors),
        ),
        MetricSpec(
            stringResource(R.string.stat_cv),
            "%.1f".format(Locale.US, stats.cvPercent),
            "%",
            variabilityTone(stats.cvPercent.toFloat(), colors),
        ),
        MetricSpec(
            stringResource(R.string.stat_gvi),
            "%.2f".format(Locale.US, variability.gvi),
            null,
            gviTone(variability.gviLabel, colors),
        ),
        MetricSpec(
            stringResource(R.string.stat_stability),
            "%.0f".format(Locale.US, variability.stabilityPct),
            "%",
            if (variability.stabilityPct >= 70f) colors.inRange else colors.high,
        ),
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.stats_summary),
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        metrics.chunked(2).forEach { rowMetrics ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowMetrics.forEach { metric ->
                    MetricTile(metric, Modifier.weight(1f).fillMaxHeight())
                }
                if (rowMetrics.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricTile(metric: MetricSpec, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 13.dp, bottomStart = 13.dp, bottomEnd = 20.dp),
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(metric.color.copy(alpha = 0.72f)),
            )
            Column(
                Modifier.padding(start = 12.dp, top = 12.dp, end = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    metric.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = metric.color,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        metric.value,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    metric.suffix?.let {
                        Text(
                            it,
                            modifier = Modifier.padding(bottom = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailCard(report: StatsReport, unit: GlucoseUnit) {
    val stats = report.stats
    val rows = listOf(
        stringResource(R.string.stat_iqr) to
            "${unit.format(report.p25MgDl.toInt())} – ${unit.format(report.p75MgDl.toInt())} ${unit.label}",
        stringResource(R.string.stat_sd) to "${unit.format(stats.sdMgDl)} ${unit.label}",
        stringResource(R.string.stat_a1c) to "%.1f%%".format(Locale.US, stats.estimatedA1cPercent),
        stringResource(R.string.stat_pgs) to stringResource(pgsLabelRes(report.variability.pgsLabel)),
        stringResource(R.string.stat_minmax) to
            "${unit.format(stats.lowestMgDl)} / ${unit.format(stats.highestMgDl)} ${unit.label}",
        stringResource(R.string.stat_count) to stats.count.toString(),
        stringResource(R.string.stat_span) to spanText(stats),
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                stringResource(R.string.stats_details_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 7.dp),
            )
            rows.forEachIndexed { index, (label, value) ->
                DetailRow(label, value)
                if (index != rows.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

private fun glucoseTone(value: Float, targetLow: Int, targetHigh: Int, colors: GlucoseColors): Color = when {
    value < targetLow -> colors.low
    value > targetHigh -> colors.high
    else -> colors.inRange
}

private fun variabilityTone(cv: Float, colors: GlucoseColors): Color = when {
    cv <= 36f -> colors.inRange
    cv <= 45f -> colors.high
    else -> colors.veryHigh
}

private fun gviTone(label: VariabilityLabel, colors: GlucoseColors): Color = when (label) {
    VariabilityLabel.EXCELLENT, VariabilityLabel.GOOD -> colors.inRange
    VariabilityLabel.MODERATE -> colors.high
    VariabilityLabel.POOR -> colors.veryHigh
}

// ---- Patterns -----------------------------------------------------------------------------------

@Composable
private fun PatternsCard(
    agp: List<AgpBand>,
    daily: List<DailyPoint>,
    colors: GlucoseColors,
    unit: GlucoseUnit,
    targetLow: Int,
    targetHigh: Int,
) {
    val hasDaily = daily.size >= 2
    var selected by remember(hasDaily) { mutableStateOf(PatternView.AGP) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 30.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        stringResource(R.string.stats_patterns_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (selected == PatternView.AGP) {
                            stringResource(R.string.stats_agp_title)
                        } else {
                            stringResource(R.string.stats_daily_title)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (hasDaily) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(3.dp),
                ) {
                    PatternTab(
                        label = stringResource(R.string.stats_agp_tab),
                        selected = selected == PatternView.AGP,
                        modifier = Modifier.weight(1f),
                    ) { selected = PatternView.AGP }
                    PatternTab(
                        label = stringResource(R.string.stats_daily_tab),
                        selected = selected == PatternView.DAILY,
                        modifier = Modifier.weight(1f),
                    ) { selected = PatternView.DAILY }
                }
            }

            when (selected) {
                PatternView.AGP -> AgpChartContent(agp, colors, unit, targetLow, targetHigh)
                PatternView.DAILY -> DailyTrendContent(daily, colors)
            }
        }
    }
}

@Composable
private fun PatternTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun AgpChartContent(
    agp: List<AgpBand>,
    colors: GlucoseColors,
    unit: GlucoseUnit,
    targetLow: Int,
    targetHigh: Int,
) {
    val primary = MaterialTheme.colorScheme.primary
    val present = agp.filter { it.median != null }
    Text(
        stringResource(
            R.string.stats_agp_desc,
            unit.format(targetLow),
            unit.format(targetHigh),
            unit.label,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    if (present.size < 2) {
        Text(stringResource(R.string.stats_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val yMin = 40f
    val yMax = ((agp.mapNotNull { it.p90 }.maxOrNull() ?: 300f) + 10f).coerceIn(200f, 410f)
    val bandWide = primary.copy(alpha = 0.14f)
    val bandNarrow = primary.copy(alpha = 0.31f)
    val targetFill = colors.inRange.copy(alpha = 0.11f)
    val targetLine = colors.inRange.copy(alpha = 0.62f)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(top = 4.dp),
    ) {
        val w = size.width
        val h = size.height
        fun px(hour: Int) = hour / 23f * w
        fun py(v: Float) = h - ((v - yMin) / (yMax - yMin)).coerceIn(0f, 1f) * h

        listOf(0.25f, 0.5f, 0.75f).forEach { ratio ->
            drawLine(grid, Offset(0f, h * ratio), Offset(w, h * ratio), strokeWidth = 1.dp.toPx())
        }

        val yHigh = py(targetHigh.toFloat())
        val yLow = py(targetLow.toFloat())
        drawRect(targetFill, topLeft = Offset(0f, yHigh), size = Size(w, yLow - yHigh))
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        drawLine(targetLine, Offset(0f, yLow), Offset(w, yLow), 1.5.dp.toPx(), pathEffect = dash)
        drawLine(targetLine, Offset(0f, yHigh), Offset(w, yHigh), 1.5.dp.toPx(), pathEffect = dash)

        fun band(lower: (AgpBand) -> Float?, upper: (AgpBand) -> Float?, color: Color) {
            val path = Path()
            present.forEachIndexed { index, point ->
                val p = Offset(px(point.hour), py(upper(point)!!))
                if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            present.indices.reversed().forEach { index ->
                val point = present[index]
                path.lineTo(px(point.hour), py(lower(point)!!))
            }
            path.close()
            drawPath(path, color)
        }

        band({ it.p10 }, { it.p90 }, bandWide)
        band({ it.p25 }, { it.p75 }, bandNarrow)

        val median = Path()
        present.forEachIndexed { index, point ->
            val p = Offset(px(point.hour), py(point.median!!))
            if (index == 0) median.moveTo(p.x, p.y) else median.lineTo(p.x, p.y)
        }
        drawPath(median, primary, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("0", "6", "12", "18", "24").forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LegendDot(primary, stringResource(R.string.stats_agp_legend_median))
        LegendDot(bandNarrow, stringResource(R.string.stats_agp_legend_iqr))
        LegendDot(bandWide, stringResource(R.string.stats_agp_legend_p10_90))
    }
}

@Composable
private fun DailyTrendContent(daily: List<DailyPoint>, colors: GlucoseColors) {
    val shown = daily.takeLast(30)
    val avgInRange = shown.map { it.inRangePct }.average()
    Text(
        stringResource(R.string.stats_daily_desc, avgInRange.toInt()),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    val targetLine = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .padding(top = 8.dp),
    ) {
        val n = shown.size
        if (n == 0) return@Canvas
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val y70 = size.height * 0.30f
        drawLine(
            color = targetLine,
            start = Offset(0f, y70),
            end = Offset(size.width, y70),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f)),
        )
        shown.forEachIndexed { index, point ->
            val barHeight = (point.inRangePct / 100f) * size.height
            val left = index * (barWidth + gap)
            val color = when {
                point.inRangePct >= 70f -> colors.inRange
                point.inRangePct >= 50f -> colors.high
                else -> colors.veryHigh
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth.coerceAtMost(5.dp.toPx())),
            )
        }
    }
    if (shown.isNotEmpty()) {
        val formatter = DateFormat.getDateInstance(DateFormat.SHORT)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatter.format(Date(shown.first().startOfDayMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatter.format(Date(shown.last().startOfDayMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Insights -----------------------------------------------------------------------------------

@Composable
private fun InsightsCard(insights: List<StatsInsight>, colors: GlucoseColors) {
    if (insights.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                stringResource(R.string.stats_insights_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            insights.forEach { insight ->
                val color = severityColor(insight.severity, colors)
                Surface(
                    color = color.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            Modifier
                                .padding(top = 4.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                insightTitle(insight.code),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                insightMessage(insight),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun severityColor(severity: InsightSeverity, colors: GlucoseColors): Color = when (severity) {
    InsightSeverity.POSITIVE -> colors.inRange
    InsightSeverity.ATTENTION -> colors.high
    InsightSeverity.CAUTION -> colors.veryHigh
}

@Composable
private fun insightTitle(code: InsightCode): String = stringResource(
    when (code) {
        InsightCode.EXCELLENT_CONTROL -> R.string.insight_excellent_control
        InsightCode.GOOD_PROGRESS -> R.string.insight_good_progress
        InsightCode.ROOM_IMPROVEMENT -> R.string.insight_room_improvement
        InsightCode.STABLE_GLUCOSE -> R.string.insight_stable_glucose
        InsightCode.VARIABILITY_RISING -> R.string.insight_variability_rising
        InsightCode.HIGH_VARIABILITY -> R.string.insight_high_variability
        InsightCode.HYPO_EXPOSURE -> R.string.insight_hypo_exposure
        InsightCode.PROLONGED_HYPER -> R.string.insight_prolonged_hyper
        InsightCode.OVERNIGHT_DRIFT -> R.string.insight_overnight_drift
        InsightCode.UNSTABLE_DAYS -> R.string.insight_unstable_days
        InsightCode.A1C_ESTIMATE -> R.string.insight_a1c_estimate
    },
)

@Composable
private fun insightMessage(insight: StatsInsight): String = when (insight.code) {
    InsightCode.EXCELLENT_CONTROL -> stringResource(R.string.insight_excellent_control_desc, insight.intArg ?: 0)
    InsightCode.GOOD_PROGRESS -> stringResource(R.string.insight_good_progress_desc, insight.intArg ?: 0)
    InsightCode.ROOM_IMPROVEMENT -> stringResource(R.string.insight_room_improvement_desc, insight.intArg ?: 0)
    InsightCode.STABLE_GLUCOSE -> stringResource(R.string.insight_stable_glucose_desc, insight.intArg ?: 0)
    InsightCode.VARIABILITY_RISING -> stringResource(R.string.insight_variability_rising_desc, insight.floatArg ?: 0f)
    InsightCode.HIGH_VARIABILITY -> stringResource(R.string.insight_high_variability_desc, insight.intArg ?: 0)
    InsightCode.HYPO_EXPOSURE -> stringResource(R.string.insight_hypo_exposure_desc, insight.floatArg ?: 0f)
    InsightCode.PROLONGED_HYPER -> stringResource(R.string.insight_prolonged_hyper_desc, insight.floatArg ?: 0f)
    InsightCode.OVERNIGHT_DRIFT -> stringResource(R.string.insight_overnight_drift_desc)
    InsightCode.UNSTABLE_DAYS -> stringResource(R.string.insight_unstable_days_desc, insight.intArg ?: 0)
    InsightCode.A1C_ESTIMATE -> stringResource(R.string.insight_a1c_estimate_desc, insight.floatArg ?: 0f)
}

// ---- Import + shared ----------------------------------------------------------------------------

@Composable
private fun ImportCard(
    importing: Boolean,
    message: String?,
    onImport: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            OutlinedButton(
                onClick = onImport,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.stats_import))
            }
            if (importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.stats_importing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            message?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun pgsLabelRes(label: PgsLabel): Int = when (label) {
    PgsLabel.LOW -> R.string.pgs_low
    PgsLabel.ELEVATED -> R.string.pgs_elevated
    PgsLabel.UNSTABLE -> R.string.pgs_unstable
    PgsLabel.STABLE -> R.string.pgs_stable
}

private fun periodSince(period: Period): Long {
    val now = System.currentTimeMillis()
    return when (period) {
        Period.TODAY -> Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        Period.D7 -> now - 7L * 86_400_000L
        Period.D14 -> now - 14L * 86_400_000L
        Period.D30 -> now - 30L * 86_400_000L
        Period.D90 -> now - 90L * 86_400_000L
    }
}

@Composable
private fun spanText(stats: GlucoseStatistics): String {
    val hours = (stats.lastAtMs - stats.firstAtMs) / 3_600_000L
    return if (hours >= 48) stringResource(R.string.span_days, (hours / 24).toInt())
    else stringResource(R.string.span_hours, hours.toInt())
}
