package re.abbot.librecr.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive theme. Uses Android 12+ dynamic color when available, otherwise the
 * teal brand scheme. Always renders Google Sans Rounded + the expressive (generously rounded)
 * shapes, and publishes the glucose range palette via [LocalGlucoseColors] so the chart/stats/
 * overlays stay consistent.
 *
 * Note: `MaterialExpressiveTheme` is still `internal` in material3 1.4.0, so we wrap the stable
 * [MaterialTheme] and supply the expressive shapes/typography ourselves. The expressive
 * components (button groups, flexible nav bars, wavy indicators) remain usable underneath it.
 */
@Composable
fun GlucoRitmTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val glucoseColors = if (dark) DarkGlucoseColors else LightGlucoseColors
    CompositionLocalProvider(LocalGlucoseColors provides glucoseColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
