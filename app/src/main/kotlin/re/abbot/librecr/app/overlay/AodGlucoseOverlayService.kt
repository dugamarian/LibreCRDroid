package re.abbot.librecr.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.widget.ChipStyle
import re.abbot.librecr.app.widget.GlucoseWidgetModel
import re.abbot.librecr.app.widget.GlucoseWidgetUi
import re.abbot.librecr.app.widget.GlucoseWidgetUpdater
import re.abbot.librecr.app.widget.WidgetChartBand
import re.abbot.librecr.app.widget.WidgetColors
import re.abbot.librecr.app.widget.WidgetFonts
import re.abbot.librecr.app.widget.WidgetGlucoseRange
import re.abbot.librecr.app.widget.badgeStyle
import re.abbot.librecr.app.widget.chartWindowLabel
import re.abbot.librecr.app.widget.chipStyle
import re.abbot.librecr.app.widget.drawWidgetChart
import re.abbot.librecr.app.widget.glucoseWidgetUi
import re.abbot.librecr.app.widget.supportingText
import re.abbot.librecr.app.widget.tone
import re.abbot.librecr.app.widget.widgetChartGeometry
import re.abbot.librecr.protocol.TrendArrowShape
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
    /** The home-screen widget's model, so the AOD card shows exactly the widget's state and chart. */
    private var model: GlucoseWidgetModel? = null
    private var hasSession = false
    private var wearDurationMinutes: Int? = null
    private var lifecycleSnapshot: SensorStateStore.SensorLifecycleSnapshot? = null
    private var burnInIndex = 0
    private var currentPosition = AodSettings.POSITION_TOP
    private var lastWindowRefreshMs = 0L
    private lateinit var prefs: SharedPreferences

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val settings = AodSettings.load(this)
        aodView?.setAodSettings(settings)
        if (!settings.enabled) hideOverlay() else refreshVisibility()
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (aodView != null) {
                refreshChart()
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
        GlucoRitm.init(this)
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
        // The widget's model applies the shared freshness rules (stale, unavailable, warmup, sensor
        // error) and carries the unit, the target band and the three-hour chart series.
        scope.launch {
            GlucoseWidgetUpdater.models().collectLatest {
                model = it
                publishReading()
            }
        }
        scope.launch {
            GlucoRitm.store.sessionFlow.collectLatest {
                hasSession = it != null
                wearDurationMinutes = it?.wearMinutes
                publishReading()
            }
        }
        scope.launch {
            GlucoRitm.store.sensorLifecycleFlow.collectLatest {
                lifecycleSnapshot = it
                publishReading()
            }
        }
    }

    private fun publishReading() {
        val view = aodView ?: return
        view.setSensorLife(currentSensorLife)
        model?.let { view.setUi(glucoseWidgetUi(it, System.currentTimeMillis())) }
    }

    /** SQLite history is loaded once a minute while the card is visible; the ring alone is ~1 h. */
    private fun refreshChart() {
        scope.launch {
            GlucoseWidgetUpdater.ensureChartHistory()
            publishReading()
        }
    }

    /** Same source as the Home sensor card: the stored lifecycle, else the newest reading's lifeCount. */
    private val currentSensorLife: SensorLife?
        get() {
            if (!hasSession) return null
            val snapshot = lifecycleSnapshot
                ?: model?.input?.reading?.let {
                    SensorStateStore.SensorLifecycleSnapshot(it.lifeCount, it.receivedAtMs)
                }
                ?: return null
            val wear = wearDurationMinutes?.takeIf { it > 0 } ?: DEFAULT_WEAR_MINUTES
            return SensorLife(snapshot, wear)
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
            val view = AodGlucoseView(this, bold, regular).apply { setAodSettings(settings) }
            // The card is sized by its content: keep it aligned and on screen when that changes.
            view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    handler.post { updateLayoutPosition() }
                }
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
            publishReading()
            refreshChart()
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
        // Keep the base position clear of the screen edges by the largest burn-in shift, so the
        // shift is never clamped away at an edge (Start/End alignment, the top band, a full-width box).
        val shift = BURN_IN_SHIFT_DP.dpToPx(this)
        lp.x = (withinShiftRoom(maxX * xBias, maxX.toFloat(), shift) + frame.jitterX.dpToPx(this))
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
        lp.y = (withinShiftRoom(bandStart + third * yBias, maxY.toFloat(), shift) + frame.jitterY.dpToPx(this))
            .roundToInt()
            .coerceIn(0, maxY)
        view.setBurnInFrame(frame)
        runCatching { windowManager?.updateViewLayout(view, lp) }
    }

    private fun withinShiftRoom(base: Float, max: Float, shift: Float): Float =
        if (max >= shift * 2f) base.coerceIn(shift, max - shift) else max / 2f

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
        /** Libre 3 wear length, used only when the session lacks the patch-reported duration. */
        private const val DEFAULT_WEAR_MINUTES = 14 * 24 * 60
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

