package re.abbot.librecr.protocol.connection

/** Persistent phases describe intent; ownership is confirmed only by a new local sensor packet. */
enum class RecoveryPhase { WATCH_MONITOR, RELEASING_WATCH, PHONE_CONNECTING, PHONE_COLLECTING, WATCH_CONNECTING }

data class RecoveryState(
    val sensor: String,
    val nodeId: String,
    val phase: RecoveryPhase,
    val token: String,
    val sequence: Long,
    val preferWatch: Boolean = true,
    val deadlineMs: Long = 0L,
    val failedWatchTransfers: Int = 0,
    val retryWatchAtMs: Long = 0L,
    val confirmationAfterLifeCount: Int = -1,
    val claimUntilMs: Long = 0L,
    val phoneReturnTrial: Boolean = false,
)

data class ReceiverHealth(
    val reachable: Boolean = false,
    val active: Boolean = false,
    val packetAgeMs: Long = Long.MAX_VALUE,
    val canStart: Boolean = false,
    val claiming: Boolean = false,
    val claimRemainingMs: Long = 0L,
    val lastLifeCount: Int = -1,
) {
    val healthy: Boolean get() = reachable && active && packetAgeMs in 0 until RecoveryPolicy.SILENCE_MS
    val claimActive: Boolean get() = claiming && claimRemainingMs > 0
}

object RecoveryPolicy {
    const val SILENCE_MS = 180_000L
    /**
     * Libre 3 readings and its reconnect advertisements share one second of every minute, so a
     * watch-silence check exactly [SILENCE_MS] after the last relayed reading fires at the very
     * instant a recovering watch re-establishes its link, before its first new reading, and
     * released it (field log 2026-11-07). The grace moves the check past that first reading.
     */
    const val MONITOR_GRACE_MS = 20_000L
    const val MONITOR_DEADLINE_MS = SILENCE_MS + MONITOR_GRACE_MS
    const val FIRST_PACKET_MS = 150_000L
    /** A watch whose own sensor link came up this recently is finishing a reconnect, not dead. */
    const val RECONNECT_CLAIM_MS = 45_000L
    /** Reconnect claims end once the watch has gone this long without a reading. */
    const val RECONNECT_CLAIM_MAX_SILENCE_MS = 2 * SILENCE_MS
    const val CRITICAL_BATTERY_PERCENT = 5
    const val RETURN_BATTERY_PERCENT = 15
    const val STATUS = "/librecr/automatic_handoff/status"
    const val PREPARE_WATCH = "/librecr/automatic_handoff/prepare_watch"
    const val SESSION_BACKUP = "/librecr/automatic_handoff/session_backup"

    fun shouldAdoptBackup(hasLocalKey: Boolean, localVerifiedAtMs: Long, peerChangedAtMs: Long, nowWallMs: Long): Boolean =
        !hasLocalKey || (peerChangedAtMs > localVerifiedAtMs && peerChangedAtMs <= nowWallMs + SILENCE_MS)

    fun acceptsControl(storedController: String, storedSequence: Long, controller: String, sequence: Long): Boolean =
        sequence >= 0 && (storedController != controller || sequence >= storedSequence)

    fun preparedStartValid(phase: String, preparedUntilMs: Long, nowMs: Long): Boolean =
        phase == "prepared" && nowMs <= preparedUntilMs

    /** Repeating a prepare may acknowledge a reservation, but cannot revive a released transfer. */
    fun acceptsPreparation(sameCommand: Boolean, phase: String): Boolean = !sameCommand || phase == "prepared"

    fun canStartWatch(batteryPercent: Int, charging: Boolean): Boolean =
        batteryPercent >= RETURN_BATTERY_PERCENT || (charging && batteryPercent > CRITICAL_BATTERY_PERCENT)

    fun failedTransferDelay(attempt: Int): Long = (180_000L shl attempt.coerceIn(0, 4)).coerceAtMost(1_800_000L)

    fun retryDelay(attempt: Int): Long = longArrayOf(20_000L, 60_000L, 120_000L, 300_000L)[attempt.coerceIn(0, 3)]

    /** Delay until the next watch-silence check, counted from the watch's last fresh packet. */
    fun monitorDelayMs(packetAgeMs: Long = 0L): Long = (MONITOR_DEADLINE_MS - packetAgeMs).coerceAtLeast(1L)

