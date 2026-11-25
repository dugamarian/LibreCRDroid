package re.abbot.librecr.app.broadcast

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.data.GlucoseBroadcastSettings
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.glucoseBroadcastDestinations
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.widget.GlucoseWidgetInput
import re.abbot.librecr.app.widget.GlucoseWidgetUpdater
import re.abbot.librecr.app.widget.WidgetReadingStatus
import re.abbot.librecr.app.widget.glucoseWidgetPresentation

/** xDrip's public broadcast contract. Numeric glucose is always mg/dL, regardless of display unit. */
internal object GlucoseBroadcastContract {
    const val ACTION = "com.eveningoutpost.dexdrip.BgEstimate"
    const val GLUCOSE = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
    const val TIMESTAMP = "com.eveningoutpost.dexdrip.Extras.Time"
    const val SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope"
    const val SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName"
    const val SOURCE = "com.eveningoutpost.dexdrip.Extras.SourceDesc"
    const val SENDER = "com.eveningoutpost.dexdrip.Extras.Sender"
    const val DISPLAY_UNITS = "com.eveningoutpost.dexdrip.Extras.Display.Units"
    const val LIFE_COUNT = "re.abbot.librecr.app.Extras.LifeCount"
    const val TREND = "re.abbot.librecr.app.Extras.Trend"
    const val DELTA_PER_MINUTE = "re.abbot.librecr.app.Extras.DeltaMgDlPerMinute"

    fun intent(reading: SensorStateStore.LastGlucose, unit: GlucoseUnit): Intent = Intent(ACTION).apply {
        putExtra(GLUCOSE, reading.mgDL.toDouble())
        putExtra(TIMESTAMP, reading.receivedAtMs)
        putExtra(SLOPE_NAME, when (reading.trend) {
            "FALLING_QUICKLY" -> "SingleDown"
            "FALLING" -> "FortyFiveDown"
            "STABLE" -> "Flat"
            "RISING" -> "FortyFiveUp"
            "RISING_QUICKLY" -> "SingleUp"
            else -> "NOT COMPUTABLE"
        })
        putExtra(SOURCE, "GlucoRitm (Libre 3)")
        putExtra(SENDER, "re.abbot.librecr.app")
        putExtra(DISPLAY_UNITS, if (unit == GlucoseUnit.MG_DL) "mg/dl" else "mmol")
        putExtra(LIFE_COUNT, reading.lifeCount)
        putExtra(TREND, reading.trend)
        reading.deltaMgDlPerMin?.takeIf { it.isFinite() }?.let { delta ->
            // xDrip BgSlope is mg/dL per MILLISECOND; our stored delta is per sensor minute.
            putExtra(SLOPE, delta / 60_000.0)
            putExtra(DELTA_PER_MINUTE, delta)
        }
    }
}

/** Serialized by its collector. Deduplication includes the timestamp so a new sensor can restart lifeCount. */
internal class GlucoseBroadcastDispatcher(private val send: (Intent) -> Unit) {
    private var previousSettings: GlucoseBroadcastSettings? = null
    private var lastReading: Pair<Int, Long>? = null

    fun update(settings: GlucoseBroadcastSettings, input: GlucoseWidgetInput, unit: GlucoseUnit, nowMs: Long) {
        if (settings != previousSettings) {
            previousSettings = settings
            lastReading = null
        }
        if (!settings.enabled) return
        val destinations = glucoseBroadcastDestinations(settings.destinationPackages) ?: return
        // Use the same validity, freshness and sensor-status rules as the phone's other displays.
        if (glucoseWidgetPresentation(input, nowMs).status != WidgetReadingStatus.CURRENT) return
        val reading = input.reading ?: return
        val key = reading.lifeCount to reading.receivedAtMs
        if (key == lastReading) return
        val intent = GlucoseBroadcastContract.intent(reading, unit)
        var sent = false
        // A package-targeted broadcast also reaches manifest receivers on Android 8+.
        for (destination in destinations.ifEmpty { listOf(null) }) {
            try {
                send(Intent(intent).setPackage(destination))
                sent = true
            } catch (error: RuntimeException) {
                // A recipient or platform failure must not stop future readings or other recipients.
                BleLog.log("broadcast: send failed: ${error.javaClass.simpleName}")
            }
        }
        if (sent) lastReading = key
    }
}

/** Runs for the process lifetime, including phone BLE and readings relayed from Wear OS. */
object GlucoseBroadcast {
    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val dispatcher = GlucoseBroadcastDispatcher { app.sendBroadcast(it) }
        GlucoRitm.appScope.launch {
            combine(
                GlucoRitm.settings.settingsFlow.map { it.glucoseBroadcast to it.unit }.distinctUntilChanged(),
                GlucoseWidgetUpdater.inputs(),
            ) { (settings, unit), input -> Triple(settings, unit, input) }
                .collect { (settings, unit, input) ->
                    dispatcher.update(settings, input, unit, System.currentTimeMillis())
                }
        }
    }
}
