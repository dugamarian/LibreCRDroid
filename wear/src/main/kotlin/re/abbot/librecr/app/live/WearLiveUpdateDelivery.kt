package re.abbot.librecr.app.live

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import re.abbot.librecr.app.data.GlucoseUnit
import java.time.ZoneId

internal enum class WearLiveUpdateMode {
    HIDDEN, SERVICE, LIVE;

    companion object {
        fun from(enabled: Boolean, foreground: Boolean): WearLiveUpdateMode = when {
            enabled -> LIVE
            foreground -> SERVICE
            else -> HIDDEN
        }
    }
}

/** Only notification-visible fields; persistence metadata must not trigger another Binder post. */
internal data class WearLiveUpdateNotificationKey(
    val pinned: Boolean,
    val foreground: Boolean,
    val status: WearLiveUpdateContent.Status,
    val value: String,
    val arrow: String,
    val delta: String,
    val unit: GlucoseUnit,
    val observedAtMs: Long,
    val ageMinutes: Long?,
    val observationTime: String?,
)

/** Only the pinned chip shows minutes, so only it reposts when the minute changes. */
internal fun WearLiveUpdateContent.notificationKey(pinned: Boolean, foreground: Boolean, nowMs: Long, zoneId: ZoneId = ZoneId.systemDefault()) =
    WearLiveUpdateNotificationKey(
        pinned, foreground, status, value, arrow, delta, unit,
        if (hasFreshObservation) observedAtMs else 0L,
        if (pinned && hasFreshObservation) ageMinutes(nowMs) else null,
        if (pinned && hasFreshObservation) observationTime(zoneId) else null,
    )

/** Hiding the relay display cancels both its data subscriptions and its pending delay. */
internal suspend fun collectWearLiveUpdates(
    modes: Flow<WearLiveUpdateMode>,
    content: () -> Flow<WearLiveUpdateContent>,
    refresh: () -> Unit,
    nowMs: () -> Long = System::currentTimeMillis,
    wait: suspend (Long) -> Unit = { delay(it) },
    refreshSignals: () -> Flow<Unit> = { emptyFlow() },
) {
    modes.distinctUntilChanged().collectLatest { mode ->
        if (mode == WearLiveUpdateMode.HIDDEN) {
            refresh()
        } else {
            content().distinctUntilChanged()
                // App delays can remain pending across deep sleep (GlucoseAgeAlarm covers
                // the chip then). A wake/clock signal must refresh even unchanged content and
                // rebase the next delay on wall-clock age. Keep reading deduplication before
                // combine so persistence still does no work.
                .combine(refreshSignals().onStart { emit(Unit) }) { snapshot, _ -> snapshot }
                .collectLatest { snapshot ->
                    while (true) {
                        refresh()
                        val remaining = snapshot.nextRefreshDelayMs(nowMs(), showAge = mode == WearLiveUpdateMode.LIVE)
                            ?: break
                        wait(remaining.coerceAtLeast(1L))
                    }
                }
        }
    }
}
