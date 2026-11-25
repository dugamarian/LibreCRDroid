package re.abbot.librecr.app.ui.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.WearAppearanceSettings
import re.abbot.librecr.app.data.WearDisplayFontWeight
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.ui.common.TrendArrow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun WearAppearanceScreen(modifier: Modifier = Modifier) {
    val appSettings = LocalAppSettings.current
    val appearance = appSettings.wearAppearance
    val scope = rememberCoroutineScope()
    var previewMode by remember { mutableStateOf(PreviewMode.IN_RANGE) }

    fun update(transform: (WearAppearanceSettings) -> WearAppearanceSettings) {
        val next = transform(appearance).withTargets(appSettings.targetLow, appSettings.targetHigh)
        scope.launch { GlucoRitm.settings.setWearAppearance(next) }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WearDeviceSelector()
        WearWeatherSettings()

        SectionCard(stringResource(R.string.wear_appearance_preview)) {
            WearFacePreview(
                appearance = appearance,
                mode = previewMode,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewMode.entries.forEach { mode ->
                    FilterChip(
                        selected = previewMode == mode,
                        onClick = { previewMode = mode },
                        label = { Text(stringResource(mode.labelRes)) },
                    )
                }
            }
        }

        SectionCard(stringResource(R.string.wear_appearance_font_weight)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WearDisplayFontWeight.entries.forEach { weight ->
                    FilterChip(
                        selected = appearance.fontWeight == weight,
                        onClick = { update { it.copy(fontWeight = weight) } },
                        label = { Text(weight.label) },
                    )
                }
            }
        }

        SectionCard(stringResource(R.string.wear_appearance_glucose_value_color)) {
            ColorSettingRow(stringResource(R.string.wear_appearance_low_glucose), appearance.glucoseLowColor) { color ->
                update { it.copy(glucoseLowColor = color) }
            }
            ColorSettingRow(stringResource(R.string.wear_appearance_in_range_glucose), appearance.glucoseInRangeColor) { color ->
                update { it.copy(glucoseInRangeColor = color) }
            }
            ColorSettingRow(stringResource(R.string.wear_appearance_high_glucose), appearance.glucoseHighColor) { color ->
                update { it.copy(glucoseHighColor = color) }
            }
        }

        SectionCard(stringResource(R.string.wear_appearance_trend_arrow_color)) {
            ColorSettingRow(stringResource(R.string.wear_appearance_low_glucose), appearance.trendLowColor) { color ->
                update { it.copy(trendLowColor = color) }
            }
            ColorSettingRow(stringResource(R.string.wear_appearance_in_range_glucose), appearance.trendInRangeColor) { color ->
                update { it.copy(trendInRangeColor = color) }
            }
            ColorSettingRow(stringResource(R.string.wear_appearance_high_glucose), appearance.trendHighColor) { color ->
                update { it.copy(trendHighColor = color) }
            }
        }

        SectionCard(stringResource(R.string.wear_appearance_timestamp_color)) {
            ColorSettingRow(stringResource(R.string.wear_appearance_timestamp_normal), appearance.timestampNormalColor) { color ->
                update { it.copy(timestampNormalColor = color) }
            }
            ColorSettingRow(stringResource(R.string.wear_appearance_timestamp_stale), appearance.timestampStaleColor) { color ->
                update { it.copy(timestampStaleColor = color) }
            }
        }

        SectionCard(stringResource(R.string.wear_appearance_delta_color)) {
            ColorSettingRow(stringResource(R.string.wear_appearance_delta_normal), appearance.deltaNormalColor) { color ->
                update { it.copy(deltaNormalColor = color) }
            }
            ColorSettingRow(stringResource(R.string.wear_appearance_delta_low), appearance.deltaLowColor) { color ->
                update { it.copy(deltaLowColor = color) }
            }
            ColorSettingRow(stringResource(R.string.wear_appearance_delta_high), appearance.deltaHighColor) { color ->
                update { it.copy(deltaHighColor = color) }
            }
        }
    }
}

@Composable
private fun WearFacePreview(
    appearance: WearAppearanceSettings,
    mode: PreviewMode,
) {
    val nowMs = System.currentTimeMillis()
    val reading = mode.reading(appearance, nowMs)
    val stale = nowMs - reading.receivedAtMs >= WEAR_STALE_AFTER_MS
    val weight = appearance.fontWeight.toComposeFontWeight()

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(238.dp),
            shape = CircleShape,
            color = Color.Black,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = reading.mgDl.toString(),
                        fontSize = 64.sp,
                        lineHeight = 64.sp,
                        fontWeight = weight,
                        letterSpacing = 0.sp,
                        color = composeColor(appearance.glucoseColorFor(reading.mgDl)),
                    )
                    TrendArrow(
                        trend = reading.trend,
                        color = composeColor(appearance.trendColorFor(reading.mgDl)),
                        size = 42.dp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = formatDelta(reading.deltaMgDlPerMin),
                    fontSize = 21.sp,
                    lineHeight = 24.sp,
                    fontWeight = weight,
                    color = composeColor(appearance.deltaColorFor(reading.mgDl)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = previewTimeFormatter.format(Instant.ofEpochMilli(reading.receivedAtMs)),
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = weight,
                    color = composeColor(appearance.timestampColor(stale)),
                )
            }
        }
    }
}

