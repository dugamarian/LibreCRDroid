package re.abbot.librecr.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention
import re.abbot.librecr.protocol.dataplane.Libre3SensorError
import re.abbot.librecr.protocol.hexToBytes
import re.abbot.librecr.protocol.toHex

private val Context.dataStore by preferencesDataStore(name = "librecr_wear_session")

class SensorStateStore(private val context: Context) {
    private val keySession = stringPreferencesKey("session_json")
    private val keyAutoConnect = booleanPreferencesKey("auto_connect")
    private val keyLastLifeCount = intPreferencesKey("last_glucose_lifecount")
    private val keyLastMgDL = intPreferencesKey("last_glucose_mgdl")
    private val keyLastTrend = stringPreferencesKey("last_glucose_trend")
    private val keyLastReceivedAtMs = longPreferencesKey("last_glucose_received_at_ms")
    private val keyLastDeltaMgDlPerMin = doublePreferencesKey("last_glucose_delta_mgdl_per_min")
    private val keyCachedPhase5RawKey = stringPreferencesKey("cached_phase5_raw_key")
    private val keySensorErrorData = intPreferencesKey("sensor_error_data")
    private val keySensorPatchState = intPreferencesKey("sensor_patch_state")
    private val keySensorStatusObservedAtMs = longPreferencesKey("sensor_status_observed_at_ms")

    data class LastGlucose(
        val lifeCount: Int,
        val mgDL: Int,
        val trend: String,
        val receivedAtMs: Long,
        val deltaMgDlPerMin: Double?,
        /** Uncapped-below-floor value carried to the phone for its history chart; null ⇒ same as [mgDL]. */
        val chartMgDL: Int? = null,
    )

    /**
     * Last patch-status error/state, kept so the watch complications can surface sensor errors
     * (insertion failure, ended, replace, or an unknown "sensor error" code). Raw bytes are stored
     * and the product-facing [attention]/[error] are derived from the protocol.
     */
    data class SensorStatusSnapshot(
        val errorData: Int,
        val patchState: Int,
        val observedAtMs: Long,
    ) {
        val attention: Libre3SensorAttention get() = Libre3SensorAttention.from(errorData, patchState)
        val error: Libre3SensorError get() = Libre3SensorError.fromCode(errorData)
    }

    val sessionFlow: Flow<ImportedSession?> = context.dataStore.data.map { prefs ->
        prefs[keySession]?.let { runCatching { ImportedSession.fromJson(it) }.getOrNull() }
    }

