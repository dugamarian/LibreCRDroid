package re.abbot.librecr.protocol.connection

/** Phone priority is opt-in; nearby connectivity permits a bounded trial, not sensor ownership. */
object PhonePriorityPolicy {
    const val ARM_WATCH = "/librecr/automatic_handoff/arm_phone_priority"
    const val PHONE_CAPABILITY = "librecr_phone_sensor_receiver"
    const val PHONE_CGM_LINK_LOST = "/librecr/automatic_handoff/phone_cgm_link_lost"
    const val PRESENCE_CHECK_MS = 15_000L
    const val ARM_RETRY_MS = 30_000L
    const val RETURN_TRIAL_MS = RecoveryPolicy.FIRST_PACKET_MS
    const val RETURN_LEASE_MS = RETURN_TRIAL_MS + 30_000L
    const val RETURN_CLOCK_SKEW_MS = 30_000L
    const val MAX_RETURN_RETRY_MS = 1_800_000L

    enum class WatchAction { WAIT, START_WATCH, REQUEST_PHONE }

    /** An initial or repeated nearby result is not evidence that an absent phone returned. */
    fun hasReturned(previousNearby: Boolean?, nearby: Boolean?): Boolean = previousNearby == false && nearby == true

    fun returnRetryDelay(attempt: Int): Long = RecoveryPolicy.failedTransferDelay(attempt)

    /** An unchanged nearby phone can retry after cooldown, once the watch actually collects. */
    fun shouldTryPhoneReturn(
        armed: Boolean,
        watchOwnsSensor: Boolean,
        phoneNearby: Boolean,
        hasFreshLocalPacket: Boolean,
        nextAttemptAtMs: Long,
        nowMs: Long,
    ): Boolean = armed && watchOwnsSensor && phoneNearby && hasFreshLocalPacket && nowMs >= nextAttemptAtMs

    fun returnLeaseExpired(leaseUntilMs: Long, nowMs: Long): Boolean = leaseUntilMs > 0L && nowMs >= leaseUntilMs

    /** Accept small peer-clock differences without granting more than the local bounded lease. */
    fun receivedReturnLeaseMs(deadlineWallMs: Long, nowWallMs: Long): Long? {
        val remaining = try { Math.subtractExact(deadlineWallMs, nowWallMs) }
        catch (_: ArithmeticException) { return null }
        return remaining.takeIf { it in 1L..RETURN_LEASE_MS + RETURN_CLOCK_SKEW_MS }
            ?.coerceAtMost(RETURN_LEASE_MS)
    }

    /** The service startup check must not reinstate a claim whose trial lease already expired. */
    fun mayStartAfterReturnTrial(healthy: Boolean, claimActive: Boolean, reclaimPending: Boolean): Boolean =
        !healthy && (reclaimPending || !claimActive)

    fun restoreReturnRetryAt(savedWallMs: Long, wallMs: Long, elapsedMs: Long): Long =
        restoreReturnDeadline(savedWallMs, wallMs, elapsedMs, MAX_RETURN_RETRY_MS)

    fun restoreReturnTrialUntil(savedWallMs: Long, wallMs: Long, elapsedMs: Long): Long =
        if (savedWallMs <= 0L) 0L else restoreReturnDeadline(savedWallMs, wallMs, elapsedMs, RETURN_TRIAL_MS)

    fun restoreReturnLeaseUntil(savedWallMs: Long, wallMs: Long, elapsedMs: Long): Long =
        if (savedWallMs <= 0L) 0L else restoreReturnDeadline(savedWallMs, wallMs, elapsedMs, RETURN_LEASE_MS)

    private fun restoreReturnDeadline(savedWallMs: Long, wallMs: Long, elapsedMs: Long, maximumMs: Long): Long {
        val remaining = if (savedWallMs <= wallMs) 0L else {
            val difference = savedWallMs - wallMs
            // A corrupt/extreme future wall time must not overflow into an immediately expired lease.
            if (difference < 0L) maximumMs else difference.coerceAtMost(maximumMs)
        }
        return if (elapsedMs > Long.MAX_VALUE - remaining) Long.MAX_VALUE else elapsedMs + remaining
    }

    /** Live hints cannot override another route, sensor, source, or a newer phone packet. */
    fun acceptsLinkLoss(
        armed: Boolean,
        released: Boolean,
        samePhone: Boolean,
        sameSensor: Boolean,
        ageMs: Long,
        lifeCount: Int,
        lastLifeCount: Int,
    ): Boolean = armed && released && samePhone && sameSensor &&
        ageMs in -30_000L..90_000L && lifeCount >= 0 && lifeCount >= lastLifeCount

    fun isFreshPhoneRecovery(
        lifeCount: Int,
        lastLocalLifeCount: Int,
        receivedAtMs: Long,
        linkLossLifeCount: Int,
        linkLossAtMs: Long,
    ): Boolean = lifeCount > maxOf(lastLocalLifeCount, linkLossLifeCount) && receivedAtMs > linkLossAtMs

    @Suppress("UNUSED_PARAMETER") // Kept for callers migrating from the former permanent return veto.
    fun watchAction(
        armed: Boolean,
        watchOwnsSensor: Boolean,
        phoneNearby: Boolean,
        phone: ReceiverHealth,
        waitForPhoneReturn: Boolean = false,
    ): WatchAction = when {
        !armed -> WatchAction.WAIT
        watchOwnsSensor -> if (phoneNearby) WatchAction.REQUEST_PHONE else WatchAction.WAIT
        !phoneNearby -> WatchAction.START_WATCH
        phone.healthy || phone.claimActive -> WatchAction.WAIT
        else -> WatchAction.START_WATCH
    }
}
