package re.abbot.librecr.app

const val GLUCOSE_STALE_AFTER_MS = 6 * 60_000L

fun isFreshGlucose(
    receivedAtMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): Boolean =
    receivedAtMs > 0L && nowMs - receivedAtMs < GLUCOSE_STALE_AFTER_MS
