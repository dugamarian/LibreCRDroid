package re.abbot.librecr.app.idrive

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import io.bimmergestalt.idriveconnectkit.android.security.SecurityAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.R
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.app.widget.GlucoseWidgetUpdater
import java.util.concurrent.Executors

internal enum class IDriveAddonStatus { WAITING, DISABLED, AUTHENTICATING, CONNECTING, CONNECTED, RESOURCES_MISSING, RETRYING }

/** Reject arbitrary remote hosts on this exported entry point; BMW's tunnel is local to the phone. */
internal data class IDriveEndpoint(val brand: String, val host: String, val port: Int, val instanceId: Int) {
    companion object {
        const val ACTION = "io.bimmergestalt.carconnection.service"

        fun fromIntent(intent: Intent?): IDriveEndpoint? {
            if (intent?.action != ACTION) return null
            val brand = intent.getStringExtra("EXTRA_BRAND")?.lowercase() ?: return null
            val host = intent.getStringExtra("EXTRA_HOST") ?: return null
            val port = intent.getIntExtra("EXTRA_PORT", -1)
            val instance = intent.getIntExtra("EXTRA_INSTANCE_ID", -1)
            if (brand !in setOf("bmw", "bmwi", "mini") || host !in setOf("127.0.0.1", "localhost", "::1") ||
                port !in 1..65535) return null
            // Instance ID is needed by AV audio sessions, not by our RHMI-only connection.
            // Some valid tunnel announcements arrive before AAIdrive knows the instance ID.
            return IDriveEndpoint(brand, host, port, instance)
        }
    }
}

/** Bound by AAIdrive: no extra foreground service, BLE connection, or cloud polling. */
class BmwIDriveService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val transport = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GlucoRitm-iDrive").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val endpoint = MutableStateFlow<IDriveEndpoint?>(null)
    private val securityReady = MutableStateFlow(false)
    private lateinit var security: SecurityAccess
    private val binder = Binder()

    override fun onCreate() {
        super.onCreate()
        GlucoRitm.init(applicationContext)
        security = SecurityAccess.getInstance(applicationContext)
        security.callback = { securityReady.value = security.isConnected() }
        scope.launch {
            combine(
                GlucoRitm.settings.settingsFlow.map { it.bmwIDriveEnabled }.distinctUntilChanged(),
                endpoint,
                securityReady,
            ) { enabled, connection, authenticated -> Triple(enabled, connection, authenticated) }
                .collectLatest { (enabled, connection, authenticated) ->
                    when {
                        !enabled -> {
                            mutableStatus.value = IDriveAddonStatus.DISABLED
                            IDriveDiagnostics.stage("DISABLED")
                        }
                        connection == null -> {
                            mutableStatus.value = IDriveAddonStatus.WAITING
                            IDriveDiagnostics.stage("WAITING_FOR_AAIDRIVE")
                        }
                        !authenticated -> {
                            mutableStatus.value = IDriveAddonStatus.AUTHENTICATING
                            IDriveDiagnostics.stage("BMW_SECURITY")
                            // Security binding is asynchronous. Its callback resumes this collector.
                            try {
                                security.connect()
                                securityReady.value = security.isConnected()
                            } catch (error: Exception) {
                                IDriveDiagnostics.failure(error)
                                mutableStatus.value = IDriveAddonStatus.RETRYING
                            }
                        }
                        else -> withContext(transport) { runConnection(connection) }
                    }
                }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        val connection = receiveConnection(intent) ?: return null
        endpoint.value = connection
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        receiveConnection(intent)?.let { endpoint.value = it }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        endpoint.value = null
        stopSelf()
        return true
    }

    override fun onRebind(intent: Intent?) {
        endpoint.value = receiveConnection(intent)
    }

    private fun receiveConnection(intent: Intent?): IDriveEndpoint? {
        IDriveDiagnostics.stage("ADDON_RECEIVED")
        return IDriveEndpoint.fromIntent(intent).also { connection ->
            if (connection == null) {
                IDriveDiagnostics.stage("INVALID_ANNOUNCEMENT")
                IDriveDiagnostics.failure(IllegalArgumentException("Missing or invalid AAIdrive connection parameters"))
            }
        }
    }

    private suspend fun runConnection(connection: IDriveEndpoint) {
        IDriveDiagnostics.stage("AAIDRIVE_ASSETS")
        val resources = try {
            AAIdriveResources(applicationContext, connection.brand)
        } catch (error: Exception) {
            mutableStatus.value = IDriveAddonStatus.RESOURCES_MISSING
            IDriveDiagnostics.failure(error)
            BleLog.log("idrive: AAIdrive resources unavailable (${error.javaClass.simpleName})")
            return
        }
        // Keep this outside the transport retry loop: later retries must not steal focus.
        var autoOpenPending = true
        var failures = 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            try {
                coroutineScope {
                    val sessionScope = this
                    val chartHistory = MutableStateFlow(emptyList<GlucoseSample>())
                    sessionScope.launch {
                        while (isActive) {
                            try {
                                chartHistory.value = GlucoRitm.history.chartSamples(System.currentTimeMillis() - IDRIVE_CHART_WINDOW_MS)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                BleLog.log("idrive: chart history refresh failed (${error.javaClass.simpleName})")
                            }
                            delay(60_000L)
                        }
                    }
                    val app = BmwIDriveCarApp(IDriveGlucoseRenderer(applicationContext), getString(R.string.idrive_car_title), dispatch = { action ->
                        // A callback failure cancels this session, closes its socket, and retries.
                        sessionScope.launch { action() }
                    }, onStage = IDriveDiagnostics::stage)
                    try {
                        mutableStatus.value = IDriveAddonStatus.CONNECTING
                        app.connect(connection, security, resources)
                        mutableStatus.value = IDriveAddonStatus.CONNECTED
                        failures = 0
                        // Read storage once a minute; the independent clock only checks freshness.
                        combine(GlucoseWidgetUpdater.inputs(), GlucoRitm.settings.settingsFlow, chartHistory,
                            GlucoRitm.store.glucoseHistoryFlow, flow {
                            while (true) {
                                emit(System.currentTimeMillis())
                                delay(1_000L)
                            }
                        }) { input, settings, history, recent, now ->
                            idriveGlucosePresentation(input, settings.unit, now, settings.targetLow, settings.targetHigh,
                                history, recent) to settings.bmwIDriveDisplayMode
                        }
                            .distinctUntilChanged().collect { (presentation, displayMode) ->
                                app.update(presentation, displayMode)
                                if (autoOpenPending) {
                                    app.open()
                                    autoOpenPending = false
                                }
                            }
                    } finally {
                        app.close()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableStatus.value = IDriveAddonStatus.RETRYING
                IDriveDiagnostics.failure(error)
                BleLog.log("idrive: connection failed (${error.javaClass.simpleName}); retrying")
                delay(listOf(2_000L, 5_000L, 10_000L, 30_000L)[failures.coerceAtMost(3)])
                failures++
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        security.callback = {}
        // SecurityAccess is process-global in the kit; release it after pending RPCs finish.
        serviceJob.invokeOnCompletion {
            security.disconnect()
            transport.close()
        }
        mutableStatus.value = IDriveAddonStatus.WAITING
        super.onDestroy()
    }

    companion object {
        private val mutableStatus = MutableStateFlow(IDriveAddonStatus.WAITING)
        internal val status = mutableStatus.asStateFlow()
    }
}
