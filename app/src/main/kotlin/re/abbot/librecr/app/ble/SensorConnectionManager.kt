package re.abbot.librecr.app.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.PowerManager
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
import re.abbot.librecr.app.isFreshGlucose
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.alarm.SensorAttentionNotifier
import re.abbot.librecr.app.alarm.StalenessWatchdog
import re.abbot.librecr.app.wear.WearDataSync
import re.abbot.librecr.protocol.dataplane.DataFrame
import re.abbot.librecr.protocol.dataplane.DataPlaneChannel
import re.abbot.librecr.protocol.dataplane.DataPlaneCrypto
import re.abbot.librecr.protocol.dataplane.DataPlaneDecodedPayload
import re.abbot.librecr.protocol.dataplane.DataPlaneDecoder
import re.abbot.librecr.protocol.dataplane.DataPlaneNotificationAssembler
import re.abbot.librecr.protocol.dataplane.DataPlanePacketKind
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
    /** Uncapped-below-floor value for mini-charts only; null ⇒ same as [mgDL] (headline stays capped). */
    val chartMgDL: Int? = null,
)

/**
 * True while the latest realtime glucose packet has no displayable value. This is not, by itself,
 * a sensor-error signal; patch-status attention remains the source of truth for real sensor errors.
 */
fun GlucoseUi?.isActiveGlucoseUnavailable(nowMs: Long = System.currentTimeMillis()): Boolean =
    this != null && !usable && isFreshGlucose(receivedAtMs, nowMs)

private data class BackfillRequest(
    val fromLifeCount: Int,
    val reason: String,
)

