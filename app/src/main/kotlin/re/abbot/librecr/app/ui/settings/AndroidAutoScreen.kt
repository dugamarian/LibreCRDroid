package re.abbot.librecr.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.R
import re.abbot.librecr.app.auto.AndroidAutoGlucoseNotifications
import re.abbot.librecr.app.auto.AndroidAutoNotificationFactory
import re.abbot.librecr.app.auto.AutoNotificationDelivery
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.ui.common.SettingsSwitchRow
import re.abbot.librecr.app.ui.common.TrendArrow

@Composable
fun AndroidAutoScreen(modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val projected by AndroidAutoGlucoseNotifications.isProjected.collectAsStateWithLifecycle()
    val delivery by AndroidAutoGlucoseNotifications.deliveryState.collectAsStateWithLifecycle()
    var enabled by remember(settings.androidAutoNotificationsEnabled) { mutableStateOf(settings.androidAutoNotificationsEnabled) }
    var notificationsAllowed by remember { mutableStateOf(AndroidAutoNotificationFactory.notificationsAllowed(context)) }
    var resending by remember { mutableStateOf(false) }

    fun persist(value: Boolean) {
        enabled = value
        scope.launch { GlucoRitm.settings.setAndroidAutoNotificationsEnabled(value) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = AndroidAutoNotificationFactory.notificationsAllowed(context)
        if (granted) persist(true)
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = AndroidAutoNotificationFactory.notificationsAllowed(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(stringResource(R.string.title_android_auto)) {
            SettingsSwitchRow(stringResource(R.string.auto_notification_enable), enabled) { value ->
                if (value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else persist(value)
            }
            Text(stringResource(R.string.auto_notification_description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(when {
                !enabled -> R.string.auto_notification_disabled
                projected -> R.string.auto_notification_connected
                else -> R.string.auto_notification_waiting
            }), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (!notificationsAllowed) {
                Text(stringResource(R.string.auto_notification_permission), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (enabled && projected && notificationsAllowed) {
                Text(stringResource(when (delivery) {
                    AutoNotificationDelivery.POSTED -> R.string.auto_notification_posted
                    AutoNotificationDelivery.DISMISSED -> R.string.auto_notification_hidden
                    AutoNotificationDelivery.BLOCKED -> R.string.auto_notification_permission
                    AutoNotificationDelivery.FAILED -> R.string.auto_notification_failed
                    else -> R.string.auto_notification_sending
                }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(
                    enabled = !resending,
                    onClick = {
                        resending = true
                        scope.launch {
                            try { AndroidAutoGlucoseNotifications.resend(context.applicationContext) }
                            finally { resending = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.auto_notification_resend)) }
            }
            FilledTonalButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.auto_notification_system_settings)) }
        }

        SectionCard(stringResource(R.string.auto_notification_setup)) {
            Text(stringResource(R.string.auto_notification_sideload), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.auto_notification_car_settings), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(stringResource(R.string.auto_notification_preview)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.widget_brand), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("112", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        TrendArrow("RISING", MaterialTheme.colorScheme.primary, 32.dp)
                        Spacer(Modifier.weight(1f))
                        Text("mg/dL", style = MaterialTheme.typography.labelLarge)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Δ +3/min", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.age_minutes, 1), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text(stringResource(R.string.auto_notification_limits), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
