package re.abbot.librecr.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.SectionCard

@Composable
fun UnitsScreen(modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    val scope = rememberCoroutineScope()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(stringResource(R.string.units_unit_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlucoseUnit.entries.forEach { u ->
                    FilterChip(
                        selected = settings.unit == u,
                        onClick = { scope.launch { GlucoRitm.settings.setUnit(u) } },
                        label = { Text(u.label) },
                    )
                }
            }
            Text(stringResource(R.string.units_unit_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }

        SectionCard(stringResource(R.string.units_target_title)) {
            Text("${stringResource(R.string.units_low)}: ${settings.unit.formatWithUnit(settings.targetLow)}", fontWeight = FontWeight.SemiBold)
            Slider(
                value = settings.targetLow.toFloat(),
                onValueChange = { v -> scope.launch { GlucoRitm.settings.setTargets(v.toInt(), settings.targetHigh) } },
                valueRange = 60f..110f,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${stringResource(R.string.units_high)}: ${settings.unit.formatWithUnit(settings.targetHigh)}", fontWeight = FontWeight.SemiBold)
            Slider(
                value = settings.targetHigh.toFloat(),
                onValueChange = { v -> scope.launch { GlucoRitm.settings.setTargets(settings.targetLow, v.toInt()) } },
                valueRange = 140f..220f,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.units_target_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
