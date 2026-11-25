package re.abbot.librecr.app.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.PreviewSizeMode
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.flow.first
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.R
import re.abbot.librecr.protocol.TrendArrowShape
import kotlin.math.roundToInt

private val CardPadding = 10.dp
private val CaptionGap = 6.dp

/** A single glanceable surface; no scroll, media session, car template or overlay permission. */
abstract class GlucoseWidget : GlanceAppWidget() {
    override val stateDefinition = null

    // Exact sizes let the sparkline bitmap match its on-screen pixels instead of being stretched.
    override val sizeMode: SizeMode = SizeMode.Exact

    override val previewSizeMode: PreviewSizeMode
        get() = SizeMode.Responsive(previewSizes)

    internal abstract val previewSizes: Set<DpSize>

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        LibreCR.init(context)
        GlucoseWidgetUpdater.ensureChartHistory()
        val models = GlucoseWidgetUpdater.models()
        val initial = models.first()
        provideContent {
            val model by models.collectAsState(initial)
            val refreshTime by GlucoseWidgetUpdater.refreshTime.collectAsState()
            GlucoseWidgetContent(glucoseWidgetUi(model, maxOf(refreshTime, System.currentTimeMillis())))
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { GlucoseWidgetContent(previewGlucoseWidgetUi(System.currentTimeMillis())) }
    }
}

class CompactGlucoseWidget : GlucoseWidget() {
    override val previewSizes = setOf(DpSize(180.dp, 144.dp), DpSize(240.dp, 180.dp))
}

class WideGlucoseWidget : GlucoseWidget() {
    override val previewSizes = setOf(DpSize(280.dp, 144.dp), DpSize(340.dp, 190.dp))
}

abstract class GlucoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        LibreCR.init(context)
        GlucoseWidgetUpdater.onWidgetsChanged(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        GlucoseWidgetUpdater.onWidgetsChanged(context)
    }
}

class CompactGlucoseWidgetReceiver : GlucoseWidgetReceiver() {
    override val glanceAppWidget = CompactGlucoseWidget()
}

class WideGlucoseWidgetReceiver : GlucoseWidgetReceiver() {
    override val glanceAppWidget = WideGlucoseWidget()
}

private enum class WidgetLayout { BAR, TILE, COMPACT, WIDE, TALL }

/**
 * Both providers share one responsive layout set, from a one-row bar to a large chart card, so
 * the widget fills any grid cell a phone launcher or a car host hands it.
 */
private fun widgetLayout(size: DpSize): WidgetLayout = when {
    size.height < 100.dp -> WidgetLayout.BAR
    size.width < 150.dp -> WidgetLayout.TILE
    size.height >= 260.dp && size.width >= 170.dp -> WidgetLayout.TALL
    size.width >= 280.dp -> WidgetLayout.WIDE
    else -> WidgetLayout.COMPACT
}

@Composable
internal fun GlucoseWidgetContent(ui: GlucoseWidgetUi) {
    val context = LocalContext.current
    val size = LocalSize.current
    Box(
        GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .widgetSurface()
            .clickable(actionStartActivity<MainActivity>())
            .semantics { contentDescription = widgetDescription(context, ui) },
    ) {
        when (widgetLayout(size)) {
            WidgetLayout.BAR -> BarLayout(ui, size)
            WidgetLayout.TILE -> TileLayout(ui, size)
            WidgetLayout.COMPACT -> CompactLayout(ui, size)
            WidgetLayout.WIDE -> WideLayout(ui, size)
            WidgetLayout.TALL -> TallLayout(ui, size)
        }
    }
}

