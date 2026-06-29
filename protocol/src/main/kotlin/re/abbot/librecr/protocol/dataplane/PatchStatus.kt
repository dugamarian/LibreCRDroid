package re.abbot.librecr.protocol.dataplane

import re.abbot.librecr.protocol.u16le

/** Decoded patch-status plaintext (12 bytes). Port of Swift `PatchStatus`.
 *  Signed fields are stored as Int (sign-extended from Int16/Int8). */
class PatchStatus(plaintext: ByteArray) {
    val lifeCount: Int
    val errorData: Int
    val eventDataRaw: Int
    val eventData: Int
    val index: Int
    val totalEvents: Int
    val patchState: Int
    val currentLifeCount: Int
    val stackDisconnectReason: Int
    val appDisconnectReason: Int

    val patchStateKind: Libre3PatchState get() = Libre3PatchState(patchState)
    val isActive: Boolean get() = patchStateKind.isActive
    val isPatchStateActive: Boolean get() = patchStateKind.isActive
    val isPatchStateExpiredOrError: Boolean get() = patchStateKind.isExpiredOrError
    val isPatchStateTerminated: Boolean get() = patchStateKind.isTerminated
    val hasErrorData: Boolean get() = errorData != 0
    val sensorError: Libre3SensorError get() = Libre3SensorError.fromCode(errorData)
    val sensorAttention: Libre3SensorAttention
        get() = Libre3SensorAttention.from(errorData = errorData, patchState = patchState)
    val shouldNotifyUser: Boolean get() = sensorAttention.shouldNotifyUser
    val shouldNotifyReplaceSensor: Boolean get() = sensorAttention.isReplaceSensor
    val isShutdownTerminated: Boolean get() = sensorError.isShutdownTerminated
    val isInsertionFailure: Boolean get() = sensorError.isInsertionFailure

    /**
     * Original combined terminal check. Kept as the union of insertion failure
     * and post-shutdown termination so existing callers do not lose either signal.
     */
    @Deprecated(
        "Use isInsertionFailure and isShutdownTerminated to distinguish start failure from shutdown.",
    )
    val isTerminalFailure: Boolean get() = isInsertionFailure || isShutdownTerminated

    /** Disconnect-reason bytes are diagnostics and do not imply sensor shutdown. */
    val hasDisconnectReason: Boolean get() = stackDisconnectReason != 0 || appDisconnectReason != 0

    init {
        if (plaintext.size != PLAINTEXT_SIZE) throw PatchStatusException.WrongPlaintextSize(plaintext.size)
        val pt = plaintext
        lifeCount = s16(pt, 0)
        errorData = s16(pt, 2)
        eventDataRaw = s16(pt, 4)
        eventData = 4000 + eventDataRaw
        index = pt[6].toInt() // already signed
        totalEvents = index + 1
        patchState = pt[7].toInt()
        currentLifeCount = s16(pt, 8)
        stackDisconnectReason = pt[10].toInt()
        appDisconnectReason = pt[11].toInt()
    }

    fun lifecycle(
        warmupDurationMinutes: Int = SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES,
        wearDurationMinutes: Int? = null,
    ): SensorLifecycle = SensorLifecycle(currentLifeCount, warmupDurationMinutes, wearDurationMinutes)

    companion object {
        const val PLAINTEXT_SIZE = 12
        private fun s16(b: ByteArray, off: Int): Int = b.u16le(off).toShort().toInt()
    }
}

sealed class PatchStatusException(message: String) : Exception(message) {
    class WrongPlaintextSize(val size: Int) : PatchStatusException("wrong plaintext size $size")
}

/**
 * Product-facing action inferred from patch-status errorData, with patch-state
 * fallback when no error code is present.
 */
sealed interface Libre3SensorAttention {
    data object None : Libre3SensorAttention
    data object CheckSensor : Libre3SensorAttention
    data object SensorEnded : Libre3SensorAttention
    data object ReplaceSensor : Libre3SensorAttention
    data class Unknown(val code: Int) : Libre3SensorAttention

    val shouldNotifyUser: Boolean get() = this != None
    val isReplaceSensor: Boolean get() = this == ReplaceSensor

    companion object {
        fun from(errorData: Int, patchState: Int? = null): Libre3SensorAttention {
            // An ACTIVE sensor that is streaming glucose is healthy — never flag it, whatever
            // errorData says. errorData's meaning is not established (the reference Libre 3 decoder,
            // Juggluco, logs it but acts only on patchState), and keying "replace sensor" off it
            // falsely alarmed on an active sensor emitting valid values right after a reconnect
            // (errorData=7, patchState=4). patchState is the lifecycle source of truth.
            if (patchState != null && Libre3PatchState(patchState).isActive) return None
            return when (errorData) {
                0 -> when (patchState) {
                    3 -> CheckSensor
                    5, 6 -> SensorEnded
                    7, 8 -> ReplaceSensor
                    else -> None
                }
                3 -> CheckSensor
                5, 6 -> SensorEnded
                7, 8 -> ReplaceSensor
                else -> Unknown(errorData)
            }
        }
    }
}

/**
 * Sensor error/status carried in patch-status errorData.
 *
 * Code 5 is normal end-of-wear and remains distinct from shutdown/end-session
 * codes 6 and 8. Unknown non-zero values are preserved for diagnostics.
 */
sealed interface Libre3SensorError {
    data object None : Libre3SensorError
    data object InsertionFailure : Libre3SensorError
    data object Expired : Libre3SensorError
    data object Terminated : Libre3SensorError
    data object TransmissionError : Libre3SensorError
    data class Unknown(val code: Int) : Libre3SensorError

    val isShutdownTerminated: Boolean get() = this == Terminated
    val isInsertionFailure: Boolean get() = this == InsertionFailure

    companion object {
        fun fromCode(code: Int): Libre3SensorError = when (code) {
            0 -> None
            3 -> InsertionFailure
            5 -> Expired
            6, 8 -> Terminated
            7 -> TransmissionError
            else -> Unknown(code)
        }
    }
}

/**
 * Raw patch-state value plus known grouping helpers.
 *
 * Active is state 4; states 3/5/7 are expired-or-error handling states; states
 * 6/8 are already terminated/shut down.
 */
data class Libre3PatchState(val rawValue: Int) {
    val isActive: Boolean get() = rawValue == 4
    val isExpiredOrError: Boolean get() = rawValue == 3 || rawValue == 5 || rawValue == 7
    val isTerminated: Boolean get() = rawValue == 6 || rawValue == 8
}
