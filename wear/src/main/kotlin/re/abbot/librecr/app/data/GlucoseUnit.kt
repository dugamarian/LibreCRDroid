package re.abbot.librecr.app.data

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Glucose display unit on the watch. Values are always mg/dL internally (the sensor's native unit
 * and what crosses the phone↔watch wire); this only governs presentation. 1 mmol/L = 18 mg/dL.
 * Mirrors the phone's `GlucoseUnit`; the choice is synced from the phone via [WearAppearanceSettings].
 */
enum class GlucoseUnit(val label: String) {
    MG_DL("mg/dL"),
    MMOL_L("mmol/L");

    /** Value only, no unit suffix. mmol/L is shown with one decimal. */
    fun format(mgDl: Int): String = when (this) {
        MG_DL -> mgDl.toString()
        MMOL_L -> String.format(Locale.US, "%.1f", mgDl / 18.0)
    }

    fun formatWithUnit(mgDl: Int): String = "${format(mgDl)} $label"

    /** Signed delta of a mg/dL change, expressed in this unit. */
    fun formatDelta(deltaMgDl: Double): String {
        val sign = if (deltaMgDl >= 0) "+" else ""
        return when (this) {
            MG_DL -> "$sign${deltaMgDl.roundToInt()}"
            MMOL_L -> sign + String.format(Locale.US, "%.1f", deltaMgDl / 18.0)
        }
    }

    companion object {
        fun fromName(name: String?): GlucoseUnit = entries.firstOrNull { it.name == name } ?: MG_DL
    }
}
