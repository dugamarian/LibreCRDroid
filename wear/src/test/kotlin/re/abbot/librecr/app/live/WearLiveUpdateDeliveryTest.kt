package re.abbot.librecr.app.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import re.abbot.librecr.app.WEAR_GLUCOSE_DISPLAY_HOLD_MS
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import java.time.ZoneId
import java.time.ZoneOffset

class WearLiveUpdateDeliveryTest {
    private val receivedAt = 1_000_000L
    private val reading = SensorStateStore.LastGlucose(100, 120, "RISING", receivedAt, 2.0)

    private fun content(value: SensorStateStore.LastGlucose = reading, nowMs: Long = value.receivedAtMs) =
        WearLiveUpdateContent.from(null, value, GlucoseUnit.MG_DL, null, nowMs = nowMs)

    private class Harness(initialMode: WearLiveUpdateMode, initialTime: Long) {
        val modes = MutableStateFlow(initialMode)
        val values = MutableSharedFlow<WearLiveUpdateContent>(replay = 1, extraBufferCapacity = 8)
        val timer = Channel<Unit>()
        val clockSignals = MutableSharedFlow<Unit>()
        var nowMs = initialTime
        var subscriptions = 0
        var emissions = 0
        var refreshes = 0
        var clockSubscriptions = 0
        var latestContent: WearLiveUpdateContent? = null
        val postedKeys = mutableListOf<WearLiveUpdateNotificationKey>()
        val waits = mutableListOf<Long>()

        fun start(scope: CoroutineScope): Job = scope.launch {
            collectWearLiveUpdates(
                modes,
                content = {
                    flow {
                        subscriptions++
                        try {
                            emitAll(values.onEach {
                                emissions++
                                latestContent = it
                            })
                        }
                        finally { subscriptions-- }
                    }
                },
                refresh = {
                    refreshes++
                    latestContent?.notificationKey(
                        pinned = modes.value == WearLiveUpdateMode.LIVE,
                        foreground = modes.value == WearLiveUpdateMode.SERVICE,
                        nowMs = nowMs,
                    )?.let { key -> if (postedKeys.lastOrNull() != key) postedKeys += key }
                },
                nowMs = { nowMs },
                wait = { waits += it; timer.receive() },
                refreshSignals = {
                    flow {
                        clockSubscriptions++
                        try { emitAll(clockSignals) }
                        finally { clockSubscriptions-- }
                    }
                },
            )
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(1_000L) { while (!condition()) yield() }
    }

