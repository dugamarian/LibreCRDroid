package re.abbot.librecr.protocol.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhonePriorityReturnScheduleTest {
    @Test fun `received return lease accepts small positive and negative phone clock differences`() {
        val now = 1_000_000L
        assertEquals(180_000L, PhonePriorityPolicy.receivedReturnLeaseMs(now + 180_000L + 646L, now))
        assertEquals(179_354L, PhonePriorityPolicy.receivedReturnLeaseMs(now + 180_000L - 646L, now))
        assertEquals(170_646L, PhonePriorityPolicy.receivedReturnLeaseMs(now + 170_000L + 646L, now))
    }

    @Test fun `clock skew tolerance never grants an extended local return lease`() {
        val now = 1_000_000L
        val maximumAccepted = PhonePriorityPolicy.RETURN_LEASE_MS + PhonePriorityPolicy.RETURN_CLOCK_SKEW_MS
        assertEquals(180_000L, PhonePriorityPolicy.receivedReturnLeaseMs(now + maximumAccepted, now))
        assertNull(PhonePriorityPolicy.receivedReturnLeaseMs(now + maximumAccepted + 1L, now))
    }

    @Test fun `expired received return leases are rejected rather than renewed`() {
        assertNull(PhonePriorityPolicy.receivedReturnLeaseMs(1_000_000L, 1_000_000L))
        assertNull(PhonePriorityPolicy.receivedReturnLeaseMs(999_999L, 1_000_000L))
        assertEquals(1L, PhonePriorityPolicy.receivedReturnLeaseMs(1_000_001L, 1_000_000L))
    }

    @Test fun `extreme peer deadlines and arithmetic overflow cannot create a return lease`() {
        assertNull(PhonePriorityPolicy.receivedReturnLeaseMs(Long.MAX_VALUE, 1_000_000L))
        assertNull(PhonePriorityPolicy.receivedReturnLeaseMs(Long.MAX_VALUE, Long.MIN_VALUE))
        assertNull(PhonePriorityPolicy.receivedReturnLeaseMs(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test fun `failed return attempts back off from three minutes to a thirty minute cap`() {
        assertEquals(listOf(180_000L, 360_000L, 720_000L, 1_440_000L, 1_800_000L, 1_800_000L),
            (0..5).map(PhonePriorityPolicy::returnRetryDelay))
        assertEquals(180_000L, PhonePriorityPolicy.returnRetryDelay(-1))
        assertEquals(1_800_000L, PhonePriorityPolicy.returnRetryDelay(Int.MAX_VALUE))
    }

    @Test fun `a continuously nearby phone becomes eligible when the cooldown expires`() {
        val nextAttempt = 1_000_000L + PhonePriorityPolicy.returnRetryDelay(0)
        assertFalse(PhonePriorityPolicy.hasReturned(true, true))
        assertEquals(PhonePriorityPolicy.WatchAction.REQUEST_PHONE,
            PhonePriorityPolicy.watchAction(true, true, true, ReceiverHealth(), waitForPhoneReturn = true))
        assertFalse(due(nowMs = nextAttempt - 1, nextAttemptAtMs = nextAttempt))
        assertTrue(due(nowMs = nextAttempt, nextAttemptAtMs = nextAttempt))
    }

    @Test fun `repeated nearby callbacks and first watch packets cannot bypass a saved cooldown`() {
        val nextAttempt = 1_000_000L + PhonePriorityPolicy.returnRetryDelay(1)
        repeat(24) { minuteQuarter ->
            assertFalse(PhonePriorityPolicy.hasReturned(true, true))
            assertFalse(due(nowMs = 1_000_000L + minuteQuarter * 15_000L, nextAttemptAtMs = nextAttempt))
        }
        assertTrue(due(nowMs = nextAttempt, nextAttemptAtMs = nextAttempt))
    }

    @Test fun `trial requests need armed ownership a nearby phone and a fresh local watch packet`() {
        assertTrue(due())
        assertFalse(due(armed = false))
        assertFalse(due(watchOwnsSensor = false))
        assertFalse(due(phoneNearby = false))
        assertFalse(due(hasFreshLocalPacket = false))
        assertFalse(due(hasFreshLocalPacket = false, nowMs = Long.MAX_VALUE))
    }

    @Test fun `phone connection trial is bounded and the watch lease includes rollback grace`() {
        assertEquals(150_000L, PhonePriorityPolicy.RETURN_TRIAL_MS)
        assertEquals(180_000L, PhonePriorityPolicy.RETURN_LEASE_MS)
        assertFalse(PhonePriorityPolicy.returnLeaseExpired(0L, 1_000_000L))
        assertFalse(PhonePriorityPolicy.returnLeaseExpired(1_180_000L, 1_179_999L))
        assertTrue(PhonePriorityPolicy.returnLeaseExpired(1_180_000L, 1_180_000L))
    }

    @Test fun `service reconciliation cannot reapply a phone claim after the return lease expires`() {
        val leaseUntil = 1_180_000L
        assertFalse(PhonePriorityPolicy.mayStartAfterReturnTrial(healthy = false, claimActive = true,
            reclaimPending = PhonePriorityPolicy.returnLeaseExpired(leaseUntil, leaseUntil - 1)))
        assertTrue(PhonePriorityPolicy.mayStartAfterReturnTrial(healthy = false, claimActive = true,
            reclaimPending = PhonePriorityPolicy.returnLeaseExpired(leaseUntil, leaseUntil)))
    }

    @Test fun `a confirmed healthy phone still prevents watch startup after trial expiry`() {
        for (claim in listOf(false, true)) {
            assertFalse(PhonePriorityPolicy.mayStartAfterReturnTrial(healthy = true, claimActive = claim, reclaimPending = true))
        }
        assertTrue(PhonePriorityPolicy.mayStartAfterReturnTrial(healthy = false, claimActive = false, reclaimPending = false))
    }

    @Test fun `restart preserves remaining return cooldown rather than restarting or dropping it`() {
        val wall = 10_000_000L
        val saved = wall + PhonePriorityPolicy.returnRetryDelay(2)
        val restored = PhonePriorityPolicy.restoreReturnRetryAt(saved, wall + 120_000L, 5_000L)
        assertEquals(605_000L, restored)
        assertFalse(due(nextAttemptAtMs = restored, nowMs = 604_999L))
        assertTrue(due(nextAttemptAtMs = restored, nowMs = 605_000L))
    }

    @Test fun `expired saved deadlines remain expired after restart`() {
        assertEquals(5_000L, PhonePriorityPolicy.restoreReturnRetryAt(9_000L, 10_000L, 5_000L))
        assertEquals(5_000L, PhonePriorityPolicy.restoreReturnTrialUntil(9_000L, 10_000L, 5_000L))
        assertEquals(5_000L, PhonePriorityPolicy.restoreReturnLeaseUntil(9_000L, 10_000L, 5_000L))
    }

    @Test fun `missing trial and lease deadlines stay absent after restart`() {
        assertEquals(0L, PhonePriorityPolicy.restoreReturnTrialUntil(0L, 10_000L, 5_000L))
        val lease = PhonePriorityPolicy.restoreReturnLeaseUntil(0L, 10_000L, 5_000L)
        assertEquals(0L, lease)
        assertFalse(PhonePriorityPolicy.returnLeaseExpired(lease, 5_000L))
    }

    @Test fun `clock changes cannot create unbounded return trials leases or retry delays`() {
        assertEquals(5_000L + PhonePriorityPolicy.MAX_RETURN_RETRY_MS,
            PhonePriorityPolicy.restoreReturnRetryAt(Long.MAX_VALUE, 10_000L, 5_000L))
        assertEquals(5_000L + PhonePriorityPolicy.RETURN_TRIAL_MS,
            PhonePriorityPolicy.restoreReturnTrialUntil(Long.MAX_VALUE, 10_000L, 5_000L))
        assertEquals(5_000L + PhonePriorityPolicy.RETURN_LEASE_MS,
            PhonePriorityPolicy.restoreReturnLeaseUntil(Long.MAX_VALUE, 10_000L, 5_000L))
        assertEquals(5_000L + PhonePriorityPolicy.RETURN_LEASE_MS,
            PhonePriorityPolicy.restoreReturnLeaseUntil(Long.MAX_VALUE, -10_000L, 5_000L))
    }

    private fun due(
        armed: Boolean = true,
        watchOwnsSensor: Boolean = true,
        phoneNearby: Boolean = true,
        hasFreshLocalPacket: Boolean = true,
        nextAttemptAtMs: Long = 1_000_000L,
        nowMs: Long = 1_000_000L,
    ) = PhonePriorityPolicy.shouldTryPhoneReturn(armed, watchOwnsSensor, phoneNearby,
        hasFreshLocalPacket, nextAttemptAtMs, nowMs)
}
