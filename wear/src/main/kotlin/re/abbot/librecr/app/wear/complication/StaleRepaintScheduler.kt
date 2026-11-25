package re.abbot.librecr.app.wear.complication

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.live.WearLiveUpdatesNotifier

/**
 * Complications freeze at whatever they last painted: with no newer reading nothing repaints
 * them, so a value past the freshness window kept LOOKING fresh until some unrelated trigger
 * repainted it — and then it snapped to "--" at once. One while-idle alarm per reading repaints
 * right after the freshness boundary so the stale look lands on time even when both the phone
 * link and the sensor link are gone. Re-arming for a newer reading replaces the previous alarm
 * (same PendingIntent).
 */
object StaleRepaintScheduler {
    private const val REQUEST_CODE = 47
    /** Fire just past the boundary so the repaint reads the value as already stale. */
    private const val SLACK_MS = 1_000L

    fun armFrom(context: Context, receivedAtMs: Long) {
        runCatching {
            val app = context.applicationContext
            val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAtMs = (receivedAtMs + GlucoseComplicationRenderer.STALE_AFTER_MS + SLACK_MS)
                .coerceAtLeast(System.currentTimeMillis() + 1_000L)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent(app))
        }.onFailure {
            BleLog.log("complication: stale repaint arm failed: ${it.message ?: it::class.java.simpleName}")
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, StaleRepaintReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class StaleRepaintReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        GlucoRitm.init(context)
        WearLiveUpdatesNotifier.refresh(context)
        BleLog.log("COMPLICATION_STALE_REPAINT freshness boundary reached; repainting")
        val pending = goAsync()
        GlucoRitmComplicationUpdater.requestGlucose(context).invokeOnCompletion { pending.finish() }
    }
}
