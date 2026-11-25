package re.abbot.librecr.app.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.service.ReceiverRecoveryService
import re.abbot.librecr.app.service.ReceiverRecoveryAlarm
import re.abbot.librecr.app.service.SensorForegroundService
import re.abbot.librecr.protocol.connection.*
import java.util.UUID

/** Phone-side authority, with a persistent monitor independent of the watch process. */
object AutomaticSensorHandoff {
    private lateinit var context: Context
    private lateinit var coordinator: PhoneRecoveryCoordinator
    private val mutex = Mutex()
    private val events = Channel<Unit>(Channel.CONFLATED)
    private val prefs get() = context.getSharedPreferences("librecr_automatic_handoff", Context.MODE_PRIVATE)
    @Volatile private var snapshot: RecoveryState? = null
    @Volatile private var enabled = false
    @Volatile private var lastLocalPacketMs: Long? = null
    @Volatile private var lastLocalLifeCount = -1
    private var retryAfterMs = 0L
    private var priorityCheckAtMs = 0L
    private var armedWatchId = ""
    private var priorityRemoteLifeCount = -1
    private var releasedWatchSensor = ""
    private var releasedWatchLifeCount = -1
    private val mutableWatchStandbyReady = MutableStateFlow(false)
    val watchStandbyReady = mutableWatchStandbyReady.asStateFlow()
    val phonePriorityEnabled: Boolean get() = enabled
    val ownsPhoneConnection: Boolean get() = snapshot?.phase?.let { it != RecoveryPhase.WATCH_MONITOR } == true
    val monitoringRequired: Boolean get() = snapshot?.let {
        it.phoneReturnTrial || (enabled && it.preferWatch && it.phase == RecoveryPhase.WATCH_MONITOR) ||
            it.phase == RecoveryPhase.WATCH_CONNECTING || it.phase == RecoveryPhase.RELEASING_WATCH ||
            (it.phase == RecoveryPhase.PHONE_CONNECTING && !SensorForegroundService.running)
    } == true || (enabled && hasBluetoothPermissions() && !SensorForegroundService.running)