/** Single row for 2×1 / 4×1 cells and short car-grid rows; details join as width allows. */
@Composable
private fun BarLayout(ui: GlucoseWidgetUi, size: DpSize) {
    val context = LocalContext.current
    val horizontal = if (size.width < 180.dp) 12.dp else 16.dp
    val vertical = 6.dp
    val width = size.width - horizontal * 2
    val height = size.height - vertical * 2
    val badge = minOf(height, 44.dp).coerceAtLeast(16.dp)
    val details = width >= 240.dp && height >= context.lineHeight(SUPPORTING_SP) + context.lineHeight(AGE_SP)
    val chip = width >= 340.dp && height >= context.chipHeight()
    val valueWidth = width - badge - 8.dp - (if (details) 124.dp else 0.dp) - (if (chip) 124.dp else 0.dp)
    Row(
        GlanceModifier.fillMaxSize().padding(horizontal = horizontal, vertical = vertical),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        StateBadge(ui, badge)
        Spacer(GlanceModifier.width(8.dp))
        ReadingValue(ui, fittedValueSp(context, ui.value, 44f, valueWidth, height))
        if (details) {
            Spacer(GlanceModifier.width(12.dp))
            Column {
                SupportingLine(ui)
                ReadingAge(ui)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        if (chip) StateChip(ui)
    }
}

/** Narrow square cell (2×2 on dense grids): trend shape on top, the reading below it. */
@Composable
private fun TileLayout(ui: GlucoseWidgetUi, size: DpSize) {
    val context = LocalContext.current
    val padding = 12.dp
    val width = size.width - padding * 2
    val height = size.height - padding * 2
    val badge = (width * 0.42f).coerceIn(28.dp, 44.dp)
    val ageHeight = context.lineHeight(AGE_SP)
    val showAge = ui.measuredAtMs != null && height - badge - ageHeight >= context.lineHeight(MIN_VALUE_SP + 8f)
    val valueHeight = height - badge - (if (showAge) ageHeight else 0.dp)
    Column(GlanceModifier.fillMaxSize().padding(padding)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            StateBadge(ui, badge)
            Spacer(GlanceModifier.defaultWeight())
            if (ui.status == WidgetReadingStatus.CURRENT) {
                Text(
                    ui.unit,
                    style = TextStyle(color = WidgetColors.onSurfaceVariant, fontSize = AGE_SP.sp, fontFamily = WidgetFonts.body),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        ReadingValue(ui, fittedValueSp(context, ui.value, 48f, width, valueHeight))
        if (showAge) ReadingAge(ui)
    }
}

/** Reading, trend shape and state chip; rows drop out before anything would be cropped. */
@Composable
private fun CompactLayout(ui: GlucoseWidgetUi, size: DpSize) {
    val context = LocalContext.current
    val padding = if (size.width < 200.dp || size.height < 150.dp) 14.dp else 16.dp
    val width = size.width - padding * 2
    val height = size.height - padding * 2
    val roomy = height >= 150.dp
    val badge = (if (roomy) 56.dp else 44.dp).coerceAtMost(width * 0.34f)
    val minValue = context.lineHeight(MIN_VALUE_SP + 8f)
    var valueHeight = height - context.lineHeight(SUPPORTING_SP)
    val warmup = ui.warmupProgress?.takeIf { valueHeight - WarmupHeight >= minValue }
    if (warmup != null) valueHeight -= WarmupHeight
    val bottomRow = valueHeight - context.chipHeight() - 6.dp >= minValue
    if (bottomRow) valueHeight -= context.chipHeight() + 6.dp
    val sp = fittedValueSp(context, ui.value, if (roomy) 60f else 52f, width - badge - 8.dp, valueHeight)
    Column(GlanceModifier.fillMaxSize().padding(padding)) {
        ValueRow(ui, sp, badge)
        SupportingLine(ui)
        warmup?.let {
            Spacer(GlanceModifier.height(8.dp))
            WarmupBar(it, width)
        }
        Spacer(GlanceModifier.defaultWeight())
        if (bottomRow) {
            // Too narrow for both: a current reading keeps its age (color and shape already carry the
            // range), any other state keeps the chip that explains it.
            val both = width >= (210f * context.resources.configuration.fontScale).dp
            val chip = both || ui.status != WidgetReadingStatus.CURRENT || ui.measuredAtMs == null
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                if (chip) StateChip(ui)
                Spacer(GlanceModifier.defaultWeight())
                if (both || !chip) ReadingAge(ui)
            }
        }
    }
}

/** Reading column beside a three-hour sparkline card. */
@Composable
private fun WideLayout(ui: GlucoseWidgetUi, size: DpSize) {
    val context = LocalContext.current
    val padding = 16.dp
    val gap = 12.dp
    val height = size.height - padding * 2
    val badge = if (height >= 140.dp) 52.dp else 44.dp
    val heroWidth = (size.width * 0.42f).coerceIn(136.dp, 184.dp)
    val cardWidth = size.width - padding * 2 - heroWidth - gap
    val minValue = context.lineHeight(MIN_VALUE_SP + 8f)
    var valueHeight = height - context.lineHeight(SUPPORTING_SP)
    val warmup = ui.warmupProgress?.takeIf { valueHeight - WarmupHeight >= minValue }
    if (warmup != null) valueHeight -= WarmupHeight
    val chip = valueHeight - context.chipHeight() - 6.dp >= minValue
    if (chip) valueHeight -= context.chipHeight() + 6.dp
    val sp = fittedValueSp(context, ui.value, 56f, heroWidth - badge - 8.dp, valueHeight)
    val plot = DpSize(cardWidth - CardPadding * 2, height - CardPadding * 2 - context.captionHeight() - CaptionGap)
    Row(GlanceModifier.fillMaxSize().padding(padding)) {
        Column(GlanceModifier.width(heroWidth).fillMaxHeight()) {
            ValueRow(ui, sp, badge)
            SupportingLine(ui)
            warmup?.let {
                Spacer(GlanceModifier.height(8.dp))
                WarmupBar(it, heroWidth)
            }
            Spacer(GlanceModifier.defaultWeight())
            if (chip) StateChip(ui)
        }
        Spacer(GlanceModifier.width(gap))
        ChartCard(ui, GlanceModifier.width(cardWidth).fillMaxHeight(), plot)
    }
}

/** Large reading on top, the sparkline card filling the rest; the brand header only when roomy. */
@Composable
private fun TallLayout(ui: GlucoseWidgetUi, size: DpSize) {
    val context = LocalContext.current
    val padding = 16.dp
    val width = size.width - padding * 2
    val height = size.height - padding * 2
    val wide = width >= 240.dp
    val badge = if (wide) 60.dp else 52.dp
    val showHeader = size.height >= 330.dp
    val warmup = ui.warmupProgress
    val rows = (if (showHeader) 20.dp + 12.dp else 0.dp) + context.lineHeight(SUPPORTING_SP) + 10.dp +
        context.chipHeight() + (if (warmup != null) 10.dp + 6.dp else 0.dp) + 12.dp
    val sp = fittedValueSp(context, ui.value, if (wide) 64f else 56f, width - badge - 8.dp, height - rows - MinCardHeight)
    val card = height - rows - maxOf(context.lineHeight(sp), badge)
    // FillBounds absorbs the small difference between this estimate and the measured card.
    val plot = DpSize(width - CardPadding * 2, (card - CardPadding * 2 - context.captionHeight() - CaptionGap).coerceAtLeast(0.dp))
    Column(GlanceModifier.fillMaxSize().padding(padding)) {
        if (showHeader) {
            BrandRow()
            Spacer(GlanceModifier.height(12.dp))
        }
        ValueRow(ui, sp, badge)
        SupportingLine(ui)
        Spacer(GlanceModifier.height(10.dp))
        StateChip(ui)
        warmup?.let {
            Spacer(GlanceModifier.height(10.dp))
            WarmupBar(it, width)
        }
        Spacer(GlanceModifier.height(12.dp))
        ChartCard(ui, GlanceModifier.fillMaxWidth().defaultWeight(), plot)
    }
}

@Composable
private fun BrandRow() {
    val context = LocalContext.current
    Row(GlanceModifier.height(20.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Image(
            ImageProvider(R.drawable.glucose_widget_ic_brand),
            contentDescription = null,
            modifier = GlanceModifier.size(18.dp),
            colorFilter = ColorFilter.tint(WidgetColors.primary),
        )
        Spacer(GlanceModifier.width(6.dp))
        Text(
            context.getString(R.string.widget_brand),
            style = TextStyle(color = WidgetColors.onSurface, fontSize = 14.sp, fontFamily = WidgetFonts.title),
            maxLines = 1,
        )
    }
}

@Composable
private fun ValueRow(ui: GlucoseWidgetUi, sp: Float, badge: Dp) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        ReadingValue(ui, sp)
        Spacer(GlanceModifier.defaultWeight())
        StateBadge(ui, badge)
    }
}

@Composable
private fun ReadingValue(ui: GlucoseWidgetUi, sp: Float) {
    Text(
        ui.value,
        style = TextStyle(
            color = ui.range?.tone?.accent ?: WidgetColors.onSurfaceVariant,
            fontSize = sp.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = WidgetFonts.display,
        ),
        maxLines = 1,
    )
}

/** TextView line height including font padding; generous so Google Sans Flex never clips. */
private const val LINE_EM = 1.36f
private const val SUPPORTING_SP = 13f
private const val AGE_SP = 12f
private const val MIN_VALUE_SP = 18f
private val WarmupHeight = 8.dp + 6.dp
private val MinCardHeight = 96.dp

private fun Context.lineHeight(sp: Float): Dp = (sp * LINE_EM * resources.configuration.fontScale).dp

private fun Context.chipHeight(): Dp = maxOf(28.dp, lineHeight(13f) + 6.dp)

private fun Context.captionHeight(): Dp = maxOf(16.dp, lineHeight(AGE_SP))

/**
 * Glance has no auto-sizing text: estimate the glyph run so mmol/L values and "NA" fit both the
 * width and the height left by the other rows (WT-4: nothing cropped at any size or font scale).
 */
private fun fittedValueSp(context: Context, text: String, maxSp: Float, width: Dp, height: Dp): Float {
    val fontScale = context.resources.configuration.fontScale
    val ems = text.sumOf { char ->
        when {
            char.isDigit() -> 0.62
            char == '.' || char == ',' -> 0.3
            else -> 0.8
        }
    }.toFloat().coerceAtLeast(0.6f)
    val byWidth = width.value / (ems * fontScale)
    val byHeight = height.value / (LINE_EM * fontScale)
    return minOf(maxSp, byWidth, byHeight).coerceAtLeast(MIN_VALUE_SP)
}

/** Unit and rate for a current reading, or the warmup countdown; null when there is neither. */
internal fun supportingText(context: Context, ui: GlucoseWidgetUi, showUnit: Boolean = true): String? = when {
    ui.status == WidgetReadingStatus.CURRENT ->
        listOfNotNull(
            ui.unit.takeIf { showUnit },
            ui.delta?.let { context.getString(R.string.widget_delta_per_minute, it) },
        ).joinToString(" · ").ifEmpty { null }
    ui.warmupRemainingMinutes != null -> context.getString(R.string.widget_warmup_remaining, ui.warmupRemainingMinutes)
    else -> null
}

@Composable
private fun SupportingLine(ui: GlucoseWidgetUi) {
    val text = supportingText(LocalContext.current, ui) ?: return
    Text(
        text,
        style = TextStyle(color = WidgetColors.onSurfaceVariant, fontSize = SUPPORTING_SP.sp, fontFamily = WidgetFonts.body),
        maxLines = 1,
    )
}

internal class BadgeStyle(
    @param:DrawableRes val shape: Int,
    val container: ColorProvider,
    val content: ColorProvider,
    /** Null draws the sensor trend arrow instead of an icon. */
    @param:DrawableRes val icon: Int?,
)

/**
 * In range sits in a calm cookie, out of range in a burst, so the state does not depend on color
 * alone; non-glucose states use a plain circle with a status icon.
 */
internal fun badgeStyle(ui: GlucoseWidgetUi): BadgeStyle = when (ui.status) {
    WidgetReadingStatus.CURRENT -> {
        val range = ui.range ?: WidgetGlucoseRange.IN_RANGE
        BadgeStyle(
            shape = if (range == WidgetGlucoseRange.IN_RANGE) R.drawable.glucose_widget_shape_cookie else R.drawable.glucose_widget_shape_burst,
            container = range.tone.container,
            content = range.tone.onContainer,
            icon = if (TrendArrowShape.hasArrow(ui.trend)) null else R.drawable.glucose_widget_ic_dash,
        )
    }
    WidgetReadingStatus.STALE -> BadgeStyle(R.drawable.glucose_widget_shape_circle, WidgetColors.chip,
        WidgetColors.onChip, R.drawable.glucose_widget_ic_schedule)
    WidgetReadingStatus.WARMUP -> BadgeStyle(R.drawable.glucose_widget_shape_circle, WidgetColors.tertiaryContainer,
        WidgetColors.onTertiaryContainer, R.drawable.glucose_widget_ic_hourglass)
    WidgetReadingStatus.SENSOR_ERROR -> BadgeStyle(R.drawable.glucose_widget_shape_burst, WidgetColors.errorContainer,
        WidgetColors.onErrorContainer, R.drawable.glucose_widget_ic_priority)
    WidgetReadingStatus.NO_READING, WidgetReadingStatus.UNAVAILABLE -> BadgeStyle(R.drawable.glucose_widget_shape_circle,
        WidgetColors.chip, WidgetColors.onChip, R.drawable.glucose_widget_ic_dash)
}

@Composable
private fun StateBadge(ui: GlucoseWidgetUi, size: Dp) {
    val style = badgeStyle(ui)
    Box(
        GlanceModifier.size(size).shapeBackground(style.shape, style.container),
        contentAlignment = Alignment.Center,
    ) {
        val icon = style.icon
        if (icon == null) {
            TrendArrow(ui.trend, style.content, size * 0.6f)
        } else {
            Image(
                ImageProvider(icon),
                contentDescription = null,
                modifier = GlanceModifier.size(size * 0.46f),
                colorFilter = ColorFilter.tint(style.content),
            )
        }
    }
}

@Composable
private fun TrendArrow(trend: String?, color: ColorProvider, size: Dp) {
    val context = LocalContext.current
    val rotation = TrendArrowShape.rotationDegrees(trend) ?: return
    val sizePx = (size.value * context.resources.displayMetrics.density).roundToInt().coerceIn(24, 256)
    // Alpha-only: the host tints it, so one bitmap serves light and dark mode.
    val bitmap = remember(rotation, sizePx) {
        createBitmap(sizePx, sizePx, Bitmap.Config.ALPHA_8).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = sizePx * TrendArrowShape.STROKE_FRACTION
                strokeCap = Paint.Cap.ROUND
            }
            canvas.scale(TrendArrowShape.SCALE, TrendArrowShape.SCALE, sizePx / 2f, sizePx / 2f)
            canvas.rotate(rotation, sizePx / 2f, sizePx / 2f)
            TrendArrowShape.SEGMENTS.forEach {
                canvas.drawLine(it.x0 * sizePx, it.y0 * sizePx, it.x1 * sizePx, it.y1 * sizePx, paint)
            }
        }
    }
    Image(
        ImageProvider(bitmap),
        contentDescription = context.getString(trendLabel(trend)),
        modifier = GlanceModifier.size(size),
        colorFilter = ColorFilter.tint(color),
    )
}

