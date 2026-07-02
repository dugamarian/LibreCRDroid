package re.abbot.librecr.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.R
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.ble.GlucoseUi
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.isFreshGlucose
import re.abbot.librecr.app.log.GlucoseTimelineTracker
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.app.ui.chart.GlucoseChartCard
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.ui.common.TrendArrow
import re.abbot.librecr.app.ui.common.readingAgeText
import re.abbot.librecr.app.ui.common.trendLabel
import re.abbot.librecr.app.ui.theme.LocalGlucoseColors
import re.abbot.librecr.protocol.TrendArrowShape
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention
import re.abbot.librecr.protocol.dataplane.SensorLifecycle
import re.abbot.librecr.protocol.dataplane.SensorLifecyclePhase

private const val CHART_HISTORY_MS = 72L * 60L * 60L * 1000L
private const val DEEP_HISTORY_REFRESH_MS = 5L * 60L * 1000L
private val AttentionRed = Color(0xFFE53935)
private val AttentionAmber = Color(0xFFFFB300)

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    val glucoseColors = LocalGlucoseColors.current

    val local by LibreCR.manager.glucose.collectAsState()
    val remote by LibreCR.store.lastGlucoseFlow.collectAsState(initial = null)
    val session by LibreCR.store.sessionFlow.collectAsState(initial = null)
    val lifecycle by LibreCR.store.sensorLifecycleFlow.collectAsState(initial = null)
    val warmup by LibreCR.store.sensorWarmupFlow.collectAsState(initial = null)
    val sensorStatus by LibreCR.store.sensorStatusFlow.collectAsState(initial = null)
    val state by LibreCR.manager.state.collectAsState()
    val statusLine by LibreCR.manager.statusLine.collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val glucose: GlucoseUi? = local
        ?.takeIf { it.usable && it.mgDL != null && isFreshGlucose(it.receivedAtMs, nowMs) }
        ?: remote?.takeIf { isFreshGlucose(it.receivedAtMs, nowMs) }?.let {
            GlucoseUi(it.mgDL, it.trend, it.lifeCount, it.mgDL in 1..500, it.receivedAtMs, it.deltaMgDlPerMin)
        }
    // Closes the watch→phone timeline: the relayed reading is now on screen. Keyed by the
    // reading's identity so it fires once per reading, not on every recomposition.
    LaunchedEffect(glucose?.lifeCount, glucose?.receivedAtMs) {
        glucose?.lifeCount?.let { GlucoseTimelineTracker.onUiRendered(it, System.currentTimeMillis()) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    // Keep the recent list cheap and live, while the chart receives enough persistent history
    // to pan through all of its 3-day window.
    val ring by LibreCR.store.glucoseHistoryFlow.collectAsState(initial = emptyList())
    // Chart uses the uncapped chart value (chartMgDL) so deep hypos below the ~40 "LO" floor are
    // visible; the recent list keeps the capped mgDL. Fallback to mgDL when no chart value is stored.
    val liveSamples = remember(ring) {
        ring.map { GlucoseSample(it.chartMgDL ?: it.mgDL, it.receivedAtMs) }
    }
    // Load the deep 72h history periodically, not on every new reading. The live `ring` (recent ~1h,
    // with per-point trend) is overlaid on top each recomposition, so the chart tail stays current
    // without re-querying 72h of SQLite every minute.
    val persistent by produceState(initialValue = emptyList<GlucoseSample>()) {
        while (true) {
            value = LibreCR.history.chartSamples(System.currentTimeMillis() - CHART_HISTORY_MS)
            delay(DEEP_HISTORY_REFRESH_MS)
        }
    }
    val samples = remember(persistent, liveSamples) {
        val byMinute = LinkedHashMap<Long, GlucoseSample>(persistent.size + liveSamples.size)
        persistent.forEach { byMinute[it.atMs / 60_000L] = it }
        liveSamples.forEach { byMinute[it.atMs / 60_000L] = it }
        byMinute.values.sortedBy { it.atMs }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val mgDl = glucose?.mgDL
                val valueColor = mgDl?.let { glucoseColors.forMgDl(it, settings.targetLow, settings.targetHigh) }
                    ?: MaterialTheme.colorScheme.onSurface
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = mgDl?.let { settings.unit.format(it) } ?: "Sensor Error",
                        fontSize = if (mgDl == null) 36.sp else 76.sp,
                        lineHeight = if (mgDl == null) 40.sp else 80.sp,
                        fontWeight = FontWeight.Bold,
                        color = valueColor,
                    )
                    Column(
                        modifier = Modifier.padding(bottom = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (TrendArrowShape.hasArrow(glucose?.trend)) {
                            TrendArrow(
                                trend = glucose?.trend,
                                color = valueColor,
                                size = 32.dp,
                            )
                        }
                        Text(
                            settings.unit.label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Column(
                        modifier = Modifier.padding(bottom = 14.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            readingAgeText(glucose?.receivedAtMs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RangePill(mgDl, settings.targetLow, settings.targetHigh)
                        }
                    }
                }
            }
        }

        sensorStatus?.attention?.let { SensorAttentionCard(it) }

        SensorLifecycleCard(
            session = session,
            snapshot = lifecycle,
            warmup = warmup,
            fallbackGlucose = glucose,
            nowMs = nowMs,
        )

        GlucoseChartCard(samples, settings.unit, settings.targetLow, settings.targetHigh)

        RecentHistoryCard(ring, settings.unit, settings.targetLow, settings.targetHigh)

        ConnectionStatusCard(state, statusLine)
    }
}

private data class SensorAttentionPresentation(val title: String, val detail: String, val color: Color)

private fun sensorAttentionPresentation(attention: Libre3SensorAttention): SensorAttentionPresentation? = when (attention) {
    Libre3SensorAttention.None -> null
    Libre3SensorAttention.CheckSensor ->
        SensorAttentionPresentation("Verifică senzorul", "Senzorul raportează o problemă de inserție.", AttentionAmber)
    Libre3SensorAttention.SensorEnded ->
        SensorAttentionPresentation("Senzor încheiat", "Sesiunea senzorului s-a încheiat.", AttentionRed)
    Libre3SensorAttention.ReplaceSensor ->
        SensorAttentionPresentation("Înlocuiește senzorul", "Senzorul a raportat o eroare și trebuie înlocuit.", AttentionRed)
    is Libre3SensorAttention.Unknown ->
        SensorAttentionPresentation("Eroare senzor", "Cod necunoscut raportat de senzor (${attention.code}).", AttentionRed)
}

@Composable
private fun SensorAttentionCard(attention: Libre3SensorAttention) {
    val presentation = sensorAttentionPresentation(attention) ?: return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(presentation.color),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(presentation.title, fontWeight = FontWeight.SemiBold, color = presentation.color)
                Text(
                    presentation.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SensorLifecycleCard(
    session: re.abbot.librecr.app.data.ImportedSession?,
    snapshot: SensorStateStore.SensorLifecycleSnapshot?,
    warmup: SensorStateStore.SensorWarmupSnapshot?,
    fallbackGlucose: GlucoseUi?,
    nowMs: Long,
) {
    if (session == null) return
    val observed = snapshot ?: fallbackGlucose?.let {
        SensorStateStore.SensorLifecycleSnapshot(
            lifeCountMinutes = it.lifeCount,
            observedAtMs = it.receivedAtMs,
        )
    }
    val warmupMinutes = session.warmupMinutes ?: SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES
    val wearMinutes = session.wearMinutes
    val observedElapsedMinutes = observed?.let { extrapolatedLifeCountMinutes(it, nowMs) }
    val warmupElapsedMinutes = warmup?.let { elapsedSinceStartMinutes(it.startedAtMs, nowMs) }
    val elapsedMinutes = observedElapsedMinutes ?: warmupElapsedMinutes
    val showWarmup = warmup != null && warmupMinutes > 0 && (elapsedMinutes ?: 0) < warmupMinutes

    SectionCard(stringResource(R.string.sensor_section)) {
        if (observed == null && warmup == null) {
            Text(
                stringResource(R.string.sensor_waiting_status),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LifecycleProgressRow(
                stringResource(R.string.sensor_wear_duration),
                0f,
                wearMinutes?.let { formatDuration(it) } ?: stringResource(R.string.sensor_duration_unavailable),
            )
            return@SectionCard
        }

        val elapsed = elapsedMinutes ?: 0
        val lifecycle = SensorLifecycle(
            currentLifeCountMinutes = elapsed,
            warmupDurationMinutes = warmupMinutes,
            wearDurationMinutes = wearMinutes,
        )
        if (showWarmup) {
            val remainingWarmup = (warmupMinutes - elapsed).coerceAtLeast(0)
            LifecycleProgressRow(
                stringResource(R.string.sensor_warmup),
                progress(elapsed, warmupMinutes),
                stringResource(R.string.sensor_warmup_remaining, formatDuration(remainingWarmup)),
            )
        }

        val wearProgress = wearMinutes?.let { progress(elapsed, it) } ?: 0f
        val wearDetail = when {
            wearMinutes == null -> stringResource(R.string.sensor_duration_unavailable)
            lifecycle.phase == SensorLifecyclePhase.EXPIRED -> stringResource(R.string.sensor_expired)
            else -> stringResource(R.string.sensor_until_finish, formatDuration(lifecycle.remainingWearMinutes ?: 0))
        }
        LifecycleProgressRow(stringResource(R.string.sensor_wear_duration), wearProgress, wearDetail)

        Text(
            stringResource(R.string.sensor_lifetime, formatDuration(elapsed)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LifecycleProgressRow(label: String, progress: Float, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun extrapolatedLifeCountMinutes(
    snapshot: SensorStateStore.SensorLifecycleSnapshot,
    nowMs: Long,
): Int {
    val observedAt = snapshot.observedAtMs.takeIf { it > 0L } ?: return snapshot.lifeCountMinutes
    val extraMinutes = ((nowMs - observedAt).coerceAtLeast(0L) / 60_000L).toInt()
    return (snapshot.lifeCountMinutes + extraMinutes).coerceAtLeast(0)
}

private fun elapsedSinceStartMinutes(startedAtMs: Long, nowMs: Long): Int =
    ((nowMs - startedAtMs).coerceAtLeast(0L) / 60_000L).toInt()

private fun progress(elapsedMinutes: Int, totalMinutes: Int): Float =
    if (totalMinutes <= 0) 1f else elapsedMinutes.toFloat() / totalMinutes.toFloat()

@Composable
private fun formatDuration(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val days = safe / (24 * 60)
    val hours = (safe % (24 * 60)) / 60
    val mins = safe % 60
    return when {
        days > 0 && hours > 0 -> stringResource(R.string.duration_days_hours, days, hours)
        days > 0 -> stringResource(R.string.duration_days, days)
        hours > 0 && mins > 0 -> stringResource(R.string.duration_hours_minutes, hours, mins)
        hours > 0 -> stringResource(R.string.duration_hours, hours)
        else -> stringResource(R.string.duration_minutes, mins)
    }
}

@Composable
private fun RecentHistoryCard(
    ring: List<SensorStateStore.LastGlucose>,
    unit: re.abbot.librecr.app.data.GlucoseUnit,
    targetLow: Int,
    targetHigh: Int,
) {
    val colors = LocalGlucoseColors.current
    SectionCard(stringResource(R.string.home_recent)) {
        if (ring.isEmpty()) {
            Text(stringResource(R.string.home_recent_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ring.takeLast(12).asReversed().forEach { r ->
                val color = colors.forMgDl(r.mgDL, targetLow, targetHigh)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Text(timeHm(r.receivedAtMs), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (TrendArrowShape.hasArrow(r.trend)) {
                        TrendArrow(
                            trend = r.trend,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 18.dp,
                        )
                    }
                    Text(unit.formatWithUnit(r.mgDL), fontWeight = FontWeight.SemiBold, color = color)
                }
            }
        }
    }
}

private fun timeHm(ms: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

@Composable
private fun RangePill(mgDl: Int?, targetLow: Int, targetHigh: Int) {
    val colors = LocalGlucoseColors.current
    val (label, color) = when {
        mgDl == null -> stringResource(R.string.range_none) to MaterialTheme.colorScheme.surfaceVariant
        mgDl < targetLow -> stringResource(R.string.range_low) to colors.low
        mgDl > targetHigh -> stringResource(R.string.range_high) to colors.high
        else -> stringResource(R.string.range_in) to colors.inRange
    }
    Surface(color = color.copy(alpha = 0.18f), contentColor = color, shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ConnectionStatusCard(state: ConnectionState, statusLine: String) {
    val color = when (state) {
        ConnectionState.STREAMING -> MaterialTheme.colorScheme.primary
        ConnectionState.ERROR -> MaterialTheme.colorScheme.error
        ConnectionState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.tertiary
    }
    SectionCard(stringResource(R.string.home_connection)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Text(statusText(state, statusLine), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun statusText(state: ConnectionState, statusLine: String): String = when (state) {
    ConnectionState.IDLE -> stringResource(R.string.status_idle)
    ConnectionState.SCANNING -> stringResource(R.string.status_scanning)
    ConnectionState.CONNECTING -> stringResource(R.string.status_connecting)
    ConnectionState.HANDSHAKING -> stringResource(R.string.status_handshaking)
    ConnectionState.STREAMING -> stringResource(R.string.status_streaming)
    ConnectionState.RECONNECTING -> stringResource(R.string.status_reconnecting)
    ConnectionState.ERROR -> statusLine.ifBlank { stringResource(R.string.status_error) }
}
