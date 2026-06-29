package re.abbot.librecr.app.ui.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.R
import re.abbot.librecr.app.ble.GlucoseUi
import re.abbot.librecr.app.data.AppSettings
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.isFreshGlucose
import re.abbot.librecr.app.ui.common.TrendArrow
import re.abbot.librecr.app.ui.common.readingAgeText
import re.abbot.librecr.app.ui.common.trendLabel
import java.text.DateFormat as JavaDateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

@Composable
fun rememberChargingState(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    val charging = remember(context) { mutableStateOf(context.currentChargingState()) }
    DisposableEffect(context) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                charging.value = intent.isChargingIntent()
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        charging.value = sticky.isChargingIntent()
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return charging
}

@Composable
fun StandbyScreen(
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val local by LibreCR.manager.glucose.collectAsState()
    val remote by LibreCR.store.lastGlucoseFlow.collectAsState(initial = null)
    val history by LibreCR.store.glucoseHistoryFlow.collectAsState(initial = emptyList())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var burnInIndex by remember { mutableIntStateOf(0) }
    val glucose = local
        ?.takeIf { it.usable && it.mgDL != null && isFreshGlucose(it.receivedAtMs, now) }
        ?: remote?.takeIf { isFreshGlucose(it.receivedAtMs, now) }?.toGlucoseUi()

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(BURN_IN_PERIOD_MS)
            burnInIndex = (burnInIndex + 1) % STANDBY_BURN_IN_FRAMES.size
        }
    }

    val frame = STANDBY_BURN_IN_FRAMES[burnInIndex]
    val offsetX by animateDpAsState(frame.x, label = "standbyBurnInX")
    val offsetY by animateDpAsState(frame.y, label = "standbyBurnInY")
    val alpha by animateFloatAsState(frame.alpha, label = "standbyBurnInAlpha")
    val scale by animateFloatAsState(frame.scale, label = "standbyBurnInScale")
    val colorShift by animateFloatAsState(frame.colorShift, label = "standbyBurnInColor")
    val standbyRed = shiftedStandbyRed(colorShift)

    Box(
        modifier
            .fillMaxSize()
            .background(StandbyBlack),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 34.dp, vertical = 18.dp),
        ) {
            val compact = maxHeight < 390.dp
            val clockSize = if (compact) 72.sp else 96.sp
            val dateSize = if (compact) 17.sp else 22.sp
            val glucoseSize = if (compact) 78.sp else 104.sp
            val labelSize = if (compact) 12.sp else 14.sp

            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.92f)
                    .offset(offsetX, offsetY)
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(0.95f),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                ) {
                    Text(
                        text = "${stringResource(R.string.standby_mode)} / ${stringResource(R.string.standby_charging)}",
                        color = standbyRed.copy(alpha = 0.68f),
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = labelSize,
                        letterSpacing = 1.2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatTime(context, now),
                        color = standbyRed,
                        fontSize = clockSize,
                        lineHeight = clockSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        text = formatDate(now),
                        color = standbyRed.copy(alpha = 0.72f),
                        fontSize = dateSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.width(if (compact) 24.dp else 42.dp))

                StandbyGlucosePanel(
                    glucose = glucose,
                    history = history,
                    settings = settings,
                    color = standbyRed,
                    compact = compact,
                    valueSize = glucoseSize,
                    labelSize = labelSize,
                    modifier = Modifier.weight(1.05f),
                )
            }
        }
    }
}

@Composable
private fun StandbyGlucosePanel(
    glucose: GlucoseUi?,
    history: List<SensorStateStore.LastGlucose>,
    settings: AppSettings,
    color: Color,
    compact: Boolean,
    valueSize: androidx.compose.ui.unit.TextUnit,
    labelSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    val value = glucose?.mgDL?.let { settings.unit.format(it) } ?: "SE"
    // Trend arrow sits beside the value and is much larger than before.
    val arrowSize = (valueSize.value * 0.82f).dp

    Column(
        modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                color = color,
                fontSize = valueSize,
                lineHeight = valueSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            TrendArrow(
                trend = glucose?.trend,
                color = color,
                size = arrowSize,
                modifier = Modifier.alpha(if (glucose == null) 0.45f else 1f),
            )
        }
        Text(
            text = trendLabel(glucose?.trend),
            color = color.copy(alpha = 0.78f),
            fontSize = if (compact) 14.sp else 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(if (glucose == null) 0.45f else 1f),
        )
        Text(
            text = readingAgeText(glucose?.receivedAtMs),
            color = color.copy(alpha = 0.58f),
            fontSize = if (compact) 11.sp else 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        StandbySparkline(
            readings = history,
            targetLow = settings.targetLow,
            targetHigh = settings.targetHigh,
            color = color,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 44.dp else 58.dp),
        )
    }
}

