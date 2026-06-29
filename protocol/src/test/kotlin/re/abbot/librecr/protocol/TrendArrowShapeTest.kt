package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TrendArrowShapeTest {
    @Test
    fun resolvesCanonicalAliasesAndRawTrendNumbers() {
        assertEquals("FALLING_QUICKLY", TrendArrowShape.resolvedTrend("FallingQuickly"))
        assertEquals("RISING_QUICKLY", TrendArrowShape.resolvedTrend("5"))
        assertEquals(-45f, TrendArrowShape.rotationDegrees("up")!!, 0f)
    }

    @Test
    fun ignoresDeltaWhenSensorTrendIsUnavailable() {
        assertNull(TrendArrowShape.resolvedTrend("UNKNOWN", -0.6))
        assertNull(TrendArrowShape.rotationDegrees("UNKNOWN", -0.6))
        assertNull(TrendArrowShape.resolvedTrend(null, 0.4))
        assertEquals("STABLE", TrendArrowShape.resolvedTrend("STABLE", 3.0))
        assertNull(TrendArrowShape.rotationDegrees("UNKNOWN", null))
    }
}