/** The largest burn-in shift in [AodGlucoseOverlayService]'s frames, on either axis. */
private const val BURN_IN_SHIFT_DP = 2f

private data class BurnInFrame(
    val jitterX: Float,
    val jitterY: Float,
    val alpha: Float,
)

private data class SensorLife(
    val snapshot: SensorStateStore.SensorLifecycleSnapshot,
    val wearMinutes: Int,
)

/** Positions computed in [AodGlucoseView.onMeasure] and drawn as-is; empty rects are hidden parts. */
private class AodCardLayout {
    /** Shrink factor a fixed box applied to the reading and its labels; 1 in automatic size. */
    var scale = 1f
    var value = ""
    val valueAt = PointF()
    val badge = RectF()
    var support: String? = null
    val supportAt = PointF()
    val warmupBar = RectF()
    var chip: ChipStyle? = null
    var chipLabel = ""
    val chipRect = RectF()
    var age: String? = null
    val ageAt = PointF()
    val ageIcon = RectF()
    val tile = RectF()
    /** String resource of the chart window label, null when the card is too narrow for one. */
    var caption: Int? = null
    val captionAt = PointF()
    val plot = RectF()
    val lifeRow = RectF()
}

/**
 * The home-screen widget's Material 3 card for the Always-On Display: the same range colors, trend
 * shapes, chips and three-hour sparkline, on a true-black card (OLED pixels off) unless the widget
 * surface is chosen. It is sized by its content and every AOD scale setting, so it can be kept
 * clear of the clock and notification icons.
 */
