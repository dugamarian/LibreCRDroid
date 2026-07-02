package re.abbot.librecr.app

import android.Manifest
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.WearAppearanceSettings
import re.abbot.librecr.app.data.WearDisplayFontWeight
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.log.GlucoseLatencyTracer
import re.abbot.librecr.app.ble.toLastGlucose
import re.abbot.librecr.app.service.SensorForegroundService
import re.abbot.librecr.app.wear.complication.LibreComplicationUpdater
import re.abbot.librecr.protocol.TrendArrowShape
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibreCR.init(this)
        resumeOwnedSensorConnection()
        LibreComplicationUpdater.requestAll(this)
        setContent {
            WearTheme {
                WearScreen()
            }
        }
    }

    private fun resumeOwnedSensorConnection() {
        lifecycleScope.launch {
            runCatching {
                val session = LibreCR.store.loadSession()
                val autoConnect = LibreCR.store.autoConnectEnabled()
                if (session == null || !autoConnect) {
                    BleLog.log("wear activity: auto-start skipped session=${session != null} enabled=$autoConnect")
                    return@launch
                }
                BleLog.log("wear activity: auto-start saved provisioning localFullHandshake=true")
                SensorForegroundService.start(this@MainActivity, allowCandidateFirstPair = true)
            }.onFailure {
                BleLog.log("wear activity: auto-start failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }
}

@Composable
private fun WearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF4EE6B8),
            background = Color.Black,
            surface = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        content = content,
    )
}

@Composable
private fun WearScreen() {
    // Live in-memory reading drives the UI so decode→ui is instant; the persisted DataStore value
    // is only the cold-start fallback (before the service has produced a reading this session).
    val live by LibreCR.manager.glucose.collectAsState()
    val persisted by LibreCR.store.lastGlucoseFlow.collectAsState(initial = null)
    val reading = live?.toLastGlucose() ?: persisted
    val appearance by LibreCR.appearance.settingsFlow.collectAsState(initial = WearAppearanceSettings())
    var burnInFrameIndex by remember { mutableStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    // End of the interactive UI path: Compose now holds the new value and is about to
    // recompose. The gap to STORE_UPDATED is the DataStore-write → flow-emission round-trip.
    LaunchedEffect(reading?.lifeCount, reading?.receivedAtMs) {
        reading?.let { GlucoseLatencyTracer.mark(it.lifeCount, GlucoseLatencyTracer.Stage.UI_RENDERED) }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(requiredPermissions())
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            burnInFrameIndex = (burnInFrameIndex + 1) % WEAR_BURN_IN_FRAMES.size
        }
    }

    val burnInFrame = WEAR_BURN_IN_FRAMES[burnInFrameIndex]

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset(burnInFrame.x, burnInFrame.y)
                .scale(burnInFrame.scale)
                .alpha(burnInFrame.alpha)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WearReadingLayout(reading, appearance)
        }
    }
}

private data class WearBurnInFrame(
    val x: Dp,
    val y: Dp,
    val scale: Float,
    val alpha: Float,
)

private val WEAR_BURN_IN_FRAMES = listOf(
    WearBurnInFrame(0.dp, 0.dp, 1.00f, 0.96f),
    WearBurnInFrame((-10).dp, (-7).dp, 0.985f, 0.90f),
    WearBurnInFrame(9.dp, 6.dp, 0.992f, 0.94f),
    WearBurnInFrame((-6).dp, 10.dp, 0.980f, 0.88f),
    WearBurnInFrame(11.dp, (-9).dp, 0.988f, 0.92f),
    WearBurnInFrame(0.dp, 12.dp, 0.982f, 0.90f),
    WearBurnInFrame((-12).dp, 2.dp, 0.990f, 0.94f),
    WearBurnInFrame(7.dp, (-12).dp, 0.984f, 0.89f),
)

