package re.abbot.librecr.protocol.connection

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReceiverRecoveryTest {
    private class Fake : RecoveryRuntime {
        var time = 1_000_000L
        var health = ReceiverHealth()
        var releaseAck = true
        var releaseWorks = true
        var startPhoneWorks = true
        var startWatchWorks = true
        var stopPhoneWorks = true
        var prepares = 0
        var starts = 0
        var phoneRunning = false
        var stopCount = 0
        var saved: RecoveryState? = null
        var token = 0
        val trace = mutableListOf<String>()
        override fun nowMs() = time
        override fun nextToken() = "token-${++token}"
        override suspend fun save(state: RecoveryState?) { saved = state; trace += "save:${state?.phase}" }
        override suspend fun status(nodeId: String) = health
        override suspend fun releaseWatch(state: RecoveryState, manual: Boolean): Boolean {
            trace += "release"
            if (releaseWorks) health = health.copy(active = false, claiming = false)
            return releaseAck
        }
        override suspend fun prepareWatch(state: RecoveryState): Boolean { prepares++; return health.reachable && health.canStart }
        override suspend fun startWatch(state: RecoveryState): Boolean { trace += "startWatch"; starts++; return startWatchWorks }
        override suspend fun startPhone(): Boolean { trace += "startPhone"; phoneRunning = startPhoneWorks; return startPhoneWorks }
        override suspend fun stopPhone(): Boolean { trace += "stopPhone"; stopCount++; if (stopPhoneWorks) phoneRunning = false; return stopPhoneWorks }
        override suspend fun watchOwnsSensor() { trace += "watchConfirmed" }
    }
    private suspend fun monitoring(f: Fake, enabled: Boolean = true): PhoneRecoveryCoordinator =
        PhoneRecoveryCoordinator(f).also { it.configure(enabled); it.preferWatch("sensor", "watch") }
    private suspend fun collectFive(f: Fake, c: PhoneRecoveryCoordinator) {
        for (minute in 1..5) { f.time += 60_000L; c.localPacket(minute, true) }
    }

    @Test fun `watch death recovers on phone without release acknowledgement`() = runTest {
        val f = Fake().apply { releaseAck = false }
        val c = monitoring(f)
        f.time += RecoveryPolicy.MONITOR_DEADLINE_MS
        c.tick()
        assertTrue(f.phoneRunning)
        assertEquals(RecoveryPhase.PHONE_CONNECTING, c.state!!.phase)
        assertFalse(f.trace.contains("watchConfirmed"))
        c.localPacket(100, true)
        assertEquals(RecoveryPhase.PHONE_COLLECTING, c.state!!.phase)
    }
    @Test fun `fresh live unavailable packets keep watch alive without numeric glucose`() = runTest {
        val f = Fake()
        val c = monitoring(f)
        repeat(8) { i ->
            f.time += 60_000L
            c.remotePacket("sensor", "watch", i)
            c.tick()
        }
        assertFalse(f.phoneRunning)
        assertEquals(RecoveryPhase.WATCH_MONITOR, c.state!!.phase)
    }
    @Test fun `duplicate remote packets cannot renew ownership forever`() = runTest {
        val f = Fake()
        val c = monitoring(f)
        c.remotePacket("sensor", "watch", 10)
        repeat(3) { f.time += 60_000L; c.remotePacket("sensor", "watch", 10) }
        f.time += RecoveryPolicy.MONITOR_GRACE_MS
        c.tick()
        assertTrue(f.phoneRunning)
    }
    @Test fun `healthy watch continues even when phone stops receiving relay`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(true, true, 2_000L, true) }
        val c = monitoring(f)
        f.time += RecoveryPolicy.MONITOR_DEADLINE_MS
        c.tick()
        assertFalse(f.phoneRunning)
        assertFalse(f.trace.contains("release"))
        assertEquals(RecoveryPolicy.monitorDelayMs(2_000L), c.nextCheckMs())
    }
    @Test fun `automatic disabled leaves watch alone while explicit manual takeover still works`() = runTest {
        val f = Fake().apply { releaseAck = false }
        val c = monitoring(f, enabled = false)
        f.time += 30 * 60_000L
        c.tick()
        assertFalse(f.phoneRunning)
        assertNull(c.nextCheckMs())
        assertTrue(c.requestPhone("sensor", "watch", manual = true))
        assertTrue(f.phoneRunning)
        assertFalse(c.state!!.preferWatch)
    }
    @Test fun `manual transfer also releases an available healthy watch`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(true, true, 0L, true) }
        val c = monitoring(f, enabled = false)
        assertTrue(c.requestPhone("sensor", "watch", manual = true))
        assertTrue(f.trace.indexOf("release") < f.trace.indexOf("startPhone"))
    }
    @Test fun `critical watch battery can request phone before signal is lost`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(true, true, 0L, false) }
        val c = monitoring(f)
        assertTrue(c.requestPhone("sensor", "watch", criticalBattery = true))
        assertTrue(f.phoneRunning)
    }
    @Test fun `a refused watch release cannot start a competing phone connection`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(true, true, 0L, true); releaseWorks = false }
        val c = monitoring(f)
        assertFalse(c.requestPhone("sensor", "watch", manual = true))
        assertFalse(f.phoneRunning)
        assertEquals(RecoveryPhase.WATCH_MONITOR, c.state!!.phase)
    }
    @Test fun `failed phone service launch retains recoverable intent and is retried`() = runTest {
        val f = Fake().apply { startPhoneWorks = false }
        val c = monitoring(f)
        assertFalse(c.requestPhone("sensor", "watch"))
        assertEquals(RecoveryPhase.PHONE_CONNECTING, f.saved!!.phase)
        f.startPhoneWorks = true
        f.time += RecoveryPolicy.FIRST_PACKET_MS
        c.tick()
        assertTrue(f.phoneRunning)
        assertEquals(RecoveryPhase.PHONE_CONNECTING, c.state!!.phase)
    }
    @Test fun `partial phone stop restores its collector without starting the watch`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(reachable = true, canStart = true); stopPhoneWorks = false }
        val c = monitoring(f)
        c.requestPhone("sensor", "watch"); collectFive(f, c)
        assertEquals(0, f.starts)
        assertTrue(f.phoneRunning)
        assertEquals(RecoveryPhase.PHONE_COLLECTING, c.state!!.phase)
        assertEquals(2, f.trace.count { it == "startPhone" })
    }
    @Test fun `five readings cannot return to an absent or low battery watch`() = runTest {
        val f = Fake()
        val c = monitoring(f)
        c.requestPhone("sensor", "watch")
        collectFive(f, c)
        assertTrue(f.phoneRunning)
        assertEquals(0, f.starts)
        f.health = ReceiverHealth(reachable = true, canStart = false)
        c.peerConnected(); c.tick()
        assertTrue(f.phoneRunning)
        assertEquals(0, f.starts)
    }
    @Test fun `start acknowledgement is not connection confirmation`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(reachable = true, canStart = true) }
        val c = monitoring(f)
        c.requestPhone("sensor", "watch")
        collectFive(f, c)
        assertEquals(RecoveryPhase.WATCH_CONNECTING, c.state!!.phase)
        assertFalse(f.phoneRunning)
        assertFalse(f.trace.contains("watchConfirmed"))
        assertTrue(f.trace.indexOf("stopPhone") < f.trace.indexOf("startWatch"))
        f.health = ReceiverHealth(true, true, 1_000L, true)
        c.tick()
        assertEquals(RecoveryPhase.WATCH_MONITOR, c.state!!.phase)
        assertTrue(f.trace.contains("watchConfirmed"))
    }
    @Test fun `lost start acknowledgement resolves through a fresh sensor packet`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(reachable = true, canStart = true); startWatchWorks = false }
        val c = monitoring(f)
        c.requestPhone("sensor", "watch")
        collectFive(f, c)
        c.remotePacket("sensor", "watch", 6)
        assertEquals(RecoveryPhase.WATCH_MONITOR, c.state!!.phase)
        assertFalse(f.phoneRunning)
    }
    @Test fun `delayed watch packet from before transfer does not confirm return`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(reachable = true, canStart = true) }
        val c = monitoring(f)
        c.requestPhone("sensor", "watch")
        collectFive(f, c)
        c.remotePacket("sensor", "watch", 4)
        assertEquals(RecoveryPhase.WATCH_CONNECTING, c.state!!.phase)
    }
    @Test fun `restart preserves the packet boundary required to confirm a watch transfer`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(reachable = true, canStart = true) }
        val c = monitoring(f)
        c.requestPhone("sensor", "watch"); collectFive(f, c)
        val restored = PhoneRecoveryCoordinator(f, f.saved)
        restored.configure(true)
        restored.remotePacket("sensor", "watch", 5)
        assertEquals(RecoveryPhase.WATCH_CONNECTING, restored.state!!.phase)
        restored.remotePacket("sensor", "watch", 6)
        assertEquals(RecoveryPhase.WATCH_MONITOR, restored.state!!.phase)
    }
    @Test fun `missing first watch packet returns to the phone after a bounded deadline`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(reachable = true, canStart = true) }
        val c = monitoring(f)
        c.requestPhone("sensor", "watch")
        collectFive(f, c)
        f.time += RecoveryPolicy.FIRST_PACKET_MS
        c.tick()
        assertEquals(RecoveryPhase.PHONE_CONNECTING, c.state!!.phase)
        assertTrue(f.phoneRunning)
        assertTrue(c.state!!.retryWatchAtMs > f.time)
    }
    @Test fun `phone signal loss can try ready watch without five new values`() = runTest {
        val f = Fake()
        val c = monitoring(f)
        c.requestPhone("sensor", "watch")
        c.localPacket(1, true)
        c.interrupted()
        f.health = ReceiverHealth(reachable = true, canStart = true)
        f.time += RecoveryPolicy.SILENCE_MS
        c.tick()
        assertEquals(RecoveryPhase.WATCH_CONNECTING, c.state!!.phase)
        assertEquals(1, f.starts)
    }
    @Test fun `both devices away from sensor keep existing phone loop without resets`() = runTest {
        val f = Fake()
        val c = monitoring(f)
        c.requestPhone("sensor", "watch")
        repeat(10) { f.time += RecoveryPolicy.SILENCE_MS; c.tick() }
        assertEquals(0, f.stopCount)
        assertEquals(0, f.starts)
        assertTrue(f.phoneRunning)
    }
    @Test fun `an unconfirmed phone cannot renew its exclusive startup grace forever`() = runTest {
        val f = Fake()
        val c = monitoring(f)
        c.requestPhone("sensor", "watch")
        val initialGrace = c.state!!.claimUntilMs
        repeat(5) { f.time += RecoveryPolicy.SILENCE_MS; c.tick() }
        assertEquals(initialGrace, c.state!!.claimUntilMs)
        assertTrue(c.state!!.claimUntilMs < f.time)
        assertTrue(f.phoneRunning)
    }
    @Test fun `failed watch return cannot repeatedly oscillate while sensor is absent`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(reachable = true, canStart = true) }
        val c = monitoring(f)
        c.requestPhone("sensor", "watch")
        f.time += RecoveryPolicy.FIRST_PACKET_MS; c.tick()
        f.time += RecoveryPolicy.FIRST_PACKET_MS; c.tick()
        val calls = f.starts
        f.time += RecoveryPolicy.FIRST_PACKET_MS; c.tick()
        assertEquals(calls, f.starts)
    }
    @Test fun `watch recovering within its grace period is not interrupted`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(true, false, canStart = true, claiming = true, claimRemainingMs = 90_000L) }
        val c = monitoring(f)
        f.time += RecoveryPolicy.MONITOR_DEADLINE_MS; c.tick()
        assertFalse(f.phoneRunning)
        assertEquals(90_000L, c.nextCheckMs())
    }
    @Test fun `silence check waits past the minute grid for a watch reconnecting at the boundary`() = runTest {
        // Field 2026-11-07: readings and reconnect advertisements share one second of every minute,
        // so the watch re-linked exactly SILENCE_MS after its last relayed reading.
        val f = Fake()
        val c = monitoring(f)
        c.remotePacket("sensor", "watch", 1)
        assertEquals(RecoveryPolicy.MONITOR_DEADLINE_MS, c.nextCheckMs())
        f.time += RecoveryPolicy.SILENCE_MS
        f.health = ReceiverHealth(reachable = true, canStart = true) // linked, first reading not yet in
        c.tick()
        assertFalse(f.trace.contains("release"))
        f.time += RecoveryPolicy.MONITOR_GRACE_MS
        f.health = ReceiverHealth(true, true, 17_000L, true) // its first new reading has arrived
        c.tick()
        assertFalse(f.trace.contains("release"))
        assertFalse(f.phoneRunning)
        assertEquals(RecoveryPhase.WATCH_MONITOR, c.state!!.phase)
        assertEquals(RecoveryPolicy.monitorDelayMs(17_000L), c.nextCheckMs())
    }
    @Test fun `watch refusing release while finishing its own reconnect keeps the phone off`() = runTest {
        val f = Fake().apply {
            releaseWorks = false
            health = ReceiverHealth(reachable = true, canStart = true, claiming = true, claimRemainingMs = 30_000L)
        }
        val c = monitoring(f)
        assertFalse(c.requestPhone("sensor", "watch"))
        assertTrue(f.trace.contains("release"))
        assertFalse(f.trace.contains("startPhone"))
        assertFalse(f.phoneRunning)
        assertEquals(RecoveryPhase.WATCH_MONITOR, c.state!!.phase)
        assertEquals(30_000L, c.nextCheckMs())
    }
    @Test fun `a released watch start claim does not block the phone`() = runTest {
        // A watch asking for help during its start window still claims until it accepts the release.
        val f = Fake().apply { health = ReceiverHealth(reachable = true, canStart = true, claiming = true, claimRemainingMs = 100_000L) }
        val c = monitoring(f)
        assertTrue(c.requestPhone("sensor", "watch"))
        assertTrue(f.phoneRunning)
        assertEquals(RecoveryPhase.PHONE_CONNECTING, c.state!!.phase)
    }
    @Test fun `restart during release keeps a watch that is finishing its own reconnect`() = runTest {
        val f = Fake().apply {
            releaseWorks = false
            health = ReceiverHealth(reachable = true, canStart = true, claiming = true, claimRemainingMs = 40_000L)
        }
        val saved = RecoveryState("sensor", "watch", RecoveryPhase.RELEASING_WATCH, "old", 8, deadlineMs = f.time)
        val c = PhoneRecoveryCoordinator(f, saved)
        c.configure(true); c.tick()
        assertFalse(f.phoneRunning)
        assertEquals(RecoveryPhase.WATCH_MONITOR, c.state!!.phase)
        assertEquals(40_000L, c.nextCheckMs())
    }
    @Test fun `only a freshly re-established watch link claims and never for long`() {
        val claim = RecoveryPolicy.RECONNECT_CLAIM_MS
        val silent = RecoveryPolicy.SILENCE_MS
        assertEquals(claim, RecoveryPolicy.reconnectClaimMs(false, 0L, silent))
        assertEquals(claim - 2_000L, RecoveryPolicy.reconnectClaimMs(false, 2_000L, silent))
        assertEquals(0L, RecoveryPolicy.reconnectClaimMs(false, claim, silent))
        assertEquals(0L, RecoveryPolicy.reconnectClaimMs(false, null, silent))
        assertEquals(0L, RecoveryPolicy.reconnectClaimMs(true, 2_000L, 1_000L))
        assertEquals(0L, RecoveryPolicy.reconnectClaimMs(false, 2_000L, RecoveryPolicy.RECONNECT_CLAIM_MAX_SILENCE_MS))
        assertEquals(0L, RecoveryPolicy.reconnectClaimMs(false, 2_000L, Long.MAX_VALUE))
    }
    @Test fun `restart during release eventually launches phone without requiring the watch`() = runTest {
        val f = Fake()
        val saved = RecoveryState("sensor", "watch", RecoveryPhase.RELEASING_WATCH, "old", 8, deadlineMs = f.time)
        val c = PhoneRecoveryCoordinator(f, saved)
        c.configure(true); c.tick()
        assertTrue(f.phoneRunning)
        assertEquals(RecoveryPhase.PHONE_CONNECTING, c.state!!.phase)
    }
    @Test fun `restart awaiting watch confirmation can recover a failed transfer`() = runTest {
        val f = Fake()
        val saved = RecoveryState("sensor", "watch", RecoveryPhase.WATCH_CONNECTING, "old", 8, deadlineMs = f.time)
        val c = PhoneRecoveryCoordinator(f, saved)
        c.configure(false); c.tick() // Finishes in-flight recovery even after toggle OFF.
        assertTrue(f.phoneRunning)
    }
    @Test fun `restart during automatic release preserves a recovered watch connection`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(true, true, 500L, true) }
        val saved = RecoveryState("sensor", "watch", RecoveryPhase.RELEASING_WATCH, "old", 8, deadlineMs = f.time)
        val c = PhoneRecoveryCoordinator(f, saved)
        c.configure(true); c.tick()
        assertEquals(RecoveryPhase.WATCH_MONITOR, c.state!!.phase)
        assertFalse(f.phoneRunning)
        assertFalse(f.trace.contains("release"))
    }
    @Test fun `old sensor and unselected watch cannot confirm a transfer`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(reachable = true, canStart = true) }
        val c = monitoring(f)
        c.requestPhone("sensor", "watch"); collectFive(f, c)
        c.remotePacket("previous-sensor", "watch", 6)
        c.remotePacket("sensor", "other-watch", 6)
        assertEquals(RecoveryPhase.WATCH_CONNECTING, c.state!!.phase)
    }
    @Test fun `unusable local values prove connection but do not qualify for return`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(reachable = true, canStart = true) }
        val c = monitoring(f)
        c.requestPhone("sensor", "watch")
        repeat(8) { i -> f.time += 60_000L; c.localPacket(i, false); c.tick() }
        assertTrue(f.phoneRunning)
        assertEquals(RecoveryPhase.PHONE_COLLECTING, c.state!!.phase)
        assertEquals(0, f.starts)
    }
    @Test fun `duplicates missing minutes and disconnect reset return streak`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(reachable = true, canStart = true) }
        val c = monitoring(f)
        c.requestPhone("sensor", "watch")
        repeat(4) { i -> f.time += 60_000L; c.localPacket(i, true); c.localPacket(i, true) }
        c.interrupted()
        f.time += 60_000L; c.localPacket(4, true)
        assertEquals(0, f.starts)
        f.time += 120_000L; c.localPacket(6, true)
        assertEquals(0, f.starts)
    }
    @Test fun `battery hysteresis refuses depleted watch and accepts charged watch`() {
        assertFalse(RecoveryPolicy.canStartWatch(5, false))
        assertFalse(RecoveryPolicy.canStartWatch(10, false))
        assertTrue(RecoveryPolicy.canStartWatch(15, false))
        assertTrue(RecoveryPolicy.canStartWatch(6, true))
        assertFalse(RecoveryPolicy.canStartWatch(0, true))
    }
    @Test fun `clock changes after restart cannot create an unlimited ownership claim`() {
        assertEquals(50L, RecoveryPolicy.restoreDeadline(1, 1000, 50))
        assertEquals(50 + RecoveryPolicy.MONITOR_DEADLINE_MS, RecoveryPolicy.restoreDeadline(Long.MAX_VALUE, 0, 50))
    }
    @Test fun `control sequence survives a restart with no active automatic cycle`() = runTest {
        val f = Fake()
        val c = PhoneRecoveryCoordinator(f, initialSequence = 42)
        c.configure(true); c.preferWatch("sensor", "watch")
        assertTrue(c.state!!.sequence > 42)
    }
    @Test fun `old release or start commands cannot override newer control`() {
        assertFalse(RecoveryPolicy.acceptsControl("phone", 42, "phone", 41))
        assertTrue(RecoveryPolicy.acceptsControl("phone", 42, "phone", 42))
        assertTrue(RecoveryPolicy.acceptsControl("phone", 42, "phone", 43))
        assertFalse(RecoveryPolicy.acceptsControl("phone", 42, "phone", -1))
        assertFalse(RecoveryPolicy.preparedStartValid("released", 2000, 1000))
        assertFalse(RecoveryPolicy.preparedStartValid("prepared", 1000, 1001))
        assertTrue(RecoveryPolicy.preparedStartValid("prepared", 2000, 1000))
        assertFalse(RecoveryPolicy.acceptsPreparation(true, "released"))
        assertFalse(RecoveryPolicy.acceptsPreparation(true, "started"))
        assertTrue(RecoveryPolicy.acceptsPreparation(true, "prepared"))
        assertTrue(RecoveryPolicy.acceptsPreparation(false, "released"))
    }
    @Test fun `peer backup cannot overwrite a newer proven local reconnect key`() {
        assertFalse(RecoveryPolicy.shouldAdoptBackup(true, 2000, 1000, 3000))
        assertTrue(RecoveryPolicy.shouldAdoptBackup(true, 1000, 2000, 3000))
        assertTrue(RecoveryPolicy.shouldAdoptBackup(false, 2000, 1000, 3000))
        assertFalse(RecoveryPolicy.shouldAdoptBackup(true, 1000, Long.MAX_VALUE, 3000))
    }
    @Test fun `watch failed reconnect request preserves return backoff`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(reachable = true, canStart = true) }
        val c = monitoring(f)
        c.requestPhone("sensor", "watch"); collectFive(f, c)
        c.requestPhone("sensor", "watch")
        assertEquals(RecoveryPhase.PHONE_CONNECTING, c.state!!.phase)
        assertEquals(1, c.state!!.failedWatchTransfers)
        assertTrue(c.state!!.retryWatchAtMs > f.time)
    }
    @Test fun `successful watch return cancels old phone recovery intent`() = runTest {
        val f = Fake().apply { health = ReceiverHealth(reachable = true, canStart = true) }
        val c = monitoring(f)
        c.requestPhone("sensor", "watch"); collectFive(f, c)
        c.remotePacket("sensor", "watch", 6)
        val stopCalls = f.stopCount
        repeat(2) { c.tick() }
        assertEquals(stopCalls, f.stopCount)
        assertFalse(f.phoneRunning)
    }
    @Test fun `rejected preparation never releases phone BLE`() = runTest {
        val f = Fake()
        val c = monitoring(f)
        c.requestPhone("sensor", "watch"); collectFive(f, c)
        assertEquals(0, f.stopCount)
        assertTrue(c.nextCheckMs()!! >= 20_000L)
    }

    @Test fun `already recovered watch is confirmed without restarting its healthy loop`() = runTest {
        val f = Fake()
        val c = monitoring(f)
        c.requestPhone("sensor", "watch")
        f.health = ReceiverHealth(true, true, 10L, true)
        f.time += RecoveryPolicy.FIRST_PACKET_MS; c.tick()
        assertEquals(RecoveryPhase.WATCH_MONITOR, c.state!!.phase)
        assertEquals(0, f.starts)
        assertFalse(f.phoneRunning)
    }

}
