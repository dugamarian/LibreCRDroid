package re.abbot.librecr.app.ui.standby

import android.app.AlarmManager
import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.delay
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.R
import re.abbot.librecr.app.ble.GlucoseDisplayStatus
import re.abbot.librecr.app.ble.GlucoseUi
import re.abbot.librecr.app.ble.isActiveGlucoseUnavailable
import re.abbot.librecr.app.ble.isSensorWarmupActive
import re.abbot.librecr.app.ble.isUnavailableForGlucoseDisplay
import re.abbot.librecr.app.data.AppSettings
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.isFreshGlucose
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.app.ui.common.drawTrendArrow
import re.abbot.librecr.app.ui.common.trendLabel
import re.abbot.librecr.app.ui.theme.DarkColors
import re.abbot.librecr.app.ui.theme.DarkGlucoseColors
import re.abbot.librecr.app.ui.theme.GlucoseColors
import re.abbot.librecr.app.widget.GlucoseWidgetUpdater
import re.abbot.librecr.app.widget.WIDGET_CHART_WINDOW_MS
import re.abbot.librecr.app.widget.WidgetChartBand
import re.abbot.librecr.app.widget.WidgetChartGeometry
import re.abbot.librecr.app.widget.WidgetChartPoint
import re.abbot.librecr.app.widget.WidgetGlucoseRange
import re.abbot.librecr.app.widget.glucoseWidgetUi
import re.abbot.librecr.app.widget.label
import re.abbot.librecr.app.widget.widgetChartBand
import re.abbot.librecr.app.widget.widgetChartGeometry
import re.abbot.librecr.app.widget.widgetGlucoseRange
import re.abbot.librecr.protocol.TrendArrowShape
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention
import re.abbot.librecr.protocol.dataplane.SensorLifecycle
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * StandBy display with a clock and a three-hour glucose trace, on true black in both palettes.
 *
 * Motion is event-driven only: digits roll when the minute or the reading changes, the trend
 * arrow turns to a new trend, the chart draws itself in once. There is no looping animation, so
 * between events the panel idles at its lowest refresh rate; the anti burn-in drift glides over
 * several seconds and is imperceptible.
 */

