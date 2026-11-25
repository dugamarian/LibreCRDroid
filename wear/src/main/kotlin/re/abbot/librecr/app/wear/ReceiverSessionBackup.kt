package re.abbot.librecr.app.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.protocol.connection.RecoveryPolicy

/** Peer credentials are kept separate; an arriving backup never changes a running BLE session. */
object ReceiverSessionBackup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var context: Context
    private val mutex = Mutex()
    @Volatile private var generation = 0L
    private val prefs get() = context.getSharedPreferences("librecr_receiver_backup", Context.MODE_PRIVATE)

    fun initialize(context: Context) {
        this.context = context.applicationContext
        refresh()
    }

    fun refresh() {
        if (!::context.isInitialized) return
        Wearable.getDataClient(this.context).dataItems.addOnSuccessListener { items ->
            try {
                for (item in items) if (item.uri.path == RecoveryPolicy.SESSION_BACKUP) {
                    item.data?.let { receive(item.uri.host.orEmpty(), it.copyOf()) }
                }
            } finally { items.release() }
        }
    }

    fun publish(session: ImportedSession, key: ByteArray) {
        val revision = synchronized(this) { ++generation }
        val reusableSession = session.copy(phase5RawKey = key.copyOf())
        scope.launch {
            try {
                mutex.withLock {
                    if (revision != generation) return@withLock
                    val raw = reusableSession.toJson()
                    val now = System.currentTimeMillis()
                    val changed = if (prefs.getString("localKeySession", null) == raw) prefs.getLong("localChangedAt", now) else now
                    prefs.edit().putString("localKeySession", raw).putLong("localChangedAt", changed)
                        .putLong("localVerifiedAt", now).commit()
                    val payload = JSONObject(raw).put("_keyChangedAtMs", changed).toString().toByteArray(Charsets.UTF_8)
                    // Stable bytes: reconnecting with the same reusable key doesn't create a new update.
                    withTimeout(10_000L) { Wearable.getDataClient(context).putDataItem(
                        PutDataRequest.create(RecoveryPolicy.SESSION_BACKUP).setData(payload).setUrgent(),
                    ).awaitHandoff() }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                BleLog.log("receiver backup: publish failed: ${error.javaClass.simpleName}")
            }
        }
    }

    fun receive(nodeId: String, bytes: ByteArray) {
        scope.launch {
            try {
                if (!AutomaticSensorHandoff.acceptsBackupNode(nodeId)) return@launch
                val raw = String(bytes, Charsets.UTF_8)
                val incoming = ImportedSession.fromJson(raw)
                mutex.withLock {
                    val local = GlucoRitm.store.loadSession() ?: return@withLock
                    if (!sameSensor(local, incoming) || incoming.phase5RawKey == null) return@withLock
                    val changedAt = JSONObject(raw).optLong("_keyChangedAtMs", 0)
                    val saved = prefs.getString("session", null)?.let { runCatching { ImportedSession.fromJson(it) }.getOrNull() }
                    if (prefs.getString("node", "") == nodeId && saved != null && sameSensor(saved, incoming) &&
                        changedAt < prefs.getLong("peerChangedAt", 0)) return@withLock
                    prefs.edit().putString("node", nodeId).putString("session", incoming.toJson())
                        .putLong("peerChangedAt", changedAt).apply()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                BleLog.log("receiver backup: receive failed: ${error.javaClass.simpleName}")
            }
        }
    }

    suspend fun adopt(nodeId: String = "") = mutex.withLock {
        val local = GlucoRitm.store.loadSession() ?: return@withLock
        if (nodeId.isNotBlank() && prefs.getString("node", "") != nodeId) return@withLock
        val incoming = prefs.getString("session", null)?.let {
            runCatching { ImportedSession.fromJson(it) }.getOrNull()
        } ?: return@withLock
        val ownKey = GlucoRitm.store.loadCachedPhase5RawKey()
        if (sameSensor(local, incoming) && RecoveryPolicy.shouldAdoptBackup(
                ownKey != null, prefs.getLong("localVerifiedAt", 0), prefs.getLong("peerChangedAt", 0), System.currentTimeMillis())) {
            // Only the reusable reconnect key is copied, never per-link encryption material.
            incoming.phase5RawKey?.let { GlucoRitm.store.saveCachedPhase5RawKey(it) }
        }
    }

    suspend fun acceptReleasedSession(raw: String) = mutex.withLock {
        val incoming = ImportedSession.fromJson(raw)
        val local = GlucoRitm.store.loadSession() ?: return@withLock
        require(sameSensor(local, incoming))
        incoming.phase5RawKey?.let {
            GlucoRitm.store.saveCachedPhase5RawKey(it)
            prefs.edit().putLong("localVerifiedAt", System.currentTimeMillis()).commit()
        }
    }

    private fun sameSensor(a: ImportedSession, b: ImportedSession): Boolean =
        a.bleAddress.equals(b.bleAddress, ignoreCase = true) && a.blePin.contentEquals(b.blePin) &&
            a.receiverId == b.receiverId && (a.serial == null || b.serial == null || a.serial == b.serial)
}
