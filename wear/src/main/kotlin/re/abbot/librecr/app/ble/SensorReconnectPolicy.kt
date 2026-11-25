package re.abbot.librecr.app.ble

/**
 * Reconnect policy for a sensor which is connectable during only one short advertising slot per
 * minute. Missing that slot costs a whole minute, so after a lost link nothing may stand between
 * the drop and a connect request which the Bluetooth controller itself keeps pending:
 *
 *  - the controller's accept-list connect (`autoConnect = true`) has no deadline, survives Doze and
 *    retries a failed establishment within the same slot without any help from the app;
 *  - a direct connect is bounded, and whatever the app does after it (timers, back-off) is frozen
 *    by Doze, which is how the slot was missed in the field (2026-11-09: recoveries of 96-123 s
 *    where the first slot was 36-63 s away).
 */

/**
 * HCI 0x08, the link-supervision timeout of an established connection. Android up to 16 hands it
 * to the app as 8; Wear OS 7 / Android 17 hands it over as GATT_CONNECTION_TIMEOUT (147), the code
 * older releases reserved for a connect attempt which never completed.
 */
internal fun isLinkSupervisionTimeout(status: Int?): Boolean = status == 8 || status == 147

/**
 * Only the cold first attempt and the one-shot escape below are direct; every other attempt is
 * handed to the controller.
 */
internal fun shouldUseControllerAutoConnect(reconnectAttempt: Int, directFallbackArmed: Boolean): Boolean =
    reconnectAttempt > 0 && !directFallbackArmed

/** A controller-owned connect intentionally has no app-side teardown timer. */
internal fun sensorConnectTimeoutMs(autoConnect: Boolean, directTimeoutMs: Long): Long? =
    if (autoConnect) null else directTimeoutMs

/**
 * Android may surface the controller's CONNECTION_FAILED_ESTABLISHMENT (0x3e) as the generic
 * GATT_ERROR (133). If a controller connect keeps ending that way before any link came up,
 * retrying in the same mode can remain stuck; one direct connect restarts establishment through
 * Android's foreground path without discarding the cached Libre session.
 *
 * A link which had come up is never an establishment failure, whatever status reports its loss:
 * the sensor then stays silent until its next slot, where only a pending controller connect helps.
 */
internal fun shouldRetryWithDirectConnect(
    autoConnect: Boolean,
    status: Int,
    linkWasEstablished: Boolean,
): Boolean = autoConnect && !linkWasEstablished && status in DIRECT_CONNECT_FALLBACK_STATUSES

internal data class ReconnectBackoff(
    val delayMs: Long,
    /** Hold a wake lock across the delay; without one Doze stretches it past the next slot. */
    val keepAwake: Boolean,
)

/**
 * Pause before the next attempt (1-based, counted since the last valid reading).
 *
 * While the next attempt can be handed to the controller the pause stays short and exact. Only
 * after [PRECISE_REARM_ATTEMPTS] consecutive failures does it fall back to the long, unlocked
 * ladder, so a fault which fails every attempt at once cannot keep the watch awake.
 */
internal fun reconnectBackoff(attempt: Int, controllerRearm: Boolean): ReconnectBackoff {
    val ladderMs = RECONNECT_BACKOFF_MS[(attempt - 1).coerceIn(0, RECONNECT_BACKOFF_MS.size - 1)]
    return if (controllerRearm && attempt <= PRECISE_REARM_ATTEMPTS) {
        ReconnectBackoff(delayMs = minOf(ladderMs, PRECISE_REARM_MAX_DELAY_MS), keepAwake = true)
    } else {
        ReconnectBackoff(delayMs = ladderMs, keepAwake = false)
    }
}

internal const val PRECISE_REARM_ATTEMPTS = 6
internal const val PRECISE_REARM_MAX_DELAY_MS = 5_000L

private val RECONNECT_BACKOFF_MS = longArrayOf(
    500L,     // attempt 1
    2_000L,   // attempt 2
    2_000L,   // attempt 3
    5_000L,   // attempt 4
    10_000L,  // attempt 5
    20_000L,  // attempt 6
    30_000L,  // attempt 7+
)

private val DIRECT_CONNECT_FALLBACK_STATUSES = setOf(
    62,  // HCI_CONNECTION_FAILED_ESTABLISHMENT (0x3e), when Android exposes the HCI value.
    133, // GATT_ERROR, how the same failure was surfaced to the app on the Pixel Watch.
)
