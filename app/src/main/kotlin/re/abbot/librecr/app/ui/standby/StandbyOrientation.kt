package re.abbot.librecr.app.ui.standby

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Tracks the physical pose on the main thread, independently of Android's display rotation. */
internal class StandbyOrientation(context: Context, private val onChanged: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var accelerometer: Sensor? = null
    private var lastSampleMs = -1L
    private var landscapeSinceMs: Long? = null
    private var landscape = false

    fun start(): Boolean {
        if (accelerometer != null) return false
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        if (!sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)) return false
        accelerometer = sensor
        return true
    }

    fun stop() {
        if (accelerometer != null) sensors?.unregisterListener(this)
        accelerometer = null
        lastSampleMs = -1L
        landscapeSinceMs = null
        landscape = false
    }

    fun isLandscape(): Boolean = landscape && isFresh(lastSampleMs)

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor !== accelerometer || event.values.size < 3) return
        val sampleMs = TimeUnit.NANOSECONDS.toMillis(event.timestamp)
        if (sampleMs <= lastSampleMs || !isFresh(sampleMs)) return

        val wasLandscape = landscape
        val wasFresh = isFresh(lastSampleMs)
        if (sampleMs - lastSampleMs > MAX_SAMPLE_AGE_MS) landscapeSinceMs = null
        lastSampleMs = sampleMs

        val mode = displays?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
        val naturallyLandscape = mode != null && mode.physicalWidth > mode.physicalHeight
        if (fitsLandscape(event.values, naturallyLandscape, wasLandscape)) {
            val since = landscapeSinceMs ?: sampleMs.also { landscapeSinceMs = it }
            landscape = wasLandscape || sampleMs - since >= LANDSCAPE_SETTLE_MS
        } else {
            landscapeSinceMs = null
            landscape = false
        }
        if (landscape != wasLandscape || (landscape && !wasFresh)) onChanged()
    }

    private fun isFresh(sampleMs: Long): Boolean =
        sampleMs >= 0L && SystemClock.elapsedRealtime() - sampleMs in 0L..MAX_SAMPLE_AGE_MS

    private fun fitsLandscape(values: FloatArray, naturallyLandscape: Boolean, settled: Boolean): Boolean {
        val (x, y, z) = values
        val screenPlane = x * x + y * y
        val outOfPlane = z * z
        // Ignore near-flat poses and readings dominated by motion rather than gravity.
        if (screenPlane * 4f <= outOfPlane || screenPlane + outOfPlane !in 36f..169f) return false
        val landscapeAxis = abs(if (naturallyLandscape) y else x)
        val portraitAxis = abs(if (naturallyLandscape) x else y)
        // tan(25°) to enter and tan(35°) to leave, avoiding oscillation near the edge.
        val maxRatio = if (settled) 0.70f else 0.47f
        return portraitAxis <= landscapeAxis * maxRatio
    }

    private companion object {
        const val LANDSCAPE_SETTLE_MS = 400L
        const val MAX_SAMPLE_AGE_MS = 1_000L
    }
}
