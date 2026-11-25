package re.abbot.librecr.app.live

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.core.graphics.drawable.toBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.ongoing.OngoingActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.WearAppearanceSettings
import re.abbot.librecr.app.wear.WearDataSync
import java.io.File

/** Runs against the actual notification manager, including promotion flags and dismissal. */
class WearLiveUpdatesNotificationTest {
    @Test
    fun relayedReadingsUpdateOnePromotableCardAndRespectUnpinAndExpiry() {
        // These tests replace sensor state with synthetic readings; only run on an emulator.
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gwear"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
            ).close()
        }
        LibreCR.init(context)
        runBlocking {
            LibreCR.store.clearSession()
            LibreCR.appearance.save(WearAppearanceSettings())
        }
        LibreCR.manager.clearSensorStatus()
        WearLiveUpdatesNotifier.setEnabled(context, true)
        val manager = context.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        // The first minute label is due 12 s later (70 s after the sample), with no new packet.
        val firstLifeCount = maxOf(20_000, (LibreCR.manager.glucose.value?.lifeCount ?: 0) + 1)
        val first = SensorStateStore.LastGlucose(firstLifeCount, 120, "RISING", now - 58_000L, 2.0)
        LibreCR.manager.acceptRemoteGlucose(first)
        await { manager.activeNotifications.any { it.id == 1 && it.notification.title() == "↗ 120 Δ +2" } }
        val notification = manager.activeNotifications.single { it.id == 1 }.notification
        assertNotNull(notification.smallIcon)
        // The card leaves age to Wear's chronometer; only the chip carries minutes.
        assertEquals("mg/dL · Δ +2 mg/dL/min", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertTrue(notification.timeoutAfter in 1L..330_000L)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        if (Build.VERSION.SDK_INT >= 36) assertTrue(notification.hasPromotableCharacteristics())
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        if (Build.VERSION.SDK_INT >= 36) {
            assertEquals("120  ·  +2 · <1 m.", notification.extras.getString("android.shortCriticalText"))
        }
        val status = requireNotNull(OngoingActivity.recoverOngoingActivity(context)?.status)
        val elapsedNow = SystemClock.elapsedRealtime()
        val timeNow = status.getText(context, elapsedNow).toString()
        val timeLater = status.getText(context, elapsedNow + 65_000L).toString()
        assertTrue(timeNow.startsWith("↗ 120 Δ +2 · "))
        assertFalse("Wear must advance the age without the app posting again", timeNow == timeLater)
        assertTrue(status.getNextChangeTimeMillis(elapsedNow) < Long.MAX_VALUE)
        val bitmap = requireNotNull(notification.getLargeIcon().loadDrawable(context)).toBitmap()
        assertEquals(96, bitmap.width)
        assertEquals(96, bitmap.height)
        File(context.cacheDir, "wear-live-icon.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // 70 s after the sample the app reposts the chip's minutes; the card keeps Wear's timer.
        if (Build.VERSION.SDK_INT >= 36) {
            await(15_000L) {
                manager.activeNotifications.any {
                    it.id == 1 && it.notification.extras.getString("android.shortCriticalText") == "120  ·  +2 · 1 m."
                }
            }
        }
        val aged = manager.activeNotifications.single { it.id == 1 }.notification
        assertEquals(first.receivedAtMs, aged.`when`)
        assertTrue(aged.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(bitmap.sameAs(requireNotNull(aged.getLargeIcon().loadDrawable(context)).toBitmap()))

        // The action's real PendingIntent must persist opt-out; subsequent readings cannot repin it.
        notification.actions.single().actionIntent.send()
        await { !WearLiveUpdatesNotifier.enabled.value && manager.activeNotifications.none { it.id == 1 } }
        assertFalse(context.getSharedPreferences("wear_live_updates", Context.MODE_PRIVATE).getBoolean("enabled", true))
        LibreCR.manager.acceptRemoteGlucose(first.copy(lifeCount = first.lifeCount + 1, mgDL = 122))
        Thread.sleep(250L)
        assertTrue(manager.activeNotifications.none { it.id == 1 })

        WearLiveUpdatesNotifier.setEnabled(context, true)
        await { manager.activeNotifications.any { it.id == 1 && it.notification.title() == "↗ 122 Δ +2" } }
        // A non-numeric live packet must hide the cached valid reading immediately.
        LibreCR.manager.acceptRemoteGlucoseUnavailable(WearDataSync.GlucoseUnavailable(
            lifeCount = first.lifeCount + 2, trend = "UNKNOWN", receivedAtMs = now, reason = "test", detail = "",
        ))
        await { manager.activeNotifications.any { it.id == 1 && it.notification.title() == "NA" } }
        val unavailable = manager.activeNotifications.single { it.id == 1 }.notification
        assertEquals(now, unavailable.`when`)
        assertTrue(unavailable.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertTrue(unavailable.timeoutAfter in 1L..330_000L)
        if (Build.VERSION.SDK_INT >= 36) {
            assertEquals("NA · <1 m.", unavailable.extras.getString("android.shortCriticalText"))
            assertTrue(unavailable.hasPromotableCharacteristics())
        }
        val naStatus = requireNotNull(OngoingActivity.recoverOngoingActivity(context)?.status)
        val naNow = SystemClock.elapsedRealtime()
        assertTrue(naStatus.getText(context, naNow).toString().startsWith("NA · "))
        assertFalse(naStatus.getText(context, naNow).toString() ==
            naStatus.getText(context, naNow + 65_000L).toString())
        LibreCR.manager.acceptRemoteGlucose(first.copy(lifeCount = first.lifeCount + 3, receivedAtMs = now - 330_001L))
        Thread.sleep(250L)
        assertTrue(manager.activeNotifications.none { it.id == 1 })

        // Leave a fresh sample for manual inspection of the round-screen notification and icon.
        LibreCR.manager.acceptRemoteGlucose(first.copy(lifeCount = first.lifeCount + 4, receivedAtMs = System.currentTimeMillis()))
        await { manager.activeNotifications.any { it.id == 1 } }
        assertEquals(1, manager.activeNotifications.count { it.id == 1 })
    }

    @Test
    fun systemTimeoutAndItsDelayedCallbackDoNotDisableRecoveredLiveUpdates() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gwear"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
            ).close()
        }
        LibreCR.init(context)
        runBlocking {
            LibreCR.store.clearSession()
            LibreCR.appearance.save(WearAppearanceSettings())
        }
        LibreCR.manager.clearSensorStatus()
        WearLiveUpdatesNotifier.setEnabled(context, true)
        val manager = context.getSystemService(NotificationManager::class.java)
        val preferences = context.getSharedPreferences("wear_live_updates", Context.MODE_PRIVATE)
        val lifeCount = maxOf(20_000, (LibreCR.manager.glucose.value?.lifeCount ?: 0) + 1)
        val old = SensorStateStore.LastGlucose(lifeCount, 120, "RISING", System.currentTimeMillis() - 327_000L, 2.0)
        val probeId = 9_001
        try {
            LibreCR.manager.acceptRemoteGlucose(old)
            await { manager.activeNotifications.any { it.id == 1 && it.notification.`when` == old.receivedAtMs } }
            val expiring = manager.activeNotifications.single { it.id == 1 }.notification
            assertTrue(expiring.timeoutAfter in 1L..3_000L)
            val oldDismissal = requireNotNull(expiring.deleteIntent)
            assertNotEquals(expiring.actions.single().actionIntent, oldDismissal)

            // App-side stale cleanup can win the race against the OS for ID 1. A separate ID
            // exercises Android's real timeout/deleteIntent path with the card's own callback.
            val probe = Notification.Builder(context, "librecr_sensor")
                .setSmallIcon(expiring.smallIcon)
                .setContentTitle("Live Updates timeout regression")
                .setDeleteIntent(oldDismissal)
                .setTimeoutAfter(expiring.timeoutAfter + 250L)
                .build()
            manager.notify(probeId, probe)
            await { manager.activeNotifications.any { it.id == probeId } }
            await { manager.activeNotifications.none { it.id == probeId || it.id == 1 } }
            Thread.sleep(250L)
            instrumentation.waitForIdleSync()
            assertTrue("Automatic timeout must preserve opt-in", WearLiveUpdatesNotifier.enabled.value)
            assertTrue(preferences.getBoolean("enabled", false))

            // Recovery must repin without any user action or Bluetooth/service restart.
            val recovered = old.copy(lifeCount = lifeCount + 1, mgDL = 125, receivedAtMs = System.currentTimeMillis())
            LibreCR.manager.acceptRemoteGlucose(recovered)
            await { manager.activeNotifications.any { it.id == 1 && it.notification.title() == "↗ 125 Δ +2" } }
            val current = manager.activeNotifications.single { it.id == 1 }.notification
            val currentDismissal = requireNotNull(current.deleteIntent)
            assertNotEquals("A newer card cannot overwrite an old callback's deadline", oldDismissal, currentDismissal)
            if (Build.VERSION.SDK_INT >= 36) assertTrue(current.hasPromotableCharacteristics())

            // Even a queued old timeout delivered after recovery must leave the new card pinned.
            oldDismissal.send()
            Thread.sleep(250L)
            instrumentation.waitForIdleSync()
            assertTrue(WearLiveUpdatesNotifier.enabled.value)
            assertTrue(preferences.getBoolean("enabled", false))
            assertTrue(manager.activeNotifications.any { it.id == 1 })

            // A genuine dismissal of the fresh card still persists opt-out across new readings.
            currentDismissal.send()
            await { !WearLiveUpdatesNotifier.enabled.value && manager.activeNotifications.none { it.id == 1 } }
            assertFalse(preferences.getBoolean("enabled", true))
            LibreCR.manager.acceptRemoteGlucose(recovered.copy(lifeCount = lifeCount + 2, mgDL = 126))
            Thread.sleep(250L)
            assertTrue(manager.activeNotifications.none { it.id == 1 })
        } finally {
            manager.cancel(probeId)
        }
    }

    private fun Notification.title(): String = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun await(timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Notification state did not arrive within $timeoutMs ms" }
            Thread.sleep(50L)
        }
    }
}
