package re.abbot.librecr.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.BmwIDriveDisplayMode
import re.abbot.librecr.app.idrive.AAIdriveResources
import re.abbot.librecr.app.idrive.BmwIDriveService
import re.abbot.librecr.app.idrive.IDriveAddonStatus
import re.abbot.librecr.app.idrive.IDriveDiagnostics
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.ui.common.SettingsSwitchRow

@Composable
fun BmwIDriveScreen(modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val status by BmwIDriveService.status.collectAsStateWithLifecycle()
    val diagnostics by IDriveDiagnostics.snapshot.collectAsStateWithLifecycle()
    var aaIdriveLaunch by remember { mutableStateOf(context.packageManager.getLaunchIntentForPackage(AAIdriveResources.PACKAGE)) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                aaIdriveLaunch = context.packageManager.getLaunchIntentForPackage(AAIdriveResources.PACKAGE)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard(stringResource(R.string.idrive_title)) {
            SettingsSwitchRow(stringResource(R.string.idrive_enable), settings.bmwIDriveEnabled) { enabled ->
                scope.launch { GlucoRitm.settings.setBmwIDriveEnabled(enabled) }
            }
            Text(stringResource(R.string.idrive_description), style = MaterialTheme.typography.bodyMedium)
            val statusText = when {
                !settings.bmwIDriveEnabled -> R.string.idrive_disabled
                aaIdriveLaunch == null -> R.string.idrive_install
                else -> when (status) {
                    IDriveAddonStatus.AUTHENTICATING -> R.string.idrive_authenticating
                    IDriveAddonStatus.CONNECTING -> R.string.idrive_connecting
                    IDriveAddonStatus.CONNECTED -> R.string.idrive_connected
                    IDriveAddonStatus.RESOURCES_MISSING -> R.string.idrive_resources_missing
                    IDriveAddonStatus.RETRYING -> R.string.idrive_retrying
                    else -> R.string.idrive_waiting
                }
            }
            Text(stringResource(statusText), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge)
            aaIdriveLaunch?.let { intent ->
                FilledTonalButton(onClick = { context.startActivity(intent) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.idrive_open_aaidrive))
                }
            }
        }
        if (settings.bmwIDriveEnabled) SectionCard(stringResource(R.string.idrive_display_mode)) {
            Column(Modifier.selectableGroup()) {
                listOf(
                    BmwIDriveDisplayMode.AUTO to R.string.idrive_display_auto,
                    BmwIDriveDisplayMode.SPLIT_SCREEN to R.string.idrive_display_split,
                    BmwIDriveDisplayMode.FULL_SCREEN to R.string.idrive_display_full,
                ).forEach { (mode, label) ->
                    val selected = settings.bmwIDriveDisplayMode == mode
                    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = {
                        scope.launch { GlucoRitm.settings.setBmwIDriveDisplayMode(mode) }
                    }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected, onClick = null)
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Text(stringResource(R.string.idrive_display_description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (settings.bmwIDriveEnabled) SectionCard(stringResource(R.string.idrive_diagnostics_title)) {
            Text(diagnostics.text(), style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("BMW iDrive", diagnostics.text()))
            }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.idrive_copy_diagnostics))
            }
        }
        SectionCard(stringResource(R.string.idrive_setup_title)) {
            Text(stringResource(R.string.idrive_setup), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.idrive_limits), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