@Composable
private fun WearReadingLayout(
    reading: SensorStateStore.LastGlucose?,
    appearance: WearAppearanceSettings,
) {
    val mgDl = reading?.mgDL
    val fontWeight = appearance.fontWeight.toComposeFontWeight()
    val stale = !isFresh(reading)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = mgDl?.let { appearance.unit.format(it) } ?: "--",
            fontSize = 76.sp,
            lineHeight = 76.sp,
            fontWeight = fontWeight,
            letterSpacing = 0.sp,
            color = composeColor(appearance.glucoseColorFor(mgDl)),
        )
        TrendArrow(
            trend = reading?.trend,
            color = composeColor(appearance.trendColorFor(mgDl)),
            size = 48.dp,
            modifier = Modifier.alpha(if (reading == null) 0.48f else 1f),
        )
    }
    Spacer(modifier = Modifier.size(10.dp))
    Text(
        text = formatDelta(reading?.deltaMgDlPerMin, appearance.unit),
        fontSize = 22.sp,
        lineHeight = 26.sp,
        fontWeight = fontWeight,
        color = composeColor(appearance.deltaColorFor(mgDl)),
        modifier = Modifier.alpha(if (reading == null) 0.48f else 1f),
    )
    Spacer(modifier = Modifier.size(6.dp))
    Text(
        text = reading?.receivedAtMs?.takeIf { it > 0L }?.let { formatTimestamp(it) } ?: "--:--",
        fontSize = 16.sp,
        lineHeight = 20.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = fontWeight,
        color = composeColor(appearance.timestampColor(stale)),
    )
}

private fun requiredPermissions(): Array<String> {
    val permissions = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions += Manifest.permission.BLUETOOTH_SCAN
        permissions += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.POST_NOTIFICATIONS
    }
    return permissions.toTypedArray()
}

/** The AOD-style trend arrow (shared [TrendArrowShape] geometry) drawn as a Compose vector. */
@Composable
private fun TrendArrow(
    trend: String?,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val rotation = TrendArrowShape.rotationDegrees(trend) ?: return@Canvas
        val side = this.size.minDimension
        val stroke = side * TrendArrowShape.STROKE_FRACTION
        withTransform({
            scale(TrendArrowShape.SCALE, TrendArrowShape.SCALE)
            rotate(rotation)
        }) {
            TrendArrowShape.SEGMENTS.forEach { s ->
                drawLine(
                    color = color,
                    start = Offset(s.x0 * side, s.y0 * side),
                    end = Offset(s.x1 * side, s.y1 * side),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private fun isFresh(reading: SensorStateStore.LastGlucose?, nowMs: Long = System.currentTimeMillis()): Boolean =
    reading != null && reading.receivedAtMs > 0L && nowMs - reading.receivedAtMs < WEAR_STALE_AFTER_MS

private fun WearDisplayFontWeight.toComposeFontWeight(): FontWeight = when (this) {
    WearDisplayFontWeight.THIN -> FontWeight.Thin
    WearDisplayFontWeight.EXTRA_LIGHT -> FontWeight.ExtraLight
    WearDisplayFontWeight.LIGHT -> FontWeight.Light
    WearDisplayFontWeight.NORMAL -> FontWeight.Normal
    WearDisplayFontWeight.MEDIUM -> FontWeight.Medium
    WearDisplayFontWeight.SEMIBOLD -> FontWeight.SemiBold
    WearDisplayFontWeight.BOLD -> FontWeight.Bold
    WearDisplayFontWeight.EXTRA_BOLD -> FontWeight.ExtraBold
}

private fun composeColor(argb: Int): Color =
    Color(
        red = AndroidColor.red(argb),
        green = AndroidColor.green(argb),
        blue = AndroidColor.blue(argb),
        alpha = AndroidColor.alpha(argb),
    )

private fun formatDelta(delta: Double?, unit: GlucoseUnit = GlucoseUnit.MG_DL): String {
    if (delta == null) return "--"
    return unit.formatDelta(delta.coerceIn(-99.0, 99.0))
}

private fun formatTimestamp(timestampMs: Long): String =
    DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestampMs))

private const val WEAR_STALE_AFTER_MS = 6 * 60_000L
