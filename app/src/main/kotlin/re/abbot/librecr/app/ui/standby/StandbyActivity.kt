package re.abbot.librecr.app.ui.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.data.AppSettings
import re.abbot.librecr.app.ui.theme.GlucoRitmTheme

/**
 * Full-screen standby (nightstand) shown over the lock screen while the phone is on a wireless
 * charger. Launched by [StandbyController]; finishes when the phone leaves landscape or the dock.
 */
class StandbyActivity : ComponentActivity() {
    private var standbyAccepted = false
    private var ambientMode by mutableStateOf(StandbyAmbientMode.WAITING)
    private val ambientLight by lazy { StandbyAmbientLight(this) { ambientMode = it } }

    private val eligibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!StandbyController.isEligible(this@StandbyActivity)) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        standbyAccepted = StandbyController.tryShow(this)
        if (!standbyAccepted) {
            finish()
            return
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawableResource(android.R.color.black)
        window.decorView.setBackgroundColor(AndroidColor.BLACK)
        // Force-dark on this device can invert explicitly drawn blacks (see the AOD/floating
        // overlay fix); the OLED background must stay true black, so opt the whole window out.
        window.decorView.isForceDarkAllowed = false
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContent {
            val settings by GlucoRitm.settings.settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
            val mode = ambientMode
            if (mode != StandbyAmbientMode.WAITING) {
                GlucoRitmTheme(dark = true, dynamicColor = false) {
                    StandbyScreen(settings = settings, ambientMode = mode)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!standbyAccepted || !StandbyController.isEligible(this)) {
            finish()
            return
        }
        ContextCompat.registerReceiver(
            this,
            eligibilityReceiver,
            IntentFilter(StandbyController.ACTION_ELIGIBILITY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ambientLight.start()
    }

    override fun onPause() {
        if (standbyAccepted) {
            ambientLight.stop()
            runCatching { unregisterReceiver(eligibilityReceiver) }
        }
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        if (standbyAccepted) StandbyController.markHidden()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
