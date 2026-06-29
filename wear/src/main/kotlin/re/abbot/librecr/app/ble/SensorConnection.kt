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

    private var gatt: BluetoothGatt? = null
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
        BleLog.log("gatt.connect device=${device.address} (autoConnect=$autoConnect)")
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(timeoutMs) { def.await() }
        } catch (e: Exception) {
            BleLog.log("gatt.connect timeout/failure: ${e.message}")
            close()
            throw e
        }
        BleLog.log("gatt.connect: connected")
    }

    private suspend fun discoverServices(timeoutMs: Long) = opMutex.withLock {
        val g = gatt ?: throw IllegalStateException("not connected")
        val def = CompletableDeferred<Unit>()
        pendingServices = def
        BleLog.log("gatt.discoverServices")
        BleLog.log("$WEAR_BLE_TAG SENSOR_DISCOVER_SERVICES_START")
        g.discoverServices()
        try {
            withTimeout(timeoutMs) { def.await() }
        } catch (e: Exception) {
            BleLog.log("$WEAR_BLE_TAG SENSOR_DISCOVER_SERVICES_FAILED reason=${e.message}")
            throw e
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, writeType)
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = writeType; c.value = value; g.writeCharacteristic(c) }
            }
            withTimeout(timeoutMs) { def.await() }
        }

    suspend fun readCharacteristic(uuid: UUID, timeoutMs: Long = OP_TIMEOUT_MS): ByteArray =
        opMutex.withLock {
            val g = gatt ?: throw IllegalStateException("not connected")
            val c = characteristics[uuid] ?: throw IllegalStateException("missing characteristic $uuid")
            val def = CompletableDeferred<ByteArray>()
            pendingRead = def
            BleLog.log("gatt.read ${short(uuid)}")
            g.readCharacteristic(c)
            withTimeout(timeoutMs) { def.await() }
        }

    /** Enable/disable notifications: setCharacteristicNotification + CCCD write. */
    suspend fun setNotify(uuid: UUID, enable: Boolean, timeoutMs: Long = OP_TIMEOUT_MS) = opMutex.withLock {
        val g = gatt ?: throw IllegalStateException("not connected")
        val c = characteristics[uuid] ?: throw IllegalStateException("missing characteristic $uuid")
        if (!c.supportsNotifyOrIndicate()) throw IllegalStateException("characteristic $uuid is not notifiable")
        BleLog.log("gatt.notify: ${if (enable) "enable" else "disable"} ${short(uuid)}")
        g.setCharacteristicNotification(c, enable)
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
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            run { cccd.value = value; g.writeDescriptor(cccd) }
        }
        withTimeout(timeoutMs) { def.await() }
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
                setNotify(uuid, true)
                BleLog.log("gatt.notify: enabled data-plane CCCD ${short(uuid)}")
            }
        } catch (e: Exception) {
            BleLog.log("$WEAR_BLE_TAG CCCD_REARM_FAILED reason=${e.message}")
            throw e
        }
        BleLog.log("$WEAR_BLE_TAG CCCD_REARM_SUCCESS durationMs=${System.currentTimeMillis() - startedAtMs}")
    }

    fun disconnect() {
        BleLog.log("gatt.disconnect requested")
        gatt?.disconnect()
    }

    fun close() {
        BleLog.log("gatt.close")
        runCatching { gatt?.close() }
        gatt = null
        characteristics.clear()
        failAllPending(IllegalStateException("connection closed"))
        notifyChannels.values.forEach { it.close() }
        notifyChannels.clear()
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
            BleLog.log("onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                BleLog.log("$WEAR_BLE_TAG SENSOR_CONNECTED status=$status")
                pendingConnect?.complete(Unit); pendingConnect = null
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                BleLog.log("$WEAR_BLE_TAG SENSOR_DISCONNECTED status=$status")
                failAllPending(GattDisconnectedException(status))
                val cb = onDisconnected
                refreshDeviceCache(g)
                runCatching { g.close() }
                gatt = null
                cb?.invoke(status)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            BleLog.log("onServicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) pendingServices?.complete(Unit)
            else pendingServices?.completeExceptionally(IllegalStateException("discover failed status=$status"))
            pendingServices = null
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pendingWrite?.complete(Unit)
            else pendingWrite?.completeExceptionally(IllegalStateException("write failed status=$status"))
            pendingWrite = null
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pendingDescriptor?.complete(Unit)
            else pendingDescriptor?.completeExceptionally(IllegalStateException("descriptor write failed status=$status"))
            pendingDescriptor = null
        }

        // API 33+
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pendingRead?.complete(value)
            else pendingRead?.completeExceptionally(IllegalStateException("read failed status=$status"))
            pendingRead = null
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // handled by the value overload
            if (status == BluetoothGatt.GATT_SUCCESS) pendingRead?.complete(c.value ?: ByteArray(0))
            else pendingRead?.completeExceptionally(IllegalStateException("read failed status=$status"))
            pendingRead = null
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliverNotify(c.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            deliverNotify(c.uuid, c.value ?: ByteArray(0))
        }

        // Available on API 21+ but hidden in some SDK levels. It is called by the framework.
        fun onConnectionUpdated(g: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
            val intervalMs = (interval * 1.25).toInt()
            val timeoutMs = timeout * 10
            BleLog.log("gatt.connectionUpdated interval=$interval intervalMs=$intervalMs latency=$latency timeout=$timeout timeoutMs=$timeoutMs status=$status")
        }
    }

    private fun deliverNotify(uuid: UUID, value: ByteArray) {
        BleLog.log("notify ${short(uuid)} len=${value.size} data=${BleLog.hex(value)}")
        notifyChannel(uuid).trySend(value)
    }

    private fun short(uuid: UUID): String = uuid.toString().substring(4, 8)

    private fun BluetoothGattCharacteristic.supportsNotifyOrIndicate(): Boolean =
        properties and (
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE
            ) != 0

    companion object {
        const val OP_TIMEOUT_MS = 5_000L

        // No connection-priority manipulation for Libre 3: every preset hurt (HIGH/BALANCED →
        // random status=8, LOW_POWER → handshake too slow, any mid-session change → status=19).
        // We rely on the default Android/sensor-negotiated BLE parameters.
        private const val WEAR_BLE_TAG = "[WEAR-BLE]"
    }

    @SuppressLint("MissingPermission")
    private fun refreshDeviceCache(gatt: BluetoothGatt?) {
        try {
            val localMethod = gatt?.javaClass?.getMethod("refresh")
            val result = localMethod?.invoke(gatt) as? Boolean ?: false
            BleLog.log("gatt.refresh result=$result")
        } catch (e: Exception) {
            BleLog.log("gatt.refresh failed: ${e.message}")
        }
    }
}
