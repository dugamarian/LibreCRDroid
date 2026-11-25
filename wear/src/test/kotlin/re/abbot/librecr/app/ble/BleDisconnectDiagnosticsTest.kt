package re.abbot.librecr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleDisconnectDiagnosticsTest {
    @Test
    fun `status 8 with very weak recent RSSI points to range or signal`() {
        val result = assessBleDisconnect(
            BleDisconnectEvidence(
                status = 8,
                localDisconnectRequested = false,
                bluetoothEnabled = true,
                rssiDbm = -94,
                rssiAgeMs = 12_000,
                lastNotifyAgeMs = 53_000,
            ),
        )

        assertEquals("link_supervision_timeout_no_controller_response", result.meaning)
        assertEquals("weak_signal_or_out_of_range", result.probableCause)
        assertEquals("high", result.confidence)
    }

    @Test
    fun `status 8 with good recent RSSI points to transient interference or peer`() {
        val result = assessBleDisconnect(
            BleDisconnectEvidence(
                status = 8,
                localDisconnectRequested = false,
                bluetoothEnabled = true,
                rssiDbm = -63,
                rssiAgeMs = 20_000,
                lastNotifyAgeMs = 51_000,
            ),
        )

        assertEquals("short_rf_interference_or_sensor_missed_connection_events", result.probableCause)
        assertEquals("medium", result.confidence)
    }

    @Test
    fun `stale RSSI is not used as strong evidence`() {
        val result = assessBleDisconnect(
            BleDisconnectEvidence(
                status = 8,
                localDisconnectRequested = false,
                bluetoothEnabled = true,
                rssiDbm = -95,
                rssiAgeMs = 120_000,
                lastNotifyAgeMs = 52_000,
            ),
        )

        assertEquals("rf_path_loss_interference_or_sensor_unresponsive", result.probableCause)
        assertEquals("low", result.confidence)
    }

    @Test
    fun `disabled watch radio is decisive evidence`() {
        val result = assessBleDisconnect(
            BleDisconnectEvidence(
                status = 8,
                localDisconnectRequested = false,
                bluetoothEnabled = false,
                rssiDbm = null,
                rssiAgeMs = null,
                lastNotifyAgeMs = 4_000,
            ),
        )

        assertEquals("watch_bluetooth_radio_turned_off", result.probableCause)
        assertEquals("high", result.confidence)
    }

    @Test
    fun `status 147 of an established link is assessed as a supervision timeout`() {
        val result = assessBleDisconnect(
            BleDisconnectEvidence(
                status = 147,
                localDisconnectRequested = false,
                bluetoothEnabled = true,
                rssiDbm = -80,
                rssiAgeMs = 39_000,
                lastNotifyAgeMs = 23_000,
            ),
        )

        assertEquals("link_supervision_timeout_no_controller_response", result.meaning)
        assertEquals("short_rf_interference_or_sensor_missed_connection_events", result.probableCause)
    }
}
