package re.abbot.librecr.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.R
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.wear.AutomaticSensorHandoff

/** Preserves the parked watch's recovery timer without keeping a sensor connection or wake lock. */
class ReceiverRecoveryService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        GlucoRitm.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.recovery_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.recovery_title))
            .setContentText(getString(R.string.recovery_monitoring))
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID, notification)
        if (!AutomaticSensorHandoff.monitoringRequired) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() { requested = false; super.onDestroy() }

    companion object {
        private const val CHANNEL = "librecr_receiver_recovery"
        private const val ID = 48
        @Volatile private var requested = false

        @Synchronized fun ensure(context: Context, needed: Boolean) {
            if (!needed) {
                if (requested) context.stopService(Intent(context, ReceiverRecoveryService::class.java))
                requested = false
            } else if (!requested) {
                requested = true
                runCatching { context.startForegroundService(Intent(context, ReceiverRecoveryService::class.java)) }
                    .onFailure {
                        requested = false
                        BleLog.log("receiver recovery: foreground monitor unavailable: ${it.javaClass.simpleName}")
                    }
            }
        }
    }
}
