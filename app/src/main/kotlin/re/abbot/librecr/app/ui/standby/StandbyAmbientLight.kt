package re.abbot.librecr.app.ui.standby

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import re.abbot.librecr.app.alarm.AlarmEvaluator
import java.time.Instant
import java.time.ZoneId

internal enum class StandbyAmbientMode {
    WAITING, DAY, NIGHT, SCHEDULE;

    fun isNight(nowMs: Long, startMinutes: Int, endMinutes: Int): Boolean = when (this) {
        NIGHT -> true
        SCHEDULE -> {
            val time = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
            AlarmEvaluator.inWindow(time.hour * 60 + time.minute, startMinutes, endMinutes)
        }
        else -> false
    }
}

/** Lives on the main thread and samples light only while the StandBy activity is resumed. */
internal class StandbyAmbientLight(context: Context, private val onMode: (StandbyAmbientMode) -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var mode = StandbyAmbientMode.WAITING
    private var pendingMode: StandbyAmbientMode? = null
    private var listening = false

    private val useSchedule = Runnable { setMode(StandbyAmbientMode.SCHEDULE) }
    private val commitTransition = Runnable { pendingMode?.let(::setMode) }

    fun start() {
        setMode(StandbyAmbientMode.WAITING)
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        listening = sensor != null && sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        if (listening) {
            handler.postDelayed(useSchedule, FIRST_READING_TIMEOUT_MS)
        } else {
            setMode(StandbyAmbientMode.SCHEDULE)
        }
    }

    fun stop() {
        listening = false
        sensorManager?.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        pendingMode = null
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!listening) return
        val lux = event.values.firstOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: return
        handler.removeCallbacks(useSchedule)

        // A separate exit threshold keeps the current palette when light is close to the boundary.
        val next = when {
            lux <= NIGHT_ENTER_LUX -> StandbyAmbientMode.NIGHT
            lux >= NIGHT_EXIT_LUX -> StandbyAmbientMode.DAY
            mode == StandbyAmbientMode.NIGHT -> StandbyAmbientMode.NIGHT
            else -> StandbyAmbientMode.DAY
        }
        if (next == pendingMode) return
        handler.removeCallbacks(commitTransition)
        pendingMode = null
        if (next == mode) return

        if (mode == StandbyAmbientMode.WAITING || mode == StandbyAmbientMode.SCHEDULE) {
            setMode(next)
        } else {
            pendingMode = next
            handler.postDelayed(commitTransition, LIGHT_SETTLE_MS)
        }
    }

    private fun setMode(newMode: StandbyAmbientMode) {
        pendingMode = null
        if (newMode == mode) return
        mode = newMode
        onMode(newMode)
    }

    private companion object {
        const val NIGHT_ENTER_LUX = 3f
        const val NIGHT_EXIT_LUX = 8f
        const val LIGHT_SETTLE_MS = 2_000L
        const val FIRST_READING_TIMEOUT_MS = 1_500L
    }
}
