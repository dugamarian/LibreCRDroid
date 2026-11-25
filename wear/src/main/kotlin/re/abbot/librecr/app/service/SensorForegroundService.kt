package re.abbot.librecr.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import re.abbot.librecr.app.wear.AutomaticSensorHandoff
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.live.WearLiveUpdatesNotifier
import re.abbot.librecr.app.log.BleLog

/**
 * Foreground service (type connectedDevice) that keeps the connection loop alive
 * in the background and surfaces live glucose in its notification — the Android
 * analogue of the iOS background-BLE strategy.
 */
class SensorForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LibreCR.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            WearLiveUpdatesNotifier.startForeground(this)
        } catch (error: RuntimeException) {
            BleLog.log("service: foreground unavailable: ${error.javaClass.simpleName}")
            startRequested = false
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        foregroundReady.value = true
        startRequested = false
        val allowCandidateFirstPair = intent?.getBooleanExtra(EXTRA_ALLOW_CANDIDATE_FIRST_PAIR, false) == true
        if (intent?.getBooleanExtra(EXTRA_STANDBY, false) == true) {
            if (!AutomaticSensorHandoff.phonePriorityServiceRequired) stopSelf()
            else startCollection(allowCandidateFirstPair = true, passiveConnect = false)
        } else startCollection(allowCandidateFirstPair, intent?.getBooleanExtra(EXTRA_PASSIVE_CONNECT, false) == true)
        return START_STICKY
    }

    private fun startCollection(allowCandidateFirstPair: Boolean, passiveConnect: Boolean) {
        scope.launch {
            val session = LibreCR.store.loadSession()
            if (session == null || !LibreCR.store.autoConnectEnabled()) {
                BleLog.log("service: no session or receiver disabled; stopping")
                if (!AutomaticSensorHandoff.phonePriorityServiceRequired) stopSelf()
                return@launch
            }
            if (!AutomaticSensorHandoff.mayStartLocalSession()) {
                if (!AutomaticSensorHandoff.phonePriorityServiceRequired) stopSelf()
                return@launch
            }
            BleLog.log(
                "service: starting sensor manager with independent local handshake " +
                    "allowCandidateFirstPair=$allowCandidateFirstPair legacyKeyIgnored=${session.phase5RawKey != null}"
            )
            LibreCR.manager.start(
                scope,
                session.withoutTransientCrypto(),
                allowCandidateFirstPair = allowCandidateFirstPair,
                passiveConnect = passiveConnect,
            )
        }
    }

    override fun onDestroy() {
        instance = null
        foregroundReady.value = false
        startRequested = false
        LibreCR.manager.stop()
        scope.cancel()
        WearLiveUpdatesNotifier.stopForeground(this)
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: SensorForegroundService? = null
        @Volatile private var startRequested = false
        private val foregroundReady = MutableStateFlow(false)
        private const val EXTRA_STANDBY = "librecr.STANDBY"
        private const val EXTRA_PASSIVE_CONNECT = "librecr.PASSIVE_CONNECT"
        private const val EXTRA_ALLOW_CANDIDATE_FIRST_PAIR =
            "re.abbot.librecr.app.extra.ALLOW_CANDIDATE_FIRST_PAIR"

        fun start(context: Context, allowCandidateFirstPair: Boolean = false, passiveConnect: Boolean = false): Boolean =
            startOrStandby(context, allowCandidateFirstPair, passiveConnect, standby = false)

        fun ensureStandby(context: Context): Boolean = startOrStandby(context, false, false, standby = true)

        suspend fun prepareStandby(context: Context): Boolean = ensureStandby(context) &&
            withTimeoutOrNull(5_000L) { foregroundReady.first { it } } == true

        @Synchronized private fun startOrStandby(context: Context, allowCandidateFirstPair: Boolean, passiveConnect: Boolean, standby: Boolean): Boolean {
            instance?.let {
                // Takeover uses the already foreground service, so no background FGS launch is needed.
                if (!standby) it.startCollection(allowCandidateFirstPair, passiveConnect)
                return true
            }
            if (standby && startRequested) return true
            val intent = Intent(context, SensorForegroundService::class.java)
                .putExtra(EXTRA_ALLOW_CANDIDATE_FIRST_PAIR, allowCandidateFirstPair)
                .putExtra(EXTRA_PASSIVE_CONNECT, passiveConnect)
                .putExtra(EXTRA_STANDBY, standby)
            startRequested = true
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure {
                startRequested = false
                BleLog.log("service: startForegroundService failed: ${it.message ?: it::class.java.simpleName}")
            }.isSuccess
        }

        @Synchronized fun stop(context: Context) {
            if (AutomaticSensorHandoff.phonePriorityServiceRequired) return
            instance = null
            foregroundReady.value = false
            startRequested = false
            context.stopService(Intent(context, SensorForegroundService::class.java))
        }
    }
}
