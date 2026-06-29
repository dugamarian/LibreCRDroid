package re.abbot.librecr.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import re.abbot.librecr.app.R
import re.abbot.librecr.app.ui.Routes

private data class SettingsEntry(val route: String, val titleRes: Int, val subtitleRes: Int, val icon: ImageVector)

@Composable
fun SettingsScreen(onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val entries = listOf(
        SettingsEntry(Routes.SENSOR, R.string.title_sensor, R.string.set_sensor_s, Icons.Rounded.Sensors),
        SettingsEntry(Routes.ALARMS, R.string.title_alarms, R.string.set_alarms_s, Icons.Rounded.Notifications),
        SettingsEntry(Routes.LIVE_UPDATES, R.string.title_live_updates, R.string.set_live_updates_s, Icons.Rounded.Update),
        SettingsEntry(Routes.CLOUD, R.string.title_cloud, R.string.set_cloud_s, Icons.Rounded.CloudUpload),
        SettingsEntry(Routes.FLOATING, R.string.title_floating, R.string.set_floating_s, Icons.Rounded.BubbleChart),
        SettingsEntry(Routes.AOD, R.string.title_aod, R.string.set_aod_s, Icons.Rounded.Bedtime),
        SettingsEntry(Routes.WEAR_APPEARANCE, R.string.title_wear_os, R.string.set_wear_appearance_s, Icons.Rounded.Watch),
        SettingsEntry(Routes.UNITS, R.string.title_units, R.string.set_units_s, Icons.Rounded.Straighten),
        SettingsEntry(Routes.LANGUAGE, R.string.title_language, R.string.set_language_s, Icons.Rounded.Translate),
        SettingsEntry(Routes.LOG, R.string.title_log, R.string.set_log_s, Icons.AutoMirrored.Rounded.ShowChart),
    )
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard {
            entries.forEachIndexed { index, entry ->
                ListItem(
                    headlineContent = { Text(stringResource(entry.titleRes)) },
                    supportingContent = { Text(stringResource(entry.subtitleRes)) },
                    leadingContent = { Icon(entry.icon, contentDescription = null) },
                    modifier = Modifier.clickable { onNavigate(entry.route) },
                )
                if (index < entries.lastIndex) HorizontalDivider()
            }
        }
    }
}
