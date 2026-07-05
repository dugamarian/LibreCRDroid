package re.abbot.librecr.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
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
            // Pair the persisted reading with the live unavailable timestamp. Patch-status attention
            // remains the separate source of truth for real sensor errors.
            val glucoseWithUnavailable = combine(manager.glucose, store.lastGlucoseFlow) { live, persisted ->
                persisted to live?.takeIf { !it.usable }?.receivedAtMs
            }
            combine(
                settings.settingsFlow,
                glucoseWithUnavailable,
                store.sensorLifecycleFlow,
                store.sessionFlow,
                liveUpdateTicker(),
            ) { appSettings, (reading, unavailableAtMs), lifecycle, session, _ ->
                LiveUpdatesNotifier.State(
                    appSettings.liveUpdates,
                    appSettings.unit,
                    reading,
                    lifecycle,
                    session,
                    unavailableAtMs,
                )
            }.collect { state ->
                LiveUpdatesNotifier.update(app, state)
            }
        }
        // Alarms: evaluate on each new reading. Trigger on lastGlucoseFlow (deduped by lifeCount) and,
        // for persistent (sustained high/low) alarms, read the recent series straight from SQLite —
        // which already stores mgDL + minute, exactly what the evaluator uses — instead of the
        // DataStore JSON history blob. The SQLite read is skipped entirely unless a persistent alarm
        // is enabled (both default off), so the common case does zero extra I/O per reading.
        appScope.launch {
            store.lastGlucoseFlow
                .filterNotNull()
                .distinctUntilChangedBy { it.lifeCount }
                .collect { r ->
                    if (r.mgDL !in 1..500) return@collect
                    val currentSettings = settings.current()
                    val alarms = currentSettings.alarms
                    val samples = if (alarms.persistentHighEnabled || alarms.persistentLowEnabled) {
                        val windowMinutes = maxOf(alarms.persistentHighMinutes, alarms.persistentLowMinutes)
                            .coerceAtLeast(0)
                        alarmSeries(System.currentTimeMillis() - (windowMinutes + ALARM_SERIES_MARGIN_MIN) * 60_000L, r)
                    } else {
                        listOf(GlucoseSample(r.mgDL, r.receivedAtMs))
                    }
                    runCatching {
                        GlucoseAlarmManager.onReading(app, r.mgDL, alarms, samples, currentSettings.unit)
                    }
                }
        }
        // Cloud upload runs on its OWN collector so slow LibreView network I/O can never delay alarm
        // evaluation for the next reading. Keyed on the latest reading, deduped by lifeCount (the
        // uploader also dedups internally and no-ops when upload is disabled).
        appScope.launch {
            store.lastGlucoseFlow
                .filterNotNull()
                .distinctUntilChangedBy { it.lifeCount }
                .collect { r ->
                    if (r.mgDL in 1..500) {
                        uploader.uploadReading(r.mgDL, r.trend, r.lifeCount, r.receivedAtMs)
                    }
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

    /**
     * Recent per-minute series for persistent alarms, read from the SQLite history and merged with the
     * just-arrived reading — so the newest point is present in the window even if its own SQLite append
     * hasn't landed yet (deduped by minute). Only called when a persistent alarm is enabled.
     */
    private suspend fun alarmSeries(sinceMs: Long, latest: SensorStateStore.LastGlucose): List<GlucoseSample> {
        val fromDb = runCatching { history.samples(sinceMs) }.getOrDefault(emptyList())
        val byMinute = LinkedHashMap<Long, GlucoseSample>(fromDb.size + 1)
        fromDb.forEach { byMinute[it.atMs / 60_000L] = it }
        byMinute[latest.receivedAtMs / 60_000L] = GlucoseSample(latest.mgDL, latest.receivedAtMs)
        return byMinute.values.sortedBy { it.atMs }
    }

    /** Extra minutes of history fetched beyond the persistent-alarm window, to satisfy its coverage check. */
    private const val ALARM_SERIES_MARGIN_MIN = 5L
}
