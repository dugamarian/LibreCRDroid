package re.abbot.librecr.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.wear.AutomaticSensorHandoff
import re.abbot.librecr.protocol.connection.PhonePriorityPolicy

/** Uses a synthetic, unreachable sensor only on an emulator; never changes a physical receiver. */
class PhonePriorityStandbyTest {
    @Test fun backgroundTakeoverReusesPreparedForegroundService() = runBlocking {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gwear"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // BOOT_COMPLETED can initialize the watch graph while the first DataStore write suspends.
        // Seed ownership before any blocking/suspending setup, and run this class in a fresh process.
        val prefs = context.getSharedPreferences("librecr_automatic_handoff", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("enabled", true).putBoolean("preferPhone", true)
            .putBoolean("priorityArmed", true).putBoolean("priorityRequested", true).putBoolean("released", true)
            .putString("phoneNodeId", "absent-test-phone")
            .putLong("releaseDeadlineWall", System.currentTimeMillis() + 180_000L).commit()
        val listeners = context.packageManager.queryIntentServices(Intent("com.google.android.gms.wearable.CAPABILITY_CHANGED")
            .setData(Uri.parse("wear://*/${PhonePriorityPolicy.PHONE_CAPABILITY}"))
            .setPackage(context.packageName), 0)
        assertTrue("Phone presence changes must wake the manifest listener",
            listeners.any { it.serviceInfo.name.endsWith("LibreWearListenerService") })
        for (permission in listOf("BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "POST_NOTIFICATIONS")) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.$permission",
            ).close()
        }
        val store = SensorStateStore(context)
        store.saveSession(ImportedSession("02:00:00:00:00:01", null, ByteArray(4), ByteArray(16),
            null, 123, "standby-test", 60, 20_160))
        store.setAutoConnectEnabled(false)
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            GlucoRitm.init(context)
            assertTrue("Fixture must seed standby ownership before watch initialization; run in a fresh process",
                AutomaticSensorHandoff.phonePriorityServiceRequired)
            assertTrue("Standby must be foreground before takeover is armed", SensorForegroundService.prepareStandby(context))
            assertEquals(ConnectionState.IDLE, GlucoRitm.manager.state.value)
            assertForeground(context)

            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            await {
                var resumed = true
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).isNotEmpty()
                }
                !resumed
            }
            val noServiceLaunches = object : ContextWrapper(context) {
                override fun startForegroundService(service: Intent): ComponentName? =
                    throw AssertionError("Takeover attempted a new foreground-service launch from the background")
                override fun startService(service: Intent): ComponentName? =
                    throw AssertionError("Takeover attempted a new service launch from the background")
            }
            prefs.edit().putBoolean("released", false).commit()
            GlucoRitm.store.setAutoConnectEnabled(true)
            assertTrue(SensorForegroundService.start(noServiceLaunches, allowCandidateFirstPair = true))
            await { GlucoRitm.manager.state.value in setOf(ConnectionState.CONNECTING, ConnectionState.SCANNING, ConnectionState.RECONNECTING) }
            assertForeground(context)
        } finally {
            AutomaticSensorHandoff.cancel()
            GlucoRitm.store.setAutoConnectEnabled(false)
            GlucoRitm.manager.stopAndJoin()
            SensorForegroundService.stop(context)
            GlucoRitm.store.clearSession()
            prefs.edit().clear().commit()
        }
    }

    private fun assertForeground(context: Context) {
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == 1 }
        assertTrue(notification.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for standby/connection state", condition())
    }
}