@Composable
internal fun StandbyScreen(
    settings: AppSettings,
    ambientMode: StandbyAmbientMode,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val local by LibreCR.manager.glucose.collectAsState()
    val remote by LibreCR.store.lastGlucoseFlow.collectAsState(initial = null)
    val session by LibreCR.store.sessionFlow.collectAsState(initial = null)
    val warmup by LibreCR.store.sensorWarmupFlow.collectAsState(initial = null)
    val sensorStatus by LibreCR.store.sensorStatusFlow.collectAsState(initial = null)
    val connectionState by LibreCR.manager.state.collectAsState()
    // The widget/AOD model carries the merged three-hour series (SQLite minutes + the live ring).
    val chartModels = remember { GlucoseWidgetUpdater.models() }
    val chartModel by chartModels.collectAsState(initial = null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var nextAlarmAtMs by remember { mutableStateOf<Long?>(null) }
    var batteryPercent by remember { mutableStateOf<Int?>(null) }
    val attention = sensorStatus?.attention ?: Libre3SensorAttention.None
    val hasSensorAttention = attention != Libre3SensorAttention.None
    val liveUnavailable = local.isActiveGlucoseUnavailable(now)
    // A fresh unavailable live reading is the newest glucose state, so do not fall back to an older
    // stored value. Only patch-status attention is allowed to produce a sensor-error label.
    val glucose = when {
        hasSensorAttention -> local?.copy(mgDL = null)
            ?: remote?.takeIf { isFreshGlucose(it.receivedAtMs, now) }?.toGlucoseUi()?.copy(mgDL = null, usable = false)
        liveUnavailable -> local?.copy(mgDL = null)
        else -> local
            ?.takeIf { it.usable && it.mgDL != null && isFreshGlucose(it.receivedAtMs, now) }
            ?: remote?.takeIf { isFreshGlucose(it.receivedAtMs, now) }?.toGlucoseUi()
    }
    val displayStatus = when {
        hasSensorAttention -> GlucoseDisplayStatus.SENSOR_ERROR
        glucose?.mgDL == null &&
            isSensorWarmupActive(
                warmup?.startedAtMs,
                session?.warmupMinutes ?: SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES,
                now,
            ) -> GlucoseDisplayStatus.SENSOR_WARMUP
        liveUnavailable || (glucose == null && connectionState.isUnavailableForGlucoseDisplay()) -> GlucoseDisplayStatus.OUT_OF_RANGE
        else -> GlucoseDisplayStatus.NORMAL
    }

    LaunchedEffect(Unit) {
        // Tick on the wall-clock second, so the minute rolls over exactly at :00.
        while (true) {
            delay(1_000L - System.currentTimeMillis() % 1_000L)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(Unit) {
        // SQLite minutes are reloaded at most once a minute; the live ring alone covers ~1 h.
        while (true) {
            GlucoseWidgetUpdater.ensureChartHistory()
            delay(CHART_REFRESH_MS)
        }
    }
    val minute = now / 60_000L
    LaunchedEffect(minute) {
        nextAlarmAtMs = context.nextAlarmAtMs()
        batteryPercent = context.batteryPercent()
    }

    val configuration = LocalConfiguration.current
    val locale = configuration.locales.get(0) ?: Locale.getDefault()
    val use24Hour = remember(minute, configuration) { DateFormat.is24HourFormat(context) }
    val clockPattern = remember(locale, use24Hour) { standbyClockPattern(locale, use24Hour) }
    val time = remember(minute, clockPattern, locale) { SimpleDateFormat(clockPattern, locale).format(Date(now)) }
    val clockTemplate = remember(clockPattern, locale) {
        SimpleDateFormat(clockPattern, locale).format(clockTemplateDate())
    }
    val date = remember(minute, locale) { standbyDate(locale, now) }
    val nextAlarm = remember(nextAlarmAtMs, minute, use24Hour) {
        nextAlarmAtMs
            ?.takeIf { it - now in 0..NEXT_ALARM_HORIZON_MS }
            ?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
    }
    val chart = remember(chartModel, minute) {
        chartModel?.let { model ->
            val series = glucoseWidgetUi(model, System.currentTimeMillis()).chart
            widgetChartGeometry(series.points, series.startMs, series.endMs, model.targetLow, model.targetHigh)
        }
    }

    val mgDl = glucose?.mgDL
    val hero = when {
        displayStatus == GlucoseDisplayStatus.SENSOR_WARMUP -> StandbyGlucose.Missing
        glucose == null -> StandbyGlucose.Missing
        mgDl == null && displayStatus == GlucoseDisplayStatus.SENSOR_ERROR ->
            StandbyGlucose.Code(stringResource(R.string.sensor_error_short))
        mgDl == null && displayStatus == GlucoseDisplayStatus.OUT_OF_RANGE -> StandbyGlucose.Code("NA")
        mgDl == null -> StandbyGlucose.Missing
        else -> StandbyGlucose.Value(
            text = settings.unit.format(mgDl),
            trend = TrendArrowShape.resolvedTrend(glucose.trend),
            range = widgetGlucoseRange(mgDl, settings.targetLow, settings.targetHigh),
        )
    }
    val status = when {
        displayStatus == GlucoseDisplayStatus.SENSOR_WARMUP -> stringResource(R.string.sensor_warming_up)
        displayStatus == GlucoseDisplayStatus.SENSOR_ERROR -> stringResource(R.string.sensor_error)
        hero is StandbyGlucose.Value -> stringResource(hero.range.label)
        else -> stringResource(R.string.widget_no_reading)
    }
    val age = glucose?.receivedAtMs?.takeIf { hero is StandbyGlucose.Value && it > 0L }?.let { standbyAge(it, now) }
    val warmupRemaining = warmup?.startedAtMs
        ?.takeIf { displayStatus == GlucoseDisplayStatus.SENSOR_WARMUP }
        ?.let { start ->
            val durationMs = (session?.warmupMinutes ?: SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES)
                .coerceAtLeast(1) * 60_000L
            ceil((start + durationMs - now) / 60_000.0).toInt().coerceAtLeast(0)
        }
    val delta = if (hero is StandbyGlucose.Value) {
        standbyDelta(settings.unit, glucose?.deltaMgDlPerMin)
            ?.let { stringResource(R.string.widget_delta_per_minute, it) }
    } else {
        null
    }
    val battery = batteryPercent?.let { NumberFormat.getPercentInstance(locale).format(it / 100.0) }

    StandbyContent(
        state = StandbyUiState(
            time = time,
            clockTemplate = clockTemplate,
            date = date,
            status = status,
            glucose = hero,
            glucoseTemplate = if (settings.unit == GlucoseUnit.MG_DL) "388" else "27.8",
            displayStatus = displayStatus,
            unit = settings.unit.label,
            delta = delta,
            age = age,
            warmup = warmupRemaining?.let { stringResource(R.string.widget_warmup_remaining, it) },
            chart = chart,
            targetLow = chartModel?.targetLow ?: settings.targetLow,
            targetHigh = chartModel?.targetHigh ?: settings.targetHigh,
            nextAlarm = nextAlarm,
            nextAlarmDescription = nextAlarm?.let { stringResource(R.string.standby_next_alarm, it) },
            battery = battery,
            batteryDescription = battery?.let { stringResource(R.string.standby_battery_charging, it) },
        ),
        modifier = modifier,
        night = ambientMode.isNight(now, settings.redStandbyStartMinutes, settings.redStandbyEndMinutes),
    )
}

/** Everything the night display shows, already resolved, so the layout itself needs no singletons. */
@Immutable
internal data class StandbyUiState(
    val time: String,
    /** A time in the same format with every digit position used; sizes the clock once, not per minute. */
    val clockTemplate: String,
    val date: String,
    val status: String,
    val glucose: StandbyGlucose,
    /** Widest value of the unit ("388" / "27.8"), so the hero never resizes between readings. */
    val glucoseTemplate: String,
    val displayStatus: GlucoseDisplayStatus,
    val unit: String,
    val delta: String?,
    val age: String?,
    val warmup: String?,
    val chart: WidgetChartGeometry?,
    val targetLow: Int,
    val targetHigh: Int,
    val nextAlarm: String? = null,
    val nextAlarmDescription: String? = null,
    val battery: String? = null,
    val batteryDescription: String? = null,
)

internal sealed interface StandbyGlucose {
    data class Value(val text: String, val trend: String?, val range: WidgetGlucoseRange) : StandbyGlucose
    /** A short status code shown instead of a number (NA, S.E.). */
    data class Code(val text: String) : StandbyGlucose
    /** No value to show: never put a non-glucose number in the hero's place. */
    data object Missing : StandbyGlucose
}

@Composable
internal fun StandbyContent(
    state: StandbyUiState,
    modifier: Modifier = Modifier,
    night: Boolean = true,
) {
    val still = LocalInspectionMode.current
    val type = rememberStandbyType()
    val entrance = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(ENTRANCE_MS, easing = EmphasizedDecelerate)) }

    var driftIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DRIFT_PERIOD_MS)
            driftIndex = (driftIndex + 1) % STANDBY_DRIFT.size
        }
    }
    val drift = STANDBY_DRIFT[driftIndex]
    val glide = tween<Float>(DRIFT_GLIDE_MS, easing = FastOutSlowInEasing)
    val driftX = animateFloatAsState(drift.x, glide, label = "standbyDriftX")
    val driftY = animateFloatAsState(drift.y, glide, label = "standbyDriftY")
    val driftAlpha = animateFloatAsState(drift.alpha, glide, label = "standbyDriftAlpha")
    val driftScale = animateFloatAsState(drift.scale, glide, label = "standbyDriftScale")

    // In landscape the camera cutout sits on one side only; mirror its inset so the composition
    // stays centred on the physical panel instead of drifting away from the hole. System bars are
    // hidden (and only overlay when swiped in), so they must not push the layout around.
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.displayCutout
    val sideInset = with(density) { maxOf(insets.getLeft(density, direction), insets.getRight(density, direction)).toDp() }
    val verticalInset = with(density) { maxOf(insets.getTop(density), insets.getBottom(density)).toDp() }

    Box(
        modifier
            .fillMaxSize()
            .background(StandbyBlack),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(horizontal = sideInset + EDGE_MARGIN_H, vertical = verticalInset + EDGE_MARGIN_V)
                .graphicsLayer {
                    // State is read here, in the layer, so drift and entrance never recompose the UI.
                    val shown = entrance.value
                    val scale = driftScale.value * (ENTRANCE_SCALE + (1f - ENTRANCE_SCALE) * shown)
                    alpha = driftAlpha.value * shown
                    scaleX = scale
                    scaleY = scale
                    translationX = driftX.value.dp.toPx()
                    translationY = driftY.value.dp.toPx() + (1f - shown) * ENTRANCE_RISE.toPx()
                    // Alpha per draw call instead of an offscreen full-screen buffer.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        ) {
            val layout = remember(maxWidth, maxHeight, type, state.clockTemplate, state.glucoseTemplate, density) {
                standbyLayout(maxWidth, maxHeight, type, state.clockTemplate, state.glucoseTemplate, density)
            }
            // Numerals are laid out one slot per character: an RTL locale must not mirror "23:47"
            // into "74:32". Text inside still follows its own bidi direction.
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Ltr,
                LocalStandbyInk provides if (night) NightStandbyInk else DayStandbyInk,
            ) {
                if (layout.landscape) {
                    StandbyLandscape(state, layout, type)
                } else {
                    StandbyPortrait(state, layout, type)
                }
            }
        }
    }
}

