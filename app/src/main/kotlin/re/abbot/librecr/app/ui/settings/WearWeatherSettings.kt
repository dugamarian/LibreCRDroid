package re.abbot.librecr.app.ui.settings

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import re.abbot.librecr.app.R
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.wear.weather.PhoneWeather
import re.abbot.librecr.app.wear.weather.WeatherPlace

@Composable
internal fun WearWeatherSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val language = LocalConfiguration.current.locales[0].language
    var enabled by remember { mutableStateOf(PhoneWeather.enabled(context)) }
    var city by remember { mutableStateOf(PhoneWeather.selectedCity(context)) }
    var background by remember { mutableStateOf(PhoneWeather.hasBackgroundLocation(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    var places by remember { mutableStateOf(emptyList<WeatherPlace>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }

    fun update(place: WeatherPlace?, useExisting: Boolean = false) {
        busy = true
        message = null
        scope.launch {
            val published = if (useExisting) {
                PhoneWeather.refresh(context, foreground = true, force = true)
            } else {
                PhoneWeather.enable(context, place)
            }
            enabled = PhoneWeather.enabled(context)
            city = PhoneWeather.selectedCity(context)
            places = emptyList()
            message = if (published) R.string.wear_weather_syncing else R.string.wear_weather_pending
            busy = false
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) update(null) else message = R.string.wear_weather_location_denied
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        background = PhoneWeather.hasBackgroundLocation(context)
        if (PhoneWeather.hasLocation(context)) update(null, useExisting = true)
    }

    SectionCard(stringResource(R.string.wear_weather_title)) {
        Text(stringResource(R.string.wear_weather_explanation), style = MaterialTheme.typography.bodyMedium)
        if (enabled) Text(city ?: stringResource(R.string.wear_weather_phone_location))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                if (PhoneWeather.hasLocation(context)) update(null) else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }) { Text(stringResource(R.string.wear_weather_use_location)) }
            if (enabled) {
                TextButton(enabled = !busy, onClick = { update(null, useExisting = true) }) {
                    Text(stringResource(R.string.wear_weather_refresh))
                }
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        PhoneWeather.disable(context)
                        enabled = false
                        city = null
                        message = null
                        busy = false
                    }
                }) { Text(stringResource(R.string.wear_weather_disable)) }
            }
        }
        if (enabled && city == null && !background) {
            Text(stringResource(R.string.wear_weather_background), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = {
                settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            }) { Text(stringResource(R.string.wear_weather_permissions)) }
        }
        OutlinedTextField(
            query, { query = it; places = emptyList() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            singleLine = true,
            label = { Text(stringResource(R.string.wear_weather_city)) },
        )
        TextButton(enabled = !busy && query.trim().length >= 2, onClick = {
            busy = true
            message = null
            scope.launch {
                val results = PhoneWeather.searchCities(query.trim(), language)
                places = results.orEmpty()
                if (places.isEmpty()) message = if (results == null) R.string.wear_weather_pending else R.string.wear_weather_no_city
                busy = false
            }
        }) { Text(stringResource(R.string.wear_weather_search)) }
        for (place in places) TextButton(enabled = !busy, onClick = { update(place) }) { Text(place.label) }
        if (busy) Text(stringResource(R.string.wear_weather_working))
        message?.let { Text(stringResource(it)) }
        Text("Open-Meteo · GeoNames", style = MaterialTheme.typography.labelSmall)
    }
}
