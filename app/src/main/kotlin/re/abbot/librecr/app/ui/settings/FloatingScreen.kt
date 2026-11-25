package re.abbot.librecr.app.ui.settings

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import re.abbot.librecr.app.overlay.FONT_BOLD
import re.abbot.librecr.app.overlay.FONT_REGULAR
import re.abbot.librecr.app.overlay.FONT_THIN
import re.abbot.librecr.app.overlay.FloatingGlucoseOverlayService
import re.abbot.librecr.app.overlay.FloatingSettings
import re.abbot.librecr.app.overlay.overlayPrefs
import re.abbot.librecr.app.ui.common.MessageBox
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.ui.common.SettingsSlider
import re.abbot.librecr.app.ui.common.SettingsSwitchRow
import kotlin.math.roundToInt

@Composable
fun FloatingScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val prefs = remember(ctx) { ctx.overlayPrefs() }
    var settings by remember { mutableStateOf(FloatingSettings.load(ctx)) }
    var enabled by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        settings = FloatingSettings.load(ctx)
    }

    fun start() {
        if (!FloatingGlucoseOverlayService.canDrawOverlays(ctx)) {
            message = null
            error = "Permisiunea de suprapunere lipsește. Permite GlucoRitm în setarea deschisă."
            ctx.startActivity(FloatingGlucoseOverlayService.permissionIntent(ctx))
            return
        }
        enabled = FloatingGlucoseOverlayService.start(ctx)
        error = null
        message = if (enabled) "Floating pornit. Trage pastila pentru poziție; tap deschide aplicația." else "Nu am putut porni overlay-ul."
    }

    fun stop() {
        FloatingGlucoseOverlayService.stop(ctx)
        enabled = false
        error = null
        message = "Floating oprit."
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard("Glucoză flotantă") {
            Text("Bulă peste celelalte aplicații, ca în Juggluco.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::start, enabled = !enabled) { Text("Pornește") }
                FilledTonalButton(onClick = ::stop, enabled = enabled) { Text("Oprește") }
                OutlinedButton(onClick = { ctx.startActivity(FloatingGlucoseOverlayService.permissionIntent(ctx)) }) {
                    Text(if (FloatingGlucoseOverlayService.canDrawOverlays(ctx)) "Permisiune OK" else "Permisiune")
                }
            }
            message?.let { MessageBox(it, false) }
            error?.let { MessageBox(it, true) }
        }

        SectionCard("Aspect") {
            SettingsSwitchRow("Transparent", settings.transparent) { edit { putBoolean(FloatingSettings.KEY_TRANSPARENT, it) } }
            SettingsSwitchRow("Valoare secundară", settings.showSecondary) { edit { putBoolean(FloatingSettings.KEY_SHOW_SECONDARY, it) } }
            SettingsSwitchRow("Săgeată trend", settings.showArrow) { edit { putBoolean(FloatingSettings.KEY_SHOW_ARROW, it) } }
            SettingsSwitchRow("Mod dynamic island", settings.dynamicIsland) { edit { putBoolean(FloatingSettings.KEY_DYNAMIC_ISLAND, it) } }
            SettingsSwitchRow("Contur subtil", settings.subtleOutline) { edit { putBoolean(FloatingSettings.KEY_SUBTLE_OUTLINE, it) } }
            SettingsSwitchRow("Culori glucoză", settings.glucoseRangeColors) { edit { putBoolean(FloatingSettings.KEY_GLUCOSE_RANGE_COLORS, it) } }
            SettingsSlider("Dimensiune font: ${settings.fontSizeSp.toInt()}sp", settings.fontSizeSp, 12f..48f) { edit { putFloat(FloatingSettings.KEY_FONT_SIZE, it) } }
            SettingsSlider("Colțuri: ${settings.cornerRadiusDp.toInt()}dp", settings.cornerRadiusDp, 0f..64f) { edit { putFloat(FloatingSettings.KEY_CORNER_RADIUS, it) } }
            SettingsSlider("Opacitate: ${(settings.opacity * 100).toInt()}%", settings.opacity, 0.1f..1f) { edit { putFloat(FloatingSettings.KEY_OPACITY, it) } }
            SettingsSlider("X: ${settings.x}px", settings.x.toFloat(), 0f..1200f, enabled = !settings.dynamicIsland) { edit { putInt(FloatingSettings.KEY_X, it.roundToInt()) } }
            SettingsSlider("Y: ${settings.y}px", settings.y.toFloat(), 0f..2400f, enabled = !settings.dynamicIsland) { edit { putInt(FloatingSettings.KEY_Y, it.roundToInt()) } }
            SettingsSlider("Island offset: ${settings.islandVerticalOffsetDp.toInt()}dp", settings.islandVerticalOffsetDp, 0f..96f, enabled = settings.dynamicIsland) { edit { putFloat(FloatingSettings.KEY_ISLAND_VERTICAL_OFFSET, it) } }
            SettingsSlider("Island gap: ${settings.islandGapDp.toInt()}dp", settings.islandGapDp, 0f..220f, enabled = settings.dynamicIsland) { edit { putFloat(FloatingSettings.KEY_ISLAND_GAP, it) } }
            SettingsSlider("Lățime island: ${autoDp(settings.islandWidthDp)}", settings.islandWidthDp, 0f..420f, enabled = settings.dynamicIsland) { edit { putFloat(FloatingSettings.KEY_ISLAND_WIDTH, it) } }
            SettingsSlider("Înălțime island: ${autoDp(settings.islandHeightDp)}", settings.islandHeightDp, 0f..96f, enabled = settings.dynamicIsland) { edit { putFloat(FloatingSettings.KEY_ISLAND_HEIGHT, it) } }
            SettingsSlider("Poziție delta: ${signedDp(settings.deltaOffsetDp)}", settings.deltaOffsetDp, -80f..80f) { edit { putFloat(FloatingSettings.KEY_DELTA_OFFSET, it) } }
            Text("Font", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontChip("Thin", settings.fontStyle == FONT_THIN) { edit { putString(FloatingSettings.KEY_FONT_STYLE, FONT_THIN) } }
                FontChip("Regular", settings.fontStyle == FONT_REGULAR) { edit { putString(FloatingSettings.KEY_FONT_STYLE, FONT_REGULAR) } }
                FontChip("Bold", settings.fontStyle == FONT_BOLD) { edit { putString(FloatingSettings.KEY_FONT_STYLE, FONT_BOLD) } }
            }
        }
    }
}

private fun signedDp(value: Float): String = "%+ddp".format(value.roundToInt())

/** 0 means "follow the text" for the island size sliders. */
private fun autoDp(value: Float): String = if (value <= 0f) "auto" else "${value.toInt()}dp"

@Composable
internal fun FontChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
