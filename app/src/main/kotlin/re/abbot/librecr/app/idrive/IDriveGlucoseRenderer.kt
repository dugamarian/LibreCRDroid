package re.abbot.librecr.app.idrive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import re.abbot.librecr.app.R
import re.abbot.librecr.app.GLUCOSE_STALE_AFTER_MS
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.ui.theme.DarkColors
import re.abbot.librecr.app.ui.theme.DarkGlucoseColors
import re.abbot.librecr.app.widget.WidgetReadingStatus
import re.abbot.librecr.protocol.TrendArrowShape
import java.io.ByteArrayOutputStream
import java.util.Date
import kotlin.math.ceil
import kotlin.math.floor

/** Locally drawn Material-style dashboard; only visible content changes send a JPEG. */
internal class IDriveGlucoseRenderer(private val context: Context) {
    private val regular = ResourcesCompat.getFont(context, R.font.google_sans_rounded_regular) ?: Typeface.DEFAULT
    private val bold = ResourcesCompat.getFont(context, R.font.google_sans_rounded_bold) ?: Typeface.DEFAULT_BOLD
    private val background = DarkColors.background.toArgb()
    private val foreground = DarkColors.onSurface.toArgb()
    private val muted = DarkColors.onSurfaceVariant.toArgb()
    private val primary = DarkColors.primary.toArgb()
    private val surface = ColorUtils.blendARGB(background, primary, 0.055f)
    private val tonalSurface = ColorUtils.blendARGB(background, primary, 0.10f)

