package re.abbot.librecr.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import re.abbot.librecr.app.log.BleLog
import kotlin.coroutines.resume

data class SensorScanResult(
    val device: BluetoothDevice,
    val advertisedName: String?,
)

/**
 * Scans for a Libre 3 sensor. Mirrors the Swift `discoverFirstSensor` model:
 *   - service-filtered scan first,
 *   - exact normalized address/name match when identity is known,
 *   - strongest-RSSI fallback only when no identity is known,
 *   - unfiltered broad fallback matched strictly by identity (a Libre 3 in some
 *     states does not advertise the data-service UUID).
 */
@SuppressLint("MissingPermission")
class SensorScanner(private val adapter: BluetoothAdapter) {

    suspend fun findSensor(targetAddress: String?, targetDeviceName: String?, timeoutMs: Long): SensorScanResult? {
        val targets = listOfNotNull(
            normalizeIdentity(targetAddress),
            normalizeIdentity(targetDeviceName),
        ).distinct().toSet()
        val hasIdentity = targets.isNotEmpty()
        val primary = if (hasIdentity) maxOf(8_000L, (timeoutMs * 0.6).toLong()) else timeoutMs
        BleLog.log("scan: targets=${targets.ifEmpty { setOf("<any>") }} timeout=${timeoutMs}ms broadFallback=$hasIdentity")

        scanPass(targets, primary, filtered = true, requireIdentityMatch = hasIdentity)?.let {
            BleLog.log("scan: selected service-filtered device=${it.device.address} name=${it.advertisedName ?: "<none>"}")
            return it
        }
        if (!hasIdentity) return null

        val remaining = maxOf(6_000L, timeoutMs - primary)
        BleLog.log("scan: service-filtered empty; broad-scan fallback targets=$targets budget=${remaining}ms")
        return scanPass(targets, remaining, filtered = false, requireIdentityMatch = true)
            ?.also { BleLog.log("scan: selected broad-fallback device=${it.device.address} name=${it.advertisedName ?: "<none>"}") }
    }

    private suspend fun scanPass(
        targets: Set<String>,
        timeoutMs: Long,
        filtered: Boolean,
        requireIdentityMatch: Boolean,
    ): SensorScanResult? {
        if (!adapter.isEnabled) {
            BleLog.log("scan: Bluetooth disabled")
            return null
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            BleLog.log("scan: bluetoothLeScanner unavailable")
            return null
        }
        val graceMs = if (!requireIdentityMatch) {
            minOf(5_000L, maxOf(1_500L, (timeoutMs * 0.12).toLong()))
        } else {
            minOf(12_000L, maxOf(4_000L, (timeoutMs * 0.12).toLong()))
        }

        val bestRssi = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)
        val best = java.util.concurrent.atomic.AtomicReference<SensorScanResult?>(null)
        BleLog.log(
            "scan: pass start filtered=$filtered requireIdentityMatch=$requireIdentityMatch " +
                "targets=${targets.ifEmpty { setOf("<any>") }} timeout=${timeoutMs}ms"
        )

        val found = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var selectionJob: Job? = null
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (!cont.isActive) return
                        val device = result.device
                        val name = result.scanRecord?.deviceName ?: device.name
                        val scanResult = SensorScanResult(device, name)
                        if (!requireIdentityMatch) {
                            if (result.rssi > bestRssi.get()) {
                                bestRssi.set(result.rssi); best.set(scanResult)
                                BleLog.log("scan: best candidate device=${device.address} name=${name ?: "<none>"} rssi=${result.rssi}")
                            }
                            if (selectionJob == null) {
                                val scanCallback = this
                                selectionJob = CoroutineScope(cont.context).launch {
                                    delay(graceMs)
                                    if (!cont.isActive) return@launch
                                    val selected = best.get() ?: return@launch
                                    BleLog.log(
                                        "scan: grace elapsed; using strongest candidate " +
                                            "device=${selected.device.address} rssi=${bestRssi.get()}",
                                    )
                                    runCatching { scanner.stopScan(scanCallback) }
                                    cont.resume(selected)
                                }
                            }
                            return
                        }
                        if (matchesIdentity(device, name, targets)) {
                            BleLog.log("scan: identity match device=${device.address} name=${name ?: "<none>"} rssi=${result.rssi}")
                            selectionJob?.cancel()
                            runCatching { scanner.stopScan(this) }
                            cont.resume(scanResult); return
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        BleLog.log("scan failed code=$errorCode")
                        selectionJob?.cancel()
                        if (cont.isActive) cont.resume(null)
                    }
                }

                val filters = if (filtered) listOf(
                    ScanFilter.Builder().setServiceUuid(ParcelUuid(LibreSensorGatt.SERVICE)).build()
                ) else emptyList()
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0L)
                    .build()
                runCatching {
                    scanner.startScan(filters, settings, callback)
                    BleLog.log("scan: startScan issued filters=${filters.size}")
                }.onFailure {
                    BleLog.log("scan start failed: ${it.message}")
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    selectionJob?.cancel()
                    runCatching { scanner.stopScan(callback) }
                }
            }
        }
        val selected = found ?: best.get()
        if (selected == null) {
            BleLog.log("scan: pass finished without device filtered=$filtered targets=${targets.ifEmpty { setOf("<any>") }}")
        } else if (found == null) {
            BleLog.log("scan: pass timeout; using best device=${selected.device.address} rssi=${bestRssi.get()}")
        }
        return selected
    }

    private fun matchesIdentity(device: BluetoothDevice, name: String?, targets: Set<String>): Boolean {
        val normalizedName = normalizeIdentity(name)
        val normalizedAddress = normalizeIdentity(device.address)
        return normalizedName in targets || normalizedAddress in targets
    }

    private fun normalizeIdentity(value: String?): String? {
        if (value == null) return null
        val n = value.filter { it.isLetterOrDigit() }.uppercase()
        return n.ifEmpty { null }
    }
}
