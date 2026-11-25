package re.abbot.librecr.protocol.connection

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.connection.PhonePriorityPolicy.WatchAction.*

class PhonePriorityRecoveryTest {
    private class Runtime : RecoveryRuntime {
        var time = 1_000_000L
        var health = ReceiverHealth(reachable = true, active = true, packetAgeMs = 0, canStart = true)
        var releaseWorks = true
        var releaseAck = true
        var stopWorks = true
        var startWorks = true
        var saved: RecoveryState? = null
        val calls = mutableListOf<String>()
        override fun nowMs() = time
        override fun nextToken() = "token-$time-${calls.size}"
        override suspend fun save(state: RecoveryState?) { saved = state }
        override suspend fun status(nodeId: String) = health
        override suspend fun releaseWatch(state: RecoveryState, manual: Boolean): Boolean {
            calls += "release:$manual"
            if (releaseWorks) health = health.copy(active = false, claiming = false, claimRemainingMs = 0)
            return releaseAck
        }
        override suspend fun prepareWatch(state: RecoveryState): Boolean { calls += "prepareWatch"; return true }
        override suspend fun startWatch(state: RecoveryState): Boolean { calls += "startWatch"; return true }
        override suspend fun startPhone(): Boolean { calls += "startPhone"; return startWorks }
        override suspend fun stopPhone(): Boolean { calls += "stopPhone"; return stopWorks }
        override suspend fun watchOwnsSensor() { calls += "watchOwns" }
    }

    @Test fun `enabling phone priority releases a healthy watch before starting phone`() = runTest {
        val runtime = Runtime()
        val coordinator = PhoneRecoveryCoordinator(runtime)
        coordinator.configure(false)
        coordinator.preferWatch("sensor", "watch")
        assertTrue(coordinator.preferPhone("sensor", "watch"))
        assertEquals(listOf("release:true", "startPhone"), runtime.calls)
        assertEquals(RecoveryPhase.PHONE_CONNECTING, runtime.saved!!.phase)
        assertFalse(runtime.saved!!.preferWatch)
    }

    @Test fun `five fresh phone readings keep phone priority`() = runTest {
        val runtime = Runtime()
        val coordinator = PhoneRecoveryCoordinator(runtime)
        coordinator.configure(false)
        coordinator.preferPhone("sensor", "watch")
        repeat(10) {
            runtime.time += 60_000L
            coordinator.localPacket(it, true)
            coordinator.tick()
        }
        assertEquals(RecoveryPhase.PHONE_COLLECTING, coordinator.state!!.phase)
        assertFalse(runtime.calls.contains("prepareWatch"))
        assertFalse(runtime.calls.contains("stopPhone"))
    }

    @Test fun `watch takeover during phone absence can be returned on phone detection`() = runTest {
        val runtime = Runtime()
        val coordinator = PhoneRecoveryCoordinator(runtime)
        coordinator.preferPhone("sensor", "watch")
        coordinator.localPacket(10, true)
        // The watch autonomously acquired BLE while the phone was left behind.
        runtime.health = ReceiverHealth(true, true, 0, true)
        runtime.calls.clear()
        assertTrue(coordinator.preferPhone("sensor", "watch", watchCollecting = true))
        assertEquals(listOf("stopPhone", "release:true", "startPhone"), runtime.calls)
        assertFalse(coordinator.state!!.preferWatch)
    }

    @Test fun `phone reconnect after restart uses persisted ownership to release the fallback watch`() = runTest {
        val runtime = Runtime()
        val original = PhoneRecoveryCoordinator(runtime)
        original.preferPhone("sensor", "watch")
        original.localPacket(10, true)
        val restored = PhoneRecoveryCoordinator(runtime, runtime.saved)
        runtime.health = ReceiverHealth(true, true, 0, true)
        runtime.calls.clear()
        restored.configure(false)
        assertTrue(restored.preferPhone("sensor", "watch", watchCollecting = true))
        assertEquals(listOf("stopPhone", "release:true", "startPhone"), runtime.calls)
    }

    @Test fun `a refused release never creates a competing phone collector`() = runTest {
        val runtime = Runtime().apply { releaseWorks = false }
        val coordinator = PhoneRecoveryCoordinator(runtime)
        assertFalse(coordinator.preferPhone("sensor", "watch"))
        assertFalse(runtime.calls.contains("startPhone"))
        assertEquals(RecoveryPhase.WATCH_MONITOR, coordinator.state!!.phase)
        runtime.releaseWorks = true
        assertTrue(coordinator.preferPhone("sensor", "watch"))
        assertEquals(RecoveryPhase.PHONE_CONNECTING, coordinator.state!!.phase)
    }

