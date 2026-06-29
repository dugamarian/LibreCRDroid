package re.abbot.librecr.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import re.abbot.librecr.app.alarm.GlucoseAlarmManager
import re.abbot.librecr.app.ble.SensorConnectionManager
import re.abbot.librecr.app.data.GlucoseHistoryStore
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.SettingsStore
import re.abbot.librecr.app.libreview.LibreViewUploader
import re.abbot.librecr.app.live.LiveUpdatesNotifier
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.app.ui.standby.StandbyController
import re.abbot.librecr.app.wear.WearDataSync

/**
 * Minimal process-wide graph so the foreground service (which owns the
 * connection loop) and the UI (which observes it) share one manager + stores.
 */
object LibreCR {
    @Volatile private var initialized = false
    lateinit var store: SensorStateStore
        private set
    lateinit var manager: SensorConnectionManager
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var history: GlucoseHistoryStore
        private set
    lateinit var uploader: LibreViewUploader
        private set

    /** Process-lifetime scope for work that must outlive any single screen (e.g. the watch handoff). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        history = GlucoseHistoryStore(app)
        store = SensorStateStore(app, history)
        manager = SensorConnectionManager(app, store)
        settings = SettingsStore(app)
        uploader = LibreViewUploader(app, settings, store)
        appScope.launch {
            store.clearCurrentGlucoseIfStale()
        }
        // Register the standby power receiver so a wireless-charger connection launches StandbyActivity
        // even when the main UI isn't foreground.
        StandbyController.init(app)
        // Per-reading processing keyed on the data funnel, not on a single transport: every reading
        // (phone BLE via the manager, or watch relay via PhoneWearListenerService) lands here. So
        // alarms and cloud upload work even when the phone's foreground service isn't the collector.
        appScope.launch {
            settings.settingsFlow
                .map { it.wearAppearance }
                .distinctUntilChanged()
                .collect { WearDataSync.sendAppearance(app, it) }
        }
        appScope.launch {
            combine(
                settings.settingsFlow,
                store.lastGlucoseFlow,
                store.sensorLifecycleFlow,
                store.sessionFlow,
                liveUpdateTicker(),
            ) { appSettings, reading, lifecycle, session, _ ->
                LiveUpdatesNotifier.State(appSettings.liveUpdates, appSettings.unit, reading, lifecycle, session)
            }.collect { state ->
                LiveUpdatesNotifier.update(app, state)
            }
        }
        appScope.launch {
            store.glucoseHistoryFlow.collect { history ->
                val r = history.lastOrNull() ?: return@collect
                if (r.mgDL !in 40..400) return@collect
                // Pass the recent series so persistent (sustained high/low) alarms can be evaluated.
                val samples = history.map { GlucoseSample(it.mgDL, it.receivedAtMs) }
                runCatching { GlucoseAlarmManager.onReading(app, r.mgDL, settings.current().alarms, samples) }
                uploader.uploadReading(r.mgDL, r.trend, r.lifeCount, r.receivedAtMs)
            }
        }
        initialized = true
    }

    private fun liveUpdateTicker() = flow {
        while (true) {
            emit(Unit)
            delay(30_000L)
        }
    }
}