@Composable
private fun StandbySparkline(
    readings: List<SensorStateStore.LastGlucose>,
    targetLow: Int,
    targetHigh: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val points = readings.takeLast(36).filter { it.mgDL in 30..420 }
        val midY = size.height / 2f
        drawLine(
            color = color.copy(alpha = 0.16f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1.dp.toPx(),
        )
        if (points.size < 2) return@Canvas

        val minValue = (min(points.minOf { it.mgDL }, targetLow) - 8).coerceAtLeast(30)
        val maxValue = (max(points.maxOf { it.mgDL }, targetHigh) + 8).coerceAtMost(420)
        val range = (maxValue - minValue).coerceAtLeast(1).toFloat()
        fun x(index: Int) = if (points.lastIndex == 0) 0f else index.toFloat() / points.lastIndex.toFloat() * size.width
        fun y(mgDl: Int) = size.height - (mgDl - minValue).toFloat() / range * size.height

        val targetTop = y(targetHigh).coerceIn(0f, size.height)
        val targetBottom = y(targetLow).coerceIn(0f, size.height)
        drawRect(
            color = color.copy(alpha = 0.07f),
            topLeft = Offset(0f, targetTop),
            size = Size(size.width, (targetBottom - targetTop).coerceAtLeast(1f)),
        )

        val path = Path()
        points.forEachIndexed { index, reading ->
            val p = Offset(x(index), y(reading.mgDL))
            if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(
            path = path,
            color = color.copy(alpha = 0.82f),
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        points.lastOrNull()?.let { last ->
            drawCircle(
                color = color,
                radius = 3.dp.toPx(),
                center = Offset(size.width, y(last.mgDL)),
            )
        }
    }
}

private fun SensorStateStore.LastGlucose.toGlucoseUi(): GlucoseUi =
    GlucoseUi(
        mgDL = mgDL,
        trend = trend,
        lifeCount = lifeCount,
        usable = true,
        receivedAtMs = receivedAtMs,
        deltaMgDlPerMin = deltaMgDlPerMin,
    )

private fun Context.currentChargingState(): Boolean =
    registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)).isChargingIntent()

private fun Intent?.isChargingIntent(): Boolean {
    // Standby is meant for a wireless charging dock only — wired AC/USB charging must NOT trigger it.
    val plugged = this?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    return plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
}

private fun formatTime(context: Context, now: Long): String =
    DateFormat.getTimeFormat(context).format(Date(now))

private fun formatDate(now: Long): String =
    JavaDateFormat.getDateInstance(JavaDateFormat.MEDIUM).format(Date(now))

private data class StandbyBurnInFrame(
    val x: Dp,
    val y: Dp,
    val alpha: Float,
    val scale: Float,
    val colorShift: Float,
)

private const val BURN_IN_PERIOD_MS = 60_000L
private val StandbyBlack = Color(0xFF000000)
private val StandbyRed = Color(0xFFFF453A)
private val STANDBY_BURN_IN_FRAMES = listOf(
    StandbyBurnInFrame(0.dp, 0.dp, 1.000f, 1.0000f, 0.000f),
    StandbyBurnInFrame((-1).dp, (-2).dp, 0.992f, 0.9992f, -0.004f),
    StandbyBurnInFrame(1.dp, 1.dp, 0.996f, 0.9988f, 0.003f),
    StandbyBurnInFrame((-1).dp, 2.dp, 0.990f, 0.9990f, -0.003f),
    StandbyBurnInFrame(2.dp, (-1).dp, 0.994f, 0.9986f, 0.004f),
    StandbyBurnInFrame((-2).dp, 1.dp, 0.996f, 0.9991f, -0.002f),
    StandbyBurnInFrame(1.dp, (-2).dp, 0.991f, 0.9994f, 0.002f),
    StandbyBurnInFrame(0.dp, 2.dp, 0.995f, 0.9989f, -0.001f),
)

private fun shiftedStandbyRed(shift: Float): Color =
    Color(
        red = (StandbyRed.red + shift).coerceIn(0f, 1f),
        green = (StandbyRed.green + shift * 0.35f).coerceIn(0f, 1f),
        blue = (StandbyRed.blue + shift * 0.25f).coerceIn(0f, 1f),
        alpha = StandbyRed.alpha,
    )
