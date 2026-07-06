package re.abbot.librecr.app.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.log.GlucoseLatencyTracer
import re.abbot.librecr.app.alarm.SensorAttentionNotifier
import re.abbot.librecr.app.wear.WearDataSync
import re.abbot.librecr.app.wear.complication.LibreComplicationUpdater
import re.abbot.librecr.protocol.dataplane.DataFrame
import re.abbot.librecr.protocol.dataplane.DataPlaneChannel
import re.abbot.librecr.protocol.dataplane.DataPlaneCrypto
import re.abbot.librecr.protocol.dataplane.DataPlaneDecodedPayload
import re.abbot.librecr.protocol.dataplane.DataPlaneDecoder
import re.abbot.librecr.protocol.dataplane.DataPlaneNotificationAssembler
import re.abbot.librecr.protocol.dataplane.DataPlanePacketKind
import re.abbot.librecr.protocol.dataplane.GlucoseTimeline
import re.abbot.librecr.protocol.dataplane.Libre3SensorCondition
import re.abbot.librecr.protocol.dataplane.PatchControlCommand
import re.abbot.librecr.protocol.dataplane.RealtimeGlucoseReading
import re.abbot.librecr.protocol.crypto.AesCcmException
import re.abbot.librecr.protocol.pairing.PairingFlow
import re.abbot.librecr.protocol.pairing.PairingFlowException
import re.abbot.librecr.protocol.pairing.PhoneCert
import re.abbot.librecr.protocol.pairing.SessionKey
import re.abbot.librecr.protocol.toHex
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionState { IDLE, SCANNING, CONNECTING, HANDSHAKING, STREAMING, RECONNECTING, ERROR }

data class GlucoseUi(
    val mgDL: Int?,
    val trend: String,
    val lifeCount: Int,
    val usable: Boolean,
    val receivedAtMs: Long,
    val deltaMgDlPerMin: Double? = null,
    val readingIssue: String? = null,
    val readingIssueDetail: String? = null,
)

private data class BackfillRequest(
    val fromLifeCount: Int,
    val reason: String,
)

/**
 * The live in-memory reading → the display/persistence model, or null for a non-usable reading.
 * Lets the watch UI and complications read straight from the manager's StateFlow (instant) instead
 * of waiting on the DataStore round-trip.
 */
fun GlucoseUi.toLastGlucose(): SensorStateStore.LastGlucose? {
    if (!usable) return null
    val mg = mgDL ?: return null
    return SensorStateStore.LastGlucose(lifeCount, mg, trend, receivedAtMs, deltaMgDlPerMin)
}

private const val GLUCOSE_UNAVAILABLE_FRESH_MS = 6 * 60_000L

/**
 * True while the latest realtime glucose packet has no displayable value. This is not, by itself,
 * a sensor-error signal; patch-status attention remains the source of truth for real sensor errors.
 */
fun GlucoseUi?.isActiveGlucoseUnavailable(nowMs: Long = System.currentTimeMillis()): Boolean =
    this != null && !usable && receivedAtMs > 0L && nowMs - receivedAtMs < GLUCOSE_UNAVAILABLE_FRESH_MS

/**
 * Owns the full connection lifecycle: scan → connect → security handshake
 * (fresh command-gated local derivation every attempt) → post-handshake CCCD enable →
 * per-minute data loop, with self-healing reconnect and a no-data watchdog.
 * This is where "connects stably + delivers glucose every minute" lives.
 */
