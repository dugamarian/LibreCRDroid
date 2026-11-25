package re.abbot.librecr.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention
import re.abbot.librecr.protocol.dataplane.SensorLifecycle

private const val ACTION_REFRESH_WIDGETS = "re.abbot.librecr.app.widget.REFRESH"
private const val PREFERENCES = "glucose_widgets"
private const val KEY_PREVIEWS_FOR_INSTALL = "previews_for_install"

/** Updates both phone-BLE and watch-relayed values through their shared persisted data funnel. */
object GlucoseWidgetUpdater {
    private const val REFRESH_MS = 60_000L
    private val updateMutex = Mutex()
    private var started = false
    private var ticker: Job? = null
    private val clock = MutableStateFlow(System.currentTimeMillis())
    internal val refreshTime: StateFlow<Long> = clock.asStateFlow()
    private val chartHistory = MutableStateFlow(ChartHistory())

    private data class ChartHistory(val loadedAtMs: Long = 0L, val samples: List<GlucoseSample> = emptyList())

    private data class DisplaySettings(val unit: GlucoseUnit, val targetLow: Int, val targetHigh: Int)

    internal fun inputs(): Flow<GlucoseWidgetInput> = combine(
        GlucoRitm.store.lastGlucoseFlow,
        GlucoRitm.store.sensorStatusFlow,
        GlucoRitm.store.sensorWarmupFlow,
        GlucoRitm.store.sessionFlow,
        GlucoRitm.manager.glucose,
    ) { reading, status, warmup, session, live ->
        GlucoseWidgetInput(
            reading = reading,
            sensorError = status?.attention?.let { it != Libre3SensorAttention.None } == true,
            unavailableAtMs = live?.takeIf { !it.usable || it.mgDL == null }?.receivedAtMs,
            warmupStartedAtMs = warmup?.startedAtMs,
            warmupDurationMinutes = session?.warmupMinutes ?: SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES,
        )
    }.distinctUntilChanged()

    private fun displaySettings(): Flow<DisplaySettings> = GlucoRitm.settings.settingsFlow
        .map { DisplaySettings(it.unit, it.targetLow, it.targetHigh) }
        .distinctUntilChanged()

    /** The widget's own view: shared freshness inputs plus unit, target band and chart series. */
    internal fun models(): Flow<GlucoseWidgetModel> = combine(
        inputs(),
        displaySettings(),
        GlucoRitm.store.glucoseHistoryFlow,
        chartHistory,
    ) { input, settings, recent, history ->
        GlucoseWidgetModel(input, settings.unit, settings.targetLow, settings.targetHigh, history.samples, recent)
    }.distinctUntilChanged()

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        GlucoRitm.appScope.launch {
            combine(inputs(), displaySettings()) { input, settings -> input to settings }
                .distinctUntilChanged()
                .collect { refresh(app) }
        }
        onWidgetsChanged(app)
        publishPreviews(app)
    }

    @Synchronized
    fun onWidgetsChanged(context: Context) {
        val app = context.applicationContext
        if (!hasWidgets(app)) {
            ticker?.cancel()
            ticker = null
            alarmManager(app).cancel(refreshIntent(app))
            return
        }
        scheduleRefresh(app)
        if (ticker?.isActive == true) return
        ticker = GlucoRitm.appScope.launch {
            while (true) {
                delay(REFRESH_MS)
                refresh(app)
            }
        }
    }

    suspend fun refresh(context: Context) {
        updateMutex.withLock {
            if (!hasWidgets(context)) return
            loadChartHistory()
            // Invalidate freshness even when Glance reuses a running composition and the reading
            // itself did not change (for example, a sensor stopped sending packets).
            clock.value = System.currentTimeMillis()
            try {
                val manager = AppWidgetManager.getInstance(context)
                val glanceManager = GlanceAppWidgetManager(context)
                providers().forEach { (receiver, widget) ->
                    manager.getAppWidgetIds(ComponentName(context, receiver)).forEach { id ->
                        widget.update(context, glanceManager.getGlanceIdBy(id))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A failed host must not stop sensor collection or subsequent widget refreshes.
                BleLog.log("widget: refresh failed: ${error.javaClass.simpleName}")
            } finally {
                scheduleRefresh(context)
            }
        }
    }

    /** A fresh session may start before the first refresh; avoid rendering it without a chart. */
    internal suspend fun ensureChartHistory() {
        if (System.currentTimeMillis() - chartHistory.value.loadedAtMs >= REFRESH_MS) loadChartHistory()
    }

    private suspend fun loadChartHistory() {
        val now = System.currentTimeMillis()
        try {
            chartHistory.value = ChartHistory(now, GlucoRitm.history.chartSamples(now - WIDGET_CHART_WINDOW_MS))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Keep the previous series; the reading itself does not depend on the chart.
            BleLog.log("widget: chart history failed: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Android 15+ pickers show a preview generated from the real Glance layout. Publishing is
     * rate-limited by the system, so it happens once per installed APK (design changes ship with it).
     */
    private fun publishPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        GlucoRitm.appScope.launch {
            try {
                val installedAt = context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                    .lastUpdateTime
                val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                if (preferences.getLong(KEY_PREVIEWS_FOR_INSTALL, 0L) == installedAt) return@launch
                val manager = GlanceAppWidgetManager(context)
                val published = providers().all { (receiver, _) ->
                    manager.setWidgetPreviews(receiver.kotlin) == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS
                }
                if (published) preferences.edit { putLong(KEY_PREVIEWS_FOR_INSTALL, installedAt) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                BleLog.log("widget: preview publish failed: ${error.javaClass.simpleName}")
            }
        }
    }

    internal fun hasWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return providers().any { (receiver, _) ->
            manager.getAppWidgetIds(ComponentName(context, receiver)).isNotEmpty()
        }
    }

    private fun providers(): List<Pair<Class<out GlucoseWidgetReceiver>, GlucoseWidget>> = listOf(
        CompactGlucoseWidgetReceiver::class.java to CompactGlucoseWidget(),
        WideGlucoseWidgetReceiver::class.java to WideGlucoseWidget(),
    )

    private fun alarmManager(context: Context): AlarmManager = context.getSystemService(AlarmManager::class.java)

    private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, GlucoseWidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH_WIDGETS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleRefresh(context: Context) {
        if (!hasWidgets(context)) return
        // Non-wakeup, inexact fallback after process death. No exact-alarm permission, wake lock,
        // or separate foreground service. Doze can delay this; the host's age clock still advances.
        alarmManager(context).set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + REFRESH_MS,
            refreshIntent(context),
        )
    }
}

/** Separate from Glance's receiver because Glance owns its own goAsync lifecycle. */
class GlucoseWidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                ACTION_REFRESH_WIDGETS,
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
            )) return
        if (!GlucoseWidgetUpdater.hasWidgets(context)) return
        val pending = goAsync()
        val app = context.applicationContext
        GlucoRitm.appScope.launch {
            try {
                GlucoRitm.init(app)
                withTimeout(8_000L) { GlucoseWidgetUpdater.refresh(app) }
            } finally {
                pending.finish()
            }
        }
    }
}
