package re.abbot.librecr.app.wear.complication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import re.abbot.librecr.app.WEAR_GLUCOSE_DISPLAY_HOLD_MS
import re.abbot.librecr.app.data.SensorStateStore

class GlucoseComplicationFreshnessTest {
    @Test
    fun `last glucose becomes stale at exactly 330 seconds`() {
        val now = 1_000_000L
        val reading = SensorStateStore.LastGlucose(
            lifeCount = 123,
            mgDL = 101,
            trend = "STABLE",
            receivedAtMs = now - 329_999L,
            deltaMgDlPerMin = 0.0,
        )

        assertTrue(GlucoseComplicationRenderer.isFresh(reading, now))
        assertFalse(GlucoseComplicationRenderer.isFresh(reading, now + 1L))
        assertEquals(330_000L, WEAR_GLUCOSE_DISPLAY_HOLD_MS)
    }

    @Test
    fun `missing and zero timestamp readings are never fresh`() {
        val zeroTimestamp = SensorStateStore.LastGlucose(
            lifeCount = 123,
            mgDL = 101,
            trend = "STABLE",
            receivedAtMs = 0L,
            deltaMgDlPerMin = null,
        )

        assertFalse(GlucoseComplicationRenderer.isFresh(null, nowMs = 1_000_000L))
        assertFalse(GlucoseComplicationRenderer.isFresh(zeroTimestamp, nowMs = 1_000_000L))
    }

    @Test
    fun `missing reading is unavailable rather than sensor error`() {
        assertEquals("--", GlucoseComplicationRenderer.valueText(null))
    }

    @Test
    fun `a sensor packet with no usable glucose is rendered as NA`() {
        assertEquals("NA", GlucoseComplicationRenderer.valueText(null, valueUnavailable = true))
    }

    @Test
    fun `age complication shows minutes inside the display window and the clock time after`() {
        val at = 1_000_000L
        assertEquals("<1 m.", GlucoseComplicationRenderer.ageText(at, at + 10_000L))
        assertEquals("2 m.", GlucoseComplicationRenderer.ageText(at, at + 125_000L))
        assertEquals("5 m.", GlucoseComplicationRenderer.ageText(at, at + 329_999L))
        // An expired sample needs no further repaint, so it shows its clock time again.
        val expired = SensorStateStore.LastGlucose(123, 101, "STABLE", at, 0.0)
        assertEquals(GlucoseComplicationRenderer.timeText(expired), GlucoseComplicationRenderer.ageText(at, at + 330_000L))
        assertEquals("--:--", GlucoseComplicationRenderer.ageText(0L, at))
    }
}
