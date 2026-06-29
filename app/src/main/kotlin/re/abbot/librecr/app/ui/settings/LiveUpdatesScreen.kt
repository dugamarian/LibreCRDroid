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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.LiveUpdateSettings
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.live.LiveUpdateFormatter
import re.abbot.librecr.app.live.LiveUpdatesNotifier
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.ui.common.SettingsSwitchRow

@Composable
fun LiveUpdatesScreen(modifier: Modifier = Modifier) {
    val appSettings = LocalAppSettings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var liveUpdates by remember(appSettings.liveUpdates) { mutableStateOf(appSettings.liveUpdates) }
    val sample = remember {
        SensorStateStore.LastGlucose(
            lifeCount = 0,
            mgDL = 120,
            trend = "STABLE",
            receivedAtMs = 0L,
            deltaMgDlPerMin = 2.0,
        )
    }

    fun persist(next: LiveUpdateSettings) {
        liveUpdates = next
        scope.launch { LibreCR.settings.setLiveUpdates(next) }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(stringResource(R.string.title_live_updates)) {
            SettingsSwitchRow(stringResource(R.string.live_updates_enable), liveUpdates.enabled) {
                persist(liveUpdates.copy(enabled = it))
            }
            Text(
                stringResource(R.string.live_updates_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            SettingsSwitchRow(stringResource(R.string.live_updates_home_screen), liveUpdates.showOnHomeScreen) {
                persist(liveUpdates.copy(showOnHomeScreen = it))
            }
            Text(
                stringResource(R.string.live_updates_home_screen_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            LiveUpdatesNotifier.canPostPromotedNotifications(ctx)?.let { allowed ->
                Text(
                    stringResource(
                        if (allowed) R.string.live_updates_promoted_allowed
                        else R.string.live_updates_promoted_blocked,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FilledTonalButton(
                onClick = { LiveUpdatesNotifier.openNotificationSettings(ctx) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.live_updates_system_settings))
            }
        }

        SectionCard(stringResource(R.string.live_updates_chip_section)) {
            SettingsSwitchRow(stringResource(R.string.live_updates_status_chip), liveUpdates.statusChipEnabled) {
                persist(liveUpdates.copy(statusChipEnabled = it))
            }
            if (liveUpdates.statusChipEnabled) {
                Text(
                    stringResource(
                        R.string.live_updates_chip_preview,
                        LiveUpdateFormatter.chipPreviewText(sample, appSettings.unit, liveUpdates),
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
                SettingsSwitchRow(stringResource(R.string.live_updates_chip_trend), liveUpdates.showTrendInChip) {
                    persist(liveUpdates.copy(showTrendInChip = it))
                }
                SettingsSwitchRow(stringResource(R.string.live_updates_chip_delta), liveUpdates.showDeltaInChip) {
                    persist(liveUpdates.copy(showDeltaInChip = it))
                }
            } else {
                Text(
                    stringResource(R.string.live_updates_status_chip_disabled),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SectionCard(stringResource(R.string.live_updates_lock_section)) {
            SettingsSwitchRow(stringResource(R.string.live_updates_lock_trend), liveUpdates.showTrendOnLockScreen) {
                persist(liveUpdates.copy(showTrendOnLockScreen = it))
            }
            SettingsSwitchRow(stringResource(R.string.live_updates_lock_delta), liveUpdates.showDeltaOnLockScreen) {
                persist(liveUpdates.copy(showDeltaOnLockScreen = it))
            }
        }
    }
}