    /**
     * Claim a watch reports while it re-establishes its own sensor link. Bounded by the link's age
     * and by the watch's silence, so a reconnect/fail loop cannot keep the phone away forever.
     */
    fun reconnectClaimMs(healthy: Boolean, linkAgeMs: Long?, packetAgeMs: Long): Long =
        if (healthy || linkAgeMs == null || linkAgeMs !in 0L until RECONNECT_CLAIM_MS ||
            packetAgeMs >= RECONNECT_CLAIM_MAX_SILENCE_MS
        ) 0L else RECONNECT_CLAIM_MS - linkAgeMs

    /** Saved wall-clock deadlines are rebased once per process; clock changes cannot extend a claim forever. */
    fun restoreDeadline(savedWallMs: Long, wallMs: Long, elapsedMs: Long): Long =
        elapsedMs + (savedWallMs - wallMs).coerceIn(0L, MONITOR_DEADLINE_MS)
}

/** Android/Wear transport adapter. A false release/start result may also mean a lost acknowledgement. */
interface RecoveryRuntime {
    fun nowMs(): Long
    fun nextToken(): String
    fun nextSequence(previous: Long): Long = previous + 1
    suspend fun save(state: RecoveryState?)
    suspend fun status(nodeId: String): ReceiverHealth
    suspend fun releaseWatch(state: RecoveryState, manual: Boolean): Boolean
    suspend fun prepareWatch(state: RecoveryState): Boolean
    suspend fun startWatch(state: RecoveryState): Boolean
    suspend fun startPhone(): Boolean
    suspend fun stopPhone(): Boolean
    suspend fun watchOwnsSensor()
}

/** Serialized by its caller. Transport failures are represented as unavailable peers by the adapter. */
class PhoneRecoveryCoordinator(private val runtime: RecoveryRuntime, initial: RecoveryState? = null, initialSequence: Long = 0) {
    var state: RecoveryState? = initial
        private set
    private var enabled = false
    private var sequence = maxOf(initial?.sequence ?: 0L, initialSequence)
    private var nextReturnAttemptMs = 0L
    private val readings = ConsecutiveReadingTracker()
    private var lastRemoteLifeCount: Int? = null
    private var lastLocalLifeCount: Int? = null
    private var lastLocalPacketMs: Long? = null

    val ownsPhone: Boolean get() = state?.phase != null && state?.phase != RecoveryPhase.WATCH_MONITOR

    suspend fun configure(enabled: Boolean) {
        this.enabled = enabled
        readings.reset()
    }

    suspend fun cancel() {
        readings.reset()
        lastRemoteLifeCount = null
        lastLocalLifeCount = null
        lastLocalPacketMs = null
        checkpoint(null)
    }

    suspend fun preferWatch(sensor: String, nodeId: String) {
        if (state?.sensor == sensor && state?.phase == RecoveryPhase.WATCH_MONITOR) {
            if (nodeId.isNotBlank() && state?.nodeId != nodeId) checkpoint(state!!.copy(nodeId = nodeId))
            return
        }
        readings.reset()
        lastLocalPacketMs = null
        checkpoint(newState(sensor, nodeId, RecoveryPhase.WATCH_MONITOR,
            deadline = runtime.nowMs() + RecoveryPolicy.monitorDelayMs()))
    }

    /** Only fresh, advancing live messages call this; DataItems and historical replay never do. */
    suspend fun remotePacket(sensor: String, nodeId: String, lifeCount: Int) {
        val current = state ?: return
        if (current.sensor != sensor || (current.nodeId.isNotBlank() && current.nodeId != nodeId)) return
        if (lastRemoteLifeCount != null && lifeCount <= lastRemoteLifeCount!!) return
        lastRemoteLifeCount = lifeCount
        when (current.phase) {
            RecoveryPhase.WATCH_MONITOR -> checkpoint(current.copy(nodeId = nodeId,
                deadlineMs = runtime.nowMs() + RecoveryPolicy.monitorDelayMs()))
            RecoveryPhase.WATCH_CONNECTING -> if (lifeCount > maxOf(lastLocalLifeCount ?: -1, current.confirmationAfterLifeCount)) finishWatch(current.copy(nodeId = nodeId))
            else -> Unit // A delayed relay cannot stop a confirmed phone collector.
        }
    }

