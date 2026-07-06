package re.abbot.librecr.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.AlarmSettings
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.stats.GlucoseSample
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns alarm firing + snooze state for the whole process. The foreground service funnels every
 * reading through [onReading]; [AlarmActivity] and [AlarmActionReceiver] call snooze/stop. A
 * high-importance channel with a full-screen intent is what surfaces the alarm over the lock
 * screen. Snooze re-alerts after N minutes if still breaching; Stop stays silent until the value
 * returns to range.
 */
object GlucoseAlarmManager {
    const val CHANNEL_ID = "librecr_alarm"
    private const val NOTIF_ID = 42
    const val EXTRA_KIND = "re.abbot.librecr.app.alarm.KIND"
    const val EXTRA_MGDL = "re.abbot.librecr.app.alarm.MGDL"
    const val EXTRA_UNIT = "re.abbot.librecr.app.alarm.UNIT"
    const val EXTRA_SNOOZE_MIN = "re.abbot.librecr.app.alarm.SNOOZE_MIN"
    const val ACTION_SNOOZE = "re.abbot.librecr.app.alarm.SNOOZE"
    const val ACTION_STOP = "re.abbot.librecr.app.alarm.STOP"

    private val snoozedUntil = ConcurrentHashMap<AlarmKind, Long>()
    @Volatile private var firing: AlarmKind? = null
    /** Channel creation is idempotent; skip the per-reading binder round-trip after the first check. */
    @Volatile private var channelEnsured = false

    fun onReading(
        context: Context,
        mgDl: Int,
        config: AlarmSettings,
        recentSamples: List<GlucoseSample>,
        unit: GlucoseUnit = GlucoseUnit.MG_DL,
    ) {
        ensureChannel(context)
        val now = System.currentTimeMillis()
        val minute = minuteOfDay(now)
        val snoozed: (AlarmKind) -> Boolean = { (snoozedUntil[it] ?: 0L) > now }

        val immediateRaw = AlarmEvaluator.rawBreach(mgDl, config)
        val persistentRaw = AlarmEvaluator.persistentRawKind(recentSamples, now, config)
        BleLog.log(
            "alarm onReading mgdl=$mgDl enabled=${config.enabled} immRaw=$immediateRaw " +
                "persRaw=$persistentRaw firing=$firing samples=${recentSamples.size}",
        )

        if (immediateRaw == null && persistentRaw == null) {
            // Fully back to normal on every front: clear the episode and snoozes so a new breach re-alerts.
            if (firing != null) cancel(context)
            firing = null
            snoozedUntil.clear()
            return
        }
        if (!config.enabled) return

        // Acute (immediate) alarms take priority over persistent ones.
        val immediate = AlarmEvaluator.evaluate(mgDl, config, minute, snoozed)
        val persistent = if (immediate == null) {
            AlarmEvaluator.persistentDecision(recentSamples, now, minute, config, snoozed)
        } else {
            null
        }
        val decision = immediate ?: persistent ?: return
        if (firing != decision.kind) {
            firing = decision.kind
            BleLog.log("alarm FIRE kind=${decision.kind} mgdl=${decision.mgDl}")
            fire(context, decision.kind, decision.mgDl, config.snoozeMinutes, unit)
        }
    }

    fun snooze(context: Context, kind: AlarmKind, minutes: Int) {
        snoozedUntil[kind] = System.currentTimeMillis() + minutes * 60_000L
        firing = null
        cancel(context)
    }

    fun stop(context: Context, kind: AlarmKind) {
        firing = kind
        cancel(context)
    }

    /** Fire a sample LOW alarm so the user can preview the full-screen experience. */
    fun fireTest(context: Context, snoozeMinutes: Int, unit: GlucoseUnit = GlucoseUnit.MG_DL) {
        ensureChannel(context)
        firing = AlarmKind.LOW
        BleLog.log("alarm fireTest snooze=$snoozeMinutes")
        fire(context, AlarmKind.LOW, 55, snoozeMinutes, unit)
    }

