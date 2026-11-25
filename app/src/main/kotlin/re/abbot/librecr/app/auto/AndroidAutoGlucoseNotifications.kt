package re.abbot.librecr.app.auto

import android.content.Context
import android.provider.Settings
import androidx.car.app.connection.CarConnection
import androidx.car.app.notification.CarNotificationManager
import androidx.core.content.edit
import androidx.lifecycle.Observer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.widget.GlucoseWidgetInput
import re.abbot.librecr.app.widget.GlucoseWidgetUpdater
import java.util.UUID

/** Experimental CGM use of the car messaging surface, NOT a generic persistent overlay API. */
object AndroidAutoGlucoseNotifications {
    const val CHANNEL_ID = AndroidAutoNotificationFactory.CHANNEL_ID
    internal const val ACTION_REFRESH = "re.abbot.librecr.app.auto.REFRESH"
    internal const val ACTION_DISMISS = "re.abbot.librecr.app.auto.DISMISS"
    internal const val ACTION_DISABLE = "re.abbot.librecr.app.auto.DISABLE"
    internal const val EXTRA_SESSION = "auto_notification_session"
    private const val NOTIFICATION_ID = 130
    private const val REFRESH_MS = 60_000L
    private const val SESSION_PREFS = "librecr_auto_notification_session"
    private val mutex = Mutex()
    private val connectionType = MutableStateFlow<Int?>(null)
    private val projected = MutableStateFlow(false)
    val isProjected = projected.asStateFlow()
    private val delivery = MutableStateFlow(AutoNotificationDelivery.DISABLED)
    internal val deliveryState = delivery.asStateFlow()
    private var started = false
    private var connection: CarConnection? = null
    private var session = AutoNotificationSession()
    private var maybePosted = true // Cancel a surviving notification on cold start if disabled.

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        session = loadSession(app)
        // A single process-lifetime observer is intentional: projection must be tracked while the
        // activity is backgrounded. No Activity is retained, and CarConnection is broadcast-driven.
        GlucoRitm.appScope.launch(Dispatchers.Main.immediate) {
            try {
                connection = CarConnection(app)
                connection!!.type.observeForever(Observer { type ->
                    connectionType.value = type
                    projected.value = type == CarConnection.CONNECTION_TYPE_PROJECTION
                    BleLog.log("android-auto: connection type=$type")
                })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // An unavailable car host must never interrupt the CGM collector.
                connectionType.value = CarConnection.CONNECTION_TYPE_NOT_CONNECTED
                BleLog.log("android-auto: connection query failed: ${error.javaClass.simpleName}")
            }
        }
        GlucoRitm.appScope.launch {
            GlucoRitm.settings.settingsFlow
                .map { it.androidAutoNotificationsEnabled }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    if (!enabled) {
                        mutex.withLock { clear(app) }
                        return@collectLatest
                    }
                    // UNKNOWN is deliberately not treated as a disconnect during a process restart.
                    connectionType.filterNotNull().collectLatest { type ->
                        val isAuto = type == CarConnection.CONNECTION_TYPE_PROJECTION
                        mutex.withLock {
                            session = autoNotificationSession(session, enabled, isAuto, UUID.randomUUID().toString())
                            delivery.value = when {
                                !isAuto -> AutoNotificationDelivery.WAITING_FOR_CAR
                                session.dismissed -> AutoNotificationDelivery.DISMISSED
                                else -> AutoNotificationDelivery.READY
                            }
                            saveSession(app)
                            if (!isAuto) cancel(app)
                        }
                        if (isAuto) {
                            combine(GlucoseWidgetUpdater.inputs(), clock()) { input, now -> input to now }
                                .collect { (input, now) -> publish(app, input, now) }
                        }
                    }
                }
        }
    }

    private fun clock() = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(REFRESH_MS)
        }
    }

    private suspend fun publish(context: Context, input: GlucoseWidgetInput, nowMs: Long) {
        mutex.withLock {
            // Recheck the persisted switch so a queued reading can never win a race against OFF.
            if (!GlucoRitm.settings.current().androidAutoNotificationsEnabled) {
                clear(context)
                return
            }
            if (connectionType.value != CarConnection.CONNECTION_TYPE_PROJECTION || !session.canPost) return
            try {
                AndroidAutoNotificationFactory.ensureChannel(context)
                if (!AndroidAutoNotificationFactory.notificationsAllowed(context)) {
                    delivery.value = AutoNotificationDelivery.BLOCKED
                    return
                }
                val content = autoGlucoseContent(input, maxOf(nowMs, System.currentTimeMillis()))
                CarNotificationManager.from(context).notify(NOTIFICATION_ID, AndroidAutoNotificationFactory.build(context, content, session))
                session = session.copy(posted = true)
                delivery.value = AutoNotificationDelivery.POSTED
                maybePosted = true
                saveSession(context)
            } catch (error: SecurityException) {
                delivery.value = AutoNotificationDelivery.BLOCKED
                BleLog.log("android-auto: notification permission denied")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                delivery.value = AutoNotificationDelivery.FAILED
                BleLog.log("android-auto: notification failed: ${error.javaClass.simpleName}")
            }
        }
    }

    /** Real CGM content only. Starts a new envelope so an old/read car card can be retried. */
    internal suspend fun resend(context: Context) {
        try {
            val completed = withTimeoutOrNull(8_000L) {
                val input = GlucoseWidgetUpdater.inputs().first()
                val ready = mutex.withLock {
                    if (!GlucoRitm.settings.current().androidAutoNotificationsEnabled ||
                        connectionType.value != CarConnection.CONNECTION_TYPE_PROJECTION) return@withLock false
                    cancel(context)
                    session = AutoNotificationSession(token = UUID.randomUUID().toString())
                    delivery.value = AutoNotificationDelivery.READY
                    saveSession(context)
                    true
                }
                if (ready) publish(context, input, System.currentTimeMillis())
                true
            }
            if (completed == null) delivery.value = AutoNotificationDelivery.FAILED
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            delivery.value = AutoNotificationDelivery.FAILED
            BleLog.log("android-auto: resend failed: ${error.javaClass.simpleName}")
        }
    }

    internal suspend fun handleAction(context: Context, action: String, expectedToken: String) {
        // A notification action may cold-start the process; await the real projection query first.
        connectionType.filterNotNull().first()
        val allowed = mutex.withLock {
            if (connectionType.value != CarConnection.CONNECTION_TYPE_PROJECTION || session.token != expectedToken ||
                !GlucoRitm.settings.current().androidAutoNotificationsEnabled) return@withLock false
            when (action) {
                ACTION_DISMISS -> {
                    session = dismissAutoNotification(session, expectedToken)
                    delivery.value = AutoNotificationDelivery.DISMISSED
                    saveSession(context)
                    cancel(context)
                }
                ACTION_DISABLE -> {
                    GlucoRitm.settings.setAndroidAutoNotificationsEnabled(false)
                    clear(context)
                }
            }
            action == ACTION_REFRESH && session.canPost
        }
        // Refresh is local presentation only; no voice reply or glucose value is sent to anyone.
        if (allowed) publish(context, GlucoseWidgetUpdater.inputs().first(), System.currentTimeMillis())
    }

    private fun clear(context: Context) {
        session = AutoNotificationSession()
        delivery.value = AutoNotificationDelivery.DISABLED
        saveSession(context)
        cancel(context)
    }

    private fun cancel(context: Context) {
        if (!maybePosted) return
        try {
            CarNotificationManager.from(context).cancel(NOTIFICATION_ID)
            maybePosted = false
        } catch (error: Exception) {
            BleLog.log("android-auto: notification cancellation failed: ${error.javaClass.simpleName}")
        }
    }

    private fun bootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    private fun loadSession(context: Context): AutoNotificationSession {
        val prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        val boot = bootCount(context)
        if (boot < 0 || prefs.getInt("boot", -2) != boot) return AutoNotificationSession()
        return AutoNotificationSession(prefs.getString("token", null), prefs.getBoolean("dismissed", false), prefs.getBoolean("posted", false))
    }

    private fun saveSession(context: Context) {
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit {
            clear()
            putInt("boot", bootCount(context))
            putString("token", session.token)
            putBoolean("dismissed", session.dismissed)
            putBoolean("posted", session.posted)
        }
    }
}
