package re.abbot.librecr.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.wear.AutomaticSensorHandoff

/** One replaceable alarm: live packets postpone it, so normal collection causes no periodic wakes. */
object ReceiverRecoveryAlarm {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var configured = false
    private var scheduledAtMs: Long? = null

    @Synchronized fun schedule(context: Context, waitMs: Long?) {
        val at = waitMs?.let { SystemClock.elapsedRealtime() + it.coerceAtLeast(1_000L) }
        if (configured && (at == scheduledAtMs ||
                (at != null && scheduledAtMs != null && kotlin.math.abs(at - scheduledAtMs!!) < 1_000L))) return
        val manager = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, 48, Intent(context, Receiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            if (at == null) manager.cancel(intent)
            // Inexact while idle: Android may defer this; no exact-alarm permission or constant wake lock.
            else manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent)
            scheduledAtMs = at
            configured = true
        }.onFailure { BleLog.log("receiver recovery: alarm unavailable: ${it.javaClass.simpleName}") }
    }

    fun runAwake(context: Context, block: suspend () -> Unit) {
        val lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlucoRitm:ReceiverRecovery")
        lock.acquire(55_000L)
        scope.launch {
            try { withTimeoutOrNull(50_000L) { block() } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                BleLog.log("receiver recovery: wake check failed: ${error.javaClass.simpleName}")
            } finally { if (lock.isHeld) lock.release() }
        }
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GlucoRitm.init(context)
            AutomaticSensorHandoff.onRecoveryWakeup()
        }
    }
}