internal class ChipStyle(
    @param:StringRes val label: Int,
    val container: ColorProvider,
    val content: ColorProvider,
    /** The range's accent dot; status chips have none. */
    val dot: ColorProvider?,
)

/** The range for a current reading, otherwise the status that replaced it. */
internal fun chipStyle(ui: GlucoseWidgetUi): ChipStyle {
    ui.range?.let { return ChipStyle(it.label, it.tone.container, it.tone.onContainer, it.tone.accent) }
    val (container, content) = when (ui.status) {
        WidgetReadingStatus.STALE, WidgetReadingStatus.SENSOR_ERROR -> WidgetColors.errorContainer to WidgetColors.onErrorContainer
        WidgetReadingStatus.WARMUP -> WidgetColors.tertiaryContainer to WidgetColors.onTertiaryContainer
        else -> WidgetColors.chip to WidgetColors.onChip
    }
    return ChipStyle(statusLabel(ui.status), container, content, dot = null)
}

@Composable
private fun StateChip(ui: GlucoseWidgetUi) {
    val context = LocalContext.current
    val style = chipStyle(ui)
    Chip(style.container) {
        style.dot?.let {
            Image(
                ImageProvider(R.drawable.glucose_widget_shape_circle),
                contentDescription = null,
                modifier = GlanceModifier.size(8.dp),
                colorFilter = ColorFilter.tint(it),
            )
            Spacer(GlanceModifier.width(6.dp))
        }
        ChipText(context.getString(style.label), style.content)
    }
}

