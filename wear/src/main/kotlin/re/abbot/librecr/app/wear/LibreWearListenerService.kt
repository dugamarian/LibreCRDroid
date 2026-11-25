package re.abbot.librecr.app.wear

import com.google.android.gms.tasks.Task
import re.abbot.librecr.protocol.connection.AutomaticHandoff
import re.abbot.librecr.protocol.connection.RecoveryPolicy
import re.abbot.librecr.protocol.connection.PhonePriorityPolicy
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.alarm.SensorAttentionNotifier
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.log.DiagnosticLogSettings
import re.abbot.librecr.app.log.GlucoseLatencyTracer
import re.abbot.librecr.app.service.SensorForegroundService
import re.abbot.librecr.app.wear.complication.LibreComplicationUpdater
import re.abbot.librecr.app.wear.complication.WatchWeather
import re.abbot.librecr.app.wear.complication.requestWeatherComplicationUpdates
import re.abbot.librecr.protocol.connection.WearWeather

class LibreWearListenerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        LibreCR.init(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray>? =
        AutomaticSensorHandoff.onRequest(nodeId, path, data) ?: super.onRequest(nodeId, path, data)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        WearDataSync.notePeerActivity()
        when (messageEvent.path) {
            PhonePriorityPolicy.PHONE_CGM_LINK_LOST -> AutomaticSensorHandoff.onPhoneSensorLinkLost(
                messageEvent.sourceNodeId, messageEvent.data,
            )
            RecoveryPolicy.SESSION_BACKUP -> ReceiverSessionBackup.receive(messageEvent.sourceNodeId, messageEvent.data)
            WearDataSync.PATH_GLUCOSE_UNAVAILABLE -> receiveUnavailable(messageEvent.data, true, messageEvent.sourceNodeId)
            AutomaticHandoff.SETTINGS -> AutomaticSensorHandoff.receiveSettings(messageEvent.sourceNodeId, messageEvent.data)
            WearDataSync.PATH_SESSION,
            WearDataSync.PATH_START -> receiveSession(messageEvent)
            WearDataSync.PATH_STOP -> stopLocalConnection()
            WearDataSync.PATH_STOPPED -> WearDataSync.notifyStopAck(messageEvent.data)
            WearDataSync.PATH_GLUCOSE -> receiveGlucose(messageEvent.data, "message", publishLatest = true, sourceNodeId = messageEvent.sourceNodeId)
            WearDataSync.PATH_GLUCOSE_REPLAY -> receiveGlucose(messageEvent.data, "message replay", publishLatest = false)
            WearDataSync.PATH_SENSOR_STATUS -> receiveSensorStatus(messageEvent.data, "message")
            WearDataSync.PATH_GLUCOSE_ACK -> receiveGlucoseAck(messageEvent.data)
            WearDataSync.PATH_GLUCOSE_REPLAY_REQUEST -> receiveReplayRequest(messageEvent.data)
            WearDataSync.PATH_WEAR_APPEARANCE -> receiveAppearance(messageEvent.data, "message")
            WearDataSync.PATH_LOG_REQUEST -> sendLog()
            WearDataSync.PATH_DIAGNOSTIC_LOGGING ->
                receiveDiagnosticLoggingSetting(messageEvent.data, "message")
            else -> super.onMessageReceived(messageEvent)
        }
    }

    override fun onPeerConnected(peer: Node) {
        super.onPeerConnected(peer)
        WearDataSync.onPeerConnected(this)
        AutomaticSensorHandoff.onPeerChanged(peer.id)
        AutomaticSensorHandoff.retryIfNeeded()
    }

    override fun onPeerDisconnected(peer: Node) {
        WearDataSync.onPeerDisconnected(peer.id)
        AutomaticSensorHandoff.onPeerChanged(peer.id)
        super.onPeerDisconnected(peer)
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (capabilityInfo.name == PhonePriorityPolicy.PHONE_CAPABILITY) {
            AutomaticSensorHandoff.onPhonePresenceChanged()
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            var weatherChanged = false
            for (event in dataEvents) {
                if (event.type == DataEvent.TYPE_CHANGED) {
                    val path = event.dataItem.uri.path ?: continue
                    when {
                        path == WearWeather.PATH -> {
                            if (event.dataItem.data?.let { WatchWeather.receive(this, it) } == true) weatherChanged = true
                        }
                        path == RecoveryPolicy.SESSION_BACKUP -> event.dataItem.data?.let {
                            ReceiverSessionBackup.receive(event.dataItem.uri.host.orEmpty(), it.copyOf())
                        }
                        path == WearDataSync.PATH_GLUCOSE_UNAVAILABLE -> event.dataItem.data?.let { receiveUnavailable(it, false) }
                        path == AutomaticHandoff.SETTINGS -> event.dataItem.data?.let {
                            AutomaticSensorHandoff.receiveSettings(event.dataItem.uri.host.orEmpty(), it.copyOf())
                        }
                        path == WearDataSync.PATH_SESSION || path == WearDataSync.PATH_START ->
                            event.dataItem.data?.let { receiveSession(path, it, "data item") }
                        path == WearDataSync.PATH_GLUCOSE ->
                            event.dataItem.data?.let { receiveGlucose(it, "data item", publishLatest = true) }
                        path.startsWith(WearDataSync.PATH_GLUCOSE_REPLAY_PREFIX) ->
                            event.dataItem.data?.let { receiveGlucose(it, "data item replay", publishLatest = false) }
                        path == WearDataSync.PATH_SENSOR_STATUS ->
                            event.dataItem.data?.let { receiveSensorStatus(it, "data item") }
                        path == WearDataSync.PATH_WEAR_APPEARANCE ->
                            event.dataItem.data?.let { receiveAppearance(it, "data item") }
                        path == WearDataSync.PATH_DIAGNOSTIC_LOGGING ->
                            event.dataItem.data?.let { receiveDiagnosticLoggingSetting(it, "data item") }
                    }
                }
            }
            if (weatherChanged) requestWeatherComplicationUpdates(this)
        } finally {
            dataEvents.release()
        }
    }

    private fun receiveSession(messageEvent: MessageEvent) {
        receiveSession(messageEvent.path, messageEvent.data, "message")
    }

    private fun receiveSession(path: String, payload: ByteArray, source: String) {
        scope.launch {
            runCatching {
                // Keep the incoming session as-is: the phone's handoff attaches its CURRENT Phase 5
                // key so the watch can resume via the cheap cached handshake (the watch cannot finish
                // a full first-pair derivation before the sensor's mid-handshake patience runs out).
                // saveSession stores the key in the separate cached slot and persists the session
                // itself keyless; keyless updates for the same sensor preserve any existing key.
                AutomaticSensorHandoff.cancel()
                val incoming = ImportedSession.fromJson(String(payload))
                val shouldStart = path == WearDataSync.PATH_START
                LibreCR.store.saveSession(incoming, preserveCachedKeyWhenKeyless = true)
                LibreCR.manager.clearSensorStatus()
                if (shouldStart) {
                    LibreCR.store.setAutoConnectEnabled(true)
                } else {
                    LibreCR.store.setAutoConnectEnabled(false)
                    val stopped = LibreCR.manager.stopAndJoin()
                    SensorForegroundService.stop(this@LibreWearListenerService)
                    BleLog.log("wear: provisioning save-only stopped local connection stopped=$stopped")
                }
                BleLog.log(
                    "wear: provisioning received via $source for ${incoming.bleAddress} " +
                        "start=$shouldStart relayedKey=${incoming.phase5RawKey != null}",
                )
                if (shouldStart) {
                    // Restart from a clean loop so the (possibly relayed) key in the store is what the
                    // manager loads — a still-running loop would keep its stale in-memory key.
                    LibreCR.manager.stopAndJoin()
                    SensorForegroundService.start(this@LibreWearListenerService, allowCandidateFirstPair = true)
                }
            }.onFailure {
                BleLog.log("wear: session receive failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun receiveAppearance(bytes: ByteArray, source: String) {
        scope.launch {
            runCatching {
                val settings = WearDataSync.parseAppearance(bytes)
                LibreCR.appearance.save(settings)
                LibreComplicationUpdater.requestAll(this@LibreWearListenerService)
                BleLog.log("wear: appearance received from phone via $source")
            }.onFailure {
                BleLog.log("wear: appearance receive failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun receiveDiagnosticLoggingSetting(bytes: ByteArray, source: String) {
        val enabled = WearDataSync.parseDiagnosticLoggingEnabled(bytes)
        DiagnosticLogSettings.setEnabled(this, enabled)
        BleLog.log("wear: diagnostic logging enabled=$enabled via $source")
    }

    private fun receiveGlucose(bytes: ByteArray, source: String, publishLatest: Boolean, sourceNodeId: String = "") {
        // Relay path: the watch never saw the BLE notify, so the Data Layer arrival here is
        // the equivalent anchor. (receivedAtMs inside the payload is the phone's clock and is
        // kept only for display age, not subtracted across devices.)
        val arrivalMs = System.currentTimeMillis()
        scope.launch {
            runCatching {
                val reading = WearDataSync.parseGlucose(bytes)
                val address = JSONObject(String(bytes, Charsets.UTF_8)).optString("bleAddress")
                val session = LibreCR.store.loadSession()
                if (address.isNotBlank() && session != null && !session.bleAddress.equals(address, true)) return@runCatching
                GlucoseLatencyTracer.mark(reading.lifeCount, GlucoseLatencyTracer.Stage.BLE_NOTIFY_RECEIVED, arrivalMs)
                if (publishLatest) {
                    AutomaticSensorHandoff.onRemotePacket(sourceNodeId, reading.lifeCount, reading.receivedAtMs, source == "message")
                    LibreCR.manager.acceptRemoteGlucose(reading)
                    LibreComplicationUpdater.requestGlucose(this@LibreWearListenerService, reading.lifeCount)
                }
                BleLog.log(
                    "wear: glucose received from phone via $source ${reading.mgDL} mg/dL " +
                        "lc=${reading.lifeCount} trend=${reading.trend} publishLatest=$publishLatest",
                )
            }.onFailure {
                BleLog.log("wear: glucose receive failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun receiveUnavailable(bytes: ByteArray, message: Boolean, sourceNodeId: String = "") {
        scope.launch {
            runCatching {
                val event = WearDataSync.parseGlucoseUnavailable(bytes)
                val address = JSONObject(String(bytes, Charsets.UTF_8)).optString("bleAddress")
                val session = LibreCR.store.loadSession()
                if (address.isNotBlank() && session != null && !session.bleAddress.equals(address, true)) return@runCatching
                AutomaticSensorHandoff.onRemotePacket(sourceNodeId, event.lifeCount, event.receivedAtMs, message)
                LibreCR.manager.acceptRemoteGlucoseUnavailable(event)
            }.onFailure { BleLog.log("receiver recovery: unavailable packet rejected") }
        }
    }

    private fun receiveSensorStatus(bytes: ByteArray, source: String) {
        scope.launch {
            runCatching {
                val status = WearDataSync.parseSensorStatus(bytes)
                // Publish in memory and queue persistence before notifying/repainting; none of those
                // live paths waits for DataStore.
                LibreCR.manager.acceptRemoteSensorStatus(status)
                SensorAttentionNotifier.onAttentionChanged(this@LibreWearListenerService, status.attention)
                LibreComplicationUpdater.requestGlucose(this@LibreWearListenerService)
                BleLog.log(
                    "wear: sensor status received via $source errorData=${status.errorData} " +
                        "patchState=${status.patchState} attention=${status.attention}",
                )
            }.onFailure {
                BleLog.log("wear: sensor status receive failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun receiveGlucoseAck(bytes: ByteArray) {
        runCatching { WearDataSync.parseLifeCount(bytes) }
            .onSuccess { WearDataSync.notifyGlucoseAck(it) }
            .onFailure { BleLog.log("wear: glucose ACK parse failed: ${it.message ?: it::class.java.simpleName}") }
    }

    private fun sendLog() {
        scope.launch {
            runCatching { WearDataSync.sendLog(this@LibreWearListenerService) }
                .onFailure { BleLog.log("wear: send log failed: ${it.message ?: it::class.java.simpleName}") }
        }
    }

    private fun receiveReplayRequest(bytes: ByteArray) {
        runCatching { WearDataSync.parseReplayRequest(bytes) }
            .onSuccess { WearDataSync.replayGlucoseRange(this, it) }
            .onFailure { BleLog.log("wear: replay request parse failed: ${it.message ?: it::class.java.simpleName}") }
    }

    private fun stopLocalConnection() {
        scope.launch {
            AutomaticSensorHandoff.cancel()
            val wasActive = LibreCR.manager.state.value.isActiveStopTarget()
            LibreCR.store.setAutoConnectEnabled(false)
            val stopped = LibreCR.manager.stopAndJoin()
            SensorForegroundService.stop(this@LibreWearListenerService)
            WearDataSync.sendStopped(this@LibreWearListenerService, wasActive)
            BleLog.log("wear: local sensor connection stopped by peer stopped=$stopped active=$wasActive")
        }
    }

    private fun ConnectionState.isActiveStopTarget(): Boolean =
        this != ConnectionState.IDLE && this != ConnectionState.ERROR
}