@Composable
private fun StandbyLandscape(state: StandbyUiState, layout: StandbyLayout, type: StandbyType) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row {
            Column(Modifier.width(layout.column), horizontalAlignment = Alignment.CenterHorizontally) {
                StandbyClockPanel(state, layout, type)
                Spacer(Modifier.height(layout.gapChart))
                Spacer(Modifier.height(layout.chartHeight))
            }
            Spacer(Modifier.width(layout.gutter))
            Box(Modifier.width(layout.column), contentAlignment = Alignment.TopCenter) {
                StandbyGlucosePanel(state, layout, type)
            }
        }
    }
}

/** Fallback for a non-landscape window (standby itself is landscape-only): the two widgets stacked. */
@Composable
private fun StandbyPortrait(state: StandbyUiState, layout: StandbyLayout, type: StandbyType) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StandbyClockPanel(state, layout, type)
        Spacer(Modifier.height(layout.gapSection))
        StandbyGlucosePanel(state, layout, type)
    }
}

@Composable
private fun StandbyClockPanel(state: StandbyUiState, layout: StandbyLayout, type: StandbyType) {
    StandbySlot(layout.labelHeight) { StandbyOverline(state.date, layout.labelSize) }
    Spacer(Modifier.height(layout.gapAbove))
    StandbySlot(layout.hero) { StandbyClock(state.time, layout, type) }
    Spacer(Modifier.height(layout.gapBelow))
    StandbySlot(layout.captionHeight) { StandbyDeviceCaption(state, layout) }
}

@Composable
private fun StandbySlot(height: Dp, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.height(height), contentAlignment = Alignment.Center, content = content)
}

@Composable
private fun StandbyClock(time: String, layout: StandbyLayout, type: StandbyType) {
    StandbyRollingText(
        text = time,
        style = standbyHeroStyle(layout.heroSize),
        color = LocalStandbyInk.current.clock,
        colonShift = layout.hero * type.colonShiftEm,
        countsUp = true,
        modifier = Modifier.clearAndSetSemantics { contentDescription = time },
    )
}

@Composable
private fun StandbyGlucosePanel(state: StandbyUiState, layout: StandbyLayout, type: StandbyType) {
    val ink = LocalStandbyInk.current
    val reading = state.glucose as? StandbyGlucose.Value
    val accent = ink.glucoseAccent(reading?.range, state.displayStatus)
    val badgeSize = layout.hero * 0.57f
    val badgeGap = layout.hero * ARROW_GAP_EM
    val valueHeight = minOf(
        layout.hero * 0.88f,
        (layout.chartWidth - badgeSize - badgeGap) / type.widthEm(state.glucoseTemplate),
    ).coerceAtLeast(1.dp)
    Column(Modifier.width(layout.chartWidth)) {
        StandbyGlucoseHeader(state, layout, accent)
        Spacer(Modifier.height(layout.gapAbove))
        Row(
            Modifier.fillMaxWidth().height(layout.hero),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StandbyGlucoseHero(state, valueHeight, type, accent, Modifier.weight(1f))
            Spacer(Modifier.width(badgeGap))
            StandbyTrendBadge(state, badgeSize, accent, Modifier.offset(y = valueHeight * (type.figureCenterEm - 0.5f)))
        }
        val captionRise = layout.gapBelow * 0.55f
        Spacer(Modifier.height(layout.gapBelow - captionRise))
        Row(
            Modifier.fillMaxWidth().height(layout.captionHeight),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reading != null) {
                StandbyPanelText(state.unit, layout.captionSize, Modifier.clearAndSetSemantics {})
            }
            val detail = if (reading != null) state.delta else state.warmup
            detail?.let { StandbyPanelText(it, layout.captionSize, color = accent.copy(alpha = 0.78f)) }
        }
        val chartGap = layout.gapChart * 0.65f
        Spacer(Modifier.height(chartGap))
        StandbyGlucoseHistory(state, layout, layout.chartHeight + layout.gapChart - chartGap + captionRise)
    }
}

@Composable
private fun StandbyGlucoseHeader(state: StandbyUiState, layout: StandbyLayout, accent: Color) {
    val labelSize = layout.labelSize * 0.84f
    val iconSize = with(LocalDensity.current) { labelSize.toDp() }
    Row(
        Modifier.fillMaxWidth().height(layout.labelHeight),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Box(
                Modifier.height(layout.labelHeight).border(1.dp, accent.copy(alpha = 0.37f), CircleShape)
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                StandbyPanelText(state.status, labelSize, color = accent.copy(alpha = 0.84f))
            }
        }
        state.age?.let { age ->
            Row(
                Modifier.widthIn(max = layout.chartWidth * 0.44f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ageColor = LocalStandbyInk.current.age
                StandbyWidgetIcon(R.drawable.glucose_widget_ic_schedule, ageColor, Modifier.size(iconSize))
                StandbyPanelText(age, labelSize, color = ageColor)
            }
        }
    }
}