    fun initialize(context: Context) {
        this.context = context.applicationContext
        snapshot = loadState()
        coordinator = PhoneRecoveryCoordinator(runtime, snapshot, prefs.getLong("last_sequence", 0L))
        LibreCR.appScope.launch {
            while (true) {
                val waitMs = mutex.withLock {
                    val fallback = coordinator.state?.takeIf { it.phase == RecoveryPhase.WATCH_MONITOR && !it.preferWatch }
                    val priorityDueAt = maxOf(priorityCheckAtMs, fallback?.deadlineMs ?: 0L)
                    val priorityWait = if (enabled && (armedWatchId.isBlank() || !coordinator.ownsPhone))
                        (priorityDueAt - SystemClock.elapsedRealtime()).coerceAtLeast(1_000L) else null
                    listOfNotNull(coordinator.nextCheckMs(), priorityWait).minOrNull()
                        ?.coerceAtLeast((retryAfterMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                        .also { ReceiverRecoveryAlarm.schedule(context, it) }
                }
                if (waitMs == null) events.receive()
                else if (withTimeoutOrNull(waitMs) { events.receive(); true } != true) {
                    safely { coordinator.tick(); reconcilePhonePriority() }
                }
            }
        }
    }

    @Volatile var localSensorAddress = ""
        private set
    fun onLocalSessionStarted(address: String) {
        localSensorAddress = address; lastLocalLifeCount = -1; lastLocalPacketMs = null
        if (enabled) launch { reconcilePhonePriority() }
    }

    fun onLocalReading(lifeCount: Int, usable: Boolean) {
        if (lifeCount <= lastLocalLifeCount) return
        lastLocalLifeCount = lifeCount
        lastLocalPacketMs = SystemClock.elapsedRealtime()
        launch { coordinator.localPacket(lifeCount, usable) }
    }

    fun onConnectionInterrupted() {
        lastLocalPacketMs = null
        launch { coordinator.interrupted() }
    }

    /** A real local GATT loss can wake standby without waiting for the remote packet-age timeout. */
    fun onSensorLinkLost(sensorAddress: String, status: Int?) {
        if (!enabled || !ownsPhoneConnection) return
        val lifeCount = lastLocalLifeCount
        if (lifeCount < 0) return
        val lostAtMs = System.currentTimeMillis()
        LibreCR.appScope.launch {
            try {
                val session = LibreCR.store.loadSession() ?: return@launch
                if (!enabled || !ownsPhoneConnection || !LibreCR.store.autoConnectEnabled() ||
                    !session.bleAddress.equals(sensorAddress, true) ||
                    !snapshot?.sensor.equals(sensorAddress, true) || lifeCount != lastLocalLifeCount ||
                    lastLocalPacketMs != null) return@launch
                WearDataSync.sendPhoneSensorLinkLost(context, sensorAddress, lifeCount, lostAtMs, status)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                BleLog.log("receiver recovery: link-loss hint failed: ${error.javaClass.simpleName}")
            }
        }
    }

    /** Only a direct live message with a recent origin timestamp can renew the watch's liveness. */
    fun onRemotePacket(nodeId: String, sensorAddress: String, lifeCount: Int, receivedAtMs: Long, message: Boolean) {
        val age = System.currentTimeMillis() - receivedAtMs
        if (!message || age !in -30_000L..90_000L) return
        launch {
            val session = LibreCR.store.loadSession() ?: return@launch
            if (!session.bleAddress.equals(sensorAddress, true)) return@launch
            if (enabled) {
                if (lifeCount <= maxOf(priorityRemoteLifeCount, lastLocalLifeCount)) return@launch
                // The last watch reading can arrive after its release ACK. It cannot undo a
                // bounded phone trial whose confirmation boundary already includes that reading.
                val trial = coordinator.state?.takeIf { it.phoneReturnTrial &&
                    it.phase in listOf(RecoveryPhase.RELEASING_WATCH, RecoveryPhase.PHONE_CONNECTING) }
                if (trial != null && lifeCount <= trial.confirmationAfterLifeCount) return@launch
                priorityRemoteLifeCount = lifeCount
                if (coordinator.confirmWatchFallback(sensorId(session), nodeId, lifeCount)) {
                    BleLog.log("receiver recovery: watch fallback confirmed; keeping watch until its return request")
                }
                return@launch
            }
            if (coordinator.state == null && !LibreCR.store.autoConnectEnabled()) {
                coordinator.preferWatch(sensorId(session), nodeId)
            }
            coordinator.remotePacket(sensorId(session), nodeId, lifeCount)
        }
    }

    fun onPeerConnected() { launch {
        armedWatchId = ""
        mutableWatchStandbyReady.value = false
        priorityCheckAtMs = 0L
        coordinator.peerConnected(); coordinator.tick(); reconcilePhonePriority()
    } }

    fun onRecoveryWakeup() { ReceiverRecoveryAlarm.runAwake(context) { safely {
        coordinator.tick(); reconcilePhonePriority()
    } } }

    fun onSettingsChanged(enabled: Boolean) {
        this.enabled = enabled
        launch {
            val wasEnabled = prefs.getBoolean("phonePriorityOptIn", false)
            if (wasEnabled != enabled || !prefs.contains("phonePriorityOptIn"))
                prefs.edit().putBoolean("phonePriorityOptIn", enabled).commit()
            val restoringFallback = coordinator.state?.let {
                it.phase == RecoveryPhase.WATCH_MONITOR && !it.preferWatch
            } == true
            // The existing coordinator still handles manual transfers and interrupted commands.
            // The opt-in mode now prefers the phone, so its legacy return-to-watch policy stays off.
            coordinator.configure(false)
            if (!enabled) coordinator.keepPhoneAfterTrial()
            armedWatchId = ""
            mutableWatchStandbyReady.value = false
            priorityCheckAtMs = 0L
            priorityRemoteLifeCount = -1
            if (enabled && hasBluetoothPermissions() && LibreCR.store.loadSession() != null) {
                SensorForegroundService.ensureStandby(context)
            } else if (!enabled && !LibreCR.store.autoConnectEnabled()) SensorForegroundService.stop(context)
            val settings = LibreCR.settings.current()
            val state = coordinator.state
            if (state != null && settings.selectedWearNodeId.isNotBlank() && settings.selectedWearNodeId != state.nodeId) {
                coordinator.cancel()
            }
            if (coordinator.state == null && LibreCR.store.watchTakeoverFlow.first()) {
                val session = LibreCR.store.loadSession() ?: return@launch
                coordinator.preferWatch(sensorId(session), settings.selectedWearNodeId)
            }
            coordinator.tick()
            reconcilePhonePriority(reclaimWatch = enabled &&
                (!wasEnabled || (!restoringFallback && coordinator.state?.phoneReturnTrial != true)))
        }
    }

    suspend fun watchHandoffStarted() = mutex.withLock {
        val session = LibreCR.store.loadSession() ?: return@withLock
        coordinator.preferWatch(sensorId(session), resolveWatchNode())
        if (enabled) reconcilePhonePriority()
        updateMonitor()
        events.trySend(Unit)
    }

    suspend fun cancel(manual: Boolean = false) = mutex.withLock {
        if (manual) disableAutomaticForManualRoute()
        coordinator.cancel()
        updateMonitor()
        events.trySend(Unit)
    }

    /** Explicit user override works with the toggle off and with a dead/unreachable watch. */
    suspend fun takePhoneManually(): Boolean = mutex.withLock {
        disableAutomaticForManualRoute()
        if (!hasBluetoothPermissions()) return@withLock false
        val session = LibreCR.store.loadSession() ?: return@withLock false
        val node = resolveWatchNode()
        coordinator.cancel()
        val started = coordinator.requestPhone(sensorId(session), node, manual = true)
        updateMonitor()
        events.trySend(Unit)
        started
    }

    suspend fun acceptsBackupNode(nodeId: String): Boolean = WearDataSync.acceptsSourceNode(nodeId)

    fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray>? {
        if (path != AutomaticHandoff.REQUEST_PHONE && path != RecoveryPolicy.STATUS) return null
        return handoffTask(LibreCR.appScope) {
            if (path == RecoveryPolicy.STATUS) {
                if (!WearDataSync.acceptsSourceNode(nodeId)) return@handoffTask handoffReply(false)
                val session = LibreCR.store.loadSession() ?: return@handoffTask handoffReply(false)
                val request = JSONObject(String(data, Charsets.UTF_8))
                if (!session.bleAddress.equals(request.optString("bleAddress"), true)) return@handoffTask handoffReply(false)
                val current = snapshot
                val age = lastLocalPacketMs?.let { SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE
                val confirmedTrialPacket = current?.phoneReturnTrial != true ||
                    (current.phase == RecoveryPhase.PHONE_CONNECTING &&
                        SystemClock.elapsedRealtime() < current.deadlineMs &&
                        lastLocalLifeCount > current.confirmationAfterLifeCount)
                return@handoffTask handoffReply(true).put("bleAddress", session.bleAddress)
                    .put("active", confirmedTrialPacket && LibreCR.manager.state.value == ConnectionState.STREAMING && age < RecoveryPolicy.SILENCE_MS)
                    .put("lastLifeCount", lastLocalLifeCount)
                    .put("packetAgeMs", age).put("preferWatch", current?.preferWatch == true).put("claimRemainingMs", if (current?.phase == RecoveryPhase.PHONE_CONNECTING)
                        (current.claimUntilMs - SystemClock.elapsedRealtime()).coerceIn(0, RecoveryPolicy.FIRST_PACKET_MS) else 0L)
            }
            mutex.withLock {
                if (!WearDataSync.acceptsSourceNode(nodeId)) return@withLock handoffReply(false)
                val session = LibreCR.store.loadSession() ?: return@withLock handoffReply(false)
                val json = JSONObject(String(data, Charsets.UTF_8))
                if (json.optString("bleAddress").isNotBlank() && !session.bleAddress.equals(json.optString("bleAddress"), true)) {
                    return@withLock handoffReply(false)
                }
                if (!enabled || !hasBluetoothPermissions() || !nearbyHandoffNode(context, nodeId)) return@withLock handoffReply(false)
                val accepted = if (json.optBoolean("phonePriority")) {
                    if (json.optBoolean("criticalBattery")) {
                        // A nearly empty watch cannot remain the reliable fallback receiver.
                        reconcilePhonePriority(watchCollecting = true, reclaimWatch = true)
                        coordinator.ownsPhone
                    } else if (json.optBoolean("phoneReturnTrial")) {
                        val accepted = coordinator.tryPhoneReturn(sensorId(session), nodeId)
                        BleLog.log("PHONE_RETURN_TRIAL_REQUEST accepted=$accepted phase=${coordinator.state?.phase}")
                        accepted
                    } else false // An older requester cannot start an unbounded automatic reclaim.
                } else coordinator.requestPhone(sensorId(session), nodeId, criticalBattery = json.optBoolean("criticalBattery"))
                updateMonitor()
                events.trySend(Unit)
                handoffReply(accepted).put("phase", coordinator.state?.phase?.name)
            }
        }
    }

    private fun launch(block: suspend () -> Unit) { LibreCR.appScope.launch { safely(block) } }
    private suspend fun safely(block: suspend () -> Unit) {
        try { mutex.withLock {
            val session = LibreCR.store.loadSession()
            if (coordinator.state != null && (session == null || coordinator.state?.sensor != sensorId(session))) coordinator.cancel()
            block()
            retryAfterMs = 0L
            updateMonitor()
        } }
        catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            retryAfterMs = SystemClock.elapsedRealtime() + RecoveryPolicy.retryDelay(0)
            BleLog.log("receiver recovery: ${error.javaClass.simpleName}: ${error.message}")
        } finally { events.trySend(Unit) }
    }

    private fun updateMonitor() { ReceiverRecoveryService.ensure(context, monitoringRequired) }

    private suspend fun disableAutomaticForManualRoute() {
        enabled = false
        armedWatchId = ""
        mutableWatchStandbyReady.value = false
        prefs.edit().putBoolean("phonePriorityOptIn", false).commit()
        coordinator.configure(false)
        LibreCR.settings.setAutomaticSensorHandoffEnabled(false)
    }

    private suspend fun reconcilePhonePriority(watchCollecting: Boolean = false, reclaimWatch: Boolean = false) {
        if (!enabled || !hasBluetoothPermissions()) return
        // Peer/settings callbacks must not re-arm the released watch or renew an active trial.
        if (coordinator.state?.phoneReturnTrial == true && !reclaimWatch && !watchCollecting) return
        if (!watchCollecting && !reclaimWatch && SystemClock.elapsedRealtime() < priorityCheckAtMs) return
        priorityCheckAtMs = SystemClock.elapsedRealtime() + PhonePriorityPolicy.ARM_RETRY_MS
        val session = LibreCR.store.loadSession() ?: return
        val node = resolveWatchNode()
        val nearby = node.isNotBlank() && nearbyHandoffNode(context, node)
        var watchOwns = watchCollecting
        val fallback = coordinator.state?.takeIf { it.phase == RecoveryPhase.WATCH_MONITOR && !it.preferWatch }
        if (!reclaimWatch && fallback != null) {
            // Keep a fresh fallback, even if its relays still arrive over the phone's longer-range
            // Bluetooth link. A dead/silent watch remains recoverable after its liveness deadline.
            if (SystemClock.elapsedRealtime() < fallback.deadlineMs) return
            val health = runtime.status(node)
            if (health.healthy || health.claimActive) return
        }
        if (nearby && (armedWatchId != node || watchCollecting)) {
            val key = LibreCR.store.loadCachedPhase5RawKey()
            val response = rpc(node, PhonePriorityPolicy.ARM_WATCH, JSONObject()
                .put("session", session.copy(phase5RawKey = key).toJson()))
            // An older watch, or a denied background service start, must never be treated as ready.
            if (response?.optBoolean("accepted") != true) {
                armedWatchId = ""
                mutableWatchStandbyReady.value = false
                return
            }
            armedWatchId = node
            mutableWatchStandbyReady.value = true
            // A delayed reading/request cannot overrule the watch's current ownership response.
            watchOwns = response.optBoolean("watchOwnsSensor")
        }
        if (coordinator.state?.sensor != null && coordinator.state?.sensor != sensorId(session)) coordinator.cancel()
        if (watchOwns && !reclaimWatch) {
            coordinator.confirmWatchFallback(sensorId(session), node)
            return
        }
        coordinator.preferPhone(sensorId(session), node, watchCollecting = watchOwns,
            phoneAlreadyRunning = LibreCR.manager.isRunning)
    }

    private suspend fun resolveWatchNode(): String {
        val selected = LibreCR.settings.current().selectedWearNodeId
        if (selected.isNotBlank()) return selected
        snapshot?.nodeId?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { WearDataSync.connectedWearNodes(context).singleOrNull { it.isNearby }?.id.orEmpty() }.getOrDefault("")
    }

    private fun sensorId(session: ImportedSession): String = session.bleAddress.uppercase()
    private fun command(state: RecoveryState) = JSONObject().put("token", state.token).put("sequence", state.sequence)
        .put("bleAddress", state.sensor).put("controller", controllerId())
    private fun controllerId(): String = prefs.getString("controller", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("controller", it).commit()
    }

    private suspend fun rpc(nodeId: String, path: String, payload: JSONObject): JSONObject? {
        if (nodeId.isBlank()) return null
        return try {
            if (!nearbyHandoffNode(context, nodeId)) null else handoffRpc(context, nodeId, path, payload)
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            null
        }
    }

    private val runtime = object : RecoveryRuntime {
        override fun nowMs() = SystemClock.elapsedRealtime()
        override fun nextToken() = UUID.randomUUID().toString()
        override fun nextSequence(previous: Long) = maxOf(previous + 1, System.currentTimeMillis())
        override suspend fun save(state: RecoveryState?) {
            val editor = prefs.edit()
            if (state == null) editor.remove("recovery_v2") else editor.putString("recovery_v2", JSONObject()
                .put("sensor", state.sensor).put("node", state.nodeId).put("phase", state.phase.name)
                .put("token", state.token).put("sequence", state.sequence).put("preferWatch", state.preferWatch)
                .put("phoneReturnTrial", state.phoneReturnTrial)
                .put("deadlineWall", System.currentTimeMillis() + (state.deadlineMs - nowMs()).coerceAtLeast(0))
                .put("failedWatchTransfers", state.failedWatchTransfers)
                .put("confirmationAfterLifeCount", state.confirmationAfterLifeCount)
                .put("claimUntilWall", System.currentTimeMillis() + (state.claimUntilMs - nowMs()).coerceAtLeast(0))
                .put("retryWatchWall", System.currentTimeMillis() + (state.retryWatchAtMs - nowMs()).coerceAtLeast(0)).toString())
            if (state != null) editor.putLong("last_sequence", maxOf(state.sequence, prefs.getLong("last_sequence", 0L)))
            check(editor.remove("cycle").commit()) { "Cannot persist receiver recovery" }
            if (snapshot?.phase != state?.phase || snapshot?.phoneReturnTrial != state?.phoneReturnTrial) {
                BleLog.log("PHONE_RECOVERY_STATE phase=${state?.phase} returnTrial=${state?.phoneReturnTrial == true} " +
                    "confirmationAfterLc=${state?.confirmationAfterLifeCount ?: -1} " +
                    "deadlineInMs=${state?.let { (it.deadlineMs - nowMs()).coerceAtLeast(0) } ?: 0}")
            }
            snapshot = state
            updateMonitor()
        }
        override suspend fun status(nodeId: String): ReceiverHealth {
            val releasedLifeCount = if (snapshot?.sensor == releasedWatchSensor) releasedWatchLifeCount else -1
            val response = rpc(nodeId, RecoveryPolicy.STATUS, JSONObject().put("bleAddress", snapshot?.sensor))
                ?: return ReceiverHealth(lastLifeCount = releasedLifeCount)
            val session = LibreCR.store.loadSession() ?: return ReceiverHealth()
            if (!session.bleAddress.equals(response.optString("bleAddress"), true)) return ReceiverHealth()
            return ReceiverHealth(reachable = response.optBoolean("accepted"), active = response.optBoolean("active"),
                packetAgeMs = response.optLong("packetAgeMs", Long.MAX_VALUE), canStart = response.optBoolean("canStart"),
                claiming = response.optLong("claimRemainingMs") > 0, claimRemainingMs = response.optLong("claimRemainingMs"),
                lastLifeCount = maxOf(response.optInt("lastLifeCount", -1), releasedLifeCount))
        }
        override suspend fun releaseWatch(state: RecoveryState, manual: Boolean): Boolean {
            releasedWatchSensor = state.sensor
            releasedWatchLifeCount = -1
            val response = rpc(state.nodeId, AutomaticHandoff.RELEASE_WATCH, command(state)
                .put("manual", manual).put("allowRecovery", enabled || state.preferWatch)
                .put("phonePriority", enabled).put("phoneReturnTrial", state.phoneReturnTrial)
                .put("returnLeaseUntilWall", if (state.phoneReturnTrial)
                    System.currentTimeMillis() + PhonePriorityPolicy.RETURN_LEASE_MS else 0L)) ?: return false
            if (response.optBoolean("accepted")) releasedWatchLifeCount = response.optInt("lastLifeCount", -1)
            response.optString("session").takeIf { it.isNotBlank() }?.let { ReceiverSessionBackup.acceptReleasedSession(it) }
            if (response.optBoolean("accepted")) delay(AutomaticHandoff.BLE_RELEASE_DELAY_MS)
            return response.optBoolean("accepted")
        }
        override suspend fun prepareWatch(state: RecoveryState): Boolean =
            rpc(state.nodeId, RecoveryPolicy.PREPARE_WATCH, command(state))?.optBoolean("accepted") == true
        override suspend fun startWatch(state: RecoveryState): Boolean {
            val session = LibreCR.store.loadSession() ?: return false
            val key = LibreCR.store.loadCachedPhase5RawKey()
            delay(AutomaticHandoff.BLE_RELEASE_DELAY_MS)
            return rpc(state.nodeId, AutomaticHandoff.START_WATCH, command(state)
                .put("session", session.copy(phase5RawKey = key).toJson())
                .put("phoneReturnRollback", state.phoneReturnTrial && state.phase == RecoveryPhase.WATCH_CONNECTING))
                ?.optBoolean("accepted") == true
        }
        override suspend fun startPhone(): Boolean {
            if (LibreCR.manager.state.value == ConnectionState.IDLE) {
                ReceiverSessionBackup.adopt(snapshot?.nodeId.orEmpty())
            }
            LibreCR.store.setWatchTakeoverEnabled(false)
            LibreCR.store.setAutoConnectEnabled(true)
            return SensorForegroundService.start(context, allowCandidateFirstPair = true, passiveConnect = snapshot?.preferWatch == true)
        }
        override suspend fun stopPhone(): Boolean {
            LibreCR.store.setAutoConnectEnabled(false)
            val stopped = LibreCR.manager.stopAndJoin()
            SensorForegroundService.stop(context)
            return stopped
        }
        override suspend fun watchOwnsSensor() { LibreCR.store.setWatchTakeoverEnabled(true) }
    }

    private fun loadState(): RecoveryState? = runCatching {
        val raw = prefs.getString("recovery_v2", null)
        if (raw == null) {
            val legacy = JSONObject(prefs.getString("cycle", null) ?: return@runCatching null)
            return@runCatching RecoveryState(legacy.getString("address").uppercase(), legacy.getString("nodeId"),
                if (legacy.optBoolean("returning")) RecoveryPhase.WATCH_CONNECTING else RecoveryPhase.RELEASING_WATCH,
                legacy.getString("token"), maxOf(prefs.getLong("last_sequence", 0), System.currentTimeMillis()),
                deadlineMs = SystemClock.elapsedRealtime() + AutomaticHandoff.RPC_TIMEOUT_MS)
        }
        val json = JSONObject(raw)
        val trial = json.optBoolean("phoneReturnTrial", false)
        val wallMs = System.currentTimeMillis()
        val elapsedMs = SystemClock.elapsedRealtime()
        fun restoreDeadline(savedWallMs: Long): Long = if (trial)
            PhonePriorityPolicy.restoreReturnTrialUntil(savedWallMs, wallMs, elapsedMs)
        else RecoveryPolicy.restoreDeadline(savedWallMs, wallMs, elapsedMs)
        RecoveryState(json.getString("sensor"), json.getString("node"), RecoveryPhase.valueOf(json.getString("phase")),
            json.getString("token"), maxOf(json.getLong("sequence"), prefs.getLong("last_sequence", 0L)),
            json.optBoolean("preferWatch", true), restoreDeadline(json.optLong("deadlineWall")), json.optInt("failedWatchTransfers"),
            SystemClock.elapsedRealtime() + (json.optLong("retryWatchWall") - System.currentTimeMillis()).coerceIn(0, 1_800_000L),
            json.optInt("confirmationAfterLifeCount", -1), restoreDeadline(json.optLong("claimUntilWall")), trial)
    }.getOrNull()

    private fun hasBluetoothPermissions(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