@Composable
private fun Chip(container: ColorProvider, content: @Composable () -> Unit) {
    Row(
        GlanceModifier.height(LocalContext.current.chipHeight())
            .shapeBackground(R.drawable.glucose_widget_shape_pill, container)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) { content() }
}

@Composable
private fun ChipText(text: String, color: ColorProvider) {
    Text(text, style = TextStyle(color = color, fontSize = 13.sp, fontFamily = WidgetFonts.label), maxLines = 1)
}

/** Track and fill pills; the fill width is exact because the widget knows its own size. */
@Composable
private fun WarmupBar(progress: Float, width: Dp) {
    Box(GlanceModifier.fillMaxWidth().height(6.dp).shapeBackground(R.drawable.glucose_widget_shape_pill, WidgetColors.tertiaryContainer)) {
        Box(
            GlanceModifier.width((width * progress).coerceAtLeast(6.dp)).height(6.dp)
                .shapeBackground(R.drawable.glucose_widget_shape_pill, WidgetColors.tertiary),
        ) {}
    }
}

@Composable
private fun ChartCard(ui: GlucoseWidgetUi, modifier: GlanceModifier, plot: DpSize) {
    val context = LocalContext.current
    val label = chartWindowLabel(plot.width)
    Column(modifier.widgetTile().padding(CardPadding)) {
        Row(GlanceModifier.fillMaxWidth().height(context.captionHeight()), verticalAlignment = Alignment.Vertical.CenterVertically) {
            label?.let {
                Text(
                    context.getString(it),
                    style = TextStyle(color = WidgetColors.onSurfaceVariant, fontSize = AGE_SP.sp, fontFamily = WidgetFonts.label),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            ReadingAge(ui, withIcon = true)
        }
        Spacer(GlanceModifier.height(CaptionGap))
        Sparkline(ui, plot, GlanceModifier.fillMaxWidth().defaultWeight())
    }
}

/** The age clock is the caption's priority; the window label shortens, then hides, on narrow cards. */
@StringRes
internal fun chartWindowLabel(plotWidth: Dp): Int? = when {
    plotWidth >= 210.dp -> R.string.widget_chart_window
    plotWidth >= 130.dp -> R.string.widget_chart_window_short
    else -> null
}

@Composable
private fun Sparkline(ui: GlucoseWidgetUi, plot: DpSize, modifier: GlanceModifier) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val widthPx = (plot.width.value * density).roundToInt()
    val heightPx = (plot.height.value * density).roundToInt()
    val layers = remember(ui.chart, ui.targetLow, ui.targetHigh, widthPx, heightPx) {
        if (widthPx < 48 || heightPx < 24) {
            null
        } else {
            renderWidgetChart(ui.chart.points, ui.chart.startMs, ui.chart.endMs, ui.targetLow, ui.targetHigh,
                widthPx, heightPx, density)
        }
    }
    if (layers == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                context.getString(R.string.widget_chart_empty),
                style = TextStyle(color = WidgetColors.onSurfaceVariant, fontSize = AGE_SP.sp, fontFamily = WidgetFonts.body,
                    textAlign = TextAlign.Center),
                maxLines = 2,
            )
        }
        return
    }
    Box(modifier) {
        ChartLayer(layers.inRange, WidgetGlucoseRange.IN_RANGE.tone.accent)
        layers.below?.let { ChartLayer(it, WidgetGlucoseRange.LOW.tone.accent) }
        layers.above?.let { ChartLayer(it, WidgetGlucoseRange.HIGH.tone.accent) }
    }
}

