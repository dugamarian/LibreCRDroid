package re.abbot.librecr.app.ble

import java.util.UUID

/**
 * GATT inventory for the Libre 3 sensor. All UUIDs share the base
 * 0898XXXX-EF89-11E9-81B4-2A2AE2DBCCE4. Mirrors Swift `LibreSensorGATT`
 * (grounded in MSLibre3Constants.java).
 */
object LibreSensorGatt {
    private fun uuid(short: String): UUID = UUID.fromString("0898${short}-EF89-11E9-81B4-2A2AE2DBCCE4")

    val SERVICE = uuid("10CC")          // LIBRE3_DATA_SERVICE — used for scanning
    val SECURITY_SERVICE = uuid("203A")

    // Data service characteristics
    val GLUCOSE_DATA = uuid("177A")     // notify — realtime glucose
    val PATCH_STATUS = uuid("1482")     // notify
    val PATCH_CONTROL = uuid("1338")    // write
    val HISTORIC_DATA = uuid("195A")    // notify
    val EVENT_LOG = uuid("1BEE")        // notify
    val CLINICAL_DATA = uuid("1AB8")    // notify
    val FACTORY_DATA = uuid("1D24")     // notify

    // Security service characteristics
    val SEC_CERT_DATA = uuid("23FA")        // cert + ephemeral exchange (0x002d)
    val SEC_CHALLENGE_DATA = uuid("22CE")   // Phase 5/6 (0x002a)
    val SEC_COMMAND_RESPONSE = uuid("2198") // single-byte command clock (0x0027)

    // Client Characteristic Configuration Descriptor
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Aliases used by the pairing flow's BleCharRef mapping. */
    val CERT_HANDSHAKE = SEC_CERT_DATA
    val CHALLENGE = SEC_CHALLENGE_DATA

    val allCharacteristics = listOf(
        GLUCOSE_DATA, PATCH_STATUS, PATCH_CONTROL, HISTORIC_DATA,
        EVENT_LOG, CLINICAL_DATA, FACTORY_DATA,
        SEC_CERT_DATA, SEC_CHALLENGE_DATA, SEC_COMMAND_RESPONSE,
    )

    /**
     * Data-plane notifications enabled once after Phase 6, in consumption order.
     *
     * Every CCCD enable after the first rides the sensor-renegotiated slow link (~2s per listen
     * window, observed in the field), so the order is critical-first: PATCH_CONTROL must stay
     * first (arms the command channel; also appears to trigger the sensor's renegotiation),
     * then the glucose pipeline (GLUCOSE_DATA + PATCH_STATUS), then the backfill channels.
     *
     * EVENT_LOG and FACTORY_DATA are deliberately ABSENT (2026-07-05): no collector consumes
     * them, each cost one slow-link round trip per reconnect, and a timed-out EVENT_LOG enable
     * is precisely what killed a field session. If the sensor ever misbehaves without them
     * (no 177a stream, status=19 right after CCCD_REARM), re-add them here first.
     */
    val dataPlaneNotifying = listOf(
        PATCH_CONTROL, GLUCOSE_DATA, PATCH_STATUS, HISTORIC_DATA, CLINICAL_DATA,
    )

    /**
     * The only notification characteristics needed before the security
     * handshake. Data-plane CCCDs are deliberately left disabled until Phase 6.
     */
    val handshakeNotifying = listOf(
        SEC_CERT_DATA, SEC_CHALLENGE_DATA, SEC_COMMAND_RESPONSE,
    )
}
