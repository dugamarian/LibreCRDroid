package re.abbot.librecr.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.EnumMap
import re.abbot.librecr.app.live.WearLiveUpdatesNotifier
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.wear.complication.LibreComplicationUpdater

/**
 * Wear redraws neither a chip's critical text nor a complication image by itself, and app delays
 * stall in deep sleep, so "<1 m." would stay frozen once readings stop. One exact while-idle
 * alarm wakes the app at the earliest next minute change (or expiry) of the surfaces that still
 * show minutes. Every reading moves those changes 70 s ahead, so readings that keep arriving each
 * minute replace the alarm before it can fire.
 */
object GlucoseAgeAlarm {
    enum class Surface { LIVE_UPDATE_CHIP, AGE_COMPLICATION }

    private const val ACTION_AGE_TICK = "re.abbot.librecr.app.AGE_TICK"
    private const val REQUEST_CODE = 121
    /** Fire just past the boundary so the repaint reads the new minute. */
    private const val SLACK_MS = 500L
    private const val UNKNOWN = -1L
    private const val NONE = 0L
    private val dueAtMs = EnumMap<Surface, Long>(Surface::class.java)
    /** Unknown after a process start: an earlier process may have left an alarm armed. */
    private var armedAtMs = UNKNOWN

    /** [delayMs] until [surface]'s next minute change, or null once it shows no minutes. */
    @Synchronized
    fun update(context: Context, surface: Surface, delayMs: Long?, nowMs: Long) {
        // now + delay is a fixed boundary of the sample, so unchanged content does not re-arm.
        if (delayMs == null) dueAtMs.remove(surface) else dueAtMs[surface] = nowMs + delayMs + SLACK_MS
        rearm(context.applicationContext)
    }

    fun clear(context: Context, surface: Surface) = update(context, surface, null, 0L)

    @Synchronized
    private fun fired(context: Context, nowMs: Long) {
        // The alarm is gone; each surface re-arms its next change while it repaints.
        armedAtMs = NONE
        dueAtMs.values.removeAll { it <= nowMs }
        rearm(context.applicationContext)
    }

    private fun rearm(context: Context) {
        val next = dueAtMs.values.minOrNull()
        if (next == null) cancel(context) else if (next != armedAtMs) arm(context, next)
    }

    private fun arm(context: Context, triggerAtMs: Long) {
        runCatching {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val operation = pendingIntent(context)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
            } else {
                // Without exact-alarm access the minute may lag, but it still advances in sleep.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
            }
            armedAtMs = triggerAtMs
        }.onFailure { BleLog.log("glucose age alarm arm failed: ${it.message ?: it::class.java.simpleName}") }
    }

    private fun cancel(context: Context) {
        if (armedAtMs == NONE) return
        runCatching {
            context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
            armedAtMs = NONE
        }.onFailure { BleLog.log("glucose age alarm cancel failed: ${it.message ?: it::class.java.simpleName}") }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, Receiver::class.java).setAction(ACTION_AGE_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_AGE_TICK) return
            LibreCR.init(context)
            fired(context, System.currentTimeMillis())
            // Repost the chip's minutes and repaint the glucose complications (at the expiry this
            // also turns the value stale on time); each re-arms its next change.
            WearLiveUpdatesNotifier.refresh(context)
            val pending = goAsync()
            LibreComplicationUpdater.requestGlucose(context).invokeOnCompletion { pending.finish() }
        }
    }
}
