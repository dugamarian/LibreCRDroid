package re.abbot.librecr.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.AppSettings
import re.abbot.librecr.app.nfc.AndroidLibre3NfcReader
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.home.HomeScreen
import re.abbot.librecr.app.ui.sensor.SensorScreen
import re.abbot.librecr.app.ui.settings.AlarmsScreen
import re.abbot.librecr.app.ui.settings.AodScreen
import re.abbot.librecr.app.ui.settings.CloudUploadScreen
import re.abbot.librecr.app.ui.settings.FloatingScreen
import re.abbot.librecr.app.ui.settings.LanguageScreen
import re.abbot.librecr.app.ui.settings.LiveUpdatesScreen
import re.abbot.librecr.app.ui.settings.LogScreen
import re.abbot.librecr.app.ui.settings.SettingsScreen
import re.abbot.librecr.app.ui.settings.UnitsScreen
import re.abbot.librecr.app.ui.settings.WearAppearanceScreen
import re.abbot.librecr.app.ui.standby.StandbyScreen
import re.abbot.librecr.app.ui.standby.rememberChargingState
import re.abbot.librecr.app.ui.stats.StatsScreen
import java.util.Calendar

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Rounded.Home),
    Tab(Routes.STATS, R.string.nav_stats, Icons.Rounded.Insights),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Rounded.Settings),
)

@Composable
fun LibreCRApp(
    nfcReader: AndroidLibre3NfcReader,
    onKeepScreenOnChange: (Boolean) -> Unit = {},
) {
    val nav = rememberNavController()
    val settings by LibreCR.settings.settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val agreementScope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val topLevel = route == null || TABS.any { it.route == route }

    // Standby is now a dedicated full-screen StandbyActivity launched by StandbyController when the
    // phone is on a WIRELESS charger inside the window — even with the app backgrounded / screen off
    // (the nightstand case). A Compose conditional here could only work while this screen is visible.
    CompositionLocalProvider(LocalAppSettings provides settings) {
        Box(Modifier.fillMaxSize()) {
            if (!settings.agreementAccepted) {
                AgreementScreen(
                    onAccept = { agreementScope.launch { LibreCR.settings.setAgreementAccepted(true) } },
                )
            } else run {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(routeTitle(route), fontWeight = FontWeight.Bold) },
                            navigationIcon = {
                                if (!topLevel) {
                                    IconButton(onClick = { nav.popBackStack() }) {
                                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                                    }
                                }
                            },
                        )
                    },
                    bottomBar = { if (topLevel) BottomBar(nav, route) },
                ) { padding ->
                    NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
                        composable(Routes.HOME) { HomeScreen() }
                        composable(Routes.STATS) { StatsScreen() }
                        composable(Routes.SETTINGS) { SettingsScreen(onNavigate = { nav.navigate(it) }) }
                        composable(Routes.SENSOR) { SensorScreen(nfcReader) }
                        composable(Routes.ALARMS) { AlarmsScreen() }
                        composable(Routes.LIVE_UPDATES) { LiveUpdatesScreen() }
                        composable(Routes.CLOUD) { CloudUploadScreen() }
                        composable(Routes.FLOATING) { FloatingScreen() }
                        composable(Routes.AOD) { AodScreen() }
                        composable(Routes.WEAR_APPEARANCE) { WearAppearanceScreen() }
                        composable(Routes.UNITS) { UnitsScreen() }
                        composable(Routes.LANGUAGE) { LanguageScreen() }
                        composable(Routes.LOG) { LogScreen() }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController, currentRoute: String?) {
    NavigationBar {
        TABS.forEach { tab ->
            val label = stringResource(tab.labelRes)
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = label) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun routeTitle(route: String?): String = stringResource(
    when (route) {
        Routes.STATS -> R.string.nav_stats
        Routes.SETTINGS -> R.string.nav_settings
        Routes.SENSOR -> R.string.title_sensor
        Routes.ALARMS -> R.string.title_alarms
        Routes.LIVE_UPDATES -> R.string.title_live_updates
        Routes.CLOUD -> R.string.title_cloud
        Routes.FLOATING -> R.string.title_floating
        Routes.AOD -> R.string.title_aod
        Routes.WEAR_APPEARANCE -> R.string.title_wear_appearance
        Routes.UNITS -> R.string.title_units
        Routes.LANGUAGE -> R.string.title_language
        Routes.LOG -> R.string.title_log
        else -> R.string.title_app
    },
)

private const val STANDBY_CLOCK_REFRESH_MS = 30_000L

private fun minuteOfDay(timeMillis: Long): Int {
    val calendar = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
    return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
}

private fun isInTimeWindow(minute: Int, start: Int, end: Int): Boolean {
    val current = minute.coerceIn(0, 23 * 60 + 59)
    val normalizedStart = start.coerceIn(0, 23 * 60 + 59)
    val normalizedEnd = end.coerceIn(0, 23 * 60 + 59)
    if (normalizedStart == normalizedEnd) return false
    return if (normalizedStart < normalizedEnd) {
        current in normalizedStart until normalizedEnd
    } else {
        current >= normalizedStart || current < normalizedEnd
    }
}
