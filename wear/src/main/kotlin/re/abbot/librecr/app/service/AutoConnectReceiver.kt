package re.abbot.librecr.app.service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.wear.complication.GlucoRitmComplicationUpdater
import re.abbot.librecr.app.wear.AutomaticSensorHandoff

/**
 * Restores the foreground connection service after events that commonly break
 * an otherwise healthy BLE loop: phone reboot, app update, and Bluetooth ON.
 */
class AutoConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!shouldHandle(intent)) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                GlucoRitm.init(context)
                // A loop that survived the Bluetooth restart may still wait on a connect which the
                // restarted stack no longer knows; the start below is ignored while that loop runs.
                if (action == BluetoothAdapter.ACTION_STATE_CHANGED) GlucoRitm.manager.onBluetoothRestarted()
                GlucoRitmComplicationUpdater.requestAll(context)
                val session = GlucoRitm.store.loadSession()
                val autoConnect = GlucoRitm.store.autoConnectEnabled()
                if (session != null) {
                    if (AutomaticSensorHandoff.phonePriorityServiceRequired) SensorForegroundService.ensureStandby(context)
                    else ReceiverRecoveryService.ensure(context, AutomaticSensorHandoff.monitoringRequired)
                }
                if (session == null || !autoConnect) {
                    BleLog.log("receiver: auto-connect skipped action=$action session=${session != null} enabled=$autoConnect")
                    return@launch
                }
                BleLog.log("receiver: auto-start service action=$action localFullHandshake=true")
                SensorForegroundService.start(context, allowCandidateFirstPair = true)
            } catch (t: Throwable) {
                BleLog.log("receiver: auto-connect failed action=$action error=${t.message ?: t::class.java.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun shouldHandle(intent: Intent): Boolean =
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            BluetoothAdapter.ACTION_STATE_CHANGED ->
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                    BluetoothAdapter.STATE_ON
            else -> false
        }
}