    fun render(content: IDriveGlucosePresentation, width: Int, height: Int, fullWidth: Int = width): ByteArray {
        val bitmap = createBitmap(width, height)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(background)
            // Keep the glucose card at the same size when the navigation sidebar opens.
            // Only very narrow/custom viewports need to reduce the whole layout to fit.
            val scale = minOf(fullWidth / 800f, height / 380f, width / 560f)
            canvas.translate(0f, (height - 380 * scale) / 2f)
            canvas.scale(scale, scale)
            val viewportWidth = width / scale
            with(Drawing(canvas)) {
                circle(31f, 30f, 7f, primary)
                text("GlucoRitm", 49f, 38f, 24f, foreground, strong = true)
                val reading = RectF(16f, 62f, 308f, 364f)
                val chartBounds = RectF(324f, 62f, viewportWidth - 16f, 364f)
                roundRect(reading, 28f, tonalSurface)
                roundRect(chartBounds, 28f, surface)
                drawReading(content, reading)
                drawChart(content, chartBounds)
            }
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun Drawing.drawReading(content: IDriveGlucosePresentation, bounds: RectF) {
        val x = bounds.left + 24f
        val available = bounds.width() - 48f
        val current = content.status == WidgetReadingStatus.CURRENT
        val valueColor = content.mgDl?.takeIf { current }?.let { rangeColor(it.toFloat(), content.targetLow, content.targetHigh) } ?: foreground
        // The native BMW status bar overlays the top-right of our image, even in split
        // screen. Keep reading age/time inside the card so that they remain visible.
        val age = content.ageMinutes?.let { context.getString(R.string.age_minutes, it.coerceAtMost(9999L).toInt()) }
        text(context.getString(R.string.idrive_reading), x, 100f, 18f, muted, maxWidth = available - 132f)
        age?.let { text(it, bounds.right - 24f, 100f, 16f, muted, maxWidth = 126f, align = Paint.Align.RIGHT) }
        text(content.value, x - 2f, 181f, 78f, valueColor, strong = true, maxWidth = available - 65f)
        text(content.unit, x, 212f, 21f, muted)
        content.measuredAtMs?.let {
            text(DateFormat.getTimeFormat(context).format(Date(it)), bounds.right - 24f, 212f,
                17f, muted, maxWidth = 118f, align = Paint.Align.RIGHT)
        }
        if (current) {
            if (TrendArrowShape.hasArrow(content.trend)) {
                circle(bounds.right - 48f, 154f, 25f, ColorUtils.blendARGB(tonalSurface, valueColor, 0.12f))
                drawTrend(content.trend, bounds.right - 64f, 138f, 32f, valueColor)
            }
            val range = when {
                content.mgDl == null -> null
                content.mgDl < content.targetLow -> R.string.range_low
                content.mgDl > content.targetHigh -> R.string.range_high
                else -> R.string.range_in
            }
            range?.let { pill(context.getString(it), x, 229f, valueColor, available) }
        } else {
            val status = when (content.status) {
                WidgetReadingStatus.STALE -> R.string.widget_stale
                WidgetReadingStatus.WARMUP -> R.string.sensor_warming_up
                WidgetReadingStatus.SENSOR_ERROR -> R.string.sensor_error
                else -> R.string.standby_no_reading
            }
            text(context.getString(status), x, 249f, 19f, muted, maxWidth = available)
        }
        line(x, 278f, bounds.right - 24f, 278f, ColorUtils.blendARGB(tonalSurface, muted, 0.20f), 1f)
        if (current) {
            val trendLabel = when (content.trend) {
                "FALLING_QUICKLY" -> R.string.trend_falling_quickly
                "FALLING" -> R.string.trend_falling
                "STABLE" -> R.string.trend_stable
                "RISING" -> R.string.trend_rising
                "RISING_QUICKLY" -> R.string.trend_rising_quickly
                else -> R.string.trend_unknown
            }
            text(context.getString(trendLabel), x, 309f, 21f, foreground, maxWidth = available)
            text("Δ ${content.delta} ${content.unit}/min", x, 340f, 20f, muted, maxWidth = available)
        } else {
            wrappedText(context.getString(R.string.idrive_waiting_reading), x, 308f, 18f, muted, available)
        }
    }

    private fun Drawing.drawChart(content: IDriveGlucosePresentation, bounds: RectF) {
        val chart = content.chart
        val unit = if (content.unit == GlucoseUnit.MMOL_L.label) GlucoseUnit.MMOL_L else GlucoseUnit.MG_DL
        val low = content.targetLow.coerceAtLeast(1)
        val high = content.targetHigh.coerceAtLeast(low + 1)
        text(context.getString(if (bounds.width() < 300f) R.string.idrive_chart_title_short else R.string.idrive_chart_title),
            bounds.left + 24f, 104f, 22f, foreground, strong = true, maxWidth = bounds.width() - 48f)
        text(context.getString(R.string.idrive_chart_target, unit.format(low), unit.format(high), unit.label),
            bounds.left + 24f, 139f, 16f, muted, maxWidth = bounds.width() - 48f)
        val plot = RectF(bounds.left + 24f, bounds.top + 102f, bounds.right - 44f, bounds.bottom - 42f)
        val minimum = (floor(minOf(40, low, chart.points.minOfOrNull { it.mgDl } ?: 40) / 20.0) * 20).toFloat()
        val maximum = (ceil(maxOf(250, high, chart.points.maxOfOrNull { it.mgDl } ?: 250) / 50.0) * 50).toFloat()
        fun x(time: Long) = plot.left + ((time - chart.startMs).toDouble() / (chart.endMs - chart.startMs).coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f) * plot.width()
        fun y(value: Float) = plot.bottom - ((value - minimum) / (maximum - minimum)).coerceIn(0f, 1f) * plot.height()
        roundRect(RectF(plot.left, y(high.toFloat()), plot.right, y(low.toFloat())), 8f,
            ColorUtils.blendARGB(surface, DarkGlucoseColors.inRange.toArgb(), 0.13f))
        for (value in listOf(minimum.toInt(), low, high, maximum.toInt()).distinct()) {
            val height = y(value.toFloat())
            line(plot.left, height, plot.right, height, ColorUtils.blendARGB(surface, muted, 0.22f), 1f)
            text(unit.format(value), plot.right + 6f, height + 4f, 12f, muted, maxWidth = 35f)
        }
        for (index in 0..2) {
            val time = chart.startMs + (chart.endMs - chart.startMs) * index / 2
            val left = x(time)
            line(left, plot.top, left, plot.bottom, ColorUtils.blendARGB(surface, muted, 0.10f), 1f)
            text(DateFormat.format("HH:mm", Date(time)).toString(), left, bounds.bottom - 14f, 13f, muted,
                align = when (index) { 0 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER })
        }
        canvas.save()
        canvas.clipRect(plot.left - 4f, plot.top - 4f, plot.right + 4f, plot.bottom + 4f)
        chart.points.zipWithNext().forEach { (previous, next) ->
            if (next.atMs - previous.atMs < GLUCOSE_STALE_AFTER_MS) {
                val color = rangeColor(next.mgDl.toFloat(), low, high)
                line(x(previous.atMs), y(previous.mgDl.toFloat()), x(next.atMs), y(next.mgDl.toFloat()),
                    ColorUtils.setAlphaComponent(color, 40), 8f)
                line(x(previous.atMs), y(previous.mgDl.toFloat()), x(next.atMs), y(next.mgDl.toFloat()), color, 3f)
            }
        }
        chart.points.forEach { point ->
            circle(x(point.atMs), y(point.mgDl.toFloat()), 2.5f, rangeColor(point.mgDl.toFloat(), low, high))
        }
        chart.points.lastOrNull()?.let { point ->
            circle(x(point.atMs), y(point.mgDl.toFloat()), 4.5f, rangeColor(point.mgDl.toFloat(), low, high))
        }
        canvas.restore()
        if (chart.points.isEmpty()) {
            wrappedText(context.getString(R.string.idrive_chart_empty), plot.left + 8f,
                plot.centerY(), 16f, muted, plot.width() - 16f, maxLines = 3)
        }
    }

    private fun rangeColor(mgDl: Float, low: Int, high: Int): Int = with(DarkGlucoseColors) {
        when {
            mgDl < 54 -> veryLow
            mgDl < low -> this.low
            mgDl <= high -> inRange
            mgDl <= 250 -> this.high
            else -> veryHigh
        }.toArgb()
    }

    private inner class Drawing(val canvas: Canvas) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun text(value: String, x: Float, baseline: Float, size: Float, color: Int,
                 strong: Boolean = false, maxWidth: Float = Float.MAX_VALUE, align: Paint.Align = Paint.Align.LEFT) {
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            paint.typeface = if (strong) bold else regular
            paint.textSize = size
            paint.color = color
            paint.textAlign = align
            val fitted = if (paint.measureText(value) <= maxWidth) value else {
                val count = paint.breakText(value, true, (maxWidth - paint.measureText("…")).coerceAtLeast(0f), null)
                value.take(count).trimEnd() + "…"
            }
            canvas.drawText(fitted, x, baseline, paint)
        }

        fun wrappedText(value: String, x: Float, baseline: Float, size: Float, color: Int,
                        maxWidth: Float, maxLines: Int = 2) {
            paint.typeface = regular
            paint.textSize = size
            var remaining = value
            repeat(maxLines) { row ->
                if (remaining.isEmpty()) return
                var count = paint.breakText(remaining, true, maxWidth, null)
                if (count < remaining.length && row < maxLines - 1) {
                    val space = remaining.lastIndexOf(' ', count)
                    if (space > 0) count = space
                }
                text(if (row == maxLines - 1) remaining else remaining.take(count),
                    x, baseline + row * (size + 5f), size, color, maxWidth = maxWidth)
                remaining = remaining.drop(count).trimStart()
            }
        }

        fun roundRect(rect: RectF, radius: Float, color: Int) {
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            paint.color = color
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        fun circle(x: Float, y: Float, radius: Float, color: Int) {
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawCircle(x, y, radius, paint)
        }

        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
            paint.color = color
            paint.strokeWidth = width
            paint.strokeCap = Paint.Cap.ROUND
            paint.pathEffect = null
            canvas.drawLine(x1, y1, x2, y2, paint)
            paint.pathEffect = null
        }

        fun pill(value: String, x: Float, top: Float, color: Int, maxWidth: Float) {
            paint.typeface = regular
            paint.textSize = 16f
            val width = (paint.measureText(value) + 24f).coerceAtMost(maxWidth)
            roundRect(RectF(x, top, x + width, top + 28f), 14f, ColorUtils.blendARGB(tonalSurface, color, 0.13f))
            text(value, x + 12f, top + 20f, 16f, color, maxWidth = width - 24f)
        }

        fun drawTrend(trend: String?, x: Float, y: Float, size: Float, color: Int) {
            val rotation = TrendArrowShape.rotationDegrees(trend) ?: return
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(TrendArrowShape.SCALE, TrendArrowShape.SCALE, size / 2, size / 2)
            canvas.rotate(rotation, size / 2, size / 2)
            TrendArrowShape.SEGMENTS.forEach {
                line(it.x0 * size, it.y0 * size, it.x1 * size, it.y1 * size,
                    color, size * TrendArrowShape.STROKE_FRACTION)
            }
            canvas.restore()
        }
    }

    fun icon(): ByteArray {
        val bitmap = createBitmap(48, 48)
        try {
            val drawing = Drawing(Canvas(bitmap))
            drawing.roundRect(RectF(2f, 2f, 46f, 46f), 12f, primary)
            drawing.text("G", 12f, 35f, 31f, background, strong = true)
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
