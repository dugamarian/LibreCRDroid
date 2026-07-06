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
import androidx.compose.material3.Slider
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
import re.abbot.librecr.app.alarm.GlucoseAlarmManager
import re.abbot.librecr.app.alarm.StalenessWatchdog
import re.abbot.librecr.app.data.AlarmSettings
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.ui.common.SettingsSwitchRow

@Composable
fun AlarmsScreen(modifier: Modifier = Modifier) {
    val appSettings = LocalAppSettings.current
    val unit = appSettings.unit
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf(appSettings.alarms) }

    fun persist(next: AlarmSettings) {
        a = next
        scope.launch { LibreCR.settings.setAlarms(next) }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(stringResource(R.string.title_alarms)) {
            SettingsSwitchRow(stringResource(R.string.alarms_enable), a.enabled) { persist(a.copy(enabled = it)) }
            Text(stringResource(R.string.alarms_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard(stringResource(R.string.alarms_low_section)) {
            SettingsSwitchRow(stringResource(R.string.alarms_low), a.lowEnabled) { persist(a.copy(lowEnabled = it)) }
            ThresholdSlider(stringResource(R.string.alarms_low_th), a.lowMgDl, 60..110, unit) { persist(a.copy(lowMgDl = it)) }
            SettingsSwitchRow(stringResource(R.string.alarms_urgent), a.urgentLowEnabled) { persist(a.copy(urgentLowEnabled = it)) }
            ThresholdSlider(stringResource(R.string.alarms_urgent_th), a.urgentLowMgDl, 50..80, unit) { persist(a.copy(urgentLowMgDl = it)) }
        }

        SectionCard(stringResource(R.string.alarms_high_section)) {
            SettingsSwitchRow(stringResource(R.string.alarms_high), a.highEnabled) { persist(a.copy(highEnabled = it)) }
            ThresholdSlider(stringResource(R.string.alarms_high_th), a.highMgDl, 160..300, unit) { persist(a.copy(highMgDl = it)) }
        }

        SectionCard(stringResource(R.string.alarms_snooze_section)) {
            Text(stringResource(R.string.alarms_snooze, a.snoozeMinutes), fontWeight = FontWeight.SemiBold)
            Slider(
                value = a.snoozeMinutes.toFloat(),
                onValueChange = { persist(a.copy(snoozeMinutes = it.toInt())) },
                valueRange = 5f..120f,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(stringResource(R.string.alarms_hours_section)) {
            SettingsSwitchRow(stringResource(R.string.alarms_hours_enable), a.activeHoursEnabled) { persist(a.copy(activeHoursEnabled = it)) }
            Text("${stringResource(R.string.alarms_start)}: ${hhmm(a.activeStartMinutes)}", fontWeight = FontWeight.SemiBold)
            Slider(
                value = a.activeStartMinutes.toFloat(),
                onValueChange = { persist(a.copy(activeStartMinutes = (it / 15).toInt() * 15)) },
                valueRange = 0f..1425f,
                enabled = a.activeHoursEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${stringResource(R.string.alarms_end)}: ${hhmm(a.activeEndMinutes)}", fontWeight = FontWeight.SemiBold)
            Slider(
                value = a.activeEndMinutes.toFloat(),
                onValueChange = { persist(a.copy(activeEndMinutes = (it / 15).toInt() * 15)) },
                valueRange = 0f..1425f,
                enabled = a.activeHoursEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.alarms_hours_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard(stringResource(R.string.alarms_persistent_section)) {
            SettingsSwitchRow(stringResource(R.string.alarms_persistent_high), a.persistentHighEnabled) { persist(a.copy(persistentHighEnabled = it)) }
            ThresholdSlider(stringResource(R.string.alarms_persistent_high_th), a.persistentHighMgDl, 140..350, unit) { persist(a.copy(persistentHighMgDl = it)) }
            DurationSlider(stringResource(R.string.alarms_persistent_high_dur), a.persistentHighMinutes) { persist(a.copy(persistentHighMinutes = it)) }
            SettingsSwitchRow(stringResource(R.string.alarms_persistent_low), a.persistentLowEnabled) { persist(a.copy(persistentLowEnabled = it)) }
            ThresholdSlider(stringResource(R.string.alarms_persistent_low_th), a.persistentLowMgDl, 60..120, unit) { persist(a.copy(persistentLowMgDl = it)) }
            DurationSlider(stringResource(R.string.alarms_persistent_low_dur), a.persistentLowMinutes) { persist(a.copy(persistentLowMinutes = it)) }
            Text(stringResource(R.string.alarms_persistent_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard(stringResource(R.string.alarms_staleness_section)) {
            SettingsSwitchRow(stringResource(R.string.alarms_staleness), a.stalenessEnabled) {
                persist(a.copy(stalenessEnabled = it))
                // Immediate side effect: OFF clears any scheduled check + posted alert; ON arms now.
                StalenessWatchdog.onSettingChanged(ctx, it)
            }
            Text(stringResource(R.string.alarms_staleness_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard(stringResource(R.string.alarms_test_section)) {
            FilledTonalButton(onClick = { GlucoseAlarmManager.fireTest(ctx, a.snoozeMinutes, unit) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.alarms_test))
            }
        }
    }
}

@Composable
private fun ThresholdSlider(
    label: String,
    valueMgDl: Int,
    range: IntRange,
    unit: re.abbot.librecr.app.data.GlucoseUnit,
    onChange: (Int) -> Unit,
) {
    Text("$label: ${unit.formatWithUnit(valueMgDl)}", fontWeight = FontWeight.SemiBold)
    Slider(
        value = valueMgDl.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DurationSlider(label: String, minutes: Int, onChange: (Int) -> Unit) {
    Text("$label: ${durationLabel(minutes)}", fontWeight = FontWeight.SemiBold)
    Slider(
        value = minutes.toFloat(),
        onValueChange = { onChange((it / 15).toInt() * 15) },
        valueRange = 15f..360f,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun hhmm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun durationLabel(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}min"
}