@Composable
private fun StandbyPanelText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = LocalStandbyInk.current.caption,
) {
    Text(
        text = text,
        modifier = modifier,
        style = standbyTextStyle(size, CAPTION_TRACKING),
        color = color,
        autoSize = standbyAutoSize(size),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StandbyGlucoseHero(
    state: StandbyUiState,
    height: Dp,
    type: StandbyType,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val description = (state.glucose as? StandbyGlucose.Value)?.let { "${it.text} ${state.unit}, ${trendLabel(it.trend)}" }
    val textStyle = standbyHeroStyle(with(LocalDensity.current) { height.toSp() })
    AnimatedContent(
        targetState = state.glucose,
        modifier = modifier.clearAndSetSemantics { if (description != null) contentDescription = description },
        transitionSpec = {
            (fadeIn(tween(STATE_FADE_IN_MS, delayMillis = STATE_FADE_DELAY_MS)) togetherWith fadeOut(tween(STATE_FADE_OUT_MS)))
                .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.CenterStart,
        label = "standbyGlucose",
        // A new reading keeps the same content, so only its changed digits roll.
        contentKey = { it::class },
    ) { glucose ->
        when (glucose) {
            is StandbyGlucose.Value -> StandbyRollingText(
                text = glucose.text,
                style = textStyle,
                color = LocalStandbyInk.current.glucoseNumber(accent),
                colonShift = 0.dp,
                countsUp = false,
            )
            is StandbyGlucose.Code -> Text(
                text = glucose.text,
                style = textStyle.copy(fontSize = textStyle.fontSize * CODE_SCALE),
                color = accent.copy(alpha = 0.86f),
                maxLines = 1,
                softWrap = false,
            )
            StandbyGlucose.Missing -> StandbyMissingValue(height, type, accent.copy(alpha = 0.5f))
        }
    }
}

/**
 * Numerals in per-character slots. When the text keeps its length only the characters that
 * changed roll (a short vertical travel with a cross-fade); a length change cross-fades the whole.
 */
@Composable
private fun StandbyRollingText(
    text: String,
    style: TextStyle,
    color: Color,
    colonShift: Dp,
    countsUp: Boolean,
    modifier: Modifier = Modifier,
) {
    // The clock always counts up; a glucose value rolls in the direction it moved.
    val committed = remember { DoubleArray(1) { Double.NaN } }
    val value = text.toDoubleOrNull()
    val rising = remember(text) {
        val previous = committed[0]
        countsUp || value == null || previous.isNaN() || value >= previous
    }
    SideEffect { committed[0] = value ?: Double.NaN }
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(STATE_FADE_IN_MS, delayMillis = STATE_FADE_DELAY_MS)) togetherWith fadeOut(tween(STATE_FADE_OUT_MS)))
                .using(SizeTransform(clip = false) { _, _ -> tween(ROLL_MS, easing = RollEasing) })
        },
        contentAlignment = Alignment.Center,
        label = "standbyText",
        contentKey = { it.length },
    ) { shown ->
        Row {
            shown.forEachIndexed { index, char ->
                key(index) { StandbyGlyph(char, rising, style, color, colonShift) }
            }
        }
    }
}

@Composable
private fun StandbyGlyph(char: Char, rising: Boolean, style: TextStyle, color: Color, colonShift: Dp) {
    AnimatedContent(
        targetState = char,
        transitionSpec = {
            val sign = if (rising) 1 else -1
            val enter = slideInVertically(tween(ROLL_MS, easing = RollEasing)) { height ->
                sign * (height * ROLL_TRAVEL).roundToInt()
            } + fadeIn(tween(ROLL_FADE_IN_MS, delayMillis = ROLL_FADE_IN_DELAY_MS))
            val exit = slideOutVertically(tween(ROLL_MS, easing = RollEasing)) { height ->
                -sign * (height * ROLL_TRAVEL).roundToInt()
            } + fadeOut(tween(ROLL_FADE_OUT_MS))
            // Proportional figures: a narrower digit eases its neighbours over instead of jumping.
            (enter togetherWith exit).using(SizeTransform(clip = false) { _, _ -> tween(ROLL_MS, easing = RollEasing) })
        },
        contentAlignment = Alignment.Center,
        label = "standbyGlyph",
    ) { glyph ->
        val separator = glyph == ':'
        Text(
            text = glyph.toString(),
            style = style,
            color = if (separator) LocalStandbyInk.current.separator else color,
            maxLines = 1,
            softWrap = false,
            // The face's colon sits at lowercase height; lift it onto the centre of the figures.
            modifier = if (separator) Modifier.offset(y = colonShift) else Modifier,
        )
    }
}

@Composable
private fun StandbyTrendBadge(state: StandbyUiState, size: Dp, accent: Color, modifier: Modifier = Modifier) {
    val reading = state.glucose as? StandbyGlucose.Value
    val shape = when {
        reading?.range == WidgetGlucoseRange.IN_RANGE -> R.drawable.glucose_widget_shape_cookie
        reading != null || state.displayStatus == GlucoseDisplayStatus.SENSOR_ERROR -> R.drawable.glucose_widget_shape_burst
        state.displayStatus == GlucoseDisplayStatus.SENSOR_WARMUP -> null
        else -> return
    }
    val icon = when (state.displayStatus) {
        GlucoseDisplayStatus.SENSOR_WARMUP -> R.drawable.glucose_widget_ic_hourglass
        GlucoseDisplayStatus.SENSOR_ERROR -> R.drawable.glucose_widget_ic_priority
        else -> R.drawable.glucose_widget_ic_dash
    }
    val rotation = TrendArrowShape.rotationDegrees(reading?.trend)
    val container = accent.copy(alpha = 0.17f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (shape != null) {
            StandbyWidgetIcon(shape, container, Modifier.matchParentSize())
        } else {
            Box(Modifier.matchParentSize().background(container, CircleShape))
        }
        if (rotation != null) {
            StandbyTrendArrow(rotation, accent.copy(alpha = 0.94f), Modifier.size(size * 0.56f))
        } else {
            StandbyWidgetIcon(icon, accent.copy(alpha = 0.84f), Modifier.size(size * 0.41f))
        }
    }
}

@Composable
private fun StandbyWidgetIcon(@DrawableRes resource: Int, color: Color, modifier: Modifier) {
    Image(painterResource(resource), contentDescription = null, modifier = modifier, colorFilter = ColorFilter.tint(color))
}

/** The shared arrow glyph turns only when the sensor reports a new trend. */
@Composable
private fun StandbyTrendArrow(target: Float, color: Color, modifier: Modifier) {
    val rotation = remember { Animatable(target) }
    LaunchedEffect(target) {
        rotation.animateTo(target, spring(dampingRatio = ARROW_DAMPING, stiffness = Spring.StiffnessVeryLow))
    }
    Canvas(modifier) {
        drawTrendArrow(rotation.value, color)
    }
}

