package re.abbot.librecr.app.wear.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import re.abbot.librecr.app.wear.awaitHandoff
import re.abbot.librecr.protocol.connection.WearWeather
import re.abbot.librecr.protocol.connection.WearWeatherReading
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal data class WeatherPlace(val label: String, val latitude: Double, val longitude: Double)

internal object PhoneWeather {
    private const val TAG = "GlucoRitmWeather"
    private const val MIN_REFRESH_MS = 5 * 60_000L
    private val lock = Mutex()

    private fun prefs(context: Context) = context.getSharedPreferences("phone_weather", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)
    fun selectedCity(context: Context): String? = prefs(context).getString("city", null)

    fun hasLocation(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun hasBackgroundLocation(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun enable(context: Context, city: WeatherPlace?): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val wasManual = selectedCity(context) != null
            prefs(context).edit {
                putBoolean("enabled", true)
                putString("city", city?.label)
                if (city != null) {
                    putString("latitude", city.latitude.toString())
                    putString("longitude", city.longitude.toString())
                } else if (wasManual) {
                    remove("latitude")
                    remove("longitude")
                }
            }
            PhoneWeatherJob.ensureScheduled(context)
            refreshLocked(context, foreground = true, network = null, force = true)
        }
    }

    suspend fun disable(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            prefs(context).edit {
                putBoolean("enabled", false)
                putBoolean("clear_pending", true)
                remove("latitude")
                remove("longitude")
                remove("city")
                remove("last_attempt")
            }
            // Keep the persisted job alive if Play services cannot accept the clear yet.
            PhoneWeatherJob.ensureScheduled(context)
            clearLocked(context)
        }
    }

    suspend fun refresh(
        context: Context,
        foreground: Boolean,
        network: Network? = null,
        force: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        lock.withLock { refreshLocked(context, foreground, network, force) }
    }

    private suspend fun refreshLocked(context: Context, foreground: Boolean, network: Network?, force: Boolean): Boolean {
        val prefs = prefs(context)
        if (!enabled(context)) {
            if (prefs.getBoolean("clear_pending", false)) clearLocked(context)
            return false
        }
        if (foreground) PhoneWeatherJob.ensureScheduled(context)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last_attempt", 0) in 0 until MIN_REFRESH_MS) return false
        val automatic = selectedCity(context) == null
        if (automatic && !hasLocation(context)) return false
        prefs.edit { putLong("last_attempt", now) }

        if (automatic && (foreground || hasBackgroundLocation(context))) {
            locate(context)?.let { (latitude, longitude) ->
                prefs.edit {
                    putString("latitude", latitude)
                    putString("longitude", longitude)
                }
            }
        }
        val latitude = prefs.getString("latitude", null) ?: return false
        val longitude = prefs.getString("longitude", null) ?: return false
        val uri = "https://api.open-meteo.com/v1/forecast".toUri().buildUpon()
            .appendQueryParameter("latitude", latitude)
            .appendQueryParameter("longitude", longitude)
            .appendQueryParameter("current", "temperature_2m,weather_code,is_day")
            .appendQueryParameter("temperature_unit", "celsius")
            .appendQueryParameter("timeformat", "unixtime")
            .build()
        val current = readJson(uri, network)?.optJSONObject("current") ?: return false
        val temperature = current.optDouble("temperature_2m")
        val code = current.optInt("weather_code", -1)
        val day = current.optInt("is_day", -1)
        val observedAt = current.optLong("time")
        if (temperature !in -100.0..70.0 || code !in 0..255 || day !in 0..1 ||
            System.currentTimeMillis() / 1000 - observedAt !in -300 until 7200
        ) return false
        return publish(context, WearWeatherReading(observedAt, temperature.roundToInt(), code, day == 1))
    }

    private suspend fun clearLocked(context: Context) {
        if (publish(context, null)) PhoneWeatherJob.cancel(context)
    }

    private suspend fun publish(context: Context, reading: WearWeatherReading?): Boolean {
        val prefs = prefs(context)
        val timestamp = maxOf(System.currentTimeMillis(), prefs.getLong("last_publication", 0) + 1)
        // Reserve the revision before sending so a cancelled publication cannot reuse it.
        prefs.edit { putLong("last_publication", timestamp) }
        return try {
            withTimeoutOrNull(10_000) {
                val request = PutDataRequest.create(WearWeather.PATH)
                    .setData(WearWeather(timestamp, reading).encode())
                    .setUrgent()
                Wearable.getDataClient(context).putDataItem(request).awaitHandoff()
                prefs.edit { remove("clear_pending") }
                Log.i(TAG, if (reading == null) "Weather cleared on Data Layer" else "Weather published to Data Layer")
                true
            } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Weather sync failed: ${error.javaClass.simpleName}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun locate(context: Context): Pair<String, String>? {
        if (!hasLocation(context)) return null
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_COARSE)
            .setMaxUpdateAgeMillis(5 * 60_000L)
            .setDurationMillis(8_000L)
            .build()
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationTokenSource()
                continuation.invokeOnCancellation { cancellation.cancel() }
                try {
                    LocationServices.getFusedLocationProviderClient(context)
                        .getCurrentLocation(request, cancellation.token)
                        .addOnCompleteListener { task ->
                            val location = if (task.isSuccessful) task.result else null
                            continuation.resume(location?.let {
                                ((it.latitude * 100).roundToInt() / 100.0).toString() to
                                    ((it.longitude * 100).roundToInt() / 100.0).toString()
                            })
                        }
                } catch (_: SecurityException) {
                    continuation.resume(null)
                }
            }
        }
    }

    suspend fun searchCities(query: String, language: String): List<WeatherPlace>? = withContext(Dispatchers.IO) {
        val uri = "https://geocoding-api.open-meteo.com/v1/search".toUri().buildUpon()
            .appendQueryParameter("name", query)
            .appendQueryParameter("count", "5")
            .appendQueryParameter("language", language)
            .build()
        val response = readJson(uri) ?: return@withContext null
        val results = response.optJSONArray("results") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until results.length()) {
                val result = results.optJSONObject(index) ?: continue
                val name = result.optString("name")
                val latitude = result.optDouble("latitude")
                val longitude = result.optDouble("longitude")
                if (name.isBlank() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) continue
                val label = listOf(name, result.optString("admin1"), result.optString("country"))
                    .filter(String::isNotBlank).distinct().joinToString(", ")
                add(WeatherPlace(label, latitude, longitude))
            }
        }
    }

    private fun readJson(uri: Uri, network: Network? = null): JSONObject? = try {
        val url = URL(uri.toString())
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            } else {
                Log.w(TAG, "Open-Meteo returned HTTP ${connection.responseCode}")
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (error: Exception) {
        Log.w(TAG, "Open-Meteo request failed: ${error.javaClass.simpleName}")
        null
    }
}
