package re.abbot.librecr.app.live

import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.LiveUpdateSettings
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.protocol.TrendArrowShape
import kotlin.math.roundToInt

object LiveUpdateFormatter {
    fun chipText(
        reading: SensorStateStore.LastGlucose,
        unit: GlucoseUnit,
        settings: LiveUpdateSettings,
    ): String = listOfNotNull(
        unit.format(reading.mgDL),
        deltaText(reading, unit).takeIf { settings.showDeltaInChip },
    ).joinToString(" ")

    fun chipPreviewText(
        reading: SensorStateStore.LastGlucose,
        unit: GlucoseUnit,
        settings: LiveUpdateSettings,
    ): String = listOfNotNull(
        trendAscii(reading.trend).takeIf { settings.showTrendInChip },
        unit.format(reading.mgDL),
        deltaText(reading, unit).takeIf { settings.showDeltaInChip },
    ).joinToString(" ")

    fun deltaText(reading: SensorStateStore.LastGlucose, unit: GlucoseUnit): String? =
        reading.deltaMgDlPerMin
            ?.takeIf { it.isFinite() }
            ?.coerceIn(-99.0, 99.0)
            ?.let { unit.formatDelta(it.roundToInt().toDouble()) }

    fun trendAscii(trend: String?): String? =
        when (TrendArrowShape.resolvedTrend(trend)) {
            "FALLING_QUICKLY", "FALLING" -> "<-"
            "STABLE" -> "->"
            "RISING", "RISING_QUICKLY" -> "->"
            else -> null
        }

    fun trendSymbol(trend: String?): String? =
        when (TrendArrowShape.resolvedTrend(trend)) {
            "FALLING_QUICKLY" -> "↓"
            "FALLING" -> "↘"
            "STABLE" -> "→"
            "RISING" -> "↗"
            "RISING_QUICKLY" -> "↑"
            else -> null
        }

    fun progress(mgDl: Int): Int = mgDl.coerceIn(PROGRESS_MIN, PROGRESS_MAX)

    const val PROGRESS_MIN = 40
    const val PROGRESS_MAX = 400
}
