package re.abbot.librecr.app.ui.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import re.abbot.librecr.app.GlucoRitm

/**
 * Manifest receiver: re-arms the runtime power receiver after boot / app update, and evaluates on
 * power-connected. (Implicit ACTION_POWER_CONNECTED may not be delivered to manifest receivers on
 * recent Android, but BOOT_COMPLETED / MY_PACKAGE_REPLACED reliably re-register the runtime one.)
 */
class StandbyPowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        GlucoRitm.init(context)
        StandbyController.init(context)
    }
}
