package re.abbot.librecr.app.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.WearAppearanceSettings
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.protocol.dataplane.GlucoseTimeline
import re.abbot.librecr.protocol.connection.AutomaticHandoff
import re.abbot.librecr.protocol.connection.PhonePriorityPolicy
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object WearDataSync {
    const val PATH_SESSION = "/librecr/session"
    const val PATH_START = "/librecr/start"
    const val PATH_STOP = "/librecr/stop"
    const val PATH_STOPPED = "/librecr/stopped"
    const val PATH_GLUCOSE = "/librecr/glucose"
    const val PATH_GLUCOSE_UNAVAILABLE = "/librecr/glucose/unavailable"
    const val PATH_SENSOR_STATUS = "/librecr/sensor_status"
    const val PATH_GLUCOSE_ACK = "/librecr/glucose/ack"
    const val PATH_GLUCOSE_REPLAY = "/librecr/glucose/replay"
    const val PATH_GLUCOSE_REPLAY_PREFIX = "/librecr/glucose/replay/"
    const val PATH_GLUCOSE_REPLAY_REQUEST = "/librecr/glucose/replay_request"
    const val PATH_WEAR_APPEARANCE = "/librecr/wear_appearance"
    const val PATH_WEAR_APPEARANCE_REQUEST = "/librecr/wear_appearance/request"
    const val PATH_LOG = "/librecr/log"
    const val PATH_LOG_REQUEST = "/librecr/log/request"
    const val PATH_DIAGNOSTIC_LOGGING = "/librecr/diagnostic_logging"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingStopAck = AtomicReference<CompletableDeferred<Boolean>?>(null)

    data class StopAckResult(
        val received: Boolean,
        val wasActive: Boolean,
    )

    data class GlucoseUnavailable(
        val lifeCount: Int,
        val trend: String,
        val receivedAtMs: Long,
        val reason: String,
        val detail: String,
    )

    data class ConnectedWearNode(
        val id: String,
        val displayName: String,
        val isNearby: Boolean,
    )

    fun parseGlucose(bytes: ByteArray): SensorStateStore.LastGlucose {
        val json = JSONObject(String(bytes))
        return SensorStateStore.LastGlucose(
            lifeCount = json.getInt("lifeCount"),
            mgDL = json.getInt("mgDL"),
            trend = json.optString("trend", "UNKNOWN"),
            receivedAtMs = json.optLong("receivedAtMs", System.currentTimeMillis()),
            deltaMgDlPerMin = if (json.has("deltaMgDlPerMin")) json.getDouble("deltaMgDlPerMin") else null,
            chartMgDL = if (json.has("chartMgDL")) json.getInt("chartMgDL") else null,
        )
    }

    /** Reconstruct the watch-side timeline shipped in the payload, stamping the phone's arrival. */
    fun parseTimeline(bytes: ByteArray, phoneReceivedTs: Long): GlucoseTimeline {
        val json = JSONObject(String(bytes))
        return GlucoseTimeline(
            lifeCount = json.getInt("lifeCount"),
            firstNotifyTs = json.optLong("firstNotifyTs", 0L),
            secondNotifyTs = json.optLong("secondNotifyTs", 0L),
            decodedTs = json.optLong("decodedTs", 0L),
            savedTs = json.optLong("savedTs", 0L),
            sentToPhoneTs = json.optLong("sentToPhoneTs", 0L),
            phoneReceivedTs = phoneReceivedTs,
        )
    }

    fun sendSession(context: Context, session: ImportedSession, startOnWatch: Boolean = false) {
        // toJson (not toProvisioningJson) so a deliberately attached phase5RawKey survives the wire:
        // the watch handoff relays the phone's CURRENT session key, letting the watch resume via the
        // cheap cached path — its 32-bit CPU cannot finish a full first-pair derivation (~31s) before
        // the sensor's ~24s mid-handshake patience runs out. All other callers pass keyless sessions.
        val payload = session.toJson().toByteArray()
        val path = if (startOnWatch) PATH_START else PATH_SESSION
        // DataItems are network-wide. Keep that offline fallback only in automatic/single-watch
        // mode; with an explicit selection the control command must use the targeted message below.
        putDataItem(context, path, payload, onlyWhenRoutingIsAutomatic = true)
        sendToNearbyNodes(
            context = context,
            path = path,
            payload = payload,
        )
    }

    fun sendStop(context: Context) {
        sendToNearbyNodes(context, PATH_STOP, ByteArray(0))
    }

    suspend fun requestStopAndWait(context: Context, timeoutMs: Long = 7_000L): Boolean =
        requestStopStatusAndWait(context, timeoutMs).wasActive

    suspend fun requestStopStatusAndWait(context: Context, timeoutMs: Long = 7_000L): StopAckResult {
        val ack = CompletableDeferred<Boolean>()
        pendingStopAck.set(ack)
        sendStop(context)
        val wasActive = withTimeoutOrNull(timeoutMs) {
            ack.await()
        }
        pendingStopAck.compareAndSet(ack, null)
        val result = StopAckResult(received = wasActive != null, wasActive = wasActive == true)
        BleLog.log("wear: stop ack received=${result.received} active=${result.wasActive}")
        return result
    }

    fun sendStopped(context: Context, wasActive: Boolean) {
        val payload = JSONObject()
            .put("wasActive", wasActive)
            .put("stoppedAtMs", System.currentTimeMillis())
            .toString()
            .toByteArray()
        sendToNearbyNodes(context, PATH_STOPPED, payload)
    }

    fun notifyStopAck(bytes: ByteArray) {
        val wasActive = parseStopAck(bytes)
        val ack = pendingStopAck.getAndSet(null)
        if (ack == null) {
            BleLog.log("wear: unsolicited stop ack active=$wasActive")
        } else {
            ack.complete(wasActive)
        }
    }

    private fun parseStopAck(bytes: ByteArray): Boolean =
        runCatching {
            if (bytes.isEmpty()) true else JSONObject(String(bytes)).optBoolean("wasActive", true)
        }.getOrDefault(true)

    fun sendGlucose(context: Context, reading: SensorStateStore.LastGlucose) {
        val json = JSONObject().apply {
            put("bleAddress", AutomaticSensorHandoff.localSensorAddress)
            put("lifeCount", reading.lifeCount)
            put("mgDL", reading.mgDL)
            put("trend", reading.trend)
            put("receivedAtMs", reading.receivedAtMs)
            reading.deltaMgDlPerMin?.let { put("deltaMgDlPerMin", it) }
            reading.chartMgDL?.takeIf { it != reading.mgDL }?.let { put("chartMgDL", it) }
        }.toString()
        val payload = json.toByteArray()
        sendToNearbyNodes(context, PATH_GLUCOSE, payload)
        putDataItem(context, PATH_GLUCOSE, payload)
    }

    /** A transient wake hint, never a persisted route command or replayable DataItem. */
    fun sendPhoneSensorLinkLost(context: Context, sensorAddress: String, lifeCount: Int, lostAtMs: Long, status: Int?) {
        val payload = JSONObject().put("bleAddress", sensorAddress).put("lifeCount", lifeCount)
            .put("lostAtMs", lostAtMs).put("status", status ?: -1).toString().toByteArray(Charsets.UTF_8)
        BleLog.log("receiver recovery: PHONE_CGM_LINK_LOST sent lc=$lifeCount status=${status ?: -1}")
        sendToNearbyNodes(context, PhonePriorityPolicy.PHONE_CGM_LINK_LOST, payload)
    }

    fun sendGlucoseUnavailable(context: Context, event: GlucoseUnavailable) {
        val payload = JSONObject()
            .put("bleAddress", AutomaticSensorHandoff.localSensorAddress)
            .put("lifeCount", event.lifeCount)
            .put("trend", event.trend)
            .put("receivedAtMs", event.receivedAtMs)
            .put("reason", event.reason)
            .put("detail", event.detail)
            .toString()
            .toByteArray()
        BleLog.log("wear: sending glucose unavailable lc=${event.lifeCount} reason=${event.reason}")
        sendToNearbyNodes(context, PATH_GLUCOSE_UNAVAILABLE, payload)
        putDataItem(context, PATH_GLUCOSE_UNAVAILABLE, payload)
    }

    fun parseGlucoseUnavailable(bytes: ByteArray): GlucoseUnavailable {
        val json = JSONObject(String(bytes))
        return GlucoseUnavailable(
            lifeCount = json.getInt("lifeCount"),
            trend = json.optString("trend", "UNKNOWN"),
            receivedAtMs = json.optLong("receivedAtMs", System.currentTimeMillis()),
            reason = json.optString("reason", "NOT_USABLE"),
            detail = json.optString("detail", ""),
        )
    }

    fun sendSensorStatus(context: Context, errorData: Int, patchState: Int, observedAtMs: Long) {
        val payload = JSONObject()
            .put("errorData", errorData)
            .put("patchState", patchState)
            .put("observedAtMs", observedAtMs)
            .toString()
            .toByteArray()
        sendToNearbyNodes(context, PATH_SENSOR_STATUS, payload)
        putDataItem(context, PATH_SENSOR_STATUS, payload)
    }

    fun parseSensorStatus(bytes: ByteArray): SensorStateStore.SensorStatusSnapshot {
        val json = JSONObject(String(bytes))
        return SensorStateStore.SensorStatusSnapshot(
            errorData = json.getInt("errorData"),
            patchState = json.getInt("patchState"),
            observedAtMs = json.optLong("observedAtMs", System.currentTimeMillis()),
        )
    }

    fun sendGlucoseAck(context: Context, lifeCount: Int) {
        val payload = JSONObject()
            .put("lifeCount", lifeCount)
            .put("ackAtMs", System.currentTimeMillis())
            .toString()
            .toByteArray()
        BleLog.log("ACK lc=$lifeCount direction=phone->watch")
        sendToNearbyNodes(context, PATH_GLUCOSE_ACK, payload)
    }

    fun requestGlucoseReplay(context: Context, fromLifeCount: Int, toLifeCount: Int) {
        if (toLifeCount < fromLifeCount) return
        val payload = JSONObject()
            .put("fromLifeCount", fromLifeCount)
            .put("toLifeCount", toLifeCount)
            .put("requestedAtMs", System.currentTimeMillis())
            .toString()
            .toByteArray()
        BleLog.log("wear: phone requested glucose replay lifeCount=$fromLifeCount..$toLifeCount")
        sendToNearbyNodes(context, PATH_GLUCOSE_REPLAY_REQUEST, payload)
    }

    fun sendAppearance(context: Context, settings: WearAppearanceSettings) {
        val payload = JSONObject(settings.toJson())
            .put("syncedAtMs", System.currentTimeMillis())
            .toString()
            .toByteArray()
        sendToNearbyNodes(context, PATH_WEAR_APPEARANCE, payload)
        putDataItem(context, PATH_WEAR_APPEARANCE, payload)
    }

    /** Ask the watch to ship its in-memory log buffer back for display in the phone's log viewer. */
    fun requestWatchLog(context: Context) {
        sendToNearbyNodes(context, PATH_LOG_REQUEST, ByteArray(0))
    }

    /**
     * Keep the watch logger aligned with the phone's persistent opt-in. The data item survives a
     * disconnected watch and the message applies the change immediately when a peer is nearby.
     */
    fun sendDiagnosticLoggingEnabled(context: Context, enabled: Boolean) {
        val payload = byteArrayOf(if (enabled) 1 else 0)
        putDataItem(context, PATH_DIAGNOSTIC_LOGGING, payload)
        sendToNearbyNodes(context, PATH_DIAGNOSTIC_LOGGING, payload)
    }

    @Synchronized
    fun sendAutomaticHandoffSettings(context: Context, enabled: Boolean, selectedWatchId: String) {
        val prefs = context.applicationContext.getSharedPreferences("librecr_automatic_handoff", Context.MODE_PRIVATE)
        val revision = maxOf(System.currentTimeMillis(), prefs.getLong("settingsRevision", 0) + 1)
        prefs.edit().putLong("settingsRevision", revision).commit()
        val payload = JSONObject().put("enabled", enabled).put("preferPhone", true).put("selectedWatchId", selectedWatchId)
            .put("syncedAtMs", revision).toString().toByteArray(Charsets.UTF_8)
        putDataItem(context, AutomaticHandoff.SETTINGS, payload)
        sendToNearbyNodes(context, AutomaticHandoff.SETTINGS, payload)
    }

    fun parseLog(bytes: ByteArray): List<String> =
        String(bytes).split('\n').filter { it.isNotBlank() }

    /** Current connected Wear nodes, exposed to the settings selector. */
    suspend fun connectedWearNodes(context: Context): List<ConnectedWearNode> =
        suspendCancellableCoroutine { continuation ->
            runCatching {
                Wearable.getNodeClient(context.applicationContext).connectedNodes
                    .addOnSuccessListener { nodes ->
                        if (continuation.isActive) {
                            continuation.resume(
                                nodes
                                    .map { ConnectedWearNode(it.id, it.displayName, it.isNearby) }
                                    .sortedWith(
                                        compareByDescending<ConnectedWearNode> { it.isNearby }
                                            .thenBy { it.displayName.lowercase() }
                                            .thenBy { it.id },
                                    ),
                            )
                        }
                    }
                    .addOnFailureListener { failure ->
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
            }.onFailure { failure ->
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }

    /** True when an inbound message/data item belongs to the selected watch (or routing is automatic). */
    suspend fun acceptsSourceNode(sourceNodeId: String): Boolean =
        WearNodeRouting.accepts(selectedWearNodeId(), sourceNodeId)

    /**
     * Apply a newly selected primary watch immediately:
     * - remove legacy broadcast control DataItems so they cannot wake another watch later;
     * - stop every currently connected non-selected watch.
     */
    fun enforceSelectedWatch(context: Context, selectedNodeId: String) {
        if (selectedNodeId.isBlank()) return
        val app = context.applicationContext
        scope.launch {
            enforceSelectedWatchNow(app, selectedNodeId)
        }
    }

    /** Re-apply the persisted selection when the app or a Wear peer reconnects. */
    fun enforcePersistedSelection(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val selectedNodeId = selectedWearNodeId()
            if (selectedNodeId.isNotBlank()) enforceSelectedWatchNow(app, selectedNodeId)
        }
    }

    private fun putDataItem(
        context: Context,
        path: String,
        payload: ByteArray,
        onlyWhenRoutingIsAutomatic: Boolean = false,
    ) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                if (onlyWhenRoutingIsAutomatic) {
                    val selectedNodeId = selectedWearNodeId()
                    if (selectedNodeId.isNotBlank()) {
                        BleLog.log("wear: skipped broadcast data item $path; selected node=$selectedNodeId")
                        return@runCatching
                    }
                }
                val request = PutDataRequest.create(path)
                    .setData(payload)
                    .setUrgent()
                Wearable.getDataClient(app).putDataItem(request)
                    .addOnSuccessListener { BleLog.log("wear: put data item $path") }
                    .addOnFailureListener { BleLog.log("wear: put data item $path failed: ${it.message}") }
            }.onFailure {
                BleLog.log("wear: put data item $path unavailable: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun sendToNearbyNodes(context: Context, path: String, payload: ByteArray) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val nodeClient = Wearable.getNodeClient(app)
                val messageClient = Wearable.getMessageClient(app)
                val selectedNodeId = selectedWearNodeId()
                nodeClient.connectedNodes
                    .addOnSuccessListener { nodes ->
                        if (nodes.isEmpty()) {
                            BleLog.log("wear: no connected Wear node for $path")
                        }
                        val targetIds = WearNodeRouting.targetNodeIds(
                            selectedNodeId,
                            nodes.map { it.id },
                        )
                        if (selectedNodeId.isNotBlank() && targetIds.isEmpty()) {
                            BleLog.log("wear: selected Wear node $selectedNodeId is not connected for $path")
                        }
                        nodes.filter { it.id in targetIds }.forEach { node ->
                            messageClient.sendMessage(node.id, path, payload)
                                .addOnSuccessListener { BleLog.log("wear: sent $path to ${node.displayName} nearby=${node.isNearby}") }
                                .addOnFailureListener { BleLog.log("wear: send $path to ${node.displayName} failed: ${it.message}") }
                        }
                    }
                    .addOnFailureListener { BleLog.log("wear: connectedNodes failed: ${it.message}") }
            }.onFailure {
                BleLog.log("wear: send $path unavailable: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private suspend fun selectedWearNodeId(): String =
        runCatching { GlucoRitm.settings.current().selectedWearNodeId }
            .onFailure {
                BleLog.log(
                    "wear: selected node lookup failed; using automatic routing: " +
                        (it.message ?: it::class.java.simpleName),
                )
            }
            .getOrDefault("")

    private suspend fun enforceSelectedWatchNow(context: Context, selectedNodeId: String) {
        deleteLegacyBroadcastControlItems(context)
        runCatching {
            val nodes = connectedWearNodes(context)
            val stopIds = WearNodeRouting.nonSelectedNodeIds(selectedNodeId, nodes.map { it.id })
            if (stopIds.isEmpty()) {
                BleLog.log("wear: selected node enforced; no other connected watches")
                return@runCatching
            }
            val messageClient = Wearable.getMessageClient(context)
            nodes.filter { it.id in stopIds }.forEach { node ->
                messageClient.sendMessage(node.id, PATH_STOP, ByteArray(0))
                    .addOnSuccessListener {
                        BleLog.log("wear: stopped non-selected watch ${node.displayName}")
                    }
                    .addOnFailureListener {
                        BleLog.log(
                            "wear: stop non-selected watch ${node.displayName} failed: ${it.message}",
                        )
                    }
            }
        }.onFailure {
            BleLog.log(
                "wear: enforce selected node failed: ${it.message ?: it::class.java.simpleName}",
            )
        }
    }

    private fun deleteLegacyBroadcastControlItems(context: Context) {
        val dataClient = Wearable.getDataClient(context)
        listOf(PATH_START, PATH_SESSION).forEach { path ->
            val uri = Uri.Builder()
                .scheme("wear")
                .authority("*")
                .path(path)
                .build()
            dataClient.deleteDataItems(uri, DataClient.FILTER_LITERAL)
                .addOnSuccessListener { deleted ->
                    BleLog.log("wear: removed $deleted legacy broadcast data item(s) for $path")
                }
                .addOnFailureListener {
                    BleLog.log("wear: remove legacy broadcast data item $path failed: ${it.message}")
                }
        }
    }
}
