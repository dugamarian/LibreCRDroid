package re.abbot.librecr.protocol.connection

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PhonePriorityReturnTrialTest {
    private class Runtime : RecoveryRuntime {
        var time = 1_000_000L
        var health = ReceiverHealth(true, true, 0, true, lastLifeCount = 100)
        var releaseAck = true
        var releaseWorks = true
        var releaseDelayMs = 0L
        var lifeCountAfterRelease = 101
        var startPhoneWorks = true
        var stopPhoneWorks = true
        var prepareWorks = true
        var startWatchWorks = true
        var saved: RecoveryState? = null
        var tokens = 0
        val states = mutableListOf<RecoveryState?>()
        val calls = mutableListOf<String>()
        override fun nowMs() = time
        override fun nextToken() = "trial-${++tokens}"
        override suspend fun save(state: RecoveryState?) { saved = state; states += state }
        override suspend fun status(nodeId: String) = health
        override suspend fun releaseWatch(state: RecoveryState, manual: Boolean): Boolean {
            calls += "release:$manual"
            time += releaseDelayMs
            if (releaseWorks) health = health.copy(active = false, claiming = false,
                claimRemainingMs = 0, lastLifeCount = lifeCountAfterRelease)
            return releaseAck
        }
        override suspend fun prepareWatch(state: RecoveryState): Boolean { calls += "prepareWatch"; return prepareWorks }
        override suspend fun startWatch(state: RecoveryState): Boolean { calls += "startWatch"; return startWatchWorks }
        override suspend fun startPhone(): Boolean { calls += "startPhone"; return startPhoneWorks }
        override suspend fun stopPhone(): Boolean { calls += "stopPhone"; return stopPhoneWorks }
        override suspend fun watchOwnsSensor() { calls += "watchOwns" }
    }

    private suspend fun watching(runtime: Runtime) = PhoneRecoveryCoordinator(runtime).also {
        it.configure(false) // The phone-priority adapter does not enable the legacy watch-first policy.
        it.preferWatch("sensor", "watch")
    }

    @Test fun `automatic return requires a reachable watch ready to recover`() = runTest {
        for (health in listOf(
            ReceiverHealth(),
            ReceiverHealth(true, true, 0, false, lastLifeCount = 100),
            ReceiverHealth(true, false, canStart = true),
            ReceiverHealth(true, false, canStart = true, claiming = true, claimRemainingMs = 0),
        )) {
            val runtime = Runtime().apply { this.health = health }
            val coordinator = watching(runtime)
            assertFalse(coordinator.tryPhoneReturn("sensor", "watch"))
            assertTrue(runtime.calls.isEmpty())
            assertEquals(RecoveryPhase.WATCH_MONITOR, coordinator.state!!.phase)
        }
    }

    @Test fun `healthy watch release is acknowledged before the finite phone trial starts`() = runTest {
        val runtime = Runtime()
        val coordinator = watching(runtime)
        assertTrue(coordinator.tryPhoneReturn("sensor", "watch"))
        assertEquals(listOf("release:true", "startPhone"), runtime.calls)
        assertEquals(RecoveryPhase.PHONE_CONNECTING, coordinator.state!!.phase)
        assertTrue(coordinator.state!!.phoneReturnTrial)
        assertFalse(coordinator.state!!.preferWatch)
        assertEquals(runtime.time + RecoveryPolicy.FIRST_PACKET_MS, coordinator.state!!.deadlineMs)
        assertEquals(coordinator.state!!.deadlineMs, coordinator.state!!.claimUntilMs)
        assertEquals(101, coordinator.state!!.confirmationAfterLifeCount)
    }

    @Test fun `only a phone packet after both watch packet boundaries confirms return`() = runTest {
        val runtime = Runtime()
        val coordinator = watching(runtime)
        coordinator.tryPhoneReturn("sensor", "watch")
        coordinator.localPacket(100, true)
        coordinator.localPacket(101, true)
        assertEquals(RecoveryPhase.PHONE_CONNECTING, coordinator.state!!.phase)
        assertTrue(coordinator.state!!.phoneReturnTrial)
        coordinator.localPacket(102, false) // An unusable sensor value still confirms the BLE link.
        assertEquals(RecoveryPhase.PHONE_COLLECTING, coordinator.state!!.phase)
        assertFalse(coordinator.state!!.phoneReturnTrial)
        assertFalse(runtime.calls.contains("startWatch"))
    }

    @Test fun `lost release acknowledgement aborts without a phone connection`() = runTest {
        val runtime = Runtime().apply { releaseAck = false }
        val coordinator = watching(runtime)
        assertFalse(coordinator.tryPhoneReturn("sensor", "watch"))
        assertFalse(runtime.calls.contains("startPhone"))
        assertFalse(runtime.calls.contains("prepareWatch"))
        assertEquals(RecoveryPhase.WATCH_MONITOR, coordinator.state!!.phase)
        assertFalse(coordinator.state!!.phoneReturnTrial)
    }

    @Test fun `a still healthy owner after release cannot compete with a phone trial`() = runTest {
        val runtime = Runtime().apply { releaseWorks = false }
        val coordinator = watching(runtime)
        assertFalse(coordinator.tryPhoneReturn("sensor", "watch"))
        assertFalse(runtime.calls.contains("startPhone"))
        assertEquals(RecoveryPhase.WATCH_MONITOR, coordinator.state!!.phase)
    }

    @Test fun `a bounded startup claim permits a trial when the watch is ready`() = runTest {
        val runtime = Runtime().apply {
            health = ReceiverHealth(true, canStart = true, claiming = true, claimRemainingMs = 30_000, lastLifeCount = 100)
        }
        assertTrue(watching(runtime).tryPhoneReturn("sensor", "watch"))
    }

    @Test fun `failed foreground launch stops phone before preparing watch with a new command`() = runTest {
        val runtime = Runtime().apply { startPhoneWorks = false }
        val coordinator = watching(runtime)
        assertFalse(coordinator.tryPhoneReturn("sensor", "watch"))
        assertEquals(listOf("release:true", "startPhone", "stopPhone", "prepareWatch", "startWatch"), runtime.calls)
        val release = runtime.states.first { it?.phase == RecoveryPhase.RELEASING_WATCH }!!
        assertEquals(RecoveryPhase.WATCH_CONNECTING, coordinator.state!!.phase)
        assertTrue(coordinator.state!!.phoneReturnTrial)
        assertFalse(coordinator.state!!.preferWatch)
        assertNotEquals(release.token, coordinator.state!!.token)
        assertTrue(coordinator.state!!.sequence > release.sequence)
    }

    @Test fun `watch restoration waits for a confirmed phone stop`() = runTest {
        val runtime = Runtime().apply { startPhoneWorks = false; stopPhoneWorks = false }
        val coordinator = watching(runtime)
        assertFalse(coordinator.tryPhoneReturn("sensor", "watch"))
        assertFalse(runtime.calls.contains("prepareWatch"))
        assertFalse(runtime.calls.contains("startWatch"))
        runtime.stopPhoneWorks = true
        coordinator.tick()
        assertTrue(runtime.calls.indexOfLast { it == "stopPhone" } < runtime.calls.indexOf("prepareWatch"))
        assertTrue(runtime.calls.contains("startWatch"))
    }

    @Test fun `a release consuming the complete trial budget never starts the phone`() = runTest {
        val runtime = Runtime().apply { releaseDelayMs = RecoveryPolicy.FIRST_PACKET_MS }
        val coordinator = watching(runtime)
        assertFalse(coordinator.tryPhoneReturn("sensor", "watch"))
        assertFalse(runtime.calls.contains("startPhone"))
        assertTrue(runtime.calls.indexOf("stopPhone") < runtime.calls.indexOf("prepareWatch"))
        assertEquals(RecoveryPhase.WATCH_CONNECTING, coordinator.state!!.phase)
    }

    @Test fun `restart while awaiting the first phone packet keeps the original deadline`() = runTest {
        val runtime = Runtime()
        val original = watching(runtime)
        original.tryPhoneReturn("sensor", "watch")
        val trial = runtime.saved!!
        val restored = PhoneRecoveryCoordinator(runtime, trial)
        runtime.time = trial.deadlineMs
        runtime.calls.clear()
        restored.tick()
        assertEquals(listOf("stopPhone", "prepareWatch", "startWatch"), runtime.calls)
        assertEquals(RecoveryPhase.WATCH_CONNECTING, restored.state!!.phase)
        assertTrue(restored.state!!.phoneReturnTrial)
        assertEquals(0, restored.state!!.claimUntilMs)
    }

    @Test fun `restart during release cannot renew the claim or start without acknowledgement`() = runTest {
        val runtime = Runtime()
        val deadline = runtime.time + RecoveryPolicy.FIRST_PACKET_MS
        val release = RecoveryState("sensor", "watch", RecoveryPhase.RELEASING_WATCH, "old", 5,
            preferWatch = false, deadlineMs = deadline, confirmationAfterLifeCount = 100,
            claimUntilMs = deadline, phoneReturnTrial = true)
        val restored = PhoneRecoveryCoordinator(runtime, release)
        runtime.time = deadline - 1
        restored.tick()
        assertTrue(runtime.calls.isEmpty())
        runtime.time = deadline
        restored.tick()
        assertEquals(RecoveryPhase.WATCH_MONITOR, restored.state!!.phase)
        assertFalse(restored.state!!.phoneReturnTrial)
        assertFalse(runtime.calls.contains("release:true"))
        assertFalse(runtime.calls.contains("startPhone"))
    }

    @Test fun `rollback restart stops any surviving phone loop before watch repair`() = runTest {
        val runtime = Runtime().apply { health = health.copy(active = false) }
        val rollback = RecoveryState("sensor", "watch", RecoveryPhase.WATCH_CONNECTING, "rollback", 9,
            preferWatch = false, deadlineMs = runtime.time + RecoveryPolicy.FIRST_PACKET_MS,
            confirmationAfterLifeCount = 101, phoneReturnTrial = true)
        val restored = PhoneRecoveryCoordinator(runtime, rollback)
        restored.tick()
        assertEquals(listOf("stopPhone", "prepareWatch", "startWatch"), runtime.calls)
    }

    @Test fun `unconfirmed watch rollback keeps repairing the watch instead of bouncing to phone`() = runTest {
        val runtime = Runtime().apply { startPhoneWorks = false; startWatchWorks = false }
        val coordinator = watching(runtime)
        coordinator.tryPhoneReturn("sensor", "watch")
        repeat(3) {
            runtime.time = coordinator.state!!.deadlineMs
            coordinator.tick()
        }
        assertEquals(1, runtime.calls.count { it == "startPhone" })
        assertEquals(4, runtime.calls.count { it == "startWatch" })
        assertEquals(RecoveryPhase.WATCH_CONNECTING, coordinator.state!!.phase)
        assertTrue(coordinator.state!!.phoneReturnTrial)
        runtime.health = ReceiverHealth(true, true, 0, true, lastLifeCount = 105)
        coordinator.tick()
        assertEquals(RecoveryPhase.WATCH_MONITOR, coordinator.state!!.phase)
        assertFalse(coordinator.state!!.phoneReturnTrial)
    }

    @Test fun `delayed watch packets at the boundary cannot abort the phone trial`() = runTest {
        val runtime = Runtime()
        val coordinator = watching(runtime)
        coordinator.tryPhoneReturn("sensor", "watch")
        assertFalse(coordinator.confirmWatchFallback("sensor", "watch", 100))
        assertFalse(coordinator.confirmWatchFallback("sensor", "watch", 101))
        assertFalse(coordinator.confirmWatchFallback("sensor", "watch"))
        assertEquals(RecoveryPhase.PHONE_CONNECTING, coordinator.state!!.phase)
        assertTrue(coordinator.confirmWatchFallback("sensor", "watch", 102))
        assertEquals(RecoveryPhase.WATCH_MONITOR, coordinator.state!!.phase)
        assertFalse(coordinator.state!!.phoneReturnTrial)
    }

    @Test fun `packet delivered after the trial deadline cannot confirm a late phone connection`() = runTest {
        val runtime = Runtime()
        val coordinator = watching(runtime)
        coordinator.tryPhoneReturn("sensor", "watch")
        runtime.time = coordinator.state!!.deadlineMs
        coordinator.localPacket(102, true)
        assertEquals(RecoveryPhase.WATCH_CONNECTING, coordinator.state!!.phase)
        assertTrue(coordinator.state!!.phoneReturnTrial)
        assertTrue(runtime.calls.indexOf("stopPhone") < runtime.calls.indexOf("prepareWatch"))
    }

    @Test fun `turning automatic off preserves the already selected phone loop`() = runTest {
        val runtime = Runtime()
        val coordinator = watching(runtime)
        coordinator.tryPhoneReturn("sensor", "watch")
        coordinator.keepPhoneAfterTrial()
        assertFalse(coordinator.state!!.phoneReturnTrial)
        runtime.time = coordinator.state!!.deadlineMs
        coordinator.tick()
        assertEquals(RecoveryPhase.PHONE_CONNECTING, coordinator.state!!.phase)
        assertFalse(runtime.calls.contains("prepareWatch"))
        assertEquals(2, runtime.calls.count { it == "startPhone" })
    }

    @Test fun `duplicate trial requests or wrong sensors do not release a working owner`() = runTest {
        val runtime = Runtime()
        val coordinator = watching(runtime)
        assertFalse(coordinator.tryPhoneReturn("other-sensor", "watch"))
        assertFalse(coordinator.tryPhoneReturn("sensor", "other-watch"))
        assertTrue(runtime.calls.isEmpty())
        coordinator.tryPhoneReturn("sensor", "watch")
        runtime.calls.clear()
        assertFalse(coordinator.tryPhoneReturn("sensor", "watch"))
        assertTrue(runtime.calls.isEmpty())
    }
}
