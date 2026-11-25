package re.abbot.librecr.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.R
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.wear.WearDataSync

@Composable
internal fun WearDeviceSelector(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = LocalAppSettings.current
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf<List<WearDataSync.ConnectedWearNode>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var refreshGeneration by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshGeneration) {
        loading = true
        loadFailed = false
        runCatching { WearDataSync.connectedWearNodes(context) }
            .onSuccess { nodes = it }
            .onFailure { loadFailed = true }
        loading = false
    }

    fun select(nodeId: String, displayName: String) {
        scope.launch {
            GlucoRitm.settings.setSelectedWearNode(nodeId, displayName)
        }
    }

    val selectedId = settings.selectedWearNodeId
    val selectedIsDisconnected = selectedId.isNotBlank() && nodes.none { it.id == selectedId }

    SectionCard(
        title = stringResource(R.string.wear_active_watch),
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.wear_active_watch_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        WearNodeRow(
            title = stringResource(R.string.wear_watch_automatic),
            detail = stringResource(R.string.wear_watch_automatic_desc),
            selected = selectedId.isBlank(),
            onClick = { select("", "") },
        )

        if (selectedIsDisconnected) {
            val savedName = if (settings.selectedWearNodeName.isBlank()) {
                stringResource(R.string.wear_watch_saved)
            } else {
                settings.selectedWearNodeName
            }
            WearNodeRow(
                title = savedName,
                detail = stringResource(
                    R.string.wear_watch_disconnected,
                    selectedId.takeLast(NODE_ID_SUFFIX_LENGTH),
                ),
                selected = true,
                onClick = { select(selectedId, settings.selectedWearNodeName) },
            )
        }

        nodes.forEach { node ->
            val displayName = if (node.displayName.isBlank()) {
                stringResource(R.string.wear_watch_unnamed)
            } else {
                node.displayName
            }
            WearNodeRow(
                title = displayName,
                detail = stringResource(
                    if (node.isNearby) {
                        R.string.wear_watch_nearby
                    } else {
                        R.string.wear_watch_remote
                    },
                    node.id.takeLast(NODE_ID_SUFFIX_LENGTH),
                ),
                selected = selectedId == node.id,
                onClick = { select(node.id, node.displayName) },
            )
        }

        if (!loading && nodes.isEmpty()) {
            Text(
                stringResource(R.string.wear_watch_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (loadFailed) {
            Text(
                stringResource(R.string.wear_watch_load_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedButton(
            onClick = { refreshGeneration += 1 },
            enabled = !loading,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.wear_watch_refresh))
            }
        }
    }
}

@Composable
private fun WearNodeRow(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val NODE_ID_SUFFIX_LENGTH = 6
