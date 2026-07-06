package re.abbot.librecr.app.ui.sensor

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.libreview.LibreViewAccountClient
import re.abbot.librecr.app.nfc.AndroidLibre3NfcReader
import re.abbot.librecr.app.service.SensorForegroundService
import re.abbot.librecr.app.ui.common.InfoRow
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.MessageBox
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.wear.WearDataSync
import re.abbot.librecr.protocol.pairing.Libre3NfcPatchInfo
import re.abbot.librecr.protocol.pairing.Libre3NfcScanMode
import re.abbot.librecr.protocol.pairing.Libre3NfcScanResult
import re.abbot.librecr.protocol.pairing.Libre3ReceiverID
import re.abbot.librecr.protocol.pairing.NfcActivationCommandCode
import re.abbot.librecr.protocol.toHex
import java.util.Locale
import java.util.UUID

@Composable
fun SensorScreen(nfcReader: AndroidLibre3NfcReader, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val appSettings = LocalAppSettings.current
    val scope = rememberCoroutineScope()
    val savedSession by LibreCR.store.sessionFlow.collectAsState(initial = null)
    val watchTakeover by LibreCR.store.watchTakeoverFlow.collectAsState(initial = false)

    val accountlessUniqueId = remember { libreCrAccountlessUniqueId(ctx) }
    val defaultReceiverId = remember(accountlessUniqueId) { Libre3ReceiverID.accountless(accountlessUniqueId) }
    val libreViewClient = remember { LibreViewAccountClient() }
    var receiverIdText by rememberSaveable { mutableStateOf(defaultReceiverId.unsignedValue.toString()) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var nfcMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var nfcError by rememberSaveable { mutableStateOf<String?>(null) }
    var patchInfo by remember { mutableStateOf<Libre3NfcPatchInfo?>(null) }
    var wearBusy by rememberSaveable { mutableStateOf(false) }
    var wearMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var wearError by rememberSaveable { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    LaunchedEffect(savedSession?.bleAddress) {
        if (savedSession == null) {
            LibreCR.store.setWatchTakeoverEnabled(false)
            wearMessage = null
            wearError = null
        }
    }

    fun startService(allowCandidateFirstPair: Boolean = false) {
        permLauncher.launch(requiredPermissions())
        scope.launch {
            LibreCR.store.setAutoConnectEnabled(true)
            LibreCR.store.setWatchTakeoverEnabled(false)
        }
        SensorForegroundService.start(ctx, allowCandidateFirstPair)
    }

    fun saveActivation(
        result: Libre3NfcScanResult,
        replaceExistingSession: Boolean = false,
        connectOnPhoneAfterScan: Boolean = false,
        startOnWatchAfterScan: Boolean = false,
    ) {
        val activation = result.activationResponse ?: return
        val receiverID = Libre3ReceiverID.fromNfcIdentityInput(receiverIdText)
        val session = ImportedSession(
            bleAddress = activation.bleAddressDisplay,
            bleDeviceName = activation.bleAddressDisplay,
            blePin = activation.blePin,
            phase5RawKey = null,
            phoneCert = null,
            receiverId = receiverID?.value,
            serial = result.patchInfo.serialNumber,
            warmupMinutes = result.patchInfo.warmupMinutes,
            wearMinutes = result.patchInfo.wearDurationMinutes,
            sensorProductType = result.patchInfo.productType,
            sensorGeneration = result.patchInfo.generation,
            sensorFirmwareVersion = result.patchInfo.firmwareVersion,
        )
        scope.launch {
            if (replaceExistingSession) {
                SensorForegroundService.stop(ctx)
                LibreCR.manager.stop()
                LibreCR.store.clearSession()
            }
            LibreCR.store.saveActivatedSession(
                session = session,
                startsWarmup = result.commandCode == NfcActivationCommandCode.ACTIVATE && result.patchInfo.isStorageState,
            )
            nfcMessage = "Senzor salvat: ${session.bleAddress}, PIN ${session.blePin.toHex()}"
            nfcError = null
            patchInfo = result.patchInfo
            when {
                startOnWatchAfterScan -> {
                    WearDataSync.sendSession(ctx, session, startOnWatch = true)
                    LibreCR.store.setWatchTakeoverEnabled(true)
                    nfcMessage += "; date trimise către ceas pentru conectare."
                }
                connectOnPhoneAfterScan -> {
                    nfcMessage += "; pornesc conexiunea pe telefon."
                    WearDataSync.sendSession(ctx, session, startOnWatch = false)
                    LibreCR.store.setWatchTakeoverEnabled(false)
                    startService(allowCandidateFirstPair = true)
                }
                else -> WearDataSync.sendSession(ctx, session, startOnWatch = false)
            }
        }
    }

    suspend fun refreshReceiverIdFromLibreViewIfConfigured(): Boolean {
        val currentReceiverId = Libre3ReceiverID.fromNfcIdentityInput(receiverIdText)
        if (currentReceiverId != null && currentReceiverId.value != defaultReceiverId.value) return true

        val cloud = LibreCR.settings.current().cloud
        if (cloud.email.isBlank() || cloud.password.isBlank()) return true

        nfcMessage = "Obțin Account ID din LibreView..."
        nfcError = null
        return runCatching {
            libreViewClient.fetchAccountIdentity(
                username = cloud.email.trim(),
                password = cloud.password,
                deviceId = accountlessUniqueId,
            )
        }.fold(
            onSuccess = { identity ->
                receiverIdText = identity.accountNumber.unsignedValue.toString()
                nfcMessage = "Account ID LibreView: ${nfcIdentityDisplay(identity.accountNumber)}"
                true
            },
            onFailure = {
                nfcMessage = null
                nfcError = "Nu am putut obține Account ID LibreView: ${it.message ?: it.toString()}"
                false
            },
        )
    }

    fun startNfc(
        modeFactory: (Int) -> Libre3NfcScanMode,
        replaceExistingSession: Boolean = false,
        connectOnPhoneAfterScan: Boolean = false,
        startOnWatchAfterScan: Boolean = false,
        restoreWatchSessionOnFailure: ImportedSession? = null,
    ) {
        val receiverID = Libre3ReceiverID.fromNfcIdentityInput(receiverIdText)
        if (receiverID == null) {
            nfcError = "Account ID invalid. Folosește număr decimal sau 4 bytes hex little-endian."
            return
        }
        scanning = true
        nfcMessage = "Apropie telefonul de senzor..."
        nfcError = null
        nfcReader.scan(modeFactory(receiverID.value)) { result ->
            scanning = false
            result.onSuccess { scan ->
                patchInfo = scan.patchInfo
                if (scan.activationResponse != null) {
                    saveActivation(
                        scan,
                        replaceExistingSession,
                        connectOnPhoneAfterScan,
                        startOnWatchAfterScan,
                    )
                } else if (scan.activationError != null) {
                    restoreWatchSessionOnFailure?.let {
                        WearDataSync.sendSession(ctx, it, startOnWatch = true)
                    }
                    nfcMessage = null
                    nfcError = "NFC error=0x${"%02x".format(requireNotNull(scan.activationError).errorCode)}"
                } else {
                    nfcMessage = "Patch citit: ${scan.patchInfo.serialNumber}"
                }
            }.onFailure {
                restoreWatchSessionOnFailure?.let { previous ->
                    WearDataSync.sendSession(ctx, previous, startOnWatch = true)
                }
                nfcMessage = null
                nfcError = it.message ?: it.toString()
            }
        }
    }

    fun connectNewSensor() {
        val previousSession = savedSession
        scope.launch {
            nfcError = null
            nfcMessage = "Pregătesc conectarea la senzor nou..."
            if (!refreshReceiverIdFromLibreViewIfConfigured()) return@launch
            if (Libre3ReceiverID.fromNfcIdentityInput(receiverIdText) == null) {
                nfcError = "Account ID invalid. Folosește număr decimal sau 4 bytes hex little-endian."
                return@launch
            }
            LibreCR.store.setAutoConnectEnabled(false)
            LibreCR.manager.stop()
            SensorForegroundService.stop(ctx)
            val watchHadControl = WearDataSync.requestStopAndWait(ctx, NEW_SENSOR_WATCH_STOP_TIMEOUT_MS)
            if (watchHadControl) {
                nfcMessage = "Ceasul a eliberat conexiunea. Telefonul preia senzorul prin NFC..."
            }
            startNfc(
                modeFactory = { Libre3NfcScanMode.ActivateOrSwitchReceiver(it) },
                replaceExistingSession = true,
                connectOnPhoneAfterScan = !watchHadControl,
                startOnWatchAfterScan = watchHadControl,
                restoreWatchSessionOnFailure = previousSession?.takeIf { watchHadControl },
            )
        }
    }

    fun connectExistingSensor() {
        val session = savedSession
        if (session != null) {
            nfcError = null
            nfcMessage = "Pornesc conexiunea la senzorul salvat."
            startService(allowCandidateFirstPair = true)
            return
        }
        scope.launch {
            if (!refreshReceiverIdFromLibreViewIfConfigured()) return@launch
            startNfc(
                modeFactory = { Libre3NfcScanMode.SwitchReceiver(it) },
                replaceExistingSession = true,
                connectOnPhoneAfterScan = true,
            )
        }
    }

    fun handoffToWatch() {
        val session = savedSession
        if (session == null || wearBusy) return
        val appCtx = ctx.applicationContext
        // Run on the process scope, not the composition scope: the handoff (stop phone BLE → wait →
        // start watch) takes several seconds, and it must NOT be cancelled when the user leaves this
        // screen. Commit the takeover intent up front so the switch flips on immediately and stays on
        // (it is driven by the persisted watchTakeoverFlow); revert only if the phone fails to release.
        LibreCR.appScope.launch {
            LibreCR.store.setWatchTakeoverEnabled(true)
            wearBusy = true
            wearError = null
            wearMessage = "Opresc orice conexiune veche pe ceas..."
            WearDataSync.requestStopStatusAndWait(appCtx, HANDOFF_WATCH_STOP_TIMEOUT_MS)
            LibreCR.store.setAutoConnectEnabled(false)
            wearMessage = "Telefonul cedează conexiunea BLE..."
            val stopped = LibreCR.manager.stopAndJoin()
            SensorForegroundService.stop(appCtx)
            if (!stopped) {
                wearBusy = false
                LibreCR.store.setWatchTakeoverEnabled(false)
                wearMessage = null
                wearError = "Telefonul nu a confirmat închiderea completă a sesiunii BLE."
                return@launch
            }
            wearMessage = "Aștept eliberarea conexiunii BLE a senzorului..."
            delay(HANDOFF_BLE_RELEASE_DELAY_MS)
            wearMessage = "Pornesc conexiunea pe ceas."
            // Relay the phone's CURRENT Phase 5 session key (read only after stopAndJoin, so it can't
            // rotate anymore). The watch resumes via the cheap cached handshake — it cannot finish a
            // full first-pair derivation before the sensor's mid-handshake patience runs out. Both
            // devices share the same receiver certificate, so the sensor sees the same identity.
            val handoffKey = runCatching { LibreCR.store.loadCachedPhase5RawKey() }.getOrNull()
            WearDataSync.sendSession(appCtx, session.copy(phase5RawKey = handoffKey), startOnWatch = true)
            wearMessage = "Ceasul preia conexiunea. Poți închide acest ecran."
            wearBusy = false
        }
    }

    fun returnToPhone() {
        if (savedSession == null || wearBusy) return
        scope.launch {
            wearBusy = true
            wearError = null
            wearMessage = "Cer ceasului să oprească senzorul..."
            val stopAck = WearDataSync.requestStopStatusAndWait(ctx)
            if (!stopAck.received) {
                wearBusy = false
                LibreCR.store.setWatchTakeoverEnabled(true)
                wearMessage = null
                wearError = "Ceasul nu a confirmat oprirea; telefonul nu pornește ca să evite conflictul BLE."
                return@launch
            }
            wearMessage = if (stopAck.wasActive) {
                "Ceasul a cedat conexiunea. Telefonul pornește BLE."
            } else {
                "Ceasul nu era conectat. Telefonul pornește BLE."
            }
            wearMessage = "Aștept eliberarea conexiunii BLE a senzorului..."
            delay(HANDOFF_BLE_RELEASE_DELAY_MS)
            LibreCR.store.clearCachedPhase5RawKey()
            LibreCR.store.setAutoConnectEnabled(true)
            wearMessage = "Telefonul pornește BLE cu handshake complet."
            startService(allowCandidateFirstPair = true)
            wearBusy = false
        }
    }

    fun setWatchTakeover(enabled: Boolean) {
        if (enabled) handoffToWatch() else returnToPhone()
    }

    fun cancelScan() {
        nfcReader.cancel()
        scanning = false
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SensorConnectionCard(
            nfcReader = nfcReader,
            hasSavedSession = savedSession != null,
            receiverIdText = receiverIdText,
            onReceiverIdText = { receiverIdText = it },
            accountStatus = receiverIdStatus(
                receiverIdText,
                accountlessUniqueId,
                appSettings.cloud.email.isNotBlank() && appSettings.cloud.password.isNotBlank(),
            ),
            scanning = scanning,
            patchInfo = patchInfo,
            message = nfcMessage,
            error = nfcError,
            onConnectNew = ::connectNewSensor,
            onConnectExisting = ::connectExistingSensor,
            onCancel = ::cancelScan,
        )

        WearTakeoverCard(
            session = savedSession,
            enabled = watchTakeover && savedSession != null,
            busy = wearBusy,
            message = wearMessage,
            error = wearError,
            onToggle = ::setWatchTakeover,
        )

        SavedSensorCard(savedSession, patchInfo)
    }
}

@Composable
private fun SensorConnectionCard(
    nfcReader: AndroidLibre3NfcReader,
    hasSavedSession: Boolean,
    receiverIdText: String,
    onReceiverIdText: (String) -> Unit,
    accountStatus: String,
    scanning: Boolean,
    patchInfo: Libre3NfcPatchInfo?,
    message: String?,
    error: String?,
    onConnectNew: () -> Unit,
    onConnectExisting: () -> Unit,
    onCancel: () -> Unit,
) {
    val canScan = nfcReader.isEnabled && !scanning
    SectionCard("Conectare senzor") {
        OutlinedTextField(
            value = receiverIdText,
            onValueChange = onReceiverIdText,
            label = { Text("Account / receiver ID") },
            supportingText = { Text(accountStatus) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (!nfcReader.isAvailable) {
            MessageBox("NFC nu este disponibil pe acest telefon.", true)
        } else if (!nfcReader.isEnabled) {
            MessageBox("NFC este oprit. Activează NFC pentru conectarea unui senzor nou.", true)
        }
        patchInfo?.let {
            InfoRow("serial", it.serialNumber)
            InfoRow("state", "0x${"%02x".format(it.stateByte)}")
            InfoRow("wear", "${it.wearDurationMinutes} min")
        }
        AnimatedVisibility(scanning) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Ține telefonul nemișcat peste senzor.", style = MaterialTheme.typography.bodySmall)
            }
        }
        message?.let { MessageBox(it, false) }
        error?.let { MessageBox(it, true) }
        Button(
            onClick = onConnectNew,
            enabled = canScan,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Activează senzor nou")
        }
        FilledTonalButton(
            onClick = onConnectExisting,
            enabled = !scanning && (hasSavedSession || nfcReader.isEnabled),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Conectare la senzor existent")
        }
        AnimatedVisibility(scanning) {
            FilledTonalButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Anulează")
            }
        }
    }
}

@Composable
private fun WearTakeoverCard(
    session: ImportedSession?,
    enabled: Boolean,
    busy: Boolean,
    message: String?,
    error: String?,
    onToggle: (Boolean) -> Unit,
) {
    SectionCard("Ceas") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Ceasul preia conexiunea")
                Text(
                    if (session == null) {
                        "Conectează sau salvează întâi un senzor."
                    } else {
                        "Telefonul cedează BLE înainte să pornească ceasul."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = session != null && !busy,
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { MessageBox(it, false) }
        error?.let { MessageBox(it, true) }
    }
}

@Composable
private fun SavedSensorCard(session: ImportedSession?, patchInfo: Libre3NfcPatchInfo?) {
    SectionCard("Senzor salvat") {
        if (session == null) {
            Text("Nicio sesiune salvată.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            InfoRow("BLE", session.bleAddress)
            InfoRow("BLE name", session.bleDeviceName ?: "--")
            InfoRow("PIN", session.blePin.toHex())
            InfoRow("serial", session.serial ?: "--")
            InfoRow("Phase 5", "se derivează local")
        }
        patchInfo?.let {
            HorizontalDivider()
            InfoRow("patch", it.serialNumber)
            InfoRow("fw", it.firmwareVersion)
            InfoRow("warmup", "${it.warmupMinutes} min")
        }
    }
}

private fun requiredPermissions(): Array<String> {
    val perms = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        perms += Manifest.permission.BLUETOOTH_SCAN
        perms += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        perms += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms += Manifest.permission.POST_NOTIFICATIONS
    }
    return perms.toTypedArray()
}

private fun receiverIdStatus(
    input: String,
    accountlessUniqueId: String,
    libreViewConfigured: Boolean,
): String {
    val identity = Libre3ReceiverID.fromNfcIdentityInput(input)
    val accountless = Libre3ReceiverID.accountless(accountlessUniqueId)
    return if (identity == null) {
        "ID fallback: ${accountless.unsignedValue}"
    } else if (identity.value == accountless.value && libreViewConfigured) {
        "ID local; LibreView actualizează Account ID la conectare"
    } else {
        "NFC: ${nfcIdentityDisplay(identity)}"
    }
}

private fun nfcIdentityDisplay(identity: Libre3ReceiverID): String =
    "${identity.unsignedValue} / LE ${identity.littleEndianHex}"

private fun libreCrAccountlessUniqueId(context: Context): String {
    val prefs = context.getSharedPreferences("librecr_identity", Context.MODE_PRIVATE)
    val existing = prefs.getString(LIBRECR_ACCOUNTLESS_UNIQUE_ID_KEY, null)
    if (!existing.isNullOrBlank()) return existing
    val created = UUID.randomUUID().toString().lowercase(Locale.US)
    prefs.edit().putString(LIBRECR_ACCOUNTLESS_UNIQUE_ID_KEY, created).apply()
    return created
}

private const val NEW_SENSOR_WATCH_STOP_TIMEOUT_MS = 2_000L
private const val HANDOFF_WATCH_STOP_TIMEOUT_MS = 2_000L
private const val HANDOFF_BLE_RELEASE_DELAY_MS = 3_000L
private const val LIBRECR_ACCOUNTLESS_UNIQUE_ID_KEY = "LibreCRAccountlessUniqueID"
