package re.abbot.librecr.app.wear.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeFormatComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import re.abbot.librecr.app.R

internal const val DATE_WEATHER_PATTERN = "dd.MM"

class DateWeatherComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        buildData(type, CurrentWeather(18, WeatherCondition.PARTLY_CLOUDY), tapAction = null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        buildData(
            request.complicationType,
            WatchWeather.current(this),
            dateWeatherTapAction(),
        )

    private fun buildData(
        type: ComplicationType,
        weather: CurrentWeather?,
        tapAction: PendingIntent?,
    ): ComplicationData? {
        val temperature = weather?.temperatureText
        val icon = weather?.let {
            MonochromaticImage.Builder(Icon.createWithResource(this, it.condition.icon)).build()
        }
        val description = dateWeatherDescription(weather)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(displayDate(), description)
                .setTitle(temperature?.let { PlainComplicationText.Builder(it).build() })
                .setMonochromaticImage(icon)
                .setTapAction(tapAction)
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                displayDate(temperature),
                description,
            )
                .setMonochromaticImage(icon)
                .setTapAction(tapAction)
                .build()

            else -> null
        }
    }

    private fun displayDate(temperature: String? = null): TimeFormatComplicationText {
        val datePlaceholder = "^1"
        val template = SpannableString(datePlaceholder + (temperature?.let { " · $it" } ?: ""))
        template.setSpan(
            ForegroundColorSpan(Color.WHITE),
            0,
            datePlaceholder.length,
            Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        return TimeFormatComplicationText.Builder(DATE_WEATHER_PATTERN).setText(template).build()
    }
}

internal fun Context.dateWeatherDescription(weather: CurrentWeather?): TimeFormatComplicationText {
    val template = if (weather == null) {
        getString(R.string.weather_unavailable_description)
    } else {
        val description = if (weather.isStale) R.string.weather_stale_description else R.string.weather_description
        getString(description, weather.temperatureText.removePrefix("~"), getString(weather.condition.label))
    }
    return TimeFormatComplicationText.Builder(DATE_WEATHER_PATTERN).setText(template).build()
}

internal fun Context.dateWeatherTapAction(): PendingIntent = PendingIntent.getActivity(
    this,
    45,
    Intent(this, WeatherConfigurationActivity::class.java),
    PendingIntent.FLAG_IMMUTABLE,
)
