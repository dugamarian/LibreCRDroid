package re.abbot.librecr.app.ui.settings

import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.R
import re.abbot.librecr.app.overlay.AodGlucoseOverlayService
import re.abbot.librecr.app.overlay.AodSettings
import re.abbot.librecr.app.overlay.FONT_BOLD
import re.abbot.librecr.app.overlay.FONT_REGULAR
import re.abbot.librecr.app.overlay.FONT_THIN
import re.abbot.librecr.app.overlay.overlayPrefs
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.MessageBox
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.ui.common.SettingsSlider
import re.abbot.librecr.app.ui.common.SettingsSwitchRow

@Composable
fun AodScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val appSettings = LocalAppSettings.current
    val scope = rememberCoroutineScope()
    val prefs = remember(ctx) { ctx.overlayPrefs() }
    var settings by remember { mutableStateOf(AodSettings.load(ctx)) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        settings = AodSettings.load(ctx)
        ctx.sendBroadcast(Intent(AodGlucoseOverlayService.ACTION_REFRESH).setPackage(ctx.packageName))
    }

    fun start() {
        val started = AodGlucoseOverlayService.start(ctx)
        message = if (started) "AOD pornit. Se afișează doar pe Always-On Display." else null
        error = if (started) null else "Pentru AOD activează serviciul LibreCRDroid din Accesibilitate. Am deschis setările."
    }

    fun stop() {
        AodGlucoseOverlayService.stop(ctx)
        error = null
        message = "AOD oprit."
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(stringResource(R.string.standby_title)) {
            SettingsSwitchRow(
                stringResource(R.string.standby_enable),
                appSettings.redStandbyEnabled,
            ) { enabled ->
                scope.launch { LibreCR.settings.setRedStandbyEnabled(enabled) }
            }
            Text(
                stringResource(R.string.standby_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StandbyTimeSlider(
                label = stringResource(R.string.alarms_start),
                minutes = appSettings.redStandbyStartMinutes,
                enabled = appSettings.redStandbyEnabled,
            ) { start ->
                scope.launch { LibreCR.settings.setRedStandbyWindow(start, appSettings.redStandbyEndMinutes) }
            }
            StandbyTimeSlider(
                label = stringResource(R.string.alarms_end),
                minutes = appSettings.redStandbyEndMinutes,
                enabled = appSettings.redStandbyEnabled,
            ) { end ->
                scope.launch { LibreCR.settings.setRedStandbyWindow(appSettings.redStandbyStartMinutes, end) }
            }
            Text(
                stringResource(R.string.standby_hours_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("Always-On Display") {
            Text("Suprapunere separată doar pentru Always-On Display.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::start) { Text("Pornește AOD") }
                FilledTonalButton(onClick = ::stop) { Text("Oprește") }
            }
            message?.let { MessageBox(it, false) }
            error?.let { MessageBox(it, true) }
        }

        SectionCard("Conținut") {
            SettingsSwitchRow("Grafic", settings.showChart) { edit { putBoolean(AodSettings.KEY_SHOW_CHART, it) } }
            SettingsSwitchRow("Săgeată", settings.showArrow) { edit { putBoolean(AodSettings.KEY_SHOW_ARROW, it) } }
            SettingsSwitchRow("Valoare secundară", settings.showSecondary) { edit { putBoolean(AodSettings.KEY_SHOW_SECONDARY, it) } }
        }

        SectionCard("Aspect") {
            SettingsSlider("Opacitate: ${(settings.opacity * 100).toInt()}%", settings.opacity, 0.35f..1f) { edit { putFloat(AodSettings.KEY_OPACITY, it) } }
            SettingsSlider("Scalare text: ${(settings.textScale * 100).toInt()}%", settings.textScale, 0.5f..6f) { edit { putFloat(AodSettings.KEY_TEXT_SCALE, it) } }
            SettingsSlider("Scalare grafic: ${(settings.chartScale * 100).toInt()}%", settings.chartScale, 0.5f..2f) { edit { putFloat(AodSettings.KEY_CHART_SCALE, it) } }
            SettingsSlider("Poziție verticală: ${(settings.verticalPosition * 100).toInt()}%", settings.verticalPosition, 0f..1f) { edit { putFloat(AodSettings.KEY_VERTICAL_POSITION, it) } }
            SettingsSlider("Dimensiune săgeată: ${(settings.arrowScale * 100).toInt()}%", settings.arrowScale, 0.5f..2f) { edit { putFloat(AodSettings.KEY_ARROW_SCALE, it) } }
            SettingsSlider("Offset săgeată: ${settings.arrowVerticalOffsetDp.toInt()}dp", settings.arrowVerticalOffsetDp, -20f..20f) { edit { putFloat(AodSettings.KEY_ARROW_VERTICAL_OFFSET, it) } }
            SettingsSlider("Scalare meta: ${(settings.metaScale * 100).toInt()}%", settings.metaScale, 0.5f..3f) { edit { putFloat(AodSettings.KEY_META_SCALE, it) } }
        }

        SectionCard("Poziții") {
            PositionCheck("Sus", AodSettings.POSITION_TOP, settings.positions) { edit { putStringSet(AodSettings.KEY_POSITIONS, toggleSet(settings.positions, AodSettings.POSITION_TOP)) } }
            PositionCheck("Centru", AodSettings.POSITION_CENTER, settings.positions) { edit { putStringSet(AodSettings.KEY_POSITIONS, toggleSet(settings.positions, AodSettings.POSITION_CENTER)) } }
            PositionCheck("Jos", AodSettings.POSITION_BOTTOM, settings.positions) { edit { putStringSet(AodSettings.KEY_POSITIONS, toggleSet(settings.positions, AodSettings.POSITION_BOTTOM)) } }
            Text("Aliniere", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontChip("Start", settings.alignment == AodSettings.ALIGN_START) { edit { putString(AodSettings.KEY_ALIGNMENT, AodSettings.ALIGN_START) } }
                FontChip("Centru", settings.alignment == AodSettings.ALIGN_CENTER) { edit { putString(AodSettings.KEY_ALIGNMENT, AodSettings.ALIGN_CENTER) } }
                FontChip("End", settings.alignment == AodSettings.ALIGN_END) { edit { putString(AodSettings.KEY_ALIGNMENT, AodSettings.ALIGN_END) } }
            }
            Text("Font", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontChip("Thin", settings.fontStyle == FONT_THIN) { edit { putString(AodSettings.KEY_FONT_STYLE, FONT_THIN) } }
                FontChip("Regular", settings.fontStyle == FONT_REGULAR) { edit { putString(AodSettings.KEY_FONT_STYLE, FONT_REGULAR) } }
                FontChip("Bold", settings.fontStyle == FONT_BOLD) { edit { putString(AodSettings.KEY_FONT_STYLE, FONT_BOLD) } }
            }
        }
    }
}

@Composable
private fun PositionCheck(label: String, value: String, positions: Set<String>, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = value in positions, onCheckedChange = { onClick() })
        Text(label)
    }
}

private fun toggleSet(source: Set<String>, value: String): Set<String> {
    val next = source.toMutableSet()
    if (!next.add(value)) next.remove(value)
    return next.ifEmpty { setOf(AodSettings.POSITION_TOP) }
}

@Composable
private fun StandbyTimeSlider(
    label: String,
    minutes: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Text("$label: ${hhmm(minutes)}", fontWeight = FontWeight.SemiBold)
    Slider(
        value = minutes.toFloat(),
        onValueChange = { onChange((it / 15).toInt() * 15) },
        valueRange = 0f..1425f,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun hhmm(minutes: Int): String {
    val normalized = minutes.coerceIn(0, 23 * 60 + 59)
    return "%02d:%02d".format(normalized / 60, normalized % 60)
}
