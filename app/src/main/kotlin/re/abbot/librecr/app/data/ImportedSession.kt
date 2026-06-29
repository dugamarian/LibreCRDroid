package re.abbot.librecr.app.data

import org.json.JSONObject
import re.abbot.librecr.protocol.hexToBytes
import re.abbot.librecr.protocol.toHex

/**
 * Sensor provisioning: BLE identity, PIN/auth tail, receiver/sensor IDs, static
 * metadata, and — when available — the cached first-pair key so an already-activated
 * sensor can be reconnected without re-pairing. Mirrors Swift `Libre3SensorState`
 * (de)serialization (`Libre3SensorStateLoader`). All of this may cross Phone <-> Watch.
 */
data class ImportedSession(
    val bleAddress: String,
    val bleDeviceName: String?,
    val blePin: ByteArray,
    /** Cached 16-byte first-pair key (Swift `phase5RawKey` / DiaBLE "sharedKey"). When present, the
     *  manager uses cached reconnect (non-destructive); when null it derives it on-device. */
    val phase5RawKey: ByteArray?,
    /** Optional 162-byte phone certificate; falls back to the bundled live-pair `03 03` cert when null. */
    val phoneCert: ByteArray?,
    val receiverId: Int?,
    val serial: String?,
    val warmupMinutes: Int?,
    val wearMinutes: Int?,
    val sensorProductType: Int? = null,
    val sensorGeneration: Int? = null,
    val sensorFirmwareVersion: String? = null,
) {
    init {
        require(blePin.size == 4) { "blePin must be 4 bytes" }
        require(phase5RawKey == null || phase5RawKey.size == 16) { "phase5RawKey must be 16 bytes" }
        require(phoneCert == null || phoneCert.size == 162) { "phoneCert must be 162 bytes" }
    }

    fun toJson(): String = JSONObject().apply {
        put("bleAddress", bleAddress)
        bleDeviceName?.let { put("bleDeviceName", it) }
        put("blePin", blePin.toHex())
        phase5RawKey?.let { put("phase5RawKey", it.toHex()) }
        phoneCert?.let { put("phoneCert", it.toHex()) }
        receiverId?.let { put("receiverId", it.toLong() and 0xffffffffL) }
        serial?.let { put("serial", it) }
        warmupMinutes?.let { put("warmupMinutes", it) }
        wearMinutes?.let { put("wearMinutes", it) }
        sensorProductType?.let { put("sensorProductType", it) }
        sensorGeneration?.let { put("sensorGeneration", it) }
        sensorFirmwareVersion?.let { put("sensorFirmwareVersion", it) }
    }.toString()

    fun toProvisioningJson(): String = withoutTransientCrypto().toJson()

    fun withoutTransientCrypto(): ImportedSession =
        if (phase5RawKey == null && phoneCert == null) this else copy(phase5RawKey = null, phoneCert = null)

    companion object {
        fun fromJson(json: String): ImportedSession {
            val o = JSONObject(json)
            val pin = o.optString("blePin").ifBlank { o.optString("blePIN") }
            // Cached first-pair key (Swift `Libre3SensorStateLoader` field `phase5RawKey`; DiaBLE
            // calls the same 16-byte AES key "sharedKey"). Accept hex or base64, like Swift.
            val rawPhase5 = optionalString(o, "phase5RawKey") ?: optionalString(o, "sharedKey")
            return ImportedSession(
                bleAddress = o.getString("bleAddress"),
                bleDeviceName = optionalString(o, "bleDeviceName") ?: optionalString(o, "deviceName"),
                blePin = parseHex(pin),
                phase5RawKey = rawPhase5?.let { parseBytesFlexible(it) },
                phoneCert = optionalString(o, "phoneCert")?.let { parseBytesFlexible(it) },
                receiverId = if (o.has("receiverId")) o.getLong("receiverId").toInt() else null,
                serial = if (o.has("serial")) o.getString("serial") else null,
                warmupMinutes = if (o.has("warmupMinutes")) o.getInt("warmupMinutes") else null,
                wearMinutes = if (o.has("wearMinutes")) o.getInt("wearMinutes") else null,
                sensorProductType = optionalInt(o, "sensorProductType") ?: optionalInt(o, "productType"),
                sensorGeneration = optionalInt(o, "sensorGeneration") ?: optionalInt(o, "generation"),
                sensorFirmwareVersion = optionalString(o, "sensorFirmwareVersion") ?: optionalString(o, "firmwareVersion"),
            )
        }

        private fun optionalInt(o: JSONObject, key: String): Int? =
            if (o.has(key) && !o.isNull(key)) o.getInt(key) else null

        private fun optionalString(o: JSONObject, key: String): String? =
            if (o.has(key) && !o.isNull(key)) o.getString(key) else null

        private fun parseHex(raw: String): ByteArray {
            val compact = raw
                .replace("0x", "", ignoreCase = true)
                .filter { it.isHexDigit() }
            return hexToBytes(compact)
        }

        /** Parse hex (with optional `0x`/`:`/`-`/space separators) preferred, else base64 — like Swift. */
        private fun parseBytesFlexible(raw: String): ByteArray {
            val trimmed = raw.trim().removePrefix("0x").removePrefix("0X")
            val sepStripped = trimmed.filter { !it.isWhitespace() && it != ':' && it != '-' }
            if (sepStripped.isNotEmpty() && sepStripped.length % 2 == 0 && sepStripped.all { it.isHexDigit() }) {
                return hexToBytes(sepStripped)
            }
            return android.util.Base64.decode(raw.trim(), android.util.Base64.DEFAULT)
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