class SensorConnectionManager(
    private val context: Context,
    private val store: SensorStateStore,
) {
    private val _state = MutableStateFlow(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = _state

    private val _glucose = MutableStateFlow<GlucoseUi?>(null)
    val glucose: StateFlow<GlucoseUi?> = _glucose

    private val _statusLine = MutableStateFlow("idle")
    val statusLine: StateFlow<String> = _statusLine

    private val lastGlucoseAt = AtomicLong(0)
    private var loopJob: Job? = null
    @Volatile private var activeConnection: SensorConnection? = null
    @Volatile private var cachedPhase5RawKey: ByteArray? = null
    /** Consecutive non-mismatch cached-reconnect failures; a correct key survives transient blips. */
    @Volatile private var cachedReconnectFailures: Int = 0
    @Volatile private var lastDecodedLifeCount: Int? = null
    @Volatile private var lastSentReading: SensorStateStore.LastGlucose? = null
    /** Last (errorData, patchState) pair we persisted/relayed, so we act only on real transitions. */
    @Volatile private var lastSensorStatus: Pair<Int, Int>? = null

    // --- Centralized sensor reconnect (the ONLY path that re-establishes the sensor BLE link). Driven
    // exclusively by validated sensor-side signals — GATT disconnect, no-data watchdog, fragment-assembly
    // timeout. The phone / Data Layer NEVER triggers it. See requestSensorReconnect(). ---
    @Volatile private var sensorOnline = false
    @Volatile private var reconnectSignal: CompletableDeferred<Unit>? = null
    @Volatile private var pendingReconnectReason: String = "initial"
    @Volatile private var lastDisconnectStatus: Int? = null
    @Volatile private var reconnectAttempt = 0
    @Volatile private var reconnectStartedAtMs = 0L
    @Volatile private var awaitingFirstReadingAfterReconnect = false
    @Volatile private var reconnectLastGoodLifeCount: Int? = null
    /** Rate-limits shipping the watch log to the phone on reconnect (a ~500-line Data Layer push). */
    @Volatile private var lastLogShipAtMs = 0L
    // Backfill coalescing: clinicalBackfillGreaterEqual(start) already covers every HIGHER start, and
    // the reconnect path + the first reading's gap detection request the same range seconds apart.
    @Volatile private var lastBackfillRequestFrom: Int? = null
    @Volatile private var lastBackfillRequestAtMs = 0L

    // DataStore persistence runs OFF the decode→ui→send path: a single conflated consumer drains
    // only the latest reading, so a slow write (Wear doze can stall flash I/O for tens of seconds)
    // can never block a CGM reading or pile up a backlog. DataStore is now cold-start/persistence only.
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistChannel = Channel<SensorStateStore.LastGlucose>(Channel.CONFLATED)

    init {
        persistScope.launch {
            for (reading in persistChannel) {
                val startedAt = System.currentTimeMillis()
                runCatching { store.saveRemoteGlucose(reading) }
                    .onFailure { BleLog.log("persist: lc=${reading.lifeCount} save failed: ${it.message}") }
                val editMs = System.currentTimeMillis() - startedAt
                if (editMs > PERSIST_SLOW_WARN_MS) {
                    BleLog.log("[ANOMALY] persist: DataStore edit ${editMs}ms lc=${reading.lifeCount} (off critical path; ui/send unaffected)")
                } else {
                    BleLog.log("[PERSIST] DataStore edit ${editMs}ms lc=${reading.lifeCount} (off critical path)")
                }
            }
        }
    }

    private val adapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    fun start(
        scope: CoroutineScope,
        session: ImportedSession,
        allowCandidateFirstPair: Boolean = false,
    ) {
        // Never run two loops. The watch is started from several uncoordinated triggers (boot
        // receiver, BT-state-change receiver, activity, phone session relay); previously the guard
        // was bypassed while deriving (`&& !allowCandidateFirstPair`), so those calls cancelled and
        // restarted each other into churn — a fresh scan + slow first-pair each time — which both
        // wedges startup and drops readings. A single running loop self-heals (reconnect + watchdog).
        if (loopJob?.isActive == true) {
            BleLog.log("manager: start ignored; connection loop already active")
            return
        }
        loopJob?.cancel()
        cachedReconnectFailures = 0
        lastSensorStatus = null
        reconnectAttempt = 0
        sensorOnline = false
        reconnectSignal = null
        awaitingFirstReadingAfterReconnect = false
        cachedPhase5RawKey = session.phase5RawKey?.copyOf()
        val localProvisioning = session.withoutTransientCrypto()
        BleLog.log(
            "manager: starting independent BLE loop; cachedResumeKey=${cachedPhase5RawKey != null} " +
                "allowCandidateFirstPair=$allowCandidateFirstPair transientKeyPersisted=false"
        )
        loopJob = scope.launch {
            // The loop must never end silently: it is the only thing keeping readings alive, and a
            // dead loop looks exactly like "stale value, no reconnect, empty log" in the field.
            try {
                runLoop(localProvisioning)
                reconLog("SENSOR_LOOP_EXITED normally (unexpected — loop should only end by cancel)")
            } catch (e: CancellationException) {
                reconLog("SENSOR_LOOP_EXITED cancelled (stop/restart)")
                throw e
            } catch (e: Throwable) {
                reconLog("SENSOR_LOOP_CRASHED ${e::class.simpleName}: ${e.message}")
                _statusLine.value = "loop crashed: ${e.message}"
                throw e
            }
        }
    }

    fun stop() {
        val job = loopJob
        loopJob = null
        job?.cancel()
        activeConnection?.disconnect()
        activeConnection?.close()
        activeConnection = null
        _state.value = ConnectionState.IDLE
        _statusLine.value = "stopped"
        BleLog.log("manager: stop requested; active GATT closed")
    }

    /**
     * The single, centralized sensor-reconnect funnel. Every validated sensor-side disconnect signal
     * routes here; nothing else — and never the phone / Data Layer — may restart the sensor link. Safe
     * to call from any thread (GATT callback, watchdog, collector). It does NOT touch the GATT itself:
     * it only records the cause and signals the running loop, which performs the close + scan + reconnect.
     */
    private fun requestSensorReconnect(reason: String, status: Int? = null) {
        reconLog("SENSOR_RECONNECT_REQUEST reason=$reason status=${status ?: -1} attempt=$reconnectAttempt")
        val signal = reconnectSignal
        if (!sensorOnline || signal == null || signal.isCompleted) {
            reconLog("SENSOR_RECONNECT_SUPPRESSED_ALREADY_RUNNING reason=$reason")
            return
        }
        sensorOnline = false
        pendingReconnectReason = reason
        lastDisconnectStatus = status
        reconnectLastGoodLifeCount = lastSentReading?.lifeCount ?: lastDecodedLifeCount
        reconnectStartedAtMs = System.currentTimeMillis()
        awaitingFirstReadingAfterReconnect = true
        val age = if (lastGlucoseAt.get() > 0) System.currentTimeMillis() - lastGlucoseAt.get() else -1

        reconLog(
            "SENSOR_DISCONNECTED status=${describeGattStatus(status)} lastPacketAgeMs=$age attempt=$reconnectAttempt " +
                "reason=$reason lastGoodLifeCount=${reconnectLastGoodLifeCount ?: -1}",
        )
        // Deliberately NO log ship here: pushing ~500 log lines to the phone rides Bluetooth
        // Classic when the Wear link has no Wi-Fi (typically outdoors), contending for the shared
        // combo antenna exactly while the BLE reconnect needs it. The ship on reconnect success
        // carries the whole drop narrative anyway, and the phone can pull the buffer on demand.
        logTransportSnapshot("disconnect", status)
        signal.complete(Unit)
    }

    private fun reconLog(message: String) = BleLog.log("$WEAR_BLE_TAG $message")

    /**
     * Keeps the numeric token greppable (`status=8/...`) while making the shipped narrative
     * self-explanatory — the reader shouldn't need the HCI error-code table on their phone.
     */
    private fun describeGattStatus(status: Int?): String = when (status) {
        null -> "-1"
        0 -> "0/OK_LOCAL_DISCONNECT"
        8 -> "8/LINK_TIMEOUT_SIGNAL_LOST"
        19 -> "19/TERMINATED_BY_SENSOR"
        22 -> "22/TERMINATED_BY_WATCH"
        62 -> "62/FAILED_TO_ESTABLISH"
        133 -> "133/GATT_ERROR"
        147 -> "147/CONNECT_TIMEOUT"
        else -> status.toString()
    }

    /**
     * Ship the connection narrative to the phone, at most once per [LOG_SHIP_MIN_INTERVAL_MS] —
     * a reconnect storm must not push the Data Layer every few seconds. Called ONLY on reconnect
     * success (first reading), never at disconnect time: without Wi-Fi the push rides Bluetooth
     * Classic and steals the shared antenna from the BLE reconnect itself. Ships events-only
     * (the timestamped `[WEAR-BLE]` lines: drop time/status/reason, transport, recovery) — the
     * phone's log screen can always pull the full verbose buffer on demand.
     */
    private fun shipLogRateLimited() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastLogShipAtMs >= LOG_SHIP_MIN_INTERVAL_MS) {
            lastLogShipAtMs = nowMs
            WearDataSync.sendLog(context, eventsOnly = true)
        }
    }

    /**
     * One log line correlating a link event with the watch's current connectivity: the active
     * network's transports (Wi-Fi vs the Bluetooth companion proxy) and the Wear node state.
     * This is what separates "drops happen when the phone link rides Bluetooth (no Wi-Fi)" from
     * "drops happen anywhere" — both lookups are local Play Services/system state, zero radio.
     */
    private fun logTransportSnapshot(trigger: String, status: Int? = null) {
        val statusLabel = describeGattStatus(status)
        val net = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching "none"
            buildList {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BT_PROXY")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELL")
            }.ifEmpty { listOf("OTHER") }.joinToString("|")
        }.getOrElse { "unavailable" }
        runCatching {
            Wearable.getNodeClient(context).connectedNodes
                .addOnSuccessListener { nodes ->
                    reconLog(
                        "WATCH_LINK_TRANSPORT trigger=$trigger status=$statusLabel net=$net " +
                            "nodes=${nodes.size} nearby=${nodes.count { it.isNearby }}",
                    )
                }
                .addOnFailureListener {
                    reconLog("WATCH_LINK_TRANSPORT trigger=$trigger status=$statusLabel net=$net nodes=? (${it.message})")
                }
        }.onFailure {
            reconLog("WATCH_LINK_TRANSPORT trigger=$trigger status=$statusLabel net=$net nodes=unavailable")
        }
    }

    /**
     * Backoff keyed to the attempt number (1-based): fast first retries, then a bounded climb. Capped at
     * [RECONNECT_BACKOFF_MAX_MS] — never the old 60s. The sensor re-advertises within seconds of a real
     * drop, so attempt 1 fires almost immediately.
     */
    private fun reconnectDelayMs(attempt: Int): Long {
        val idx = (attempt - 1).coerceIn(0, RECONNECT_BACKOFF_MS.size - 1)
        return RECONNECT_BACKOFF_MS[idx].coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
    }

    /** Brief wake lock around the critical connect/discover burst so Wear doze can't suspend the CPU
     *  mid-reconnect. Auto-released after [RECONNECT_WAKE_LOCK_TIMEOUT_MS]; released in finally; never held long. */
    private suspend fun <T> withReconnectWakeLock(block: suspend () -> T): T {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreCR:BleReconnect")
            ?: return block()
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(RECONNECT_WAKE_LOCK_TIMEOUT_MS)
        return try {
            block()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    fun acceptRemoteGlucose(reading: SensorStateStore.LastGlucose) {
        val current = _glucose.value
        if (current != null && current.lifeCount > reading.lifeCount) {
            BleLog.log("WATCH_STATE_UPDATED skipped stale remote lc=${reading.lifeCount} current=${current.lifeCount}")
            return
        }
        if (current != null && current.lifeCount == reading.lifeCount) {
            BleLog.log(
                "WATCH_STATE_UPDATED skipped duplicate remote lc=${reading.lifeCount} " +
                    "currentTrend=${current.trend} incomingTrend=${reading.trend}",
            )
            return
        }
        _glucose.value = GlucoseUi(
            mgDL = reading.mgDL,
            trend = reading.trend,
            lifeCount = reading.lifeCount,
            usable = true,
            receivedAtMs = reading.receivedAtMs,
            deltaMgDlPerMin = reading.deltaMgDlPerMin,
        )
        lastSentReading = reading
        persistChannel.trySend(reading)
        BleLog.log("WATCH_STATE_UPDATED lc=${reading.lifeCount} source=remote")
    }

    /** Publish a non-numeric realtime sensor reading relayed by the phone. */
    fun acceptRemoteGlucoseUnavailable(event: WearDataSync.GlucoseUnavailable) {
        val current = _glucose.value
        if (current != null && current.lifeCount > event.lifeCount) {
            BleLog.log("WATCH_STATE_UPDATED skipped stale remote unavailable lc=${event.lifeCount} current=${current.lifeCount}")
            return
        }
        if (current != null && current.lifeCount == event.lifeCount && !current.usable) {
            BleLog.log("WATCH_STATE_UPDATED skipped duplicate remote unavailable lc=${event.lifeCount}")
            return
        }
        _glucose.value = GlucoseUi(
            mgDL = null,
            trend = event.trend,
            lifeCount = event.lifeCount,
            usable = false,
            receivedAtMs = event.receivedAtMs,
            readingIssue = event.reason,
            readingIssueDetail = event.detail,
        )
        LibreComplicationUpdater.requestAll(context, event.lifeCount)
        BleLog.log(
            "WATCH_STATE_UPDATED lc=${event.lifeCount} source=remote usable=false " +
                "issue=${event.reason} ${event.detail}",
        )
    }

    suspend fun stopAndJoin(timeoutMs: Long = STOP_JOIN_TIMEOUT_MS): Boolean {
        val job = loopJob
        stop()
        if (job == null) return true
        return withTimeoutOrNull(timeoutMs) {
            job.join()
            true
        } ?: false
    }

    private suspend fun runLoop(session: ImportedSession) {
        val scanner = SensorScanner(adapter)
        var currentSession = session
        loadCachedPhase5RawKeyIfNeeded()

        while (kotlin.coroutines.coroutineContext.isActive) {
            var conn: SensorConnection? = null
            // The real cause of THIS attempt's teardown. pendingReconnectReason only updates via
            // requestSensorReconnect (streaming-time drops), so on a connect/handshake failure it
            // still holds the PREVIOUS drop's reason — logging it misled a field post-mortem
            // (SENSOR_GATT_CLOSED said gatt_disconnect when the truth was a CCCD op timeout).
            var attemptFailure: String? = null
            try {
                if (!adapter.isEnabled) {
                    _state.value = ConnectionState.RECONNECTING
                    _statusLine.value = "Bluetooth oprit; aștept repornirea"
                    BleLog.log("manager: Bluetooth disabled; waiting before scan")
                    waitForBluetoothEnabled()
                    reconnectAttempt = 0
                    continue
                }

                val canResume = cachedPhase5RawKey != null
                // Stale-key recovery: if the sensor was re-paired elsewhere (another app, or the
                // phone re-provisioned), the cached key fails with a link drop (status=19 right
                // after Phase 5) — which is indistinguishable from a transient blip, so the key is
                // never "provably" wrong and the cached path would retry forever. After a few
                // consecutive cached failures, PROBE a full first-pair while KEEPING the cached key
                // as fallback: success replaces the key; refusal loses nothing.
                val probeFullHandshake = canResume &&
                    cachedReconnectFailures >= CACHED_RECONNECT_PROBE_AFTER_FAILURES
                _statusLine.value = when {
                    probeFullHandshake -> "preparing full-handshake probe"
                    canResume -> "preparing cached reconnect"
                    else -> "preparing local handshake"
                }
                BleLog.log(
                    when {
                        probeFullHandshake ->
                            "manager: probing full first-pair after $cachedReconnectFailures cached failures (cached key kept as fallback)"
                        canResume -> "manager: reconnect attempt will try cached session resume"
                        else -> "manager: reconnect attempt will use fresh local handshake material"
                    }
                )
                val firstPairEphemeral =
                    if (!canResume || probeFullHandshake) prepareFirstPairEphemeral() else null
                kotlin.coroutines.coroutineContext.ensureActive()

                // Reconnect strategy for a KNOWN sensor address: hand off to the BLE controller with
                // connectGatt(autoConnect=true) instead of an app-level active scan. Repeated active
                // scans hit Android's 5-scans-per-30s throttle — after that startScan is silently
                // ignored (no results, no error), which is exactly why our scan passes were running
                // far past their timeouts and never finding the sensor. The controller-level
                // auto-connect is NOT throttled, survives Doze/CPU-suspend on the watch, and latches
                // onto the sensor the instant it re-advertises (the Android analogue of CoreBluetooth's
                // passive connect on iOS). Active scanning is kept only as a fallback when we have no
                // confirmed BLE address yet.
                val knownAddress = currentSession.bleAddress
                    .takeIf { BluetoothAdapter.checkBluetoothAddress(it.uppercase()) }
                    ?.uppercase()
                val device: BluetoothDevice
                val autoConnect: Boolean
                if (knownAddress != null) {
                    device = adapter.getRemoteDevice(knownAddress)
                    autoConnect = reconnectAttempt > 0
                    _state.value = ConnectionState.CONNECTING
                    _statusLine.value = "connecting $knownAddress"
                    reconLog("SENSOR_DIRECT_CONNECT target=$knownAddress autoConnect=$autoConnect attempt=$reconnectAttempt")
                } else {
                    _state.value = ConnectionState.SCANNING
                    _statusLine.value = "scanning for ${currentSession.bleAddress}"
                    val scanTimeoutMs = if (reconnectAttempt == 0) COLD_SCAN_TIMEOUT_MS else RECONNECT_SCAN_TIMEOUT_MS
                    reconLog("SENSOR_SCAN_START target=${currentSession.bleAddress} timeoutMs=$scanTimeoutMs attempt=$reconnectAttempt")
                    val scan = scanner.findSensor(currentSession.bleAddress, currentSession.bleDeviceName, timeoutMs = scanTimeoutMs)
                    if (scan == null) {
                        reconLog("SENSOR_SCAN_TIMEOUT target=${currentSession.bleAddress}")
                        throw IllegalStateException("sensor not found")
                    }
                    reconLog("SENSOR_SCAN_FOUND device=${scan.device.address}")
                    currentSession = rememberObservedIdentity(currentSession, scan)
                    device = scan.device
                    autoConnect = false
                    _state.value = ConnectionState.CONNECTING
                    _statusLine.value = "connecting ${device.address}"
                }
                reconLog("SENSOR_CONNECT_START device=${device.address} autoConnect=$autoConnect")
                val c = SensorConnection(context, device)
                conn = c
                activeConnection = c
                val disconnected = CompletableDeferred<Unit>()
                reconnectSignal = disconnected
                // The GATT callback fires on a real sensor-side link drop — a validated reconnect trigger.
                c.onDisconnected = { status -> requestSensorReconnect("gatt_disconnect", status) }
                // Short wake lock only around the critical connect/discover burst (auto-released).
                // With autoConnect=true the controller waits/connects in the background, so the lock
                // expiring before the link comes up is fine — the GATT callback wakes us on connect.
                withReconnectWakeLock {
                    // Libre 3 accepts connections on ~minute-spaced windows → long connect ceiling.
                    c.connectAndDiscover(connectTimeoutMs = 120_000, discoverTimeoutMs = 45_000, autoConnect = autoConnect)
                }

                _state.value = ConnectionState.HANDSHAKING
                _statusLine.value = if (cachedPhase5RawKey != null) "handshake: cached reconnect" else "handshake: full local derivation"
                val auth = withHandshakeWakeLock {
                    authorize(c, currentSession, firstPairEphemeral, probeFullHandshake).also {
                        c.refreshDataPlaneNotifications()
                    }
                }
                val material = auth.result.sessionMaterial
                // Key prefixes only — the full session key in a log that ships to the phone would
                // let any log holder decrypt a captured BLE glucose stream.
                BleLog.log("manager: ${auth.mode} authorized kEnc=${material.kEnc.toHex().take(8)}… ivEnc=${material.ivEnc.toHex().take(8)}…")

                val crypto = DataPlaneCrypto(material.kEnc, material.ivEnc)
                val decoder = DataPlaneDecoder(crypto)
                val patchControl = PatchControlBackfillWriter(c, crypto, POST_AUTH_PATCH_CONTROL_TIMEOUT_MS)
                
                _state.value = ConnectionState.STREAMING
                _statusLine.value = "streaming"
                lastGlucoseAt.set(System.currentTimeMillis())
                sensorOnline = true
                // Fresh connection ⇒ fresh backfill dedup window: a request from the previous
                // link died with it, so an identical fromLifeCount must be re-sendable now.
                lastBackfillRequestFrom = null
                reconLog("SENSOR_RECONNECTED device=${device.address} attempt=$reconnectAttempt resume=${cachedPhase5RawKey != null}")
                logTransportSnapshot("streaming_start")
                // Diagnostic only (HCI query, no renegotiation): SENSOR_PHY in the shipped log
                // answers whether the link runs at 2M — the precondition for ever trying a 1M force.
                c.readPhy()

                coroutineScope {
                    val backfillRequests = Channel<BackfillRequest>(Channel.UNLIMITED)
                    val backfill = launch {
                        for (request in backfillRequests) {
                            sendBackfillFrom(patchControl, request.fromLifeCount, request.reason)
                        }
                    }
                    val collectors = listOf(
                        launch { collectGlucose(c, decoder, backfillRequests) },
                        launch { collectChannel(c, decoder, LibreSensorGatt.PATCH_STATUS, DataPlaneChannel.PATCH_STATUS) },
                        launch { collectChannel(c, decoder, LibreSensorGatt.HISTORIC_DATA, DataPlaneChannel.HISTORIC_DATA) },
                        launch { collectChannel(c, decoder, LibreSensorGatt.CLINICAL_DATA, DataPlaneChannel.CLINICAL_DATA) },
                    )
                    launch { enqueueImmediateReconnectBackfill(backfillRequests) }
                    val watchdog = launch { watchdog(c) }

                    disconnected.await()
                    collectors.forEach { it.cancel() }
                    backfill.cancel()
                    backfillRequests.close()
                    watchdog.cancel()
                }
            } catch (e: TimeoutCancellationException) {
                // A GATT op timed out (withTimeout) — a reconnectable failure like any other GATT
                // error. This branch MUST precede CancellationException: TimeoutCancellationException
                // is a SUBCLASS of it, and the rethrow below silently killed the whole reconnect loop
                // (field log 2026-07-05: post-handshake CCCD re-arm on 1bee timed out → loop dead →
                // stale reading for 14+ min, no reconnect until app relaunch).
                attemptFailure = "op_timeout: ${e.message}"
                reconLog("SENSOR_RECONNECT_FAILED reason=op_timeout ${e.message}")
                _statusLine.value = "error: ${e.message}"
            } catch (e: CancellationException) {
                attemptFailure = "cancelled"
                conn?.disconnect(); conn?.close()
                throw e
            } catch (e: Exception) {
                attemptFailure = e.message ?: e::class.java.simpleName
                reconLog("SENSOR_RECONNECT_FAILED reason=${e.message}")
                _statusLine.value = "error: ${e.message}"
            } finally {
                sensorOnline = false
                reconnectSignal = null
                if (conn != null) {
                    conn.close()
                    reconLog("SENSOR_GATT_CLOSED reason=${attemptFailure ?: pendingReconnectReason}")
                }
                if (activeConnection === conn) activeConnection = null
            }

            reconnectAttempt += 1
            val delayMs = reconnectDelayMs(reconnectAttempt)
            _state.value = ConnectionState.RECONNECTING
            _statusLine.value = "reconnecting in ${delayMs / 1000}s"
            reconLog(
                "SENSOR_RECONNECT_DELAY attempt=$reconnectAttempt ms=$delayMs reason=$pendingReconnectReason " +
                    "status=${lastDisconnectStatus ?: -1} resume=${cachedPhase5RawKey != null}"
            )
            delay(delayMs)
            reconLog("SENSOR_BACKOFF_NEXT delayMs=${reconnectDelayMs(reconnectAttempt + 1)}")
        }
    }

    private suspend fun prepareFirstPairEphemeral(): SessionKey.FirstPairNativeEphemeral {
        _statusLine.value = "deriving first-pair key (one-time)"
        BleLog.log("manager: deriving fresh first-pair native ephemeral locally...")
        val random = SecureRandom()
        val ephemeral = SessionKey.makeFirstPairNativeEphemeral { count ->
            ByteArray(count).also { random.nextBytes(it) }
        }
        BleLog.log("manager: first-pair ephemeral ready attempts=${ephemeral.attempts} pub=${ephemeral.phoneEphemeralPub65.toHex()}")
        return ephemeral
    }

    private suspend fun waitForBluetoothEnabled() {
        while (kotlin.coroutines.coroutineContext.isActive && !adapter.isEnabled) {
            delay(1_000L)
        }
        if (kotlin.coroutines.coroutineContext.isActive) {
            BleLog.log("manager: Bluetooth enabled; settling before reconnect")
            delay(1_000L)
        }
    }

    private data class AuthorizationOutcome(
        val result: PairingFlow.AuthorizationResult,
        val mode: String,
    )

    private suspend fun authorize(
        conn: SensorConnection,
        session: ImportedSession,
        firstPairEphemeral: SessionKey.FirstPairNativeEphemeral?,
        probeFullHandshake: Boolean = false,
    ): AuthorizationOutcome {
        val transport = AndroidGattTransport(conn)
        val phoneCert = PhoneCert.bundled162b()
        BleLog.log("manager: phone cert prefix=${phoneCert.raw.copyOfRange(0, 4).toHex()}")
        val flow = PairingFlow(transport, phoneCert = phoneCert, logger = { BleLog.log(it) })
        val resumeKey = cachedPhase5RawKey
        if (resumeKey != null && !probeFullHandshake) {
            BleLog.log("manager: cached reconnect authorization (StartAuthorization only)")
            try {
                val result = flow.runCachedReconnectHandshake(session.blePin, resumeKey)
                cachedReconnectFailures = 0
                rememberPhase5RawKey(result.phase5RawKey)
                return AuthorizationOutcome(result, "cached reconnect handshake")
            } catch (e: Exception) {
                // Discard the cached key ONLY when it is *provably* wrong (Phase 6 CCM/echo failure).
                // Discarding it forces a full first-pair handshake, which an already-provisioned Libre 3
                // can refuse to honor without a fresh NFC switch-receiver — i.e. an over-eager discard can
                // strand the session ("sensor stopped, fixed only by New Sensor"). So a transient drop
                // (status=19/8), a timeout, or a failure count must NEVER throw the key away; keep it and
                // retry the cached path. A stale key (sensor re-paired by another receiver) also fails
                // this way — that case is recovered by the periodic full-handshake PROBE, which keeps
                // this key as fallback and only replaces it after a *successful* full handshake.
                cachedReconnectFailures += 1
                if (isKeyMismatch(e)) {
                    forgetPhase5RawKey()
                    cachedReconnectFailures = 0
                    BleLog.log("manager: cached reconnect failed (${e.message}); cached key DISCARDED (provable key mismatch); next attempt full handshake")
                } else {
                    BleLog.log(
                        "manager: cached reconnect failed (${e.message}); cached key KEPT " +
                            "(#$cachedReconnectFailures — full-handshake probe after $CACHED_RECONNECT_PROBE_AFTER_FAILURES)",
                    )
                }
                throw IllegalStateException("cached reconnect failed; retry on next attempt", e)
            }
        }

        if (resumeKey != null && probeFullHandshake) {
            // Stale-key recovery probe: run the full first-pair WITHOUT discarding the cached key.
            // Success ⇒ the sensor accepted a fresh derivation, the new key replaces the stale one.
            // Failure ⇒ nothing lost; reset the counter so the cached path gets retried first.
            BleLog.log("manager: FULL-HANDSHAKE PROBE (stale-key recovery); cached key kept as fallback")
            val ephemeral = firstPairEphemeral ?: prepareFirstPairEphemeral()
            try {
                val result = flow.runCommandGatedFirstPairHandshake(session.blePin, ephemeral)
                cachedReconnectFailures = 0
                rememberPhase5RawKey(result.phase5RawKey)
                BleLog.log("manager: PROBE SUCCEEDED — stale cached key replaced by fresh full-handshake key")
                return AuthorizationOutcome(result, "full handshake probe (stale-key recovery)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cachedReconnectFailures = 0
                BleLog.log("manager: probe full handshake failed (${e.message}); cached key kept; returning to cached path")
                throw IllegalStateException("full-handshake probe failed; retry cached on next attempt", e)
            }
        }

        BleLog.log("manager: full command-gated first-pair authorization (local key derivation)")
        val ephemeral = firstPairEphemeral ?: prepareFirstPairEphemeral()
        val result = flow.runCommandGatedFirstPairHandshake(session.blePin, ephemeral)
        rememberPhase5RawKey(result.phase5RawKey)
        return AuthorizationOutcome(result, "full local handshake")
    }

    private suspend fun loadCachedPhase5RawKeyIfNeeded() {
        if (cachedPhase5RawKey != null) return
        runCatching { store.loadCachedPhase5RawKey() }
            .onSuccess { key ->
                if (key != null) {
                    cachedPhase5RawKey = key.copyOf()
                    BleLog.log("manager: loaded cached Phase 5 resume key from local store")
                }
            }
            .onFailure { BleLog.log("manager: cached Phase 5 resume key unavailable: ${it.message}") }
    }

    private suspend fun rememberPhase5RawKey(key: ByteArray) {
        cachedPhase5RawKey = key.copyOf()
        runCatching { store.saveCachedPhase5RawKey(key) }
            .onSuccess { BleLog.log("manager: cached Phase 5 resume key saved locally") }
            .onFailure { BleLog.log("manager: cached Phase 5 resume key save failed: ${it.message}") }
    }

    private suspend fun forgetPhase5RawKey() {
        cachedPhase5RawKey = null
        runCatching { store.clearCachedPhase5RawKey() }
            .onSuccess { BleLog.log("manager: cached Phase 5 resume key cleared") }
            .onFailure { BleLog.log("manager: cached Phase 5 resume key clear failed: ${it.message}") }
    }

    /**
     * True only when the cached key is *provably* wrong: a Phase 6 CCM MAC / R1-R2 echo failure
     * (AesCcmException or Phase6VerificationFailed) during the handshake — that only happens when the
     * derived session material can't validate the sensor's Phase 6, i.e. the key is stale.
     *
     * Deliberately does NOT include GATT status=19 (peer-terminated). status=19 is a transient link
     * drop (RF, Wi-Fi/BT coexistence, the sensor closing a slow handshake) — NOT proof of a bad key.
     * Treating it as a mismatch discarded a perfectly good key and stranded the session until a manual
     * NFC re-provision, which is exactly the failure being fixed here.
     */
    private fun isKeyMismatch(error: Throwable): Boolean {
        var t: Throwable? = error
        while (t != null) {
            if (t is AesCcmException || t is PairingFlowException.Phase6VerificationFailed) return true
            t = t.cause
        }
        return false
    }

    private suspend fun <T> withHandshakeWakeLock(block: suspend () -> T): T {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreCR:BleHandshake")
        if (wakeLock == null) {
            BleLog.log("power: handshake wake lock unavailable")
            return block()
        }
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(HANDSHAKE_WAKE_LOCK_TIMEOUT_MS)
        BleLog.log("power: handshake wake lock acquired timeout=${HANDSHAKE_WAKE_LOCK_TIMEOUT_MS}ms")
        return try {
            block()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
                BleLog.log("power: handshake wake lock released")
            }
        }
    }

    private suspend fun rememberObservedIdentity(session: ImportedSession, scan: SensorScanResult): ImportedSession {
        val observedName = scan.advertisedName?.takeIf { it.isNotBlank() } ?: return session
        if (normalizeIdentity(session.bleDeviceName) == normalizeIdentity(observedName)) return session
        val updated = session.copy(bleDeviceName = observedName)
        store.saveSession(updated, preserveCachedKeyWhenKeyless = true)
        BleLog.log("manager: cached permanent BLE device name=$observedName for ${session.bleAddress}")
        return updated
    }

    private fun normalizeIdentity(value: String?): String? =
        value?.filter { it.isLetterOrDigit() }?.uppercase()?.takeIf { it.isNotEmpty() }

    private suspend fun collectGlucose(
        conn: SensorConnection,
        decoder: DataPlaneDecoder,
        backfillRequests: Channel<BackfillRequest>,
    ) {
        val channel = conn.notifyChannel(LibreSensorGatt.GLUCOSE_DATA)
        val assembler = DataPlaneNotificationAssembler()
        // Single-coroutine loop, so these per-reading locals need no synchronization. A glucose
        // reading is a 15B prefix + suffix → two 177a notifies; we time both and the decode.
        var pendingFirstNotifyTs = 0L
        while (true) {
            // A glucose reading is prefix + suffix. Once the prefix is buffered, the suffix should follow
            // within ~1s; if it never does, the sensor channel is half-stuck → a validated reconnect trigger.
            val frag = if (pendingFirstNotifyTs > 0L) {
                withTimeoutOrNull(FRAGMENT_ASSEMBLY_TIMEOUT_MS) { channel.receive() } ?: run {
                    reconLog("SENSOR_FRAGMENT_TIMEOUT channel=glucose awaitedMs=$FRAGMENT_ASSEMBLY_TIMEOUT_MS (prefix without suffix)")
                    conn.disconnect()
                    requestSensorReconnect("stale_fragment_timeout")
                    return
                }
            } else {
                channel.receive()
            }
            val notifyAtMs = System.currentTimeMillis()
            val result = assembler.feedDetailed(frag, DataPlaneChannel.GLUCOSE_DATA)
            result.flushedOrphanAgeMs?.let { age ->
                BleLog.log("[ANOMALY] REASSEMBLY: flushed orphan 177a prefix age=${age}ms (suffix never arrived → that reading lost)")
                pendingFirstNotifyTs = 0L
            }
            result.replacedPrefixAgeMs?.let { age ->
                BleLog.log("[ANOMALY] REASSEMBLY: replaced orphan 177a prefix age=${age}ms with fresh prefix")
                pendingFirstNotifyTs = notifyAtMs
            }
            result.orphanSuffixSize?.let { size ->
                BleLog.log("[ANOMALY] REASSEMBLY: orphan 177a suffix len=${size}B arrived without prefix")
            }
            val combined = result.combined
            if (combined == null) {
                // Buffered the 15B prefix; remember when it landed to time first→second notify.
                pendingFirstNotifyTs = notifyAtMs
                BleLog.log("[LATENCY] 177a reassembly: buffered ${frag.size}B prefix t=$notifyAtMs, awaiting suffix")
                continue
            }
            val firstNotifyTs = if (pendingFirstNotifyTs > 0L) pendingFirstNotifyTs else notifyAtMs
            val secondNotifyTs = notifyAtMs
            pendingFirstNotifyTs = 0L
            runCatching {
                val packet = decoder.decrypt(DataFrame.parse(combined), DataPlaneChannel.GLUCOSE_DATA)
                (packet.payload as? DataPlaneDecodedPayload.RealtimeGlucose)?.reading?.let { r ->
                    val decodedTs = System.currentTimeMillis()
                    val missingStart = recordRealtimeLifeCount(r.lifeCount, "177a")
                    if (missingStart != null) {
                        queueBackfill(
                            backfillRequests,
                            missingStart,
                            "missing realtime before lc=${r.lifeCount}",
                        )
                    }
                    GlucoseLatencyTracer.mark(r.lifeCount, GlucoseLatencyTracer.Stage.BLE_NOTIFY_RECEIVED, secondNotifyTs)
                    GlucoseLatencyTracer.mark(r.lifeCount, GlucoseLatencyTracer.Stage.GLUCOSE_DECODED, decodedTs)
                    BleLog.log(
                        "WATCH_DECODED lc=${r.lifeCount} mgdl=${r.currentGlucoseMgDL ?: "NA"} " +
                            "trend=${r.trendKind} usable=${r.isCurrentGlucoseUsable}",
                    )
                    BleLog.log(
                        "[LATENCY] 177a→decoded lc=${r.lifeCount} firstNotify=$firstNotifyTs secondNotify=$secondNotifyTs " +
                            "decoded=$decodedTs firstNotify→decoded=${decodedTs - firstNotifyTs}ms " +
                            "interFragment=${secondNotifyTs - firstNotifyTs}ms",
                    )
                    lastGlucoseAt.set(decodedTs)
                    // No connection-priority manipulation: the link keeps the default Android/sensor-
                    // negotiated parameters for the whole session (HIGH/BALANCED → status=8, LOW_POWER →
                    // slow handshake, any mid-session change → status=19).
                    // First notify after a reconnect: close out the reconnect (success + gap diagnostics)
                    // and reset the backoff so the next drop starts fresh from the short pause.
                    if (awaitingFirstReadingAfterReconnect) {
                        awaitingFirstReadingAfterReconnect = false
                        reconLog("SENSOR_FIRST_NOTIFY_AFTER_RECONNECT lifeCount=${r.lifeCount}")
                        reconLog("SENSOR_RECONNECT_SUCCESS durationMs=${decodedTs - reconnectStartedAtMs} attempt=$reconnectAttempt")
                        val lastGood = reconnectLastGoodLifeCount
                        if (lastGood != null && r.lifeCount > lastGood + 1) {
                            reconLog(
                                "SENSOR_LIFECOUNT_GAP_AFTER_RECONNECT lastGood=$lastGood now=${r.lifeCount} " +
                                    "missing=${r.lifeCount - lastGood - 1}",
                            )
                        }
                        // Ship the reconnect narrative (drop → attempts → success → gap size) to the
                        // phone's log viewer; after a long outage the rate limiter has long expired.
                        shipLogRateLimited()
                    }
                    // Backoff resets only on a real reading from the sensor — not merely on GATT connect.
                    if (reconnectAttempt != 0) {
                        reconLog("SENSOR_BACKOFF_RESET reason=first_valid_reading")
                        reconnectAttempt = 0
                    }
                    val mg = r.currentGlucoseMgDL
                    val usable = r.isCurrentGlucoseUsable
                    val issue = glucoseReadingIssue(r)
                    val issueDetail = glucoseReadingIssueDetail(r)
                    // Delta from the previous in-memory reading — never re-reads DataStore.
                    val delta = if (usable && mg != null) deltaPerMin(lastSentReading, mg, r.lifeCount, decodedTs) else null
                    // 1) In-memory StateFlow first: the watch UI, notification and complications all
                    //    read this instantly, independent of any DataStore write.
                    _glucose.value = GlucoseUi(
                        mgDL = mg,
                        trend = r.trendKind.name,
                        lifeCount = r.lifeCount,
                        usable = usable,
                        receivedAtMs = decodedTs,
                        deltaMgDlPerMin = delta,
                        readingIssue = issue,
                        readingIssueDetail = issueDetail,
                    )
                    GlucoseLatencyTracer.mark(r.lifeCount, GlucoseLatencyTracer.Stage.VIEWMODEL_UPDATED)
                    BleLog.log("WATCH_STATE_UPDATED lc=${r.lifeCount} source=ble usable=$usable")
                    BleLog.log(
                        "glucose lifeCount=${r.lifeCount} mgdl=${mg ?: "NA"} trend=${r.trendKind} " +
                            "usable=$usable issue=${issue ?: "none"} $issueDetail",
                    )
                    if (usable && mg != null) {
                        val reading = SensorStateStore.LastGlucose(
                            r.lifeCount, mg, r.trendKind.name, decodedTs, delta,
                            chartMgDL = r.currentGlucoseChartMgDL,
                        )
                        lastSentReading = reading
                        val timeline = GlucoseTimeline(
                            lifeCount = r.lifeCount,
                            firstNotifyTs = firstNotifyTs,
                            secondNotifyTs = secondNotifyTs,
                            decodedTs = decodedTs,
                        )
                        // 2) Send to the phone straight from the decoded object (no DataStore reload),
                        //    and 3) refresh complications from the in-memory value.
                        WearDataSync.sendGlucose(context, reading, timeline)
                        LibreComplicationUpdater.requestAll(context, r.lifeCount)
                        // 4) Persist last — fire-and-forget, conflated, on the dedicated IO consumer.
                        persistChannel.trySend(reading)
                    } else {
                        WearDataSync.sendGlucoseUnavailable(
                            context,
                            WearDataSync.GlucoseUnavailable(
                                lifeCount = r.lifeCount,
                                trend = r.trendKind.name,
                                receivedAtMs = decodedTs,
                                reason = issue ?: GLUCOSE_ISSUE_NOT_USABLE,
                                detail = issueDetail,
                            ),
                        )
                        LibreComplicationUpdater.requestAll(context, r.lifeCount)
                    }
                }
            }.onFailure { BleLog.log("glucose decode failed len=${combined.size}: ${it.message}") }
        }
    }

    private fun glucoseReadingIssue(r: RealtimeGlucoseReading): String? = when {
        r.currentGlucoseMgDL == null -> GLUCOSE_ISSUE_VALUE_UNAVAILABLE
        !r.dqError.isGood -> GLUCOSE_ISSUE_DATA_QUALITY
        r.sensorCondition != Libre3SensorCondition.OK -> GLUCOSE_ISSUE_SENSOR_CONDITION
        !r.isCurrentGlucoseUsable -> GLUCOSE_ISSUE_NOT_USABLE
        else -> null
    }

    private fun glucoseReadingIssueDetail(r: RealtimeGlucoseReading): String =
        "raw=${r.uncappedCurrentMgDL} dq=0x${"%04x".format(r.dqErrorRaw)} " +
            "condition=${r.sensorConditionRaw}/${r.sensorCondition} action=${r.actionability}"

    private suspend fun collectChannel(
        conn: SensorConnection,
        decoder: DataPlaneDecoder,
        uuid: UUID,
        channel: DataPlaneChannel,
    ) {
        val ch = conn.notifyChannel(uuid)
        while (true) {
            val frag = ch.receive()
            runCatching {
                val packet = decoder.decrypt(DataFrame.parse(frag), channel)
                when (val payload = packet.payload) {
                    is DataPlaneDecodedPayload.PatchStatusPayload -> {
                        val s = payload.status
                        BleLog.log(
                            "decoded $channel kind=${packet.kind} lifeCount=${s.lifeCount} " +
                                "currentLifeCount=${s.currentLifeCount} patchState=${s.patchState} " +
                                "sensorError=${s.sensorError} sensorAttention=${s.sensorAttention} " +
                                "notifyUser=${s.shouldNotifyUser} replaceSensor=${s.shouldNotifyReplaceSensor} " +
                                "stackDisconnectReason=${s.stackDisconnectReason} appDisconnectReason=${s.appDisconnectReason} " +
                                "plaintext=${packet.plaintext.toHex()}",
                        )
                        // Surface sensor errors (insertion failure / ended / replace / unknown) once per
                        // transition: persist for complications, relay to the phone, notify the user, repaint.
                        val statusKey = s.errorData to s.patchState
                        if (statusKey != lastSensorStatus) {
                            lastSensorStatus = statusKey
                            val observedAtMs = System.currentTimeMillis()
                            store.saveSensorStatus(s.errorData, s.patchState, observedAtMs)
                            WearDataSync.sendSensorStatus(context, s.errorData, s.patchState, observedAtMs)
                            SensorAttentionNotifier.onAttentionChanged(context, s.sensorAttention)
                            LibreComplicationUpdater.requestAll(context)
                        }
                    }
                    is DataPlaneDecodedPayload.HistoricalReadingPagePayload -> {
                        val p = payload.page
                        BleLog.log(
                            "[BACKFILL] historical page lc=${p.startLifeCount}..${p.endLifeCount} " +
                                "samples=${p.samples.joinToString { "${it.lifeCount}:${it.glucoseMgDL ?: "NA"}" }}",
                        )
                        for (sample in p.samples) {
                            val mg = sample.glucoseMgDL ?: continue
                            BleLog.log(
                                "[BACKFILL] historical recovered lc=${sample.lifeCount} mgdl=$mg " +
                                    "watchStoreSkipped=true",
                            )
                        }
                    }
                    is DataPlaneDecodedPayload.ClinicalReadingRecordPayload -> {
                        val r = payload.record
                        val mg = r.currentGlucoseMgDL
                        if (mg != null) {
                            // Recovered a minute the watch missed live (e.g. a brief BLE blip when the
                            // phone's Wear link migrates WiFi→BT). The watch keeps no history of its own,
                            // so relay it to the phone on the replay path — stamped at its real minute —
                            // to fill the one-point gap instead of dropping it.
                            val anchor = lastSentReading
                            val estimatedMs = if (anchor != null) {
                                (anchor.receivedAtMs - (anchor.lifeCount - r.lifeCount).toLong() * 60_000L)
                                    .coerceAtLeast(1L)
                            } else {
                                System.currentTimeMillis()
                            }
                            val reading = SensorStateStore.LastGlucose(r.lifeCount, mg, "BACKFILL_CLINICAL", estimatedMs, null)
                            WearDataSync.sendBackfilledGlucose(context, reading)
                            BleLog.log(
                                "[BACKFILL] clinical relayed lc=${r.lifeCount} mgdl=$mg estimatedMs=$estimatedMs → phone",
                            )
                        } else {
                            BleLog.log(
                                "[BACKFILL] clinical lc=${r.lifeCount} mgdl=NA " +
                                    "raw=${r.currentGlucoseRaw} historicEstimate=${r.historicLifeCountEstimate} skipped",
                            )
                        }
                    }
                    else -> BleLog.log("decoded $channel kind=${packet.kind} plaintext=${packet.plaintext.toHex()}")
                }
            }.onFailure { BleLog.log("$channel decode failed: ${it.message}") }
        }
    }

    private suspend fun enqueueImmediateReconnectBackfill(backfillRequests: Channel<BackfillRequest>) {
        val saved = runCatching { store.lastGlucose() }
            .onFailure { BleLog.log("[BACKFILL] reconnect skipped; last glucose unavailable: ${it.message}") }
            .getOrNull()
        if (saved == null) {
            BleLog.log("[BACKFILL] reconnect skipped; no saved glucose lifeCount")
            return
        }
        val previousDecoded = lastDecodedLifeCount
        if (previousDecoded == null || saved.first > previousDecoded) {
            lastDecodedLifeCount = saved.first
        }
        val fromLifeCount = saved.first + 1
        BleLog.log(
            "[BACKFILL] reconnect immediate request from missing lifeCount=$fromLifeCount " +
                "lastSaved=${saved.first} mgdl=${saved.second}",
        )
        queueBackfill(backfillRequests, fromLifeCount, "reconnect")
    }

    private fun queueBackfill(backfillRequests: Channel<BackfillRequest>, fromLifeCount: Int, reason: String) {
        val request = BackfillRequest(maxOf(fromLifeCount, 0), reason)
        // Skip a request already covered by one just sent on this link: the sensor command is
        // "everything ≥ start", so a recent request with an equal-or-lower start subsumes this one.
        // (Benign race between the two callers — a slipped duplicate only costs one extra write.)
        val lastFrom = lastBackfillRequestFrom
        val sinceLastMs = System.currentTimeMillis() - lastBackfillRequestAtMs
        if (lastFrom != null && request.fromLifeCount >= lastFrom && sinceLastMs < BACKFILL_COALESCE_WINDOW_MS) {
            BleLog.log(
                "[BACKFILL] coalesced reason=$reason from lifeCount=${request.fromLifeCount} " +
                    "(covered by ≥$lastFrom sent ${sinceLastMs}ms ago)",
            )
            return
        }
        lastBackfillRequestFrom = request.fromLifeCount
        lastBackfillRequestAtMs = System.currentTimeMillis()
        val result = backfillRequests.trySend(request)
        if (result.isSuccess) {
            BleLog.log("[BACKFILL] queued reason=$reason from lifeCount=${request.fromLifeCount}")
        } else {
            BleLog.log("[BACKFILL] queue failed reason=$reason from lifeCount=${request.fromLifeCount}: ${result.exceptionOrNull()?.message}")
        }
    }

    private suspend fun sendBackfillFrom(
        patchControl: PatchControlBackfillWriter,
        fromLifeCount: Int,
        reason: String,
    ) {
        val start = maxOf(fromLifeCount, 0)
        val commands = listOf(
            PatchControlCommand.clinicalBackfillGreaterEqual(start),
        )
        for (command in commands) {
            patchControl.write(command, reason)
        }
    }

    private fun recordRealtimeLifeCount(lifeCount: Int, source: String): Int? {
        val previous = lastDecodedLifeCount
        return when {
            previous == null -> {
                lastDecodedLifeCount = lifeCount
                null
            }
            lifeCount > previous -> {
                lastDecodedLifeCount = lifeCount
                val missing = lifeCount - previous - 1
                if (missing > 0) {
                    val start = previous + 1
                    val end = lifeCount - 1
                    BleLog.log(
                        "[MISSING] missing ${formatLifeCountRange(start, end)} before $source lc=$lifeCount " +
                            "previous=$previous count=$missing",
                    )
                    start
                } else {
                    null
                }
            }
            lifeCount == previous -> {
                BleLog.log("[MISSING] duplicate lifeCount=$lifeCount from $source")
                null
            }
            else -> {
                BleLog.log("[MISSING] out-of-order lifeCount=$lifeCount from $source previous=$previous")
                null
            }
        }
    }

    private fun formatLifeCountRange(start: Int, end: Int): String =
        if (start == end) "lifeCount=$start" else "lifeCount=$start..$end"

    private class PatchControlBackfillWriter(
        private val conn: SensorConnection,
        private val crypto: DataPlaneCrypto,
        private val timeoutMs: Long,
    ) {
        private var nextSequence = 1

        suspend fun write(command: PatchControlCommand, reason: String) {
            val sequence = nextSequence
            nextSequence = if (nextSequence == 0xffff) 1 else nextSequence + 1
            runCatching {
                val frame = crypto.encrypt(command.plaintext, sequence, DataPlanePacketKind.patchControlWrite)
                BleLog.log(
                    "[BACKFILL] patchControl reason=$reason ${command.label} " +
                        "seq=${"%04x".format(sequence)} pt=${command.plaintext.toHex()} raw=${frame.raw.toHex()}",
                )
                conn.writeCharacteristic(
                    LibreSensorGatt.PATCH_CONTROL,
                    frame.raw,
                    withResponse = true,
                    timeoutMs = timeoutMs,
                )
                BleLog.log("[BACKFILL] patchControl ACK reason=$reason ${command.label}")
            }.onFailure {
                BleLog.log("[BACKFILL] patchControl ${command.label} reason=$reason write not accepted: ${it.message}")
            }
        }
    }

    private suspend fun watchdog(conn: SensorConnection) {
        while (true) {
            delay(WATCHDOG_CHECK_MS)
            val since = System.currentTimeMillis() - lastGlucoseAt.get()
            if (since > NO_DATA_TIMEOUT_MS) {
                reconLog("SENSOR_NO_DATA_WATCHDOG noDataMs=$since → reconnect")
                conn.disconnect()
                requestSensorReconnect("no_data_watchdog")
                return
            }
        }
    }

    /**
     * Per-minute delta from the previous in-memory reading (no I/O). The denominator is the
     * sensor's own minute counter (lifeCount), NOT wall-clock time: after a reconnect the sensor
     * delivers two readings seconds apart, and a wall-clock denominator of a few seconds exploded
     * the delta to ±99. lifeCount difference is the true elapsed sensor minutes regardless of
     * when the packets happen to arrive.
     */
    private fun deltaPerMin(prev: SensorStateStore.LastGlucose?, mgDL: Int, lifeCount: Int, atMs: Long): Double? {
        val p = prev ?: return null
        if (p.receivedAtMs !in 1 until atMs) return null
        val minutes = (lifeCount - p.lifeCount).toDouble()
        if (minutes <= 0.0) return null
        return ((mgDL - p.mgDL) / minutes).takeIf { it.isFinite() }
    }

    companion object {
        private const val STOP_JOIN_TIMEOUT_MS = 5_000L
        private const val WATCHDOG_CHECK_MS = 15_000L
        /**
         * No-data watchdog: two missed minutes + margin. A live BLE link cannot lose a notification
         * (link-layer retransmission), so one missing minute means the sensor skipped a send — the
         * old 75s ceiling tore down that healthy link at ~75–90s even though the next reading lands
         * at ~120s. A genuinely dead link is reported by the controller itself (status=8) within
         * seconds; the watchdog only backstops rare half-open states, so slower detection is cheap.
         * NOTE: anything between ~90s and ~120s is the worst of both — still fires before the ~120s
         * post-skip reading, yet detects real death later. Keep this ≥135s (120s + jitter margin).
         */
        private const val NO_DATA_TIMEOUT_MS = 135_000L
        private const val POST_AUTH_PATCH_CONTROL_TIMEOUT_MS = 10_000L
        /** A backfill request with an equal-or-lower start within this window subsumes a new one. */
        private const val BACKFILL_COALESCE_WINDOW_MS = 60_000L
        private const val HANDSHAKE_WAKE_LOCK_TIMEOUT_MS = 90_000L
        private const val PERSIST_SLOW_WARN_MS = 1_000L

        /** Greppable tag for the whole sensor reconnect / reading-gap narrative on Wear OS. */
        private const val WEAR_BLE_TAG = "[WEAR-BLE]"
        /** Suffix must follow a buffered glucose prefix within this window, else the channel is stale. */
        private const val FRAGMENT_ASSEMBLY_TIMEOUT_MS = 8_000L
        private const val RECONNECT_WAKE_LOCK_TIMEOUT_MS = 5_000L
        /** Minimum spacing between watch-log pushes to the phone on reconnect. */
        private const val LOG_SHIP_MIN_INTERVAL_MS = 5 * 60_000L
        /**
         * After this many consecutive cached-reconnect failures, probe a full first-pair handshake
         * (keeping the cached key as fallback). Recovers a stale key — sensor re-paired by another
         * app/device — which fails exactly like a transient drop (status=19 after Phase 5) and would
         * otherwise retry the cached path forever.
         */
        private const val CACHED_RECONNECT_PROBE_AFTER_FAILURES = 4
        /** Cold/first attempt: long window for the sensor's ~minute advertising cadence. */
        private const val COLD_SCAN_TIMEOUT_MS = 60_000L
        /** Reconnect: the sensor re-advertises within seconds of a real drop, so fail fast into backoff. */
        private const val RECONNECT_SCAN_TIMEOUT_MS = 12_000L
        /** Per-attempt reconnect delay (1-based). Fast first retries, bounded climb — never the old 60s. */
        private val RECONNECT_BACKOFF_MS = longArrayOf(
            500L,     // attempt 1
            2_000L,   // attempt 2
            2_000L,   // attempt 3
            5_000L,   // attempt 4
            10_000L,  // attempt 5
            20_000L,  // attempt 6
            30_000L,  // attempt 7+
        )
        private const val RECONNECT_BACKOFF_MAX_MS = 30_000L

        private const val GLUCOSE_ISSUE_VALUE_UNAVAILABLE = "VALUE_UNAVAILABLE"
        private const val GLUCOSE_ISSUE_DATA_QUALITY = "DATA_QUALITY"
        private const val GLUCOSE_ISSUE_SENSOR_CONDITION = "SENSOR_CONDITION"
        private const val GLUCOSE_ISSUE_NOT_USABLE = "NOT_USABLE"
    }
}
