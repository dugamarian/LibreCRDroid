package re.abbot.librecr.app.wear.complication

import android.content.ComponentName
import re.abbot.librecr.app.log.BleLog

/**
 * In-memory map of the complication instances the watch face currently displays, per data source.
 * Lets [GlucoRitmComplicationUpdater] target exactly those instances instead of broadcasting
 * update-all requests for every service. Activation callbacks are NOT re-delivered after a
 * process restart, so instances are also (re)registered from every onComplicationRequest —
 * until the first host request arrives the map is empty and callers fall back to update-all.
 */
object ActiveComplicationRegistry {
    private val lock = Any()
    private val instances = LinkedHashMap<Int, ComponentName>()

    fun register(instanceId: Int, component: ComponentName) {
        synchronized(lock) {
            val previous = instances.put(instanceId, component)
            if (previous == null) {
                BleLog.log("COMPLICATION_INSTANCE_ACTIVE id=$instanceId service=${component.shortClassName}")
            }
        }
    }

    fun unregister(instanceId: Int) {
        synchronized(lock) {
            instances.remove(instanceId)?.let {
                BleLog.log("COMPLICATION_INSTANCE_GONE id=$instanceId service=${it.shortClassName}")
            }
        }
    }

    /** Known active instance ids for [component]; empty until its first request after a restart. */
    fun instanceIdsFor(component: ComponentName): IntArray = synchronized(lock) {
        instances.filterValues { it == component }.keys.toIntArray()
    }
}
