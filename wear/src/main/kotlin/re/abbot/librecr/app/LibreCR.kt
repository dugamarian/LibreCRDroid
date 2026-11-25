package re.abbot.librecr.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import re.abbot.librecr.app.ble.SensorConnectionManager
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.WearAppearanceStore
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.live.WearLiveUpdatesNotifier
import re.abbot.librecr.app.log.DiagnosticLogSettings
import re.abbot.librecr.app.wear.WearDataSync
import re.abbot.librecr.app.wear.AutomaticSensorHandoff
import re.abbot.librecr.app.wear.ReceiverSessionBackup
import re.abbot.librecr.app.wear.complication.LibreComplicationUpdater
import re.abbot.librecr.app.wear.complication.WatchWeather

/**
 * Minimal process-wide graph so the foreground service (which owns the
 * connection loop) and the UI (which observes it) share one manager + store.
 */
object LibreCR {
    @Volatile private var initialized = false
    lateinit var store: SensorStateStore
        private set
    lateinit var manager: SensorConnectionManager
        private set
    lateinit var appearance: WearAppearanceStore
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        DiagnosticLogSettings.initialize(app)
        store = SensorStateStore(app)
        manager = SensorConnectionManager(app, store)
        appearance = WearAppearanceStore(app)
        WearDataSync.initializePeerState(app)
        AutomaticSensorHandoff.initialize(app)
        ReceiverSessionBackup.initialize(app)
        WearLiveUpdatesNotifier.initialize(app, scope)
        // Seed patch status off the live path. A delayed DataStore read cannot delay BLE startup,
        // UI, phone relay or complication requests, and cannot overwrite a newer live snapshot.
        scope.launch {
            runCatching { store.loadSensorStatus() }
                .onSuccess(manager::seedPersistedSensorStatus)
                .onFailure { BleLog.log("persisted sensor status seed failed: ${it.message}") }
        }
        scope.launch {
            appearance.settingsFlow
                .distinctUntilChanged()
                .collect { LibreComplicationUpdater.requestAll(app) }
        }
        WearDataSync.fetchAppearance(app) { settings ->
            scope.launch {
                appearance.save(settings)
                LibreComplicationUpdater.requestAll(app)
            }
        }
        WearDataSync.requestAppearance(app)
        scope.launch { WatchWeather.restoreFromDataLayer(app) }
        initialized = true
    }
}