/** Two short rounded bars on the figures' centre line, in the face's own stroke weight. */
@Composable
private fun StandbyMissingValue(height: Dp, type: StandbyType, color: Color) {
    Canvas(Modifier.size(width = height * (2 * MISSING_BAR_EM + MISSING_GAP_EM), height = height)) {
        val em = size.height
        val stroke = em * type.strokeEm
        val bar = em * MISSING_BAR_EM
        val gap = em * MISSING_GAP_EM
        val y = em * type.figureCenterEm
        var x = 0f
        repeat(2) {
            drawLine(
                color = color,
                start = Offset(x + stroke / 2f, y),
                end = Offset(x + bar - stroke / 2f, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            x += bar + gap
        }
    }
}

/** Tracked capitals above the clock. */
@Composable
private fun StandbyOverline(text: String, size: TextUnit) {
    val locale = LocalConfiguration.current.locales.get(0) ?: Locale.getDefault()
    Crossfade(targetState = text.uppercase(locale), animationSpec = tween(TEXT_FADE_MS), label = "standbyOverline") { shown ->
        Text(
            text = shown,
            color = LocalStandbyInk.current.date,
            style = standbyTextStyle(size, OVERLINE_TRACKING),
            // A long date in a narrow column shrinks a little instead of being cut off.
            autoSize = standbyAutoSize(size),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StandbyCaptionText(text: String, size: TextUnit, color: Color) {
    Text(
        text = text,
        color = color,
        style = standbyTextStyle(size, CAPTION_TRACKING),
        autoSize = standbyAutoSize(size),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

/**
 * Secondary text in a medium cut of the variable face: in dim red at night a regular stroke all
 * but disappears (the eye is least sensitive to red in the dark), so legibility comes from weight
 * and size here, and the hierarchy from the numerals' scale, not from fading these out.
 */
@OptIn(ExperimentalTextApi::class)
private val StandbyTextFace = FontFamily(
    Font(
        resId = R.font.google_sans_rounded_regular,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(TEXT_WEIGHT)),
    ),
)

private fun standbyTextStyle(size: TextUnit, tracking: Float) = TextStyle(
    fontFamily = StandbyTextFace,
    fontWeight = FontWeight.Medium,
    fontSize = size,
    letterSpacing = tracking.em,
)

private fun standbyAutoSize(size: TextUnit) =
    TextAutoSize.StepBased(minFontSize = size * AUTO_SIZE_MIN, maxFontSize = size, stepSize = AUTO_SIZE_STEP)

/** Next alarm (within a day) and the charge level — the nightstand's quiet footnotes. */
@Composable
private fun StandbyDeviceCaption(state: StandbyUiState, layout: StandbyLayout) {
    val icon = with(LocalDensity.current) { layout.captionSize.toDp() } * CAPTION_ICON_SCALE
    val ink = LocalStandbyInk.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(icon * 1.2f),
    ) {
        state.nextAlarm?.let { alarm ->
            StandbyIconCaption(
                Icons.Rounded.Alarm, alarm, state.nextAlarmDescription ?: alarm, icon, layout.captionSize, ink.alarm,
            )
        }
        state.battery?.let { battery ->
            StandbyIconCaption(
                Icons.Rounded.Bolt, battery, state.batteryDescription ?: battery, icon, layout.captionSize, ink.charge,
            )
        }
    }
}

@Composable
private fun StandbyIconCaption(
    icon: ImageVector,
    text: String,
    description: String,
    iconSize: Dp,
    textSize: TextUnit,
    color: Color,
) {
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(iconSize * 0.3f),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
        StandbyCaptionText(text, textSize, color)
    }
}

@Composable
private fun StandbyGlucoseHistory(state: StandbyUiState, layout: StandbyLayout, height: Dp) {
    val geometry = state.chart
    Column(Modifier.fillMaxWidth().height(height)) {
        if (geometry != null) {
            StandbyChart(
                geometry = geometry,
                targetLow = state.targetLow,
                targetHigh = state.targetHigh,
                highlightLatest = state.glucose is StandbyGlucose.Value,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            StandbyPanelText(stringResource(R.string.widget_chart_window), layout.labelSize * 0.75f)
        }
    }
}

/** Thin target guides and brighter excursions share the same three-hour history as the widget. */
@Composable
private fun StandbyChart(
    geometry: WidgetChartGeometry,
    targetLow: Int,
    targetHigh: Int,
    highlightLatest: Boolean,
    modifier: Modifier,
) {
    val still = LocalInspectionMode.current
    val reveal = remember { Animatable(if (still) 1f else 0f) }
    val ink = LocalStandbyInk.current
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(CHART_REVEAL_MS, delayMillis = CHART_REVEAL_DELAY_MS, easing = EmphasizedDecelerate))
    }
    Spacer(
        modifier.drawWithCache {
            val stroke = CHART_STROKE.toPx()
            val ring = CHART_RING.toPx()
            val dot = CHART_DOT.toPx()
            val inset = ring + stroke / 2f
            if (size.minDimension <= 2f * inset) return@drawWithCache onDrawBehind {}
            val left = inset
            val right = size.width - inset
            val bottom = size.height - inset
            val span = geometry.scale.endInclusive - geometry.scale.start
            fun x(point: WidgetChartPoint) = left + point.x * (right - left)
            fun y(mgDl: Float) = bottom - (mgDl - geometry.scale.start) / span * (bottom - inset)
            val traces = geometry.runs.filter { it.points.size > 1 }.map { run ->
                ink.chartInk(run.band) to Path().apply {
                    run.points.forEachIndexed { index, point ->
                        if (index == 0) moveTo(x(point), y(point.mgDl)) else lineTo(x(point), y(point.mgDl))
                    }
                }
            }
            val isolated = geometry.runs.filter { it.points.size == 1 }.map { run ->
                ink.chartInk(run.band) to Offset(x(run.points[0]), y(run.points[0].mgDl))
            }
            val latest = if (highlightLatest) {
                geometry.runs.maxByOrNull { it.points.last().x }?.points?.last()?.let { point ->
                    val band = widgetChartBand(point.mgDl, targetLow, targetHigh)
                    Offset(x(point), y(point.mgDl)) to ink.chartAccent(band).copy(alpha = 0.94f)
                }
            } else {
                null
            }
            val guides = listOf(targetLow, targetHigh).map { y(it.toFloat()) }
            val guideWidth = CHART_GUIDE_WIDTH.toPx()
            val dotted = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), CHART_GUIDE_SPACING.toPx()))
            val line = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val markerOutline = Stroke(width = guideWidth)
            onDrawBehind {
                guides.forEach { guideY ->
                    drawLine(
                        color = ink.guide,
                        start = Offset(left, guideY),
                        end = Offset(right, guideY),
                        strokeWidth = guideWidth,
                        cap = StrokeCap.Round,
                        pathEffect = dotted,
                    )
                }
                val progress = reveal.value
                clipRect(right = left + (size.width - left) * progress) {
                    traces.forEach { (ink, path) -> drawPath(path, ink, style = line) }
                    isolated.forEach { (ink, center) -> drawCircle(ink, radius = stroke, center = center) }
                }
                val emphasis = ((progress - 0.85f) / 0.15f).coerceIn(0f, 1f)
                if (latest != null && emphasis > 0f) {
                    val (center, color) = latest
                    drawCircle(ink.guide, radius = ring, center = center, alpha = emphasis, style = markerOutline)
                    drawCircle(color, radius = dot, center = center, alpha = emphasis)
                }
            }
        },
    )
}

