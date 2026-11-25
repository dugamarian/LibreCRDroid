package re.abbot.librecr.app.ble

/**
 * Evidence which is available without changing the BLE link. A supervision timeout (status 8, or
 * 147 on Wear OS 7) only states that the controller's link-supervision timer expired; this
 * assessment deliberately reports a likely cause family, never a certainty which the controller
 * did not expose. Callers pass the loss of a link which had been established.
 */
internal data class BleDisconnectEvidence(
    val status: Int,
    val localDisconnectRequested: Boolean,
    val bluetoothEnabled: Boolean?,
    val rssiDbm: Int?,
    val rssiAgeMs: Long?,
    val lastNotifyAgeMs: Long?,
)

internal data class BleDisconnectAssessment(
    val meaning: String,
    val probableCause: String,
    val confidence: String,
    val evidence: String,
)

internal fun assessBleDisconnect(e: BleDisconnectEvidence): BleDisconnectAssessment {
    if (!isLinkSupervisionTimeout(e.status)) {
        return BleDisconnectAssessment(
            meaning = when (e.status) {
                0 -> "local_disconnect"
                19 -> "peer_terminated_connection"
                22 -> "local_host_terminated_connection"
                62 -> "connection_establishment_failed"
                133 -> "android_gatt_stack_error"
                else -> "gatt_or_hci_error_${e.status}"
            },
            probableCause = "status_specific_not_link_timeout",
            confidence = "high",
            evidence = "status=${e.status}",
        )
    }

    val meaning = "link_supervision_timeout_no_controller_response"
    if (e.localDisconnectRequested) {
        return BleDisconnectAssessment(
            meaning = meaning,
            probableCause = "timeout_raced_with_local_disconnect",
            confidence = "high",
            evidence = evidenceText(e),
        )
    }
    if (e.bluetoothEnabled == false) {
        return BleDisconnectAssessment(
            meaning = meaning,
            probableCause = "watch_bluetooth_radio_turned_off",
            confidence = "high",
            evidence = evidenceText(e),
        )
    }

    val freshRssi = e.rssiDbm?.takeIf {
        val age = e.rssiAgeMs
        age != null && age in 0..RSSI_FRESH_MS
    }
    return when {
        freshRssi != null && freshRssi <= RSSI_VERY_WEAK_DBM -> BleDisconnectAssessment(
            meaning = meaning,
            probableCause = "weak_signal_or_out_of_range",
            confidence = "high",
            evidence = evidenceText(e),
        )
        freshRssi != null && freshRssi <= RSSI_WEAK_DBM -> BleDisconnectAssessment(
            meaning = meaning,
            probableCause = "weak_signal_or_rf_interference",
            confidence = "medium",
            evidence = evidenceText(e),
        )
        freshRssi != null -> BleDisconnectAssessment(
            meaning = meaning,
            probableCause = "short_rf_interference_or_sensor_missed_connection_events",
            confidence = "medium",
            evidence = evidenceText(e),
        )
        else -> BleDisconnectAssessment(
            meaning = meaning,
            probableCause = "rf_path_loss_interference_or_sensor_unresponsive",
            confidence = "low",
            evidence = evidenceText(e),
        )
    }
}

private fun evidenceText(e: BleDisconnectEvidence): String =
    "localRequest=${e.localDisconnectRequested},btEnabled=${e.bluetoothEnabled ?: "unknown"}," +
        "rssi=${e.rssiDbm ?: "unknown"},rssiAgeMs=${e.rssiAgeMs ?: -1}," +
        "lastNotifyAgeMs=${e.lastNotifyAgeMs ?: -1}"

private const val RSSI_FRESH_MS = 90_000L
private const val RSSI_WEAK_DBM = -82
private const val RSSI_VERY_WEAK_DBM = -90
