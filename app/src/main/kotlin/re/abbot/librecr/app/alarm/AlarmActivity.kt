package re.abbot.librecr.app.alarm

import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.ui.theme.LibreCRTheme

/** Full-screen, lock-screen-visible glucose alarm with Snooze / Stop. */
class AlarmActivity : ComponentActivity() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val kind = runCatching {
            AlarmKind.valueOf(intent.getStringExtra(GlucoseAlarmManager.EXTRA_KIND) ?: "")
        }.getOrNull()
        if (kind == null) {
            finish()
            return
        }
        val mgDl = intent.getIntExtra(GlucoseAlarmManager.EXTRA_MGDL, 0)
        val unit = GlucoseUnit.fromName(intent.getStringExtra(GlucoseAlarmManager.EXTRA_UNIT))
        val snoozeMin = intent.getIntExtra(GlucoseAlarmManager.EXTRA_SNOOZE_MIN, 30)
        startFeedback()

        setContent {
            LibreCRTheme(dynamicColor = false) {
                AlarmScreen(
                    kind = kind,
                    mgDl = mgDl,
                    unit = unit,
                    snoozeMinutes = snoozeMin,
                    onSnooze = {
                        stopFeedback()
                        GlucoseAlarmManager.snooze(this, kind, snoozeMin)
                        finish()
                    },
                    onStop = {
                        stopFeedback()
                        GlucoseAlarmManager.stop(this, kind)
                        finish()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        stopFeedback()
        super.onDestroy()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun startFeedback() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }
        runCatching {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        }
    }

    private fun stopFeedback() {
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        ringtone = null
        vibrator = null
    }
}

@Composable
private fun AlarmScreen(
    kind: AlarmKind,
    mgDl: Int,
    unit: GlucoseUnit,
    snoozeMinutes: Int,
    onSnooze: () -> Unit,
    onStop: () -> Unit,
) {
    val accent = when (kind) {
        AlarmKind.URGENT_LOW -> Color(0xFFD32F2F)
        AlarmKind.LOW, AlarmKind.PERSISTENT_LOW -> Color(0xFFF4511E)
        AlarmKind.HIGH, AlarmKind.PERSISTENT_HIGH -> Color(0xFFF9A825)
    }
    val title = stringResource(GlucoseAlarmManager.titleRes(kind))
    // In landscape (the standby-charger case) the 132sp value plus two 64dp buttons exceed the
    // screen height, so the content is compacted and scrollable — the buttons must stay reachable.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val valueSize = if (landscape) 96.sp else 132.sp
    val gap = if (landscape) 20.dp else 48.dp
    Surface(Modifier.fillMaxSize(), color = accent.copy(alpha = 0.12f)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = accent)
            Text(unit.format(mgDl), fontSize = valueSize, fontWeight = FontWeight.Bold, color = accent)
            Text(unit.label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(gap))
            Button(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
            ) {
                Text(stringResource(R.string.alarm_action_snooze_min, snoozeMinutes), fontSize = 20.sp)
            }
            Spacer(Modifier.size(14.dp))
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Text(stringResource(R.string.alarm_action_stop), fontSize = 20.sp)
            }
        }
    }
}
