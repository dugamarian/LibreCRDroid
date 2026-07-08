package re.abbot.librecr.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "librecr_settings")

/** Glucose alarm configuration. Thresholds are stored in mg/dL regardless of display unit. */
data class AlarmSettings(
    val enabled: Boolean = false,
    val lowEnabled: Boolean = true,
    val lowMgDl: Int = 70,
    val urgentLowEnabled: Boolean = true,
    val urgentLowMgDl: Int = 54,
    val highEnabled: Boolean = true,
    val highMgDl: Int = 240,
    val snoozeMinutes: Int = 30,
    /** Staleness safety net (no readings for ~10 min). Follows the master switch + active hours. */
    val stalenessEnabled: Boolean = true,
    /** When enabled, non-urgent alarms only fire inside [activeStartMinutes, activeEndMinutes). */
    val activeHoursEnabled: Boolean = false,
    val activeStartMinutes: Int = 8 * 60,
    val activeEndMinutes: Int = 22 * 60,
    /** Persistent high/low: alert only if glucose stays past the threshold for the whole duration. */
    val persistentHighEnabled: Boolean = false,
    val persistentHighMgDl: Int = 250,
    val persistentHighMinutes: Int = 120,
    val persistentLowEnabled: Boolean = false,
    val persistentLowMgDl: Int = 70,
    val persistentLowMinutes: Int = 30,
)

/** LibreView cloud upload config. Credentials are stored to allow unattended re-login. */
data class CloudSettings(
    val uploadEnabled: Boolean = false,
    val email: String = "",
    val password: String = "",
)

/** Lock-screen Live Updates notification config. Kept separate from glucose alarms. */
data class LiveUpdateSettings(
    val enabled: Boolean = false,
    val statusChipEnabled: Boolean = true,
    val showOnHomeScreen: Boolean = true,
    val showTrendInChip: Boolean = true,
    val showDeltaInChip: Boolean = true,
    val showTrendOnLockScreen: Boolean = true,
    val showDeltaOnLockScreen: Boolean = true,
)

/** All user-facing app settings (presentation, targets, alarms, language, cloud). */
data class AppSettings(
    val unit: GlucoseUnit = GlucoseUnit.MG_DL,
    val targetLow: Int = 70,
    val targetHigh: Int = 180,
    /** BCP-47 language tag for the in-app locale; blank = follow the system. */
    val languageTag: String = "",
    val redStandbyEnabled: Boolean = true,
    val redStandbyStartMinutes: Int = 22 * 60,
    val redStandbyEndMinutes: Int = 7 * 60,
    val alarms: AlarmSettings = AlarmSettings(),
    val cloud: CloudSettings = CloudSettings(),
    val liveUpdates: LiveUpdateSettings = LiveUpdateSettings(),
    val wearAppearance: WearAppearanceSettings = WearAppearanceSettings()
        .withTargets(targetLow, targetHigh),
    /** Whether the user has accepted the one-time use agreement / medical disclaimer. */
    val agreementAccepted: Boolean = false,
)

/**
 * Persists [AppSettings] in a dedicated DataStore (separate from the BLE session store). Exposed
 * as a single [settingsFlow] plus granular setters so screens can update one field at a time.
 */
class SettingsStore(private val context: Context) {
    private val keyUnit = stringPreferencesKey("glucose_unit")
    private val keyTargetLow = intPreferencesKey("target_low")
    private val keyTargetHigh = intPreferencesKey("target_high")
    private val keyAgreementAccepted = booleanPreferencesKey("agreement_accepted")
    private val keyLanguageTag = stringPreferencesKey("language_tag")
    private val keyRedStandbyEnabled = booleanPreferencesKey("red_standby_enabled")
    private val keyRedStandbyStart = intPreferencesKey("red_standby_start")
    private val keyRedStandbyEnd = intPreferencesKey("red_standby_end")