/**
 * The numeral face. The bundled Google Sans Rounded is a variable font (opsz, wdth, wght, GRAD,
 * slnt axes); Android otherwise renders its default instance, cut for 18 pt body text. The big
 * numerals use the display optical size in a light, slightly narrow cut: finer details at this
 * size, a taller clock in the same width, and fewer lit OLED pixels at night.
 */
private const val NUMERAL_WEIGHT = 300
private const val NUMERAL_WIDTH = 92f
private const val NUMERAL_OPTICAL_SIZE = 144f

@OptIn(ExperimentalTextApi::class) // resource-font variation settings; applied via Paint on API 26+
private val StandbyNumerals = FontFamily(
    Font(
        resId = R.font.google_sans_rounded_regular,
        weight = FontWeight.Light,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(NUMERAL_WEIGHT),
            FontVariation.width(NUMERAL_WIDTH),
            FontVariation.Setting("opsz", NUMERAL_OPTICAL_SIZE),
        ),
    ),
)

/** The same instance for Paint measurements in [StandbyType]. */
private const val NUMERAL_VARIATIONS = "'wght' $NUMERAL_WEIGHT, 'wdth' $NUMERAL_WIDTH, 'opsz' $NUMERAL_OPTICAL_SIZE"

private fun standbyHeroStyle(size: TextUnit) = TextStyle(
    fontFamily = StandbyNumerals,
    fontWeight = FontWeight.Light,
    fontSize = size,
    // A 1 em line box centred on the font's ascent/descent; StandbyType measures against this.
    lineHeight = 1.em,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/**
 * Ink metrics of the numeral face, measured once: the figures' optical centre, how far the colon
 * must rise to meet it, the stroke weight, and the widest digit for sizing.
 */
private class StandbyType(typeface: Typeface) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = REFERENCE_PX
        setFontVariationSettings(NUMERAL_VARIATIONS)
    }
    private val widestDigit: Char = DIGITS.maxBy { paint.measureText(it.toString()) }

    /** Vertical centre of the figures inside the 1 em hero line box, from its top, in em. */
    val figureCenterEm: Float

    /** Vertical offset (em, negative = up) that puts the colon on the figures' centre. */
    val colonShiftEm: Float

    /** Horizontal stroke weight of the face (en dash), in em, for the missing-value dashes. */
    val strokeEm: Float

    init {
        val metrics = paint.fontMetrics
        val baseline = (-metrics.ascent - ((metrics.descent - metrics.ascent) - REFERENCE_PX) / 2f) / REFERENCE_PX
        figureCenterEm = baseline + inkBounds("0").exactCenterY() / REFERENCE_PX
        colonShiftEm = figureCenterEm - (baseline + inkBounds(":").exactCenterY() / REFERENCE_PX)
        strokeEm = (inkBounds("–").height() / REFERENCE_PX).takeIf { it > 0f } ?: DEFAULT_STROKE_EM
    }

    /** Width in em of [text] drawn one slot per character, with every digit at its widest. */
    fun widthEm(text: String): Float =
        text.sumOf { char -> paint.measureText((if (char.isDigit()) widestDigit else char).toString()).toDouble() }
            .toFloat() / REFERENCE_PX

    private fun inkBounds(text: String): Rect = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
}

@Composable
private fun rememberStandbyType(): StandbyType {
    val context = LocalContext.current
    return remember(context) {
        val typeface = runCatching { ResourcesCompat.getFont(context, R.font.google_sans_rounded_regular) }.getOrNull()
        StandbyType(typeface ?: Typeface.DEFAULT)
    }
}

@Immutable
private data class StandbyLayout(
    val landscape: Boolean,
    val column: Dp,
    val gutter: Dp,
    /** Size of the big numerals: font size and line-box height at once. */
    val hero: Dp,
    val heroSize: TextUnit,
    val labelSize: TextUnit,
    val labelHeight: Dp,
    val captionSize: TextUnit,
    val captionHeight: Dp,
    val gapAbove: Dp,
    val gapBelow: Dp,
    val gapChart: Dp,
    val gapSection: Dp,
    val chartWidth: Dp,
    val chartHeight: Dp,
)

/** Fits the original clock grid and gives the glucose panel the same vertical slots. */
private fun standbyLayout(
    width: Dp,
    height: Dp,
    type: StandbyType,
    clockTemplate: String,
    glucoseTemplate: String,
    density: Density,
): StandbyLayout {
    val landscape = width >= height
    // Retain the space originally reserved for the reading and its bare arrow in the clock grid.
    val widestEm = maxOf(type.widthEm(clockTemplate), type.widthEm(glucoseTemplate) + ARROW_GAP_EM + ARROW_EM)
    val labelSize = (minOf(width, height).value * LABEL_RATIO).coerceIn(LABEL_MIN_SP, LABEL_MAX_SP).sp
    val captionSize = (labelSize.value * CAPTION_SCALE).sp
    val labelHeight = with(density) { labelSize.toDp() } * TEXT_LINE
    val captionHeight = with(density) { captionSize.toDp() } * TEXT_LINE
    val gutter = if (landscape) (width * GUTTER_RATIO).coerceIn(28.dp, 96.dp) else 0.dp
    val column = if (landscape) (width - gutter) / 2 else width
    val byWidth = column * HERO_FILL / widestEm
    val byHeight = if (landscape) {
        (height * HEIGHT_FILL - labelHeight - captionHeight) /
            (1f + GAP_ABOVE + GAP_BELOW + GAP_CHART + CHART_RATIO)
    } else {
        (height * HEIGHT_FILL - labelHeight * 2 - captionHeight * 2) /
            (2f + 2f * (GAP_ABOVE + GAP_BELOW) + GAP_SECTION + GAP_CHART + CHART_RATIO)
    }
    val hero = minOf(byWidth, byHeight).coerceIn(HERO_MIN, HERO_MAX)
    return StandbyLayout(
        landscape = landscape,
        column = column,
        gutter = gutter,
        hero = hero,
        heroSize = with(density) { hero.toSp() },
        labelSize = labelSize,
        labelHeight = labelHeight,
        captionSize = captionSize,
        captionHeight = captionHeight,
        gapAbove = hero * GAP_ABOVE,
        gapBelow = hero * GAP_BELOW,
        gapChart = hero * GAP_CHART,
        gapSection = hero * GAP_SECTION,
        chartWidth = minOf(column * CHART_WIDTH_RATIO, hero * CHART_MAX_WIDTH_EM),
        chartHeight = hero * CHART_RATIO,
    )
}

