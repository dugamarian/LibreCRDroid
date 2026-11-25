package re.abbot.librecr.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.AppSettings
import re.abbot.librecr.protocol.TrendArrowShape

/** The current app settings, collected once in the nav shell and shared with every screen. */
val LocalAppSettings = compositionLocalOf { AppSettings() }

/** A titled elevated card — the standard surface for a section on any screen. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun MessageBox(text: String, error: Boolean) {
    val color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = color, contentColor = content, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun InfoRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(name, modifier = Modifier.weight(0.34f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.66f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SettingsSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            label,
            color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

/**
 * The trend arrow in the watch-face "AOD style" — shared [TrendArrowShape] geometry drawn as a
 * Compose vector, so every surface (phone screens, overlays, Wear) shows an identical arrow.
 * Draws nothing for an unknown trend (the box still reserves [size] to keep layout stable).
 */
@Composable
fun TrendArrow(
    trend: String?,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val rotation = TrendArrowShape.rotationDegrees(trend) ?: return@Canvas
        drawTrendArrow(rotation, color)
    }
}

/**
 * Draws the shared [TrendArrowShape] glyph into this (square) draw area at an arbitrary rotation,
 * so a surface can animate between trends while keeping the exact same arrow. [strokeWidth] (px,
 * as drawn) lets a surface match the weight of the numerals beside it; the geometry is unchanged.
 */
fun DrawScope.drawTrendArrow(rotationDegrees: Float, color: Color, strokeWidth: Float? = null) {
    val side = size.minDimension
    // The stroke is drawn inside the SCALE transform, so undo it for an explicit width.
    val stroke = strokeWidth?.div(TrendArrowShape.SCALE) ?: (side * TrendArrowShape.STROKE_FRACTION)
    withTransform({
        scale(TrendArrowShape.SCALE, TrendArrowShape.SCALE)
        rotate(rotationDegrees)
    }) {
        TrendArrowShape.SEGMENTS.forEach { s ->
            drawLine(
                color = color,
                start = Offset(s.x0 * side, s.y0 * side),
                end = Offset(s.x1 * side, s.y1 * side),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun trendLabel(trend: String?): String = stringResource(
    when (TrendArrowShape.resolvedTrend(trend)) {
        "FALLING_QUICKLY" -> R.string.trend_falling_quickly
        "FALLING" -> R.string.trend_falling
        "STABLE" -> R.string.trend_stable
        "RISING" -> R.string.trend_rising
        "RISING_QUICKLY" -> R.string.trend_rising_quickly
        else -> R.string.trend_unknown
    },
)

@Composable
fun readingAgeText(receivedAtMs: Long?): String {
    if (receivedAtMs == null || receivedAtMs <= 0L) return stringResource(R.string.age_no_reading)
    val seconds = ((System.currentTimeMillis() - receivedAtMs) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 5 -> stringResource(R.string.age_now)
        seconds < 60 -> stringResource(R.string.age_seconds, seconds.toInt())
        seconds < 3600 -> stringResource(R.string.age_minutes, (seconds / 60).toInt())
        else -> stringResource(R.string.age_hours, (seconds / 3600).toInt())
    }
}