@Composable
private fun ChartLayer(bitmap: Bitmap, color: ColorProvider) {
    Image(
        ImageProvider(bitmap),
        contentDescription = null,
        modifier = GlanceModifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
        colorFilter = ColorFilter.tint(color),
    )
}

@Composable
private fun ReadingAge(ui: GlucoseWidgetUi, withIcon: Boolean = false) {
    val context = LocalContext.current
    val measuredAtMs = ui.measuredAtMs ?: return
    val color = WidgetColors.onSurfaceVariant
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        if (withIcon) {
            Image(
                ImageProvider(R.drawable.glucose_widget_ic_schedule),
                contentDescription = null,
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(color),
            )
            Spacer(GlanceModifier.width(4.dp))
        }
        if (ui.preview) {
            Text(
                context.getString(R.string.age_minutes, 1),
                style = TextStyle(color = color, fontSize = AGE_SP.sp, fontFamily = WidgetFonts.body),
                maxLines = 1,
            )
        } else {
            AndroidRemoteViews(readingAgeViews(context, measuredAtMs, color))
        }
    }
}

/**
 * The HOST advances this clock even if our process is stopped. Its base is the original
 * measurement time, never the widget's refresh time. No per-second application work is needed.
 */
private fun readingAgeViews(context: Context, measuredAtMs: Long, color: ColorProvider): RemoteViews {
    val ageMs = (System.currentTimeMillis() - measuredAtMs).coerceAtLeast(0L)
    return RemoteViews(context.packageName, WidgetFonts.ageLayout).apply {
        setChronometer(
            R.id.widget_reading_age,
            SystemClock.elapsedRealtime() - ageMs,
            context.getString(R.string.widget_age_format),
            true,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Resolve both variants so a car host can select night mode independently of the phone.
            setColorInt(
                R.id.widget_reading_age,
                "setTextColor",
                widgetThemeColor(context, color, Configuration.UI_MODE_NIGHT_NO),
                widgetThemeColor(context, color, Configuration.UI_MODE_NIGHT_YES),
            )
        } else {
            setTextColor(R.id.widget_reading_age, color.getColor(context).toArgb())
        }
    }
}

