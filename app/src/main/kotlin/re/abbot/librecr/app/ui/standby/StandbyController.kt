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
import android.os.Build
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.data.AppSettings
import re.abbot.librecr.app.log.BleLog
import java.util.Calendar

/**
 * Standby launcher, modelled on Juggluco's `StandbyMode`. The Compose-conditional approach can
 * never show standby when the phone is on a charger with the screen off / app backgrounded, because
 * the main Activity isn't running. Instead we listen for power changes and, on a WIRELESS charger
 * inside the configured window, surface a full-screen [StandbyActivity] that owns landscape
 * orientation.
 *
 * A direct launch is attempted first for immediate activation. If Android blocks the background
 * launch, a high-priority full-screen intent is posted as a fallback.
 */
object StandbyController {
    private const val CHANNEL_ID = "librecr_standby"
    private const val NOTIF_ID = 77
    private const val BOUNDARY_REQUEST_CODE = 78
    private const val FULL_SCREEN_FALLBACK_DELAY_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var registered = false
    @Volatile private var observingSettings = false
    @Volatile private var visible = false
    @Volatile private var launchPending = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == Intent.ACTION_POWER_DISCONNECTED) {
                launchPending = false
                cancelNotification(context) // the visible activity self-finishes
            } else {
                evaluate(context.applicationContext)
            }
        }
    }

    /** Register the runtime power receiver (call from LibreCR.init so it lives with the process). */
    fun init(context: Context) {
        val app = context.applicationContext
        if (!registered) {
            // NB: deliberately NOT ACTION_BATTERY_CHANGED. That broadcast fires many times per minute
            // while charging (exactly the standby scenario), and each delivery re-read settings,
            // re-registered a sticky receiver and rescheduled an exact alarm — a needless wakeup/CPU
            // firehose. Wireless plug/unplug is already covered by POWER_CONNECTED/DISCONNECTED, and
            // window entry/exit by the exact boundary alarm (scheduleNextBoundary).
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            }
            runCatching {
                ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
                BleLog.log("standby: power receiver registered")
            }.onFailure { BleLog.log("standby: register failed: ${it.message}") }
        }
        if (!observingSettings) {
            observingSettings = true
            scope.launch {
                LibreCR.settings.settingsFlow
                    .map {
                        Triple(
                            it.redStandbyEnabled,
                            it.redStandbyStartMinutes,
                            it.redStandbyEndMinutes,
                        )
                    }
                    .distinctUntilChanged()
                    .collect {
                        scheduleNextBoundary(app)
                        evaluate(app)
                    }
            }
        }
        scope.launch { scheduleNextBoundary(app) }
        evaluate(app)
    }

    fun markVisible(isVisible: Boolean) {
        visible = isVisible
        if (isVisible) launchPending = false
    }

    /** Surface standby (via full-screen intent) if eligible and not already shown. */
    fun evaluate(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val settings = LibreCR.settings.current()
            val wireless = isWirelessCharging(app)
            val inWindow = isInWindow(settings.redStandbyStartMinutes, settings.redStandbyEndMinutes)
            val eligible = settings.redStandbyEnabled && wireless && inWindow
            BleLog.log(
                "standby: evaluate enabled=${settings.redStandbyEnabled} wireless=$wireless " +
                    "inWindow=$inWindow visible=$visible",
            )
            if (eligible) {
                if (!visible && !launchPending) launchImmediately(app)
            } else {
                launchPending = false
                cancelNotification(app)
                app.sendBroadcast(Intent(ACTION_ELIGIBILITY_CHANGED).setPackage(app.packageName))
            }
            scheduleNextBoundary(app, settings)
        }
    }

    /** Used by [StandbyActivity] to decide whether to stay up. */
    suspend fun isEligible(context: Context): Boolean =
        eligible(context.applicationContext, LibreCR.settings.current())

    fun cancelNotification(context: Context) {
        runCatching { notificationManager(context).cancel(NOTIF_ID) }
    }

    private fun eligible(context: Context, settings: AppSettings): Boolean =
        settings.redStandbyEnabled &&
            isWirelessCharging(context) &&
            isInWindow(settings.redStandbyStartMinutes, settings.redStandbyEndMinutes)

    private fun launchImmediately(context: Context) {
        launchPending = true
        val launchIntent = Intent(context, StandbyActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        runCatching {
            context.startActivity(launchIntent)
            BleLog.log("standby: direct launch requested")
        }.onFailure {
            BleLog.log("standby: direct launch failed: ${it.message}")
        }

        mainHandler.postDelayed({
            if (!visible) {
                scope.launch {
                    val settings = LibreCR.settings.current()
                    if (eligible(context, settings)) {
                        postFullScreenIntent(context)
                    } else {
                        launchPending = false
                    }
                }
            }
        }, FULL_SCREEN_FALLBACK_DELAY_MS)
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
            .setContentTitle("Standby")
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
            BleLog.log("standby: full-screen intent posted (wireless charging, in window)")
        }.onFailure { BleLog.log("standby: notify failed: ${it.message}") }
    }

    private fun ensureChannel(context: Context) {
        val nm = notificationManager(context)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        // High importance so the full-screen intent fires, but silent (standby must not beep).
        val channel = NotificationChannel(CHANNEL_ID, "Standby", NotificationManager.IMPORTANCE_HIGH).apply {
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
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        (plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0
    }.getOrDefault(false)

    private fun isInWindow(start: Int, end: Int): Boolean {
        if (start == end) return true // equal start/end = always
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (start < end) now in start until end else (now >= start || now < end)
    }

    private suspend fun scheduleNextBoundary(context: Context) {
        scheduleNextBoundary(context, LibreCR.settings.current())
    }

    private fun scheduleNextBoundary(context: Context, settings: AppSettings) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = boundaryPendingIntent(context)
        alarmManager.cancel(pendingIntent)
        if (!settings.redStandbyEnabled ||
            settings.redStandbyStartMinutes == settings.redStandbyEndMinutes
        ) {
            return
        }

        val now = Calendar.getInstance()
        val nextStart = nextOccurrence(now, settings.redStandbyStartMinutes)
        val nextEnd = nextOccurrence(now, settings.redStandbyEndMinutes)
        val triggerAt = minOf(nextStart.timeInMillis, nextEnd.timeInMillis)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        BleLog.log("standby: next time boundary scheduled at $triggerAt")
    }

    private fun nextOccurrence(now: Calendar, minutes: Int): Calendar =
        (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }

    private fun boundaryPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            BOUNDARY_REQUEST_CODE,
            Intent(context, StandbyPowerReceiver::class.java).setAction(ACTION_TIME_BOUNDARY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    const val ACTION_TIME_BOUNDARY = "re.abbot.librecr.app.action.STANDBY_TIME_BOUNDARY"
    const val ACTION_ELIGIBILITY_CHANGED =
        "re.abbot.librecr.app.action.STANDBY_ELIGIBILITY_CHANGED"
}