    suspend fun localPacket(lifeCount: Int, usable: Boolean) {
        val current = state ?: return
        if (current.phase != RecoveryPhase.PHONE_CONNECTING && current.phase != RecoveryPhase.PHONE_COLLECTING) return
        if (current.phoneReturnTrial && runtime.nowMs() >= current.deadlineMs) {
            rollbackPhoneReturn(current)
            return
        }
        if (current.phoneReturnTrial && lifeCount <= current.confirmationAfterLifeCount) return
        if (lastLocalLifeCount != null && lifeCount <= lastLocalLifeCount!!) return
        lastLocalLifeCount = lifeCount
        lastLocalPacketMs = runtime.nowMs()
        if (current.phase == RecoveryPhase.PHONE_CONNECTING) checkpoint(current.copy(
            phase = RecoveryPhase.PHONE_COLLECTING, deadlineMs = runtime.nowMs() + RecoveryPolicy.SILENCE_MS,
            phoneReturnTrial = false))
        if (readings.reading(lifeCount, usable, runtime.nowMs()) && enabled && current.preferWatch) tryReturn()
    }

    fun interrupted() { readings.reset() }
    fun peerConnected() { nextReturnAttemptMs = 0L }

    /** A fallback reading proves watch ownership, not that the phone can reach the sensor again. */
    suspend fun confirmWatchFallback(sensor: String, nodeId: String, lifeCount: Int? = null): Boolean {
        val current = state
        if (current != null && (current.sensor != sensor || (current.nodeId.isNotBlank() && current.nodeId != nodeId))) return false
        if (current?.phoneReturnTrial == true && current.phase == RecoveryPhase.PHONE_CONNECTING &&
            (lifeCount == null || lifeCount <= current.confirmationAfterLifeCount)) return false
        if (lifeCount != null && lifeCount <= maxOf(lastLocalLifeCount ?: -1, lastRemoteLifeCount ?: -1)) return false
        if (current?.phase != RecoveryPhase.WATCH_MONITOR) {
            if (!runtime.stopPhone()) return false
            runtime.watchOwnsSensor()
        }
        readings.reset()
        lastLocalPacketMs = null
        if (lifeCount != null) lastRemoteLifeCount = lifeCount
        val next = current ?: newState(sensor, nodeId, RecoveryPhase.WATCH_MONITOR,
            preferWatch = false, deadline = runtime.nowMs() + RecoveryPolicy.monitorDelayMs())
        checkpoint(next.copy(phase = RecoveryPhase.WATCH_MONITOR, nodeId = nodeId, preferWatch = false,
            deadlineMs = runtime.nowMs() + RecoveryPolicy.monitorDelayMs(), phoneReturnTrial = false))
        return true
    }

    /** Automatic trials require a recoverable watch and a confirmed release; manual transfers do not. */
    suspend fun tryPhoneReturn(sensor: String, nodeId: String): Boolean {
        val current = state
        if (current != null && (current.sensor != sensor ||
                (current.nodeId.isNotBlank() && current.nodeId != nodeId) ||
                current.phase != RecoveryPhase.WATCH_MONITOR || current.phoneReturnTrial)) return false
        val before = runtime.status(nodeId)
        if (!before.reachable || !before.canStart || (!before.healthy && !before.claimActive)) return false
        val deadline = runtime.nowMs() + RecoveryPolicy.FIRST_PACKET_MS
        var trial = newState(sensor, nodeId, RecoveryPhase.RELEASING_WATCH, preferWatch = false,
            deadline = deadline).copy(phoneReturnTrial = true, claimUntilMs = deadline,
                confirmationAfterLifeCount = before.lastLifeCount)
        readings.reset()
        lastLocalLifeCount = null
        lastLocalPacketMs = null
        checkpoint(trial)
        if (!runtime.releaseWatch(trial, manual = true)) {
            // A lost release ACK must not cause a competing phone connection. The watch's own
            // persisted release lease can restore it even if it actually stopped before ACK loss.
            finishWatch(trial)
            return false
        }
        val after = runtime.status(nodeId)
        trial = trial.copy(confirmationAfterLifeCount = maxOf(before.lastLifeCount, after.lastLifeCount))
        if (after.healthy || after.claimActive) {
            finishWatch(trial, after)
            return false
        }
        trial = trial.copy(phase = RecoveryPhase.PHONE_CONNECTING)
        checkpoint(trial)
        if (runtime.nowMs() >= trial.deadlineMs || !runtime.startPhone()) {
            rollbackPhoneReturn(trial)
            return false
        }
        return true
    }

