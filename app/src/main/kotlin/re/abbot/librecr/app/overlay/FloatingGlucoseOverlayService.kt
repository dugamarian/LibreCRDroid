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
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.R
import re.abbot.librecr.app.isFreshGlucose
import re.abbot.librecr.protocol.TrendArrowShape
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.ble.GlucoseDisplayStatus
import re.abbot.librecr.app.ble.GlucoseUi
import re.abbot.librecr.app.ble.isActiveGlucoseUnavailable
import re.abbot.librecr.app.ble.isUnavailableForGlucoseDisplay
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention
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
    private var connectionState: ConnectionState = ConnectionState.IDLE
    private var currentUnit: GlucoseUnit = GlucoseUnit.MG_DL
    private lateinit var prefs: SharedPreferences

    private val currentReading: GlucoseUi?
        get() {
            val local = localReading
            if (sensorAttention != Libre3SensorAttention.None) {
                return local?.copy(mgDL = null, usable = false)
                    ?: storedReading?.takeIf { isFreshGlucose(it.receivedAtMs) }?.copy(mgDL = null, usable = false)
            }
            // A fresh unavailable live reading is the newest glucose state: render "OOR" instead of
            // falling back to the older stored value.
            if (local.isActiveGlucoseUnavailable()) return local?.copy(mgDL = null)
            return local?.takeIf { it.usable && it.mgDL != null && isFreshGlucose(it.receivedAtMs) }
                ?: storedReading?.takeIf { isFreshGlucose(it.receivedAtMs) }
        }

    private val currentDisplayStatus: GlucoseDisplayStatus
        get() = when {
            sensorAttention != Libre3SensorAttention.None -> GlucoseDisplayStatus.SENSOR_ERROR
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
        LibreCR.init(this)
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
        val screenWidth = resources.displayMetrics.widthPixels
        val viewWidth = overlayView?.measuredWidth?.takeIf { it > 0 } ?: 260
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = ((screenWidth - viewWidth) / 2f).roundToInt()
        lp.y = settings.islandVerticalOffsetDp.dpToPx(this).roundToInt()
    }

    private fun observeGlucose() {
        // The two reading collectors feed only the CURRENT value (live preferred, stored fallback).
        // The mini-chart history has a single source of truth — the store's history blob, which is
        // appended on every reading anyway — so no manual per-reading append here (it was redundant:
        // the next replaceHistory rebuilt the deque from the blob regardless).
        scope.launch {
            LibreCR.manager.glucose.collectLatest { reading ->
                localReading = reading
                publishReading()
            }
        }
        scope.launch {
            LibreCR.manager.state.collectLatest {
                connectionState = it
                publishReading()
            }
        }
        scope.launch {
            LibreCR.store.lastGlucoseFlow.collectLatest { last ->
                storedReading = last?.toGlucoseUi()
                publishReading()
            }
        }
        scope.launch {
            LibreCR.store.sensorStatusFlow.collectLatest {
                sensorAttention = it?.attention ?: Libre3SensorAttention.None
                publishReading()
            }
        }
        scope.launch {
            LibreCR.store.glucoseHistoryFlow.collectLatest { storedHistory ->
                replaceHistory(storedHistory.map { it.toGlucoseUi() })
            }
        }
        scope.launch {
            LibreCR.settings.settingsFlow
                .map { it.unit }
                .distinctUntilChanged()
                .collectLatest { unit ->
                    currentUnit = unit
                    overlayView?.setGlucoseUnit(unit)
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
        val hasValue = reading?.mgDL != null
        val primary = primaryText()
        val secondary = if (hasValue && currentSettings.showSecondary) glucoseUnit.label else ""
        val delta = if (hasValue) deltaText(history, glucoseUnit, includeSymbol = false).orEmpty() else ""
        val showArrow = hasValue && currentSettings.showArrow && TrendArrowShape.hasArrow(reading?.trend)
        val arrowWidth = if (showArrow) arrowSize() else 0f
        val arrowGap = if (showArrow) dp(1.5f) else 0f
        val deltaGap = if (delta.isNotEmpty()) dp(2f) else 0f
        val secondaryGap = if (secondary.isNotEmpty()) dp(3f) else 0f
        val gap = if (currentSettings.dynamicIsland) {
            (currentSettings.islandGapDp.takeIf { it > 0f } ?: 70f).dpToPx(context)
        } else {
            0f
        }
        val width = dp(12f) + valuePaint.measureText(primary) + arrowGap + arrowWidth +
            deltaGap + secondaryPaint.measureText(delta) + secondaryGap + secondaryPaint.measureText(secondary) + gap
        val height = (currentSettings.fontSizeSp * 1.14f).spToPx(context) + dp(4f)
        setMeasuredDimension(width.coerceAtLeast(dp(52f)).roundToInt(), height.coerceAtLeast(dp(24f)).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        configurePaints()
        drawContainer(canvas)
        drawFloatingContent(canvas, rect)
    }

    protected fun drawFloatingContent(canvas: Canvas, bounds: RectF, aod: AodSettings? = null) {
        val hasValue = reading?.mgDL != null
        val primary = primaryText()
        val baseY = bounds.centerY() - (valuePaint.ascent() + valuePaint.descent()) / 2f
        val leftPadding = dp(6f)
        val islandGap = if (currentSettings.dynamicIsland && aod == null) {
            (currentSettings.islandGapDp.takeIf { it > 0f } ?: 70f).dpToPx(context)
        } else {
            0f
        }
        var x = bounds.left + leftPadding + islandGap / 2f
        canvas.drawText(primary, x, baseY, valuePaint)
        x += valuePaint.measureText(primary) + dp(1.5f)
        val showArrow = (aod?.showArrow ?: currentSettings.showArrow) &&
            hasValue &&
            TrendArrowShape.hasArrow(reading?.trend)
        if (showArrow) {
            val size = arrowSize() * (aod?.arrowScale ?: 1f)
            val arrowTop = bounds.centerY() - size / 2f + (aod?.arrowVerticalOffsetDp ?: 0f).dpToPx(context)
            drawTrendArrow(canvas, reading?.trend, x, arrowTop, size, Color.WHITE)
            x += size + dp(2f)
        }
        if (hasValue) deltaText(history, glucoseUnit, includeSymbol = false)?.let {
            canvas.drawText(it, x, baseY, secondaryPaint)
            x += secondaryPaint.measureText(it) + dp(3f)
        }
        if (hasValue && (currentSettings.showSecondary || aod?.showSecondary == true)) {
            canvas.drawText(glucoseUnit.label, x, baseY, secondaryPaint)
        }
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
            GlucoseDisplayStatus.OUT_OF_RANGE -> context.getString(R.string.sensor_out_of_range_short)
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
}

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
