package re.abbot.librecr.app.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.service.SensorForegroundService
import re.abbot.librecr.app.service.ReceiverRecoveryService
import re.abbot.librecr.app.service.ReceiverRecoveryAlarm
import re.abbot.librecr.protocol.connection.*

/** The watch keeps collecting in isolation and can recover an abandoned release itself. */
object AutomaticSensorHandoff {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val events = Channel<Unit>(Channel.CONFLATED)
    private lateinit var context: Context
    private var requestJob: Job? = null
    @Volatile private var nextRequestAtMs = 0L
    private var preparedUntilMs = 0L
    private var startUntilMs = 0L
    private var recoveryRetries = 0
    @Volatile private var priorityReturnFailures = 0
    @Volatile private var lastPacketMs: Long? = null
    @Volatile private var lastLifeCount = -1
    @Volatile private var lastRemoteLifeCount = -1
    @Volatile private var remoteDeadlineMs = 0L
    @Volatile var enabled = false
        private set
    @Volatile private var preferPhone = false
    @Volatile private var priorityArmed = false
    @Volatile private var lastRemotePacketMs: Long? = null
    private var returnLeaseUntilMs = 0L
    private var returnBoundaryLifeCount = -1
    @Volatile private var returnReclaimPending = false
    private var phoneHealthySinceMs: Long? = null
    private var freshPhoneStatusRequired = false
    @Volatile private var lastLinkLossLifeCount = -1
    @Volatile private var lastLinkLossAtMs = 0L
    private var lastPhoneNearby: Boolean? = null
    @Volatile private var phoneNodeId = ""
    @Volatile private var batteryPercent = 100
    @Volatile private var charging = false
    private val prefs get() = context.getSharedPreferences("librecr_automatic_handoff", Context.MODE_PRIVATE)
    val phonePriorityServiceRequired: Boolean get() = enabled && preferPhone && priorityArmed
    val monitoringRequired: Boolean get() = phonePriorityServiceRequired ||
        (enabled && !preferPhone && prefs.getBoolean("released", false) && prefs.getBoolean("allowRecovery", true))

