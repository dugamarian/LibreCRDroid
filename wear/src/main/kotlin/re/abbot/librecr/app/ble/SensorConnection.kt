package re.abbot.librecr.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import re.abbot.librecr.app.log.BleLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class GattDisconnectedException(val gattStatus: Int) : IllegalStateException("disconnected status=$gattStatus")

class GattOperationException(
    val operation: String,
    val gattStatus: Int,
) : IllegalStateException("$operation failed status=$gattStatus")

class GattOperationTimedOutException(
    operation: String,
    cause: TimeoutCancellationException,
) : IllegalStateException("$operation timed out and closed the GATT", cause)

/** Immutable, callback-safe snapshot used for the post-mortem lines in the shipped Wear log. */
internal data class SensorLinkSnapshot(
    val sessionId: Long,
    val sessionAgeMs: Long,
    val connectedForMs: Long?,
    val streamingForMs: Long?,
    val lastNotifyAgeMs: Long?,
    val lastNotifyCharacteristic: String?,
    val notificationCount: Long,
    val notificationBytes: Long,
    val notificationChannels: String,
    val rssiDbm: Int?,
    val rssiAgeMs: Long?,
    val txPhy: String?,
    val rxPhy: String?,
    val connectionIntervalMs: Double?,
    val connectionLatency: Int?,
    val supervisionTimeoutMs: Int?,
    val connectionParamsAgeMs: Long?,
    val currentOperation: String?,
    val currentOperationAgeMs: Long?,
    val lastOperation: String?,
    val lastOperationResult: String?,
    val lastOperationAgeMs: Long?,
    val lastCallback: String?,
    val lastCallbackAgeMs: Long?,
    val disconnectRequested: Boolean,
    val closeRequested: Boolean,
)

private class NotifyDiagnostics {
    val count = AtomicLong(0)
    val bytes = AtomicLong(0)
    @Volatile var lastAtElapsedMs: Long = 0L
}

/**
 * One connected GATT session against a Libre 3 sensor.
 *
 * Android allows only ONE outstanding GATT operation at a time, so every
 * write/read/descriptor-write/discovery goes through [opMutex] and awaits the
 * matching callback (with a per-op timeout). This is the Android analogue of
 * CoreBluetooth's serialized dispatch queue + pending-continuation boxes in
 * Swift `SensorSession`, and is the main guard against the connection
 * instability that comes from issuing overlapping GATT ops.
 */