    /** Turning automatic mode off keeps an already chosen phone collector without a later rollback. */
    suspend fun keepPhoneAfterTrial() {
        val current = state ?: return
        if (current.phoneReturnTrial && current.phase in listOf(RecoveryPhase.PHONE_CONNECTING, RecoveryPhase.PHONE_COLLECTING)) {
            checkpoint(current.copy(phoneReturnTrial = false))
        }
    }

    /** Uses the acknowledged release protocol even for a healthy watch when the phone is preferred. */
    suspend fun preferPhone(sensor: String, nodeId: String, watchCollecting: Boolean = false, phoneAlreadyRunning: Boolean = false): Boolean {
        val current = state
        if (current != null && current.sensor != sensor) return false
        if ((ownsPhone || phoneAlreadyRunning) && watchCollecting) {
            if (!runtime.stopPhone()) return false
            cancel()
        } else if (current?.phase in listOf(RecoveryPhase.PHONE_CONNECTING, RecoveryPhase.PHONE_COLLECTING)) {
            checkpoint(current!!.copy(nodeId = nodeId, preferWatch = false))
            return true
        } else if (phoneAlreadyRunning) {
            // Enabling priority while a manual phone session is live must not interrupt it or
            // import the parked watch's older reconnect key into the running handshake.
            checkpoint(newState(sensor, nodeId, RecoveryPhase.PHONE_CONNECTING, preferWatch = false,
                deadline = runtime.nowMs() + RecoveryPolicy.FIRST_PACKET_MS).copy(
                    claimUntilMs = runtime.nowMs() + RecoveryPolicy.FIRST_PACKET_MS))
            return true
        }
        return requestPhone(sensor, nodeId, manual = true)
    }

    suspend fun requestPhone(sensor: String, nodeId: String, manual: Boolean = false, criticalBattery: Boolean = false): Boolean {
        if (!manual && !enabled) return false
        val current = state
        if (current != null && current.sensor != sensor) return false
        if (current?.phase in listOf(RecoveryPhase.PHONE_CONNECTING, RecoveryPhase.PHONE_COLLECTING)) return true
        val health = runtime.status(nodeId)
        if (health.healthy && !manual && !criticalBattery) {
            preferWatch(sensor, nodeId)
            checkpoint(state!!.copy(deadlineMs = watchCheckAt(health)))
            return false
        }
        val failedReturn = current?.phase == RecoveryPhase.WATCH_CONNECTING
        var next = newState(sensor, nodeId, RecoveryPhase.RELEASING_WATCH,
            preferWatch = !manual, deadline = runtime.nowMs() + AutomaticHandoff.RPC_TIMEOUT_MS).copy(
                failedWatchTransfers = (current?.failedWatchTransfers ?: 0) + if (failedReturn) 1 else 0,
                retryWatchAtMs = if (failedReturn) runtime.nowMs() + RecoveryPolicy.failedTransferDelay(current?.failedWatchTransfers ?: 0)
                    else current?.retryWatchAtMs ?: 0L)
        checkpoint(next) // Recovery after a process death must not depend on the peer finishing an RPC.
        runtime.releaseWatch(next, manual)
        val afterRelease = runtime.status(nodeId)
        if (afterRelease.healthy || afterRelease.claimActive) {
            // The owner recovered, is finishing its own reconnect, or refused the release; starting
            // the phone now would compete for the single sensor link. A manual request can be retried.
            finishWatch(next, afterRelease)
            return false
        }
        next = next.copy(phase = RecoveryPhase.PHONE_CONNECTING,
            deadlineMs = runtime.nowMs() + RecoveryPolicy.FIRST_PACKET_MS,
            claimUntilMs = runtime.nowMs() + RecoveryPolicy.FIRST_PACKET_MS)
        readings.reset()
        lastLocalLifeCount = null
        lastLocalPacketMs = null
        checkpoint(next)
        return runtime.startPhone() // No release ACK is required when the peer cannot answer.
    }

