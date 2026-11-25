package re.abbot.librecr.app.broadcast

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import re.abbot.librecr.app.GLUCOSE_STALE_AFTER_MS
import re.abbot.librecr.app.data.AppSettings
import re.abbot.librecr.app.data.GlucoseBroadcastSettings
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.glucoseBroadcastDestinations
import re.abbot.librecr.app.widget.GlucoseWidgetInput

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GlucoseBroadcastTest {
    private val now = 1_800_000_000_000L
    private val reading = SensorStateStore.LastGlucose(120, 126, "FALLING", now - 120_000L, -1.5)
    private val enabled = GlucoseBroadcastSettings(enabled = true)
    private val sent = mutableListOf<Intent>()
    private val dispatcher = GlucoseBroadcastDispatcher { sent.add(it) }

    @Test
    fun `sharing is off by default and sends nothing until enabled`() {
        assertFalse(AppSettings().glucoseBroadcast.enabled)
        update(AppSettings().glucoseBroadcast)
        assertTrue(sent.isEmpty())
        update()
        assertEquals(1, sent.size)
    }

    @Test
    fun `xDrip wire types preserve mgdl original time and slope per millisecond in mmol mode`() {
        update(unit = GlucoseUnit.MMOL_L)
        val intent = sent.single()
        assertEquals("com.eveningoutpost.dexdrip.BgEstimate", intent.action)
        // Literal external keys and typed reads catch accidental API changes and Int versus Double extras.
        assertEquals(126.0, intent.getDoubleExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", -1.0), 0.0)
        assertEquals(reading.receivedAtMs, intent.getLongExtra("com.eveningoutpost.dexdrip.Extras.Time", -1))
        assertEquals(-1.5 / 60_000.0, intent.getDoubleExtra("com.eveningoutpost.dexdrip.Extras.BgSlope", 0.0), 1e-12)
        assertEquals("FortyFiveDown", intent.getStringExtra("com.eveningoutpost.dexdrip.Extras.BgSlopeName"))
        assertEquals("mmol", intent.getStringExtra("com.eveningoutpost.dexdrip.Extras.Display.Units"))
        assertEquals("re.abbot.librecr.app", intent.getStringExtra("com.eveningoutpost.dexdrip.Extras.Sender"))
        assertEquals("LibreCRDroid (Libre 3)", intent.getStringExtra("com.eveningoutpost.dexdrip.Extras.SourceDesc"))
        assertEquals(120, intent.getIntExtra("re.abbot.librecr.app.Extras.LifeCount", -1))
        assertEquals("FALLING", intent.getStringExtra("re.abbot.librecr.app.Extras.Trend"))
        assertEquals(-1.5, intent.getDoubleExtra("re.abbot.librecr.app.Extras.DeltaMgDlPerMinute", 0.0), 0.0)
        assertNull(intent.`package`)
    }

    @Test
    fun `all sensor trend categories have interoperable names without inventing missing deltas`() {
        val names = mapOf("FALLING_QUICKLY" to "SingleDown", "FALLING" to "FortyFiveDown",
            "STABLE" to "Flat", "RISING" to "FortyFiveUp", "RISING_QUICKLY" to "SingleUp",
            "NOT_DETERMINED" to "NOT COMPUTABLE", "UNKNOWN" to "NOT COMPUTABLE")
        names.forEach { (trend, name) ->
            val intent = GlucoseBroadcastContract.intent(reading.copy(trend = trend, deltaMgDlPerMin = null), GlucoseUnit.MG_DL)
            assertEquals(name, intent.getStringExtra(GlucoseBroadcastContract.SLOPE_NAME))
            assertFalse(intent.hasExtra(GlucoseBroadcastContract.SLOPE))
            assertFalse(intent.hasExtra(GlucoseBroadcastContract.DELTA_PER_MINUTE))
            assertEquals("mg/dl", intent.getStringExtra(GlucoseBroadcastContract.DISPLAY_UNITS))
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { delta ->
            val intent = GlucoseBroadcastContract.intent(reading.copy(deltaMgDlPerMin = delta), GlucoseUnit.MG_DL)
            assertFalse(intent.hasExtra(GlucoseBroadcastContract.SLOPE))
            assertFalse(intent.hasExtra(GlucoseBroadcastContract.DELTA_PER_MINUTE))
        }
    }

    @Test
    fun `stale future unavailable invalid and sensor error states never broadcast cached values`() {
        val invalid = listOf(
            GlucoseWidgetInput(),
            GlucoseWidgetInput(reading.copy(mgDL = 0)),
            GlucoseWidgetInput(reading.copy(mgDL = 501)),
            GlucoseWidgetInput(reading.copy(receivedAtMs = 0)),
            GlucoseWidgetInput(reading.copy(receivedAtMs = now + 1)),
            GlucoseWidgetInput(reading.copy(receivedAtMs = now - GLUCOSE_STALE_AFTER_MS)),
            GlucoseWidgetInput(reading, sensorError = true),
            GlucoseWidgetInput(reading, unavailableAtMs = now - 60_000L),
            GlucoseWidgetInput(warmupStartedAtMs = now - 60_000L),
        )
        invalid.forEach { update(input = it) }
        assertTrue(sent.isEmpty())
        update(input = GlucoseWidgetInput(reading.copy(receivedAtMs = now - GLUCOSE_STALE_AFTER_MS + 1)))
        assertEquals(1, sent.size)
    }

    @Test
    fun `unrelated updates do not duplicate readings but enabling and changing recipients resend original time`() {
        update()
        update()
        update(unit = GlucoseUnit.MMOL_L)
        assertEquals(1, sent.size)
        update(enabled.copy(destinationPackages = "com.example.display"))
        assertEquals("com.example.display", sent.last().`package`)
        update(enabled.copy(enabled = false))
        assertEquals(2, sent.size)
        update()
        assertEquals(3, sent.size)
        assertTrue(sent.all { it.getLongExtra(GlucoseBroadcastContract.TIMESTAMP, -1) == reading.receivedAtMs })
        // The next reading is sent, including when a later sensor happens to reuse this lifeCount.
        update(input = GlucoseWidgetInput(reading.copy(receivedAtMs = now - 60_000L)))
        assertEquals(4, sent.size)
    }

    @Test
    fun `destinations are deduplicated and malformed input never falls back to sharing with all apps`() {
        assertEquals(listOf("com.example.one", "com.example.two"),
            glucoseBroadcastDestinations(" com.example.one, com.example.two;\ncom.example.one "))
        assertEquals(emptyList<String>(), glucoseBroadcastDestinations(" \n "))
        listOf("bad-package", "com.example.good, invalid", "com..example", ", ;").forEach { value ->
            assertNull(glucoseBroadcastDestinations(value))
            update(enabled.copy(destinationPackages = value))
        }
        assertTrue(sent.isEmpty())
        update(enabled.copy(destinationPackages = "com.example.one,com.example.two;com.example.one"))
        assertEquals(listOf("com.example.one", "com.example.two"), sent.map { it.`package` })
        assertNotSame(sent[0], sent[1])
    }

    @Test
    fun `a platform send failure cannot stop other recipients or subsequent readings`() {
        var fail = true
        val dispatcher = GlucoseBroadcastDispatcher { intent ->
            if (fail) {
                fail = false
                throw SecurityException("test failure")
            }
            sent.add(intent)
        }
        val recipients = enabled.copy(destinationPackages = "com.example.one com.example.two")
        dispatcher.update(recipients, GlucoseWidgetInput(reading), GlucoseUnit.MG_DL, now)
        assertEquals("com.example.two", sent.single().`package`)
        dispatcher.update(recipients, GlucoseWidgetInput(reading.copy(lifeCount = 121, receivedAtMs = now)), GlucoseUnit.MG_DL, now)
        assertEquals(3, sent.size)
    }

    private fun update(
        settings: GlucoseBroadcastSettings = enabled,
        input: GlucoseWidgetInput = GlucoseWidgetInput(reading),
        unit: GlucoseUnit = GlucoseUnit.MG_DL,
    ) = dispatcher.update(settings, input, unit, now)
}
