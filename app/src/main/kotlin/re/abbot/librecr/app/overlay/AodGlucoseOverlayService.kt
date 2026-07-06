package re.abbot.librecr.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.R
import re.abbot.librecr.app.isFreshGlucose
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.ble.GlucoseDisplayStatus
import re.abbot.librecr.app.ble.GlucoseUi
import re.abbot.librecr.app.ble.isActiveGlucoseUnavailable
import re.abbot.librecr.app.ble.isUnavailableForGlucoseDisplay
import re.abbot.librecr.protocol.TrendArrowShape
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention
import kotlin.math.max
import kotlin.math.roundToInt

class AodGlucoseOverlayService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var displayManager: DisplayManager? = null
    private var aodView: AodGlucoseView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var receiverRegistered = false
    private var prefsRegistered = false
    private val history = ArrayDeque<GlucoseUi>()
    private var localReading: GlucoseUi? = null
    private var storedReading: GlucoseUi? = null
    private var sensorAttention: Libre3SensorAttention = Libre3SensorAttention.None
    private var connectionState: ConnectionState = ConnectionState.IDLE
    private var burnInIndex = 0
    private var currentPosition = AodSettings.POSITION_TOP
    private var currentUnit: GlucoseUnit = GlucoseUnit.MG_DL
    private var lastWindowRefreshMs = 0L
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
        val settings = AodSettings.load(this)
        aodView?.setAodSettings(settings)
        if (!settings.enabled) hideOverlay() else refreshVisibility()
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (aodView != null) {
                aodView?.setReading(currentReading, history.toList(), currentDisplayStatus)
                advanceBurnIn()
                updateLayoutPosition()
                handler.postDelayed(this, PERIODIC_REFRESH_MS)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_REFRESH -> {
                    aodView?.invalidate()
                    refreshVisibility()
                }
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> refreshVisibility()
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) refreshVisibility()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        LibreCR.init(this)
        prefs = overlayPrefs()
        if (!prefsRegistered) {
            prefs.registerOnSharedPreferenceChangeListener(prefListener)
            prefsRegistered = true
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, handler)
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter().apply {
                    addAction(ACTION_REFRESH)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        observeGlucose()
        refreshVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window-state changes are a belt-and-suspenders trigger for the AOD transition (display
        // callbacks vary per device), but this system-wide stream fires constantly during normal
        // phone use. Debounce it: the display listener + screen on/off receiver remain the primary
        // signals, so a short suppression window here cannot miss the doze transition.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastWindowRefreshMs < WINDOW_EVENT_DEBOUNCE_MS) return
            lastWindowRefreshMs = now
            refreshVisibility()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        if (prefsRegistered && ::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            prefsRegistered = false
        }
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        displayManager = null
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        hideOverlay()
        scope.cancel()
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
                    aodView?.setGlucoseUnit(unit)
                }
        }
    }

    private fun replaceHistory(readings: List<GlucoseUi>) {
        history.clear()
        readings.takeLast(MAX_HISTORY_POINTS).forEach { history.addLast(it) }
        publishReading()
    }

    private fun publishReading() {
        aodView?.setReading(currentReading, history.toList(), currentDisplayStatus)
    }

    private fun refreshVisibility() {
        val settings = AodSettings.load(this)
        if (!settings.enabled) {
            hideOverlay()
            return
        }
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val isAod = display?.state == Display.STATE_DOZE || display?.state == Display.STATE_DOZE_SUSPEND
        if (isAod) showOverlay(settings) else hideOverlay()
    }

    private fun showOverlay(settings: AodSettings) {
        if (aodView == null) {
            val bold = ResourcesCompat.getFont(this, R.font.google_sans_rounded_bold) ?: Typeface.DEFAULT_BOLD
            val regular = ResourcesCompat.getFont(this, R.font.google_sans_rounded_regular) ?: Typeface.DEFAULT
            val view = AodGlucoseView(this, bold, regular).apply {
                setAodSettings(settings)
                setGlucoseUnit(currentUnit)
                setReading(currentReading, history.toList(), currentDisplayStatus)
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            runCatching {
                windowManager?.addView(view, lp)
                aodView = view
                layoutParams = lp
                choosePosition(settings, preferDifferent = false)
            }.onFailure {
                BleLog.log("aod: addView failed: ${it.message ?: it::class.java.simpleName}")
            }
        } else {
            aodView?.setAodSettings(settings)
        }
        updateLayoutPosition()
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, PERIODIC_REFRESH_MS)
    }

    private fun hideOverlay() {
        handler.removeCallbacks(refreshRunnable)
        aodView?.let { runCatching { windowManager?.removeView(it) } }
        aodView = null
        layoutParams = null
        currentPosition = AodSettings.POSITION_TOP
    }

    private fun updateLayoutPosition() {
        val view = aodView ?: return
        val lp = layoutParams ?: return
        val settings = AodSettings.load(this)
        val frame = BURN_IN_FRAMES[burnInIndex % BURN_IN_FRAMES.size]
        val metrics = resources.displayMetrics
        val maxX = max(0, metrics.widthPixels - view.measuredWidth)
        val xBias = when (settings.alignment) {
            AodSettings.ALIGN_START -> 0.04f
            AodSettings.ALIGN_END -> 0.96f
            else -> 0.50f
        }
        lp.x = (maxX * xBias + frame.jitterX.dpToPx(this))
            .roundToInt()
            .coerceIn(0, maxX)

        val maxY = max(0, metrics.heightPixels - view.measuredHeight)
        val third = maxY / 3f
        val bandStart = when (currentPosition) {
            AodSettings.POSITION_BOTTOM -> third * 2f
            AodSettings.POSITION_CENTER -> third
            else -> 0f
        }
        val yBias = settings.verticalPosition.coerceIn(0f, 1f)
        lp.y = (bandStart + third * yBias + frame.jitterY.dpToPx(this))
            .roundToInt()
            .coerceIn(0, maxY)
        view.setBurnInFrame(frame)
        runCatching { windowManager?.updateViewLayout(view, lp) }
    }

    private fun advanceBurnIn() {
        burnInIndex = (burnInIndex + 1) % BURN_IN_FRAMES.size
    }

    private fun choosePosition(settings: AodSettings, preferDifferent: Boolean) {
        val positions = settings.positions.ifEmpty { setOf(AodSettings.POSITION_TOP) }.toList()
        val candidates = if (preferDifferent && positions.size > 1) positions.filterNot { it == currentPosition } else positions
        currentPosition = candidates.randomOrNull() ?: AodSettings.POSITION_TOP
    }

    companion object {
        const val ACTION_REFRESH = "re.abbot.librecr.app.action.AOD_IMMEDIATE_REFRESH"
        private const val PERIODIC_REFRESH_MS = 60_000L
        private const val WINDOW_EVENT_DEBOUNCE_MS = 500L
        private const val MAX_HISTORY_POINTS = 48
        private val BURN_IN_FRAMES = listOf(
            BurnInFrame(jitterX = 0f, jitterY = 0f, alpha = 0.960f),
            BurnInFrame(jitterX = -1.0f, jitterY = -1.5f, alpha = 0.954f),
            BurnInFrame(jitterX = 0.8f, jitterY = 1.2f, alpha = 0.958f),
            BurnInFrame(jitterX = -0.6f, jitterY = 1.8f, alpha = 0.952f),
            BurnInFrame(jitterX = 1.0f, jitterY = -1.6f, alpha = 0.956f),
            BurnInFrame(jitterX = 0f, jitterY = 2.0f, alpha = 0.955f),
            BurnInFrame(jitterX = -1.2f, jitterY = 0.4f, alpha = 0.959f),
            BurnInFrame(jitterX = 0.6f, jitterY = -2.0f, alpha = 0.953f),
            BurnInFrame(jitterX = -0.4f, jitterY = 0.8f, alpha = 0.957f),
            BurnInFrame(jitterX = 0.4f, jitterY = -0.6f, alpha = 0.955f),
        )

        fun start(context: Context): Boolean {
            context.overlayPrefs().edit().putBoolean(AodSettings.KEY_ENABLED, true).apply()
            context.sendBroadcast(Intent(ACTION_REFRESH).setPackage(context.packageName))
            if (!isAccessibilityEnabled(context)) {
                context.startActivity(accessibilitySettingsIntent())
                return false
            }
            return true
        }

        fun stop(context: Context) {
            context.overlayPrefs().edit().putBoolean(AodSettings.KEY_ENABLED, false).apply()
            context.sendBroadcast(Intent(ACTION_REFRESH).setPackage(context.packageName))
        }

        fun accessibilitySettingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun isAccessibilityEnabled(context: Context): Boolean {
            val expected = ComponentName(context, AodGlucoseOverlayService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (service in splitter) {
                if (service.equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }
}

private data class BurnInFrame(
    val jitterX: Float,
    val jitterY: Float,
    val alpha: Float,
)

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

private class AodGlucoseView(
    context: Context,
    private val boldTypeface: Typeface,
    private val regularTypeface: Typeface,
) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(72, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f.dpToPx(context)
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF30D158.toInt() }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(178, 255, 255, 255) }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 255, 255, 255)
        strokeWidth = 1f.dpToPx(context)
    }
    private val chartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f.dpToPx(context)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val chipRect = RectF()
    private val chartRect = RectF()
    private val path = Path()
    private var settings = AodSettings.load(context)
    private var glucoseUnit: GlucoseUnit = GlucoseUnit.MG_DL
    private var reading: GlucoseUi? = null
    private var history: List<GlucoseUi> = emptyList()
    private var displayStatus: GlucoseDisplayStatus = GlucoseDisplayStatus.NORMAL
    private var burnInAlpha = 0.96f

    fun setAodSettings(settings: AodSettings) {
        this.settings = settings
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

    fun setBurnInFrame(frame: BurnInFrame) {
        burnInAlpha = frame.alpha.coerceIn(0.94f, 0.98f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        configurePaints()
        val width = (resources.displayMetrics.widthPixels * 0.9f).roundToInt()
        val bodyHeight = 118f.dpToPx(context) * settings.textScale.coerceIn(0.75f, 2.4f)
        val chartHeight = if (settings.showChart) 76f.dpToPx(context) * settings.chartScale else 0f
        val height = bodyHeight + chartHeight + 18f.dpToPx(context)
        setMeasuredDimension(width, height.roundToInt().coerceAtLeast(128f.dpToPx(context).roundToInt()))
    }

    override fun onDraw(canvas: Canvas) {
        configurePaints()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        bgPaint.color = applyAlpha(settings.backgroundColor, settings.opacity * burnInAlpha)
        val radius = 30f.dpToPx(context)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        canvas.drawRoundRect(rect.insetCopy(0.5f.dpToPx(context)), radius, radius, borderPaint)

        val pad = 18f.dpToPx(context)
        val headerBaseline = 28f.dpToPx(context)
        val age = ageText(reading?.receivedAtMs)
        canvas.drawCircle(pad + 4f.dpToPx(context), headerBaseline - 4f.dpToPx(context), 3.5f.dpToPx(context), livePaint)
        canvas.drawText("LibreCRDroid", pad + 14f.dpToPx(context), headerBaseline, labelPaint)
        canvas.drawText(age, width - pad - labelPaint.measureText(age), headerBaseline, labelPaint)

        val hasValue = reading?.mgDL != null
        val glucose = primaryText()
        val unit = glucoseUnit.label
        val valueBaseline = headerBaseline + 58f.dpToPx(context) * settings.textScale.coerceIn(0.75f, 2.2f)
        val rowCenterY = valueBaseline + (valuePaint.ascent() + valuePaint.descent()) / 2f
        val showUnit = hasValue && settings.showSecondary
        val showArrow = hasValue && settings.showArrow && TrendArrowShape.hasArrow(reading?.trend)
        val arrowSize = if (showArrow) 34f.dpToPx(context) * settings.arrowScale * settings.textScale.coerceIn(0.75f, 2f) else 0f
        val delta = if (hasValue) deltaText(history, glucoseUnit) else null
        val unitWidth = if (showUnit) metaPaint.measureText(unit) + 9f.dpToPx(context) else 0f
        val arrowChipWidth = if (showArrow) arrowSize + 18f.dpToPx(context) else 0f
        val deltaChipWidth = delta?.let { metaPaint.measureText(it) + 20f.dpToPx(context) + 7f.dpToPx(context) } ?: 0f
        val groupWidth = valuePaint.measureText(glucose) + unitWidth + arrowChipWidth + deltaChipWidth
        var x = pad
        if (settings.alignment == AodSettings.ALIGN_CENTER) {
            x = (width - groupWidth) / 2f
        } else if (settings.alignment == AodSettings.ALIGN_END) {
            x = width - groupWidth - pad
        }
        canvas.drawText(glucose, x, valueBaseline, valuePaint)
        x += valuePaint.measureText(glucose) + 9f.dpToPx(context)
        if (showUnit) {
            val unitBaseline = rowCenterY - (metaPaint.ascent() + metaPaint.descent()) / 2f
            canvas.drawText(unit, x, unitBaseline, metaPaint)
            x += metaPaint.measureText(unit) + 9f.dpToPx(context)
        }
        if (showArrow) {
            val arrowChipHeight = arrowSize + 12f.dpToPx(context)
            val chipTop = rowCenterY - arrowChipHeight / 2f + settings.arrowVerticalOffsetDp.dpToPx(context)
            chipRect.set(x, chipTop, x + arrowSize + 18f.dpToPx(context), chipTop + arrowSize + 12f.dpToPx(context))
            chipPaint.color = Color.argb((42f * burnInAlpha).roundToInt(), 255, 255, 255)
            canvas.drawRoundRect(chipRect, chipRect.height() / 2f, chipRect.height() / 2f, chipPaint)
            drawArrow(
                canvas,
                reading?.trend,
                chipRect.left + 9f.dpToPx(context),
                chipRect.top + 6f.dpToPx(context),
                arrowSize,
            )
            x = chipRect.right + 7f.dpToPx(context)
        }
        delta?.let {
            val deltaChipHeight = 30f.dpToPx(context)
            val deltaBaseline = rowCenterY - (metaPaint.ascent() + metaPaint.descent()) / 2f
            chipRect.set(x, rowCenterY - deltaChipHeight / 2f, x + metaPaint.measureText(it) + 20f.dpToPx(context), rowCenterY + deltaChipHeight / 2f)
            chipPaint.color = Color.argb((34f * burnInAlpha).roundToInt(), 255, 255, 255)
            canvas.drawRoundRect(chipRect, chipRect.height() / 2f, chipRect.height() / 2f, chipPaint)
            canvas.drawText(it, chipRect.left + 10f.dpToPx(context), deltaBaseline, metaPaint)
        }

        if (settings.showChart) {
            chartRect.set(
                pad,
                height - 82f.dpToPx(context) * settings.chartScale,
                width - pad,
                height - 18f.dpToPx(context),
            )
            drawChart(canvas)
        }
    }

    private fun configurePaints() {
        val typeface = if (settings.fontStyle == FONT_REGULAR) regularTypeface else boldTypeface
        valuePaint.typeface = typeface
        metaPaint.typeface = typeface
        labelPaint.typeface = regularTypeface
        valuePaint.alpha = (255f * burnInAlpha).roundToInt().coerceIn(184, 245)
        metaPaint.alpha = (220f * burnInAlpha).roundToInt().coerceIn(140, 220)
        labelPaint.alpha = (178f * burnInAlpha).roundToInt().coerceIn(112, 178)
        livePaint.alpha = (255f * burnInAlpha).roundToInt().coerceIn(160, 255)
        borderPaint.alpha = (72f * burnInAlpha).roundToInt().coerceIn(36, 72)
        valuePaint.textSize = 58f.spToPx(context) * settings.textScale.coerceIn(0.65f, 2.2f)
        metaPaint.textSize = 17f.spToPx(context) * settings.metaScale
        labelPaint.textSize = 13f.spToPx(context) * settings.metaScale
        arrowPaint.strokeWidth = 3.8f.dpToPx(context) * settings.arrowScale
    }

    private fun primaryText(): String =
        reading?.mgDL?.let { glucoseUnit.format(it) } ?: when (displayStatus) {
            GlucoseDisplayStatus.SENSOR_ERROR -> context.getString(R.string.sensor_error_short)
            GlucoseDisplayStatus.OUT_OF_RANGE -> context.getString(R.string.sensor_out_of_range_short)
            GlucoseDisplayStatus.NORMAL -> "--"
        }

    private fun drawChart(canvas: Canvas) {
        // Chart line only: the uncapped value so deep hypos below the ~40 "LO" floor stay visible.
        // The headline number and colors keep the capped mgDL.
        val points = history.mapNotNull { g ->
            val mg = g.chartMgDL ?: g.mgDL ?: return@mapNotNull null
            if (mg <= 0) return@mapNotNull null
            g.receivedAtMs to mg
        }
        if (points.size < 2) return
        canvas.drawLine(chartRect.left, chartRect.bottom, chartRect.right, chartRect.bottom, guidePaint)
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
        chartPaint.color = if (points.last().second in 70..180) settings.chartInRangeColor else settings.chartOutOfRangeColor
        canvas.drawPath(path, chartPaint)
    }

    private fun drawArrow(
        canvas: Canvas,
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
            canvas.drawLine(left + s.x0 * size, top + s.y0 * size, left + s.x1 * size, top + s.y1 * size, arrowPaint)
        }
        canvas.restore()
    }

    private fun applyAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun RectF.insetCopy(inset: Float): RectF =
        RectF(left + inset, top + inset, right - inset, bottom - inset)
}

private fun ageText(receivedAtMs: Long?): String {
    if (receivedAtMs == null || receivedAtMs <= 0L) return "0 min"
    val minutes = ((System.currentTimeMillis() - receivedAtMs).coerceAtLeast(0L) / 60_000L).toInt()
    return if (minutes <= 0) "now" else "$minutes min"
}
