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
                val session = GlucoRitm.store.loadSession()
                val autoConnect = GlucoRitm.store.autoConnectEnabled()
                if (session != null) {
                    ReceiverRecoveryService.ensure(context, re.abbot.librecr.app.wear.AutomaticSensorHandoff.monitoringRequired)
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
