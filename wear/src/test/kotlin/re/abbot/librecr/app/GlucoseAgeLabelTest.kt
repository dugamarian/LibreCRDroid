package re.abbot.librecr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlucoseAgeLabelTest {
    private val at = 1_000_000L

    @Test
    fun `labels floor the elapsed minutes and clock skew never makes them negative`() {
        assertEquals("<1 m.", glucoseAgeLabel(at, at + 59_999L))
        assertEquals("1 m.", glucoseAgeLabel(at, at + 60_000L))
        assertEquals("5 m.", glucoseAgeLabel(at, at + 329_999L))
        assertEquals("<1 m.", glucoseAgeLabel(at, at - 10_000L))
    }

    @Test
    fun `wake-ups come 10 s after the first minute then each minute then at the expiry`() {
        val changes = generateSequence(at) { t -> nextGlucoseAgeRefreshDelayMs(at, t)?.let { t + it } }
            .drop(1).map { it - at }.toList()
        assertEquals(listOf(70_000L, 120_000L, 180_000L, 240_000L, 300_000L, 330_000L), changes)
        // A reading that lands up to 10 s late replaces "<1 m." before any wake-up is due.
        assertEquals(5_000L, nextGlucoseAgeLabelDelayMs(at, at + 65_000L))
        assertNull(nextGlucoseAgeLabelDelayMs(at, at + 300_000L))
        assertNull(nextGlucoseAgeRefreshDelayMs(at, at + WEAR_GLUCOSE_DISPLAY_HOLD_MS))
        assertNull(nextGlucoseAgeRefreshDelayMs(0L, at))
    }
}
