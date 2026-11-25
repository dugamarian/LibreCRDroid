package re.abbot.librecr.app.live

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import re.abbot.librecr.protocol.TrendArrowShape

/** White on transparent, as required by OngoingActivity, including ambient mode. */
internal object WearLiveUpdateIcon {
    fun create(content: WearLiveUpdateContent): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        val rotation = TrendArrowShape.rotationDegrees(content.reading?.trend)
        if (rotation != null) {
            canvas.save()
            canvas.translate(35f, 0f)
            canvas.scale(26f, 26f)
            canvas.scale(TrendArrowShape.SCALE, TrendArrowShape.SCALE, 0.5f, 0.5f)
            canvas.rotate(rotation, 0.5f, 0.5f)
            paint.strokeWidth = TrendArrowShape.STROKE_FRACTION
            paint.strokeCap = Paint.Cap.ROUND
            TrendArrowShape.SEGMENTS.forEach {
                canvas.drawLine(it.x0, it.y0, it.x1, it.y1, paint)
            }
            canvas.restore()
        }
        paint.textSize = 42f
        fitText(canvas, content.value, 64f, paint)
        paint.textSize = 23f
        val secondary = when {
            content.reading != null -> "${content.delta} ${content.observationTime()}"
            content.hasFreshObservation -> content.observationTime()
            else -> content.delta
        }
        fitText(canvas, secondary, 91f, paint)
        return bitmap
    }

    private fun fitText(canvas: Canvas, text: String, baseline: Float, paint: Paint) {
        val width = paint.measureText(text)
        if (width > 90f) paint.textSize *= 90f / width
        canvas.drawText(text, 48f, baseline, paint)
    }
}
