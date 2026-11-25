package re.abbot.librecr.protocol.connection

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhonePriorityLinkLossTest {
    @Test fun `stale or implausibly future link loss hints cannot wake a takeover`() {
        assertFalse(accepts(ageMs = 90_001))
        assertFalse(accepts(ageMs = -30_001))
    }

    @Test fun `hint reordered behind a newer live phone reading is ignored`() {
        assertFalse(accepts(lifeCount = 99, lastLifeCount = 100))
        assertFalse(accepts(lifeCount = -1, lastLifeCount = -1))
    }

    @Test fun `fresh matching hint applies only to the armed parked watch`() {
        assertTrue(accepts(ageMs = 0))
        assertTrue(accepts(ageMs = 90_000))
        assertTrue(accepts(ageMs = -30_000))
        assertFalse(accepts(armed = false))
        assertFalse(accepts(released = false))
        assertFalse(accepts(samePhone = false))
        assertFalse(accepts(sameSensor = false))
    }

    @Test fun `delayed phone packet at the loss boundary cannot park a recovering watch`() {
        assertFalse(PhonePriorityPolicy.isFreshPhoneRecovery(100, -1, 11_000, 100, 10_000))
        assertFalse(PhonePriorityPolicy.isFreshPhoneRecovery(101, -1, 9_000, 100, 10_000))
    }

    @Test fun `next phone sensor sample after the loss can park the pending watch`() {
        assertTrue(PhonePriorityPolicy.isFreshPhoneRecovery(101, -1, 11_000, 100, 10_000))
        assertFalse(PhonePriorityPolicy.isFreshPhoneRecovery(101, 101, 11_000, 100, 10_000))
    }

    private fun accepts(
        armed: Boolean = true,
        released: Boolean = true,
        samePhone: Boolean = true,
        sameSensor: Boolean = true,
        ageMs: Long = 1_000,
        lifeCount: Int = 100,
        lastLifeCount: Int = 100,
    ) = PhonePriorityPolicy.acceptsLinkLoss(armed, released, samePhone, sameSensor, ageMs, lifeCount, lastLifeCount)
}