@Composable
private fun ColorSettingRow(
    label: String,
    color: Int,
    onColorChange: (Int) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { pickerOpen = true }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
            color = composeColor(color),
            tonalElevation = 2.dp,
        ) {}
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hexColor(color), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (pickerOpen) {
        ColorPickerDialog(
            label = label,
            color = color,
            onColorChange = onColorChange,
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun ColorPickerDialog(
    label: String,
    color: Int,
    onColorChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var red by remember(color) { mutableIntStateOf(AndroidColor.red(color)) }
    var green by remember(color) { mutableIntStateOf(AndroidColor.green(color)) }
    var blue by remember(color) { mutableIntStateOf(AndroidColor.blue(color)) }
    var hex by remember(color) { mutableStateOf(hexColor(color)) }

    fun emit(next: Int) {
        red = AndroidColor.red(next)
        green = AndroidColor.green(next)
        blue = AndroidColor.blue(next)
        hex = hexColor(next)
        onColorChange(next)
    }

    fun emitRgb(r: Int = red, g: Int = green, b: Int = blue) {
        emit(AndroidColor.argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(composeColor(AndroidColor.argb(255, red, green, blue)), MaterialTheme.shapes.medium),
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { value ->
                        hex = value.uppercase()
                        parseHexColor(value)?.let(::emit)
                    },
                    label = { Text("#RRGGBB") },
                    singleLine = true,
                )
                ColorSlider("R", red) { emitRgb(r = it) }
                ColorSlider("G", green) { emitRgb(g = it) }
                ColorSlider("B", blue) { emitRgb(b = it) }
            }
        },
    )
}

@Composable
private fun ColorSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..255f,
        )
    }
}

private enum class PreviewMode(val labelRes: Int) {
    LOW(R.string.wear_preview_low),
    IN_RANGE(R.string.wear_preview_in_range),
    HIGH(R.string.wear_preview_high),
    STALE(R.string.wear_preview_stale);

    fun reading(appearance: WearAppearanceSettings, nowMs: Long): PreviewReading {
        val targetMid = (appearance.targetLowMgDl + appearance.targetHighMgDl) / 2
        return when (this) {
            LOW -> PreviewReading(
                mgDl = (appearance.targetLowMgDl - 12).coerceAtLeast(40),
                trend = "FALLING",
                receivedAtMs = nowMs - 90_000L,
                deltaMgDlPerMin = -2.0,
            )
            IN_RANGE -> PreviewReading(
                mgDl = targetMid,
                trend = "STABLE",
                receivedAtMs = nowMs - 90_000L,
                deltaMgDlPerMin = 1.0,
            )
            HIGH -> PreviewReading(
                mgDl = appearance.targetHighMgDl + 24,
                trend = "RISING",
                receivedAtMs = nowMs - 90_000L,
                deltaMgDlPerMin = 3.0,
            )
            STALE -> PreviewReading(
                mgDl = targetMid,
                trend = "STABLE",
                receivedAtMs = nowMs - 9 * 60_000L,
                deltaMgDlPerMin = 0.0,
            )
        }
    }
}

private data class PreviewReading(
    val mgDl: Int,
    val trend: String,
    val receivedAtMs: Long,
    val deltaMgDlPerMin: Double,
)

private val previewTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault())

private const val WEAR_STALE_AFTER_MS = 6 * 60_000L

private fun WearDisplayFontWeight.toComposeFontWeight(): FontWeight = when (this) {
    WearDisplayFontWeight.THIN -> FontWeight.Thin
    WearDisplayFontWeight.EXTRA_LIGHT -> FontWeight.ExtraLight
    WearDisplayFontWeight.LIGHT -> FontWeight.Light
    WearDisplayFontWeight.NORMAL -> FontWeight.Normal
    WearDisplayFontWeight.MEDIUM -> FontWeight.Medium
    WearDisplayFontWeight.SEMIBOLD -> FontWeight.SemiBold
    WearDisplayFontWeight.BOLD -> FontWeight.Bold
    WearDisplayFontWeight.EXTRA_BOLD -> FontWeight.ExtraBold
}

private fun composeColor(argb: Int): Color =
    Color(
        red = AndroidColor.red(argb),
        green = AndroidColor.green(argb),
        blue = AndroidColor.blue(argb),
        alpha = AndroidColor.alpha(argb),
    )

private fun formatDelta(delta: Double?): String {
    if (delta == null) return "--"
    val rounded = delta.coerceIn(-99.0, 99.0).roundToInt()
    return if (rounded > 0) "+$rounded" else rounded.toString()
}

private fun hexColor(color: Int): String =
    "#%02X%02X%02X".format(AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color))

private fun parseHexColor(raw: String): Int? {
    val clean = raw.trim().removePrefix("#")
    if (clean.length != 6 && clean.length != 8) return null
    return runCatching {
        val value = clean.toLong(16)
        if (clean.length == 6) {
            (0xFF000000L or value).toInt()
        } else {
            value.toInt()
        }
    }.getOrNull()
}
