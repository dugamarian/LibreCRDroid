package re.abbot.librecr.app.data

import org.json.JSONObject

enum class WearDisplayFontWeight(val label: String, val weight: Int) {
    THIN("Thin", 100),
    EXTRA_LIGHT("Extra Light", 200),
    LIGHT("Light", 300),
    NORMAL("Normal", 400),
    MEDIUM("Medium", 500),
    SEMIBOLD("SemiBold", 600),
    BOLD("Bold", 700),
    EXTRA_BOLD("ExtraBold", 800);

    companion object {
        fun fromName(value: String?): WearDisplayFontWeight =
            entries.firstOrNull { it.name == value } ?: NORMAL
    }
}

enum class WearGlucoseRange {
    LOW,
    IN_RANGE,
    HIGH,
}

data class WearAppearanceSettings(
    val fontWeight: WearDisplayFontWeight = WearDisplayFontWeight.NORMAL,
    val glucoseLowColor: Int = 0xFFFF8A65.toInt(),
    val glucoseInRangeColor: Int = 0xFF4EE6B8.toInt(),
    val glucoseHighColor: Int = 0xFFFFC95C.toInt(),
    val trendLowColor: Int = 0xFF64B5F6.toInt(),
    val trendInRangeColor: Int = 0xFF4EE6B8.toInt(),
    val trendHighColor: Int = 0xFFFFB74D.toInt(),
    val timestampNormalColor: Int = 0xFFA8FFF0.toInt(),
    val timestampStaleColor: Int = 0xFF8E8E93.toInt(),
    val deltaNormalColor: Int = 0xFFDDE4E3.toInt(),
    val deltaLowColor: Int = 0xFFFF8A65.toInt(),
    val deltaHighColor: Int = 0xFFFFC95C.toInt(),
    val targetLowMgDl: Int = 70,
    val targetHighMgDl: Int = 180,
    /** Display unit, synced from the phone so the watch UI + complications match. */
    val unit: GlucoseUnit = GlucoseUnit.MG_DL,
) {
    fun withTargets(lowMgDl: Int, highMgDl: Int): WearAppearanceSettings =
        copy(
            targetLowMgDl = lowMgDl,
            targetHighMgDl = highMgDl.coerceAtLeast(lowMgDl + 1),
        )

    fun rangeForMgDl(mgDl: Int?): WearGlucoseRange = when {
        mgDl == null -> WearGlucoseRange.IN_RANGE
        mgDl < targetLowMgDl -> WearGlucoseRange.LOW
        mgDl > targetHighMgDl -> WearGlucoseRange.HIGH
        else -> WearGlucoseRange.IN_RANGE
    }

    fun glucoseColorFor(mgDl: Int?): Int = when (rangeForMgDl(mgDl)) {
        WearGlucoseRange.LOW -> glucoseLowColor
        WearGlucoseRange.IN_RANGE -> glucoseInRangeColor
        WearGlucoseRange.HIGH -> glucoseHighColor
    }

    fun trendColorFor(mgDl: Int?): Int = when (rangeForMgDl(mgDl)) {
        WearGlucoseRange.LOW -> trendLowColor
        WearGlucoseRange.IN_RANGE -> trendInRangeColor
        WearGlucoseRange.HIGH -> trendHighColor
    }

    fun deltaColorFor(mgDl: Int?): Int = when (rangeForMgDl(mgDl)) {
        WearGlucoseRange.LOW -> deltaLowColor
        WearGlucoseRange.IN_RANGE -> deltaNormalColor
        WearGlucoseRange.HIGH -> deltaHighColor
    }

    fun timestampColor(stale: Boolean): Int =
        if (stale) timestampStaleColor else timestampNormalColor

    fun toJson(): String = JSONObject()
        .put("version", 1)
        .put("fontWeight", fontWeight.name)
        .put("glucoseLowColor", glucoseLowColor)
        .put("glucoseInRangeColor", glucoseInRangeColor)
        .put("glucoseHighColor", glucoseHighColor)
        .put("trendLowColor", trendLowColor)
        .put("trendInRangeColor", trendInRangeColor)
        .put("trendHighColor", trendHighColor)
        .put("timestampNormalColor", timestampNormalColor)
        .put("timestampStaleColor", timestampStaleColor)
        .put("deltaNormalColor", deltaNormalColor)
        .put("deltaLowColor", deltaLowColor)
        .put("deltaHighColor", deltaHighColor)
        .put("targetLowMgDl", targetLowMgDl)
        .put("targetHighMgDl", targetHighMgDl)
        .put("unit", unit.name)
        .toString()

    companion object {
        fun fromJson(raw: String): WearAppearanceSettings {
            val defaults = WearAppearanceSettings()
            val json = JSONObject(raw)
            return WearAppearanceSettings(
                fontWeight = WearDisplayFontWeight.fromName(json.optString("fontWeight", defaults.fontWeight.name)),
                glucoseLowColor = json.optInt("glucoseLowColor", defaults.glucoseLowColor),
                glucoseInRangeColor = json.optInt("glucoseInRangeColor", defaults.glucoseInRangeColor),
                glucoseHighColor = json.optInt("glucoseHighColor", defaults.glucoseHighColor),
                trendLowColor = json.optInt("trendLowColor", defaults.trendLowColor),
                trendInRangeColor = json.optInt("trendInRangeColor", defaults.trendInRangeColor),
                trendHighColor = json.optInt("trendHighColor", defaults.trendHighColor),
                timestampNormalColor = json.optInt("timestampNormalColor", defaults.timestampNormalColor),
                timestampStaleColor = json.optInt("timestampStaleColor", defaults.timestampStaleColor),
                deltaNormalColor = json.optInt("deltaNormalColor", defaults.deltaNormalColor),
                deltaLowColor = json.optInt("deltaLowColor", defaults.deltaLowColor),
                deltaHighColor = json.optInt("deltaHighColor", defaults.deltaHighColor),
                targetLowMgDl = json.optInt("targetLowMgDl", defaults.targetLowMgDl),
                targetHighMgDl = json.optInt("targetHighMgDl", defaults.targetHighMgDl),
                unit = GlucoseUnit.fromName(json.optString("unit", defaults.unit.name)),
            )
        }
    }
}
