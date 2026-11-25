package re.abbot.librecr.protocol.connection

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhonePriorityPresenceTest {
    @Test fun `phone remaining nearby does not bypass a failed sensor return cooldown`() {
        repeat(10) { assertFalse(PhonePriorityPolicy.hasReturned(true, true)) }
    }

    @Test fun `initial nearby observation is not a phone returning after absence`() {
        assertFalse(PhonePriorityPolicy.hasReturned(null, true))
        assertFalse(PhonePriorityPolicy.hasReturned(null, false))
    }

    @Test fun `unknown presence from a failed transport query never proves phone return`() {
        assertFalse(PhonePriorityPolicy.hasReturned(true, null))
        assertFalse(PhonePriorityPolicy.hasReturned(false, null))
        assertFalse(PhonePriorityPolicy.hasReturned(null, null))
    }

    @Test fun `phone departure and repeated absent events keep the return cooldown`() {
        assertFalse(PhonePriorityPolicy.hasReturned(true, false))
        assertFalse(PhonePriorityPolicy.hasReturned(false, false))
    }

    @Test fun `a directly nearby phone after observed absence permits immediate retry`() {
        assertTrue(PhonePriorityPolicy.hasReturned(false, true))
    }

    @Test fun `nearby phone permits scheduling a return instead of a permanent presence veto`() {
        repeat(20) {
            assertEquals(PhonePriorityPolicy.WatchAction.REQUEST_PHONE,
                PhonePriorityPolicy.watchAction(true, true, true, ReceiverHealth(), waitForPhoneReturn = true))
        }
    }

    @Test fun `guard stays through departure and releases only when the phone reappears`() {
        var guarded = true
        if (PhonePriorityPolicy.hasReturned(true, false)) guarded = false
        assertEquals(PhonePriorityPolicy.WatchAction.WAIT,
            PhonePriorityPolicy.watchAction(true, true, false, ReceiverHealth(), guarded))
        if (PhonePriorityPolicy.hasReturned(false, true)) guarded = false
        assertEquals(PhonePriorityPolicy.WatchAction.REQUEST_PHONE,
            PhonePriorityPolicy.watchAction(true, true, true, ReceiverHealth(), guarded))
    }

    @Test fun `default return without failed nearby phone recovery remains available`() {
        assertEquals(PhonePriorityPolicy.WatchAction.REQUEST_PHONE,
            PhonePriorityPolicy.watchAction(true, true, true, ReceiverHealth()))
    }
}