    private fun fire(
        context: Context,
        kind: AlarmKind,
        mgDl: Int,
        snoozeMinutes: Int,
        unit: GlucoseUnit = GlucoseUnit.MG_DL,
    ) {
        val app = context.applicationContext
        val full = Intent(app, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_KIND, kind.name)
            .putExtra(EXTRA_MGDL, mgDl)
            .putExtra(EXTRA_UNIT, unit.name)
            .putExtra(EXTRA_SNOOZE_MIN, snoozeMinutes)
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val fullPi = PendingIntent.getActivity(app, 1, full, piFlags)
        val snoozePi = PendingIntent.getBroadcast(
            app, 2,
            Intent(app, AlarmActionReceiver::class.java).setAction(ACTION_SNOOZE)
                .putExtra(EXTRA_KIND, kind.name).putExtra(EXTRA_SNOOZE_MIN, snoozeMinutes),
            piFlags,
        )
        val stopPi = PendingIntent.getBroadcast(
            app, 3,
            Intent(app, AlarmActionReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_KIND, kind.name),
            piFlags,
        )
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setContentTitle(app.getString(titleRes(kind)))
            .setContentText(unit.formatWithUnit(mgDl))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_recent_history,
                    app.getString(R.string.alarm_action_snooze),
                    snoozePi,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    app.getString(R.string.alarm_action_stop),
                    stopPi,
                ).build(),
            )
            .build()
        runCatching { notificationManager(app).notify(NOTIF_ID, notification) }
            .onSuccess { BleLog.log("alarm notification posted kind=$kind mgdl=$mgDl") }
            .onFailure { BleLog.log("alarm notify FAILED: ${it.message}") }
        // A full-screen intent only auto-launches when the screen is off/locked; with the screen ON
        // showing our own UI (the landscape standby clock is the common case) Android downgrades it
        // to a heads-up. Also try a direct launch: it succeeds whenever the app is in the foreground
        // and is silently discarded by the OS when backgrounded — the notification stays the fallback.
        runCatching { app.startActivity(full) }
            .onSuccess { BleLog.log("alarm direct launch attempted kind=$kind") }
            .onFailure { BleLog.log("alarm direct launch failed: ${it.message}") }
    }

    private fun cancel(context: Context) {
        notificationManager(context.applicationContext).cancel(NOTIF_ID)
    }

    private fun ensureChannel(context: Context) {
        if (channelEnsured) return
        channelEnsured = true
        val nm = notificationManager(context.applicationContext)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.alarm_channel_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 300, 500, 300, 500)
            setBypassDnd(true)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs)
        }
        nm.createNotificationChannel(channel)
    }

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun minuteOfDay(ms: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    /** Shared with [AlarmActivity] so the notification and the full-screen alarm always match. */
    fun titleRes(kind: AlarmKind): Int = when (kind) {
        AlarmKind.URGENT_LOW -> R.string.alarm_title_urgent_low
        AlarmKind.LOW -> R.string.alarm_title_low
        AlarmKind.HIGH -> R.string.alarm_title_high
        AlarmKind.PERSISTENT_HIGH -> R.string.alarm_title_persistent_high
        AlarmKind.PERSISTENT_LOW -> R.string.alarm_title_persistent_low
    }
}

/** Handles the Snooze/Stop buttons on the alarm notification. */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = runCatching { AlarmKind.valueOf(intent.getStringExtra(GlucoseAlarmManager.EXTRA_KIND) ?: "") }.getOrNull() ?: return
        when (intent.action) {
            GlucoseAlarmManager.ACTION_SNOOZE ->
                GlucoseAlarmManager.snooze(context, kind, intent.getIntExtra(GlucoseAlarmManager.EXTRA_SNOOZE_MIN, 30))
            GlucoseAlarmManager.ACTION_STOP ->
                GlucoseAlarmManager.stop(context, kind)
        }
    }
}