    private val keyAlarmEnabled = booleanPreferencesKey("alarm_enabled")
    private val keyAlarmLowEnabled = booleanPreferencesKey("alarm_low_enabled")
    private val keyAlarmLow = intPreferencesKey("alarm_low_mgdl")
    private val keyAlarmUrgentLowEnabled = booleanPreferencesKey("alarm_urgent_low_enabled")
    private val keyAlarmUrgentLow = intPreferencesKey("alarm_urgent_low_mgdl")
    private val keyAlarmHighEnabled = booleanPreferencesKey("alarm_high_enabled")
    private val keyAlarmHigh = intPreferencesKey("alarm_high_mgdl")
    private val keyAlarmSnooze = intPreferencesKey("alarm_snooze_minutes")
    private val keyAlarmStalenessEnabled = booleanPreferencesKey("alarm_staleness_enabled")
    private val keyActiveHoursEnabled = booleanPreferencesKey("alarm_active_hours_enabled")
    private val keyActiveStart = intPreferencesKey("alarm_active_start")
    private val keyActiveEnd = intPreferencesKey("alarm_active_end")
    private val keyPersistentHighEnabled = booleanPreferencesKey("alarm_persistent_high_enabled")
    private val keyPersistentHigh = intPreferencesKey("alarm_persistent_high_mgdl")
    private val keyPersistentHighMin = intPreferencesKey("alarm_persistent_high_minutes")
    private val keyPersistentLowEnabled = booleanPreferencesKey("alarm_persistent_low_enabled")
    private val keyPersistentLow = intPreferencesKey("alarm_persistent_low_mgdl")
    private val keyPersistentLowMin = intPreferencesKey("alarm_persistent_low_minutes")

    private val keyCloudEnabled = booleanPreferencesKey("cloud_upload_enabled")
    private val keyCloudEmail = stringPreferencesKey("cloud_email")
    private val keyCloudPassword = stringPreferencesKey("cloud_password")

    private val keyLiveUpdatesEnabled = booleanPreferencesKey("live_updates_enabled")
    private val keyLiveUpdatesStatusChipEnabled = booleanPreferencesKey("live_updates_status_chip_enabled")
    private val keyLiveUpdatesShowOnHomeScreen = booleanPreferencesKey("live_updates_show_on_home_screen")
    private val keyLiveUpdatesShowTrendInChip = booleanPreferencesKey("live_updates_show_trend_in_chip")
    private val keyLiveUpdatesShowDeltaInChip = booleanPreferencesKey("live_updates_show_delta_in_chip")
    private val keyLiveUpdatesShowTrendOnLockScreen = booleanPreferencesKey("live_updates_show_trend_on_lock_screen")
    private val keyLiveUpdatesShowDeltaOnLockScreen = booleanPreferencesKey("live_updates_show_delta_on_lock_screen")

