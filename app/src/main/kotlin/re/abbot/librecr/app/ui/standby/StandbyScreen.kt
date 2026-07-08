package re.abbot.librecr.app.ui.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.R
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.ble.GlucoseDisplayStatus
import re.abbot.librecr.app.ble.GlucoseUi
import re.abbot.librecr.app.ble.isActiveGlucoseUnavailable
import re.abbot.librecr.app.ble.isUnavailableForGlucoseDisplay
import re.abbot.librecr.app.data.AppSettings
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.isFreshGlucose
import re.abbot.librecr.app.ui.common.TrendArrow
import re.abbot.librecr.app.ui.common.readingAgeText
import re.abbot.librecr.app.ui.common.trendLabel
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention
import java.text.DateFormat as JavaDateFormat
import java.util.Date

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
    val sensorStatus by LibreCR.store.sensorStatusFlow.collectAsState(initial = null)
    val connectionState by LibreCR.manager.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var burnInIndex by remember { mutableIntStateOf(0) }
    val attention = sensorStatus?.attention ?: Libre3SensorAttention.None
    val hasSensorAttention = attention != Libre3SensorAttention.None
    val liveUnavailable = local.isActiveGlucoseUnavailable(now)
    // A fresh unavailable live reading is the newest glucose state: show "OOR" instead of falling
    // back to the older stored value. Real sensor errors still come from patch-status attention.
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
        liveUnavailable || (glucose == null && connectionState.isUnavailableForGlucoseDisplay()) -> GlucoseDisplayStatus.OUT_OF_RANGE
        else -> GlucoseDisplayStatus.NORMAL
    }

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
            val portrait = maxHeight > maxWidth
            val compact = if (portrait) maxHeight < 620.dp else maxHeight < 390.dp
            val narrow = maxWidth < 390.dp
            val clockSize = when {
                portrait && compact -> 76.sp
                portrait && narrow -> 84.sp
                portrait -> 96.sp
                compact -> 72.sp
                else -> 90.sp
            }
            val dateSize = if (compact) 17.sp else 21.sp
            val glucoseSize = when {
                portrait && compact -> 82.sp
                portrait -> 98.sp
                compact -> 76.sp
                else -> 98.sp
            }
            val detailSize = if (compact) 13.sp else 16.sp
            val burnInMotion = Modifier
                .offset(offsetX, offsetY)
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                }

            if (portrait) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(if (narrow) 0.92f else 0.86f)
                        .then(burnInMotion),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 30.dp else 44.dp),
                ) {
                    StandbyTimeBlock(
                        now = now,
                        context = context,
                        color = standbyRed,
                        clockSize = clockSize,
                        dateSize = dateSize,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StandbyGlucosePanel(
                        glucose = glucose,
                        displayStatus = displayStatus,
                        settings = settings,
                        color = standbyRed,
                        compact = compact,
                        valueSize = glucoseSize,
                        detailSize = detailSize,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.92f)
                        .then(burnInMotion),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StandbyTimeBlock(
                        now = now,
                        context = context,
                        color = standbyRed,
                        clockSize = clockSize,
                        dateSize = dateSize,
                        horizontalAlignment = Alignment.Start,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(0.95f),
                    )

                    Spacer(Modifier.width(if (compact) 22.dp else 40.dp))

                    StandbyGlucosePanel(
                        glucose = glucose,
                        displayStatus = displayStatus,
                        settings = settings,
                        color = standbyRed,
                        compact = compact,
                        valueSize = glucoseSize,
                        detailSize = detailSize,
                        horizontalAlignment = Alignment.End,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1.05f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StandbyTimeBlock(
    now: Long,
    context: Context,
    color: Color,
    clockSize: TextUnit,
    dateSize: TextUnit,
    horizontalAlignment: Alignment.Horizontal,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = formatDate(now),
            color = color.copy(alpha = 0.58f),
            fontSize = dateSize,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = formatTime(context, now),
            color = color,
            fontSize = clockSize,
            lineHeight = clockSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StandbyGlucosePanel(
    glucose: GlucoseUi?,
    displayStatus: GlucoseDisplayStatus,
    settings: AppSettings,
    color: Color,
    compact: Boolean,
    valueSize: TextUnit,
    detailSize: TextUnit,
    horizontalAlignment: Alignment.Horizontal,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    val value = when {
        glucose == null -> stringResource(R.string.standby_no_reading)
        glucose.mgDL == null && displayStatus == GlucoseDisplayStatus.SENSOR_ERROR -> stringResource(R.string.sensor_error_short)
        glucose.mgDL == null && displayStatus == GlucoseDisplayStatus.OUT_OF_RANGE -> stringResource(R.string.sensor_out_of_range_short)
        glucose.mgDL == null -> stringResource(R.string.standby_no_reading)
        else -> settings.unit.format(glucose.mgDL)
    }
    val hasValue = glucose?.mgDL != null
    val trendText = if (hasValue) trendLabel(glucose?.trend) else null
    val ageText = if (glucose == null) null else readingAgeText(glucose.receivedAtMs)
    val detailText = when {
        glucose == null -> null
        hasValue -> "$trendText / $ageText"
        else -> ageText
    }
    val arrowSize = (valueSize.value * 0.68f).dp

    Column(
        modifier,
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                color = color,
                fontSize = if (glucose == null) detailSize else valueSize,
                lineHeight = if (glucose == null) detailSize else valueSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
                modifier = if (glucose == null) Modifier.fillMaxWidth() else Modifier,
            )
            if (hasValue) {
                TrendArrow(
                    trend = glucose.trend,
                    color = color,
                    size = arrowSize,
                    modifier = Modifier.alpha(0.92f),
                )
            }
        }
        if (hasValue) {
            Text(
                text = settings.unit.label,
                color = color.copy(alpha = 0.46f),
                fontSize = if (compact) 12.sp else 14.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (detailText != null) {
            Text(
                text = detailText,
                color = color.copy(alpha = 0.62f),
                fontSize = detailSize,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
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
    return (plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0
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

private const val BURN_IN_PERIOD_MS = 45_000L
private val StandbyBlack = Color(0xFF000000)
private val StandbyRed = Color(0xFFFF3B30)
private val STANDBY_BURN_IN_FRAMES = listOf(
    StandbyBurnInFrame(0.dp, 0.dp, 0.920f, 1.0000f, 0.000f),
    StandbyBurnInFrame((-4).dp, (-5).dp, 0.885f, 0.9988f, -0.006f),
    StandbyBurnInFrame(4.dp, 3.dp, 0.905f, 0.9982f, 0.004f),
    StandbyBurnInFrame((-3).dp, 5.dp, 0.875f, 0.9986f, -0.005f),
    StandbyBurnInFrame(5.dp, (-3).dp, 0.900f, 0.9980f, 0.006f),
    StandbyBurnInFrame((-5).dp, 2.dp, 0.895f, 0.9989f, -0.003f),
    StandbyBurnInFrame(3.dp, (-5).dp, 0.880f, 0.9992f, 0.003f),
    StandbyBurnInFrame(0.dp, 5.dp, 0.910f, 0.9985f, -0.002f),
)

private fun shiftedStandbyRed(shift: Float): Color =
    Color(
        red = (StandbyRed.red + shift).coerceIn(0f, 1f),
        green = (StandbyRed.green + shift * 0.35f).coerceIn(0f, 1f),
        blue = (StandbyRed.blue + shift * 0.25f).coerceIn(0f, 1f),
        alpha = StandbyRed.alpha,
    )
