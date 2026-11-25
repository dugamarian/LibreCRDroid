package re.abbot.librecr.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.glucoseBroadcastDestinations
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.ui.common.SettingsSwitchRow

@Composable
fun GlucoseBroadcastScreen(modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current.glucoseBroadcast
    val scope = rememberCoroutineScope()
    var destinations by rememberSaveable(settings.destinationPackages) { mutableStateOf(settings.destinationPackages) }
    val parsed = glucoseBroadcastDestinations(destinations)
    val changed = parsed?.joinToString(" ") != settings.destinationPackages

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(stringResource(R.string.broadcast_title)) {
            SettingsSwitchRow(stringResource(R.string.broadcast_enable), settings.enabled) { enabled ->
                scope.launch { LibreCR.settings.setGlucoseBroadcastEnabled(enabled) }
            }
            Text(stringResource(R.string.broadcast_description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.broadcast_sharing), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard(stringResource(R.string.broadcast_destinations_title)) {
            OutlinedTextField(
                value = destinations,
                onValueChange = { destinations = it },
                label = { Text(stringResource(R.string.broadcast_packages)) },
                supportingText = {
                    Text(stringResource(if (parsed == null) R.string.broadcast_packages_error else R.string.broadcast_packages_hint))
                },
                isError = parsed == null,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            FilledTonalButton(
                enabled = parsed != null && changed,
                onClick = { scope.launch { LibreCR.settings.setGlucoseBroadcastDestinations(destinations) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.broadcast_save)) }
            Text(stringResource(R.string.broadcast_destinations_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard(stringResource(R.string.broadcast_setup_title)) {
            Text(stringResource(R.string.broadcast_setup), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
