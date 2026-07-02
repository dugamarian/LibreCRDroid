package re.abbot.librecr.app.ui.chart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.app.ui.theme.LocalGlucoseColors
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

private const val HOUR_MS = 3_600_000L
private const val MIN_WINDOW_MS = HOUR_MS
private const val MAX_WINDOW_MS = 72L * HOUR_MS
private const val GAP_BREAK_MS = 45L * 60_000L
private const val LIVE_EDGE_TOLERANCE_MS = 2L * 60_000L
private const val DEFAULT_GRAPH_LOW_MGDL = 40f
private const val DEFAULT_GRAPH_HIGH_MGDL = 216f
private const val GRAPH_EDGE_PADDING_MGDL = 8f
private val WINDOWS = listOf(1, 3, 6, 12, 24, 72)

@Composable
fun GlucoseChartCard(
    samples: List<GlucoseSample>,
    unit: GlucoseUnit,
    targetLow: Int,
    targetHigh: Int,
    modifier: Modifier = Modifier,
) {
    val orderedSamples = remember(samples) {
        samples
            .asSequence()
            .filter { it.atMs > 0L && it.mgDl in 1..500 }
            .sortedBy { it.atMs }
            .distinctBy { it.atMs }
            .toList()
    }
    var windowMs by rememberSaveable { mutableLongStateOf(6L * HOUR_MS) }
    var viewportEndMs by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }
    val latestMs = orderedSamples.lastOrNull()?.atMs ?: 0L
    val displayedViewportEndMs = if (viewportEndMs == Long.MIN_VALUE) latestMs else viewportEndMs

    LaunchedEffect(latestMs) {
        if (
            latestMs > 0L &&
            (viewportEndMs == Long.MIN_VALUE || latestMs - viewportEndMs <= LIVE_EDGE_TOLERANCE_MS)
        ) {
            viewportEndMs = latestMs
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(292.dp)) {
                if (orderedSamples.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.chart_no_data),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    GlucoseChartCanvas(
                        samples = orderedSamples,
                        unit = unit,
                        targetLow = targetLow,
                        targetHigh = targetHigh,
                        windowMs = windowMs,
                        viewportEndMs = displayedViewportEndMs,
                        onViewportChange = { nextWindow, nextEnd ->
                            windowMs = nextWindow
                            viewportEndMs = nextEnd
                        },
                    )

                    if (latestMs - displayedViewportEndMs > LIVE_EDGE_TOLERANCE_MS) {
                        Surface(
                            onClick = { viewportEndMs = latestMs },
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                            ),
                        ) {
                            Text(
                                stringResource(R.string.age_now),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            ChartWindowPicker(
                windowMs = windowMs,
                onWindowSelected = { hours ->
                    windowMs = hours * HOUR_MS
                    viewportEndMs = latestMs
                },
            )
        }
    }
}

@Composable
private fun GlucoseChartCanvas(
    samples: List<GlucoseSample>,
    unit: GlucoseUnit,
    targetLow: Int,
    targetHigh: Int,
    windowMs: Long,
    viewportEndMs: Long,
    onViewportChange: (windowMs: Long, viewportEndMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glucoseColors = LocalGlucoseColors.current
    val targetColor = glucoseColors.inRange
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val firstMs = samples.first().atMs
    val latestMs = samples.last().atMs
    val clampedWindowMs = windowMs.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
    val minimumEndMs = min(latestMs, firstMs + clampedWindowMs)
    val clampedEndMs = viewportEndMs.coerceIn(minimumEndMs, latestMs)
    val startMs = clampedEndMs - clampedWindowMs
    val visibleSamples = remember(samples, startMs, clampedEndMs) {
        samples.filter { it.atMs in startMs..clampedEndMs }
    }
    // The drawn line extends GAP_BREAK_MS past each edge for continuity; the Y bounds must cover that
    // same set, otherwise a low point in the margin is plotted below the axis and clipped off-canvas.
    val drawnSamples = remember(samples, startMs, clampedEndMs) {
        samples.filter { it.atMs in (startMs - GAP_BREAK_MS)..(clampedEndMs + GAP_BREAK_MS) }
    }
    val boundsSamples = drawnSamples.ifEmpty { visibleSamples.ifEmpty { samples.takeLast(1) } }
    val (yMin, yMax) = yBounds(boundsSamples, targetLow, targetHigh)
    val currentWindowMs = rememberUpdatedState(clampedWindowMs)
    val currentViewportEndMs = rememberUpdatedState(clampedEndMs)
    val currentViewportChange = rememberUpdatedState(onViewportChange)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(samples, widthPx) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val activeWindowMs = currentWindowMs.value
                        val activeEndMs = currentViewportEndMs.value
                        val activeStartMs = activeEndMs - activeWindowMs
                        val oldWindow = activeWindowMs.toDouble()
                        val nextWindow = (oldWindow / zoom)
                            .roundToLong()
                            .coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
                        val anchorFraction = (centroid.x / widthPx).coerceIn(0f, 1f)
                        val anchorTime = activeStartMs +
                            (oldWindow * anchorFraction).roundToLong()
                        val nextStart = anchorTime -
                            (nextWindow * anchorFraction).roundToLong() -
                            (pan.x / widthPx * nextWindow).roundToLong()
                        val requestedEnd = nextStart + nextWindow
                        val earliestEnd = min(latestMs, firstMs + nextWindow)
                        currentViewportChange.value(
                            nextWindow,
                            requestedEnd.coerceIn(earliestEnd, latestMs),
                        )
                    }
                },
        ) {
            val plotTop = 8.dp.toPx()
            val plotBottom = size.height - 25.dp.toPx()
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
            val plotWidth = size.width
            val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
            val guideStyle = TextStyle(
                fontSize = 10.sp,
                color = labelColor,
                fontWeight = FontWeight.SemiBold,
            )

            fun x(atMs: Long): Float =
                ((atMs - startMs).toDouble() / clampedWindowMs.toDouble() * plotWidth)
                    .toFloat()

            fun y(mgDl: Float): Float =
                plotBottom - ((mgDl - yMin) / (yMax - yMin) * plotHeight)

            val targetTop = y(targetHigh.toFloat()).coerceIn(plotTop, plotBottom)
            val targetBottom = y(targetLow.toFloat()).coerceIn(plotTop, plotBottom)
            if (targetBottom > targetTop) {
                drawRect(
                    color = targetColor.copy(alpha = 0.14f),
                    topLeft = Offset(0f, targetTop),
                    size = Size(plotWidth, targetBottom - targetTop),
                )
            }

            yTicks(yMin, yMax, unit).forEach { tick ->
                val tickY = y(tick.toFloat())
                drawLine(
                    color = gridColor,
                    start = Offset(0f, tickY),
                    end = Offset(plotWidth, tickY),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLabel(
                    text = unit.format(tick),
                    x = 5.dp.toPx(),
                    centerY = tickY,
                    style = labelStyle,
                    background = labelBackground,
                    measurer = measurer,
                )
            }

            val timeStepMs = timeGridStep(clampedWindowMs)
            var gridTime = floorDiv(startMs, timeStepMs) * timeStepMs
            if (gridTime < startMs) gridTime += timeStepMs
            while (gridTime <= clampedEndMs) {
                val gridX = x(gridTime)
                val text = timeLabel(gridTime, clampedWindowMs)
                val layout = measurer.measure(text, labelStyle)
                val labelX = (gridX - layout.size.width / 2f)
                    .coerceIn(2.dp.toPx(), plotWidth - layout.size.width - 2.dp.toPx())
                drawText(
                    layout,
                    topLeft = Offset(labelX, plotBottom + 4.dp.toPx()),
                )
                gridTime += timeStepMs
            }

            val visibleExtremes = visibleSamples
                .map { it.mgDl }
                .let { values ->
                    if (values.isEmpty()) emptyList()
                    else listOf(values.min(), values.max()).distinct()
                }
            val guideDash = PathEffect.dashPathEffect(
                floatArrayOf(5.dp.toPx(), 5.dp.toPx()),
            )
            visibleExtremes.forEach { value ->
                val guideY = y(value.toFloat())
                val guideLayout = measurer.measure(unit.format(value), guideStyle)
                val guideLeft = 5.dp.toPx()
                val guideWidth = guideLayout.size.width + 8.dp.toPx()
                drawLine(
                    color = labelColor.copy(alpha = 0.48f),
                    start = Offset(guideLeft + guideWidth, guideY),
                    end = Offset(plotWidth, guideY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = guideDash,
                )
                drawRoundRect(
                    color = labelBackground,
                    topLeft = Offset(
                        guideLeft,
                        guideY - guideLayout.size.height / 2f - 2.dp.toPx(),
                    ),
                    size = Size(
                        guideWidth,
                        guideLayout.size.height + 4.dp.toPx(),
                    ),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                drawText(
                    guideLayout,
                    topLeft = Offset(
                        guideLeft + 4.dp.toPx(),
                        guideY - guideLayout.size.height / 2f,
                    ),
                )
            }

            val pointsToDraw = drawnSamples
            var previous: GlucoseSample? = null
            val lineStrokeWidth = 3.dp.toPx()

            pointsToDraw.forEach { sample ->
                previous?.let { prev ->
                    val gap = sample.atMs - prev.atMs
                    if (gap <= GAP_BREAK_MS) {
                        drawGlucoseLineSegment(
                            start = Offset(x(prev.atMs), y(prev.mgDl.toFloat())),
                            end = Offset(x(sample.atMs), y(sample.mgDl.toFloat())),
                            startMgDl = prev.mgDl.toFloat(),
                            endMgDl = sample.mgDl.toFloat(),
                            targetLow = targetLow,
                            targetHigh = targetHigh,
                            strokeWidth = lineStrokeWidth,
                            colorForMgDl = { mgDl ->
                                when {
                                    mgDl < 54f -> glucoseColors.veryLow
                                    mgDl < targetLow.toFloat() -> glucoseColors.low
                                    mgDl <= targetHigh.toFloat() -> glucoseColors.inRange
                                    mgDl <= 250f -> glucoseColors.high
                                    else -> glucoseColors.veryHigh
                                }
                            },
                        )
                    }
                }
                previous = sample
            }

            visibleSamples.lastOrNull()?.let { latestVisible ->
                val center = Offset(x(latestVisible.atMs), y(latestVisible.mgDl.toFloat()))
                drawCircle(
                    color = labelBackground,
                    radius = 5.dp.toPx(),
                    center = center,
                )
                drawCircle(
                    color = glucoseColors.forMgDl(
                        latestVisible.mgDl,
                        targetLow,
                        targetHigh,
                    ),
                    radius = 3.4.dp.toPx(),
                    center = center,
                )
            }

            drawLine(
                color = outlineColor,
                start = Offset(0f, plotBottom),
                end = Offset(plotWidth, plotBottom),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

@Composable
private fun ChartWindowPicker(
    windowMs: Long,
    onWindowSelected: (hours: Long) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WINDOWS.forEach { hours ->
            val selected = windowMs == hours * HOUR_MS
            Surface(
                onClick = { onWindowSelected(hours.toLong()) },
                modifier = Modifier.weight(1f).height(38.dp),
                shape = RoundedCornerShape(8.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                },
                border = BorderStroke(
                    1.dp,
                    if (selected) {
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (hours == 72) "3D" else "${hours}H",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private fun yBounds(
    samples: List<GlucoseSample>,
    targetLow: Int,
    targetHigh: Int,
): Pair<Float, Float> {
    val sampleMin = samples.minOf { it.mgDl }
    val sampleMax = samples.maxOf { it.mgDl }
    var low = min(DEFAULT_GRAPH_LOW_MGDL, targetLow.toFloat())
    var high = max(DEFAULT_GRAPH_HIGH_MGDL, targetHigh.toFloat())

    if (sampleMin < low + GRAPH_EDGE_PADDING_MGDL) {
        low = floor((sampleMin - GRAPH_EDGE_PADDING_MGDL) / 10f) * 10f
    }
    if (sampleMax > high - GRAPH_EDGE_PADDING_MGDL) {
        high = ceil((sampleMax + GRAPH_EDGE_PADDING_MGDL) / 10f) * 10f
    }

    low = low.coerceAtLeast(0f)
    high = high.coerceAtMost(500f)
    if (high - low < 90f) {
        val extra = (90f - (high - low)) / 2f
        low = (floor((low - extra) / 10f) * 10f).coerceAtLeast(0f)
        high = (ceil((high + extra) / 10f) * 10f).coerceAtMost(500f)
    }
    return low to max(high, low + 90f)
}

private fun yTicks(yMin: Float, yMax: Float, unit: GlucoseUnit): List<Int> {
    val step = if (unit == GlucoseUnit.MMOL_L) 36 else 50
    var value = ceil(yMin / step).toInt() * step
    return buildList {
        while (value <= yMax) {
            add(value)
            value += step
        }
    }
}

private fun timeGridStep(windowMs: Long): Long = when {
    windowMs <= 3L * HOUR_MS -> 30L * 60_000L
    windowMs <= 6L * HOUR_MS -> HOUR_MS
    windowMs <= 12L * HOUR_MS -> 2L * HOUR_MS
    windowMs <= 24L * HOUR_MS -> 4L * HOUR_MS
    else -> 12L * HOUR_MS
}

private fun timeLabel(atMs: Long, windowMs: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = atMs }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    return if (windowMs > 24L * HOUR_MS) {
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        "%02d/%02d".format(day, hour)
    } else {
        "%02d:%02d".format(hour, minute)
    }
}

private fun floorDiv(value: Long, divisor: Long): Long =
    Math.floorDiv(value, divisor)

private fun DrawScope.drawGlucoseLineSegment(
    start: Offset,
    end: Offset,
    startMgDl: Float,
    endMgDl: Float,
    targetLow: Int,
    targetHigh: Int,
    strokeWidth: Float,
    colorForMgDl: (Float) -> Color,
) {
    var fromFraction = 0f
    var from = start
    val breakpoints = glucoseLineBreakpoints(startMgDl, endMgDl, targetLow, targetHigh)

    (breakpoints + 1f).forEach { toFraction ->
        val to = interpolate(start, end, toFraction)
        val midpointMgDl = interpolate(startMgDl, endMgDl, (fromFraction + toFraction) / 2f)
        drawLine(
            color = colorForMgDl(midpointMgDl),
            start = from,
            end = to,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        from = to
        fromFraction = toFraction
    }
}

private fun glucoseLineBreakpoints(
    startMgDl: Float,
    endMgDl: Float,
    targetLow: Int,
    targetHigh: Int,
): List<Float> {
    val delta = endMgDl - startMgDl
    if (delta == 0f) return emptyList()

    val low = min(startMgDl, endMgDl)
    val high = max(startMgDl, endMgDl)
    return listOf(54f, targetLow.toFloat(), targetHigh.toFloat(), 250f)
        .distinct()
        .filter { it > low && it < high }
        .map { (it - startMgDl) / delta }
        .filter { it > 0f && it < 1f }
        .sorted()
}

private fun interpolate(start: Offset, end: Offset, fraction: Float): Offset =
    Offset(
        x = interpolate(start.x, end.x, fraction),
        y = interpolate(start.y, end.y, fraction),
    )

private fun interpolate(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    text: String,
    x: Float,
    centerY: Float,
    style: TextStyle,
    background: androidx.compose.ui.graphics.Color,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val layout = measurer.measure(text, style)
    val horizontalPadding = 4.dp.toPx()
    val verticalPadding = 2.dp.toPx()
    drawRoundRect(
        color = background,
        topLeft = Offset(
            x,
            centerY - layout.size.height / 2f - verticalPadding,
        ),
        size = Size(
            layout.size.width + horizontalPadding * 2f,
            layout.size.height + verticalPadding * 2f,
        ),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    drawText(
        layout,
        topLeft = Offset(
            x + horizontalPadding,
            centerY - layout.size.height / 2f,
        ),
    )
}