    fun initialize(context: Context) {
        this.context = context.applicationContext
        enabled = prefs.getBoolean("enabled", false)
        preferPhone = prefs.getBoolean("preferPhone", false)
        priorityArmed = prefs.getBoolean("priorityArmed", false)
        priorityReturnFailures = prefs.getInt("priorityReturnFailures", 0)
        nextRequestAtMs = PhonePriorityPolicy.restoreReturnRetryAt(prefs.getLong("returnRetryWall", 0),
            System.currentTimeMillis(), SystemClock.elapsedRealtime())
        // Migrate the old indefinite veto into one finite cooldown.
        if (prefs.getBoolean("returnNeedsReappearance", false) && !prefs.contains("returnRetryWall")) {
            scheduleReturnRetry()
        }
        prefs.edit().remove("returnNeedsReappearance").apply()
        returnLeaseUntilMs = if (prefs.contains("returnLeaseUntilWall"))
            PhonePriorityPolicy.restoreReturnLeaseUntil(prefs.getLong("returnLeaseUntilWall", 0),
                System.currentTimeMillis(), SystemClock.elapsedRealtime()) else 0L
        returnBoundaryLifeCount = prefs.getInt("returnBoundaryLifeCount", -1)
        returnReclaimPending = prefs.getBoolean("returnReclaimPending", false)
        lastLinkLossLifeCount = prefs.getInt("lastLinkLossLifeCount", -1)
        lastLinkLossAtMs = prefs.getLong("lastLinkLossAtMs", 0)
        lastRemoteLifeCount = maxOf(lastRemoteLifeCount, lastLinkLossLifeCount)
        phoneNodeId = prefs.getString("phoneNodeId", "").orEmpty()
        lastPhoneNearby = if (prefs.contains("lastPhoneNearby")) prefs.getBoolean("lastPhoneNearby", false) else null
        preparedUntilMs = RecoveryPolicy.restoreDeadline(prefs.getLong("prepareUntilWall", 0),
            System.currentTimeMillis(), SystemClock.elapsedRealtime())
        startUntilMs = RecoveryPolicy.restoreDeadline(prefs.getLong("startDeadlineWall", 0),
            System.currentTimeMillis(), SystemClock.elapsedRealtime())
        remoteDeadlineMs = RecoveryPolicy.restoreDeadline(prefs.getLong("releaseDeadlineWall", 0),
            System.currentTimeMillis(), SystemClock.elapsedRealtime())
        trace { "WATCH_RECOVERY_INIT enabled=$enabled preferPhone=$preferPhone armed=$priorityArmed " +
            "requested=${prefs.getBoolean("priorityRequested", false)} released=${prefs.getBoolean("released", false)} " +
            "returnFailures=$priorityReturnFailures " +
            "checkInMs=${(remoteDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)}" }
        this.context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) batteryPercent = level * 100 / scale
                charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in
                    listOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
                if (batteryPercent <= RecoveryPolicy.CRITICAL_BATTERY_PERCENT && !charging) retryIfNeeded()
            }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        Wearable.getDataClient(this.context).dataItems.addOnSuccessListener { items ->
            try {
                for (item in items) if (item.uri.path == AutomaticHandoff.SETTINGS) {
                    item.data?.let { receiveSettings(item.uri.host.orEmpty(), it.copyOf()) }
                }
            } finally { items.release() }
        }
        scope.launch {
            while (true) {
                updateMonitor()
                if (!monitoringRequired) {
                    events.receive()
                    recoveryRetries = 0
                    continue
                }
                val remaining = (remoteDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                if (withTimeoutOrNull(remaining) { events.receive(); true } == true) continue
                checkRecovery()
            }
        }
        updateMonitor()
    }

    fun onRecoveryWakeup() {
        trace { "WATCH_RECOVERY_WAKE required=$monitoringRequired checkInMs=${(remoteDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)}" }
        ReceiverRecoveryAlarm.runAwake(context) { checkRecovery() }
    }

    private suspend fun checkRecovery() = mutex.withLock {
        if (!monitoringRequired || SystemClock.elapsedRealtime() < remoteDeadlineMs) return@withLock
        trace { "WATCH_RECOVERY_CHECK released=${prefs.getBoolean("released", false)} priority=$phonePriorityServiceRequired " +
            "loop=${LibreCR.manager.isRunning} state=${LibreCR.manager.state.value}" }
        try {
            val session = LibreCR.store.loadSession() ?: run {
                prefs.edit().putBoolean("released", false).apply()
                priorityArmed = false
                prefs.edit().putBoolean("priorityArmed", false).apply()
                SensorForegroundService.stop(context)
                return@withLock
            }
            if (phonePriorityServiceRequired) {
                val presence = nearbyHandoffNodeOrNull(context, phoneNodeId)
                if (presence != null) observePhonePresence(presence)
                // A timed-out query must not invent a departure/reappearance and clear the guard.
                val nearby = presence ?: (lastPhoneNearby == true)
                val released = prefs.getBoolean("released", false)
                // A cloud relay is not phone proximity. Check the direct node before trusting packets.
                val packetAge = lastRemotePacketMs?.let { SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE
                val freshStatus = freshPhoneStatusRequired
                val trialExpired = released && (returnReclaimPending ||
                    PhonePriorityPolicy.returnLeaseExpired(returnLeaseUntilMs, SystemClock.elapsedRealtime()))
                val phone = if (released && (trialExpired || freshStatus || (nearby && packetAge >= RecoveryPolicy.SILENCE_MS)))
                    phoneStatus(session, allowRemote = freshStatus || trialExpired) else null
                val health = if (!trialExpired && !freshStatus && released && nearby && packetAge < RecoveryPolicy.SILENCE_MS)
                    ReceiverHealth(reachable = true, active = true, packetAgeMs = packetAge)
                else ReceiverHealth(reachable = phone?.optBoolean("accepted") == true,
                    active = phone?.optBoolean("active") == true,
                    packetAgeMs = phone?.optLong("packetAgeMs", Long.MAX_VALUE) ?: Long.MAX_VALUE,
                    claiming = (phone?.optLong("claimRemainingMs") ?: 0L) > 0,
                    claimRemainingMs = phone?.optLong("claimRemainingMs") ?: 0L)
                freshPhoneStatusRequired = false
                val critical = batteryPercent <= RecoveryPolicy.CRITICAL_BATTERY_PERCENT && !charging
                // A trial has a finite lease. Its launch ACK or a renewed claim cannot strand the
                // watch in standby if the phone dies or never obtains an actual sensor reading.
                val action = when {
                    released && returnLeaseUntilMs > 0 && !trialExpired -> PhonePriorityPolicy.WatchAction.WAIT
                    trialExpired && health.healthy -> {
                        clearReturnLease()
                        setReturnReclaimPending(false)
                        PhonePriorityPolicy.WatchAction.WAIT
                    }
                    trialExpired -> PhonePriorityPolicy.WatchAction.START_WATCH
                    freshStatus && (health.healthy || health.claimActive) -> PhonePriorityPolicy.WatchAction.WAIT
                    else -> PhonePriorityPolicy.watchAction(true, !released, nearby, health)
                }
                trace { "WATCH_PRIORITY_DECISION released=$released nearby=$nearby presenceKnown=${presence != null} phoneReachable=${health.reachable} " +
                    "phoneActive=${health.active} phonePacketAgeMs=${health.packetAgeMs} " +
                    "phoneClaimMs=${health.claimRemainingMs} freshStatus=$freshStatus action=$action " +
                    "trialExpired=$trialExpired critical=$critical " +
                    "returnInMs=${(nextRequestAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)}" }
                when (action) {
                    PhonePriorityPolicy.WatchAction.START_WATCH -> {
                        // Count the failed attempt once at release; waking/restarting this loop
                        // must neither clear nor extend the persisted backoff on every check.
                        if (returnLeaseUntilMs == 0L && nearby && SystemClock.elapsedRealtime() >= nextRequestAtMs)
                            scheduleReturnRetry()
                        clearReturnLease()
                        if (trialExpired) setReturnReclaimPending(true)
                        phoneHealthySinceMs = null
                        lastRemotePacketMs = null
                        ReceiverSessionBackup.adopt(phoneNodeId)
                        LibreCR.store.setAutoConnectEnabled(true)
                        startUntilMs = SystemClock.elapsedRealtime() + RecoveryPolicy.FIRST_PACKET_MS
                        prefs.edit().putBoolean("released", false)
                            .putLong("startDeadlineWall", System.currentTimeMillis() + RecoveryPolicy.FIRST_PACKET_MS)
                            .apply {
                                if (trialExpired) putLong("sequence", prefs.getLong("sequence", 0) + 1)
                            }.commit()
                        val started = SensorForegroundService.start(context, allowCandidateFirstPair = true)
                        trace { "WATCH_PRIORITY_START accepted=$started nearby=$nearby" }
                        if (!started) {
                            LibreCR.store.setAutoConnectEnabled(false)
                            prefs.edit().putBoolean("released", true).commit()
                        }
                    }
                    PhonePriorityPolicy.WatchAction.REQUEST_PHONE -> retryIfNeeded()
                    PhonePriorityPolicy.WatchAction.WAIT -> if (!released && !LibreCR.manager.isRunning) {
                        SensorForegroundService.start(context, allowCandidateFirstPair = true)
                    }
                }
                remoteDeadlineMs = SystemClock.elapsedRealtime() + PhonePriorityPolicy.PRESENCE_CHECK_MS
                return@withLock
            }
            val status = phoneStatus(session)
            val age = status?.optLong("packetAgeMs", Long.MAX_VALUE) ?: Long.MAX_VALUE
            trace { "WATCH_RECOVERY_STATUS phoneActive=${status?.optBoolean("active") == true} " +
                "phonePacketAgeMs=$age phoneClaimMs=${status?.optLong("claimRemainingMs") ?: 0L}" }
            if (status?.optBoolean("active") == true && age < RecoveryPolicy.SILENCE_MS) {
                remoteDeadlineMs = SystemClock.elapsedRealtime() + RecoveryPolicy.SILENCE_MS - age
            } else if ((status?.optLong("claimRemainingMs") ?: 0L) > 0) {
                remoteDeadlineMs = SystemClock.elapsedRealtime() + status!!.optLong("claimRemainingMs")
                    .coerceAtMost(RecoveryPolicy.FIRST_PACKET_MS)
            } else {
                ReceiverSessionBackup.adopt(phoneNodeId)
                LibreCR.store.setAutoConnectEnabled(true)
                prefs.edit().putBoolean("released", false).commit()
                if (!SensorForegroundService.start(context, allowCandidateFirstPair = true, passiveConnect = true)) {
                    prefs.edit().putBoolean("released", true).commit()
                    remoteDeadlineMs = SystemClock.elapsedRealtime() + RecoveryPolicy.retryDelay(recoveryRetries++)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            remoteDeadlineMs = SystemClock.elapsedRealtime() + RecoveryPolicy.retryDelay(recoveryRetries++)
            BleLog.log("receiver recovery: watch resume pending: ${error.javaClass.simpleName}")
        } finally { updateMonitor(); events.trySend(Unit) }
    }

    fun receiveSettings(sourceNodeId: String, payload: ByteArray) {
        scope.launch {
            mutex.withLock {
                try {
                    val json = JSONObject(String(payload, Charsets.UTF_8))
                    val version = json.optLong("syncedAtMs")
                    if (sourceNodeId == phoneNodeId && version < prefs.getLong("settingsVersion", 0)) return@withLock
                    val selected = json.optString("selectedWatchId")
                    val requestedEnabled = json.optBoolean("enabled", false)
                    // Disabling recovery or selecting automatically does not need Play services.
                    // A stalled selected-node lookup must not hold this mutex across recovery wakes.
                    val localId = if (requestedEnabled && selected.isNotBlank()) {
                        handoffQueryOrNull("local_node") { Wearable.getNodeClient(context).localNode.awaitHandoff().id }
                            ?: run {
                                trace { "WATCH_RECOVERY_SETTINGS deferred reason=local_node_unavailable version=$version" }
                                return@withLock
                            }
                    } else ""
                    enabled = requestedEnabled && (selected.isBlank() || selected == localId)
                    preferPhone = json.optBoolean("preferPhone", false)
                    val changedPhone = phoneNodeId != sourceNodeId
                    if (changedPhone) {
                        clearLinkLossFence()
                        lastRemoteLifeCount = -1
                        lastRemotePacketMs = null
                    }
                    if (!enabled || !preferPhone || changedPhone) {
                        requestJob?.cancel()
                        priorityArmed = false
                        clearReturnLease()
                        setReturnReclaimPending(false)
                        resetReturnBackoff()
                        prefs.edit().putBoolean("priorityArmed", false).putBoolean("priorityRequested", false).commit()
                    }
                    phoneNodeId = sourceNodeId
                    prefs.edit().putBoolean("enabled", enabled).putString("phoneNodeId", sourceNodeId)
                        .putBoolean("preferPhone", preferPhone)
                        .putLong("settingsVersion", version).apply()
                    trace { "WATCH_RECOVERY_SETTINGS enabled=$enabled preferPhone=$preferPhone armed=$priorityArmed " +
                        "released=${prefs.getBoolean("released", false)} changedPhone=$changedPhone version=$version" }
                    updateMonitor()
                    events.trySend(Unit)
                    if (changedPhone) ReceiverSessionBackup.refresh()
                    if (enabled) retryIfNeeded()
                    if (!phonePriorityServiceRequired && !LibreCR.store.autoConnectEnabled()) SensorForegroundService.stop(context)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    BleLog.log("receiver recovery: settings failed: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    @Volatile var localSensorAddress = ""
        private set
    fun onLocalSessionStarted(address: String) { localSensorAddress = address; lastPacketMs = null; lastLifeCount = -1 }
    fun onLocalReading(lifeCount: Int) {
        if (lifeCount <= lastLifeCount) return
        lastLifeCount = lifeCount
        lastPacketMs = SystemClock.elapsedRealtime()
        if (returnReclaimPending) setReturnReclaimPending(false)
    }
    suspend fun onRemotePacket(nodeId: String, lifeCount: Int, receivedAtMs: Long, message: Boolean) = mutex.withLock {
        if (nodeId != phoneNodeId || !message || System.currentTimeMillis() - receivedAtMs !in -30_000L..90_000L || lifeCount <= lastRemoteLifeCount) return@withLock
        lastRemoteLifeCount = lifeCount
        if (lastRemotePacketMs?.let { SystemClock.elapsedRealtime() - it > 90_000L } != false)
            phoneHealthySinceMs = null
        lastRemotePacketMs = SystemClock.elapsedRealtime()
        val expiredReturn = returnReclaimPending ||
            PhonePriorityPolicy.returnLeaseExpired(returnLeaseUntilMs, SystemClock.elapsedRealtime())
        if (prefs.getBoolean("released", false) && !expiredReturn && PhonePriorityPolicy.isFreshPhoneRecovery(
                lifeCount, maxOf(lastLifeCount, returnBoundaryLifeCount), receivedAtMs,
                lastLinkLossLifeCount, lastLinkLossAtMs)) {
            clearReturnLease()
            val now = SystemClock.elapsedRealtime()
            val since = phoneHealthySinceMs ?: now.also { phoneHealthySinceMs = it }
            // A single successful packet followed by another loss must not reset the failure
            // budget and recreate the short repeating transfer cycle.
            if (now - since >= RecoveryPolicy.SILENCE_MS) resetReturnBackoff()
        }
        if (prefs.getBoolean("released", false)) {
            remoteDeadlineMs = SystemClock.elapsedRealtime() + if (expiredReturn) 0L
                else if (phonePriorityServiceRequired) PhonePriorityPolicy.PRESENCE_CHECK_MS else RecoveryPolicy.SILENCE_MS
            events.trySend(Unit)
        } else if (phonePriorityServiceRequired && PhonePriorityPolicy.isFreshPhoneRecovery(
                lifeCount, lastLifeCount, receivedAtMs, lastLinkLossLifeCount, lastLinkLossAtMs)) {
            // Only local phone BLE code sends these advancing live messages. If the phone
            // recovered before this standby attempt, leave its healthy link alone.
            ReceiverRecoveryAlarm.runAwake(context) { mutex.withLock phoneRecovered@ {
                if (!phonePriorityServiceRequired || prefs.getBoolean("released", false) ||
                    nodeId != phoneNodeId || lifeCount < lastRemoteLifeCount ||
                    !PhonePriorityPolicy.isFreshPhoneRecovery(lifeCount, lastLifeCount, receivedAtMs,
                        lastLinkLossLifeCount, lastLinkLossAtMs) ||
                    System.currentTimeMillis() - receivedAtMs !in -30_000L..90_000L) return@phoneRecovered
                // A queued phone packet can predate an unannounced loss (for example power-off).
                // Confirm the live link before giving up even a pending watch connection.
                val session = LibreCR.store.loadSession() ?: return@phoneRecovered
                val status = phoneStatus(session, allowRemote = true)
                if (status?.optBoolean("active") != true ||
                    status.optLong("packetAgeMs", Long.MAX_VALUE) !in 0 until RecoveryPolicy.SILENCE_MS ||
                    !PhonePriorityPolicy.isFreshPhoneRecovery(lifeCount, lastLifeCount, receivedAtMs,
                        lastLinkLossLifeCount, lastLinkLossAtMs)) return@phoneRecovered
                LibreCR.store.setAutoConnectEnabled(false)
                val stopped = LibreCR.manager.stopAndJoin()
                park()
                SensorForegroundService.stop(context)
                trace { "WATCH_PRIORITY_PHONE_RECOVERED lc=$lifeCount stopped=$stopped; parked pending local loop" }
            } }
        }
    }

    /** Message-only hints wake a fresh phone status check; they never grant sensor ownership. */
    fun onPhoneSensorLinkLost(nodeId: String, payload: ByteArray) {
        if (!phonePriorityServiceRequired || nodeId != phoneNodeId || !prefs.getBoolean("released", false)) return
        val json = try { JSONObject(String(payload, Charsets.UTF_8)) }
        catch (_: Exception) { return }
        val sensor = json.optString("bleAddress")
        val lifeCount = json.optInt("lifeCount", -1)
        val lostAtMs = json.optLong("lostAtMs", 0)
        ReceiverRecoveryAlarm.runAwake(context) {
            val accepted = mutex.withLock {
                val session = LibreCR.store.loadSession() ?: return@withLock false
                if (!PhonePriorityPolicy.acceptsLinkLoss(phonePriorityServiceRequired,
                        prefs.getBoolean("released", false), nodeId == phoneNodeId,
                        session.bleAddress.equals(sensor, true), System.currentTimeMillis() - lostAtMs,
                        lifeCount, lastRemoteLifeCount)) return@withLock false
                if (lifeCount == lastLinkLossLifeCount && lostAtMs < lastLinkLossAtMs) return@withLock false
                lastLinkLossLifeCount = lifeCount
                lastLinkLossAtMs = lostAtMs
                lastRemoteLifeCount = maxOf(lastRemoteLifeCount, lifeCount)
                prefs.edit().putInt("lastLinkLossLifeCount", lifeCount).putLong("lastLinkLossAtMs", lostAtMs).commit()
                lastRemotePacketMs = null
                phoneHealthySinceMs = null
                freshPhoneStatusRequired = true
                remoteDeadlineMs = SystemClock.elapsedRealtime()
                trace { "WATCH_PHONE_CGM_LINK_LOST accepted lc=$lifeCount status=${json.optInt("status", -1)}" }
                true
            }
            if (accepted) checkRecovery()
        }
    }

    @Synchronized fun retryIfNeeded() {
        val critical = batteryPercent <= RecoveryPolicy.CRITICAL_BATTERY_PERCENT && !charging
        val returnToPhone = phonePriorityServiceRequired && !prefs.getBoolean("released", false)
        if (!enabled || (preferPhone && !returnToPhone) ||
            (!returnToPhone && !critical && LibreCR.manager.failedReconnectCount < AutomaticHandoff.FAILED_RECONNECTS) ||
            requestJob?.isActive == true || SystemClock.elapsedRealtime() < nextRequestAtMs) return
        if (returnToPhone && !critical && !PhonePriorityPolicy.shouldTryPhoneReturn(
                phonePriorityServiceRequired, true, lastPhoneNearby == true,
                LibreCR.manager.state.value == ConnectionState.STREAMING &&
                    lastPacketMs?.let { SystemClock.elapsedRealtime() - it < RecoveryPolicy.SILENCE_MS } == true,
                nextRequestAtMs, SystemClock.elapsedRealtime())) return
        nextRequestAtMs = SystemClock.elapsedRealtime() + 60_000L
        requestJob = scope.launch {
            try {
                val session = LibreCR.store.loadSession() ?: return@launch
                if (!LibreCR.store.autoConnectEnabled() || !nearbyHandoffNode(context, phoneNodeId)) return@launch
                handoffRpc(context, phoneNodeId, AutomaticHandoff.REQUEST_PHONE,
                    JSONObject().put("bleAddress", session.bleAddress).put("criticalBattery", critical)
                        .put("phonePriority", returnToPhone).put("phoneReturnTrial", returnToPhone && !critical))
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                BleLog.log("receiver recovery: phone unavailable")
            }
        }
    }

    suspend fun acceptsBackupNode(nodeId: String): Boolean = nodeId == phoneNodeId

    /** A previously denied preparation can finish while the user grants Bluetooth permissions. */
    fun onAppVisible() {
        trace { "WATCH_RECOVERY_APP_VISIBLE enabled=$enabled preferPhone=$preferPhone armed=$priorityArmed " +
            "requested=${prefs.getBoolean("priorityRequested", false)} released=${prefs.getBoolean("released", false)}" }
        scope.launch { mutex.withLock {
            if (!enabled || !preferPhone || !prefs.getBoolean("priorityRequested", false) || LibreCR.store.loadSession() == null) return@withLock
            priorityArmed = true
            prefs.edit().putBoolean("priorityArmed", true).commit()
            if (!LibreCR.store.autoConnectEnabled() && !prefs.getBoolean("released", false)) park()
            val prepared = SensorForegroundService.prepareStandby(context)
            trace { "WATCH_PRIORITY_PREPARE source=activity accepted=$prepared" }
            if (!prepared) {
                priorityArmed = false
                prefs.edit().putBoolean("priorityArmed", false).commit()
            }
            updateMonitor()
            events.trySend(Unit)
        } }
    }

    fun onPeerChanged(nodeId: String) {
        trace { "WATCH_RECOVERY_PEER_EVENT selected=${nodeId == phoneNodeId} required=$phonePriorityServiceRequired" }
        if (nodeId != phoneNodeId || !phonePriorityServiceRequired) return
        remoteDeadlineMs = SystemClock.elapsedRealtime()
        ReceiverRecoveryAlarm.runAwake(context) { checkRecovery() }
        events.trySend(Unit)
    }

    fun onPhonePresenceChanged() {
        trace { "WATCH_RECOVERY_CAPABILITY_EVENT required=$phonePriorityServiceRequired" }
        onPeerChanged(phoneNodeId)
    }

    /** Boot/update/start events reconcile with a live phone before creating another BLE loop. */
    suspend fun mayStartLocalSession(): Boolean = mutex.withLock {
        if (prefs.getBoolean("released", false)) { events.trySend(Unit); return@withLock false }
        val session = LibreCR.store.loadSession() ?: return@withLock false
        val status = phoneStatus(session)
        if (!PhonePriorityPolicy.mayStartAfterReturnTrial(status?.optBoolean("active") == true,
                (status?.optLong("claimRemainingMs") ?: 0L) > 0, returnReclaimPending)) {
            park(enabled && (phonePriorityServiceRequired || status?.optBoolean("preferWatch") == true))
            LibreCR.store.setAutoConnectEnabled(false)
            false
        } else true
    }

    fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray>? {
        if (path !in listOf(PhonePriorityPolicy.ARM_WATCH, RecoveryPolicy.STATUS, RecoveryPolicy.PREPARE_WATCH,
                AutomaticHandoff.RELEASE_WATCH, AutomaticHandoff.START_WATCH)) return null
        return handoffTask(scope) {
            mutex.withLock {
                if (nodeId != phoneNodeId || !nearbyHandoffNode(context, nodeId)) return@withLock handoffReply(false)
                val json = JSONObject(String(data, Charsets.UTF_8))
                if (path == PhonePriorityPolicy.ARM_WATCH) return@withLock armPhonePriority(json)
                val session = LibreCR.store.loadSession() ?: return@withLock handoffReply(false)
                if (json.optString("bleAddress").isNotBlank() && !session.bleAddress.equals(json.optString("bleAddress"), true)) return@withLock handoffReply(false)
                val age = lastPacketMs?.let { SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE
                val healthy = LibreCR.manager.state.value == ConnectionState.STREAMING && age < RecoveryPolicy.SILENCE_MS
                val owner = !prefs.getBoolean("released", false) && LibreCR.store.autoConnectEnabled()
                // A link re-established seconds ago is a recovery in progress, not a dead watch: it is
                // still handshaking or awaiting its first reading, so it is not "healthy" yet.
                val reconnectClaimMs = if (owner) {
                    RecoveryPolicy.reconnectClaimMs(healthy, LibreCR.manager.liveLinkAgeMs(), age)
                } else 0L
                if (path == RecoveryPolicy.STATUS) {
                    val startClaimMs = if (owner && !healthy)
                        (startUntilMs - SystemClock.elapsedRealtime()).coerceIn(0, RecoveryPolicy.FIRST_PACKET_MS) else 0L
                    return@withLock handoffReply(true)
                        .put("bleAddress", session.bleAddress).put("active", healthy).put("packetAgeMs", age)
                        .put("lastLifeCount", lastLifeCount)
                        .put("canStart", RecoveryPolicy.canStartWatch(batteryPercent, charging))
                        .put("batteryPercent", batteryPercent)
                        .put("claimRemainingMs", maxOf(startClaimMs, reconnectClaimMs))
                }
                val token = json.getString("token")
                val sequence = json.getLong("sequence")
                val controller = json.getString("controller")
                val sameCommand = controller == prefs.getString("controller", "") && sequence == prefs.getLong("sequence", 0)
                if (sameCommand && token != prefs.getString("token", "")) return@withLock handoffReply(false)
                if (!RecoveryPolicy.acceptsControl(prefs.getString("controller", "").orEmpty(),
                        prefs.getLong("sequence", 0), controller, sequence)) return@withLock handoffReply(false)
                when (path) {
                    RecoveryPolicy.PREPARE_WATCH -> {
                        if (!RecoveryPolicy.canStartWatch(batteryPercent, charging)) return@withLock handoffReply(false)
                        if (!RecoveryPolicy.acceptsPreparation(sameCommand, prefs.getString("phase", "").orEmpty())) return@withLock handoffReply(false)
                        if (sameCommand) return@withLock handoffReply(RecoveryPolicy.preparedStartValid(
                            prefs.getString("phase", "").orEmpty(), preparedUntilMs, SystemClock.elapsedRealtime()))
                        recordCommand(token, sequence, controller)
                        preparedUntilMs = SystemClock.elapsedRealtime() + RecoveryPolicy.FIRST_PACKET_MS
                        prefs.edit().putString("phase", "prepared").putLong("prepareUntilWall", System.currentTimeMillis() + RecoveryPolicy.FIRST_PACKET_MS).commit()
                        handoffReply(true)
                    }
                    AutomaticHandoff.RELEASE_WATCH -> {
                        val trial = json.optBoolean("phoneReturnTrial") && phonePriorityServiceRequired
                        val leaseRemaining = PhonePriorityPolicy.receivedReturnLeaseMs(
                            json.optLong("returnLeaseUntilWall"), System.currentTimeMillis())
                        if (trial && !sameCommand && leaseRemaining == null) {
                            trace { "WATCH_PHONE_RETURN_TRIAL rejected reason=invalid_lease" }
                            return@withLock handoffReply(false)
                        }
                        val cancellingUnconfirmedStart = prefs.getString("token", "") == token &&
                            !prefs.getBoolean("released", false) && !healthy
                        val critical = batteryPercent <= RecoveryPolicy.CRITICAL_BATTERY_PERCENT && !charging
                        // Releasing a link that has just come back would cost the reading it is about to
                        // deliver plus a full ownership round trip; the phone re-checks when the claim ends.
                        if ((healthy || reconnectClaimMs > 0) && !json.optBoolean("manual") && !critical &&
                            !cancellingUnconfirmedStart
                        ) return@withLock handoffReply(false)
                        recordCommand(token, sequence, controller)
                        prefs.edit().putString("phase", "released").commit()
                        if (!json.optBoolean("phonePriority")) {
                            priorityArmed = false
                            prefs.edit().putBoolean("priorityArmed", false).putBoolean("priorityRequested", false).commit()
                        }
                        if (trial && !sameCommand) {
                            val leaseMs = checkNotNull(leaseRemaining)
                            returnLeaseUntilMs = SystemClock.elapsedRealtime() + leaseMs
                            returnBoundaryLifeCount = lastLifeCount
                            setReturnReclaimPending(false)
                            phoneHealthySinceMs = null
                            lastRemotePacketMs = null
                            prefs.edit().putLong("returnLeaseUntilWall", System.currentTimeMillis() + leaseMs)
                                .putInt("returnBoundaryLifeCount", returnBoundaryLifeCount).commit()
                            scheduleReturnRetry()
                            trace { "WATCH_PHONE_RETURN_TRIAL leaseMs=$leaseMs boundary=$returnBoundaryLifeCount" }
                        }
                        park(json.optBoolean("allowRecovery", true), preserveReturnTrial = trial)
                        LibreCR.store.setAutoConnectEnabled(false)
                        val stopped = LibreCR.manager.stopAndJoin()
                        if (trial) {
                            returnBoundaryLifeCount = maxOf(returnBoundaryLifeCount, lastLifeCount)
                            prefs.edit().putInt("returnBoundaryLifeCount", returnBoundaryLifeCount).commit()
                        }
                        SensorForegroundService.stop(context)
                        val key = LibreCR.store.loadCachedPhase5RawKey()
                        handoffReply(stopped).put("lastLifeCount", lastLifeCount)
                            .put("session", session.copy(phase5RawKey = key).toJson())
                    }
                    else -> {
                        if (prefs.getString("token", "") != token) return@withLock handoffReply(false)
                        if (prefs.getString("startedToken", "") == token && LibreCR.store.autoConnectEnabled()) {
                            return@withLock handoffReply(true).put("confirmed", healthy)
                        }
                        if (!RecoveryPolicy.preparedStartValid(prefs.getString("phase", "").orEmpty(), preparedUntilMs, SystemClock.elapsedRealtime()) ||
                            !RecoveryPolicy.canStartWatch(batteryPercent, charging)) return@withLock handoffReply(false)
                        if (!LibreCR.manager.stopAndJoin()) return@withLock handoffReply(false)
                        json.optString("session").takeIf { it.isNotBlank() }?.let { ReceiverSessionBackup.acceptReleasedSession(it) }
                        if (json.optBoolean("phoneReturnRollback")) {
                            clearReturnLease()
                            setReturnReclaimPending(true)
                            phoneHealthySinceMs = null
                            lastRemotePacketMs = null
                            trace { "WATCH_PHONE_RETURN_ROLLBACK backoffMs=${(nextRequestAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)}" }
                        }
                        LibreCR.store.setAutoConnectEnabled(true)
                        startUntilMs = SystemClock.elapsedRealtime() + RecoveryPolicy.FIRST_PACKET_MS
                        prefs.edit().putBoolean("released", false).putString("startedToken", token).putString("phase", "started")
                            .putLong("startDeadlineWall", System.currentTimeMillis() + RecoveryPolicy.FIRST_PACKET_MS).commit()
                        val started = SensorForegroundService.start(context, allowCandidateFirstPair = true)
                        if (!started) {
                            prefs.edit().putString("phase", "prepared").commit()
                            park()
                            LibreCR.store.setAutoConnectEnabled(false)
                        }
                        updateMonitor()
                        events.trySend(Unit)
                        handoffReply(started).put("confirmed", false)
                    }
                }
            }
        }
    }

    private fun recordCommand(token: String, sequence: Long, controller: String) {
        check(prefs.edit().putString("token", token).putLong("sequence", sequence).putString("controller", controller).commit())
    }

    /** Provision and confirm the persistent standby service before the phone relies on this watch. */
    private suspend fun armPhonePriority(json: JSONObject): JSONObject {
        trace { "WATCH_PRIORITY_ARM enabled=$enabled preferPhone=$preferPhone armed=$priorityArmed " +
            "released=${prefs.getBoolean("released", false)}" }
        if (!enabled || !preferPhone) return handoffReply(false)
        // The authenticated ARM request has already passed the direct-nearby check.
        observePhonePresence(true)
        val incoming = ImportedSession.fromJson(json.getString("session"))
        val local = LibreCR.store.loadSession()
        if (local != null && (!local.bleAddress.equals(incoming.bleAddress, true) ||
                !local.blePin.contentEquals(incoming.blePin) || local.receiverId != incoming.receiverId)) return handoffReply(false)
        if (local == null) {
            clearLinkLossFence()
            LibreCR.store.saveSession(incoming)
            LibreCR.store.setAutoConnectEnabled(false)
        }
        val watchOwns = !prefs.getBoolean("released", false) && LibreCR.store.autoConnectEnabled()
        if (!watchOwns) ReceiverSessionBackup.acceptReleasedSession(incoming.toJson())
        priorityArmed = true
        prefs.edit().putBoolean("priorityArmed", true).putBoolean("priorityRequested", true).commit()
        if (!watchOwns) park(preserveReturnTrial = returnLeaseUntilMs > 0)
        val prepared = SensorForegroundService.prepareStandby(context)
        trace { "WATCH_PRIORITY_PREPARE source=phone accepted=$prepared watchOwns=$watchOwns" }
        if (!prepared) {
            priorityArmed = false
            prefs.edit().putBoolean("priorityArmed", false).commit()
            updateMonitor()
            return handoffReply(false)
        }
        remoteDeadlineMs = SystemClock.elapsedRealtime() + PhonePriorityPolicy.PRESENCE_CHECK_MS
        updateMonitor()
        events.trySend(Unit)
        return handoffReply(true).put("watchOwnsSensor", watchOwns)
    }

    private fun park(allowRecovery: Boolean = true, preserveReturnTrial: Boolean = false) {
        if (!preserveReturnTrial) {
            clearReturnLease()
            setReturnReclaimPending(false)
        }
        val delay = if (phonePriorityServiceRequired) PhonePriorityPolicy.PRESENCE_CHECK_MS else RecoveryPolicy.SILENCE_MS
        remoteDeadlineMs = SystemClock.elapsedRealtime() + delay
        prefs.edit().putBoolean("released", true).putBoolean("allowRecovery", allowRecovery)
            .putLong("releaseDeadlineWall", System.currentTimeMillis() + delay).commit()
        updateMonitor()
        events.trySend(Unit)
    }
    private fun updateMonitor() {
        if (phonePriorityServiceRequired) SensorForegroundService.ensureStandby(context)
        ReceiverRecoveryService.ensure(context, monitoringRequired && !phonePriorityServiceRequired)
        ReceiverRecoveryAlarm.schedule(context, if (monitoringRequired) (remoteDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(1) else null)
    }
    private suspend fun phoneStatus(session: ImportedSession, allowRemote: Boolean = false): JSONObject? = try {
        if (phoneNodeId.isBlank() || (!allowRemote && !nearbyHandoffNode(context, phoneNodeId))) null else handoffRpc(
            context, phoneNodeId, RecoveryPolicy.STATUS, JSONObject().put("bleAddress", session.bleAddress))
    } catch (error: Exception) {
        if (error is CancellationException && error !is TimeoutCancellationException) throw error
        null
    }
    suspend fun cancel() {
        mutex.withLock {
            trace { "WATCH_RECOVERY_CANCEL armed=$priorityArmed released=${prefs.getBoolean("released", false)}" }
            lastRemoteLifeCount = -1
            lastRemotePacketMs = null
            clearLinkLossFence()
            requestJob?.cancel()
            priorityArmed = false
            clearReturnLease()
            setReturnReclaimPending(false)
            resetReturnBackoff()
            prefs.edit().remove("token").remove("startedToken").remove("prepareUntilWall")
                .putBoolean("priorityArmed", false).putBoolean("priorityRequested", false).putBoolean("released", false)
                .putLong("sequence", prefs.getLong("sequence", 0) + 1).apply()
            updateMonitor()
            events.trySend(Unit)
        }
    }

    private inline fun trace(message: () -> String) {
        if (BleLog.isEnabled()) BleLog.log(message())
    }

    /** Capability/peer callbacks can repeat while the phone never left; they cannot clear backoff. */
    private fun observePhonePresence(nearby: Boolean) {
        val previous = lastPhoneNearby
        if (previous == nearby) return
        val returned = PhonePriorityPolicy.hasReturned(previous, nearby)
        lastPhoneNearby = nearby
        prefs.edit().putBoolean("lastPhoneNearby", nearby).apply()
        if (returned) {
            nextRequestAtMs = 0L
            prefs.edit().remove("returnRetryWall").apply()
        }
        trace { "WATCH_PHONE_PRESENCE previous=$previous nearby=$nearby returned=$returned " +
            "returnInMs=${(nextRequestAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)}" }
    }

    private fun scheduleReturnRetry() {
        val delay = PhonePriorityPolicy.returnRetryDelay(priorityReturnFailures++)
        nextRequestAtMs = SystemClock.elapsedRealtime() + delay
        prefs.edit().putInt("priorityReturnFailures", priorityReturnFailures)
            .putLong("returnRetryWall", System.currentTimeMillis() + delay).commit()
        trace { "WATCH_PHONE_RETURN_RETRY failures=$priorityReturnFailures delayMs=$delay" }
    }

    private fun resetReturnBackoff() {
        priorityReturnFailures = 0
        nextRequestAtMs = 0L
        prefs.edit().remove("returnRetryWall").remove("priorityReturnFailures").apply()
    }

    private fun clearReturnLease() {
        returnLeaseUntilMs = 0L
        prefs.edit().remove("returnLeaseUntilWall").apply()
    }

    private fun setReturnReclaimPending(pending: Boolean) {
        returnReclaimPending = pending
        prefs.edit().putBoolean("returnReclaimPending", pending).commit()
    }

    private fun clearLinkLossFence() {
        lastLinkLossLifeCount = -1
        lastLinkLossAtMs = 0
        returnBoundaryLifeCount = -1
        freshPhoneStatusRequired = false
        prefs.edit().remove("lastLinkLossLifeCount").remove("lastLinkLossAtMs")
            .remove("returnBoundaryLifeCount").apply()
    }
}
