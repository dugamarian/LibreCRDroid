package re.abbot.librecr.app.wear

import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import re.abbot.librecr.protocol.connection.RecoveryPolicy
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.alarm.SensorAttentionNotifier
import re.abbot.librecr.app.alarm.StalenessWatchdog
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.log.GlucoseTimelineTracker
import re.abbot.librecr.app.service.SensorForegroundService

class PhoneWearListenerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        LibreCR.init(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onPeerConnected(peer: Node) {
        super.onPeerConnected(peer)
        WearDataSync.enforcePersistedSelection(this)
        scope.launch {
            val settings = LibreCR.settings.current()
            WearDataSync.sendAutomaticHandoffSettings(this@PhoneWearListenerService,
                settings.automaticSensorHandoffEnabled, settings.selectedWearNodeId)
        }
        AutomaticSensorHandoff.onPeerConnected()
    }

    override fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray>? =
        AutomaticSensorHandoff.onRequest(nodeId, path, data) ?: super.onRequest(nodeId, path, data)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val sourceNodeId = messageEvent.sourceNodeId
        val path = messageEvent.path
        val data = messageEvent.data.copyOf()
        val phoneReceivedTs = System.currentTimeMillis()
        scope.launch {
            if (!WearDataSync.acceptsSourceNode(sourceNodeId)) {
                BleLog.log("wear: ignored $path from non-selected node=$sourceNodeId")
                return@launch
            }
            when (path) {
                RecoveryPolicy.SESSION_BACKUP -> ReceiverSessionBackup.receive(sourceNodeId, data)
                WearDataSync.PATH_SESSION,
                WearDataSync.PATH_START -> receiveSession(data)
                WearDataSync.PATH_STOP -> stopLocalConnection()
                WearDataSync.PATH_STOPPED -> WearDataSync.notifyStopAck(data)
                WearDataSync.PATH_GLUCOSE ->
                    receiveGlucose(
                        data,
                        "message",
                        publishLatest = true,
                        phoneReceivedTs = phoneReceivedTs,
                        sourceNodeId = sourceNodeId,
                    )
                WearDataSync.PATH_GLUCOSE_UNAVAILABLE ->
                    receiveGlucoseUnavailable(data, "message", sourceNodeId)
                WearDataSync.PATH_GLUCOSE_REPLAY ->
                    receiveGlucose(
                        data,
                        "message replay",
                        publishLatest = false,
                        phoneReceivedTs = phoneReceivedTs,
                        sourceNodeId = sourceNodeId,
                    )
                WearDataSync.PATH_SENSOR_STATUS -> receiveSensorStatus(data, "message")
                WearDataSync.PATH_LOG -> ingestWatchLog(data)
                WearDataSync.PATH_WEAR_APPEARANCE_REQUEST -> sendAppearance()
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val path = event.dataItem.uri.path ?: continue
                val sourceNodeId = event.dataItem.uri.host.orEmpty()
                val data = event.dataItem.data?.copyOf() ?: continue
                val phoneReceivedTs = System.currentTimeMillis()
                scope.launch {
                    if (!WearDataSync.acceptsSourceNode(sourceNodeId)) {
                        BleLog.log("wear: ignored data item $path from non-selected node=$sourceNodeId")
                        return@launch
                    }
                    if (path == RecoveryPolicy.SESSION_BACKUP) {
                        ReceiverSessionBackup.receive(sourceNodeId, data)
                    } else if (path == WearDataSync.PATH_GLUCOSE) {
                        receiveGlucose(
                            data,
                            "data item",
                            publishLatest = true,
                            phoneReceivedTs = phoneReceivedTs,
                            sourceNodeId = sourceNodeId,
                        )
                    } else if (path == WearDataSync.PATH_GLUCOSE_UNAVAILABLE) {
                        receiveGlucoseUnavailable(data, "data item", sourceNodeId)
                    } else if (path.startsWith(WearDataSync.PATH_GLUCOSE_REPLAY_PREFIX)) {
                        receiveGlucose(
                            data,
                            "data item replay",
                            publishLatest = false,
                            phoneReceivedTs = phoneReceivedTs,
                            sourceNodeId = sourceNodeId,
                        )
                    } else if (path == WearDataSync.PATH_SENSOR_STATUS) {
                        receiveSensorStatus(data, "data item")
                    }
                }
            }
        } finally {
            dataEvents.release()
        }
    }

    private fun receiveGlucose(
        bytes: ByteArray,
        source: String,
        publishLatest: Boolean,
        phoneReceivedTs: Long,
        sourceNodeId: String,
    ) {
        scope.launch {
            runCatching {
                val reading = WearDataSync.parseGlucose(bytes)
                val sensorAddress = JSONObject(String(bytes, Charsets.UTF_8)).optString("bleAddress")
                val session = LibreCR.store.loadSession()
                if (sensorAddress.isNotBlank() && session != null && !session.bleAddress.equals(sensorAddress, true)) {
                    WearDataSync.sendGlucoseAck(this@PhoneWearListenerService, reading.lifeCount)
                    return@runCatching
                }
                if (publishLatest) AutomaticSensorHandoff.onRemotePacket(
                    sourceNodeId, sensorAddress, reading.lifeCount, reading.receivedAtMs, source == "message",
                )
                val automaticPhoneOwner = AutomaticSensorHandoff.ownsPhoneConnection
                if (publishLatest && !automaticPhoneOwner) {
                    LibreCR.manager.acceptRemoteGlucose(reading)
                    // Any live relay (even a duplicate) proves the watch→phone pipeline is alive.
                    StalenessWatchdog.onFreshReading(this@PhoneWearListenerService)
                }
                val previous = LibreCR.store.loadLastGlucose()
                if (previous != null && reading.lifeCount > previous.lifeCount + 1) {
                    WearDataSync.requestGlucoseReplay(
                        this@PhoneWearListenerService,
                        fromLifeCount = previous.lifeCount + 1,
                        toLifeCount = reading.lifeCount - 1,
                    )
                }
                val advancesTimeline = previous == null || reading.lifeCount > previous.lifeCount
                if (advancesTimeline) {
                    GlucoseTimelineTracker.onPhoneReceived(WearDataSync.parseTimeline(bytes, phoneReceivedTs))
                }
                val advancedLatest = LibreCR.store.saveRemoteGlucose(reading)
                if (advancedLatest && !automaticPhoneOwner) {
                    LibreCR.store.setWatchTakeoverEnabled(true)
                }
                BleLog.log(
                    "PHONE_RECV lc=${reading.lifeCount} source=$source mgdl=${reading.mgDL} " +
                        "trend=${reading.trend} publishLatest=$publishLatest advancedLatest=$advancedLatest " +
                        "timeline=$advancesTimeline previous=${previous?.lifeCount ?: -1}",
                )
                WearDataSync.sendGlucoseAck(this@PhoneWearListenerService, reading.lifeCount)
                // A relayed reading proves the watch is the active receiver. The Libre 3 allows only
                // one connected receiver, so the phone yields its own BLE to avoid the two fighting
                // over the single slot (which alternates readings → every-2-minutes). Acts once;
                // an explicit "return to phone" handoff re-enables auto-connect.
                if (advancedLatest && !AutomaticSensorHandoff.ownsPhoneConnection && LibreCR.store.autoConnectEnabled()) {
                    BleLog.log("wear: watch is active receiver → phone yielding BLE")
                    LibreCR.store.setAutoConnectEnabled(false)
                    val stopped = LibreCR.manager.stopAndJoin()
                    SensorForegroundService.stop(this@PhoneWearListenerService)
                    BleLog.log("wear: phone yielded BLE stopped=$stopped")
                }
            }.onFailure {
                BleLog.log("wear: phone glucose receive failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun receiveGlucoseUnavailable(bytes: ByteArray, source: String, sourceNodeId: String) {
        scope.launch {
            runCatching {
                val event = WearDataSync.parseGlucoseUnavailable(bytes)
                val sensorAddress = JSONObject(String(bytes, Charsets.UTF_8)).optString("bleAddress")
                val session = LibreCR.store.loadSession()
                if (sensorAddress.isNotBlank() && session != null && !session.bleAddress.equals(sensorAddress, true)) return@runCatching
                AutomaticSensorHandoff.onRemotePacket(sourceNodeId, sensorAddress, event.lifeCount, event.receivedAtMs, source == "message")
                if (AutomaticSensorHandoff.ownsPhoneConnection) return@runCatching
                LibreCR.manager.acceptRemoteGlucoseUnavailable(event)
                // An unusable reading is still a liveness proof — sensor-error states must not
                // double-alert as staleness (SensorAttentionNotifier owns real sensor errors).
                StalenessWatchdog.onFreshReading(this@PhoneWearListenerService)
                BleLog.log(
                    "PHONE_RECV_UNAVAILABLE lc=${event.lifeCount} source=$source " +
                        "reason=${event.reason} ${event.detail}",
                )
            }.onFailure {
                BleLog.log("wear: phone glucose unavailable receive failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun ingestWatchLog(bytes: ByteArray) {
        scope.launch {
            runCatching {
                val lines = WearDataSync.parseLog(bytes)
                BleLog.ingestRemote(lines, source = "WATCH")
                BleLog.log("wear: ingested watch log (${lines.size} lines) into phone log viewer")
            }.onFailure {
                BleLog.log("wear: watch log ingest failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun receiveSensorStatus(bytes: ByteArray, source: String) {
        scope.launch {
            runCatching {
                val status = WearDataSync.parseSensorStatus(bytes)
                LibreCR.store.saveSensorStatus(status.errorData, status.patchState, status.observedAtMs)
                SensorAttentionNotifier.onAttentionChanged(this@PhoneWearListenerService, status.attention)
                BleLog.log(
                    "wear: phone saved sensor status via $source errorData=${status.errorData} " +
                        "patchState=${status.patchState} attention=${status.attention}",
                )
            }.onFailure {
                BleLog.log("wear: phone sensor status receive failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun receiveSession(bytes: ByteArray) {
        scope.launch {
            runCatching {
                val session = ImportedSession.fromJson(String(bytes)).withoutTransientCrypto()
                LibreCR.store.saveSession(session, preserveCachedKeyWhenKeyless = true)
                BleLog.log("wear: phone saved provisioning update from watch for ${session.bleAddress}")
            }.onFailure {
                BleLog.log("wear: phone session receive failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun sendAppearance() {
        scope.launch {
            runCatching {
                WearDataSync.sendAppearance(this@PhoneWearListenerService, LibreCR.settings.current().wearAppearance)
                BleLog.log("wear: appearance sent after watch request")
            }.onFailure {
                BleLog.log("wear: appearance request failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun stopLocalConnection() {
        scope.launch {
            AutomaticSensorHandoff.cancel(manual = true)
            val wasActive = LibreCR.manager.state.value.isActiveStopTarget()
            LibreCR.store.setAutoConnectEnabled(false)
            val stopped = LibreCR.manager.stopAndJoin()
            SensorForegroundService.stop(this@PhoneWearListenerService)
            WearDataSync.sendStopped(this@PhoneWearListenerService, wasActive)
            BleLog.log("wear: phone local sensor connection stopped by peer stopped=$stopped active=$wasActive")
        }
    }

    private fun ConnectionState.isActiveStopTarget(): Boolean =
        this != ConnectionState.IDLE && this != ConnectionState.ERROR
}
