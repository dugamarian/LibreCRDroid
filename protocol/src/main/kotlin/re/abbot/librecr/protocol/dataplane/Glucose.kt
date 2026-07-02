package re.abbot.librecr.protocol.dataplane

/** Display-range handling for a raw 16-bit sensor glucose value. Port of Swift
 *  `Libre3GlucoseValueStatus`. */
sealed class Libre3GlucoseValueStatus {
    data class Valid(val mgDL: Int) : Libre3GlucoseValueStatus()
    data class BelowDisplayRange(val raw: Int, val cappedMgDL: Int) : Libre3GlucoseValueStatus()
    data class AboveDisplayRange(val raw: Int, val cappedMgDL: Int) : Libre3GlucoseValueStatus()
    data class Unavailable(val raw: Int) : Libre3GlucoseValueStatus()

    val displayMgDL: Int?
        get() = when (this) {
            is Valid -> mgDL
            is BelowDisplayRange -> cappedMgDL
            is AboveDisplayRange -> cappedMgDL
            is Unavailable -> null
        }

    /**
     * Value for charting/history only: like [displayMgDL] but returns the sensor's actual (uncapped)
     * estimate when it reads below the display floor, so a deep hypo is visible on the graph instead
     * of flat-lining at the "LO" cap. The headline number / alarms / upload keep [displayMgDL].
     */
    val chartMgDL: Int?
        get() = when (this) {
            is Valid -> mgDL
            is BelowDisplayRange -> raw
            is AboveDisplayRange -> cappedMgDL
            is Unavailable -> null
        }

    val isDisplayable: Boolean get() = displayMgDL != null

    companion object {
        fun fromRaw(value: Int): Libre3GlucoseValueStatus = when (value) {
            in 1..38 -> BelowDisplayRange(value, 39)
            in 39..501 -> Valid(value)
            in 502..999 -> AboveDisplayRange(value, 501)
            else -> Unavailable(value)
        }
    }
}

enum class Libre3Trend(val raw: Int) {
    NOT_DETERMINED(0), FALLING_QUICKLY(1), FALLING(2), STABLE(3), RISING(4), RISING_QUICKLY(5), RAW(-1);
    companion object {
        fun fromRaw(raw: Int): Libre3Trend = entries.firstOrNull { it.raw == raw } ?: RAW
    }
}

enum class Libre3SensorCondition(val raw: Int) {
    OK(0), INVALID(1), ESA(2), RAW(-1);
    companion object { fun fromRaw(raw: Int) = entries.firstOrNull { it.raw == raw } ?: RAW }
}

enum class Libre3ResultRangeStatus(val raw: Int) {
    IN_RANGE(0), BELOW_RANGE(1), ABOVE_RANGE(2), RAW(-1);
    companion object { fun fromRaw(raw: Int) = entries.firstOrNull { it.raw == raw } ?: RAW }
}

enum class Libre3ActionableStatus(val raw: Int) {
    NOT_ACTIONABLE(0), ACTIONABLE(1), RAW(-1);
    companion object { fun fromRaw(raw: Int) = entries.firstOrNull { it.raw == raw } ?: RAW }
}

/** Data-quality error decoded from a packed glucose word (raw 0 == good). */
data class Libre3DataQualityError(val rawValue: Int) {
    val isGood: Boolean get() = rawValue == 0
    val isNotDisplayable: Boolean
        get() = when {
            rawValue and 0xE000 == 0xA000 -> true // too hot
            rawValue and 0xE000 == 0xC000 -> true // too cold
            rawValue and 0x8000 != 0 -> true
            else -> false
        }
}
