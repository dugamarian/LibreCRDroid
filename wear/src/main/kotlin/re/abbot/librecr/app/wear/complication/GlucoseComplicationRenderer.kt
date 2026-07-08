package re.abbot.librecr.app.wear.complication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.WearAppearanceSettings
import re.abbot.librecr.app.data.WearDisplayFontWeight
import re.abbot.librecr.protocol.TrendArrowShape
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object GlucoseComplicationRenderer {
    private const val STALE_AFTER_MS = 6 * 60_000L
    private const val TIMESTAMP_TEXT_SCALE = 0.66f
    private val COLOR_ERROR = 0xFFE53935.toInt()
    private val COLOR_WARN = 0xFFFFB300.toInt()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    fun isFresh(reading: SensorStateStore.LastGlucose?, nowMs: Long = System.currentTimeMillis()): Boolean =
        reading != null && reading.receivedAtMs > 0L && nowMs - reading.receivedAtMs < STALE_AFTER_MS

    fun timeText(reading: SensorStateStore.LastGlucose?): String =
        timeText(reading?.receivedAtMs ?: 0L)

    private fun timeText(atMs: Long): String =
        atMs.takeIf { it > 0L }?.let { timeFormatter.format(Instant.ofEpochMilli(it)) } ?: "--:--"

    fun deltaText(reading: SensorStateStore.LastGlucose?, unit: GlucoseUnit = GlucoseUnit.MG_DL): String =
        formatDelta(reading?.deltaMgDlPerMin, unit)

    fun valueText(reading: SensorStateStore.LastGlucose?, unit: GlucoseUnit = GlucoseUnit.MG_DL): String =
        if (isFresh(reading)) reading?.mgDL?.let { unit.format(it) } ?: "OOR" else "OOR"

    fun buildAgeDeltaBitmap(
        context: Context,
        reading: SensorStateStore.LastGlucose?,
        settings: WearAppearanceSettings = WearAppearanceSettings(),
        size: Int = 150,
        attention: Libre3SensorAttention = Libre3SensorAttention.None,
        sensorError: Boolean = false,
        sensorErrorAtMs: Long = 0L,
        unavailable: Boolean = false,
        unavailableAtMs: Long = 0L,
    ): Bitmap {
        val fresh = isFresh(reading)
        val statusAtMs = when {
            sensorError -> sensorErrorAtMs
            unavailable -> unavailableAtMs
            else -> 0L
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        canvas.drawColor(Color.TRANSPARENT)

        paint.color = when {
            sensorError -> COLOR_ERROR
            unavailable -> settings.timestampStaleColor
            else -> settings.timestampColor(stale = !fresh)
        }
        paint.typeface = typefaceFor(context, settings.fontWeight)
        paint.textSize = size * 0.34f * TIMESTAMP_TEXT_SCALE
        val time = if (sensorError || unavailable) timeText(statusAtMs) else timeText(reading)
        drawCenteredText(canvas, time, size / 2f, size * 0.27f, paint)
        paint.color = if (sensorError || unavailable) settings.timestampStaleColor else settings.deltaColorFor(reading?.mgDL)
        paint.typeface = typefaceFor(context, settings.fontWeight)
        paint.textSize = size * 0.36f
        val delta = when {
            sensorError -> "--"
            unavailable -> "OOR"
            else -> deltaText(reading, settings.unit)
        }
        drawCenteredText(canvas, delta, size / 2f, size * 0.67f, paint)
        attentionBadge(attention)?.let { drawAttentionDot(canvas, size, it.color) }
        return bitmap
    }

    fun buildTrendValueBitmap(
        context: Context,
        reading: SensorStateStore.LastGlucose?,
        settings: WearAppearanceSettings = WearAppearanceSettings(),
        size: Int = 150,
        attention: Libre3SensorAttention = Libre3SensorAttention.None,
        sensorError: Boolean = false,
        unavailable: Boolean = false,
    ): Bitmap {
        val fresh = isFresh(reading)
        val badge = attentionBadge(attention)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = typefaceFor(context, settings.fontWeight)
        }
        canvas.drawColor(Color.TRANSPARENT)

        canvas.save()
        canvas.translate(0f, -size * 0.15f)

        val arrow = buildArrowBitmap(if (sensorError || unavailable) null else reading, settings, (size * 0.38f).roundToInt())
        canvas.drawBitmap(arrow, (size - arrow.width) / 2f, size * 0.17f, null)
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        val centerText = when {
            sensorError -> "S.E."
            unavailable -> "OOR"
            !fresh && badge != null -> badge.shortText
            else -> valueText(reading, settings.unit)
        }
        paint.color = when {
            sensorError -> COLOR_ERROR
            unavailable -> settings.timestampStaleColor
            !fresh && badge != null -> badge.color
            fresh -> settings.glucoseColorFor(reading?.mgDL)
            else -> settings.timestampStaleColor
        }
        paint.typeface = typefaceFor(context, settings.fontWeight)
        paint.textSize = if (sensorError || unavailable || (!fresh && badge != null)) size * 0.36f else size * 0.50f
        drawCenteredText(canvas, centerText, size / 2f, size * 0.74f, paint)
        // Even with a fresh value, flag attention with a corner dot so the number stays visible.
        badge?.let { drawAttentionDot(canvas, size, it.color) }

        canvas.restore()
        return bitmap
    }

    fun buildArrowBitmap(
        reading: SensorStateStore.LastGlucose?,
        settings: WearAppearanceSettings = WearAppearanceSettings(),
        size: Int = 150,
    ): Bitmap =
        buildArrowBitmap(
            trend = reading?.trend,
            size = size,
            color = if (isFresh(reading)) settings.trendColorFor(reading?.mgDL) else settings.timestampStaleColor,
            fontWeight = settings.fontWeight,
        )

    fun buildArrowBitmap(
        trend: String?,
        size: Int = 150,
        color: Int = Color.WHITE,
        fontWeight: WearDisplayFontWeight = WearDisplayFontWeight.NORMAL,
    ): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = size * 0.094f * fontWeight.strokeScale()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawColor(Color.TRANSPARENT)
            drawAodArrow(canvas, paint, trend, 0f, 0f, size.toFloat())
        }

    fun contentDescription(
        label: String,
        reading: SensorStateStore.LastGlucose?,
        attention: Libre3SensorAttention = Libre3SensorAttention.None,
        unit: GlucoseUnit = GlucoseUnit.MG_DL,
        sensorError: Boolean = false,
        unavailable: Boolean = false,
    ): String {
        val base = when {
            sensorError -> "$label sensor error"
            unavailable -> "$label out of range"
            reading != null ->
                "$label ${unit.formatWithUnit(reading.mgDL)} ${trendLabel(reading.trend)} ${timeText(reading)} ${deltaText(reading, unit)}"
            else -> "$label no glucose"
        }
        return attentionLabel(attention)?.let { "$base, $it" } ?: base
    }

    private data class AttentionBadge(val color: Int, val shortText: String)

    /** Visual badge for a sensor-attention state; null when nothing needs the user's attention. */
    private fun attentionBadge(attention: Libre3SensorAttention): AttentionBadge? = when (attention) {
        Libre3SensorAttention.None -> null
        Libre3SensorAttention.CheckSensor -> AttentionBadge(COLOR_WARN, "CHK")
        Libre3SensorAttention.SensorEnded -> AttentionBadge(COLOR_ERROR, "END")
        Libre3SensorAttention.ReplaceSensor -> AttentionBadge(COLOR_ERROR, "REPL")
        is Libre3SensorAttention.Unknown -> AttentionBadge(COLOR_ERROR, "ERR")
    }

    private fun attentionLabel(attention: Libre3SensorAttention): String? = when (attention) {
        Libre3SensorAttention.None -> null
        Libre3SensorAttention.CheckSensor -> "check sensor"
        Libre3SensorAttention.SensorEnded -> "sensor ended"
        Libre3SensorAttention.ReplaceSensor -> "replace sensor"
        is Libre3SensorAttention.Unknown -> "sensor error ${attention.code}"
    }

    private fun drawAttentionDot(canvas: Canvas, size: Int, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size * 0.83f, size * 0.17f, size * 0.10f, paint)
    }

    private fun trendLabel(trend: String?): String = when (TrendArrowShape.resolvedTrend(trend)) {
        "FALLING_QUICKLY" -> "falling quickly"
        "FALLING" -> "falling"
        "STABLE" -> "stable"
        "RISING" -> "rising"
        "RISING_QUICKLY" -> "rising quickly"
        else -> "unknown trend"
    }

    private fun drawAodArrow(
        canvas: Canvas,
        paint: Paint,
        trend: String?,
        left: Float,
        top: Float,
        size: Float,
    ) {
        val rotation = TrendArrowShape.rotationDegrees(trend) ?: return
        val cx = left + size / 2f
        val cy = top + size / 2f
        canvas.save()
        canvas.scale(TrendArrowShape.SCALE, TrendArrowShape.SCALE, cx, cy)
        canvas.rotate(rotation, cx, cy)
        TrendArrowShape.SEGMENTS.forEach { s ->
            canvas.drawLine(left + s.x0 * size, top + s.y0 * size, left + s.x1 * size, top + s.y1 * size, paint)
        }
        canvas.restore()
    }

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, x, y - bounds.exactCenterY(), paint)
    }

    private fun typefaceFor(context: Context, fontWeight: WearDisplayFontWeight): Typeface {
        val baseFont = if (fontWeight.weight >= WearDisplayFontWeight.SEMIBOLD.weight) {
            R.font.google_sans_rounded_bold
        } else {
            R.font.google_sans_rounded_regular
        }
        val roundedTypeface = runCatching { ResourcesCompat.getFont(context, baseFont) }.getOrNull()
            ?: Typeface.SANS_SERIF
        return Typeface.create(roundedTypeface, fontWeight.weight, false)
    }

    private fun WearDisplayFontWeight.strokeScale(): Float =
        (weight / 400f).coerceIn(0.68f, 1.45f)

    private fun formatDelta(delta: Double?, unit: GlucoseUnit = GlucoseUnit.MG_DL): String {
        if (delta == null) return "--"
        return unit.formatDelta(delta.coerceIn(-99.0, 99.0))
    }
}
