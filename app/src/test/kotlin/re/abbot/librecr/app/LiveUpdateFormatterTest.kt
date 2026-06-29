package re.abbot.librecr.app

import org.junit.Assert.assertEquals
import org.junit.Test
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.LiveUpdateSettings
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.live.LiveUpdateFormatter

class LiveUpdateFormatterTest {
    private val reading = SensorStateStore.LastGlucose(
        lifeCount = 100,
        mgDL = 120,
        trend = "STABLE",
        receivedAtMs = 1_700_000_000_000L,
        deltaMgDlPerMin = 2.0,
    )

    @Test
    fun chipTextKeepsArrowOutOfTextBecauseItIsRenderedAsBitmap() {
        assertEquals("120 +2", LiveUpdateFormatter.chipText(reading, GlucoseUnit.MG_DL, LiveUpdateSettings()))
        assertEquals("-> 120 +2", LiveUpdateFormatter.chipPreviewText(reading, GlucoseUnit.MG_DL, LiveUpdateSettings()))
    }

    @Test
    fun chipDeltaCanBeHidden() {
        val settings = LiveUpdateSettings(showDeltaInChip = false)
        assertEquals("120", LiveUpdateFormatter.chipText(reading, GlucoseUnit.MG_DL, settings))
        assertEquals("-> 120", LiveUpdateFormatter.chipPreviewText(reading, GlucoseUnit.MG_DL, settings))
    }

    @Test
    fun chipTrendCanBeHidden() {
        val settings = LiveUpdateSettings(showTrendInChip = false)
        assertEquals("120 +2", LiveUpdateFormatter.chipText(reading, GlucoseUnit.MG_DL, settings))
        assertEquals("120 +2", LiveUpdateFormatter.chipPreviewText(reading, GlucoseUnit.MG_DL, settings))
    }

    @Test
    fun chipUsesOnlySensorTrendWhenTrendIsUnknown() {
        val unknownTrend = reading.copy(trend = "UNKNOWN", deltaMgDlPerMin = -1.0)
        assertEquals("120 -1", LiveUpdateFormatter.chipText(unknownTrend, GlucoseUnit.MG_DL, LiveUpdateSettings()))
        assertEquals("120 -1", LiveUpdateFormatter.chipPreviewText(unknownTrend, GlucoseUnit.MG_DL, LiveUpdateSettings()))
    }
}