    val autoConnectFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyAutoConnect] ?: false
    }

    val lastGlucoseFlow: Flow<LastGlucose?> = context.dataStore.data.map { prefs ->
        val lifeCount = prefs[keyLastLifeCount] ?: return@map null
        val mgDL = prefs[keyLastMgDL] ?: return@map null
        LastGlucose(
            lifeCount = lifeCount,
            mgDL = mgDL,
            trend = prefs[keyLastTrend] ?: "UNKNOWN",
            receivedAtMs = prefs[keyLastReceivedAtMs] ?: 0L,
            deltaMgDlPerMin = prefs[keyLastDeltaMgDlPerMin],
        )
    }

    val sensorStatusFlow: Flow<SensorStatusSnapshot?> = context.dataStore.data.map { prefs ->
        val errorData = prefs[keySensorErrorData] ?: return@map null
        val patchState = prefs[keySensorPatchState] ?: return@map null
        SensorStatusSnapshot(errorData, patchState, prefs[keySensorStatusObservedAtMs] ?: 0L)
    }

    suspend fun loadSession(): ImportedSession? = sessionFlow.first()

    suspend fun autoConnectEnabled(): Boolean = autoConnectFlow.first()

    suspend fun loadSensorStatus(): SensorStatusSnapshot? = sensorStatusFlow.first()

    suspend fun saveSensorStatus(errorData: Int, patchState: Int, observedAtMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit {
            it[keySensorErrorData] = errorData
            it[keySensorPatchState] = patchState
            it[keySensorStatusObservedAtMs] = observedAtMs
        }
    }

    private fun MutablePreferences.clearSensorStatus() {
        remove(keySensorErrorData)
        remove(keySensorPatchState)
        remove(keySensorStatusObservedAtMs)
    }

    suspend fun saveSession(
        session: ImportedSession,
        preserveCachedKeyWhenKeyless: Boolean = false,
    ) {
        context.dataStore.edit { prefs ->
            applyCachedKeyOnSessionChange(prefs, session, preserveCachedKeyWhenKeyless)
            prefs[keySession] = session.withoutTransientCrypto().toJson()
            prefs.clearSensorStatus()
        }
    }

    /**
     * Reconcile the separate cached first-pair key with an incoming session:
     *  - a session carrying a 16-byte key (imported from external JSON / Swift `phase5RawKey`) sets it;
     *  - a key-less session drops any cached key, because the caller is replacing provisioning;
     *  - a metadata-only same-address update can explicitly preserve the locally-derived key.
     */
    private fun applyCachedKeyOnSessionChange(
        prefs: MutablePreferences,
        session: ImportedSession,
        preserveCachedKeyWhenKeyless: Boolean = false,
    ) {
        val previousAddress = prefs[keySession]
            ?.let { runCatching { ImportedSession.fromJson(it).bleAddress }.getOrNull() }
        val importedKey = session.phase5RawKey?.takeIf { it.size == 16 }
        when {
            importedKey != null -> prefs[keyCachedPhase5RawKey] = importedKey.toHex()
            preserveCachedKeyWhenKeyless &&
                previousAddress != null &&
                previousAddress.equals(session.bleAddress, ignoreCase = true) -> Unit
            else -> prefs.remove(keyCachedPhase5RawKey)
        }
    }

    suspend fun setAutoConnectEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyAutoConnect] = enabled }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(keySession)
            it.remove(keyCachedPhase5RawKey)
            it.clearSensorStatus()
        }
    }

    suspend fun loadCachedPhase5RawKey(): ByteArray? {
        val raw = context.dataStore.data.first()[keyCachedPhase5RawKey] ?: return null
        return runCatching { hexToBytes(raw).takeIf { it.size == 16 } }.getOrNull()
    }

    suspend fun saveCachedPhase5RawKey(key: ByteArray) {
        require(key.size == 16) { "phase5 raw key must be 16 bytes" }
        context.dataStore.edit { it[keyCachedPhase5RawKey] = key.toHex() }
    }

    suspend fun clearCachedPhase5RawKey() {
        context.dataStore.edit { it.remove(keyCachedPhase5RawKey) }
    }

    suspend fun lastGlucose(): Pair<Int, Int>? {
        val prefs = context.dataStore.data.first()
        val lc = prefs[keyLastLifeCount] ?: return null
        val mg = prefs[keyLastMgDL] ?: return null
        return lc to mg
    }

    suspend fun loadLastGlucose(): LastGlucose? = lastGlucoseFlow.first()

    suspend fun saveLastGlucose(lifeCount: Int, mgDL: Int) {
        saveGlucoseReading(lifeCount, mgDL, "UNKNOWN", System.currentTimeMillis())
    }

    suspend fun saveGlucoseReading(
        lifeCount: Int,
        mgDL: Int,
        trend: String,
        receivedAtMs: Long,
    ) {
        val previous = loadLastGlucose()
        // lifeCount (the sensor's minute counter) is the denominator, not wall-clock: post-reconnect
        // bursts deliver readings seconds apart and a seconds-based denominator exploded delta to ±99.
        val delta = previous
            ?.takeIf { lifeCount > it.lifeCount && it.receivedAtMs in 1 until receivedAtMs }
            ?.let { (mgDL - it.mgDL).toDouble() / (lifeCount - it.lifeCount).toDouble() }
        context.dataStore.edit {
            it[keyLastLifeCount] = lifeCount
            it[keyLastMgDL] = mgDL
            it[keyLastTrend] = trend
            it[keyLastReceivedAtMs] = receivedAtMs
            if (delta == null || !delta.isFinite()) {
                it.remove(keyLastDeltaMgDlPerMin)
            } else {
                it[keyLastDeltaMgDlPerMin] = delta
            }
        }
    }

    /**
     * Merge a point recovered from clinical/historical backfill. The watch has no long history
     * store, so older recovered points are logged by the caller but do not move the displayed value
     * backwards.
     */
    suspend fun saveBackfilledGlucoseReading(
        lifeCount: Int,
        mgDL: Int,
        trend: String,
        fallbackReceivedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val current = loadLastGlucose()
        if (current != null && lifeCount <= current.lifeCount) return false
        val receivedAtMs = estimateBackfillTime(current, lifeCount, fallbackReceivedAtMs)
        saveGlucoseReading(lifeCount, mgDL, trend, receivedAtMs)
        return true
    }

    suspend fun saveRemoteGlucose(reading: LastGlucose) {
        context.dataStore.edit {
            it[keyLastLifeCount] = reading.lifeCount
            it[keyLastMgDL] = reading.mgDL
            it[keyLastTrend] = reading.trend
            it[keyLastReceivedAtMs] = reading.receivedAtMs
            val delta = reading.deltaMgDlPerMin
            if (delta == null || !delta.isFinite()) it.remove(keyLastDeltaMgDlPerMin)
            else it[keyLastDeltaMgDlPerMin] = delta
        }
    }

    private fun estimateBackfillTime(current: LastGlucose?, lifeCount: Int, fallbackReceivedAtMs: Long): Long {
        val anchor = current ?: return fallbackReceivedAtMs
        val estimated = anchor.receivedAtMs + (lifeCount - anchor.lifeCount) * 60_000L
        return estimated.takeIf { it > 0L } ?: fallbackReceivedAtMs
    }
}
