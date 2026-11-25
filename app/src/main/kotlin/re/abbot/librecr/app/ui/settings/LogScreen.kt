package re.abbot.librecr.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.R
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.ui.common.SettingsSwitchRow
import re.abbot.librecr.app.wear.WearDataSync

enum class LogType { PHONE, WATCH }

@Composable
fun LogScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val appSettings = LocalAppSettings.current
    val scope = rememberCoroutineScope()
    val phoneLogLines by BleLog.phoneLog.collectAsState()
    val watchLogLines by BleLog.watchLog.collectAsState()
    var loggingEnabled by remember(appSettings.diagnosticLoggingEnabled) {
        mutableStateOf(appSettings.diagnosticLoggingEnabled)
    }
    var selectedLog by remember { mutableStateOf(LogType.PHONE) }
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(stringResource(R.string.title_log)) {
            SettingsSwitchRow(
                stringResource(R.string.log_enable),
                loggingEnabled,
            ) { enabled ->
                loggingEnabled = enabled
                BleLog.setEnabled(enabled)
                scope.launch { GlucoRitm.settings.setDiagnosticLoggingEnabled(enabled) }
            }
            Text(
                stringResource(R.string.log_enable_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { selectedLog = LogType.PHONE },
                modifier = Modifier.weight(1f),
                colors = if (selectedLog == LogType.PHONE) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
            ) {
                Text(stringResource(R.string.log_phone))
            }
            OutlinedButton(
                onClick = { selectedLog = LogType.WATCH },
                modifier = Modifier.weight(1f),
                colors = if (selectedLog == LogType.WATCH) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
            ) {
                Text(stringResource(R.string.log_watch))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { WearDataSync.requestWatchLog(ctx) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.log_request_watch))
            }
            OutlinedButton(
                onClick = {
                    if (selectedLog == LogType.PHONE) BleLog.clearPhone() else BleLog.clearWatch()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.log_clear))
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            val linesToShow = if (selectedLog == LogType.PHONE) phoneLogLines else watchLogLines
            for (line in linesToShow.asReversed().take(400)) {
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
    }
}