/** The locale's hour:minute pattern without the day period; StandBy-style clocks drop AM/PM. */
private fun standbyClockPattern(locale: Locale, use24Hour: Boolean): String {
    val best = DateFormat.getBestDateTimePattern(locale, if (use24Hour) "Hm" else "hm")
    val pattern = StringBuilder()
    var quoted = false
    best.forEach { char ->
        if (char == '\'') quoted = !quoted
        if (quoted || char !in "abB") pattern.append(char)
    }
    return pattern.toString().trim().ifEmpty { if (use24Hour) "HH:mm" else "h:mm" }
}

/** 10:08 has a two-digit hour in both 12 h and 24 h formats. */
private fun clockTemplateDate(): Date = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 10)
    set(Calendar.MINUTE, 8)
}.time

private fun standbyDate(locale: Locale, nowMs: Long): String =
    SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(Date(nowMs))

/** Minute granularity on purpose: a seconds counter would be the only thing moving at night. */
@Composable
private fun standbyAge(receivedAtMs: Long, nowMs: Long): String {
    val minutes = ((nowMs - receivedAtMs).coerceAtLeast(0L) / 60_000L).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.age_now)
        minutes < 60 -> stringResource(R.string.age_minutes, minutes)
        else -> stringResource(R.string.age_hours, minutes / 60)
    }
}

/** Rate per minute in [unit], formatted like the widgets (no "-0", one decimal). */
private fun standbyDelta(unit: GlucoseUnit, mgDlPerMin: Double?): String? {
    val rate = mgDlPerMin?.takeIf { it.isFinite() } ?: return null
    val value = if (unit == GlucoseUnit.MG_DL) rate else rate / 18.0
    val text = String.format(Locale.US, "%+.1f", if (abs(value) < 0.05) 0.0 else value)
    return if (unit == GlucoseUnit.MG_DL) text.removeSuffix(".0") else text
}

private fun Context.nextAlarmAtMs(): Long? =
    runCatching { getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime }.getOrNull()

