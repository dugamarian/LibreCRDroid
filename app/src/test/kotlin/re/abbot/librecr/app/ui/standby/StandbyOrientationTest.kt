package re.abbot.librecr.app.ui.standby

import android.app.Application
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-port")
class StandbyOrientationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val sensorManager = shadowOf(context.getSystemService(SensorManager::class.java)!!)
    private val accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
    private val changes = mutableListOf<Boolean>()
    private val orientation = StandbyOrientation(context, ::recordChange)

    @Test
    fun `portrait stays ineligible with the display rotated to landscape`() {
        val display = context.getSystemService(DisplayManager::class.java)!!.getDisplay(Display.DEFAULT_DISPLAY)
        shadowOf(display).setRotation(Surface.ROTATION_90)
        startMonitoring()
        report(y = 9.8f)
        advance(600)
        report(y = 9.8f)
        assertFalse(orientation.isLandscape())
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `both sides qualify after settling and standing upright ends standby`() {
        startMonitoring()
        report(x = 9.8f)
        advance(200)
        report(x = 9.8f)
        assertFalse(orientation.isLandscape())
        advance(200)
        report(x = 9.8f)
        assertTrue(orientation.isLandscape())

        advance(100)
        report(y = 9.8f)
        assertFalse(orientation.isLandscape())

        advance(100)
        report(x = -8f, z = 5.5f)
        advance(400)
        report(x = -8f, z = 5.5f)
        assertEquals(listOf(true, false, true), changes)
    }

    @Test
    fun `flat or transient poses do not qualify`() {
        startMonitoring()
        report(z = 9.8f)
        advance(600)
        report(z = 9.8f)
        advance(100)
        report(x = 9.8f)
        advance(200)
        report(x = 7f, y = 7f)
        advance(600)
        report(x = 9.8f)
        assertFalse(orientation.isLandscape())
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `stale readings and a previous charging session cannot authorize launch`() {
        startMonitoring()
        report(x = 9.8f)
        advance(400)
        report(x = 9.8f)
        assertTrue(orientation.isLandscape())
        advance(1_100)
        assertFalse(orientation.isLandscape())

        orientation.stop()
        assertFalse(sensorManager.hasListener(orientation))
        orientation.start()
        report(x = 9.8f)
        assertFalse(orientation.isLandscape())
    }

    @Test
    fun `an activity without a valid launch finishes before forcing landscape or keeping the screen on`() {
        val controller = Robolectric.buildActivity(StandbyActivity::class.java).create()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.destroy()
    }

    private fun startMonitoring() {
        sensorManager.addSensor(accelerometer)
        orientation.start()
    }

    private fun report(x: Float = 0f, y: Float = 0f, z: Float = 0f) {
        val event = SensorEventBuilder.newBuilder()
            .setSensor(accelerometer)
            .setValues(floatArrayOf(x, y, z))
            .setTimestamp(SystemClock.elapsedRealtimeNanos())
            .build()
        sensorManager.sendSensorEventToListeners(event, accelerometer)
    }

    private fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))

    private fun recordChange() {
        changes.add(orientation.isLandscape())
    }
}
