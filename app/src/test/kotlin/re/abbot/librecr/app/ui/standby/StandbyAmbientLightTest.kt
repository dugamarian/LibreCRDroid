package re.abbot.librecr.app.ui.standby

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StandbyAmbientLightTest {
    private val context = RuntimeEnvironment.getApplication()
    private val sensorManager = shadowOf(context.getSystemService(SensorManager::class.java)!!)
    private val lightSensor = ShadowSensor.newInstance(Sensor.TYPE_LIGHT)
    private val modes = mutableListOf<StandbyAmbientMode>()
    private val ambientLight = StandbyAmbientLight(context) { modes.add(it) }

    @Test
    fun `first valid reading applies immediately and overrides the schedule`() {
        sensorManager.addSensor(lightSensor)
        ambientLight.start()
        reportLux(1f)
        assertTrue(modes.single().isNight(atLocalTime(12, 0), 22 * 60, 7 * 60))

        ambientLight.stop()
        ambientLight.start()
        reportLux(100f)
        assertFalse(modes.last().isNight(atLocalTime(23, 0), 22 * 60, 7 * 60))
    }

    @Test
    fun `short changes and readings between thresholds keep the current palette`() {
        sensorManager.addSensor(lightSensor)
        ambientLight.start()
        reportLux(40f)
        reportLux(2f)
        advance(1_000)
        reportLux(5f)
        advance(2_000)
        assertEquals(listOf(StandbyAmbientMode.DAY), modes)

        reportLux(3f)
        advance(1_000)
        reportLux(0f)
        advance(1_000)
        assertEquals(StandbyAmbientMode.NIGHT, modes.last())

        reportLux(7f)
        advance(2_000)
        assertEquals(StandbyAmbientMode.NIGHT, modes.last())
        reportLux(8f)
        advance(2_000)
        assertEquals(StandbyAmbientMode.DAY, modes.last())
    }

    @Test
    fun `a missing sensor uses the configured window across midnight`() {
        ambientLight.start()
        val mode = modes.single()
        assertEquals(StandbyAmbientMode.SCHEDULE, mode)
        assertFalse(mode.isNight(atLocalTime(21, 59), 22 * 60, 7 * 60))
        assertTrue(mode.isNight(atLocalTime(22, 0), 22 * 60, 7 * 60))
        assertTrue(mode.isNight(atLocalTime(6, 59), 22 * 60, 7 * 60))
        assertFalse(mode.isNight(atLocalTime(7, 0), 22 * 60, 7 * 60))
    }

    @Test
    fun `a delayed first reading replaces the schedule and stopping cancels transitions`() {
        sensorManager.addSensor(lightSensor)
        ambientLight.start()
        reportLux(Float.NaN)
        advance(1_500)
        assertEquals(StandbyAmbientMode.SCHEDULE, modes.last())

        reportLux(20f)
        assertEquals(StandbyAmbientMode.DAY, modes.last())
        reportLux(1f)
        ambientLight.stop()
        assertFalse(sensorManager.hasListener(ambientLight))
        advance(2_000)
        assertEquals(listOf(StandbyAmbientMode.SCHEDULE, StandbyAmbientMode.DAY), modes)
    }

    private fun reportLux(lux: Float) {
        val event = SensorEventBuilder.newBuilder().setSensor(lightSensor).setValues(floatArrayOf(lux)).build()
        sensorManager.sendSensorEventToListeners(event, lightSensor)
    }

    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))

    private fun atLocalTime(hour: Int, minute: Int): Long = LocalDateTime.of(2026, 1, 15, hour, minute)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