/** Builds both RemoteViews color variants; the host chooses the active configuration at render time. */
private fun widgetThemeColor(context: Context, color: ColorProvider, nightMode: Int): Int {
    val configuration = Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
    }
    return color.getColor(context.createConfigurationContext(configuration)).toArgb()
}

private fun widgetDescription(context: Context, ui: GlucoseWidgetUi): String {
    val parts = mutableListOf(context.getString(R.string.widget_brand))
    if (ui.status == WidgetReadingStatus.CURRENT) {
        parts += "${ui.value} ${ui.unit}"
        if (TrendArrowShape.hasArrow(ui.trend)) parts += context.getString(trendLabel(ui.trend))
        ui.range?.let { parts += context.getString(it.label) }
    } else {
        parts += context.getString(statusLabel(ui.status))
    }
    return parts.joinToString(", ")
}

@StringRes
internal fun trendLabel(trend: String?): Int = when (TrendArrowShape.resolvedTrend(trend)) {
    "FALLING_QUICKLY" -> R.string.trend_falling_quickly
    "FALLING" -> R.string.trend_falling
    "STABLE" -> R.string.trend_stable
    "RISING" -> R.string.trend_rising
    "RISING_QUICKLY" -> R.string.trend_rising_quickly
    else -> R.string.trend_unknown
}

@StringRes
internal fun statusLabel(status: WidgetReadingStatus): Int = when (status) {
    WidgetReadingStatus.CURRENT -> R.string.widget_current
    WidgetReadingStatus.STALE -> R.string.widget_stale
    WidgetReadingStatus.WARMUP -> R.string.sensor_warming_up
    WidgetReadingStatus.SENSOR_ERROR -> R.string.sensor_error
    WidgetReadingStatus.NO_READING, WidgetReadingStatus.UNAVAILABLE -> R.string.widget_no_reading
}

internal val WidgetGlucoseRange.label: Int
    @StringRes get() = when (this) {
        WidgetGlucoseRange.VERY_LOW -> R.string.widget_range_very_low
        WidgetGlucoseRange.LOW -> R.string.widget_range_low
        WidgetGlucoseRange.IN_RANGE -> R.string.widget_range_in
        WidgetGlucoseRange.HIGH -> R.string.widget_range_high
        WidgetGlucoseRange.VERY_HIGH -> R.string.widget_range_very_high
    }
