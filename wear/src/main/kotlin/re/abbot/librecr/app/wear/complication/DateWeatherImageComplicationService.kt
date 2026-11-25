package re.abbot.librecr.app.wear.complication

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class DateWeatherImageComplicationService : SuspendingTimelineComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SMALL_IMAGE) {
            imageData(LocalDate.now(), CurrentWeather(18, WeatherCondition.PARTLY_CLOUDY), tapAction = null)
        } else {
            null
        }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        if (request.complicationType != ComplicationType.SMALL_IMAGE) return null
        val weather = WatchWeather.current(this)
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val tapAction = dateWeatherTapAction()
        return ComplicationDataTimeline(
            defaultComplicationData = imageData(LocalDate.ofInstant(now, zone), weather?.at(now), tapAction),
            timelineEntries = dateWeatherFrames(now, zone, weather).map { frame ->
                TimelineEntry(
                    validity = TimeInterval(frame.start, frame.end),
                    complicationData = imageData(frame.date, frame.weather, tapAction),
                )
            },
        )
    }

    private fun imageData(
        date: LocalDate,
        weather: CurrentWeather?,
        tapAction: PendingIntent?,
    ): SmallImageComplicationData {
        val bitmap = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = 52f
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 4f
        }
        drawText(canvas, date.format(dateFormat), CENTER, if (weather == null) CENTER else 54f, paint)

        if (weather != null) {
            val temperature = weather.temperatureText
            paint.textSize = 36f
            paint.textSize *= minOf(1f, 96f / paint.measureText(temperature))
            val temperatureWidth = paint.measureText(temperature)
            val rowStart = CENTER - (WEATHER_ICON_SIZE + WEATHER_ICON_GAP + temperatureWidth) / 2f
            getDrawable(weather.condition.icon)?.apply {
                val left = rowStart.roundToInt()
                val top = (WEATHER_ROW_Y - WEATHER_ICON_SIZE / 2f).roundToInt()
                setBounds(left, top, left + WEATHER_ICON_SIZE, top + WEATHER_ICON_SIZE)
                draw(canvas)
            }
            val temperatureCenter = rowStart + WEATHER_ICON_SIZE + WEATHER_ICON_GAP + temperatureWidth / 2f
            drawText(canvas, temperature, temperatureCenter, WEATHER_ROW_Y, paint)
        }

        val icon = Icon.createWithBitmap(bitmap)
        val image = SmallImage.Builder(icon, SmallImageType.ICON).setAmbientImage(icon).build()
        return SmallImageComplicationData.Builder(image, dateWeatherDescription(weather))
            .setTapAction(tapAction)
            .build()
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, centerY: Float, paint: Paint) {
        val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
        paint.style = Paint.Style.STROKE
        paint.color = Color.BLACK
        canvas.drawText(text, x, baseline, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawText(text, x, baseline, paint)
    }

    private companion object {
        const val IMAGE_SIZE = 160
        const val CENTER = IMAGE_SIZE / 2f
        const val WEATHER_ROW_Y = 110f
        const val WEATHER_ICON_SIZE = 28
        const val WEATHER_ICON_GAP = 7
        val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(DATE_WEATHER_PATTERN)
    }
}

internal data class DateWeatherFrame(
    val start: Instant,
    val end: Instant,
    val date: LocalDate,
    val weather: CurrentWeather?,
)

// The watch face can change the date and mark an old reading even before its next update request.
internal fun dateWeatherFrames(now: Instant, zone: ZoneId, weather: CurrentWeather?): List<DateWeatherFrame> {
    val tomorrow = LocalDate.ofInstant(now, zone).plusDays(1)
    val midnight = tomorrow.atStartOfDay(zone).toInstant()
    val end = tomorrow.plusDays(1).atStartOfDay(zone).toInstant()
    val freshUntil = weather?.takeUnless { it.isStale }?.freshUntil?.takeIf { it > now && it < end }
    return listOfNotNull(midnight, end, freshUntil).distinct().sorted().zipWithNext { start, stop ->
        DateWeatherFrame(start, stop, LocalDate.ofInstant(start, zone), weather?.at(start))
    }
}