private fun Context.batteryPercent(): Int? =
    runCatching { getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
        .getOrNull()
        ?.takeIf { it in 0..100 }

private fun SensorStateStore.LastGlucose.toGlucoseUi(): GlucoseUi =
    GlucoseUi(
        mgDL = mgDL,
        trend = trend,
        lifeCount = lifeCount,
        usable = true,
        receivedAtMs = receivedAtMs,
        deltaMgDlPerMin = deltaMgDlPerMin,
    )

private class StandbyDrift(val x: Float, val y: Float, val alpha: Float, val scale: Float)

// Keep this independent from MaterialTheme.background. The app's dark theme is intentionally
// blue/teal, but this surface must be true black so OLED pixels can switch off.
private val StandbyBlack = Color.Black

@Immutable
private class StandbyPalette(
    clockFace: Color,
    private val glucoseFace: Color = clockFace,
    clockDetail: Color = clockFace,
    charging: Color = clockFace,
    private val glucoseDetail: Color = glucoseFace,
    private val ranges: GlucoseColors? = null,
) {
    val clock = clockFace.copy(alpha = 0.94f)
    val separator = clockDetail.copy(alpha = 0.76f)
    val date = clockDetail.copy(alpha = 0.84f)
    val alarm = clockDetail.copy(alpha = 0.78f)
    val charge = charging.copy(alpha = 0.78f)
    val caption = glucoseDetail.copy(alpha = 0.78f)
    val age = clockDetail.copy(alpha = 0.78f)
    val guide = glucoseDetail.copy(alpha = 0.37f)

    fun glucoseAccent(range: WidgetGlucoseRange?, status: GlucoseDisplayStatus): Color {
        val colors = ranges ?: return glucoseFace
        return when (range) {
            WidgetGlucoseRange.VERY_LOW -> colors.veryLow
            WidgetGlucoseRange.LOW -> colors.low
            WidgetGlucoseRange.IN_RANGE -> colors.inRange
            WidgetGlucoseRange.HIGH -> colors.high
            WidgetGlucoseRange.VERY_HIGH -> colors.veryHigh
            null -> when (status) {
                GlucoseDisplayStatus.SENSOR_WARMUP -> colors.high
                GlucoseDisplayStatus.SENSOR_ERROR -> DarkColors.error
                else -> glucoseDetail
            }
        }
    }

    fun glucoseNumber(accent: Color): Color =
        (if (ranges == null) glucoseFace else lerp(glucoseFace, accent, 0.28f)).copy(alpha = 0.94f)

    fun chartAccent(band: WidgetChartBand): Color = when (band) {
        WidgetChartBand.IN_RANGE -> ranges?.inRange
        WidgetChartBand.ABOVE -> ranges?.high
        WidgetChartBand.BELOW -> ranges?.low
    } ?: glucoseFace

    fun chartInk(band: WidgetChartBand): Color = chartAccent(band).copy(
        alpha = when (band) {
            WidgetChartBand.IN_RANGE -> 0.66f
            WidgetChartBand.ABOVE -> 0.86f
            WidgetChartBand.BELOW -> 0.94f
        },
    )
}

private val NightStandbyInk = StandbyPalette(Color(0xFFFF2A1C))
private val DayStandbyInk = StandbyPalette(
    clockFace = Color(0xFFC5D6F9),
    glucoseFace = Color(0xFFE6F4ED),
    clockDetail = Color(0xFFCABDF0),
    charging = DarkColors.primary,
    glucoseDetail = DarkColors.secondary,
    ranges = DarkGlucoseColors,
)
private val LocalStandbyInk = compositionLocalOf { NightStandbyInk }

// Pixel-shift path for OLED burn-in protection; each step glides over DRIFT_GLIDE_MS.
private val STANDBY_DRIFT = listOf(
    StandbyDrift(0f, 0f, 0.920f, 1.0000f),
    StandbyDrift(-4f, -5f, 0.885f, 0.9988f),
    StandbyDrift(4f, 3f, 0.905f, 0.9982f),
    StandbyDrift(-3f, 5f, 0.875f, 0.9986f),
    StandbyDrift(5f, -3f, 0.900f, 0.9980f),
    StandbyDrift(-5f, 2f, 0.895f, 0.9989f),
    StandbyDrift(3f, -5f, 0.880f, 0.9992f),
    StandbyDrift(0f, 5f, 0.910f, 0.9985f),
)

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val RollEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private const val CHART_REFRESH_MS = 60_000L
private const val NEXT_ALARM_HORIZON_MS = 24 * 60 * 60_000L
private const val DRIFT_PERIOD_MS = 45_000L
private const val DRIFT_GLIDE_MS = 5_000
private const val ENTRANCE_MS = 1_100
private const val ENTRANCE_SCALE = 0.965f
private val ENTRANCE_RISE = 14.dp
private const val ROLL_MS = 650
private const val ROLL_TRAVEL = 0.32f
private const val ROLL_FADE_IN_MS = 380
private const val ROLL_FADE_IN_DELAY_MS = 90
private const val ROLL_FADE_OUT_MS = 260
private const val STATE_FADE_IN_MS = 520
private const val STATE_FADE_DELAY_MS = 160
private const val STATE_FADE_OUT_MS = 280
private const val TEXT_FADE_MS = 600
private const val ARROW_DAMPING = 0.82f
private const val CHART_REVEAL_MS = 1_100
private const val CHART_REVEAL_DELAY_MS = 250

private val EDGE_MARGIN_H = 24.dp
private val EDGE_MARGIN_V = 16.dp
private const val GUTTER_RATIO = 0.06f
private const val HERO_FILL = 0.94f
private const val HEIGHT_FILL = 0.92f
private val HERO_MIN = 40.dp
private val HERO_MAX = 300.dp
private const val ARROW_EM = 0.6f
private const val ARROW_GAP_EM = 0.1f
private const val CODE_SCALE = 0.8f
private const val MISSING_BAR_EM = 0.44f
private const val MISSING_GAP_EM = 0.22f
private const val DEFAULT_STROKE_EM = 0.07f
private const val GAP_ABOVE = 0.08f
private const val GAP_BELOW = 0.08f
private const val GAP_CHART = 0.14f
private const val GAP_SECTION = 0.4f
private const val CHART_RATIO = 0.46f
private const val CHART_WIDTH_RATIO = 0.84f
private const val CHART_MAX_WIDTH_EM = 3f
private val CHART_STROKE = 1.8.dp
private val CHART_RING = 5.5.dp
private val CHART_DOT = 2.6.dp
private val CHART_GUIDE_WIDTH = 1.dp
private val CHART_GUIDE_SPACING = 5.dp
private const val LABEL_RATIO = 0.054f
private const val LABEL_MIN_SP = 14f
private const val LABEL_MAX_SP = 24f
private const val CAPTION_SCALE = 1.06f
private const val CAPTION_ICON_SCALE = 1.15f
private const val TEXT_LINE = 1.32f
private const val TEXT_WEIGHT = 520
private const val OVERLINE_TRACKING = 0.08f
private const val CAPTION_TRACKING = 0.01f
private const val AUTO_SIZE_MIN = 0.72f
private val AUTO_SIZE_STEP = 0.5.sp
private const val REFERENCE_PX = 1000f
private const val DIGITS = "0123456789"

// region Android Studio previews

@Preview(name = "Standby · reading", widthDp = 891, heightDp = 411, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun StandbyReadingPreview() {
    StandbyContent(previewStandbyState())
}

@Preview(name = "Standby · daylight", widthDp = 891, heightDp = 411)
@Composable
private fun StandbyDaylightPreview() {
    StandbyContent(previewStandbyState(), night = false)
}

@Preview(name = "Standby · no reading", widthDp = 891, heightDp = 411, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun StandbyMissingPreview() {
    StandbyContent(
        previewStandbyState().copy(
            glucose = StandbyGlucose.Missing,
            status = "Fără citire",
            age = null,
            delta = null,
        ),
    )
}

@Preview(name = "Standby · daylight, low (compact)", widthDp = 640, heightDp = 300, fontScale = 1.25f, locale = "fr")
@Composable
private fun StandbyLowPreview() {
    StandbyContent(
        previewStandbyState().copy(
            date = "Samedi 22 novembre",
            status = "Très bas",
            glucose = StandbyGlucose.Value("2.8", "FALLING", WidgetGlucoseRange.VERY_LOW),
            glucoseTemplate = "27.8",
            unit = "mmol/L",
            delta = "−0.1/min",
            age = "il y a 1 min",
            chart = null,
        ),
        night = false,
    )
}

private fun previewStandbyState(): StandbyUiState {
    val end = PREVIEW_END_MS
    val values = intArrayOf(
        158, 164, 171, 177, 182, 184, 181, 174, 165, 154, 142, 130, 118, 106, 95, 86, 78, 72, 68, 66,
        67, 70, 75, 81, 87, 92, 96, 99, 101, 103, 104, 105, 106, 107, 109, 110, 112,
    )
    val samples = values.mapIndexed { index, mgDl ->
        GlucoseSample(mgDl, end - (values.size - 1 - index) * 5 * 60_000L)
    }
    return StandbyUiState(
        time = "23:47",
        clockTemplate = "10:08",
        date = "Sâmbătă, 22 noiembrie",
        status = "În țintă",
        glucose = StandbyGlucose.Value("112", "RISING", WidgetGlucoseRange.IN_RANGE),
        glucoseTemplate = "388",
        displayStatus = GlucoseDisplayStatus.NORMAL,
        unit = "mg/dL",
        delta = "+1.1/min",
        age = "acum 1 min",
        warmup = null,
        chart = widgetChartGeometry(samples, end - WIDGET_CHART_WINDOW_MS, end, 70, 180),
        targetLow = 70,
        targetHigh = 180,
        nextAlarm = "07:00",
        battery = "87%",
    )
}

private const val PREVIEW_END_MS = 1_763_852_820_000L

// endregion