    @Test
    fun `hidden relay display neither subscribes to readings nor starts a timer`() = runBlocking {
        val h = Harness(WearLiveUpdateMode.HIDDEN, receivedAt)
        val job = h.start(this)
        try {
            await { h.refreshes == 1 }
            h.values.emit(content())
            repeat(5) { yield() }
            assertEquals(0, h.subscriptions)
            assertEquals(0, h.clockSubscriptions)
            assertEquals(0, h.emissions)
            assertEquals(1, h.refreshes)
            assertEquals(emptyList<Long>(), h.waits)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `unpin cancels the pending timer and subscriptions and repin reads the latest value`() = runBlocking {
        val h = Harness(WearLiveUpdateMode.LIVE, receivedAt)
        h.values.emit(content())
        val job = h.start(this)
        try {
            await { h.waits.size == 1 }
            h.modes.value = WearLiveUpdateMode.HIDDEN
            await { h.subscriptions == 0 && h.clockSubscriptions == 0 && h.refreshes == 2 }
            h.nowMs += 60_000L
            h.values.emit(content(reading.copy(lifeCount = 101, mgDL = 126, receivedAtMs = h.nowMs)))
            repeat(5) { yield() }
            assertEquals(2, h.refreshes)
            assertEquals(1, h.waits.size)
            h.modes.value = WearLiveUpdateMode.LIVE
            await { h.refreshes == 3 && h.waits.size == 2 }
            assertEquals(1, h.subscriptions)
            assertEquals(70_000L, h.waits.last())
            assertEquals(126, h.latestContent?.reading?.mgDL)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `ordinary foreground notification schedules only expiry without minute ticks`() = runBlocking {
        val h = Harness(WearLiveUpdateMode.SERVICE, receivedAt + 10_000L)
        h.values.emit(content())
        val job = h.start(this)
        try {
            await { h.waits.size == 1 }
            assertEquals(WEAR_GLUCOSE_DISPLAY_HOLD_MS - 10_000L, h.waits.single())
            h.nowMs = receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS
            h.timer.send(Unit)
            await { h.refreshes == 2 }
            repeat(5) { yield() }
            assertEquals(1, h.waits.size)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `persistence of the same effective reading does not repost or restart the timer`() = runBlocking {
        val h = Harness(WearLiveUpdateMode.LIVE, receivedAt)
        h.values.emit(content())
        val job = h.start(this)
        try {
            await { h.waits.size == 1 }
            h.values.emit(content())
            await { h.emissions == 2 }
            repeat(5) { yield() }
            assertEquals(1, h.refreshes)
            assertEquals(1, h.waits.size)
            // Same numeric value is still a new observation and must reset age immediately.
            h.nowMs += 60_000L
            h.values.emit(content(reading.copy(lifeCount = 101, receivedAtMs = h.nowMs)))
            await { h.refreshes == 2 && h.waits.size == 2 }
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `disabled local receiver keeps the ordinary notification rather than hiding the service`() {
        assertEquals(WearLiveUpdateMode.SERVICE, WearLiveUpdateMode.from(false, true))
        assertEquals(WearLiveUpdateMode.HIDDEN, WearLiveUpdateMode.from(false, false))
        assertEquals(WearLiveUpdateMode.LIVE, WearLiveUpdateMode.from(true, true))
    }

    @Test
    fun `minute age changes notification identity only for the live display`() {
        val c = content()
        assertEquals(c.notificationKey(false, true, receivedAt),
            c.notificationKey(false, true, receivedAt + 60_000L))
        assertNotEquals(c.notificationKey(true, true, receivedAt),
            c.notificationKey(true, true, receivedAt + 60_000L))
        // Seconds inside the same minute never repost the chip.
        assertEquals(c.notificationKey(true, true, receivedAt),
            c.notificationKey(true, true, receivedAt + 59_999L))
    }

    @Test
    fun `watch time zone change invalidates the static sample clock label`() {
        val c = content()
        val utc = c.notificationKey(true, false, receivedAt, ZoneOffset.UTC)
        val brussels = c.notificationKey(true, false, receivedAt, ZoneId.of("Europe/Brussels"))
        assertEquals("00:16", utc.observationTime)
        assertEquals("01:16", brussels.observationTime)
        assertNotEquals(utc, brussels)
        assertEquals(utc.observedAtMs, brussels.observedAtMs)
    }

    @Test
    fun `waking after sleep refreshes age without a reading or the old delay completing`() = runBlocking {
        val h = Harness(WearLiveUpdateMode.LIVE, receivedAt + 10_000L)
        h.values.emit(content())
        val job = h.start(this)
        try {
            await { h.waits.size == 1 && h.clockSignals.subscriptionCount.value == 1 }
            assertEquals(0L, h.postedKeys.last().ageMinutes)
            // The first label change waits 10 s past the minute for a slightly late reading.
            assertEquals(60_000L, h.waits.last())
            // Wall time includes sleep, but the app's old delay has not completed.
            h.nowMs = receivedAt + 135_000L
            h.clockSignals.emit(Unit)
            await { h.waits.size == 2 }
            assertEquals(1, h.emissions)
            assertEquals(2L, h.postedKeys.last().ageMinutes)
            assertEquals(45_000L, h.waits.last())
            h.nowMs = receivedAt + 180_000L
            h.timer.send(Unit)
            await { h.waits.size == 3 }
            assertEquals(3L, h.postedKeys.last().ageMinutes)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `clock signals in the same minute do not repost and do not duplicate subscriptions`() = runBlocking {
        val h = Harness(WearLiveUpdateMode.LIVE, receivedAt)
        h.values.emit(content())
        val job = h.start(this)
        try {
            await { h.waits.size == 1 && h.clockSignals.subscriptionCount.value == 1 }
            repeat(3) { index ->
                h.nowMs += 5_000L
                h.clockSignals.emit(Unit)
                await { h.waits.size == index + 2 }
            }
            assertEquals(1, h.postedKeys.size)
            assertEquals(1, h.subscriptions)
            assertEquals(1, h.clockSubscriptions)
            assertEquals(55_000L, h.waits.last())
            val beforeHide = h.refreshes
            h.modes.value = WearLiveUpdateMode.HIDDEN
            await { h.subscriptions == 0 && h.clockSubscriptions == 0 && h.refreshes == beforeHide + 1 }
            val refreshes = h.refreshes
            h.clockSignals.emit(Unit)
            repeat(5) { yield() }
            assertEquals(refreshes, h.refreshes)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `waking after expiry checks immediately and leaves no further delay`() = runBlocking {
        val h = Harness(WearLiveUpdateMode.LIVE, receivedAt)
        h.values.emit(content())
        val job = h.start(this)
        try {
            await { h.waits.size == 1 && h.clockSignals.subscriptionCount.value == 1 }
            h.nowMs = receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS
            h.clockSignals.emit(Unit)
            await { h.refreshes == 2 }
            repeat(5) { yield() }
            assertEquals(1, h.waits.size)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `new observations unpinning and receiver handoff cannot be suppressed as duplicates`() {
        val c = content()
        val key = c.notificationKey(true, true, receivedAt)
        assertNotEquals(key, content(reading.copy(receivedAtMs = receivedAt + 1L))
            .notificationKey(true, true, receivedAt))
        assertNotEquals(key, c.notificationKey(false, true, receivedAt))
        assertNotEquals(key, c.notificationKey(true, false, receivedAt))
    }

    @Test
    fun `stale foreground value expires even when live updates are disabled`() {
        val expired = content(nowMs = receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS)
        assertEquals(WearLiveUpdateContent.Status.UNAVAILABLE, expired.status)
        assertEquals("--", expired.value)
        assertNull(expired.nextRefreshDelayMs(receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS, showAge = false))
        assertNotEquals(content().notificationKey(false, true, receivedAt),
            expired.notificationKey(false, true, receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS))
    }
}
