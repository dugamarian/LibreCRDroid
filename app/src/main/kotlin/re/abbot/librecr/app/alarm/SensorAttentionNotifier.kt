package re.abbot.librecr.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention

/**
 * One-shot status notification when the sensor needs the user to act (replace / ended / unknown
 * error). Separate, lower-urgency channel from the glucose [GlucoseAlarmManager] alarms — this is
 * informational, not a full-screen alarm. Called only on attention transitions, so it fires once
 * per change and clears itself when the sensor returns to normal.
 */
object SensorAttentionNotifier {
    private const val CHANNEL_ID = "librecr_sensor_status"
    private const val NOTIF_ID = 77

    fun onAttentionChanged(context: Context, attention: Libre3SensorAttention) {
        when (attention) {
            Libre3SensorAttention.ReplaceSensor,
            Libre3SensorAttention.SensorEnded,
            is Libre3SensorAttention.Unknown,
            -> notify(context, attention)
            else -> cancel(context)
        }
    }

    private fun notify(context: Context, attention: Libre3SensorAttention) {
        val app = context.applicationContext
        ensureChannel(app)
        val pi = PendingIntent.getActivity(
            app,
            7,
            Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = message(attention)
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { notificationManager(app).notify(NOTIF_ID, notification) }
            .onSuccess { BleLog.log("sensor-attention notification posted: $attention") }
            .onFailure { BleLog.log("sensor-attention notify FAILED: ${it.message}") }
    }

    private fun cancel(context: Context) {
        runCatching { notificationManager(context.applicationContext).cancel(NOTIF_ID) }
    }

    private fun message(attention: Libre3SensorAttention): Pair<String, String> = when (attention) {
        Libre3SensorAttention.ReplaceSensor ->
            "Înlocuiește senzorul" to "Senzorul a raportat o eroare și trebuie înlocuit."
        Libre3SensorAttention.SensorEnded ->
            "Senzor încheiat" to "Sesiunea senzorului s-a încheiat."
        is Libre3SensorAttention.Unknown ->
            "Eroare senzor" to "Senzorul a raportat un cod necunoscut (${attention.code})."
        else -> "Senzor" to ""
    }

    private fun ensureChannel(context: Context) {
        val manager = notificationManager(context)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Stare senzor", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notificări despre starea senzorului (înlocuire / erori)"
            },
        )
    }

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
