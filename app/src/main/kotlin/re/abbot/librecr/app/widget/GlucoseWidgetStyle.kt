// Resource-backed ColorProvider(@ColorRes) is LIBRARY_GROUP-restricted in Glance 1.2, but it is the
// only way to let the host resolve colors at draw time (day/night, Material You, a car's palette).
@file:SuppressLint("RestrictedApi")

package re.abbot.librecr.app.widget

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.text.FontFamily
import androidx.glance.unit.ColorProvider
import re.abbot.librecr.app.R

/**
 * Widget color roles. They are resources rather than resolved colors so the host applies its own
 * day/night configuration and, on Android 12+, the wallpaper palette (see values-v31).
 */
internal object WidgetColors {
    val surface = ColorProvider(R.color.glucose_widget_surface)
    val tile = ColorProvider(R.color.glucose_widget_tile)
    val onSurface = ColorProvider(R.color.glucose_widget_on_surface)
    val onSurfaceVariant = ColorProvider(R.color.glucose_widget_on_surface_variant)
    val primary = ColorProvider(R.color.glucose_widget_primary)
    val chip = ColorProvider(R.color.glucose_widget_chip)
    val onChip = ColorProvider(R.color.glucose_widget_on_chip)
    val tertiary = ColorProvider(R.color.glucose_widget_tertiary)
    val tertiaryContainer = ColorProvider(R.color.glucose_widget_tertiary_container)
    val onTertiaryContainer = ColorProvider(R.color.glucose_widget_on_tertiary_container)
    val errorContainer = ColorProvider(R.color.glucose_widget_error_container)
    val onErrorContainer = ColorProvider(R.color.glucose_widget_on_error_container)
}

/** A tonal triple: [accent] for text and lines on the surface, [container]/[onContainer] for shapes. */
internal data class WidgetTone(val accent: ColorProvider, val container: ColorProvider, val onContainer: ColorProvider)

internal val WidgetGlucoseRange.tone: WidgetTone
    get() = when (this) {
        WidgetGlucoseRange.VERY_LOW -> tone(R.color.glucose_widget_very_low, R.color.glucose_widget_very_low_container,
            R.color.glucose_widget_on_very_low_container)
        WidgetGlucoseRange.LOW -> tone(R.color.glucose_widget_low, R.color.glucose_widget_low_container,
            R.color.glucose_widget_on_low_container)
        WidgetGlucoseRange.IN_RANGE -> tone(R.color.glucose_widget_in_range, R.color.glucose_widget_in_range_container,
            R.color.glucose_widget_on_in_range_container)
        WidgetGlucoseRange.HIGH -> tone(R.color.glucose_widget_high, R.color.glucose_widget_high_container,
            R.color.glucose_widget_on_high_container)
        WidgetGlucoseRange.VERY_HIGH -> tone(R.color.glucose_widget_very_high, R.color.glucose_widget_very_high_container,
            R.color.glucose_widget_on_very_high_container)
    }

private fun tone(accent: Int, container: Int, onContainer: Int) =
    WidgetTone(ColorProvider(accent), ColorProvider(container), ColorProvider(onContainer))

/**
 * RemoteViews cannot load the app's bundled fonts, so text uses system families: the Material 3
 * Expressive type scale on Pixels that ship it (rounded Google Sans Flex), then Google Sans, then
 * the platform default. The host resolves the same system font map as this process.
 */
internal object WidgetFonts {
    private const val FLEX_DISPLAY = "variable-display-large-emphasized"
    private const val FLEX_DISPLAY_LIGHT = "variable-display-small-emphasized-light"
    private const val FLEX_TITLE = "variable-title-medium-emphasized"
    private const val FLEX_LABEL = "variable-label-large-emphasized"
    private const val FLEX_BODY = "variable-body-medium"

    val display: FontFamily? by lazy { family(FLEX_DISPLAY, "google-sans") }
    val title: FontFamily? by lazy { family(FLEX_TITLE, "google-sans-medium") }
    val label: FontFamily? by lazy { family(FLEX_LABEL, "google-sans-medium") }
    val body: FontFamily? by lazy { family(FLEX_BODY, "google-sans-text") }

    // In-process drawing (the AOD overlay) can load the app's bundled Google Sans Rounded, so it
    // takes only the rounded Flex families and otherwise keeps that bundled [fallback].
    fun displayTypeface(style: Int, fallback: Typeface): Typeface = typeface(FLEX_DISPLAY, style, fallback)
    fun lightDisplayTypeface(fallback: Typeface): Typeface = typeface(FLEX_DISPLAY_LIGHT, Typeface.NORMAL, fallback)
    fun labelTypeface(fallback: Typeface): Typeface = typeface(FLEX_LABEL, Typeface.NORMAL, fallback)
    fun bodyTypeface(fallback: Typeface): Typeface = typeface(FLEX_BODY, Typeface.NORMAL, fallback)

    /** The host-rendered age clock lives in XML, so its font is picked by layout. */
    @get:LayoutRes
    val ageLayout: Int by lazy {
        when {
            available("variable-label-large") -> R.layout.glucose_widget_age_flex
            available("google-sans-text-medium") -> R.layout.glucose_widget_age_google
            else -> R.layout.glucose_widget_age
        }
    }

    private fun family(vararg names: String): FontFamily? = names.firstOrNull(::available)?.let(::FontFamily)

    private fun typeface(name: String, style: Int, fallback: Typeface): Typeface =
        if (available(name)) Typeface.create(name, style) else fallback

    // Unknown family names resolve to the shared default typeface instance.
    private fun available(name: String): Boolean = Typeface.create(name, Typeface.NORMAL) !== Typeface.DEFAULT
}

/** Outer surface: the launcher's own corner radius on Android 12+, a rounded drawable before. */
internal fun GlanceModifier.widgetSurface(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        background(ColorProvider(R.color.glucose_widget_surface_translucent))
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        background(ImageProvider(R.drawable.glucose_widget_preview_background))
    }

/** Inner card, concentric with the outer surface through the system inner radius. */
internal fun GlanceModifier.widgetTile(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        background(ColorProvider(R.color.glucose_widget_tile_translucent))
            .cornerRadius(android.R.dimen.system_app_widget_inner_radius)
    } else {
        background(ImageProvider(R.drawable.glucose_widget_preview_tile))
    }

/** Any tinted shape drawable as background; works on every API level, unlike outline clipping. */
internal fun GlanceModifier.shapeBackground(@DrawableRes shape: Int, color: ColorProvider): GlanceModifier =
    background(ImageProvider(shape), colorFilter = ColorFilter.tint(color))
