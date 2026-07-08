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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import re.abbot.librecr.app.log.BleLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GattDisconnectedException(val gattStatus: Int) : IllegalStateException("disconnected status=$gattStatus")

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
) {
    @Volatile var onDisconnected: ((status: Int) -> Unit)? = null

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    private val characteristics = ConcurrentHashMap<UUID, BluetoothGattCharacteristic>()
    private val notifyChannels = ConcurrentHashMap<UUID, Channel<ByteArray>>()

    private val opMutex = Mutex()
    @Volatile private var pendingConnect: CompletableDeferred<Unit>? = null
    @Volatile private var pendingServices: CompletableDeferred<Unit>? = null
    @Volatile private var pendingWrite: CompletableDeferred<Unit>? = null
    @Volatile private var pendingRead: CompletableDeferred<ByteArray>? = null
    @Volatile private var pendingDescriptor: CompletableDeferred<Unit>? = null

    val isConnected: Boolean get() = gatt != null

    fun notifyChannel(uuid: UUID): Channel<ByteArray> =
        notifyChannels.getOrPut(uuid) { Channel(Channel.UNLIMITED) }

    /**
     * Connect, discover services, and enable only the security-handshake
     * notifications. Data-plane notifications are enabled once after Phase 6.
     */
    suspend fun connectAndDiscover(connectTimeoutMs: Long, discoverTimeoutMs: Long, autoConnect: Boolean = false) {
        connect(connectTimeoutMs, autoConnect)

        // NO connection-priority requests for Libre 3. Every preset hurt: HIGH and BALANCED caused
        // random status=8 link drops, LOW_POWER made the multi-round-trip handshake too slow, and any
        // mid-session change renegotiated bad params (status=19). The most stable behavior is to leave
        // the default BLE connection parameters that Android and the sensor negotiate on their own.
        discoverServices(discoverTimeoutMs)

        subscribeNotifyCharacteristics(LibreSensorGatt.handshakeNotifying, "handshake")
    }

    private suspend fun connect(timeoutMs: Long, autoConnect: Boolean) = opMutex.withLock {
        val def = CompletableDeferred<Unit>()
        pendingConnect = def
        closed = false
        BleLog.log("gatt.connect device=${device.address} (autoConnect=$autoConnect)")
        val newGatt = runCatching {
            device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IllegalStateException("connectGatt returned null")
        }.getOrElse {
            pendingConnect = null
            closed = true
            throw it
        }
        gatt = newGatt
        try {
            withTimeout(timeoutMs) { def.await() }
        } catch (e: TimeoutCancellationException) {
            BleLog.log("gatt.connect timed out after ${timeoutMs}ms")
            close()
            throw e
        } catch (e: Exception) {
            BleLog.log("gatt.connect failure: ${e.message}")
            close()
            throw e
        } finally {
            if (pendingConnect === def) pendingConnect = null
        }
        BleLog.log("gatt.connect: connected")
    }

    private suspend fun discoverServices(timeoutMs: Long) = opMutex.withLock {
        val g = gatt ?: throw IllegalStateException("not connected")
        val def = CompletableDeferred<Unit>()
        pendingServices = def
        BleLog.log("gatt.discoverServices")
        BleLog.log("$WEAR_BLE_TAG SENSOR_DISCOVER_SERVICES_START")
        if (!g.discoverServices()) {
            pendingServices = null
            BleLog.log("$WEAR_BLE_TAG SENSOR_DISCOVER_SERVICES_FAILED reason=start_not_accepted")
            throw IllegalStateException("discoverServices not accepted by Android GATT")
        }
        try {
            withTimeout(timeoutMs) { def.await() }
        } catch (e: TimeoutCancellationException) {
            BleLog.log("$WEAR_BLE_TAG SENSOR_DISCOVER_SERVICES_FAILED reason=timeout_${timeoutMs}ms")
            close()
            throw e
        } catch (e: Exception) {
            BleLog.log("$WEAR_BLE_TAG SENSOR_DISCOVER_SERVICES_FAILED reason=${e.message}")
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
            } catch (e: TimeoutCancellationException) {
                BleLog.log("gatt.write ${short(uuid)} timed out after ${timeoutMs}ms")
                close()
                throw e
            } finally {
                if (pendingWrite === def) pendingWrite = null
            }
        }

    suspend fun readCharacteristic(uuid: UUID, timeoutMs: Long = OP_TIMEOUT_MS): ByteArray =
        opMutex.withLock {
            val g = gatt ?: throw IllegalStateException("not connected")
            val c = characteristics[uuid] ?: throw IllegalStateException("missing characteristic $uuid")
            val def = CompletableDeferred<ByteArray>()
            pendingRead = def
            BleLog.log("gatt.read ${short(uuid)}")
            try {
                ensureStarted("read ${short(uuid)}", g.readCharacteristic(c))
                withTimeout(timeoutMs) { def.await() }
            } catch (e: TimeoutCancellationException) {
                BleLog.log("gatt.read ${short(uuid)} timed out after ${timeoutMs}ms")
                close()
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
        BleLog.log("gatt.notify: ${if (enable) "enable" else "disable"} ${short(uuid)}")
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ensureBluetoothStatus("writeDescriptor ${short(uuid)}", g.writeDescriptor(cccd, value))
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = value
                    ensureStarted("writeDescriptor ${short(uuid)}", g.writeDescriptor(cccd))
                }
            }
            withTimeout(timeoutMs) { def.await() }
        } catch (e: TimeoutCancellationException) {
            BleLog.log("gatt.notify ${short(uuid)} timed out after ${timeoutMs}ms")
            close()
            throw e
        } finally {
            if (pendingDescriptor === def) pendingDescriptor = null
        }
        BleLog.log("gatt.notify: ${if (enable) "enabled" else "disabled"} ${short(uuid)} cccd=${BleLog.hex(value)}")
    }

    /**
     * First-time post-Phase 6 enable of the data-plane CCCDs. They are not
     * subscribed during discovery, so no off→on toggle or settle delay is needed.
     *
     * Android GATT operations must remain serialized; unlike CoreBluetooth, the
     * descriptor writes cannot safely be issued concurrently.
     */
    suspend fun refreshDataPlaneNotifications() {
        val startedAtMs = System.currentTimeMillis()
        BleLog.log("$WEAR_BLE_TAG CCCD_REARM_START count=${LibreSensorGatt.dataPlaneNotifying.size}")
        try {
            for (uuid in LibreSensorGatt.dataPlaneNotifying) {
                // Longer per-CCCD ceiling than the generic 5s op timeout: the sensor renegotiates
                // to its streaming params (observed interval=390ms latency=4 ⇒ a guaranteed listen
                // window only every ~2s) right in the middle of this sequence, so 5s left ~2.5
                // windows for the descriptor ack and timed out in the field.
                setNotify(uuid, true, timeoutMs = DATA_PLANE_CCCD_TIMEOUT_MS)
                BleLog.log("gatt.notify: enabled data-plane CCCD ${short(uuid)}")
            }
        } catch (e: Exception) {
            BleLog.log("$WEAR_BLE_TAG CCCD_REARM_FAILED reason=${e.message}")
            throw e
        }
        BleLog.log("$WEAR_BLE_TAG CCCD_REARM_SUCCESS durationMs=${System.currentTimeMillis() - startedAtMs}")
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

    fun disconnect() {
        BleLog.log("gatt.disconnect requested")
        gatt?.disconnect()
    }

    fun close() {
        BleLog.log("gatt.close")
        closed = true
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
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (ignoreStaleCallback(g, "onConnectionStateChange")) return
            BleLog.log("onConnectionStateChange status=$status newState=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                BleLog.log("$WEAR_BLE_TAG SENSOR_CONNECTED status=$status")
                pendingConnect?.complete(Unit); pendingConnect = null
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                BleLog.log("$WEAR_BLE_TAG SENSOR_DISCONNECTED status=$status")
                closed = true
                failAllPending(GattDisconnectedException(status))
                val cb = onDisconnected
                runCatching { g.close() }
                gatt = null
                cb?.invoke(status)
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                BleLog.log("$WEAR_BLE_TAG SENSOR_DISCONNECTED status=$status")
                closed = true
                failAllPending(GattDisconnectedException(status))
                val cb = onDisconnected
                runCatching { g.close() }
                gatt = null
                cb?.invoke(status)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (ignoreStaleCallback(g, "onServicesDiscovered")) return
            BleLog.log("onServicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) pendingServices?.complete(Unit)
            else pendingServices?.completeExceptionally(IllegalStateException("discover failed status=$status"))
            pendingServices = null
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (ignoreStaleCallback(g, "onCharacteristicWrite")) return
            if (status == BluetoothGatt.GATT_SUCCESS) pendingWrite?.complete(Unit)
            else pendingWrite?.completeExceptionally(IllegalStateException("write failed status=$status"))
            pendingWrite = null
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (ignoreStaleCallback(g, "onDescriptorWrite")) return
            if (status == BluetoothGatt.GATT_SUCCESS) pendingDescriptor?.complete(Unit)
            else pendingDescriptor?.completeExceptionally(IllegalStateException("descriptor write failed status=$status"))
            pendingDescriptor = null
        }

        // API 33+
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (ignoreStaleCallback(g, "onCharacteristicRead")) return
            if (status == BluetoothGatt.GATT_SUCCESS) pendingRead?.complete(value)
            else pendingRead?.completeExceptionally(IllegalStateException("read failed status=$status"))
            pendingRead = null
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // handled by the value overload
            if (ignoreStaleCallback(g, "onCharacteristicRead")) return
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
            val intervalMs = (interval * 1.25).toInt()
            val timeoutMs = timeout * 10
            BleLog.log("gatt.connectionUpdated interval=$interval intervalMs=$intervalMs latency=$latency timeout=$timeout timeoutMs=$timeoutMs status=$status")
        }

        override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (ignoreStaleCallback(g, "onPhyRead")) return
            BleLog.log("$WEAR_BLE_TAG SENSOR_PHY tx=${phyName(txPhy)} rx=${phyName(rxPhy)} status=$status")
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (ignoreStaleCallback(g, "onPhyUpdate")) return
            BleLog.log("$WEAR_BLE_TAG SENSOR_PHY_UPDATED tx=${phyName(txPhy)} rx=${phyName(rxPhy)} status=$status")
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
        BleLog.log("notify ${short(uuid)} len=${value.size} data=${BleLog.hex(value)}")
        notifyChannel(uuid).trySend(value)
    }

    private fun ignoreStaleCallback(g: BluetoothGatt, name: String): Boolean {
        val current = gatt
        if (closed || (current != null && current !== g)) {
            BleLog.log("$name ignored for stale/closed GATT")
            runCatching { g.close() }
            return true
        }
        return false
    }

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

        // No connection-priority manipulation for Libre 3: every preset hurt (HIGH/BALANCED →
        // random status=8, LOW_POWER → handshake too slow, any mid-session change → status=19).
        // We rely on the default Android/sensor-negotiated BLE parameters.
        private const val WEAR_BLE_TAG = "[WEAR-BLE]"
        private const val BLUETOOTH_STATUS_SUCCESS = 0
    }
}
