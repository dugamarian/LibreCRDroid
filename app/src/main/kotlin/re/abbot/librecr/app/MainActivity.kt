package re.abbot.librecr.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import re.abbot.librecr.app.nfc.AndroidLibre3NfcReader
import re.abbot.librecr.app.ui.GlucoRitmApp
import re.abbot.librecr.app.ui.theme.GlucoRitmTheme
import re.abbot.librecr.app.wear.weather.PhoneWeather

/**
 * Thin host: owns the NFC reader and hands off to the Compose navigation shell. AppCompatActivity
 * is used so per-app language selection (AppCompatDelegate.setApplicationLocales) works on all SDKs.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var nfcReader: AndroidLibre3NfcReader
    private var standbyKeepsScreenOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GlucoRitm.init(this)
        nfcReader = AndroidLibre3NfcReader(this)
        setContent {
            GlucoRitmTheme {
                GlucoRitmApp(nfcReader, onKeepScreenOnChange = ::setStandbyKeepScreenOn)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (standbyKeepsScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch { PhoneWeather.refresh(this@MainActivity, foreground = true) }
    }

    override fun onPause() {
        nfcReader.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onDestroy() {
        setStandbyKeepScreenOn(false)
        super.onDestroy()
    }

    private fun setStandbyKeepScreenOn(enabled: Boolean) {
        standbyKeepsScreenOn = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