    @Test fun `lost release acknowledgement is settled by watch status`() = runTest {
        val runtime = Runtime().apply { releaseAck = false }
        val coordinator = PhoneRecoveryCoordinator(runtime)
        assertTrue(coordinator.preferPhone("sensor", "watch"))
        assertEquals(listOf("release:true", "startPhone"), runtime.calls)
    }

    @Test fun `an existing phone collector changes preference without restarting BLE`() = runTest {
        val runtime = Runtime()
        val saved = RecoveryState("sensor", "watch", RecoveryPhase.PHONE_COLLECTING, "token", 1, preferWatch = true)
        val coordinator = PhoneRecoveryCoordinator(runtime, saved)
        coordinator.configure(false)
        assertTrue(coordinator.preferPhone("sensor", "watch"))
        assertFalse(coordinator.state!!.preferWatch)
        assertTrue(runtime.calls.isEmpty())
    }

    @Test fun `enabling priority preserves a running manual phone handshake and its key`() = runTest {
        val runtime = Runtime()
        val coordinator = PhoneRecoveryCoordinator(runtime)
        assertTrue(coordinator.preferPhone("sensor", "watch", phoneAlreadyRunning = true))
        assertTrue(runtime.calls.isEmpty())
        assertFalse(coordinator.state!!.preferWatch)
        coordinator.localPacket(10, true)
        assertEquals(RecoveryPhase.PHONE_COLLECTING, coordinator.state!!.phase)
    }

    @Test fun `an untracked phone loop is stopped before importing the active watch session`() = runTest {
        val runtime = Runtime()
        val coordinator = PhoneRecoveryCoordinator(runtime)
        assertTrue(coordinator.preferPhone("sensor", "watch", watchCollecting = true, phoneAlreadyRunning = true))
        assertEquals(listOf("stopPhone", "release:true", "startPhone"), runtime.calls)
    }

    @Test fun `failed phone launch retains intent and retries without the watch`() = runTest {
        val runtime = Runtime().apply { startWorks = false }
        val coordinator = PhoneRecoveryCoordinator(runtime)
        assertFalse(coordinator.preferPhone("sensor", "watch"))
        assertEquals(RecoveryPhase.PHONE_CONNECTING, runtime.saved!!.phase)
        runtime.startWorks = true
        runtime.time += RecoveryPolicy.FIRST_PACKET_MS
        coordinator.tick()
        assertEquals(2, runtime.calls.count { it == "startPhone" })
        assertFalse(runtime.calls.contains("startWatch"))
    }

    @Test fun `failed local stop preserves existing ownership before another transfer`() = runTest {
        val runtime = Runtime()
        val coordinator = PhoneRecoveryCoordinator(runtime)
        coordinator.preferPhone("sensor", "watch")
        runtime.stopWorks = false
        runtime.calls.clear()
        assertFalse(coordinator.preferPhone("sensor", "watch", watchCollecting = true))
        assertEquals(listOf("stopPhone"), runtime.calls)
    }

    @Test fun `a different sensor cannot reclaim the current session`() = runTest {
        val runtime = Runtime()
        val coordinator = PhoneRecoveryCoordinator(runtime)
        coordinator.preferPhone("sensor", "watch")
        runtime.calls.clear()
        assertFalse(coordinator.preferPhone("old-sensor", "watch", watchCollecting = true))
        assertTrue(runtime.calls.isEmpty())
    }

    @Test fun `unarmed or disabled standby never starts sensor or requests phone`() {
        for (owns in listOf(false, true)) for (nearby in listOf(false, true)) {
            assertEquals(WAIT, PhonePriorityPolicy.watchAction(false, owns, nearby, ReceiverHealth()))
        }
    }

    @Test fun `phone power loss starts watch without waiting three minutes`() {
        assertEquals(START_WATCH, PhonePriorityPolicy.watchAction(true, false, false, ReceiverHealth()))
    }

