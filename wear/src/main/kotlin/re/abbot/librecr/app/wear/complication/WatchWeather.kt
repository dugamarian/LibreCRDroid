package re.abbot.librecr.app.wear.complication

import android.content.Context
import android.content.ComponentName
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import re.abbot.librecr.app.R
import re.abbot.librecr.app.wear.awaitHandoff
import re.abbot.librecr.protocol.connection.WearWeather
import re.abbot.librecr.protocol.connection.WearWeatherReading
import java.time.Instant
import kotlin.math.roundToInt

internal data class CurrentWeather(
    val temperatureC: Int,
    val condition: WeatherCondition,
    val observedAt: Instant? = null,
    val isStale: Boolean = false,
) {
    val freshUntil: Instant? get() = observedAt?.plusSeconds(2 * 60 * 60L)
    val temperatureText: String get() = "${if (isStale) "~" else ""}$temperatureC°C"

    fun at(time: Instant): CurrentWeather =
        if (!isStale && freshUntil?.let { time >= it } == true) copy(isStale = true) else this
}

internal enum class WeatherCondition(@get:DrawableRes val icon: Int, @get:StringRes val label: Int) {
    SUNNY(R.drawable.ic_weather_sun, R.string.weather_clear),
    CLEAR_NIGHT(R.drawable.ic_weather_moon, R.string.weather_clear),
    PARTLY_CLOUDY(R.drawable.ic_weather_partly_cloudy, R.string.weather_partly_cloudy),
    PARTLY_CLOUDY_NIGHT(R.drawable.ic_weather_partly_cloudy_night, R.string.weather_partly_cloudy),
    OVERCAST(R.drawable.ic_weather_cloud, R.string.weather_overcast),
    FOG(R.drawable.ic_weather_fog, R.string.weather_fog),
    RAIN(R.drawable.ic_weather_rain, R.string.weather_rain),
    SNOW(R.drawable.ic_weather_snow, R.string.weather_snow),
    STORM(R.drawable.ic_weather_storm, R.string.weather_storm),
    UNKNOWN(R.drawable.ic_weather_unknown, R.string.weather_unknown);

    companion object {
        // Open-Meteo's WMO weather interpretation codes.
        fun fromCode(code: Int, isDay: Boolean): WeatherCondition = when (code) {
            0 -> if (isDay) SUNNY else CLEAR_NIGHT
            1, 2 -> if (isDay) PARTLY_CLOUDY else PARTLY_CLOUDY_NIGHT
            3 -> OVERCAST
            45, 48 -> FOG
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 97, 99 -> STORM
            else -> UNKNOWN
        }
    }
}

internal object WatchWeather {
    private fun prefs(context: Context) = context.getSharedPreferences("complication_weather", Context.MODE_PRIVATE)

    @Synchronized
    fun receive(context: Context, payload: ByteArray): Boolean {
        val update = WearWeather.decode(payload) ?: return false
        val prefs = prefs(context)
        if (update.publishedAtMillis <= prefs.getLong("phone_publication", 0)) return false
        val reading = update.reading
        val previous = current(context)
        val next = reading?.toCurrentWeather()?.at(Instant.now())
        prefs.edit {
            clear()
            putLong("phone_publication", update.publishedAtMillis)
            if (reading != null) {
                putLong("observed_at", reading.observedAtSeconds)
                putInt("temperature", reading.temperatureC)
                putInt("code", reading.code)
                putBoolean("day", reading.isDay)
            }
        }
        return next != previous
    }

    fun current(context: Context): CurrentWeather? {
        val values = prefs(context).all
        val reading = if (values.containsKey("phone_publication")) {
            val observedAt = values["observed_at"] as? Long ?: return null
            val temperature = values["temperature"] as? Int ?: return null
            val code = values["code"] as? Int ?: return null
            val day = values["day"] as? Boolean ?: return null
            WearWeatherReading(observedAt, temperature, code, day)
        } else {
            legacyReading(values["current"] as? String ?: return null)
        }
        return reading?.toCurrentWeather()?.at(Instant.now())
    }

    private fun legacyReading(text: String): WearWeatherReading? = runCatching {
        val json = JSONObject(text)
        val observedAt = json.optLong("time")
        val temperature = json.optDouble("temperature_2m")
        val code = json.optInt("weather_code", -1)
        val day = json.optInt("is_day", -1)
        if (observedAt <= 0 || temperature !in -100.0..70.0 || code !in 0..255 || day !in 0..1) return null
        WearWeatherReading(observedAt, temperature.roundToInt(), code, day == 1)
    }.getOrNull()

    private fun WearWeatherReading.toCurrentWeather() = CurrentWeather(
        temperatureC, WeatherCondition.fromCode(code, isDay), Instant.ofEpochSecond(observedAtSeconds),
    )

    suspend fun restoreFromDataLayer(context: Context) {
        try {
            val uri = "wear://*${WearWeather.PATH}".toUri()
            val items = withTimeoutOrNull(5_000) {
                Wearable.getDataClient(context).getDataItems(uri, DataClient.FILTER_LITERAL).awaitHandoff()
            } ?: return
            var changed = false
            try {
                for (item in items) {
                    if (item.uri.path == WearWeather.PATH && item.data?.let { receive(context, it) } == true) {
                        changed = true
                    }
                }
            } finally {
                items.release()
            }
            if (changed) requestWeatherComplicationUpdates(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("LibreCRWeather", "Weather sync unavailable: ${error.javaClass.simpleName}")
        }
    }
}

internal fun requestWeatherComplicationUpdates(context: Context) {
    for (service in listOf(DateWeatherComplicationService::class.java, DateWeatherImageComplicationService::class.java)) {
        try {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, service)).requestUpdateAll()
        } catch (error: RuntimeException) {
            Log.w("LibreCRWeather", "Complication update unavailable: ${error.javaClass.simpleName}")
        }
    }
}
