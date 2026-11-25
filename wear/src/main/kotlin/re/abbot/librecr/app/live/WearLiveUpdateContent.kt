package re.abbot.librecr.app.live

import re.abbot.librecr.app.WEAR_GLUCOSE_DISPLAY_HOLD_MS
import re.abbot.librecr.app.ble.GlucoseUi
import re.abbot.librecr.app.glucoseAgeLabel
import re.abbot.librecr.app.glucoseAgeMinutes
import re.abbot.librecr.app.nextGlucoseAgeLabelDelayMs
import re.abbot.librecr.app.ble.toLastGlucose
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.isWearGlucoseFresh
import re.abbot.librecr.protocol.TrendArrowShape
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention
import re.abbot.librecr.protocol.dataplane.SensorLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure display policy: a non-numeric live packet must never resurrect a persisted value. */
internal data class WearLiveUpdateContent(
    val reading: SensorStateStore.LastGlucose?,
    val unit: GlucoseUnit,
    val status: Status,
    val observedAtMs: Long,
) {
    enum class Status { READING, VALUE_UNAVAILABLE, UNAVAILABLE, WARMUP, SENSOR_ERROR }

    val hasFreshObservation: Boolean get() = status == Status.READING || status == Status.VALUE_UNAVAILABLE

    val arrow: String get() = when (TrendArrowShape.resolvedTrend(reading?.trend)) {
        "FALLING_QUICKLY" -> "↓"
        "FALLING" -> "↘"
        "STABLE" -> "→"
        "RISING" -> "↗"
        "RISING_QUICKLY" -> "↑"
        else -> ""
    }

    val value: String get() = reading?.let { unit.format(it.mgDL) }
        ?: if (status == Status.VALUE_UNAVAILABLE) "NA" else "--"
    val delta: String get() = reading?.deltaMgDlPerMin?.takeIf { it.isFinite() }
        ?.let(unit::formatDelta) ?: "--"
    val primaryText: String get() = if (reading == null) value else
        listOf(arrow, value, "Δ $delta").filter { it.isNotBlank() }.joinToString(" ")

    /** Static bitmap text must remain truthful even while Wear cannot run an app repaint. */
    fun observationTime(zoneId: ZoneId = ZoneId.systemDefault()): String =
        if (observedAtMs > 0L) OBSERVATION_TIME.format(Instant.ofEpochMilli(observedAtMs).atZone(zoneId)) else "--:--"

    fun ageMinutes(nowMs: Long): Long = glucoseAgeMinutes(observedAtMs, nowMs)

    fun ageLabel(nowMs: Long): String = glucoseAgeLabel(observedAtMs, nowMs)

    /**
     * Two spaces surround the dot between glucose and delta. Wear never re-renders critical text
     * (and it hides the chronometer in the chip), so the minutes advance only when the app reposts:
     * GlucoseAgeAlarm wakes the app for that while readings are missing.
     */
    fun chipText(nowMs: Long): String? = when {
        status == Status.VALUE_UNAVAILABLE -> "NA · ${ageLabel(nowMs)}"
        reading == null -> null
        else -> "$value  ·  $delta · ${ageLabel(nowMs)}"
    }

    /** Next change of the minute label while fresh, shared with the age complication. */
    fun nextAgeLabelDelayMs(nowMs: Long): Long? =
        if (hasFreshObservation) nextGlucoseAgeLabelDelayMs(observedAtMs, nowMs) else null

    /** Minute labels (if shown) and the expiry; no perpetual ticker after the reading expires. */
    fun nextRefreshDelayMs(nowMs: Long, showAge: Boolean = true): Long? {
        if (!hasFreshObservation || observedAtMs <= 0L) return null
        val remaining = WEAR_GLUCOSE_DISPLAY_HOLD_MS - (nowMs - observedAtMs).coerceAtLeast(0L)
        if (remaining <= 0L) return null
        // A label change, when one is left, always comes before the expiry.
        return (if (showAge) nextAgeLabelDelayMs(nowMs) else null) ?: remaining
    }

    companion object {
        private val OBSERVATION_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

        fun from(
            live: GlucoseUi?,
            persisted: SensorStateStore.LastGlucose?,
            unit: GlucoseUnit,
            sensorStatus: SensorStateStore.SensorStatusSnapshot?,
            warmupMinutes: Int = SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES,
            nowMs: Long,
        ): WearLiveUpdateContent {
            val candidate = if (live != null) live.toLastGlucose() else persisted
            val status = when {
                sensorStatus?.attention?.let { it != Libre3SensorAttention.None } == true -> Status.SENSOR_ERROR
                live != null && !live.usable && live.lifeCount < warmupMinutes -> Status.WARMUP
                live != null && (!live.usable || live.mgDL == null) &&
                    isWearGlucoseFresh(live.receivedAtMs, nowMs) -> Status.VALUE_UNAVAILABLE
                candidate != null && isWearGlucoseFresh(candidate.receivedAtMs, nowMs) -> Status.READING
                else -> Status.UNAVAILABLE
            }
            return WearLiveUpdateContent(
                reading = candidate.takeIf { status == Status.READING },
                unit = unit,
                status = status,
                observedAtMs = live?.receivedAtMs ?: persisted?.receivedAtMs ?: 0L,
            )
        }
    }
}
