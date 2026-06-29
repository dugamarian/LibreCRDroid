package re.abbot.librecr.protocol

/**
 * Single source of truth for the trend-arrow glyph — the "AOD style" used on the
 * watch face: a shaft plus two barbs forming an arrowhead, pointing right at
 * rotation 0 and rotated per trend. Kept as pure geometry in a unit square so every
 * surface (Wear & phone, Compose & android.graphics Canvas) draws an identical arrow
 * instead of each rolling its own glyph or Unicode character.
 *
 * Render recipe, given a square box of side `size`:
 *   1. translate to the box,
 *   2. scale by [SCALE] about the box centre,
 *   3. rotate by [rotationDegrees] about the box centre,
 *   4. stroke each [SEGMENTS] line with width `size * `[STROKE_FRACTION] (round caps).
 */
object TrendArrowShape {
    /** Enlarge the unit glyph slightly so the barbs read at small sizes. */
    const val SCALE = 1.15f

    /** Stroke width as a fraction of the box side. */
    const val STROKE_FRACTION = 0.112f

    /** A line segment in the unit square [0,1]×[0,1], drawn at rotation 0 (arrow points right). */
    data class Segment(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    val SEGMENTS: List<Segment> = listOf(
        Segment(0.18f, 0.50f, 0.82f, 0.50f), // shaft
        Segment(0.62f, 0.28f, 0.82f, 0.50f), // upper barb
        Segment(0.62f, 0.72f, 0.82f, 0.50f), // lower barb
    )

    /** Canonical trend name to render. The trend comes from the sensor, never from glucose delta. */
    fun resolvedTrend(trend: String?, deltaMgDlPerMin: Double? = null): String? =
        canonicalTrend(trend)

    /** Clockwise rotation in degrees for a trend name, or null when there is no arrow to draw. */
    fun rotationDegrees(trend: String?): Float? = rotationForCanonical(canonicalTrend(trend))

    /**
     * Clockwise rotation in degrees. [deltaMgDlPerMin] is accepted for source compatibility, but
     * deliberately ignored so UI surfaces show the sensor-reported trend exactly.
     */
    fun rotationDegrees(trend: String?, deltaMgDlPerMin: Double?): Float? =
        rotationDegrees(trend)

    fun hasArrow(trend: String?): Boolean = rotationDegrees(trend) != null

    fun hasArrow(trend: String?, deltaMgDlPerMin: Double?): Boolean =
        hasArrow(trend)

    private fun rotationForCanonical(trend: String?): Float? = when (trend) {
        "FALLING_QUICKLY" -> 90f
        "FALLING" -> 45f
        "STABLE" -> 0f
        "RISING" -> -45f
        "RISING_QUICKLY" -> -90f
        else -> null
    }

    private fun canonicalTrend(trend: String?): String? {
        val raw = trend?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (raw.uppercase().replace('-', '_').replace(' ', '_')) {
            "1", "FALLING_QUICKLY", "FALLINGQUICKLY", "DOUBLE_DOWN", "DOWN_FAST", "FAST_DOWN" -> "FALLING_QUICKLY"
            "2", "FALLING", "DOWN" -> "FALLING"
            "3", "STABLE", "FLAT", "STEADY" -> "STABLE"
            "4", "RISING", "UP" -> "RISING"
            "5", "RISING_QUICKLY", "RISINGQUICKLY", "DOUBLE_UP", "UP_FAST", "FAST_UP" -> "RISING_QUICKLY"
            else -> null
        }
    }
}