    fun nextCheckMs(): Long? {
        val current = state ?: return null
        val now = runtime.nowMs()
        return when (current.phase) {
            RecoveryPhase.WATCH_MONITOR -> if (enabled) (current.deadlineMs - now).coerceAtLeast(1L) else null
            RecoveryPhase.PHONE_COLLECTING -> if (enabled && current.preferWatch) {
                if (readings.ready(now)) return (maxOf(nextReturnAttemptMs, current.retryWatchAtMs) - now).coerceAtLeast(1L)
                (maxOf(current.deadlineMs, (lastLocalPacketMs ?: 0L) + RecoveryPolicy.SILENCE_MS) - now).coerceAtLeast(1L)
            } else null
            RecoveryPhase.WATCH_CONNECTING -> minOf(20_000L, (current.deadlineMs - now).coerceAtLeast(1L))
            else -> (current.deadlineMs - now).coerceAtLeast(1L)
        }
    }

    suspend fun tick() {
        val current = state ?: return
        val now = runtime.nowMs()
        when (current.phase) {
            RecoveryPhase.WATCH_MONITOR -> if (enabled && now >= current.deadlineMs) {
                val health = runtime.status(current.nodeId)
                if (health.healthy || health.claimActive) checkpoint(current.copy(deadlineMs = watchCheckAt(health)))
                else requestPhone(current.sensor, current.nodeId)
            }
            RecoveryPhase.RELEASING_WATCH -> if (now >= current.deadlineMs) {
                if (current.phoneReturnTrial) {
                    // A restart lost knowledge of the release acknowledgement. Never renew this
                    // reservation or start the phone without one; the watch release lease survives.
                    finishWatch(current)
                    return
                }
                // Includes a restart between persisted intent, release, and service launch.
                if (current.preferWatch && runtime.status(current.nodeId).healthy) { finishWatch(current); return }
                runtime.releaseWatch(current, manual = !current.preferWatch)
                val afterRelease = runtime.status(current.nodeId)
                if (afterRelease.healthy || afterRelease.claimActive) { finishWatch(current, afterRelease); return }
                checkpoint(current.copy(phase = RecoveryPhase.PHONE_CONNECTING,
                    deadlineMs = now + RecoveryPolicy.FIRST_PACKET_MS, claimUntilMs = now + RecoveryPolicy.FIRST_PACKET_MS))
                runtime.startPhone()
            }
            RecoveryPhase.PHONE_CONNECTING -> if (now >= current.deadlineMs) {
                if (current.phoneReturnTrial) { rollbackPhoneReturn(current); return }
                if (current.preferWatch && runtime.status(current.nodeId).healthy) { finishWatch(current); return }
                if (enabled && current.preferWatch && tryReturn()) return
                // Keep the existing economical BLE loop. Do not restart it every timeout.
                checkpoint(current.copy(deadlineMs = now + RecoveryPolicy.SILENCE_MS))
                runtime.startPhone() // Idempotent service start also repairs a failed foreground start.
            }
            RecoveryPhase.PHONE_COLLECTING -> {
                if (enabled && current.preferWatch && readings.ready(now)) tryReturn()
                else if (enabled && current.preferWatch && now >= maxOf(current.deadlineMs, (lastLocalPacketMs ?: 0L) + RecoveryPolicy.SILENCE_MS)) {
                    // The phone lost the sensor too: returning does not require five impossible values.
                    if (!tryReturn()) checkpoint(current.copy(deadlineMs = now + RecoveryPolicy.SILENCE_MS))
                }
            }
            RecoveryPhase.WATCH_CONNECTING -> {
                if (current.phoneReturnTrial) {
                    // Restart may have happened after saving rollback intent and before stopping
                    // the phone. Re-establish that ordering before any watch start/retry.
                    if (!runtime.stopPhone()) return
                    val health = runtime.status(current.nodeId)
                    if (health.healthy && health.lastLifeCount > current.confirmationAfterLifeCount) finishWatch(current, health)
                    else if (now >= current.deadlineMs) rollbackPhoneReturn(current)
                    else if (!health.claimActive) startWatchRollback(current)
                    return
                }
                val health = runtime.status(current.nodeId)
                if (health.healthy) finishWatch(current)
                else if (now >= current.deadlineMs) {
                    // A start ACK only confirms launch, not a working sensor link.
                    runtime.releaseWatch(current, false)
                    val latest = runtime.status(current.nodeId)
                    if (latest.healthy) finishWatch(current)
                    else {
                        checkpoint(current.copy(phase = RecoveryPhase.PHONE_CONNECTING,
                            deadlineMs = now + RecoveryPolicy.FIRST_PACKET_MS,
                            claimUntilMs = now + RecoveryPolicy.FIRST_PACKET_MS,
                            failedWatchTransfers = current.failedWatchTransfers + 1,
                            retryWatchAtMs = now + RecoveryPolicy.failedTransferDelay(current.failedWatchTransfers)))
                        runtime.startPhone()
                    }
                }
            }
        }
    }