private data class LifecycleUpdate(
    val lifeCount: Int,
    val observedAtMs: Long,
)

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
    @Volatile private var activeSession: ImportedSession? = null
    @Volatile private var cachedPhase5RawKey: ByteArray? = null
    /** Consecutive non-mismatch cached-reconnect failures; a correct key survives transient blips. */
    @Volatile private var cachedReconnectFailures: Int = 0
    @Volatile private var lastDecodedLifeCount: Int? = null
    @Volatile private var lastSentReading: SensorStateStore.LastGlucose? = null
    /** Last (errorData, patchState) pair we persisted/relayed, so we act only on real transitions. */
    @Volatile private var lastSensorStatus: Pair<Int, Int>? = null
    // Keep the phone's BLE recovery behavior aligned with the Wear path: one running loop owns
    // reconnects, and live sensor-side signals only wake that loop instead of rebuilding GATT inline.
    @Volatile private var sensorOnline = false
    @Volatile private var reconnectSignal: CompletableDeferred<Unit>? = null
    @Volatile private var pendingReconnectReason: String = "initial"
    @Volatile private var lastDisconnectStatus: Int? = null
    @Volatile private var reconnectAttempt = 0
    @Volatile private var reconnectStartedAtMs = 0L
    @Volatile private var awaitingFirstReadingAfterReconnect = false
    @Volatile private var reconnectLastGoodLifeCount: Int? = null

    // Match the stable Wear path: live decode/UI/relay never waits for DataStore. A single
    // conflated IO consumer persists only the newest pending reading, so slow flash I/O cannot
    // delay a BLE notification, block the GATT collector, or build an unbounded backlog.
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistChannel = Channel<SensorStateStore.LastGlucose>(Channel.CONFLATED)
    private val lifecycleChannel = Channel<LifecycleUpdate>(Channel.CONFLATED)

    init {
        persistScope.launch {
            for (reading in persistChannel) {
                val startedAt = System.currentTimeMillis()
                runCatching { store.saveRemoteGlucose(reading) }
                    .onFailure { BleLog.log("persist: lc=${reading.lifeCount} save failed: ${it.message}") }
                val editMs = System.currentTimeMillis() - startedAt
                if (editMs > PERSIST_SLOW_WARN_MS) {
                    BleLog.log(
                        "[ANOMALY] persist: DataStore edit ${editMs}ms lc=${reading.lifeCount} " +
                            "(off critical path; ui/relay unaffected)",
                    )
                } else {
                    BleLog.log("[PERSIST] DataStore edit ${editMs}ms lc=${reading.lifeCount} (off critical path)")
                }
            }
        }
        persistScope.launch {
            for (update in lifecycleChannel) {
                runCatching { store.saveSensorLifecycle(update.lifeCount, update.observedAtMs) }
                    .onFailure { BleLog.log("persist: lifecycle lc=${update.lifeCount} failed: ${it.message}") }
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
        if (loopJob?.isActive == true) {
            if (activeSession.isSameSensorAs(session)) {
                BleLog.log("manager: start ignored; connection loop already active")
                return
            }
            BleLog.log("manager: switching sensor session; closing previous connection loop")
            loopJob?.cancel()
            activeConnection?.disconnect()
            activeConnection?.close()
            activeConnection = null
        }
        loopJob?.cancel()
        clearCurrentGlucoseState()
        lastSentReading = null
        lastSensorStatus = null
        cachedReconnectFailures = 0
        reconnectAttempt = 0
        pendingReconnectReason = "initial"
        lastDisconnectStatus = null
        sensorOnline = false
        reconnectSignal = null
        awaitingFirstReadingAfterReconnect = false
        cachedPhase5RawKey = session.phase5RawKey?.copyOf()
        val localProvisioning = session.withoutTransientCrypto()
        activeSession = localProvisioning
        BleLog.log(
            "manager: starting independent BLE loop; cachedResumeKey=${cachedPhase5RawKey != null} " +
                "allowCandidateFirstPair=$allowCandidateFirstPair transientKeyPersisted=false"
        )
        loopJob = scope.launch {
            // The loop must never end silently: it is the only thing keeping readings alive, and a
            // dead loop looks exactly like "stale value, no reconnect, empty log" in the field.
            try {
                runLoop(localProvisioning)
                BleLog.log("manager: connection loop EXITED normally (unexpected — should only end by cancel)")
            } catch (e: CancellationException) {
                BleLog.log("manager: connection loop exited (cancelled by stop/restart)")
                throw e
            } catch (e: Throwable) {
                BleLog.log("manager: connection loop CRASHED ${e::class.simpleName}: ${e.message}")
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
        activeSession = null
        sensorOnline = false
        reconnectSignal = null
        awaitingFirstReadingAfterReconnect = false
        clearCurrentGlucoseState()
        _state.value = ConnectionState.IDLE
        _statusLine.value = "stopped"
        BleLog.log("manager: stop requested; active GATT closed")
    }

    private fun clearCurrentGlucoseState() {
        _glucose.value = null
        lastGlucoseAt.set(0L)
        lastDecodedLifeCount = null
        lastSentReading = null
        lastSensorStatus = null
        cachedReconnectFailures = 0
    }

    /**
     * Phone-side equivalent of the Wear reconnect funnel. Validated sensor-side signals
     * (GATT disconnect, no-data watchdog, stale glucose fragment) complete the current
     * streaming wait; the outer loop then closes GATT and retries with the shared backoff.
     */
    private fun requestSensorReconnect(reason: String, status: Int? = null) {
        BleLog.log("manager: reconnect request reason=$reason status=${status ?: -1} attempt=$reconnectAttempt")
        val signal = reconnectSignal
        if (!sensorOnline || signal == null || signal.isCompleted) {
            BleLog.log("manager: reconnect request suppressed reason=$reason")
            return
        }
        sensorOnline = false
        pendingReconnectReason = reason
        lastDisconnectStatus = status
        reconnectLastGoodLifeCount = lastSentReading?.lifeCount ?: lastDecodedLifeCount
        reconnectStartedAtMs = System.currentTimeMillis()
        awaitingFirstReadingAfterReconnect = true
        val age = if (lastGlucoseAt.get() > 0) System.currentTimeMillis() - lastGlucoseAt.get() else -1
        BleLog.log(
            "manager: sensor disconnected status=${describeGattStatus(status)} lastPacketAgeMs=$age " +
                "attempt=$reconnectAttempt reason=$reason lastGoodLifeCount=${reconnectLastGoodLifeCount ?: -1}",
        )
        signal.complete(Unit)
    }

    private fun describeGattStatus(status: Int?): String = when (status) {
        null -> "-1"
        0 -> "0/OK_LOCAL_DISCONNECT"
        8 -> "8/LINK_TIMEOUT_SIGNAL_LOST"
        19 -> "19/TERMINATED_BY_SENSOR"
        22 -> "22/TERMINATED_BY_PHONE"
        62 -> "62/FAILED_TO_ESTABLISH"
        133 -> "133/GATT_ERROR"
        147 -> "147/CONNECT_TIMEOUT"
        else -> status.toString()
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val idx = (attempt - 1).coerceIn(0, RECONNECT_BACKOFF_MS.size - 1)
        return RECONNECT_BACKOFF_MS[idx].coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
    }

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

    private fun ImportedSession?.isSameSensorAs(next: ImportedSession): Boolean {
        val previous = this ?: return false
        val previousSerial = previous.serial?.takeIf { it.isNotBlank() }
        val nextSerial = next.serial?.takeIf { it.isNotBlank() }
        if (previousSerial != null && nextSerial != null) {
            return previousSerial.equals(nextSerial, ignoreCase = true)
        }
        return previous.bleAddress.equals(next.bleAddress, ignoreCase = true)
    }

    /** Publish a watch-relayed value immediately; the listener persists it separately. */
    fun acceptRemoteGlucose(reading: SensorStateStore.LastGlucose) {
        val current = _glucose.value
        if (current != null && current.lifeCount > reading.lifeCount) {
            BleLog.log("PHONE_STATE_UPDATED skipped stale remote lc=${reading.lifeCount} current=${current.lifeCount}")
            return
        }
        if (current != null && current.lifeCount == reading.lifeCount) {
            BleLog.log(
                "PHONE_STATE_UPDATED skipped duplicate remote lc=${reading.lifeCount} " +
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
            chartMgDL = reading.chartMgDL,
        )
        lastSentReading = reading
        BleLog.log("PHONE_STATE_UPDATED lc=${reading.lifeCount} source=remote")
    }

    /** Publish a non-numeric realtime sensor reading relayed by the watch. */
    fun acceptRemoteGlucoseUnavailable(event: WearDataSync.GlucoseUnavailable) {
        val current = _glucose.value
        if (current != null && current.lifeCount > event.lifeCount) {
            BleLog.log("PHONE_STATE_UPDATED skipped stale remote unavailable lc=${event.lifeCount} current=${current.lifeCount}")
            return
        }
        if (current != null && current.lifeCount == event.lifeCount && !current.usable) {
            BleLog.log("PHONE_STATE_UPDATED skipped duplicate remote unavailable lc=${event.lifeCount}")
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
        BleLog.log(
            "PHONE_STATE_UPDATED lc=${event.lifeCount} source=remote usable=false " +
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
        loadLastSentReadingIfNeeded()

        while (kotlin.coroutines.coroutineContext.isActive) {
            var conn: SensorConnection? = null
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
                // watch re-provisioned), the cached key fails with a link drop (status=19 right
                // after Phase 5) — indistinguishable from a transient blip, so the key is never
                // "provably" wrong and the cached path would retry forever. After a few consecutive
                // cached failures, PROBE a full first-pair while KEEPING the cached key as fallback.
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

                val knownAddress = currentSession.bleAddress
                    .takeIf { BluetoothAdapter.checkBluetoothAddress(it.uppercase()) }
                    ?.uppercase()
                val device: android.bluetooth.BluetoothDevice
                val autoConnect: Boolean
                if (knownAddress != null) {
                    device = adapter.getRemoteDevice(knownAddress)
                    autoConnect = reconnectAttempt > 0
                    _state.value = ConnectionState.CONNECTING
                    _statusLine.value = "connecting $knownAddress"
                    BleLog.log(
                        "manager: direct connect target=$knownAddress autoConnect=$autoConnect " +
                            "attempt=$reconnectAttempt",
                    )
                } else {
                    _state.value = ConnectionState.SCANNING
                    _statusLine.value = "scanning for ${currentSession.bleAddress}"
                    val scanTimeoutMs = if (reconnectAttempt == 0) COLD_SCAN_TIMEOUT_MS else RECONNECT_SCAN_TIMEOUT_MS
                    BleLog.log(
                        "manager: scan start target=${currentSession.bleAddress} " +
                            "timeoutMs=$scanTimeoutMs attempt=$reconnectAttempt",
                    )
                    val scan = scanner.findSensor(currentSession.bleAddress, currentSession.bleDeviceName, timeoutMs = scanTimeoutMs)
                        ?: throw IllegalStateException("sensor not found")
                    currentSession = rememberObservedIdentity(currentSession, scan)
                    device = scan.device
                    autoConnect = false
                }

                _state.value = ConnectionState.CONNECTING
                _statusLine.value = "connecting ${device.address}"
                val c = SensorConnection(context, device)
                conn = c
                activeConnection = c
                val disconnected = CompletableDeferred<Unit>()
                reconnectSignal = disconnected
                c.onDisconnected = { status -> requestSensorReconnect("gatt_disconnect", status) }
                // Libre 3 accepts connections on ~minute-spaced windows → long connect timeout.
                withReconnectWakeLock {
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
                // Key prefixes only — the full session key in a log would let any log holder
                // decrypt a captured BLE glucose stream.
                BleLog.log("manager: ${auth.mode} authorized kEnc=${material.kEnc.toHex().take(8)}… ivEnc=${material.ivEnc.toHex().take(8)}…")

                val crypto = DataPlaneCrypto(material.kEnc, material.ivEnc)
                val decoder = DataPlaneDecoder(crypto)
                val patchControl = PatchControlBackfillWriter(c, crypto, POST_AUTH_PATCH_CONTROL_TIMEOUT_MS)
                _state.value = ConnectionState.STREAMING
                _statusLine.value = "streaming"
                lastGlucoseAt.set(System.currentTimeMillis())
                sensorOnline = true
                BleLog.log(
                    "manager: sensor reconnected device=${device.address} " +
                        "attempt=$reconnectAttempt resume=${cachedPhase5RawKey != null}",
                )

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
                // (hit in the field on the watch 2026-07-05: post-handshake CCCD re-arm timeout →
                // loop dead → stale reading, no reconnect until app relaunch).
                BleLog.log("manager: attempt failed (op timeout): ${e.message}")
                _statusLine.value = "error: ${e.message}"
            } catch (e: CancellationException) {
                conn?.disconnect(); conn?.close()
                throw e
            } catch (e: Exception) {
                BleLog.log("manager: attempt failed: ${e.message}")
                _statusLine.value = "error: ${e.message}"
            } finally {
                sensorOnline = false
                reconnectSignal = null
                conn?.close()
                if (conn != null) {
                    BleLog.log("manager: GATT closed reason=$pendingReconnectReason")
                }
                if (activeConnection === conn) activeConnection = null
            }

            reconnectAttempt += 1
            val delayMs = reconnectDelayMs(reconnectAttempt)
            _state.value = ConnectionState.RECONNECTING
            _statusLine.value = if (delayMs < 1_000L) "reconnecting now" else "reconnecting in ${delayMs / 1000}s"
            BleLog.log(
                "manager: reconnect scheduled in ${delayMs}ms attempt=$reconnectAttempt " +
                    "reason=$pendingReconnectReason status=${lastDisconnectStatus ?: -1}; next attempt will redo scan/connect and " +
                    (if (cachedPhase5RawKey != null) "try cached reconnect" else "run full handshake"),
            )
            delay(delayMs)
            BleLog.log("manager: next reconnect delay would be ${reconnectDelayMs(reconnectAttempt + 1)}ms")
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

    private suspend fun loadLastSentReadingIfNeeded() {
        if (lastSentReading != null) return
        runCatching { store.loadLastGlucose() }
            .onSuccess { reading ->
                if (reading != null) {
                    lastSentReading = reading
                    BleLog.log("manager: seeded in-memory glucose state from lc=${reading.lifeCount}")
                }
            }
            .onFailure { BleLog.log("manager: previous glucose unavailable: ${it.message}") }
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
     * True only when the cached key is *provably* wrong: a Phase 6 CCM MAC / R1-R2 echo failure during
     * the handshake. Deliberately does NOT include GATT status=19 (peer-terminated) — that is a transient
     * link drop (RF, Wi-Fi/BT coexistence, slow handshake), not proof of a bad key. Treating it as a
     * mismatch discarded a good key and stranded the session until a manual NFC re-provision.
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
        var pendingFirstNotifyTs = 0L
        while (true) {
            val frag = if (pendingFirstNotifyTs > 0L) {
                withTimeoutOrNull(FRAGMENT_ASSEMBLY_TIMEOUT_MS) { channel.receive() } ?: run {
                    BleLog.log(
                        "[ANOMALY] REASSEMBLY: glucose suffix timeout after ${FRAGMENT_ASSEMBLY_TIMEOUT_MS}ms; reconnecting",
                    )
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
                BleLog.log("[ANOMALY] REASSEMBLY: flushed orphan 177a prefix age=${age}ms (suffix never arrived)")
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
                    BleLog.log(
                        "[LATENCY] 177a→decoded lc=${r.lifeCount} firstNotify=$firstNotifyTs secondNotify=$secondNotifyTs " +
                            "decoded=$decodedTs firstNotify→decoded=${decodedTs - firstNotifyTs}ms " +
                            "interFragment=${secondNotifyTs - firstNotifyTs}ms",
                    )
                    lastGlucoseAt.set(decodedTs)
                    // Liveness proof for the staleness safety net (usable or not).
                    StalenessWatchdog.onFreshReading(context)
                    if (awaitingFirstReadingAfterReconnect) {
                        awaitingFirstReadingAfterReconnect = false
                        BleLog.log("manager: first notify after reconnect lifeCount=${r.lifeCount}")
                        BleLog.log("manager: reconnect success durationMs=${decodedTs - reconnectStartedAtMs} attempt=$reconnectAttempt")
                        val lastGood = reconnectLastGoodLifeCount
                        if (lastGood != null && r.lifeCount > lastGood + 1) {
                            BleLog.log(
                                "manager: lifeCount gap after reconnect lastGood=$lastGood now=${r.lifeCount} " +
                                    "missing=${r.lifeCount - lastGood - 1}",
                            )
                        }
                    }
                    if (reconnectAttempt != 0) {
                        BleLog.log("manager: reconnect backoff reset reason=first_valid_reading")
                        reconnectAttempt = 0
                    }
                    val mg = r.currentGlucoseMgDL
                    val usable = r.isCurrentGlucoseUsable
                    val issue = glucoseReadingIssue(r)
                    val issueDetail = glucoseReadingIssueDetail(r)
                    val delta = if (usable && mg != null) deltaPerMin(lastSentReading, mg, r.lifeCount, decodedTs) else null

                    // Publish first. UI, overlays and the foreground notification observe this
                    // StateFlow without waiting for disk I/O.
                    _glucose.value = GlucoseUi(
                        mgDL = mg,
                        trend = r.trendKind.name,
                        lifeCount = r.lifeCount,
                        usable = usable,
                        receivedAtMs = decodedTs,
                        deltaMgDlPerMin = delta,
                        readingIssue = issue,
                        readingIssueDetail = issueDetail,
                        chartMgDL = r.currentGlucoseChartMgDL,
                    )
                    BleLog.log("PHONE_STATE_UPDATED lc=${r.lifeCount} source=ble usable=$usable")
                    BleLog.log(
                        "glucose lifeCount=${r.lifeCount} mgdl=${mg ?: "NA"} trend=${r.trendKind} " +
                            "usable=$usable issue=${issue ?: "none"} $issueDetail",
                    )

                    if (usable && mg != null) {
                        val reading = SensorStateStore.LastGlucose(
                            lifeCount = r.lifeCount,
                            mgDL = mg,
                            trend = r.trendKind.name,
                            receivedAtMs = decodedTs,
                            deltaMgDlPerMin = delta,
                            chartMgDL = r.currentGlucoseChartMgDL,
                        )
                        lastSentReading = reading

                        // Relay directly from the decoded object. Persistence is deliberately last
                        // and fire-and-forget, exactly like the stable Wear implementation.
                        WearDataSync.sendGlucose(context, reading)
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
                        lifecycleChannel.trySend(
                            LifecycleUpdate(
                                lifeCount = s.currentLifeCount.coerceAtLeast(0),
                                observedAtMs = System.currentTimeMillis(),
                            ),
                        )
                        BleLog.log(
                            "decoded $channel kind=${packet.kind} lifeCount=${s.lifeCount} " +
                                "currentLifeCount=${s.currentLifeCount} patchState=${s.patchState} " +
                                "sensorError=${s.sensorError} sensorAttention=${s.sensorAttention} " +
                                "notifyUser=${s.shouldNotifyUser} replaceSensor=${s.shouldNotifyReplaceSensor} " +
                                "stackDisconnectReason=${s.stackDisconnectReason} appDisconnectReason=${s.appDisconnectReason} " +
                                "plaintext=${packet.plaintext.toHex()}",
                        )
                        // Surface sensor errors (insertion failure / ended / replace / unknown) once per
                        // transition: persist for UI + complications, relay to the watch, notify the user.
                        val statusKey = s.errorData to s.patchState
                        if (statusKey != lastSensorStatus) {
                            lastSensorStatus = statusKey
                            val observedAtMs = System.currentTimeMillis()
                            store.saveSensorStatus(s.errorData, s.patchState, observedAtMs)
                            WearDataSync.sendSensorStatus(context, s.errorData, s.patchState, observedAtMs)
                            SensorAttentionNotifier.onAttentionChanged(context, s.sensorAttention)
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
                            val recovered = store.saveBackfilledGlucoseReading(
                                sample.lifeCount,
                                mg,
                                "BACKFILL_HISTORICAL",
                                chartMgDL = sample.glucoseStatus.chartMgDL,
                            )
                            BleLog.log("[BACKFILL] historical recovered lc=${sample.lifeCount} mgdl=$mg saved=$recovered")
                        }
                    }
                    is DataPlaneDecodedPayload.ClinicalReadingRecordPayload -> {
                        val r = payload.record
                        val mg = r.currentGlucoseMgDL
                        val recovered = mg?.let {
                            store.saveBackfilledGlucoseReading(
                                r.lifeCount,
                                it,
                                "BACKFILL_CLINICAL",
                                chartMgDL = r.currentGlucose.chartMgDL,
                            )
                        } ?: false
                        BleLog.log(
                            "[BACKFILL] clinical lc=${r.lifeCount} mgdl=${mg ?: "NA"} " +
                                "raw=${r.currentGlucoseRaw} historicEstimate=${r.historicLifeCountEstimate} saved=$recovered",
                        )
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
                BleLog.log("watchdog: no glucose for ${since / 1000}s → forcing reconnect")
                conn.disconnect()
                requestSensorReconnect("no_data_watchdog")
                return
            }
        }
    }

    /**
     * Per-minute delta from the previous in-memory reading; no DataStore read on the live path.
     * The denominator is the sensor's own minute counter (lifeCount), NOT wall-clock time: after a
     * reconnect the sensor delivers two readings seconds apart, and a wall-clock denominator of a
     * few seconds exploded the delta to ±99.
     */
    private fun deltaPerMin(prev: SensorStateStore.LastGlucose?, mgDL: Int, lifeCount: Int, atMs: Long): Double? {
        val previous = prev ?: return null
        if (previous.receivedAtMs !in 1 until atMs) return null
        val minutes = (lifeCount - previous.lifeCount).toDouble()
        if (minutes <= 0.0) return null
        return ((mgDL - previous.mgDL) / minutes).takeIf { it.isFinite() }
    }

    companion object {
        private const val STOP_JOIN_TIMEOUT_MS = 5_000L
        private const val WATCHDOG_CHECK_MS = 15_000L
        /**
         * Match Wear: two missed minutes plus jitter margin. A real dead link should surface as
         * status=8 quickly; this watchdog only catches rare half-open states.
         */
        private const val NO_DATA_TIMEOUT_MS = 135_000L
        private const val POST_AUTH_PATCH_CONTROL_TIMEOUT_MS = 10_000L
        private const val HANDSHAKE_WAKE_LOCK_TIMEOUT_MS = 90_000L
        private const val PERSIST_SLOW_WARN_MS = 1_000L
        private const val FRAGMENT_ASSEMBLY_TIMEOUT_MS = 8_000L
        private const val RECONNECT_WAKE_LOCK_TIMEOUT_MS = 5_000L
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
        /** Per-attempt reconnect delay (1-based), aligned with the Wear manager. */
        private val RECONNECT_BACKOFF_MS = longArrayOf(
            500L,
            2_000L,
            2_000L,
            5_000L,
            10_000L,
            20_000L,
            30_000L,
        )
        private const val RECONNECT_BACKOFF_MAX_MS = 30_000L
        private const val GLUCOSE_ISSUE_VALUE_UNAVAILABLE = "VALUE_UNAVAILABLE"
        private const val GLUCOSE_ISSUE_DATA_QUALITY = "DATA_QUALITY"
        private const val GLUCOSE_ISSUE_SENSOR_CONDITION = "SENSOR_CONDITION"
        private const val GLUCOSE_ISSUE_NOT_USABLE = "NOT_USABLE"
    }
}
