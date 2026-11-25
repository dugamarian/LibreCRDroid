package re.abbot.librecr.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.R
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.ble.isUnavailableForGlucoseDisplay
import re.abbot.librecr.app.isFreshGlucose
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.wear.AutomaticSensorHandoff
import re.abbot.librecr.protocol.dataplane.SensorLifecycle

/**
 * Foreground service (type connectedDevice) that keeps the connection loop alive
 * in the background and surfaces live glucose in its notification — the Android
 * analogue of the iOS background-BLE strategy.
 */
class SensorForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var glucoseCollectorJob: Job? = null
    private var warmupDurationMinutes = SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        GlucoRitm.init(this)
        running = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundCompat(buildNotification(getString(R.string.standby_no_reading)))
        } catch (error: RuntimeException) {
            startRequested = false
            BleLog.log("service: foreground unavailable: ${error.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        startRequested = false
        val allowCandidateFirstPair = intent?.getBooleanExtra(EXTRA_ALLOW_CANDIDATE_FIRST_PAIR, false) == true
        if (intent?.getBooleanExtra(EXTRA_STANDBY, false) == true) {
            if (!AutomaticSensorHandoff.phonePriorityEnabled) stopSelf()
            else startCollection(allowCandidateFirstPair = true, passiveConnect = false)
        } else startCollection(allowCandidateFirstPair, intent?.getBooleanExtra(EXTRA_PASSIVE_CONNECT, false) == true)
        if (glucoseCollectorJob?.isActive != true) {
            glucoseCollectorJob = scope.launch {
                // Alarms + cloud upload are handled process-wide in GlucoRitm (keyed on the data
                // store), so they fire for watch-relayed readings too. Here we just keep the
                // foreground notification current.
                combine(
                    GlucoRitm.manager.glucose,
                    GlucoRitm.settings.settingsFlow,
                    GlucoRitm.manager.state,
                ) { glucose, settings, state -> Triple(glucose, settings.unit, state) }.collectLatest { (g, unit, state) ->
                    val text = when {
                        g == null && state.isUnavailableForGlucoseDisplay() -> getString(R.string.standby_no_reading)
                        g == null -> getString(R.string.standby_no_reading)
                        !g.usable && g.lifeCount < warmupDurationMinutes -> getString(R.string.sensor_warming_up)
                        !g.usable -> "NA"
                        state.isUnavailableForGlucoseDisplay() && !isFreshGlucose(g.receivedAtMs) ->
                            getString(R.string.standby_no_reading)
                        g.mgDL != null -> "${unit.formatWithUnit(g.mgDL)}  ${g.trend}"
                        else -> getString(R.string.standby_no_reading)
                    }
                    notify(text, g?.receivedAtMs)
                }
            }
        }
        return START_STICKY
    }

    private fun startCollection(allowCandidateFirstPair: Boolean, passiveConnect: Boolean) {
        scope.launch {
            val session = GlucoRitm.store.loadSession()
            if (session == null || !GlucoRitm.store.autoConnectEnabled()) {
                BleLog.log("service: no session or receiver disabled; stopping")
                if (!AutomaticSensorHandoff.phonePriorityEnabled) stopSelf()
                return@launch
            }
            warmupDurationMinutes = session.warmupMinutes ?: SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES
            BleLog.log("service: starting sensor manager with independent local handshake allowCandidateFirstPair=$allowCandidateFirstPair")
            GlucoRitm.manager.start(scope, session.withoutTransientCrypto(),
                allowCandidateFirstPair = allowCandidateFirstPair, passiveConnect = passiveConnect)
        }
    }

    override fun onDestroy() {
        instance = null
        startRequested = false
        running = false
        GlucoRitm.manager.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun notify(text: String, observedAtMs: Long?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, observedAtMs))
    }

    private fun buildNotification(text: String, observedAtMs: Long? = null): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setWhen(observedAtMs ?: System.currentTimeMillis())
            .setShowWhen(observedAtMs != null && observedAtMs > 0L)
            .setContentTitle("GlucoRitm sensor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Sensor connection", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
    }

    companion object {
        @Volatile private var instance: SensorForegroundService? = null
        @Volatile private var startRequested = false
        @Volatile var running = false
            private set
        private const val EXTRA_PASSIVE_CONNECT = "librecr.PASSIVE_CONNECT"
        private const val EXTRA_STANDBY = "librecr.STANDBY"
        private const val CHANNEL_ID = "librecr_sensor"
        private const val NOTIF_ID = 1
        private const val EXTRA_ALLOW_CANDIDATE_FIRST_PAIR =
            "re.abbot.librecr.app.extra.ALLOW_CANDIDATE_FIRST_PAIR"

        fun start(context: Context, allowCandidateFirstPair: Boolean = false, passiveConnect: Boolean = false): Boolean =
            startOrStandby(context, allowCandidateFirstPair, passiveConnect, standby = false)

        fun ensureStandby(context: Context): Boolean = startOrStandby(context, false, false, standby = true)

        @Synchronized private fun startOrStandby(context: Context, allowCandidateFirstPair: Boolean, passiveConnect: Boolean, standby: Boolean): Boolean {
            instance?.let {
                if (!standby) it.startCollection(allowCandidateFirstPair, passiveConnect)
                return true
            }
            if (standby && startRequested) return true
            val intent = Intent(context, SensorForegroundService::class.java)
                .putExtra(EXTRA_ALLOW_CANDIDATE_FIRST_PAIR, allowCandidateFirstPair)
                .putExtra(EXTRA_PASSIVE_CONNECT, passiveConnect)
                .putExtra(EXTRA_STANDBY, standby)
            startRequested = true
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure {
                startRequested = false
                BleLog.log("service: startForegroundService failed: ${it.message ?: it::class.java.simpleName}")
            }.isSuccess
        }

        @Synchronized fun stop(context: Context) {
            if (AutomaticSensorHandoff.phonePriorityEnabled) return
            instance = null
            startRequested = false
            context.stopService(Intent(context, SensorForegroundService::class.java))
        }
    }
}
