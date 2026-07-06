package re.abbot.librecr.app.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.WearAppearanceSettings
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.protocol.dataplane.GlucoseTimeline
import java.util.concurrent.atomic.AtomicReference

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
    private const val LOG_SYNC_MAX_LINES = 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingStopAck = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val glucoseBufferLock = Any()
    private val glucoseBuffer = LinkedHashMap<Int, BufferedGlucose>()
    @Volatile private var retryLoopStarted = false

    data class ReplayRequest(
        val fromLifeCount: Int,
        val toLifeCount: Int,
    )

    data class GlucoseUnavailable(
        val lifeCount: Int,
        val trend: String,
        val receivedAtMs: Long,
        val reason: String,
        val detail: String,
    )

    private data class BufferedGlucose(
        val reading: SensorStateStore.LastGlucose,
        val timeline: GlucoseTimeline?,
        var lastSentAtMs: Long = 0L,
        var attempts: Int = 0,
    )

    private data class SendSnapshot(
        val reading: SensorStateStore.LastGlucose,
        val timeline: GlucoseTimeline?,
        val sentAtMs: Long,
        val attempt: Int,
    )

    fun sendSession(context: Context, session: ImportedSession, startOnWatch: Boolean = true) {
        sendToNearbyNodes(context, if (startOnWatch) PATH_START else PATH_SESSION, session.toProvisioningJson().toByteArray())
    }

    fun sendStop(context: Context) {
        sendToNearbyNodes(context, PATH_STOP, ByteArray(0))
    }

    suspend fun requestStopAndWait(context: Context, timeoutMs: Long = 7_000L): Boolean {
        val ack = CompletableDeferred<Boolean>()
        pendingStopAck.set(ack)
        sendStop(context)
        val wasActive = withTimeoutOrNull(timeoutMs) {
            ack.await()
        } ?: false
        pendingStopAck.compareAndSet(ack, null)
        BleLog.log("wear: stop ack received active=$wasActive")
        return wasActive
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

    fun sendGlucose(context: Context, reading: SensorStateStore.LastGlucose, timeline: GlucoseTimeline? = null) {
        val app = context.applicationContext
        rememberGlucose(reading, timeline)
        ensureRetryLoop(app)
        sendBufferedGlucose(
            context = app,
            lifeCount = reading.lifeCount,
            messagePath = PATH_GLUCOSE,
            reason = "live",
            latestDataItem = true,
            replayDataItem = false,
        )
    }

    /**
     * Relay a reading recovered via on-watch backfill (an earlier minute the watch missed live) to the
     * phone. Goes on the replay path — buffered + retried like a live reading and serveable to future
     * replay requests, but never published as the "latest" value (it is older than the current reading;
     * the phone drops stale latest and only fills its history gap).
     */
    fun sendBackfilledGlucose(context: Context, reading: SensorStateStore.LastGlucose) {
        val app = context.applicationContext
        rememberGlucose(reading, null)
        ensureRetryLoop(app)
        sendBufferedGlucose(
            context = app,
            lifeCount = reading.lifeCount,
            messagePath = PATH_GLUCOSE_REPLAY,
            reason = "backfill",
            latestDataItem = false,
            replayDataItem = true,
        )
    }

    fun sendGlucoseUnavailable(context: Context, event: GlucoseUnavailable) {
        val payload = JSONObject()
            .put("lifeCount", event.lifeCount)
            .put("trend", event.trend)
            .put("receivedAtMs", event.receivedAtMs)
            .put("reason", event.reason)
            .put("detail", event.detail)
            .toString()
            .toByteArray()
        BleLog.log("wear: sending glucose unavailable lc=${event.lifeCount} reason=${event.reason}")
        sendToNearbyNodes(context, PATH_GLUCOSE_UNAVAILABLE, payload)
        putDataItem(context, PATH_GLUCOSE_UNAVAILABLE, payload, event.lifeCount)
    }

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

    /**
     * Ship the watch's in-memory log buffer (newest [LOG_SYNC_MAX_LINES]) to the phone's log viewer.
     * With [eventsOnly] only the `[WEAR-BLE]` connection narrative ships — disconnects (time, status,
     * reason, transport), reconnect attempts/success, watchdog — which is what the automatic
     * post-reconnect push uses: small, readable, and light on the shared antenna. The phone's
     * on-demand pull keeps the full verbose buffer for deep post-mortems.
     */
    fun sendLog(context: Context, eventsOnly: Boolean = false) {
        val lines = BleLog.snapshot()
        val selected = if (eventsOnly) lines.filter { it.contains(EVENT_LOG_MARKER) } else lines
        val payload = selected.takeLast(LOG_SYNC_MAX_LINES).joinToString("\n").toByteArray()
        BleLog.log("wear: sending watch log to phone (${payload.size} bytes, eventsOnly=$eventsOnly)")
        sendToNearbyNodes(context, PATH_LOG, payload)
    }

    fun parseLifeCount(bytes: ByteArray): Int =
        JSONObject(String(bytes)).getInt("lifeCount")

    fun parseReplayRequest(bytes: ByteArray): ReplayRequest {
        val json = JSONObject(String(bytes))
        return ReplayRequest(
            fromLifeCount = json.getInt("fromLifeCount"),
            toLifeCount = json.optInt("toLifeCount", Int.MAX_VALUE),
        )
    }

    fun parseAppearance(bytes: ByteArray): WearAppearanceSettings =
        WearAppearanceSettings.fromJson(String(bytes))

    fun fetchAppearance(context: Context, onAppearance: (WearAppearanceSettings) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                Wearable.getDataClient(app).dataItems
                    .addOnSuccessListener { items ->
                        try {
                            for (item in items) {
                                if (item.uri.path == PATH_WEAR_APPEARANCE) {
                                    item.data?.let { data ->
                                        runCatching { onAppearance(parseAppearance(data)) }
                                            .onFailure { BleLog.log("wear: parse cached appearance failed: ${it.message}") }
                                    }
                                }
                            }
                        } finally {
                            items.release()
                        }
                    }
                    .addOnFailureListener { BleLog.log("wear: fetch appearance data item failed: ${it.message}") }
            }.onFailure {
                BleLog.log("wear: fetch appearance unavailable: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    fun requestAppearance(context: Context) {
        sendToNearbyNodes(context, PATH_WEAR_APPEARANCE_REQUEST, ByteArray(0))
    }

    fun notifyGlucoseAck(lifeCount: Int) {
        val removed = synchronized(glucoseBufferLock) {
            glucoseBuffer.remove(lifeCount)
        }
        BleLog.log("ACK lc=$lifeCount pendingRemoved=${removed != null}")
    }

    fun replayGlucoseRange(context: Context, request: ReplayRequest) {
        val app = context.applicationContext
        val lifeCounts = synchronized(glucoseBufferLock) {
            glucoseBuffer.keys
                .filter { it >= request.fromLifeCount && it <= request.toLifeCount }
                .sorted()
        }
        BleLog.log(
            "wear: replay request from=${request.fromLifeCount} to=${request.toLifeCount} " +
                "available=${lifeCounts.size}",
        )
        lifeCounts.forEach { lifeCount ->
            sendBufferedGlucose(
                context = app,
                lifeCount = lifeCount,
                messagePath = PATH_GLUCOSE_REPLAY,
                reason = "replay",
                latestDataItem = false,
                replayDataItem = true,
            )
        }
    }

    private fun rememberGlucose(reading: SensorStateStore.LastGlucose, timeline: GlucoseTimeline?) {
        synchronized(glucoseBufferLock) {
            glucoseBuffer.remove(reading.lifeCount)
            glucoseBuffer[reading.lifeCount] = BufferedGlucose(
                reading = reading,
                timeline = timeline,
            )
            while (glucoseBuffer.size > GLUCOSE_BUFFER_SIZE) {
                val oldest = glucoseBuffer.keys.firstOrNull() ?: break
                glucoseBuffer.remove(oldest)
            }
        }
    }

    private fun ensureRetryLoop(context: Context) {
        // The started-flag and the buffer share glucoseBufferLock, so this must NOT be gated by an
        // unsynchronized fast-path: that could race the loop stopping itself (below) and leave a
        // pending reading with no running retry loop.
        synchronized(glucoseBufferLock) {
            if (retryLoopStarted) return
            retryLoopStarted = true
        }
        val app = context.applicationContext
        scope.launch {
            while (true) {
                delay(GLUCOSE_RETRY_INTERVAL_MS)
                val due = synchronized(glucoseBufferLock) {
                    if (glucoseBuffer.isEmpty()) {
                        // Everything acked → stop waking every interval. The next sendGlucose/replay
                        // restarts the loop via ensureRetryLoop (same lock, so no lost wakeup).
                        retryLoopStarted = false
                        return@launch
                    }
                    val now = System.currentTimeMillis()
                    glucoseBuffer.values
                        .filter { now - it.lastSentAtMs >= GLUCOSE_RETRY_INTERVAL_MS }
                        .map { it.reading.lifeCount }
                }
                due.forEach { lifeCount ->
                    sendBufferedGlucose(
                        context = app,
                        lifeCount = lifeCount,
                        messagePath = PATH_GLUCOSE_REPLAY,
                        reason = "retry",
                        latestDataItem = false,
                        replayDataItem = true,
                    )
                }
            }
        }
    }

    private fun sendBufferedGlucose(
        context: Context,
        lifeCount: Int,
        messagePath: String,
        reason: String,
        latestDataItem: Boolean,
        replayDataItem: Boolean,
    ) {
        val snapshot = nextSendSnapshot(lifeCount) ?: run {
            BleLog.log("wear: glucose replay unavailable lc=$lifeCount reason=$reason")
            return
        }
        val payload = buildGlucosePayload(snapshot, reason)
        snapshot.timeline?.let {
            if (it.decodedTs > 0L) {
                BleLog.log(
                    "[LATENCY] 177a→sent lc=$lifeCount decoded→sent=${snapshot.sentAtMs - it.decodedTs}ms " +
                        "sent=${snapshot.sentAtMs} attempt=${snapshot.attempt} reason=$reason",
                )
            }
        }
        BleLog.log(
            "WATCH_SEND lc=$lifeCount reason=$reason attempt=${snapshot.attempt} " +
                "messagePath=$messagePath latestDataItem=$latestDataItem replayDataItem=$replayDataItem",
        )
        sendToNearbyNodes(context, messagePath, payload)
        if (latestDataItem) {
            putDataItem(context, PATH_GLUCOSE, payload, lifeCount)
        }
        if (replayDataItem) {
            putDataItem(context, "$PATH_GLUCOSE_REPLAY_PREFIX${lifeCount.floorMod(GLUCOSE_BUFFER_SIZE)}", payload, lifeCount)
        }
    }

    private fun nextSendSnapshot(lifeCount: Int): SendSnapshot? {
        val sentAt = System.currentTimeMillis()
        return synchronized(glucoseBufferLock) {
            val item = glucoseBuffer[lifeCount] ?: return@synchronized null
            item.attempts += 1
            item.lastSentAtMs = sentAt
            SendSnapshot(
                reading = item.reading,
                timeline = item.timeline,
                sentAtMs = sentAt,
                attempt = item.attempts,
            )
        }
    }

    private fun buildGlucosePayload(snapshot: SendSnapshot, reason: String): ByteArray {
        val reading = snapshot.reading
        return JSONObject().apply {
            put("lifeCount", reading.lifeCount)
            put("mgDL", reading.mgDL)
            put("trend", reading.trend)
            put("receivedAtMs", reading.receivedAtMs)
            reading.deltaMgDlPerMin?.let { put("deltaMgDlPerMin", it) }
            reading.chartMgDL?.takeIf { it != reading.mgDL }?.let { put("chartMgDL", it) }
            snapshot.timeline?.let { t ->
                put("firstNotifyTs", t.firstNotifyTs)
                put("secondNotifyTs", t.secondNotifyTs)
                put("decodedTs", t.decodedTs)
                put("savedTs", t.savedTs)
            }
            put("sentToPhoneTs", snapshot.sentAtMs)
            put("transportAttempt", snapshot.attempt)
            put("transportReason", reason)
            put("transportAttemptTs", snapshot.sentAtMs)
        }.toString().toByteArray()
    }

    private fun putDataItem(context: Context, path: String, payload: ByteArray, lifeCount: Int? = null) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val request = PutDataRequest.create(path)
                    .setData(payload)
                    .setUrgent()
                Wearable.getDataClient(app).putDataItem(request)
                    .addOnSuccessListener {
                        BleLog.log("wear: put data item $path${lifeCount?.let { " lc=$it" } ?: ""}")
                    }
                    .addOnFailureListener {
                        BleLog.log("wear: put data item $path${lifeCount?.let { " lc=$it" } ?: ""} failed: ${it.message}")
                    }
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
                nodeClient.connectedNodes
                    .addOnSuccessListener { nodes ->
                        if (nodes.isEmpty()) {
                            BleLog.log("[ANOMALY] wear: no connected Wear node for $path; DataItem/retry buffer remain active")
                        }
                        nodes.forEach { node ->
                            messageClient.sendMessage(node.id, path, payload)
                                .addOnSuccessListener { BleLog.log("wear: sent $path to ${node.displayName} nearby=${node.isNearby}") }
                                .addOnFailureListener { BleLog.log("[ANOMALY] wear: send $path to ${node.displayName} failed: ${it.message}") }
                        }
                    }
                    .addOnFailureListener { BleLog.log("wear: connectedNodes failed: ${it.message}") }
            }.onFailure {
                BleLog.log("wear: send $path unavailable: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private const val GLUCOSE_BUFFER_SIZE = 60
    private const val GLUCOSE_RETRY_INTERVAL_MS = 20_000L
    /** Tag selecting the connection-narrative lines for the events-only log ship. */
    private const val EVENT_LOG_MARKER = "[WEAR-BLE]"

    private fun Int.floorMod(modulus: Int): Int {
        val remainder = this % modulus
        return if (remainder >= 0) remainder else remainder + modulus
    }
}
