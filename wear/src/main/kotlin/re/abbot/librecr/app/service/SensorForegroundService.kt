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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.log.BleLog

/**
 * Foreground service (type connectedDevice) that keeps the connection loop alive
 * in the background and surfaces live glucose in its notification — the Android
 * analogue of the iOS background-BLE strategy.
 */
class SensorForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var glucoseCollectorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LibreCR.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification("Starting…"))
        val allowCandidateFirstPair = intent?.getBooleanExtra(EXTRA_ALLOW_CANDIDATE_FIRST_PAIR, false) == true
        scope.launch {
            val session = LibreCR.store.loadSession()
            if (session == null) {
                BleLog.log("service: no imported session; stopping")
                stopSelf()
                return@launch
            }
            BleLog.log(
                "service: starting sensor manager with independent local handshake " +
                    "allowCandidateFirstPair=$allowCandidateFirstPair legacyKeyIgnored=${session.phase5RawKey != null}"
            )
            LibreCR.manager.start(
                scope,
                session.withoutTransientCrypto(),
                allowCandidateFirstPair = allowCandidateFirstPair,
            )
        }
        if (glucoseCollectorJob?.isActive != true) {
            glucoseCollectorJob = scope.launch {
                combine(
                    LibreCR.manager.glucose,
                    LibreCR.appearance.settingsFlow,
                    LibreCR.manager.state,
                ) { g, appearance, state -> Triple(g, appearance.unit, state) }.collectLatest { (g, unit, state) ->
                    val text = when {
                        g == null && state.isUnavailableForDisplay() -> "out of range"
                        g == null -> "no reading yet"
                        !g.usable -> "out of range"
                        state.isUnavailableForDisplay() && !isFresh(g.receivedAtMs) -> "out of range"
                        g.mgDL != null -> "${unit.formatWithUnit(g.mgDL)}  ${g.trend}"
                        else -> "no reading yet"
                    }
                    notify(text)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LibreCR.manager.stop()
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

    private fun notify(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LibreCR sensor")
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

    private fun ConnectionState.isUnavailableForDisplay(): Boolean =
        this == ConnectionState.SCANNING ||
            this == ConnectionState.CONNECTING ||
            this == ConnectionState.HANDSHAKING ||
            this == ConnectionState.RECONNECTING ||
            this == ConnectionState.ERROR

    private fun isFresh(receivedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        receivedAtMs > 0L && nowMs - receivedAtMs < GLUCOSE_STALE_AFTER_MS

    companion object {
        private const val CHANNEL_ID = "librecr_sensor"
        private const val NOTIF_ID = 1
        private const val GLUCOSE_STALE_AFTER_MS = 6 * 60_000L
        private const val EXTRA_ALLOW_CANDIDATE_FIRST_PAIR =
            "re.abbot.librecr.app.extra.ALLOW_CANDIDATE_FIRST_PAIR"

        fun start(context: Context, allowCandidateFirstPair: Boolean = false): Boolean {
            val intent = Intent(context, SensorForegroundService::class.java)
                .putExtra(EXTRA_ALLOW_CANDIDATE_FIRST_PAIR, allowCandidateFirstPair)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure {
                BleLog.log("service: startForegroundService failed: ${it.message ?: it::class.java.simpleName}")
            }.isSuccess
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SensorForegroundService::class.java))
        }
    }
}