    private suspend fun rollbackPhoneReturn(current: RecoveryState) {
        val rollback = newState(current.sensor, current.nodeId, RecoveryPhase.WATCH_CONNECTING,
            preferWatch = false, deadline = runtime.nowMs() + RecoveryPolicy.FIRST_PACKET_MS).copy(
                phoneReturnTrial = true, confirmationAfterLifeCount = current.confirmationAfterLifeCount)
        checkpoint(rollback)
        if (!runtime.stopPhone()) return
        startWatchRollback(rollback)
    }

    private suspend fun startWatchRollback(current: RecoveryState) {
        if (runtime.prepareWatch(current)) runtime.startWatch(current)
    }

    private suspend fun tryReturn(): Boolean {
        val current = state ?: return false
        if (runtime.nowMs() < maxOf(nextReturnAttemptMs, current.retryWatchAtMs)) return false
        nextReturnAttemptMs = runtime.nowMs() + 60_000L
        if (runtime.status(current.nodeId).healthy) { finishWatch(current); return true }
        val next = newState(current.sensor, current.nodeId, RecoveryPhase.WATCH_CONNECTING,
            deadline = runtime.nowMs() + RecoveryPolicy.FIRST_PACKET_MS).copy(
                failedWatchTransfers = current.failedWatchTransfers, retryWatchAtMs = current.retryWatchAtMs,
                confirmationAfterLifeCount = lastLocalLifeCount ?: current.confirmationAfterLifeCount)
        if (!runtime.prepareWatch(next)) return false // Includes peer absence and low battery.
        checkpoint(next)
        if (!runtime.stopPhone()) {
            checkpoint(current)
            runtime.startPhone() // Repair the phone's service/auto-connect flags after a partial stop.
            return false
        }
        runtime.startWatch(next)
        readings.reset()
        lastLocalPacketMs = null
        // Stay in WATCH_CONNECTING even on a missing ACK; a packet/status or deadline settles it.
        return true
    }

    private suspend fun finishWatch(current: RecoveryState, health: ReceiverHealth? = null) {
        runtime.stopPhone()
        runtime.watchOwnsSensor()
        readings.reset()
        lastLocalLifeCount = null
        lastLocalPacketMs = null
        checkpoint(current.copy(phase = RecoveryPhase.WATCH_MONITOR,
            deadlineMs = health?.let { watchCheckAt(it) } ?: (runtime.nowMs() + RecoveryPolicy.monitorDelayMs()),
            phoneReturnTrial = false))
    }

    /** Next look at a kept watch: one check window after its last packet, or when its claim ends. */
    private fun watchCheckAt(health: ReceiverHealth): Long = runtime.nowMs() + when {
        health.healthy -> RecoveryPolicy.monitorDelayMs(health.packetAgeMs)
        health.claimActive -> health.claimRemainingMs.coerceAtMost(RecoveryPolicy.FIRST_PACKET_MS)
        else -> RecoveryPolicy.monitorDelayMs()
    }

    private fun newState(sensor: String, nodeId: String, phase: RecoveryPhase, preferWatch: Boolean = true, deadline: Long) =
        RecoveryState(sensor, nodeId, phase, runtime.nextToken(), runtime.nextSequence(sequence).also { sequence = it }, preferWatch, deadline)

    private suspend fun checkpoint(value: RecoveryState?) {
        runtime.save(value)
        state = value
    }
}
