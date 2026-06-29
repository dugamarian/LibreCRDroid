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
import org.json.JSONArray
import org.json.JSONObject
import re.abbot.librecr.app.isFreshGlucose
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention
import re.abbot.librecr.protocol.dataplane.Libre3SensorError
import re.abbot.librecr.protocol.dataplane.SensorLifecycle
import re.abbot.librecr.protocol.hexToBytes
import re.abbot.librecr.protocol.toHex

private val Context.dataStore by preferencesDataStore(name = "librecr_session")

/**
 * Persists the imported session + the last accepted glucose point so reconnect
 * can request bounded backfill (mirrors the Swift `Libre3SensorState` ↔
 * `Libre3DataPlaneState` bridge).
 */
class SensorStateStore(
    private val context: Context,
    /** Long-term append-only history feeding the Statistics screen (GMI / Time-in-Range). */
    private val history: GlucoseHistoryStore = GlucoseHistoryStore(context),
) {
    private val keySession = stringPreferencesKey("session_json")
    private val keyAutoConnect = booleanPreferencesKey("auto_connect")
    private val keyWatchTakeover = booleanPreferencesKey("watch_takeover")
    private val keyLastLifeCount = intPreferencesKey("last_glucose_lifecount")
    private val keyLastMgDL = intPreferencesKey("last_glucose_mgdl")
    private val keyLastTrend = stringPreferencesKey("last_glucose_trend")
    private val keyLastReceivedAtMs = longPreferencesKey("last_glucose_received_at_ms")
    private val keyLastDeltaMgDlPerMin = doublePreferencesKey("last_glucose_delta_mgdl_per_min")
    private val keyGlucoseHistory = stringPreferencesKey("glucose_history_json")
    private val keyCachedPhase5RawKey = stringPreferencesKey("cached_phase5_raw_key")
    private val keyLifecycleLifeCount = intPreferencesKey("sensor_lifecycle_lifecount")
    private val keyLifecycleObservedAtMs = longPreferencesKey("sensor_lifecycle_observed_at_ms")
    private val keyWarmupStartedAtMs = longPreferencesKey("sensor_warmup_started_at_ms")
    private val keySensorErrorData = intPreferencesKey("sensor_error_data")
    private val keySensorPatchState = intPreferencesKey("sensor_patch_state")
    private val keySensorStatusObservedAtMs = longPreferencesKey("sensor_status_observed_at_ms")

    data class LastGlucose(
        val lifeCount: Int,
        val mgDL: Int,
        val trend: String,
        val receivedAtMs: Long,
        val deltaMgDlPerMin: Double?,
    )

    data class SensorLifecycleSnapshot(
        val lifeCountMinutes: Int,
        val observedAtMs: Long,
    )

    data class SensorWarmupSnapshot(
        val startedAtMs: Long,
    )

    /**
     * Last patch-status error/state, kept so the phone UI + watch complications can surface
     * sensor errors (insertion failure, ended, replace, or an unknown "sensor error" code).
     * The raw bytes are stored and the product-facing [attention]/[error] are derived from the
     * protocol's single source of truth, so the mapping never drifts.
     */
    data class SensorStatusSnapshot(
        val errorData: Int,
        val patchState: Int,
        val observedAtMs: Long,
    ) {
        val attention: Libre3SensorAttention get() = Libre3SensorAttention.from(errorData, patchState)
        val error: Libre3SensorError get() = Libre3SensorError.fromCode(errorData)
    }

    /** Last accepted glucose (local or relayed from the watch), for UI + complications. */
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

    val glucoseHistoryFlow: Flow<List<LastGlucose>> = context.dataStore.data.map { prefs ->
        parseHistory(prefs[keyGlucoseHistory])
    }

    val sensorLifecycleFlow: Flow<SensorLifecycleSnapshot?> = context.dataStore.data.map { prefs ->
        val lifeCount = prefs[keyLifecycleLifeCount] ?: return@map null
        SensorLifecycleSnapshot(
            lifeCountMinutes = lifeCount,
            observedAtMs = prefs[keyLifecycleObservedAtMs] ?: 0L,
        )
    }

    val sensorWarmupFlow: Flow<SensorWarmupSnapshot?> = context.dataStore.data.map { prefs ->
        val startedAtMs = prefs[keyWarmupStartedAtMs]?.takeIf { it > 0L } ?: return@map null
        SensorWarmupSnapshot(startedAtMs)
    }

    val sensorStatusFlow: Flow<SensorStatusSnapshot?> = context.dataStore.data.map { prefs ->
        val errorData = prefs[keySensorErrorData] ?: return@map null
        val patchState = prefs[keySensorPatchState] ?: return@map null
        SensorStatusSnapshot(errorData, patchState, prefs[keySensorStatusObservedAtMs] ?: 0L)
    }

    val sessionFlow: Flow<ImportedSession?> = context.dataStore.data.map { prefs ->
        prefs[keySession]?.let { runCatching { ImportedSession.fromJson(it) }.getOrNull() }
    }

    val autoConnectFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyAutoConnect] ?: false
    }

    val watchTakeoverFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyWatchTakeover] ?: false
    }

    suspend fun loadSession(): ImportedSession? = sessionFlow.first()

    suspend fun autoConnectEnabled(): Boolean = autoConnectFlow.first()

    suspend fun saveSession(
        session: ImportedSession,
        preserveCachedKeyWhenKeyless: Boolean = false,
    ) {
        context.dataStore.edit { prefs ->
            val previousSession = prefs.storedSession()
            applyCachedKeyOnSessionChange(prefs, session, preserveCachedKeyWhenKeyless)
            prefs[keySession] = session.withoutTransientCrypto().toJson()
            prefs.remove(keyLifecycleLifeCount)
            prefs.remove(keyLifecycleObservedAtMs)
            if (!previousSession.isSameSensorAs(session)) prefs.clearCurrentGlucose()
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
        val previousSession = prefs.storedSession()
        val importedKey = session.phase5RawKey?.takeIf { it.size == 16 }
        when {
            importedKey != null -> prefs[keyCachedPhase5RawKey] = importedKey.toHex()
            preserveCachedKeyWhenKeyless && previousSession.isSameSensorAs(session) -> Unit
            else -> prefs.remove(keyCachedPhase5RawKey)
        }
    }

    suspend fun saveActivatedSession(
        session: ImportedSession,
        startsWarmup: Boolean,
        warmupStartedAtMs: Long = System.currentTimeMillis(),
    ) {
        context.dataStore.edit {
            val previousSession = it.storedSession()
            applyCachedKeyOnSessionChange(it, session)
            it[keySession] = session.withoutTransientCrypto().toJson()
            it.remove(keyLifecycleLifeCount)
            it.remove(keyLifecycleObservedAtMs)
            if (!previousSession.isSameSensorAs(session)) it.clearCurrentGlucose()
            it.clearSensorStatus()
            if (startsWarmup) {
                it[keyWarmupStartedAtMs] = warmupStartedAtMs
            } else {
                it.remove(keyWarmupStartedAtMs)
            }
        }
    }

    suspend fun setAutoConnectEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyAutoConnect] = enabled }
    }

    suspend fun setWatchTakeoverEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyWatchTakeover] = enabled }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(keySession)
            it.remove(keyCachedPhase5RawKey)
            it[keyWatchTakeover] = false
            it.remove(keyLifecycleLifeCount)
            it.remove(keyLifecycleObservedAtMs)
            it.remove(keyWarmupStartedAtMs)
            it.clearCurrentGlucose()
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

    suspend fun clearCurrentGlucoseIfStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        var cleared = false
        context.dataStore.edit {
            val receivedAtMs = it[keyLastReceivedAtMs] ?: return@edit
            if (!isFreshGlucose(receivedAtMs, nowMs)) {
                it.clearCurrentGlucose()
                cleared = true
            }
        }
        return cleared
    }

    suspend fun saveLastGlucose(lifeCount: Int, mgDL: Int) {
        saveGlucoseReading(lifeCount, mgDL, "UNKNOWN", System.currentTimeMillis())
    }

    suspend fun saveGlucoseReading(lifeCount: Int, mgDL: Int, trend: String, receivedAtMs: Long) {
        val previous = loadLastGlucose()?.takeIf { isFreshGlucose(it.receivedAtMs, receivedAtMs) }
        val delta = previous
            ?.takeIf { it.lifeCount != lifeCount && it.receivedAtMs in 1 until receivedAtMs }
            ?.let { (mgDL - it.mgDL).toDouble() / ((receivedAtMs - it.receivedAtMs).toDouble() / 60_000.0) }
        context.dataStore.edit {
            val reading = LastGlucose(lifeCount, mgDL, trend, receivedAtMs, delta)
            it[keyLastLifeCount] = lifeCount
            it[keyLastMgDL] = mgDL
            it[keyLastTrend] = trend
            it[keyLastReceivedAtMs] = receivedAtMs
            it[keyLifecycleLifeCount] = lifeCount
            it[keyLifecycleObservedAtMs] = receivedAtMs
            it.clearWarmupIfComplete(lifeCount)
            if (delta == null || !delta.isFinite()) it.remove(keyLastDeltaMgDlPerMin)
            else it[keyLastDeltaMgDlPerMin] = delta
            it[keyGlucoseHistory] = encodeHistory(appendHistory(parseHistory(it[keyGlucoseHistory]), reading))
        }
        history.append(mgDL, receivedAtMs)
    }

    suspend fun saveSensorLifecycle(lifeCountMinutes: Int, observedAtMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit {
            it[keyLifecycleLifeCount] = lifeCountMinutes.coerceAtLeast(0)
            it[keyLifecycleObservedAtMs] = observedAtMs
            it.clearWarmupIfComplete(lifeCountMinutes)
        }
    }

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

    private fun MutablePreferences.clearCurrentGlucose() {
        remove(keyLastLifeCount)
        remove(keyLastMgDL)
        remove(keyLastTrend)
        remove(keyLastReceivedAtMs)
        remove(keyLastDeltaMgDlPerMin)
    }

    private fun MutablePreferences.storedSession(): ImportedSession? =
        this[keySession]?.let { runCatching { ImportedSession.fromJson(it) }.getOrNull() }

    private fun ImportedSession?.isSameSensorAs(next: ImportedSession): Boolean {
        val previous = this ?: return false
        val previousSerial = previous.serial?.takeIf { it.isNotBlank() }
        val nextSerial = next.serial?.takeIf { it.isNotBlank() }
        if (previousSerial != null && nextSerial != null) {
            return previousSerial.equals(nextSerial, ignoreCase = true)
        }
        return previous.bleAddress.equals(next.bleAddress, ignoreCase = true)
    }

    /**
     * Merge a point recovered from clinical/historical backfill. Older points fill history without
     * moving the "last glucose" UI state backwards.
     */
    suspend fun saveBackfilledGlucoseReading(
        lifeCount: Int,
        mgDL: Int,
        trend: String,
        fallbackReceivedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val current = loadLastGlucose()
        if (current?.lifeCount == lifeCount) return false
        val receivedAtMs = estimateBackfillTime(current, lifeCount, fallbackReceivedAtMs)
        if (current == null || lifeCount > current.lifeCount) {
            saveGlucoseReading(lifeCount, mgDL, trend, receivedAtMs)
            return true
        }
        val reading = LastGlucose(lifeCount, mgDL, trend, receivedAtMs, deltaMgDlPerMin = null)
        context.dataStore.edit {
            it[keyGlucoseHistory] = encodeHistory(appendHistory(parseHistory(it[keyGlucoseHistory]), reading))
        }
        history.importSamples(listOf(GlucoseSample(mgDL, receivedAtMs)))
        return true
    }

    /**
     * Store a glucose reading relayed from the watch (carries its own trend/delta/time).
     * Returns true when it advanced the displayed latest value. Older replayed readings fill
     * history but never move the UI backwards.
     */
    suspend fun saveRemoteGlucose(reading: LastGlucose): Boolean {
        val current = loadLastGlucose()
        if (current != null && isFreshGlucose(current.receivedAtMs)) {
            if (reading.lifeCount < current.lifeCount) {
                context.dataStore.edit {
                    it[keyGlucoseHistory] = encodeHistory(appendHistory(parseHistory(it[keyGlucoseHistory]), reading))
                }
                history.append(reading.mgDL, reading.receivedAtMs)
                return false
            }
            if (reading.lifeCount == current.lifeCount) {
                context.dataStore.edit {
                    it[keyGlucoseHistory] = encodeHistory(appendHistory(parseHistory(it[keyGlucoseHistory]), reading))
                }
                history.append(reading.mgDL, reading.receivedAtMs)
                return false
            }
        }
        context.dataStore.edit {
            it[keyLastLifeCount] = reading.lifeCount
            it[keyLastMgDL] = reading.mgDL
            it[keyLastTrend] = reading.trend
            it[keyLastReceivedAtMs] = reading.receivedAtMs
            it[keyLifecycleLifeCount] = reading.lifeCount
            it[keyLifecycleObservedAtMs] = reading.receivedAtMs
            it.clearWarmupIfComplete(reading.lifeCount)
            val delta = reading.deltaMgDlPerMin
            if (delta == null || !delta.isFinite()) it.remove(keyLastDeltaMgDlPerMin)
            else it[keyLastDeltaMgDlPerMin] = delta
            it[keyGlucoseHistory] = encodeHistory(appendHistory(parseHistory(it[keyGlucoseHistory]), reading))
        }
        history.append(reading.mgDL, reading.receivedAtMs)
        return true
    }

    private fun parseHistory(raw: String?): List<LastGlucose> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val lifeCount = item.optInt("lifeCount", Int.MIN_VALUE)
                    val mgDL = item.optInt("mgDL", Int.MIN_VALUE)
                    val receivedAtMs = item.optLong("receivedAtMs", 0L)
                    if (lifeCount == Int.MIN_VALUE || mgDL == Int.MIN_VALUE || receivedAtMs <= 0L) continue
                    val delta = if (item.has("deltaMgDlPerMin") && !item.isNull("deltaMgDlPerMin")) {
                        item.optDouble("deltaMgDlPerMin").takeIf { it.isFinite() }
                    } else {
                        null
                    }
                    add(
                        LastGlucose(
                            lifeCount = lifeCount,
                            mgDL = mgDL,
                            trend = item.optString("trend", "UNKNOWN"),
                            receivedAtMs = receivedAtMs,
                            deltaMgDlPerMin = delta,
                        ),
                    )
                }
            }.sortedBy { it.receivedAtMs }.takeLast(MAX_GLUCOSE_HISTORY_POINTS)
        }.getOrDefault(emptyList())
    }

    private fun encodeHistory(history: List<LastGlucose>): String {
        val array = JSONArray()
        history.takeLast(MAX_GLUCOSE_HISTORY_POINTS).forEach { reading ->
            array.put(
                JSONObject()
                    .put("lifeCount", reading.lifeCount)
                    .put("mgDL", reading.mgDL)
                    .put("trend", reading.trend)
                    .put("receivedAtMs", reading.receivedAtMs)
                    .apply {
                        reading.deltaMgDlPerMin
                            ?.takeIf { it.isFinite() }
                            ?.let { put("deltaMgDlPerMin", it) }
                    },
            )
        }
        return array.toString()
    }

    private fun appendHistory(history: List<LastGlucose>, reading: LastGlucose): List<LastGlucose> {
        val filtered = history.filterNot {
            it.receivedAtMs == reading.receivedAtMs || it.lifeCount == reading.lifeCount
        }
        return (filtered + reading)
            .sortedBy { it.receivedAtMs }
            .takeLast(MAX_GLUCOSE_HISTORY_POINTS)
    }

    private fun MutablePreferences.clearWarmupIfComplete(lifeCountMinutes: Int) {
        val warmupMinutes = runCatching {
            this[keySession]
                ?.let { ImportedSession.fromJson(it).warmupMinutes }
                ?: SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES
        }.getOrDefault(SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES)
        if (lifeCountMinutes >= warmupMinutes) {
            remove(keyWarmupStartedAtMs)
        }
    }

    private fun estimateBackfillTime(current: LastGlucose?, lifeCount: Int, fallbackReceivedAtMs: Long): Long {
        val anchor = current ?: return fallbackReceivedAtMs
        val estimated = anchor.receivedAtMs + (lifeCount - anchor.lifeCount) * 60_000L
        return estimated.takeIf { it > 0L } ?: fallbackReceivedAtMs
    }

    companion object {
        private const val MAX_GLUCOSE_HISTORY_POINTS = 360
    }
}
