package re.abbot.librecr.app.auto

import android.app.IntentService
import android.content.Intent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.log.BleLog

/** Android Auto notification PendingIntents allowlist this serial background action handler. */
@Suppress("DEPRECATION") // Still supported for this PendingIntent use case by the official car guide.
class AndroidAutoNotificationActionService : IntentService("GlucoRitmAutoNotificationActions") {
    override fun onHandleIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in setOf(AndroidAutoGlucoseNotifications.ACTION_REFRESH,
                AndroidAutoGlucoseNotifications.ACTION_DISMISS, AndroidAutoGlucoseNotifications.ACTION_DISABLE)) return
        val token = intent.getStringExtra(AndroidAutoGlucoseNotifications.EXTRA_SESSION) ?: return
        GlucoRitm.init(this)
        try {
            runBlocking {
                withTimeout(8_000L) { AndroidAutoGlucoseNotifications.handleAction(applicationContext, action, token) }
            }
        } catch (error: Exception) {
            BleLog.log("android-auto: notification action failed: ${error.javaClass.simpleName}")
        }
    }
}