    private val keyWearFontWeight = stringPreferencesKey("wear_appearance_font_weight")
    private val keyWearGlucoseLowColor = intPreferencesKey("wear_appearance_glucose_low_color")
    private val keyWearGlucoseInRangeColor = intPreferencesKey("wear_appearance_glucose_in_range_color")
    private val keyWearGlucoseHighColor = intPreferencesKey("wear_appearance_glucose_high_color")
    private val keyWearTrendLowColor = intPreferencesKey("wear_appearance_trend_low_color")
    private val keyWearTrendInRangeColor = intPreferencesKey("wear_appearance_trend_in_range_color")
    private val keyWearTrendHighColor = intPreferencesKey("wear_appearance_trend_high_color")
    private val keyWearTimestampNormalColor = intPreferencesKey("wear_appearance_timestamp_normal_color")
    private val keyWearTimestampStaleColor = intPreferencesKey("wear_appearance_timestamp_stale_color")
    private val keyWearDeltaNormalColor = intPreferencesKey("wear_appearance_delta_normal_color")
    private val keyWearDeltaLowColor = intPreferencesKey("wear_appearance_delta_low_color")
    private val keyWearDeltaHighColor = intPreferencesKey("wear_appearance_delta_high_color")

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        val defaults = AlarmSettings()
        val targetLow = p[keyTargetLow] ?: 70
        val targetHigh = p[keyTargetHigh] ?: 180
        val wearDefaults = WearAppearanceSettings().withTargets(targetLow, targetHigh)
        val displayUnit = GlucoseUnit.fromName(p[keyUnit])
        AppSettings(
            unit = displayUnit,
            targetLow = targetLow,
            targetHigh = targetHigh,
            agreementAccepted = p[keyAgreementAccepted] ?: false,
            languageTag = p[keyLanguageTag] ?: "",
            redStandbyEnabled = p[keyRedStandbyEnabled] ?: true,
            redStandbyStartMinutes = normalizeQuarterHour(p[keyRedStandbyStart] ?: 22 * 60),
            redStandbyEndMinutes = normalizeQuarterHour(p[keyRedStandbyEnd] ?: 7 * 60),
            alarms = AlarmSettings(
                enabled = p[keyAlarmEnabled] ?: defaults.enabled,
                lowEnabled = p[keyAlarmLowEnabled] ?: defaults.lowEnabled,
                lowMgDl = p[keyAlarmLow] ?: defaults.lowMgDl,
                urgentLowEnabled = p[keyAlarmUrgentLowEnabled] ?: defaults.urgentLowEnabled,
                urgentLowMgDl = p[keyAlarmUrgentLow] ?: defaults.urgentLowMgDl,
                highEnabled = p[keyAlarmHighEnabled] ?: defaults.highEnabled,
                highMgDl = p[keyAlarmHigh] ?: defaults.highMgDl,
                snoozeMinutes = p[keyAlarmSnooze] ?: defaults.snoozeMinutes,
                stalenessEnabled = p[keyAlarmStalenessEnabled] ?: defaults.stalenessEnabled,
                activeHoursEnabled = p[keyActiveHoursEnabled] ?: defaults.activeHoursEnabled,
                activeStartMinutes = p[keyActiveStart] ?: defaults.activeStartMinutes,
                activeEndMinutes = p[keyActiveEnd] ?: defaults.activeEndMinutes,
                persistentHighEnabled = p[keyPersistentHighEnabled] ?: defaults.persistentHighEnabled,
                persistentHighMgDl = p[keyPersistentHigh] ?: defaults.persistentHighMgDl,
                persistentHighMinutes = p[keyPersistentHighMin] ?: defaults.persistentHighMinutes,
                persistentLowEnabled = p[keyPersistentLowEnabled] ?: defaults.persistentLowEnabled,
                persistentLowMgDl = p[keyPersistentLow] ?: defaults.persistentLowMgDl,
                persistentLowMinutes = p[keyPersistentLowMin] ?: defaults.persistentLowMinutes,
            ),
            cloud = CloudSettings(
                uploadEnabled = p[keyCloudEnabled] ?: false,
                email = p[keyCloudEmail] ?: "",
                password = p[keyCloudPassword] ?: "",
            ),
            liveUpdates = LiveUpdateSettings(
                enabled = p[keyLiveUpdatesEnabled] ?: false,
                statusChipEnabled = p[keyLiveUpdatesStatusChipEnabled] ?: true,
                showOnHomeScreen = p[keyLiveUpdatesShowOnHomeScreen] ?: true,
                showTrendInChip = p[keyLiveUpdatesShowTrendInChip] ?: true,
                showDeltaInChip = p[keyLiveUpdatesShowDeltaInChip] ?: true,
                showTrendOnLockScreen = p[keyLiveUpdatesShowTrendOnLockScreen] ?: true,
                showDeltaOnLockScreen = p[keyLiveUpdatesShowDeltaOnLockScreen] ?: true,
            ),
            wearAppearance = WearAppearanceSettings(
                fontWeight = WearDisplayFontWeight.fromName(p[keyWearFontWeight]),
                glucoseLowColor = p[keyWearGlucoseLowColor] ?: wearDefaults.glucoseLowColor,
                glucoseInRangeColor = p[keyWearGlucoseInRangeColor] ?: wearDefaults.glucoseInRangeColor,
                glucoseHighColor = p[keyWearGlucoseHighColor] ?: wearDefaults.glucoseHighColor,
                trendLowColor = p[keyWearTrendLowColor] ?: wearDefaults.trendLowColor,
                trendInRangeColor = p[keyWearTrendInRangeColor] ?: wearDefaults.trendInRangeColor,
                trendHighColor = p[keyWearTrendHighColor] ?: wearDefaults.trendHighColor,
                timestampNormalColor = p[keyWearTimestampNormalColor] ?: wearDefaults.timestampNormalColor,
                timestampStaleColor = p[keyWearTimestampStaleColor] ?: wearDefaults.timestampStaleColor,
                deltaNormalColor = p[keyWearDeltaNormalColor] ?: wearDefaults.deltaNormalColor,
                deltaLowColor = p[keyWearDeltaLowColor] ?: wearDefaults.deltaLowColor,
                deltaHighColor = p[keyWearDeltaHighColor] ?: wearDefaults.deltaHighColor,
                targetLowMgDl = targetLow,
                targetHighMgDl = targetHigh,
                unit = displayUnit,
            ),
        )
    }

    suspend fun current(): AppSettings = settingsFlow.first()

    suspend fun setCloud(cloud: CloudSettings) = edit {
        it[keyCloudEnabled] = cloud.uploadEnabled
        it[keyCloudEmail] = cloud.email
        it[keyCloudPassword] = cloud.password
    }

    suspend fun setCloudUploadEnabled(enabled: Boolean) = edit {
        it[keyCloudEnabled] = enabled
    }

    suspend fun setCloudCredentials(email: String, password: String) = edit {
        it[keyCloudEmail] = email
        it[keyCloudPassword] = password
    }

    suspend fun setLiveUpdates(liveUpdates: LiveUpdateSettings) = edit {
        it[keyLiveUpdatesEnabled] = liveUpdates.enabled
        it[keyLiveUpdatesStatusChipEnabled] = liveUpdates.statusChipEnabled
        it[keyLiveUpdatesShowOnHomeScreen] = liveUpdates.showOnHomeScreen
        it[keyLiveUpdatesShowTrendInChip] = liveUpdates.showTrendInChip
        it[keyLiveUpdatesShowDeltaInChip] = liveUpdates.showDeltaInChip
        it[keyLiveUpdatesShowTrendOnLockScreen] = liveUpdates.showTrendOnLockScreen
        it[keyLiveUpdatesShowDeltaOnLockScreen] = liveUpdates.showDeltaOnLockScreen
    }

    suspend fun setUnit(unit: GlucoseUnit) = edit { it[keyUnit] = unit.name }

    suspend fun setTargets(low: Int, high: Int) = edit {
        it[keyTargetLow] = low
        it[keyTargetHigh] = high
    }

    suspend fun setAgreementAccepted(accepted: Boolean) = edit { it[keyAgreementAccepted] = accepted }

    suspend fun setLanguageTag(tag: String) = edit { it[keyLanguageTag] = tag }

    suspend fun setRedStandbyEnabled(enabled: Boolean) = edit { it[keyRedStandbyEnabled] = enabled }

    suspend fun setRedStandbyWindow(startMinutes: Int, endMinutes: Int) = edit {
        it[keyRedStandbyStart] = normalizeQuarterHour(startMinutes)
        it[keyRedStandbyEnd] = normalizeQuarterHour(endMinutes)
    }

    suspend fun setAlarms(a: AlarmSettings) = edit {
        it[keyAlarmEnabled] = a.enabled
        it[keyAlarmLowEnabled] = a.lowEnabled
        it[keyAlarmLow] = a.lowMgDl
        it[keyAlarmUrgentLowEnabled] = a.urgentLowEnabled
        it[keyAlarmUrgentLow] = a.urgentLowMgDl
        it[keyAlarmHighEnabled] = a.highEnabled
        it[keyAlarmHigh] = a.highMgDl
        it[keyAlarmSnooze] = a.snoozeMinutes
        it[keyAlarmStalenessEnabled] = a.stalenessEnabled
        it[keyActiveHoursEnabled] = a.activeHoursEnabled
        it[keyActiveStart] = a.activeStartMinutes
        it[keyActiveEnd] = a.activeEndMinutes
        it[keyPersistentHighEnabled] = a.persistentHighEnabled
        it[keyPersistentHigh] = a.persistentHighMgDl
        it[keyPersistentHighMin] = a.persistentHighMinutes
        it[keyPersistentLowEnabled] = a.persistentLowEnabled
        it[keyPersistentLow] = a.persistentLowMgDl
        it[keyPersistentLowMin] = a.persistentLowMinutes
    }

    suspend fun setWearAppearance(appearance: WearAppearanceSettings) = edit {
        it[keyWearFontWeight] = appearance.fontWeight.name
        it[keyWearGlucoseLowColor] = appearance.glucoseLowColor
        it[keyWearGlucoseInRangeColor] = appearance.glucoseInRangeColor
        it[keyWearGlucoseHighColor] = appearance.glucoseHighColor
        it[keyWearTrendLowColor] = appearance.trendLowColor
        it[keyWearTrendInRangeColor] = appearance.trendInRangeColor
        it[keyWearTrendHighColor] = appearance.trendHighColor
        it[keyWearTimestampNormalColor] = appearance.timestampNormalColor
        it[keyWearTimestampStaleColor] = appearance.timestampStaleColor
        it[keyWearDeltaNormalColor] = appearance.deltaNormalColor
        it[keyWearDeltaLowColor] = appearance.deltaLowColor
        it[keyWearDeltaHighColor] = appearance.deltaHighColor
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}

private fun normalizeQuarterHour(minutes: Int): Int =
    (minutes.coerceIn(0, 23 * 60 + 45) / 15) * 15
