package re.abbot.librecr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorReconnectPolicyTest {
    @Test
    fun `supervision timeout is recognised under both Android status codes`() {
        assertTrue(isLinkSupervisionTimeout(8))
        assertTrue(isLinkSupervisionTimeout(147))
        assertFalse(isLinkSupervisionTimeout(19))
        assertFalse(isLinkSupervisionTimeout(133))
        assertFalse(isLinkSupervisionTimeout(null))
    }

    @Test
    fun `a lost established link never arms the bounded direct connect`() {
        for (status in listOf(8, 19, 62, 133, 147)) {
            assertFalse(shouldRetryWithDirectConnect(autoConnect = true, status = status, linkWasEstablished = true))
        }
    }

    @Test
    fun `only an establishment failure of a controller connect escapes to one direct attempt`() {
        assertTrue(shouldRetryWithDirectConnect(autoConnect = true, status = 133, linkWasEstablished = false))
        assertTrue(shouldRetryWithDirectConnect(autoConnect = true, status = 62, linkWasEstablished = false))
        assertFalse(shouldRetryWithDirectConnect(autoConnect = true, status = 147, linkWasEstablished = false))
        assertFalse(shouldRetryWithDirectConnect(autoConnect = true, status = 8, linkWasEstablished = false))
        assertFalse(shouldRetryWithDirectConnect(autoConnect = false, status = 133, linkWasEstablished = false))
    }

    @Test
    fun `every attempt after the cold one is handed to the controller without a deadline`() {
        assertFalse(shouldUseControllerAutoConnect(reconnectAttempt = 0, directFallbackArmed = false))
        assertTrue(shouldUseControllerAutoConnect(reconnectAttempt = 1, directFallbackArmed = false))
        assertFalse(shouldUseControllerAutoConnect(reconnectAttempt = 2, directFallbackArmed = true))
        assertNull(sensorConnectTimeoutMs(autoConnect = true, directTimeoutMs = 20_000L))
        assertEquals(20_000L, sensorConnectTimeoutMs(autoConnect = false, directTimeoutMs = 20_000L))
    }

    @Test
    fun `pause before a controller connect is short and held awake`() {
        assertEquals(ReconnectBackoff(500L, keepAwake = true), reconnectBackoff(1, controllerRearm = true))
        for (attempt in 1..PRECISE_REARM_ATTEMPTS) {
            val backoff = reconnectBackoff(attempt, controllerRearm = true)
            assertTrue(backoff.keepAwake)
            assertTrue(backoff.delayMs <= PRECISE_REARM_MAX_DELAY_MS)
        }
    }

    @Test
    fun `persistent failures and the scan path fall back to the long unlocked ladder`() {
        assertEquals(ReconnectBackoff(30_000L, keepAwake = false), reconnectBackoff(7, controllerRearm = true))
        assertEquals(ReconnectBackoff(500L, keepAwake = false), reconnectBackoff(1, controllerRearm = false))
        assertEquals(ReconnectBackoff(30_000L, keepAwake = false), reconnectBackoff(50, controllerRearm = false))
    }

    @Test
    fun `wake locks cover the re-arm stretch`() {
        assertTrue(LINK_LOSS_REARM_WAKE_LOCK_TIMEOUT_MS >= PRECISE_REARM_MAX_DELAY_MS + REARM_WAKE_LOCK_MARGIN_MS)
        assertTrue(CONNECTION_ESTABLISH_WAKE_LOCK_TIMEOUT_MS >= 45_000L + 3 * 5_000L + 5_000L)
    }
}
