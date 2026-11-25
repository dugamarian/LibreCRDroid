package re.abbot.librecr.app.live

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore

class WearLiveUpdateClockTest {
    @Test
    fun screenWakeCatchesUpMinutesWithoutAnotherReadingOrTheOldDelay() = runBlocking {
        // This test changes screen state; never interrupt the physical watch.
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gwear"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val receivedAt = System.currentTimeMillis()
        val now = AtomicLong(receivedAt + 10_000L)
        val reading = SensorStateStore.LastGlucose(100, 120, "RISING", receivedAt, 2.0)
        val content = WearLiveUpdateContent.from(null, reading, GlucoseUnit.MG_DL, null, nowMs = now.get())
        val renders = Channel<String>(Channel.UNLIMITED)
        val waits = Channel<Long>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) {
            collectWearLiveUpdates(
                modes = flowOf(WearLiveUpdateMode.LIVE),
                content = { flowOf(content) },
                refresh = { renders.trySend(content.ageLabel(now.get())) },
                nowMs = now::get,
                // Model a delay still waiting for CPU time while the wall clock has advanced.
                wait = { waits.trySend(it); awaitCancellation() },
                refreshSignals = { wearLiveUpdateClockSignals(context) },
            )
        }
        try {
            assertEquals("<1 m.", withTimeout(5_000L) { renders.receive() })
            // The first label change waits 10 s past the minute for a slightly late reading.
            assertEquals(60_000L, withTimeout(5_000L) { waits.receive() })
            fun shell(command: String) {
                instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                }
            }
            shell("input keyevent KEYCODE_SLEEP")
            now.set(receivedAt + 135_000L)
            shell("input keyevent KEYCODE_WAKEUP")
            withTimeout(5_000L) {
                while (renders.receive() != "2 m.") { /* ignore a duplicate wake signal */ }
                while (waits.receive() != 45_000L) { /* ignore the earlier wait */ }
            }
            assertEquals(receivedAt, content.observedAtMs)
        } finally {
            job.cancelAndJoin()
            renders.close()
            waits.close()
        }
    }

    @Test
    fun chipMinutesAdvanceWhileTheStaticBitmapKeepsSampleTime() {
        val receivedAt = System.currentTimeMillis()
        val reading = SensorStateStore.LastGlucose(100, 120, "RISING", receivedAt, 2.0)
        val fresh = WearLiveUpdateContent.from(null, reading, GlucoseUnit.MG_DL, null, nowMs = receivedAt + 10_000L)
        val aged = WearLiveUpdateContent.from(null, reading, GlucoseUnit.MG_DL, null, nowMs = receivedAt + 135_000L)
        assertEquals("120  ·  +2 · <1 m.", fresh.chipText(receivedAt + 10_000L))
        assertEquals("120  ·  +2 · 2 m.", aged.chipText(receivedAt + 135_000L))
        assertTrue(WearLiveUpdateIcon.create(fresh).sameAs(WearLiveUpdateIcon.create(aged)))
    }
}
