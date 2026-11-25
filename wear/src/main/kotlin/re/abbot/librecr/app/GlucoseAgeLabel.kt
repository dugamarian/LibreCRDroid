package re.abbot.librecr.app

private const val MINUTE_MS = 60_000L

/** Readings normally land a minute apart; allow 10 s before showing "1 m.". */
const val FIRST_GLUCOSE_AGE_LABEL_AT_MS = MINUTE_MS + 10_000L

fun glucoseAgeMinutes(observedAtMs: Long, nowMs: Long): Long =
    (nowMs - observedAtMs).coerceAtLeast(0L) / MINUTE_MS

/**
 * Minutes since a sample as the Live Update chip and the age complication show them: "<1 m.",
 * "1 m." … Wear redraws neither surface by itself, so [GlucoseAgeAlarm] wakes the app for each
 * change that [nextGlucoseAgeRefreshDelayMs] computes.
 */
fun glucoseAgeLabel(observedAtMs: Long, nowMs: Long): String =
    glucoseAgeMinutes(observedAtMs, nowMs).let { if (it == 0L) "<1 m." else "$it m." }

/**
 * Next change of the minute label inside the display window. The first is due 10 s after the
 * minute, so a reading that lands a few seconds late replaces "<1 m." without any wake-up.
 */
fun nextGlucoseAgeLabelDelayMs(observedAtMs: Long, nowMs: Long): Long? {
    if (observedAtMs <= 0L) return null
    val age = (nowMs - observedAtMs).coerceAtLeast(0L)
    val next = if (age < FIRST_GLUCOSE_AGE_LABEL_AT_MS) FIRST_GLUCOSE_AGE_LABEL_AT_MS - age else MINUTE_MS - age % MINUTE_MS
    return next.takeIf { age + it < WEAR_GLUCOSE_DISPLAY_HOLD_MS }
}

/** Label changes, then the display-window expiry; null once the sample has expired. */
fun nextGlucoseAgeRefreshDelayMs(observedAtMs: Long, nowMs: Long): Long? {
    if (observedAtMs <= 0L) return null
    val remaining = WEAR_GLUCOSE_DISPLAY_HOLD_MS - (nowMs - observedAtMs).coerceAtLeast(0L)
    if (remaining <= 0L) return null
    // A label change, when one is left, always comes before the expiry.
    return nextGlucoseAgeLabelDelayMs(observedAtMs, nowMs) ?: remaining
}