    @Test fun `fresh fallback readings leave the watch collecting while phone Bluetooth remains nearby`() = runTest {
        val runtime = Runtime()
        val coordinator = PhoneRecoveryCoordinator(runtime)
        coordinator.configure(false)
        coordinator.preferPhone("sensor", "watch")
        coordinator.localPacket(10, true)
        runtime.calls.clear()
        assertTrue(coordinator.confirmWatchFallback("sensor", "watch", 14))
        repeat(5) {
            runtime.time += 60_000L
            assertTrue(coordinator.confirmWatchFallback("sensor", "watch", 15 + it))
            coordinator.tick()
        }
        assertEquals(listOf("stopPhone", "watchOwns"), runtime.calls)
        assertEquals(RecoveryPhase.WATCH_MONITOR, coordinator.state!!.phase)
        assertFalse(coordinator.state!!.preferWatch)
        assertNull(coordinator.nextCheckMs())
    }

    @Test fun `explicit return request can still release a confirmed fallback watch`() = runTest {
        val runtime = Runtime()
        val coordinator = PhoneRecoveryCoordinator(runtime)
        coordinator.preferPhone("sensor", "watch")
        coordinator.confirmWatchFallback("sensor", "watch", 14)
        runtime.health = ReceiverHealth(true, true, 0, true)
        runtime.calls.clear()
        assertTrue(coordinator.preferPhone("sensor", "watch", watchCollecting = true))
        assertEquals(listOf("release:true", "startPhone"), runtime.calls)
    }

    @Test fun `fallback ownership survives phone restart without an automatic release`() = runTest {
        val runtime = Runtime()
        val coordinator = PhoneRecoveryCoordinator(runtime)
        coordinator.preferPhone("sensor", "watch")
        coordinator.confirmWatchFallback("sensor", "watch", 14)
        val restored = PhoneRecoveryCoordinator(runtime, runtime.saved)
        restored.configure(false)
        runtime.calls.clear()
        runtime.time += 600_000L
        restored.tick()
        assertTrue(runtime.calls.isEmpty())
        assertFalse(restored.ownsPhone)
    }

    @Test fun `old duplicate and wrong source readings cannot establish fallback ownership`() = runTest {
        val runtime = Runtime()
        val coordinator = PhoneRecoveryCoordinator(runtime)
        coordinator.preferPhone("sensor", "watch")
        coordinator.localPacket(10, true)
        runtime.calls.clear()
        assertFalse(coordinator.confirmWatchFallback("sensor", "watch", 10))
        assertFalse(coordinator.confirmWatchFallback("old-sensor", "watch", 14))
        assertFalse(coordinator.confirmWatchFallback("sensor", "other-watch", 14))
        assertTrue(runtime.calls.isEmpty())
        assertTrue(coordinator.confirmWatchFallback("sensor", "watch", 14))
        runtime.calls.clear()
        assertFalse(coordinator.confirmWatchFallback("sensor", "watch", 14))
        assertTrue(runtime.calls.isEmpty())
    }

    @Test fun `cloud-only phone is absent even if a fresh relay arrived`() {
        assertEquals(START_WATCH, PhonePriorityPolicy.watchAction(true, false, false,
            ReceiverHealth(true, true, 0, true)))
        assertEquals(WAIT, PhonePriorityPolicy.watchAction(true, true, false, ReceiverHealth(true, true, 0, true)))
    }

    @Test fun `healthy nearby phone keeps standby from opening BLE`() {
        assertEquals(WAIT, PhonePriorityPolicy.watchAction(true, false, true, ReceiverHealth(true, true, 0, true)))
    }

    @Test fun `watch returns to nearby phone without a five reading delay`() {
        assertEquals(REQUEST_PHONE, PhonePriorityPolicy.watchAction(true, true, true, ReceiverHealth()))
    }

    @Test fun `phone startup claim is respected but an expired claim cannot strand the watch`() {
        assertEquals(WAIT, PhonePriorityPolicy.watchAction(true, false, true,
            ReceiverHealth(true, claiming = true, claimRemainingMs = 1)))
        assertEquals(START_WATCH, PhonePriorityPolicy.watchAction(true, false, true,
            ReceiverHealth(true, claiming = true, claimRemainingMs = 0)))
    }

    @Test fun `silent nearby phone permits fallback and stale status cannot hold ownership`() {
        assertEquals(START_WATCH, PhonePriorityPolicy.watchAction(true, false, true, ReceiverHealth()))
        assertEquals(START_WATCH, PhonePriorityPolicy.watchAction(true, false, true,
            ReceiverHealth(true, true, RecoveryPolicy.SILENCE_MS)))
    }
}
