package re.abbot.librecr.app.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearDataSync.PATH_SESSION,
            WearDataSync.PATH_START -> receiveSession(messageEvent)
            WearDataSync.PATH_STOP -> stopLocalConnection()
            WearDataSync.PATH_STOPPED -> WearDataSync.notifyStopAck(messageEvent.data)
            WearDataSync.PATH_GLUCOSE -> receiveGlucose(messageEvent.data, "message", publishLatest = true)
            WearDataSync.PATH_GLUCOSE_UNAVAILABLE -> receiveGlucoseUnavailable(messageEvent.data, "message")
            WearDataSync.PATH_GLUCOSE_REPLAY -> receiveGlucose(messageEvent.data, "message replay", publishLatest = false)
            WearDataSync.PATH_SENSOR_STATUS -> receiveSensorStatus(messageEvent.data, "message")
            WearDataSync.PATH_LOG -> ingestWatchLog(messageEvent.data)
            WearDataSync.PATH_WEAR_APPEARANCE_REQUEST -> sendAppearance()
            else -> super.onMessageReceived(messageEvent)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val path = event.dataItem.uri.path ?: continue
                if (path == WearDataSync.PATH_GLUCOSE) {
                    event.dataItem.data?.let { receiveGlucose(it, "data item", publishLatest = true) }
                } else if (path == WearDataSync.PATH_GLUCOSE_UNAVAILABLE) {
                    event.dataItem.data?.let { receiveGlucoseUnavailable(it, "data item") }
                } else if (path.startsWith(WearDataSync.PATH_GLUCOSE_REPLAY_PREFIX)) {
                    event.dataItem.data?.let { receiveGlucose(it, "data item replay", publishLatest = false) }
                } else if (path == WearDataSync.PATH_SENSOR_STATUS) {
                    event.dataItem.data?.let { receiveSensorStatus(it, "data item") }
                }
            }
        } finally {
            dataEvents.release()
        }
    }

    private fun receiveGlucose(bytes: ByteArray, source: String, publishLatest: Boolean) {
        // Stamp arrival on the binder thread, before any coroutine dispatch, so the cross-device
        // hop (sent→phoneReceived) isn't inflated by scheduler latency.
        val phoneReceivedTs = System.currentTimeMillis()
        scope.launch {
            runCatching {
                val reading = WearDataSync.parseGlucose(bytes)
                if (publishLatest) {
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
                if (advancedLatest) {
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
                if (advancedLatest && LibreCR.store.autoConnectEnabled()) {
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

    private fun receiveGlucoseUnavailable(bytes: ByteArray, source: String) {
        scope.launch {
            runCatching {
                val event = WearDataSync.parseGlucoseUnavailable(bytes)
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

    private fun receiveSession(messageEvent: MessageEvent) {
        scope.launch {
            runCatching {
                val session = ImportedSession.fromJson(String(messageEvent.data)).withoutTransientCrypto()
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
