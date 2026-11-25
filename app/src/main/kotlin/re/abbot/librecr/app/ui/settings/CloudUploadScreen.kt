package re.abbot.librecr.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.CloudSettings
import re.abbot.librecr.app.libreview.CloudStatus
import androidx.compose.ui.res.stringResource
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.MessageBox
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.ui.common.SettingsSwitchRow
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun CloudUploadScreen(modifier: Modifier = Modifier) {
    val appSettings = LocalAppSettings.current
    val scope = rememberCoroutineScope()
    val status by GlucoRitm.uploader.status.collectAsState()

    var enabled by rememberSaveable(appSettings.cloud.uploadEnabled) {
        mutableStateOf(appSettings.cloud.uploadEnabled)
    }
    var email by rememberSaveable(appSettings.cloud.email) {
        mutableStateOf(appSettings.cloud.email)
    }
    var password by remember(appSettings.cloud.password) {
        mutableStateOf(appSettings.cloud.password)
    }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(stringResource(R.string.title_cloud)) {
            SettingsSwitchRow(stringResource(R.string.cloud_enable), enabled) { value ->
                enabled = value
                scope.launch { GlucoRitm.settings.setCloudUploadEnabled(value) }
            }
            OutlinedTextField(
                value = email,
                onValueChange = { value ->
                    email = value
                    scope.launch {
                        GlucoRitm.settings.setCloudCredentials(value.trim(), password)
                    }
                },
                label = { Text(stringResource(R.string.cloud_email)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { value ->
                    password = value
                    scope.launch {
                        GlucoRitm.settings.setCloudCredentials(email.trim(), value)
                    }
                },
                label = { Text(stringResource(R.string.cloud_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            val okMsg = stringResource(R.string.cloud_test_ok)
            Button(
                onClick = {
                    scope.launch {
                        GlucoRitm.settings.setCloud(CloudSettings(enabled, email.trim(), password))
                        testing = true
                        testMsg = null
                        val result = GlucoRitm.uploader.testNow()
                        testing = false
                        testMsg = result.fold({ okMsg }, { it.message ?: it.toString() })
                    }
                },
                enabled = !testing && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (testing) R.string.cloud_testing else R.string.cloud_test))
            }
            if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())
            statusLine(status)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            testMsg?.let { MessageBox(it, false) }
            MessageBox(stringResource(R.string.cloud_warning), true)
        }
    }
}

@Composable
private fun statusLine(status: CloudStatus): String? = when (status) {
    is CloudStatus.Ok -> stringResource(R.string.cloud_status_ok, hhmmss(status.atMs), status.mgDl)
    is CloudStatus.Error -> stringResource(R.string.cloud_status_error, status.message)
    CloudStatus.Uploading -> stringResource(R.string.cloud_status_uploading)
    CloudStatus.Idle -> null
}

private fun hhmmss(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(ms)
