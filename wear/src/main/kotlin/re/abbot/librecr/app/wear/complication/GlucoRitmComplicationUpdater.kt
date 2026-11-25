package re.abbot.librecr.app.wear.complication

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import re.abbot.librecr.app.log.BleLog

object GlucoRitmComplicationUpdater {
    private val glucoseServices = listOf(
        AgeDeltaComplicationService::class.java,
        TrendAboveValueComplicationService::class.java,
    )
    private val services = glucoseServices + listOf(
        DateComplicationService::class.java,
        WatchBatteryComplicationService::class.java,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lockGuard = Any()
    private val completion = ComplicationUpdateCompletion<ComponentName>()
    private val inactiveSources = InactiveComplicationCache<ComponentName>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var updateWakeLock: PowerManager.WakeLock? = null
    private var acquiredAtMs = 0L

    // Keep the CPU awake through the async host round trip, including with no phone in Doze.
    // The timeout is a safety bound when the host does not respond, not the normal release path.
    private const val UPDATE_WAKE_LOCK_TIMEOUT_MS = 3_000L
    // Allow the Wear SDK's asynchronous submit/render handoff after data has been returned.
    private const val HANDOFF_GRACE_MS = 250L
    private const val ACTIVE_DISCOVERY_TIMEOUT_MS = 750L

    // Preserve the stagger: simultaneous requests can lose complication images in ambient mode.
    private const val REQUEST_STAGGER_MS = 50L

    fun requestAll(context: Context): Job = request(context, services, null)

    /** Every new reading refreshes active glucose sources; confirmed inactive sources need no work. */
    fun requestGlucose(context: Context, lifeCount: Int? = null): Job =
        request(context, glucoseServices, lifeCount)

    private fun request(context: Context, sources: List<Class<*>>, lifeCount: Int?): Job {
        val app = context.applicationContext
        val components = sources.map { ComponentName(app, it) }
        // Acquire synchronously while the BLE/Data Layer/alarm caller's wake source is alive.
        val (token, queryVersion) = synchronized(lockGuard) {
            // No polling, coroutine or wakelock for sources the system confirmed are inactive.
            // This result lives only in this process and is cleared by every lifecycle/request.
            if (inactiveSources.canSkip(components)) return Job().apply { complete() }
            val generation = completion.begin(components.associateWith {
                ActiveComplicationRegistry.instanceIdsFor(it).toSet()
            })
            acquireUpdateWakeLock(app)
            generation to inactiveSources.version
        }
        return scope.launch {
            var handoffRequired = false
            try {
                // Query the system on Wear OS 6+: instance ids change with the active watch face.
                // Inactive sources must not force a three-second wait for nonexistent responses.
                val active = discoverActive(app)
                if (active != null) synchronized(lockGuard) {
                    inactiveSources.record(queryVersion, components, active.keys)
                }
                val targets = components.mapNotNull { component ->
                    val ids = active?.get(component)
                    if (active != null && ids.isNullOrEmpty()) null
                    else component to (ids ?: ActiveComplicationRegistry.instanceIdsFor(component).toSet())
                }.toMap()
                synchronized(lockGuard) { completion.setTargets(token, targets) }
                targets.entries.forEachIndexed { index, (component, ids) ->
                    if (index > 0) delay(REQUEST_STAGGER_MS)
                    if (request(app, component, ids.toIntArray(), lifeCount)) {
                        handoffRequired = true
                    } else {
                        synchronized(lockGuard) { completion.requestFailed(token, component) }
                    }
                }
            } finally {
                synchronized(lockGuard) {
                    completion.finishDispatch(token)
                    releaseWhenComplete(token, handoffRequired)
                }
            }
        }
    }

    private suspend fun discoverActive(context: Context): Map<ComponentName, Set<Int>>? {
        if (Build.VERSION.SDK_INT < 36) return null
        return try {
            withTimeoutOrNull(ACTIVE_DISCOVERY_TIMEOUT_MS) {
                WearSdkActiveComplications.find(context, Dispatchers.Default.asExecutor())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            BleLog.log("complication: active discovery failed; requesting all: ${error.message}")
            null
        }
    }

    private fun request(context: Context, component: ComponentName, ids: IntArray, lifeCount: Int?): Boolean =
        runCatching {
            val requester = ComplicationDataSourceUpdateRequester.create(context, component)
            if (ids.isEmpty()) requester.requestUpdateAll() else requester.requestUpdate(*ids)
            val route = if (Build.VERSION.SDK_INT >= 36) "WEAR_SDK" else "LEGACY_BROADCAST"
            BleLog.log(
                "COMPLICATION_UPDATE_REQUESTED lc=${lifeCount ?: -1} " +
                    "service=${component.shortClassName} route=$route " +
                    "ids=${if (ids.isEmpty()) "all" else ids.joinToString(",")}",
            )
        }.onFailure {
            BleLog.log("complication: update request failed for ${component.shortClassName}: ${it.message ?: it::class.java.simpleName}")
        }.isSuccess

    internal fun onRequestStarted(component: ComponentName, instanceId: Int): Long? =
        synchronized(lockGuard) { completion.requestStarted(component, instanceId) }

    internal fun onConfigurationChanged() {
        synchronized(lockGuard) { inactiveSources.configurationChanged() }
    }

    internal fun onDataReady(token: Long?, component: ComponentName, instanceId: Int) {
        if (token == null) return
        // The AndroidX suspend wrapper delivers to its listener synchronously after our function
        // returns. This main-loop post runs after that listener has received the data.
        mainHandler.post {
            synchronized(lockGuard) {
                completion.responseDelivered(token, component, instanceId)
                releaseWhenComplete(token)
            }
        }
    }

    private fun releaseWhenComplete(token: Long, handoffRequired: Boolean = true) {
        if (!completion.canRelease(token)) return
        if (!handoffRequired) {
            // With no dispatched data there is no asynchronous handoff to protect.
            releaseWakeLock(token)
            return
        }
        mainHandler.postDelayed({
            synchronized(lockGuard) {
                releaseWakeLock(token)
            }
        }, HANDOFF_GRACE_MS)
    }

    private fun releaseWakeLock(token: Long) {
        if (completion.canRelease(token) && updateWakeLock?.isHeld == true) {
            updateWakeLock?.release()
            BleLog.log("COMPLICATION_WAKE_RELEASE elapsedMs=${SystemClock.elapsedRealtime() - acquiredAtMs}")
        }
    }

    private fun acquireUpdateWakeLock(context: Context) {
        runCatching {
            val lock = updateWakeLock ?: run {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    ?: return
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlucoRitm:ComplicationUpdate")
                    .apply { setReferenceCounted(false) }
                    .also { updateWakeLock = it }
            }
            acquiredAtMs = SystemClock.elapsedRealtime()
            // Re-acquiring extends the safety timeout; old callbacks cannot release a newer burst.
            lock.acquire(UPDATE_WAKE_LOCK_TIMEOUT_MS)
        }.onFailure {
            BleLog.log("complication: update wake lock unavailable: ${it.message ?: it::class.java.simpleName}")
        }
    }
}
