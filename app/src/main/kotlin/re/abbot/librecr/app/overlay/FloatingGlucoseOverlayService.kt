package re.abbot.librecr.app.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.R
import re.abbot.librecr.app.isFreshGlucose
import re.abbot.librecr.protocol.TrendArrowShape
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.ble.GlucoseDisplayStatus
import re.abbot.librecr.app.ble.GlucoseUi
import re.abbot.librecr.app.ble.isActiveGlucoseUnavailable
import re.abbot.librecr.app.ble.isSensorWarmupActive
import re.abbot.librecr.app.ble.isUnavailableForGlucoseDisplay
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.ui.theme.DarkGlucoseColors
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention
import re.abbot.librecr.protocol.dataplane.SensorLifecycle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class FloatingGlucoseOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var overlayView: FloatingGlucoseView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val history = ArrayDeque<GlucoseUi>()
    private var localReading: GlucoseUi? = null
    private var storedReading: GlucoseUi? = null
    private var sensorAttention: Libre3SensorAttention = Libre3SensorAttention.None
    private var warmupStartedAtMs: Long? = null
    private var warmupDurationMinutes = SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES
    private var connectionState: ConnectionState = ConnectionState.IDLE
    private var currentUnit: GlucoseUnit = GlucoseUnit.MG_DL
    private var targetLow = DEFAULT_TARGET_LOW
    private var targetHigh = DEFAULT_TARGET_HIGH
    private lateinit var prefs: SharedPreferences

    private val currentReading: GlucoseUi?
        get() {
            val local = localReading
            if (sensorAttention != Libre3SensorAttention.None) {
                return local?.copy(mgDL = null, usable = false)
                    ?: storedReading?.takeIf { isFreshGlucose(it.receivedAtMs) }?.copy(mgDL = null, usable = false)
            }
            // A fresh unavailable live reading is the newest glucose state. Do not fall back to the
            // older stored value, but also do not call it a sensor error without patch-status proof.
            if (local.isActiveGlucoseUnavailable()) return local?.copy(mgDL = null)
            return local?.takeIf { it.usable && it.mgDL != null && isFreshGlucose(it.receivedAtMs) }
                ?: storedReading?.takeIf { isFreshGlucose(it.receivedAtMs) }
        }

    private val currentDisplayStatus: GlucoseDisplayStatus
        get() = when {
            sensorAttention != Libre3SensorAttention.None -> GlucoseDisplayStatus.SENSOR_ERROR
            currentReading?.mgDL == null &&
                isSensorWarmupActive(warmupStartedAtMs, warmupDurationMinutes) ->
                GlucoseDisplayStatus.SENSOR_WARMUP
            localReading.isActiveGlucoseUnavailable() ||
                (currentReading == null && connectionState.isUnavailableForGlucoseDisplay()) ->
                GlucoseDisplayStatus.OUT_OF_RANGE
            else -> GlucoseDisplayStatus.NORMAL
        }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val settings = FloatingSettings.load(this)
        if (!settings.enabled) {
            removeOverlay()
            stopSelf()
            return@OnSharedPreferenceChangeListener
        }
        if (isLandscape()) {
            removeOverlay()
            return@OnSharedPreferenceChangeListener
        }
        if (overlayView == null) {
            addOverlay()
            return@OnSharedPreferenceChangeListener
        }
        overlayView?.setFloatingSettings(settings)
        layoutParams?.let { lp ->
            if (!settings.dynamicIsland) {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = settings.x
                lp.y = settings.y
            } else {
                applyDynamicIslandPosition(lp)
            }
            runCatching { windowManager?.updateViewLayout(overlayView, lp) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        GlucoRitm.init(this)
        prefs = overlayPrefs()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!canDrawOverlays(this)) {
            BleLog.log("floating: SYSTEM_ALERT_WINDOW permission missing")
            stopSelf()
            return
        }
        refreshOverlayVisibility()
        observeGlucose()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshOverlayVisibility()
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        scope.cancel()
        removeOverlay()
        super.onDestroy()
    }

    private fun refreshOverlayVisibility() {
        val settings = FloatingSettings.load(this)
        if (!settings.enabled) {
            removeOverlay()
            stopSelf()
            return
        }
        if (isLandscape()) {
            removeOverlay()
        } else {
            addOverlay()
        }
    }

    private fun addOverlay() {
        if (overlayView != null) return
        val settings = FloatingSettings.load(this)
        if (!settings.enabled || isLandscape()) return
        val bold = ResourcesCompat.getFont(this, R.font.google_sans_rounded_bold) ?: Typeface.DEFAULT_BOLD
        val regular = ResourcesCompat.getFont(this, R.font.google_sans_rounded_regular) ?: Typeface.DEFAULT

        val view = FloatingGlucoseView(this, bold, regular).apply {
            setFloatingSettings(settings)
            setGlucoseUnit(currentUnit)
            setTargetRange(targetLow, targetHigh)
            setReading(currentReading, history.toList(), currentDisplayStatus)
            setOnClickListener { openApp() }
            setDragListener { dx, dy, finished ->
                val lp = this@FloatingGlucoseOverlayService.layoutParams ?: return@setDragListener
                lp.x += dx
                lp.y += dy
                if (finished) {
                    prefs.edit()
                        .putInt(FloatingSettings.KEY_X, lp.x)
                        .putInt(FloatingSettings.KEY_Y, lp.y)
                        .apply()
                }
                runCatching { windowManager?.updateViewLayout(this, lp) }
            }
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.x
            y = settings.y
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (settings.dynamicIsland) applyDynamicIslandPosition(lp)

        runCatching {
            windowManager?.addView(view, lp)
            overlayView = view
            layoutParams = lp
        }.onFailure {
            BleLog.log("floating: addView failed: ${it.message ?: it::class.java.simpleName}")
            stopSelf()
        }
    }

    private fun removeOverlay() {
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        overlayView = null
        layoutParams = null
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applyDynamicIslandPosition(lp: WindowManager.LayoutParams) {
        val settings = FloatingSettings.load(this)
        // CENTER_HORIZONTAL re-centres on every re-measure. Computing x from measuredWidth
        // instead centred the *previous* width, so the pill drifted off the cutout whenever
        // the gap or font size changed.
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.x = 0
        lp.y = settings.islandVerticalOffsetDp.dpToPx(this).roundToInt()
    }

    private fun observeGlucose() {
        // The two reading collectors feed only the CURRENT value (live preferred, stored fallback).
        // The mini-chart history has a single source of truth — the store's history blob, which is
        // appended on every reading anyway — so no manual per-reading append here (it was redundant:
        // the next replaceHistory rebuilt the deque from the blob regardless).
        scope.launch {
            GlucoRitm.manager.glucose.collectLatest { reading ->
                localReading = reading
                publishReading()
            }
        }
        scope.launch {
            GlucoRitm.manager.state.collectLatest {
                connectionState = it
                publishReading()
            }
        }
        scope.launch {
            GlucoRitm.store.lastGlucoseFlow.collectLatest { last ->
                storedReading = last?.toGlucoseUi()
                publishReading()
            }
        }
        scope.launch {
            GlucoRitm.store.sensorStatusFlow.collectLatest {
                sensorAttention = it?.attention ?: Libre3SensorAttention.None
                publishReading()
            }
        }
        scope.launch {
            GlucoRitm.store.sensorWarmupFlow.collectLatest {
                warmupStartedAtMs = it?.startedAtMs
                publishReading()
            }
        }
        scope.launch {
            GlucoRitm.store.sessionFlow.collectLatest {
                warmupDurationMinutes =
                    it?.warmupMinutes ?: SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES
                publishReading()
            }
        }
        scope.launch {
            GlucoRitm.store.glucoseHistoryFlow.collectLatest { storedHistory ->
                replaceHistory(storedHistory.map { it.toGlucoseUi() })
            }
        }
        scope.launch {
            GlucoRitm.settings.settingsFlow
                .map { Triple(it.unit, it.targetLow, it.targetHigh) }
                .distinctUntilChanged()
                .collectLatest { (unit, low, high) ->
                    currentUnit = unit
                    targetLow = low
                    targetHigh = high
                    overlayView?.setGlucoseUnit(unit)
                    overlayView?.setTargetRange(low, high)
                }
        }
        scope.launch {
            while (true) {
                delay(FRESHNESS_REFRESH_MS)
                publishReading()
            }
        }
    }

    private fun replaceHistory(readings: List<GlucoseUi>) {
        history.clear()
        readings.takeLast(MAX_HISTORY_POINTS).forEach { history.addLast(it) }
        publishReading()
    }

    private fun publishReading() {
        overlayView?.setReading(currentReading, history.toList(), currentDisplayStatus)
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    companion object {
        private const val MAX_HISTORY_POINTS = 48
        private const val DEFAULT_TARGET_LOW = 70
        private const val DEFAULT_TARGET_HIGH = 180
        private const val FRESHNESS_REFRESH_MS = 30_000L

        fun start(context: Context): Boolean {
            if (!canDrawOverlays(context)) return false
            return runCatching {
                context.overlayPrefs().edit().putBoolean(FloatingSettings.KEY_ENABLED, true).apply()
                context.startService(Intent(context, FloatingGlucoseOverlayService::class.java))
            }.onFailure {
                BleLog.log("floating: start failed: ${it.message ?: it::class.java.simpleName}")
            }.isSuccess
        }

        fun stop(context: Context) {
            context.overlayPrefs().edit().putBoolean(FloatingSettings.KEY_ENABLED, false).apply()
            context.stopService(Intent(context, FloatingGlucoseOverlayService::class.java))
        }

        fun canDrawOverlays(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun permissionIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
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
        chartMgDL = chartMgDL,
    )

internal open class FloatingGlucoseView(
    context: Context,
    private val boldTypeface: Typeface,
    private val regularTypeface: Typeface,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(132, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(0.9f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(178, 255, 255, 255) }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val chartGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 255, 255, 255)
        strokeWidth = dp(1f)
    }
    private val chartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF30D158.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()
    private var reading: GlucoseUi? = null
    private var history: List<GlucoseUi> = emptyList()
    private var displayStatus: GlucoseDisplayStatus = GlucoseDisplayStatus.NORMAL
    protected var currentSettings = FloatingSettings.load(context)
    private var glucoseUnit: GlucoseUnit = GlucoseUnit.MG_DL
    private var targetLow = 70
    private var targetHigh = 180
    private var downRawX = 0f
    private var downRawY = 0f
    private var pendingClick = false
    private var dragListener: ((Int, Int, Boolean) -> Unit)? = null

    fun setFloatingSettings(settings: FloatingSettings) {
        currentSettings = settings
        requestLayout()
        invalidate()
    }

    fun setGlucoseUnit(unit: GlucoseUnit) {
        glucoseUnit = unit
        requestLayout()
        invalidate()
    }

    fun setTargetRange(low: Int, high: Int) {
        targetLow = low
        targetHigh = high
        invalidate()
    }

    fun setReading(
        reading: GlucoseUi?,
        history: List<GlucoseUi>,
        displayStatus: GlucoseDisplayStatus = this.displayStatus,
    ) {
        this.reading = reading
        this.history = history
        this.displayStatus = displayStatus
        requestLayout()
        invalidate()
    }

    fun setDragListener(listener: (Int, Int, Boolean) -> Unit) {
        dragListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        configurePaints()
        val parts = layoutParts()
        var width = contentWidth(parts)
        var height = (currentSettings.fontSizeSp * 1.14f).spToPx(context) + dp(4f)
        if (isIsland(null)) {
            // Island size is the cutout's business, not the font's: the explicit dp values win,
            // and the text measurement only acts as a floor so nothing ever gets clipped.
            width = width.coerceAtLeast(currentSettings.islandWidthDp.dpToPx(context))
            height = height.coerceAtLeast(currentSettings.islandHeightDp.dpToPx(context))
        }
        setMeasuredDimension(width.coerceAtLeast(dp(52f)).roundToInt(), height.coerceAtLeast(dp(24f)).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        configurePaints()
        drawContainer(canvas)
        drawFloatingContent(canvas, rect)
    }

    protected fun drawFloatingContent(canvas: Canvas, bounds: RectF, aod: AodSettings? = null) {
        val parts = layoutParts(aod)
        val baseY = bounds.centerY() - (valuePaint.ascent() + valuePaint.descent()) / 2f
        val tint = valueTint()
        valuePaint.color = tint
        secondaryPaint.color = tint.withAlpha(SECONDARY_ALPHA)
        val leadX: Float
        val trailX: Float
        if (isIsland(aod)) {
            // Both groups hang off the cutout rather than off the pill edges. Anchoring them to
            // the centre is what keeps the clear gap exactly `islandGapDp` wide and exactly over
            // the camera: laying out left-to-right instead shifted the gap by (lead - trail) / 2,
            // which grows with the font size and with the width of the delta.
            val half = islandGap(aod) / 2f
            leadX = bounds.centerX() - half - parts.leadWidth()
            trailX = bounds.centerX() + half + deltaOffset()
        } else {
            leadX = bounds.left + dp(6f)
            trailX = leadX + parts.leadWidth() + deltaOffset() +
                if (parts.trailWidth() > 0f) dp(2f) else 0f
        }
        var x = leadX
        canvas.drawText(parts.primary, x, baseY, valuePaint)
        x += valuePaint.measureText(parts.primary)
        if (parts.arrowSize > 0f) {
            x += dp(1.5f)
            val arrowTop = bounds.centerY() - parts.arrowSize / 2f +
                (aod?.arrowVerticalOffsetDp ?: 0f).dpToPx(context)
            drawTrendArrow(canvas, reading?.trend, x, arrowTop, parts.arrowSize, tint)
        }
        x = trailX
        if (parts.delta.isNotEmpty()) {
            canvas.drawText(parts.delta, x, baseY, secondaryPaint)
            x += secondaryPaint.measureText(parts.delta) + dp(3f)
        }
        if (parts.unit.isNotEmpty()) canvas.drawText(parts.unit, x, baseY, secondaryPaint)
    }

    /**
     * Natural width. Island mode keeps both sides equal so the gap lands dead centre; the delta
     * offset counts as part of the right side so nudging it never pushes text past the pill.
     */
    private fun contentWidth(parts: FloatingParts): Float = if (isIsland(null)) {
        val side = max(parts.leadWidth(), parts.trailWidth() + deltaOffset())
        dp(12f) + side * 2f + islandGap(null)
    } else {
        val trail = if (parts.trailWidth() > 0f) {
            (dp(2f) + parts.trailWidth() + deltaOffset()).coerceAtLeast(0f)
        } else {
            0f
        }
        dp(12f) + parts.leadWidth() + trail
    }

    private fun deltaOffset(): Float = currentSettings.deltaOffsetDp.dpToPx(context)

    /** Left group (value + trend arrow), right group (delta + unit) and the text that fills them. */
    private fun layoutParts(aod: AodSettings? = null): FloatingParts {
        val hasValue = reading?.mgDL != null
        val showArrow = (aod?.showArrow ?: currentSettings.showArrow) &&
            hasValue &&
            TrendArrowShape.hasArrow(reading?.trend)
        return FloatingParts(
            primary = primaryText(),
            arrowSize = if (showArrow) arrowSize() * (aod?.arrowScale ?: 1f) else 0f,
            delta = if (hasValue) deltaText(history, glucoseUnit, includeSymbol = false).orEmpty() else "",
            unit = if (hasValue && (currentSettings.showSecondary || aod?.showSecondary == true)) {
                glucoseUnit.label
            } else {
                ""
            },
        )
    }

    private fun FloatingParts.leadWidth(): Float =
        valuePaint.measureText(primary) + if (arrowSize > 0f) dp(1.5f) + arrowSize else 0f

    private fun FloatingParts.trailWidth(): Float {
        var width = secondaryPaint.measureText(delta)
        if (unit.isNotEmpty()) {
            width += secondaryPaint.measureText(unit) + if (delta.isNotEmpty()) dp(3f) else 0f
        }
        return width
    }

    private fun isIsland(aod: AodSettings?): Boolean = aod == null && currentSettings.dynamicIsland

    /**
     * Island mode always reserves the gap, even with nothing to put on the far side: it keeps the
     * pill width steady while the delta comes and goes, and keeps text off the camera cutout.
     */
    private fun islandGap(aod: AodSettings?): Float =
        if (isIsland(aod)) currentSettings.islandGapDp.dpToPx(context) else 0f

    /** Range color from the app palette (dark variant reads best on the translucent pill). */
    private fun valueTint(): Int {
        val mgDl = reading?.mgDL
        if (!currentSettings.glucoseRangeColors || mgDl == null) return Color.WHITE
        return DarkGlucoseColors.forMgDl(mgDl, targetLow, targetHigh).toArgb()
    }

    protected fun drawHistoryChart(
        canvas: Canvas,
        chartRect: RectF,
        inRangeColor: Int,
        outOfRangeColor: Int,
    ) {
        // Chart line only: the uncapped value so deep hypos below the ~40 "LO" floor stay visible.
        // The headline number, delta text and colors keep the capped mgDL.
        val points = history.mapNotNull { g ->
            val mg = g.chartMgDL ?: g.mgDL ?: return@mapNotNull null
            if (mg <= 0) return@mapNotNull null
            g.receivedAtMs to mg
        }
        if (points.size < 2) return
        canvas.drawLine(chartRect.left, chartRect.bottom, chartRect.right, chartRect.bottom, chartGuidePaint)
        val min = (points.minOf { it.second } - 20).coerceAtMost(70).coerceAtLeast(0)
        val max = (points.maxOf { it.second } + 20).coerceAtLeast(180)
        val t0 = points.first().first
        val t1 = points.last().first.coerceAtLeast(t0 + 1L)
        path.reset()
        points.forEachIndexed { index, point ->
            val x = chartRect.left + (point.first - t0).toFloat() / (t1 - t0).toFloat() * chartRect.width()
            val y = chartRect.bottom - (point.second - min).toFloat() / (max - min).toFloat() * chartRect.height()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        chartPaint.color = if (points.last().second in 70..180) inRangeColor else outOfRangeColor
        canvas.drawPath(path, chartPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentSettings.dynamicIsland) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                pendingClick = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).roundToInt()
                val dy = (event.rawY - downRawY).roundToInt()
                if (dx != 0 || dy != 0) {
                    if (max(abs(dx), abs(dy)) > dp(4f)) pendingClick = false
                    dragListener?.invoke(dx, dy, false)
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragListener?.invoke(0, 0, true)
                if (event.actionMasked == MotionEvent.ACTION_UP && pendingClick) performClick()
                pendingClick = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun configurePaints() {
        val typeface = when (currentSettings.fontStyle) {
            FONT_REGULAR -> regularTypeface
            else -> boldTypeface
        }
        valuePaint.typeface = typeface
        secondaryPaint.typeface = typeface
        valuePaint.textSize = currentSettings.fontSizeSp.spToPx(context)
        secondaryPaint.textSize = (currentSettings.fontSizeSp * 0.62f * 1.3f * 1.2f).spToPx(context)
        arrowPaint.strokeWidth = arrowSize() * 0.15f
    }

    private fun primaryText(): String =
        reading?.mgDL?.let { glucoseUnit.format(it) } ?: when (displayStatus) {
            GlucoseDisplayStatus.SENSOR_ERROR -> context.getString(R.string.sensor_error_short)
            GlucoseDisplayStatus.OUT_OF_RANGE -> if (reading != null) "NA" else "--"
            GlucoseDisplayStatus.SENSOR_WARMUP,
            GlucoseDisplayStatus.NORMAL -> "--"
        }

    private fun drawContainer(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val alpha = if (currentSettings.transparent) 0 else (currentSettings.opacity * 255).roundToInt().coerceIn(0, 255)
        bgPaint.color = Color.argb(alpha, 0, 0, 0)
        val radius = currentSettings.cornerRadiusDp.dpToPx(context)
        if (!currentSettings.transparent) canvas.drawRoundRect(rect, radius, radius, bgPaint)
        strokePaint.alpha = if (currentSettings.subtleOutline || currentSettings.transparent) 54 else 132
        canvas.drawRoundRect(rect.insetCopy(dp(0.5f)), radius, radius, strokePaint)
    }

    private fun drawTrendArrow(
        canvas: Canvas,
        trend: String?,
        left: Float,
        top: Float,
        size: Float,
        color: Int,
    ) {
        val rotation = TrendArrowShape.rotationDegrees(trend) ?: return
        arrowPaint.color = color
        val cx = left + size / 2f
        val cy = top + size / 2f
        canvas.save()
        canvas.scale(TrendArrowShape.SCALE, TrendArrowShape.SCALE, cx, cy)
        canvas.rotate(rotation, cx, cy)
        TrendArrowShape.SEGMENTS.forEach { s ->
            canvas.drawLine(left + s.x0 * size, top + s.y0 * size, left + s.x1 * size, top + s.y1 * size, arrowPaint)
        }
        canvas.restore()
    }

    private fun arrowSize(): Float = (currentSettings.fontSizeSp * 0.72f).dpToPx(context)
    private fun dp(value: Float): Float = value * density
    private fun RectF.insetCopy(inset: Float): RectF = RectF(left + inset, top + inset, right - inset, bottom - inset)
    private fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

    private companion object {
        const val SECONDARY_ALPHA = 178
    }
}

private data class FloatingParts(
    val primary: String,
    val arrowSize: Float,
    val delta: String,
    val unit: String,
)

internal fun deltaText(
    history: List<GlucoseUi>,
    unit: GlucoseUnit = GlucoseUnit.MG_DL,
    includeSymbol: Boolean = true,
): String? {
    val values = history.mapNotNull { reading ->
        val value = reading.mgDL ?: return@mapNotNull null
        reading.receivedAtMs to value
    }
    if (values.size < 2) return null
    val delta = values.last().second - values[values.lastIndex - 1].second
    val value = unit.formatDelta(delta.toDouble())
    return if (includeSymbol) "Δ $value" else value
}

internal fun Float.dpToPx(context: Context): Float = this * context.resources.displayMetrics.density
internal fun Float.spToPx(context: Context): Float = this * context.resources.displayMetrics.scaledDensity
