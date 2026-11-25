package re.abbot.librecr.app.ui.standby

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.R
import re.abbot.librecr.app.log.BleLog

/**
 * Watches physical orientation while wirelessly charging. Starts [StandbyActivity] once the
 * phone is on its side, including when the main UI is behind the lock screen.
 *
 * A direct launch is attempted first for immediate activation. If Android blocks the background
 * launch, a high-priority full-screen intent is posted as a fallback.
 */
object StandbyController {
    private const val CHANNEL_ID = "librecr_standby"
    private const val NOTIF_ID = 77
    private const val BOUNDARY_REQUEST_CODE = 78
    private const val FULL_SCREEN_FALLBACK_DELAY_MS = 1_500L
    private const val DOCK_SAMPLE_WAKE_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var registered = false
    private var observingSettings = false
    private var enabled = false
    private var wirelessCharging = false
    private var visible = false
    private var launchPending = false
    private var fullscreenFallback: Runnable? = null
    private var orientation: StandbyOrientation? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val wireless = when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val plugged = isWirelessPlug(intent)
                    if (plugged == wirelessCharging) return
                    plugged
                }
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> isWirelessCharging(context)
            }
            updateCharger(context.applicationContext, wireless)
        }
    }

    /** Register the runtime power receiver (call from GlucoRitm.init so it lives with the process). */
    fun init(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (orientation == null) orientation = StandbyOrientation(app) { updateActivity(app) }
            if (!registered) {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
                runCatching {
                    ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                    registered = true
                    cancelLegacyTimeBoundary(app)
                    BleLog.log("standby: power receiver registered")
                }.onFailure { BleLog.log("standby: register failed: ${it.message}") }
            }
            updateCharger(app, isWirelessCharging(app))
            if (observingSettings) return@launch
            observingSettings = true
            GlucoRitm.settings.settingsFlow
                .map { it.redStandbyEnabled }
                .distinctUntilChanged()
                .collect { standbyEnabled ->
                    enabled = standbyEnabled
                    updateCharger(app, isWirelessCharging(app))
                }
        }
    }

    /** Recheck before the activity can wake the screen or force landscape. */
    fun tryShow(context: Context): Boolean {
        cancelLaunch(context)
        if (!isEligible(context)) return false
        visible = true
        return true
    }

    fun markHidden() {
        visible = false
    }

    fun isEligible(context: Context): Boolean =
        enabled && wirelessCharging && orientation?.isLandscape() == true && isWirelessCharging(context)

    private fun updateCharger(context: Context, wireless: Boolean) {
        wirelessCharging = wireless
        if (enabled && wireless) {
            if (orientation?.start() == true) keepCpuAwakeForPlacement(context)
        } else {
            orientation?.stop()
        }
        updateActivity(context)
    }

    private fun keepCpuAwakeForPlacement(context: Context) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        if (power.isInteractive) return
        // Allow the non-wake-up accelerometer to settle at docking without turning on the screen.
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlucoRitm:StandbyOrientation").acquire(DOCK_SAMPLE_WAKE_MS)
    }

    private fun updateActivity(context: Context) {
        if (isEligible(context)) {
            if (!visible && !launchPending) launchImmediately(context)
        } else {
            cancelLaunch(context)
            if (visible) {
                context.sendBroadcast(Intent(ACTION_ELIGIBILITY_CHANGED).setPackage(context.packageName))
            }
        }
    }

    private fun cancelLaunch(context: Context) {
        fullscreenFallback?.let(mainHandler::removeCallbacks)
        fullscreenFallback = null
        launchPending = false
        runCatching { notificationManager(context).cancel(NOTIF_ID) }
    }

    private fun launchImmediately(context: Context) {
        launchPending = true
        val launchIntent = Intent(context, StandbyActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        val fallback = Runnable {
            fullscreenFallback = null
            if (isEligible(context)) postFullScreenIntent(context) else cancelLaunch(context)
        }
        fullscreenFallback = fallback
        mainHandler.postDelayed(fallback, FULL_SCREEN_FALLBACK_DELAY_MS)
        runCatching {
            context.startActivity(launchIntent)
            BleLog.log("standby: direct launch requested")
        }.onFailure {
            BleLog.log("standby: direct launch failed: ${it.message}")
        }
    }

    private fun postFullScreenIntent(context: Context) {
        ensureChannel(context)
        val pi = PendingIntent.getActivity(
            context,
            0,
            Intent(context, StandbyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.standby_mode))
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        runCatching {
            notificationManager(context).notify(NOTIF_ID, notification)
            BleLog.log("standby: full-screen intent posted (wireless charging)")
        }.onFailure { BleLog.log("standby: notify failed: ${it.message}") }
    }

    private fun ensureChannel(context: Context) {
        val nm = notificationManager(context)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        // High importance so the full-screen intent fires, but silent (standby must not beep).
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.standby_mode),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun isWirelessCharging(context: Context): Boolean = runCatching {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        isWirelessPlug(battery)
    }.getOrDefault(false)

    private fun isWirelessPlug(battery: Intent): Boolean =
        (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) and BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0

    /** Cancel an activation alarm left behind by an older installation. */
    private fun cancelLegacyTimeBoundary(context: Context) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            BOUNDARY_REQUEST_CODE,
            Intent(context, StandbyPowerReceiver::class.java).setAction("re.abbot.librecr.app.action.STANDBY_TIME_BOUNDARY"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    const val ACTION_ELIGIBILITY_CHANGED =
        "re.abbot.librecr.app.action.STANDBY_ELIGIBILITY_CHANGED"
}