private class AodGlucoseView(
    context: Context,
    private val boldTypeface: Typeface,
    private val regularTypeface: Typeface,
) : View(context) {
    /** AOD is always dark: every color is the widget's night variant, whatever the phone theme. */
    private val night: Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        },
    )
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val supportPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lifeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelTypeface = WidgetFonts.labelTypeface(boldTypeface)
    private val bodyTypeface = WidgetFonts.bodyTypeface(regularTypeface)
    private val drawables = HashMap<Int, Drawable>()
    private val lifeRect = RectF()
    private val lifeClip = Path()
    private val card = AodCardLayout()
    private var settings = AodSettings.load(context)
    private var valueTypeface = typefaceFor(settings)
    private var ui: GlucoseWidgetUi? = null
    private var sensorLife: SensorLife? = null

    fun setAodSettings(settings: AodSettings) {
        this.settings = settings
        valueTypeface = typefaceFor(settings)
        requestLayout()
        invalidate()
    }

    /** Called with a fresh clock at least once a minute, so the age label and freshness follow. */
    fun setUi(ui: GlucoseWidgetUi) {
        this.ui = ui
        requestLayout()
        invalidate()
    }

    fun setSensorLife(life: SensorLife?) {
        if (life == sensorLife) return
        val visibilityChanged = (life == null) != (sensorLife == null)
        sensorLife = life
        if (visibilityChanged) requestLayout()
        invalidate()
    }

    fun setBurnInFrame(frame: BurnInFrame) {
        alpha = frame.alpha.coerceIn(0.94f, 0.98f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screen = resources.displayMetrics
        val pad = PAD.px
        val chart = settings.showChart
        if (settings.fixedSize) {
            // Even at 100% the box leaves the burn-in shift its room on both sides.
            val widest = screen.widthPixels - BURN_IN_SHIFT_DP.px * 2
            measureFixed(
                (screen.widthPixels * settings.widthFraction).coerceAtMost(widest),
                screen.heightPixels * settings.heightFraction,
                pad,
                chart,
            )
        } else {
            measureAuto(screen.widthPixels * MAX_WIDTH_FRACTION, pad, chart)
        }
    }

    /** The content's own size: the reading at its scales, the chart at its own scale beside or below it. */
    private fun measureAuto(maxWidth: Float, pad: Float, chart: Boolean) {
        val maxContent = maxWidth - pad * 2
        val hero = measureHero(1f, ageInChipRow = !chart, maxContent)
        val life = lifeHeight(1f)
        var tileWidth = 0f
        var tileHeight = 0f
        var mode = ChartMode.NONE
        if (chart) {
            val naturalHeight = tileChromeHeight(1f) + PLOT_HEIGHT.px * settings.chartScale
            val sideWidth = CHART_WIDTH.px * settings.chartScale
            if (hero.width + COLUMN_GAP.px + sideWidth <= maxContent) {
                mode = ChartMode.SIDE
                tileWidth = sideWidth
                tileHeight = max(naturalHeight, hero.height)
            } else {
                mode = ChartMode.STACKED
                tileWidth = max(hero.width, sideWidth).coerceAtMost(maxContent)
                tileHeight = naturalHeight
            }
        }
        val rowsWidth = when (mode) {
            ChartMode.NONE -> hero.width
            ChartMode.SIDE -> hero.width + COLUMN_GAP.px + tileWidth
            ChartMode.STACKED -> max(hero.width, tileWidth)
        }
        val rowsHeight = when (mode) {
            ChartMode.NONE -> hero.height
            ChartMode.SIDE -> max(hero.height, tileHeight)
            ChartMode.STACKED -> hero.height + ROW_GAP.px + tileHeight
        }
        place(hero, 1f, mode, pad, columnWidth = hero.width, rowsWidth, rowsHeight, life)
    }

    /**
     * A user-chosen box: the reading keeps its scales unless it does not fit (then everything shrinks
     * together), and the chart takes whatever is left — beside or below, whichever is larger — or
     * steps aside when there is no useful room for it.
     */
    private fun measureFixed(boxWidth: Float, boxHeight: Float, pad: Float, chart: Boolean) {
        val contentWidth = (boxWidth - pad * 2).coerceAtLeast(1f)
        val contentHeight = (boxHeight - pad * 2).coerceAtLeast(1f)
        var (hero, scale) = fitHero(ageInChipRow = !chart, contentWidth, contentHeight)
        var rows = contentHeight - lifeHeight(scale)
        val mode = if (chart) chartMode(hero, contentWidth, rows) else ChartMode.NONE
        if (chart && mode == ChartMode.NONE) {
            // No chart after all: the age moves from the chart caption into the chip row.
            val refit = fitHero(ageInChipRow = true, contentWidth, contentHeight)
            hero = refit.first
            scale = refit.second
            rows = contentHeight - lifeHeight(scale)
        }
        val columnWidth = if (mode == ChartMode.SIDE) hero.width else contentWidth
        place(hero, scale, mode, pad, columnWidth, contentWidth, rows, lifeHeight(scale))
    }

    private enum class ChartMode { NONE, SIDE, STACKED }

    private fun chartMode(hero: Hero, contentWidth: Float, rows: Float): ChartMode {
        val sideWidth = contentWidth - hero.width - COLUMN_GAP.px
        val stackedHeight = rows - hero.height - ROW_GAP.px
        val side = sideWidth >= MIN_CHART_WIDTH.px && rows >= MIN_CHART_HEIGHT.px
        val stacked = stackedHeight >= MIN_CHART_HEIGHT.px
        return when {
            side && stacked -> if (sideWidth * rows >= contentWidth * stackedHeight) ChartMode.SIDE else ChartMode.STACKED
            side -> ChartMode.SIDE
            stacked -> ChartMode.STACKED
            else -> ChartMode.NONE
        }
    }

    /** Measures the reading at full scale, then once more shrunk to the box if it overflows. */
    private fun fitHero(ageInChipRow: Boolean, width: Float, height: Float): Pair<Hero, Float> {
        val natural = measureHero(1f, ageInChipRow, width)
        val scale = minOf(
            1f,
            width / natural.naturalWidth.coerceAtLeast(1f),
            (height - lifeHeight(1f)) / natural.height.coerceAtLeast(1f),
        ).coerceIn(MIN_SCALE, 1f)
        return if (scale < 1f) measureHero(scale, ageInChipRow, width) to scale else natural to 1f
    }

    /** The reading column's parts at [scale]; also fills the card's texts. Sets the paints' sizes. */
    private fun measureHero(scale: Float, ageInChipRow: Boolean, maxWidth: Float): Hero {
        configurePaints(scale)
        val ui = ui
        val c = card
        val meta = settings.metaScale.coerceIn(0.75f, 2f) * scale
        c.value = ui?.value ?: "—"
        val badge = if (settings.showArrow) {
            BADGE.px * settings.textScale.coerceIn(0.6f, 2.5f) * settings.arrowScale * scale
        } else {
            0f
        }
        val badgeGap = if (badge > 0f) VALUE_BADGE_GAP.px * scale else 0f
        var valueWidth = valuePaint.measureText(c.value)
        val valueRoom = maxWidth - badge - badgeGap
        if (valueWidth > valueRoom && valueWidth > 0f) {
            valuePaint.textSize *= (valueRoom / valueWidth).coerceAtLeast(0.1f)
            valueWidth = valuePaint.measureText(c.value)
        }
        c.support = ui?.let { supportingText(context, it, showUnit = settings.showSecondary) }
        c.chip = ui?.let(::chipStyle)
        c.chipLabel = c.chip?.let { context.getString(it.label) }.orEmpty()
        c.age = ui?.measuredAtMs?.let { ageText(it) }
        val chipHeight = chipPaint.lineHeight() + CHIP_VERTICAL.px * meta * 2f
        val chipWidth = c.chip?.let { style ->
            chipPaint.measureText(c.chipLabel) + CHIP_HORIZONTAL.px * meta * 2f +
                (if (style.dot != null) (DOT.px + DOT_GAP.px) * meta else 0f)
        } ?: 0f
        val ageInRow = ageInChipRow && c.age != null
        val ageWidth = c.age?.let { AGE_ICON.px * meta + AGE_ICON_GAP.px + captionPaint.measureText(it) } ?: 0f
        val chipRowWidth = chipWidth + if (ageInRow) CHIP_AGE_GAP.px + ageWidth else 0f
        val chipRowHeight = if (c.chip != null || ageInRow) max(chipHeight, captionPaint.lineHeight()) else 0f
        val valueRowHeight = max(valuePaint.lineHeight(), badge)
        val supportHeight = c.support?.let { SUPPORT_GAP.px * scale + supportPaint.lineHeight() } ?: 0f
        val warmupHeight = if (ui?.warmupProgress != null) (WARMUP_GAP.px + WARMUP_BAR.px) * scale else 0f
        val naturalWidth = maxOf(
            valueWidth + badgeGap + badge,
            c.support?.let { supportPaint.measureText(it) } ?: 0f,
            chipRowWidth,
        )
        return Hero(
            width = naturalWidth.coerceAtMost(maxWidth),
            naturalWidth = naturalWidth,
            height = valueRowHeight + supportHeight + warmupHeight +
                (if (chipRowHeight > 0f) CHIP_GAP.px * scale + chipRowHeight else 0f),
            valueRowHeight = valueRowHeight,
            badge = badge,
            chipWidth = chipWidth,
            chipHeight = chipHeight,
            chipRowHeight = chipRowHeight,
            ageInChipRow = ageInRow,
        )
    }

    /** Sizes of the reading column, see [measureHero]. */
    private class Hero(
        val width: Float,
        val naturalWidth: Float,
        val height: Float,
        val valueRowHeight: Float,
        val badge: Float,
        val chipWidth: Float,
        val chipHeight: Float,
        val chipRowHeight: Float,
        val ageInChipRow: Boolean,
    )

    private fun tileChromeHeight(scale: Float): Float =
        (TILE_PAD.px * 2 + CAPTION_GAP.px) * scale + captionHeight(scale)

    private fun captionHeight(scale: Float): Float =
        max(captionPaint.lineHeight(), AGE_ICON.px * settings.metaScale.coerceIn(0.75f, 2f) * scale)

    private fun lifeHeight(scale: Float): Float =
        if (showsSensorLife()) LIFE_GAP.px * scale + lifeTextPaint.lineHeight() else 0f

    /**
     * Lays the card out in a [rowsWidth] × [rowsHeight] area below [pad]: the reading column
     * ([columnWidth] wide, its chip on the column's bottom edge), the chart tile per [mode], then
     * the sensor-life row under everything.
     */
    private fun place(
        hero: Hero,
        scale: Float,
        mode: ChartMode,
        pad: Float,
        columnWidth: Float,
        rowsWidth: Float,
        rowsHeight: Float,
        life: Float,
    ) {
        val c = card
        c.scale = scale
        val meta = settings.metaScale.coerceIn(0.75f, 2f) * scale
        val rowCenter = pad + hero.valueRowHeight / 2f
        c.valueAt.set(pad, valuePaint.baselineFor(rowCenter))
        c.badge.setEmpty()
        if (hero.badge > 0f) {
            val badgeLeft = pad + columnWidth - hero.badge
            val badgeTop = rowCenter - hero.badge / 2f + settings.arrowVerticalOffsetDp.px * scale
            c.badge.set(badgeLeft, badgeTop, badgeLeft + hero.badge, badgeTop + hero.badge)
        }
        var y = pad + hero.valueRowHeight
        c.support?.let {
            y += SUPPORT_GAP.px * scale
            c.supportAt.set(pad, supportPaint.baselineFor(y + supportPaint.lineHeight() / 2f))
            y += supportPaint.lineHeight()
        }
        c.warmupBar.setEmpty()
        if (ui?.warmupProgress != null) {
            y += WARMUP_GAP.px * scale
            c.warmupBar.set(pad, y, pad + columnWidth, y + WARMUP_BAR.px * scale)
        }
        // The chip sits on the column's bottom edge, level with a chart beside it (as on the widget).
        val heroBottom = pad + if (mode == ChartMode.STACKED) hero.height else rowsHeight
        val chipCenter = heroBottom - hero.chipRowHeight / 2f
        c.chipRect.set(pad, chipCenter - hero.chipHeight / 2f, pad + hero.chipWidth, chipCenter + hero.chipHeight / 2f)
        c.tile.setEmpty()
        c.caption = null
        if (mode != ChartMode.NONE) {
            val tileLeft = if (mode == ChartMode.SIDE) pad + columnWidth + COLUMN_GAP.px else pad
            val tileTop = if (mode == ChartMode.SIDE) pad else pad + hero.height + ROW_GAP.px
            c.tile.set(tileLeft, tileTop, pad + rowsWidth, pad + rowsHeight)
            val tilePad = TILE_PAD.px * scale
            val captionHeight = captionHeight(scale)
            val captionCenter = tileTop + tilePad + captionHeight / 2f
            c.caption = chartWindowLabel(((c.tile.width() - tilePad * 2) / resources.displayMetrics.density).dp)
            c.captionAt.set(tileLeft + tilePad, captionPaint.baselineFor(captionCenter))
            placeAge(c.tile.right - tilePad, captionCenter, meta)
            c.plot.set(
                tileLeft + tilePad,
                tileTop + tilePad + captionHeight + CAPTION_GAP.px * scale,
                c.tile.right - tilePad,
                c.tile.bottom - tilePad,
            )
        } else if (hero.ageInChipRow) {
            placeAge(pad + columnWidth, chipCenter, meta)
        }
        c.lifeRow.setEmpty()
        if (life > 0f) {
            val top = pad + rowsHeight + (life - lifeTextPaint.lineHeight())
            c.lifeRow.set(pad, top, pad + rowsWidth, top + lifeTextPaint.lineHeight())
        }
        setMeasuredDimension((pad * 2 + rowsWidth).roundToInt(), (pad * 2 + rowsHeight + life).roundToInt())
    }

    /** Right-aligns the clock icon and age label at [right], centered on [centerY]. */
    private fun placeAge(right: Float, centerY: Float, meta: Float) {
        val age = card.age ?: return
        val textLeft = right - captionPaint.measureText(age)
        card.ageAt.set(textLeft, captionPaint.baselineFor(centerY))
        val icon = AGE_ICON.px * meta
        val iconLeft = textLeft - AGE_ICON_GAP.px - icon
        card.ageIcon.set(iconLeft, centerY - icon / 2f, iconLeft + icon, centerY + icon / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val ui = ui
        val c = card
        val muted = color(WidgetColors.onSurfaceVariant)

        val background = if (settings.widgetSurface) color(WidgetColors.surface) else settings.backgroundColor
        fillPaint.color = withAlpha(background, Color.alpha(background) / 255f * settings.opacity)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), CARD_RADIUS.px, CARD_RADIUS.px, fillPaint)

        valuePaint.color = ui?.range?.let { color(it.tone.accent) } ?: muted
        canvas.drawText(c.value, c.valueAt.x, c.valueAt.y, valuePaint)
        if (ui != null && !c.badge.isEmpty) drawBadge(canvas, ui, c.badge)
        c.support?.let {
            supportPaint.color = muted
            canvas.drawText(it, c.supportAt.x, c.supportAt.y, supportPaint)
        }
        if (!c.warmupBar.isEmpty) drawWarmup(canvas, c.warmupBar, ui?.warmupProgress ?: 0f)
        c.chip?.let { drawChip(canvas, it) }
        if (!c.tile.isEmpty) {
            fillPaint.color = color(WidgetColors.tile)
            canvas.drawRoundRect(c.tile, TILE_RADIUS.px * c.scale, TILE_RADIUS.px * c.scale, fillPaint)
            captionPaint.color = muted
            c.caption?.let { canvas.drawText(context.getString(it), c.captionAt.x, c.captionAt.y, captionPaint) }
            drawChart(canvas, ui, c.plot, muted)
        }
        c.age?.let {
            drawTinted(canvas, R.drawable.glucose_widget_ic_schedule, c.ageIcon, muted)
            captionPaint.color = muted
            canvas.drawText(it, c.ageAt.x, c.ageAt.y, captionPaint)
        }
        if (!c.lifeRow.isEmpty) drawSensorLife(canvas, c.lifeRow, muted)
    }

    private fun drawBadge(canvas: Canvas, ui: GlucoseWidgetUi, bounds: RectF) {
        val style = badgeStyle(ui)
        drawTinted(canvas, style.shape, bounds, color(style.container))
        val content = color(style.content)
        val icon = style.icon
        if (icon == null) {
            drawArrow(canvas, ui.trend, bounds, content)
        } else {
            val size = bounds.width() * 0.46f
            val iconBounds = RectF(bounds.centerX() - size / 2f, bounds.centerY() - size / 2f,
                bounds.centerX() + size / 2f, bounds.centerY() + size / 2f)
            drawTinted(canvas, icon, iconBounds, content)
        }
    }

    /** The shared [TrendArrowShape] glyph at 60 % of the badge, as on the widget. */
    private fun drawArrow(canvas: Canvas, trend: String?, badge: RectF, color: Int) {
        val rotation = TrendArrowShape.rotationDegrees(trend) ?: return
        val size = badge.width() * 0.6f
        val left = badge.centerX() - size / 2f
        val top = badge.centerY() - size / 2f
        arrowPaint.color = color
        arrowPaint.strokeWidth = size * TrendArrowShape.STROKE_FRACTION
        canvas.save()
        canvas.scale(TrendArrowShape.SCALE, TrendArrowShape.SCALE, badge.centerX(), badge.centerY())
        canvas.rotate(rotation, badge.centerX(), badge.centerY())
        TrendArrowShape.SEGMENTS.forEach { s ->
            canvas.drawLine(left + s.x0 * size, top + s.y0 * size, left + s.x1 * size, top + s.y1 * size, arrowPaint)
        }
        canvas.restore()
    }

    private fun drawChip(canvas: Canvas, style: ChipStyle) {
        val rect = card.chipRect
        val meta = settings.metaScale.coerceIn(0.75f, 2f) * card.scale
        fillPaint.color = color(style.container)
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fillPaint)
        var x = rect.left + CHIP_HORIZONTAL.px * meta
        style.dot?.let {
            val dot = DOT.px * meta
            fillPaint.color = color(it)
            canvas.drawCircle(x + dot / 2f, rect.centerY(), dot / 2f, fillPaint)
            x += dot + DOT_GAP.px * meta
        }
        chipPaint.color = color(style.content)
        canvas.drawText(card.chipLabel, x, chipPaint.baselineFor(rect.centerY()), chipPaint)
    }

    private fun drawWarmup(canvas: Canvas, bar: RectF, progress: Float) {
        val radius = bar.height() / 2f
        fillPaint.color = color(WidgetColors.tertiaryContainer)
        canvas.drawRoundRect(bar, radius, radius, fillPaint)
        fillPaint.color = color(WidgetColors.tertiary)
        val fillRight = bar.left + max(bar.height(), bar.width() * progress.coerceIn(0f, 1f))
        canvas.drawRoundRect(bar.left, bar.top, fillRight, bar.bottom, radius, radius, fillPaint)
    }

    private fun drawChart(canvas: Canvas, ui: GlucoseWidgetUi?, plot: RectF, muted: Int) {
        val geometry = ui?.let {
            widgetChartGeometry(it.chart.points, it.chart.startMs, it.chart.endMs, it.targetLow, it.targetHigh)
        }
        if (ui == null || geometry == null) {
            captionPaint.color = muted
            val empty = context.getString(R.string.widget_chart_empty)
            canvas.drawText(empty, plot.centerX() - captionPaint.measureText(empty) / 2f,
                captionPaint.baselineFor(plot.centerY()), captionPaint)
            return
        }
        val below = color(WidgetGlucoseRange.LOW.tone.accent)
        val inRange = color(WidgetGlucoseRange.IN_RANGE.tone.accent)
        val above = color(WidgetGlucoseRange.HIGH.tone.accent)
        drawWidgetChart(canvas, plot, geometry, ui.targetLow, ui.targetHigh, resources.displayMetrics.density) { band ->
            when (band) {
                WidgetChartBand.BELOW -> below
                WidgetChartBand.IN_RANGE -> inRange
                WidgetChartBand.ABOVE -> above
            }
        }
    }

    private fun showsSensorLife(): Boolean = settings.showSensorLife && sensorLife != null

    /**
     * Wear-life bar: elapsed fill over a dim track whose final 24 h is tinted red, so the change
     * window is visible days ahead; once inside it the fill and the remaining-time label turn red.
     */
    private fun drawSensorLife(canvas: Canvas, row: RectF, muted: Int) {
        val life = sensorLife ?: return
        val total = life.wearMinutes.toFloat()
        val elapsed = life.snapshot.elapsedMinutesAt(System.currentTimeMillis())
        val remaining = (life.wearMinutes - elapsed).coerceAtLeast(0)
        val finalDay = remaining <= FINAL_DAY_MINUTES
        val label = if (remaining == 0) context.getString(R.string.sensor_expired) else formatLifeDuration(remaining)

        lifeTextPaint.color = if (finalDay) LIFE_RED else muted
        val labelWidth = lifeTextPaint.measureText(label)
        canvas.drawText(label, row.right - labelWidth, lifeTextPaint.baselineFor(row.centerY()), lifeTextPaint)

        val barHeight = LIFE_BAR.px * card.scale
        lifeRect.set(row.left, row.centerY() - barHeight / 2f, row.right - labelWidth - 12f.dpToPx(context),
            row.centerY() + barHeight / 2f)
        if (lifeRect.width() <= barHeight) return
        fun xAt(minutes: Float) = lifeRect.left + (minutes / total).coerceIn(0f, 1f) * lifeRect.width()
        val redStartX = xAt(total - FINAL_DAY_MINUTES)
        val elapsedX = xAt(elapsed.toFloat())

        canvas.save()
        lifeClip.reset()
        lifeClip.addRoundRect(lifeRect, barHeight / 2f, barHeight / 2f, Path.Direction.CW)
        canvas.clipPath(lifeClip)
        fillPaint.color = withAlpha(muted, 0.22f)
        canvas.drawRect(lifeRect.left, lifeRect.top, redStartX, lifeRect.bottom, fillPaint)
        fillPaint.color = withAlpha(LIFE_RED, 0.38f)
        canvas.drawRect(redStartX, lifeRect.top, lifeRect.right, lifeRect.bottom, fillPaint)
        fillPaint.color = withAlpha(muted, 0.8f)
        canvas.drawRect(lifeRect.left, lifeRect.top, minOf(elapsedX, redStartX), lifeRect.bottom, fillPaint)
        if (elapsedX > redStartX) {
            fillPaint.color = LIFE_RED
            canvas.drawRect(redStartX, lifeRect.top, elapsedX, lifeRect.bottom, fillPaint)
        }
        canvas.restore()
    }

    private fun formatLifeDuration(minutes: Int): String {
        val days = minutes / (24 * 60)
        val hours = (minutes % (24 * 60)) / 60
        val mins = minutes % 60
        return when {
            days > 0 && hours > 0 -> context.getString(R.string.duration_days_hours, days, hours)
            days > 0 -> context.getString(R.string.duration_days, days)
            hours > 0 && mins > 0 -> context.getString(R.string.duration_hours_minutes, hours, mins)
            hours > 0 -> context.getString(R.string.duration_hours, hours)
            else -> context.getString(R.string.duration_minutes, mins)
        }
    }

    /** Minute resolution: the card redraws once a minute, so seconds would only ever be stale. */
    private fun ageText(measuredAtMs: Long): String {
        val minutes = ((System.currentTimeMillis() - measuredAtMs).coerceAtLeast(0L) / 60_000L).toInt()
        return when {
            minutes < 1 -> context.getString(R.string.age_now)
            minutes < 60 -> context.getString(R.string.age_minutes, minutes)
            else -> context.getString(R.string.age_hours, minutes / 60)
        }
    }

    private fun configurePaints(scale: Float) {
        val meta = settings.metaScale * scale
        valuePaint.typeface = valueTypeface
        valuePaint.textSize = VALUE_SP.spToPx(context) * settings.textScale * scale
        supportPaint.typeface = bodyTypeface
        supportPaint.textSize = SUPPORT_SP.spToPx(context) * meta
        chipPaint.typeface = labelTypeface
        chipPaint.textSize = CHIP_SP.spToPx(context) * meta
        captionPaint.typeface = labelTypeface
        captionPaint.textSize = CAPTION_SP.spToPx(context) * meta
        lifeTextPaint.typeface = labelTypeface
        lifeTextPaint.textSize = LIFE_SP.spToPx(context) * meta
    }

    /** The Font setting: rounded Google Sans Flex weights when the system has them, else the bundled font. */
    private fun typefaceFor(settings: AodSettings): Typeface = when (settings.fontStyle) {
        FONT_THIN -> WidgetFonts.lightDisplayTypeface(regularTypeface)
        FONT_REGULAR -> WidgetFonts.displayTypeface(Typeface.NORMAL, regularTypeface)
        else -> WidgetFonts.displayTypeface(Typeface.BOLD, boldTypeface)
    }

    private fun drawTinted(canvas: Canvas, @DrawableRes id: Int, bounds: RectF, color: Int) {
        val drawable = drawables.getOrPut(id) { requireNotNull(ContextCompat.getDrawable(context, id)).mutate() }
        drawable.setBounds(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
        drawable.setTint(color)
        drawable.draw(canvas)
    }

    private fun color(provider: ColorProvider): Int = provider.getColor(night).toArgb()

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private val Float.px: Float get() = dpToPx(context)

    private fun Paint.lineHeight(): Float = fontMetrics.let { it.descent - it.ascent }

    private fun Paint.baselineFor(centerY: Float): Float = centerY - (ascent() + descent()) / 2f

    private companion object {
        const val MAX_WIDTH_FRACTION = 0.94f
        const val PAD = 18f
        const val CARD_RADIUS = 28f
        const val VALUE_SP = 46f
        const val BADGE = 46f
        const val VALUE_BADGE_GAP = 14f
        const val SUPPORT_SP = 15f
        const val SUPPORT_GAP = 2f
        const val WARMUP_GAP = 8f
        const val WARMUP_BAR = 6f
        const val CHIP_SP = 14f
        const val CHIP_GAP = 12f
        const val CHIP_VERTICAL = 6f
        const val CHIP_HORIZONTAL = 12f
        const val DOT = 8f
        const val DOT_GAP = 6f
        const val CHIP_AGE_GAP = 12f
        const val CAPTION_SP = 13f
        const val AGE_ICON = 14f
        const val AGE_ICON_GAP = 4f
        const val COLUMN_GAP = 16f
        const val ROW_GAP = 14f
        const val TILE_PAD = 12f
        const val TILE_RADIUS = 20f
        const val CAPTION_GAP = 6f
        const val CHART_WIDTH = 210f
        const val PLOT_HEIGHT = 84f
        /** Below these a fixed box drops the chart instead of drawing an unreadable one. */
        const val MIN_CHART_WIDTH = 110f
        const val MIN_CHART_HEIGHT = 72f
        /** A fixed box never shrinks the reading below this fraction of its set size. */
        const val MIN_SCALE = 0.3f
        const val LIFE_SP = 14f
        const val LIFE_GAP = 14f
        const val LIFE_BAR = 6f
        const val FINAL_DAY_MINUTES = 24 * 60
        const val LIFE_RED = 0xFFFF453A.toInt()
    }
}
