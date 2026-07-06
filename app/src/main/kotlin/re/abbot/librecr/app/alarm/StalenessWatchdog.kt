package re.abbot.librecr.app.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.R
import re.abbot.librecr.app.log.BleLog
import java.util.Calendar

/**
 * Last-resort staleness alert: a heads-up notification when no liveness signal (usable OR
 * unusable reading, relayed or decoded locally) has arrived for [STALE_AFTER_MS] — regardless of
 * why: dead watch loop, dead relay, wedged BLE stack, killed process. Every liveness event pushes
 * an AlarmManager check to now+threshold (same PendingIntent, so it self-replaces); the check only
 * ever fires when the events stop. Low/high alarms tell the user what the glucose IS — this tells
 * them nobody knows what it is.
 *
 * Gated like an alarm: obeys the master alarms switch + its own [AlarmSettings.stalenessEnabled]
 * toggle, and inside the same active-hours window as the other non-urgent alarms — when stale
 * outside the window, the alert is deferred to the window start instead of firing at night.
 *
 * Self-disarming: no provisioned session (or no reading ever) → the fired check does nothing and
 * does not reschedule; the next reading re-arms it. Known gap: a reboot clears the schedule until
 * the first post-boot reading — acceptable for a diagnostic safety net.
 */
object StalenessWatchdog {
    private const val CHANNEL_ID = "librecr_staleness"
    private const val NOTIF_ID = 78
    private const val REQUEST_CODE = 78
    private const val STALE_AFTER_MS = 10 * 60_000L
    /** While stale: re-check (and re-alert with the updated age) on this cadence. */
    private const val RECHECK_WHILE_STALE_MS = 15 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Call on EVERY liveness event; cheap (one PendingIntent replace, no I/O on the caller). */
    fun onFreshReading(context: Context) {
        val app = context.applicationContext
        runCatching { notificationManager(app).cancel(NOTIF_ID) }
        schedule(app, System.currentTimeMillis() + STALE_AFTER_MS)
    }

    /** Alarms-screen toggle: ON arms from now; OFF clears the schedule and any posted alert. */
    fun onSettingChanged(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        if (enabled) {
            onFreshReading(app)
            BleLog.log("staleness: enabled via settings — armed")
        } else {
            runCatching { notificationManager(app).cancel(NOTIF_ID) }
            runCatching {
                (app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(checkIntent(app))
            }
            BleLog.log("staleness: disabled via settings — schedule + notification cleared")
        }
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val result = goAsync()
            scope.launch {
                try {
                    check(context.applicationContext)
                } finally {
                    result.finish()
                }
            }
        }
    }

    private suspend fun check(app: Context) {
        val session = runCatching { LibreCR.store.loadSession() }.getOrNull()
        if (session == null) {
            BleLog.log("staleness: check fired with no session — disarmed")
            return
        }
        val last = runCatching { LibreCR.store.loadLastGlucose() }.getOrNull()
        if (last == null) {
            BleLog.log("staleness: check fired with no reading ever — disarmed until first reading")
            return
        }
        val nowMs = System.currentTimeMillis()
        val ageMs = nowMs - last.receivedAtMs
        if (ageMs < STALE_AFTER_MS) {
            // Raced a reading that arrived after this alarm was armed; realign to it.
            schedule(app, last.receivedAtMs + STALE_AFTER_MS)
            return
        }
        // The staleness alert is an alarm: it obeys the master alarms switch, its own toggle,
        // and the same active-hours window as the other non-urgent alarms.
        val alarms = runCatching { LibreCR.settings.current().alarms }.getOrNull()
        if (alarms == null || !alarms.enabled || !alarms.stalenessEnabled) {
            BleLog.log("staleness: stale but alert disabled (master=${alarms?.enabled} staleness=${alarms?.stalenessEnabled}) — disarmed until next reading")
            return
        }
        if (alarms.activeHoursEnabled &&
            !AlarmEvaluator.inWindow(minuteOfDay(nowMs), alarms.activeStartMinutes, alarms.activeEndMinutes)
        ) {
            // Outside alarm hours: stay silent now, alert the moment the window opens (if
            // readings resume in the meantime, that check realigns instead of alerting).
            val windowStartMs = nextTimeAtMinuteOfDay(nowMs, alarms.activeStartMinutes) + 60_000L
            BleLog.log("staleness: stale outside alarm hours — deferred to window start")
            schedule(app, windowStartMs)
            return
        }
        val ageMin = (ageMs / 60_000L).toInt().coerceAtLeast((STALE_AFTER_MS / 60_000L).toInt())
        BleLog.log("[ANOMALY] staleness: no reading for ${ageMin}min — alerting")
        notifyStale(app, ageMin)
        schedule(app, nowMs + RECHECK_WHILE_STALE_MS)
    }

    private fun minuteOfDay(ms: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    /** Next wall-clock instant (strictly after [nowMs]) falling on [minuteOfDay] — today or tomorrow. */
    private fun nextTimeAtMinuteOfDay(nowMs: Long, minuteOfDay: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun schedule(app: Context, atMs: Long) {
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // Inexact-while-idle: fires in doze maintenance windows without SCHEDULE_EXACT_ALARM;
        // a few minutes of slip is fine for a 10-minute staleness net.
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, checkIntent(app))
        }.onFailure { BleLog.log("staleness: schedule failed: ${it.message}") }
    }

    private fun checkIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_CODE,
        Intent(app, Receiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notifyStale(app: Context, ageMin: Int) {
        ensureChannel(app)
        val pi = PendingIntent.getActivity(
            app,
            REQUEST_CODE,
            Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setContentTitle(app.getString(R.string.staleness_alert_title))
            .setContentText(app.getString(R.string.staleness_alert_detail, ageMin))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setCategory(Notification.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { notificationManager(app).notify(NOTIF_ID, notification) }
            .onFailure { BleLog.log("staleness: notify FAILED: ${it.message}") }
    }

    private fun ensureChannel(context: Context) {
        val manager = notificationManager(context)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.staleness_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.staleness_channel_desc)
            },
        )
    }

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