@SuppressLint("MissingPermission")
class SensorConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    val connectionGenerationId: Long,
    private val isConnectionGenerationCurrent: (Long) -> Boolean,
) {
    /**
     * [linkWasEstablished] tells the loss of a link which had come up apart from a connect attempt
     * that never completed; the GATT status alone no longer does (see [isLinkSupervisionTimeout]).
     */
    @Volatile var onDisconnected: ((status: Int, linkWasEstablished: Boolean) -> Unit)? = null

    /** Runs on the GATT callback thread the instant the link comes up, before any coroutine resumes. */
    @Volatile private var onLinkUp: (() -> Unit)? = null

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    @Volatile private var connected = false
    private val characteristics = ConcurrentHashMap<UUID, BluetoothGattCharacteristic>()
    private val notifyChannels = ConcurrentHashMap<UUID, Channel<ByteArray>>()
    private val notifyDiagnostics = ConcurrentHashMap<UUID, NotifyDiagnostics>()

    private val sessionId = nextSessionId.getAndIncrement()
    private val createdAtElapsedMs = SystemClock.elapsedRealtime()
    @Volatile private var connectedAtElapsedMs = 0L
    @Volatile private var streamingAtElapsedMs = 0L
    @Volatile private var lastNotifyAtElapsedMs = 0L
    @Volatile private var lastNotifyUuid: UUID? = null
    private val notificationCount = AtomicLong(0)
    private val notificationBytes = AtomicLong(0)
    @Volatile private var lastRssiDbm: Int? = null
    @Volatile private var lastRssiAtElapsedMs = 0L
    @Volatile private var lastTxPhy: String? = null
    @Volatile private var lastRxPhy: String? = null
    @Volatile private var lastConnectionIntervalMs: Double? = null
    @Volatile private var lastConnectionLatency: Int? = null
    @Volatile private var lastSupervisionTimeoutMs: Int? = null
    @Volatile private var lastConnectionParamsAtElapsedMs = 0L
    @Volatile private var currentOperation: String? = null
    @Volatile private var currentOperationAtElapsedMs = 0L
    @Volatile private var lastOperation: String? = null
    @Volatile private var lastOperationResult: String? = null
    @Volatile private var lastOperationAtElapsedMs = 0L
    @Volatile private var lastCallback: String? = null
    @Volatile private var lastCallbackAtElapsedMs = 0L
    @Volatile private var disconnectRequestedAtElapsedMs = 0L
    @Volatile private var closeRequestedAtElapsedMs = 0L

    private val opMutex = Mutex()
    @Volatile private var pendingConnect: CompletableDeferred<Unit>? = null
    @Volatile private var pendingServices: CompletableDeferred<Unit>? = null
    @Volatile private var pendingWrite: CompletableDeferred<Unit>? = null
    @Volatile private var pendingRead: CompletableDeferred<ByteArray>? = null
    @Volatile private var pendingDescriptor: CompletableDeferred<Unit>? = null
    @Volatile private var pendingRssi: CompletableDeferred<Int>? = null

    val isConnected: Boolean get() = connected && gatt != null && !closed
    val deviceAddress: String get() = device.address

    /** Milliseconds since this GATT reached STATE_CONNECTED, or null while no link is up. */
    fun linkAgeMs(): Long? =
        if (isConnected && connectedAtElapsedMs > 0L) {
            (SystemClock.elapsedRealtime() - connectedAtElapsedMs).coerceAtLeast(0L)
        } else {
            null
        }

    fun notifyChannel(uuid: UUID): Channel<ByteArray> =
        notifyChannels.getOrPut(uuid) { Channel(Channel.UNLIMITED) }

    /**
     * Connect, discover services, and enable only the security-handshake
     * notifications. Data-plane notifications are enabled once after Phase 6.
     */
    suspend fun connectAndDiscover(
        connectTimeoutMs: Long?,
        discoverTimeoutMs: Long,
        autoConnect: Boolean = false,
        onLinkUp: (() -> Unit)? = null,
    ) {
        this.onLinkUp = onLinkUp
        connect(connectTimeoutMs, autoConnect)

        // NO connection-priority requests for Libre 3. Every preset hurt: HIGH and BALANCED caused
        // random status=8 link drops, LOW_POWER made the multi-round-trip handshake too slow, and any
        // mid-session change renegotiated bad params (status=19). The most stable behavior is to leave
        // the default BLE connection parameters that Android and the sensor negotiate on their own.
        discoverServices(discoverTimeoutMs)

        subscribeNotifyCharacteristics(LibreSensorGatt.handshakeNotifying, "handshake")
    }

    /**
     * Fails a connect which is still waiting for the link. A Bluetooth restart forgets every GATT
     * client without a callback, so a controller connect issued before it would wait forever.
     */
    fun abortPendingConnect(reason: String): Boolean =
        pendingConnect?.completeExceptionally(IllegalStateException(reason)) == true

    private suspend fun connect(timeoutMs: Long?, autoConnect: Boolean) = opMutex.withLock {
        val operation = "connect(auto=$autoConnect)"
        beginOperation(operation)
        val def = CompletableDeferred<Unit>()
        pendingConnect = def
        closed = false
        connected = false
        BleLog.log("gatt.connect device=${device.address} (autoConnect=$autoConnect)")
        BleLog.log(
            "$WEAR_BLE_TAG SENSOR_CONNECT_GATT generation=$connectionGenerationId " +
                "device=${device.address} autoConnect=$autoConnect transport=LE",
        )
        val newGatt = runCatching {
            device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IllegalStateException("connectGatt returned null")
        }.getOrElse {
            pendingConnect = null
            closed = true
            finishOperation(operation, "start_failed:${diagnosticToken(it.message)}")
            throw it
        }
        gatt = newGatt
        try {
            if (timeoutMs == null) {
                BleLog.log(
                    "$WEAR_BLE_TAG SENSOR_AUTO_CONNECT_PERSISTENT generation=$connectionGenerationId " +
                        "device=${device.address}",
                )
                def.await()
            } else {
                withTimeout(timeoutMs) { def.await() }
            }
            finishOperation(operation, "ok")
        } catch (e: TimeoutCancellationException) {
            BleLog.log("gatt.connect timed out after ${timeoutMs}ms")
            finishOperation(operation, "timeout_${timeoutMs}ms")
            close()
            throw e
        } catch (e: Exception) {
            BleLog.log("gatt.connect failure: ${e.message}")
            finishOperation(operation, "failed:${diagnosticToken(e.message)}")
            close()
            throw e
        } finally {
            if (pendingConnect === def) pendingConnect = null
        }
        BleLog.log("gatt.connect: connected")
    }

    private suspend fun discoverServices(timeoutMs: Long) = opMutex.withLock {
        val g = gatt ?: throw IllegalStateException("not connected")
        val operation = "discoverServices"
        beginOperation(operation)
        val def = CompletableDeferred<Unit>()
        pendingServices = def
        BleLog.log("gatt.discoverServices")
        BleLog.log("$WEAR_BLE_TAG SENSOR_DISCOVER_SERVICES_START")
        if (!g.discoverServices()) {
            pendingServices = null
            BleLog.log("$WEAR_BLE_TAG SENSOR_DISCOVER_SERVICES_FAILED reason=start_not_accepted")
            finishOperation(operation, "start_not_accepted")
            throw IllegalStateException("discoverServices not accepted by Android GATT")
        }
        try {
            withTimeout(timeoutMs) { def.await() }
            finishOperation(operation, "ok")
        } catch (e: TimeoutCancellationException) {
            BleLog.log("$WEAR_BLE_TAG SENSOR_DISCOVER_SERVICES_FAILED reason=timeout_${timeoutMs}ms")
            finishOperation(operation, "timeout_${timeoutMs}ms")
            close()
            throw e
        } catch (e: Exception) {
            BleLog.log("$WEAR_BLE_TAG SENSOR_DISCOVER_SERVICES_FAILED reason=${e.message}")
            finishOperation(operation, "failed:${diagnosticToken(e.message)}")
            throw e
        } finally {
            if (pendingServices === def) pendingServices = null
        }
        BleLog.log("$WEAR_BLE_TAG SENSOR_DISCOVER_SERVICES_SUCCESS")
        characteristics.clear()
        var serviceCount = 0
        for (svc in listOf(LibreSensorGatt.SERVICE, LibreSensorGatt.SECURITY_SERVICE)) {
            val service = g.getService(svc)
            if (service == null) {
                BleLog.log("gatt.discoverServices: missing service $svc")
                continue
            }
            serviceCount += 1
            BleLog.log("gatt.discoverServices: service $svc chars=${service.characteristics.size}")
            for (c in service.characteristics) {
                characteristics[c.uuid] = c
                BleLog.log("gatt.discoverServices: char ${short(c.uuid)} props=0x${"%02x".format(c.properties)}")
            }
        }
        BleLog.log("gatt.discoverServices: services=$serviceCount characteristics=${characteristics.size}")
    }

    private suspend fun subscribeNotifyCharacteristics(uuids: List<UUID>, label: String) {
        BleLog.log("gatt.notify: subscribing ${uuids.size} $label characteristics")
        for (uuid in uuids) {
            setNotify(uuid, true)
            BleLog.log("gatt.notify: subscribed $label ${short(uuid)}")
        }
    }

    suspend fun writeCharacteristic(uuid: UUID, value: ByteArray, withResponse: Boolean = true, timeoutMs: Long = OP_TIMEOUT_MS) =
        opMutex.withLock {
            val g = gatt ?: throw IllegalStateException("not connected")
            val c = characteristics[uuid] ?: throw IllegalStateException("missing characteristic $uuid")
            val operation = "write:${short(uuid)}"
            beginOperation(operation)
            val def = CompletableDeferred<Unit>()
            pendingWrite = def
            val writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            BleLog.log("gatt.write ${short(uuid)} len=${value.size} withResponse=$withResponse")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ensureBluetoothStatus("write ${short(uuid)}", g.writeCharacteristic(c, value, writeType))
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        c.writeType = writeType
                        c.value = value
                        ensureStarted("write ${short(uuid)}", g.writeCharacteristic(c))
                    }
                }
                withTimeout(timeoutMs) { def.await() }
                finishOperation(operation, "ok")
            } catch (e: TimeoutCancellationException) {
                BleLog.log("gatt.write ${short(uuid)} timed out after ${timeoutMs}ms")
                finishOperation(operation, "timeout_${timeoutMs}ms")
                close()
                throw e
            } catch (e: Exception) {
                finishOperation(operation, "failed:${diagnosticToken(e.message)}")
                throw e
            } finally {
                if (pendingWrite === def) pendingWrite = null
            }
        }

    suspend fun readCharacteristic(uuid: UUID, timeoutMs: Long = OP_TIMEOUT_MS): ByteArray =
        opMutex.withLock {
            val g = gatt ?: throw IllegalStateException("not connected")
            val c = characteristics[uuid] ?: throw IllegalStateException("missing characteristic $uuid")
            val operation = "read:${short(uuid)}"
            beginOperation(operation)
            val def = CompletableDeferred<ByteArray>()
            pendingRead = def
            BleLog.log("gatt.read ${short(uuid)}")
            try {
                ensureStarted("read ${short(uuid)}", g.readCharacteristic(c))
                withTimeout(timeoutMs) { def.await() }
                    .also { finishOperation(operation, "ok") }
            } catch (e: TimeoutCancellationException) {
                BleLog.log("gatt.read ${short(uuid)} timed out after ${timeoutMs}ms")
                finishOperation(operation, "timeout_${timeoutMs}ms")
                close()
                throw e
            } catch (e: Exception) {
                finishOperation(operation, "failed:${diagnosticToken(e.message)}")
                throw e
            } finally {
                if (pendingRead === def) pendingRead = null
            }
        }

    /** Enable/disable notifications: setCharacteristicNotification + CCCD write. */
    suspend fun setNotify(uuid: UUID, enable: Boolean, timeoutMs: Long = OP_TIMEOUT_MS) = opMutex.withLock {
        val g = gatt ?: throw IllegalStateException("not connected")
        val c = characteristics[uuid] ?: throw IllegalStateException("missing characteristic $uuid")
        if (!c.supportsNotifyOrIndicate()) throw IllegalStateException("characteristic $uuid is not notifiable")
        val operation = "cccd:${short(uuid)}:${if (enable) "on" else "off"}"
        beginOperation(operation)
        BleLog.log("gatt.notify: ${if (enable) "enable" else "disable"} ${short(uuid)}")
        try {
            ensureStarted("setCharacteristicNotification ${short(uuid)}", g.setCharacteristicNotification(c, enable))
            val cccd = c.getDescriptor(LibreSensorGatt.CCCD) ?: throw IllegalStateException("no CCCD on $uuid")
            val value = when {
                !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            val def = CompletableDeferred<Unit>()
            pendingDescriptor = def
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ensureBluetoothStatus("writeDescriptor ${short(uuid)}", g.writeDescriptor(cccd, value))
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = value
                    ensureStarted("writeDescriptor ${short(uuid)}", g.writeDescriptor(cccd))
                }
            }
            try {
                withTimeout(timeoutMs) { def.await() }
            } finally {
                if (pendingDescriptor === def) pendingDescriptor = null
            }
            finishOperation(operation, "ok")
            BleLog.log("gatt.notify: ${if (enable) "enabled" else "disabled"} ${short(uuid)} cccd=${BleLog.hex(value)}")
        } catch (e: TimeoutCancellationException) {
            BleLog.log("gatt.notify ${short(uuid)} timed out after ${timeoutMs}ms")
            finishOperation(operation, "timeout_${timeoutMs}ms")
            close()
            throw e
        } catch (e: Exception) {
            finishOperation(operation, "failed:${diagnosticToken(e.message)}")
            throw e
        }
    }

    /**
     * Enables the complete post-Phase 6 data-plane CCCD set in protocol order.
     *
     * A fresh Android GATT only needs the seven enables. A PATCH_CONTROL 0xFD
     * recovery needs a real off→on edge to make the sensor observe the new
     * session. Android GATT operations remain serialized.
     */
    suspend fun refreshDataPlaneNotifications(forceRearm: Boolean = false) {
        val startedAtMs = System.currentTimeMillis()
        val mode = if (forceRearm) "off_on" else "enable"
        BleLog.log(
            "$WEAR_BLE_TAG CCCD_REFRESH_START mode=$mode count=${LibreSensorGatt.dataPlaneNotifying.size}",
        )
        try {
            for (uuid in LibreSensorGatt.dataPlaneNotifying) {
                // Longer per-CCCD ceiling than the generic 5s op timeout: the sensor renegotiates
                // to its streaming params (observed interval=390ms latency=4 ⇒ a guaranteed listen
                // window only every ~2s) right in the middle of this sequence, so 5s left ~2.5
                // windows for the descriptor ack and timed out in the field.
                if (forceRearm) {
                    setNotify(uuid, false, timeoutMs = DATA_PLANE_CCCD_TIMEOUT_MS)
                    delay(DATA_PLANE_CCCD_SETTLE_MS)
                }
                setNotify(uuid, true, timeoutMs = DATA_PLANE_CCCD_TIMEOUT_MS)
                if (forceRearm) delay(DATA_PLANE_CCCD_SETTLE_MS)
                BleLog.log("gatt.notify: enabled data-plane CCCD ${short(uuid)}")
                if (uuid == LibreSensorGatt.GLUCOSE_DATA) {
                    BleLog.log(
                        "$WEAR_BLE_TAG SENSOR_CCCD_177A_ENABLED generation=$connectionGenerationId",
                    )
                }
            }
        } catch (e: Exception) {
            BleLog.log("$WEAR_BLE_TAG CCCD_REFRESH_FAILED mode=$mode reason=${e.message}")
            throw e
        }
        BleLog.log(
            "$WEAR_BLE_TAG CCCD_REFRESH_SUCCESS mode=$mode " +
                "durationMs=${System.currentTimeMillis() - startedAtMs}",
        )
    }

    /**
     * Log the link's current PHY (result lands in [BluetoothGattCallback.onPhyRead]). A pure HCI
     * query — no link-layer renegotiation, safe on the touchy Libre 3. Diagnostic for the "would
     * forcing 1M PHY widen the status=8 margin?" question: if this reports 1M already, that
     * theory is dead; only a consistent 2M reading justifies ever trying setPreferredPhy.
     */
    fun readPhy() {
        val g = gatt ?: return
        BleLog.log("gatt.readPhy requested")
        g.readPhy()
    }

    /**
     * Read-only controller diagnostic. It is serialized with every other GATT operation, and a
     * failed sample never tears down an otherwise healthy sensor link.
     */
    suspend fun sampleRemoteRssi(timeoutMs: Long = RSSI_TIMEOUT_MS): Int? = opMutex.withLock {
        val g = gatt ?: return@withLock null
        if (closed) return@withLock null
        val operation = "readRemoteRssi"
        beginOperation(operation)
        val def = CompletableDeferred<Int>()
        pendingRssi = def
        try {
            ensureStarted(operation, g.readRemoteRssi())
            val rssi = withTimeout(timeoutMs) { def.await() }
            finishOperation(operation, "ok:$rssi")
            rssi
        } catch (e: TimeoutCancellationException) {
            finishOperation(operation, "timeout_${timeoutMs}ms")
            BleLog.log("$WEAR_BLE_TAG SENSOR_RSSI_SAMPLE_FAILED reason=timeout_${timeoutMs}ms")
            null
        } catch (e: Exception) {
            finishOperation(operation, "failed:${diagnosticToken(e.message)}")
            BleLog.log("$WEAR_BLE_TAG SENSOR_RSSI_SAMPLE_FAILED reason=${diagnosticToken(e.message)}")
            null
        } finally {
            if (pendingRssi === def) pendingRssi = null
        }
    }

    fun markStreaming() {
        streamingAtElapsedMs = SystemClock.elapsedRealtime()
    }

    internal fun diagnosticSnapshot(): SensorLinkSnapshot {
        val now = SystemClock.elapsedRealtime()
        val channels = notifyDiagnostics.entries
            .sortedBy { short(it.key) }
            .joinToString(",") { (uuid, stats) ->
                val age = ageOrNull(now, stats.lastAtElapsedMs) ?: -1
                "${short(uuid)}:${stats.count.get()}/${stats.bytes.get()}B/${age}ms"
            }
            .ifEmpty { "none" }
        return SensorLinkSnapshot(
            sessionId = sessionId,
            sessionAgeMs = (now - createdAtElapsedMs).coerceAtLeast(0L),
            connectedForMs = ageOrNull(now, connectedAtElapsedMs),
            streamingForMs = ageOrNull(now, streamingAtElapsedMs),
            lastNotifyAgeMs = ageOrNull(now, lastNotifyAtElapsedMs),
            lastNotifyCharacteristic = lastNotifyUuid?.let(::short),
            notificationCount = notificationCount.get(),
            notificationBytes = notificationBytes.get(),
            notificationChannels = channels,
            rssiDbm = lastRssiDbm,
            rssiAgeMs = ageOrNull(now, lastRssiAtElapsedMs),
            txPhy = lastTxPhy,
            rxPhy = lastRxPhy,
            connectionIntervalMs = lastConnectionIntervalMs,
            connectionLatency = lastConnectionLatency,
            supervisionTimeoutMs = lastSupervisionTimeoutMs,
            connectionParamsAgeMs = ageOrNull(now, lastConnectionParamsAtElapsedMs),
            currentOperation = currentOperation,
            currentOperationAgeMs = currentOperation?.let { ageOrNull(now, currentOperationAtElapsedMs) },
            lastOperation = lastOperation,
            lastOperationResult = lastOperationResult,
            lastOperationAgeMs = ageOrNull(now, lastOperationAtElapsedMs),
            lastCallback = lastCallback,
            lastCallbackAgeMs = ageOrNull(now, lastCallbackAtElapsedMs),
            disconnectRequested = disconnectRequestedAtElapsedMs > 0L,
            closeRequested = closeRequestedAtElapsedMs > 0L,
        )
    }

    fun disconnect() {
        disconnectRequestedAtElapsedMs = SystemClock.elapsedRealtime()
        BleLog.log("gatt.disconnect requested")
        gatt?.disconnect()
    }

    fun close() {
        closeRequestedAtElapsedMs = SystemClock.elapsedRealtime()
        BleLog.log("gatt.close generation=$connectionGenerationId")
        closed = true
        connected = false
        onDisconnected = null
        onLinkUp = null
        val closingGatt = gatt
        gatt = null
        characteristics.clear()
        failAllPending(IllegalStateException("connection closed"))
        notifyChannels.values.forEach { it.close() }
        notifyChannels.clear()
        runCatching { closingGatt?.close() }
    }

    private fun failAllPending(e: Throwable) {
        pendingConnect?.completeExceptionally(e); pendingConnect = null
        pendingServices?.completeExceptionally(e); pendingServices = null
        pendingWrite?.completeExceptionally(e); pendingWrite = null
        pendingRead?.completeExceptionally(e); pendingRead = null
        pendingDescriptor?.completeExceptionally(e); pendingDescriptor = null
        pendingRssi?.completeExceptionally(e); pendingRssi = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (ignoreStaleCallback(g, "onConnectionStateChange")) return
            recordCallback("connectionState:$status/$newState")
            BleLog.log("onConnectionStateChange status=$status newState=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connectedAtElapsedMs = SystemClock.elapsedRealtime()
                connected = true
                closed = false
                BleLog.log(
                    "$WEAR_BLE_TAG SENSOR_STATE_CONNECTED generation=$connectionGenerationId status=$status",
                )
                // The controller may have woken the watch for this callback alone; the owner takes
                // its wake lock here, before the coroutine waiting on pendingConnect is scheduled.
                onLinkUp?.invoke()
                pendingConnect?.complete(Unit); pendingConnect = null
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val linkWasEstablished = connectedAtElapsedMs > 0L
                connected = false
                BleLog.log(
                    "$WEAR_BLE_TAG SENSOR_STATE_DISCONNECTED generation=$connectionGenerationId status=$status " +
                        "established=$linkWasEstablished",
                )
                closed = true
                failAllPending(GattDisconnectedException(status))
                // The owner first: it takes its wake lock in this callback, which may be all that
                // keeps the watch awake, before the binder round trip of close() below.
                onDisconnected?.invoke(status, linkWasEstablished)
                // Every loss ends this GATT: recovery belongs to a fresh controller connect, which
                // keeps listening through Doze, never to a retry on the old client.
                runCatching { g.close() }
                if (gatt === g) gatt = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (ignoreStaleCallback(g, "onServicesDiscovered")) return
            recordCallback("services:$status")
            BleLog.log("onServicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) pendingServices?.complete(Unit)
            else pendingServices?.completeExceptionally(IllegalStateException("discover failed status=$status"))
            pendingServices = null
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (ignoreStaleCallback(g, "onCharacteristicWrite")) return
            recordCallback("write:${short(c.uuid)}:$status")
            if (status == BluetoothGatt.GATT_SUCCESS) pendingWrite?.complete(Unit)
            else {
                pendingWrite?.completeExceptionally(
                    GattOperationException("write ${short(c.uuid)}", status),
                )
            }
            pendingWrite = null
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (ignoreStaleCallback(g, "onDescriptorWrite")) return
            recordCallback("descriptor:${short(d.characteristic.uuid)}:$status")
            if (status == BluetoothGatt.GATT_SUCCESS) pendingDescriptor?.complete(Unit)
            else {
                pendingDescriptor?.completeExceptionally(
                    GattOperationException("descriptor ${short(d.characteristic.uuid)}", status),
                )
            }
            pendingDescriptor = null
        }

        // API 33+
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (ignoreStaleCallback(g, "onCharacteristicRead")) return
            recordCallback("read:${short(c.uuid)}:$status")
            if (status == BluetoothGatt.GATT_SUCCESS) pendingRead?.complete(value)
            else pendingRead?.completeExceptionally(IllegalStateException("read failed status=$status"))
            pendingRead = null
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // handled by the value overload
            if (ignoreStaleCallback(g, "onCharacteristicRead")) return
            recordCallback("read:${short(c.uuid)}:$status")
            if (status == BluetoothGatt.GATT_SUCCESS) pendingRead?.complete(c.value ?: ByteArray(0))
            else pendingRead?.completeExceptionally(IllegalStateException("read failed status=$status"))
            pendingRead = null
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (ignoreStaleCallback(g, "onCharacteristicChanged")) return
            deliverNotify(c.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (ignoreStaleCallback(g, "onCharacteristicChanged")) return
            deliverNotify(c.uuid, c.value ?: ByteArray(0))
        }

        // Available on API 21+ but hidden in some SDK levels. It is called by the framework.
        fun onConnectionUpdated(g: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
            if (ignoreStaleCallback(g, "onConnectionUpdated")) return
            recordCallback("connectionUpdated:$status")
            val intervalMs = interval * 1.25
            val timeoutMs = timeout * 10
            lastConnectionIntervalMs = intervalMs
            lastConnectionLatency = latency
            lastSupervisionTimeoutMs = timeoutMs
            lastConnectionParamsAtElapsedMs = SystemClock.elapsedRealtime()
            BleLog.log("gatt.connectionUpdated interval=$interval intervalMs=$intervalMs latency=$latency timeout=$timeout timeoutMs=$timeoutMs status=$status")
        }

        override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (ignoreStaleCallback(g, "onPhyRead")) return
            recordCallback("phyRead:$status")
            lastTxPhy = phyName(txPhy)
            lastRxPhy = phyName(rxPhy)
            BleLog.log("$WEAR_BLE_TAG SENSOR_PHY tx=$lastTxPhy rx=$lastRxPhy status=$status")
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (ignoreStaleCallback(g, "onPhyUpdate")) return
            recordCallback("phyUpdate:$status")
            lastTxPhy = phyName(txPhy)
            lastRxPhy = phyName(rxPhy)
            BleLog.log("$WEAR_BLE_TAG SENSOR_PHY_UPDATED tx=$lastTxPhy rx=$lastRxPhy status=$status")
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (ignoreStaleCallback(g, "onReadRemoteRssi")) return
            recordCallback("rssi:$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                lastRssiDbm = rssi
                lastRssiAtElapsedMs = SystemClock.elapsedRealtime()
                BleLog.log("$WEAR_BLE_TAG SENSOR_RSSI valueDbm=$rssi status=$status")
                pendingRssi?.complete(rssi)
            } else {
                BleLog.log("$WEAR_BLE_TAG SENSOR_RSSI_SAMPLE_FAILED reason=status_$status")
                pendingRssi?.completeExceptionally(IllegalStateException("read RSSI failed status=$status"))
            }
            pendingRssi = null
        }
    }

    private fun phyName(phy: Int): String = when (phy) {
        BluetoothDevice.PHY_LE_1M -> "1M"
        BluetoothDevice.PHY_LE_2M -> "2M"
        BluetoothDevice.PHY_LE_CODED -> "CODED"
        else -> phy.toString()
    }

    private fun deliverNotify(uuid: UUID, value: ByteArray) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        lastNotifyAtElapsedMs = now
        lastNotifyUuid = uuid
        notificationCount.incrementAndGet()
        notificationBytes.addAndGet(value.size.toLong())
        notifyDiagnostics.getOrPut(uuid) { NotifyDiagnostics() }.also {
            it.count.incrementAndGet()
            it.bytes.addAndGet(value.size.toLong())
            it.lastAtElapsedMs = now
        }
        recordCallback("notify:${short(uuid)}:${value.size}B", now)
        BleLog.log("notify ${short(uuid)} len=${value.size} data=${BleLog.hex(value)}")
        notifyChannel(uuid).trySend(value)
    }

    private fun ignoreStaleCallback(g: BluetoothGatt, name: String): Boolean {
        val current = gatt
        if (!isConnectionGenerationCurrent(connectionGenerationId) || closed || (current != null && current !== g)) {
            BleLog.log(
                "$name ignored for stale/closed GATT generation=$connectionGenerationId",
            )
            runCatching { g.close() }
            return true
        }
        return false
    }

    private fun beginOperation(operation: String) {
        currentOperation = operation
        currentOperationAtElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun finishOperation(operation: String, result: String) {
        val now = SystemClock.elapsedRealtime()
        lastOperation = operation
        lastOperationResult = result
        lastOperationAtElapsedMs = now
        if (currentOperation == operation) {
            currentOperation = null
            currentOperationAtElapsedMs = 0L
        }
    }

    private fun recordCallback(name: String, atElapsedMs: Long = SystemClock.elapsedRealtime()) {
        lastCallback = name
        lastCallbackAtElapsedMs = atElapsedMs
    }

    private fun ageOrNull(now: Long, timestamp: Long): Long? =
        timestamp.takeIf { it > 0L }?.let { (now - it).coerceAtLeast(0L) }

    private fun diagnosticToken(value: String?): String = value
        ?.replace(Regex("[^A-Za-z0-9_.:/-]+"), "_")
        ?.take(96)
        ?.ifBlank { "unknown" }
        ?: "unknown"

    private fun ensureStarted(operation: String, started: Boolean) {
        if (!started) throw IllegalStateException("$operation not accepted by Android GATT")
    }

    private fun ensureBluetoothStatus(operation: String, status: Int) {
        if (status != BLUETOOTH_STATUS_SUCCESS) {
            throw IllegalStateException("$operation not accepted by Android GATT status=$status")
        }
    }

    private fun short(uuid: UUID): String = uuid.toString().substring(4, 8)

    private fun BluetoothGattCharacteristic.supportsNotifyOrIndicate(): Boolean =
        properties and (
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE
            ) != 0

    companion object {
        const val OP_TIMEOUT_MS = 5_000L
        /** Post-handshake CCCD enables ride the renegotiated low-power link (~2s listen windows). */
        const val DATA_PLANE_CCCD_TIMEOUT_MS = 20_000L
        private const val DATA_PLANE_CCCD_SETTLE_MS = 90L
        private const val RSSI_TIMEOUT_MS = 4_000L

        private const val WEAR_BLE_TAG = "[WEAR-BLE]"
        private const val BLUETOOTH_STATUS_SUCCESS = 0
        private val nextSessionId = AtomicLong(1L)
    }
}
