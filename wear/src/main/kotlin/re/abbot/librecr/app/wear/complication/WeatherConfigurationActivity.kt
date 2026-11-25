package re.abbot.librecr.app.wear.complication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import re.abbot.librecr.app.R

class WeatherConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { WatchWeather.restoreFromDataLayer(this@WeatherConfigurationActivity) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4EE6B8))) {
                Surface {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 28.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.complication_date_weather), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.weather_configure_on_phone), textAlign = TextAlign.Center)
                        Button(onClick = {
                            setResult(RESULT_OK)
                            finish()
                        }) { Text(stringResource(R.string.weather_done)) }
                    }
                }
            }
        }
    }
}
