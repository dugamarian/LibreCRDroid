package re.abbot.librecr.app.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.ble.isActiveGlucoseUnavailable
import re.abbot.librecr.app.ble.toLastGlucose
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.WearAppearanceSettings
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.log.GlucoseLatencyTracer
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention

class AgeDeltaComplicationService : SuspendingComplicationDataSourceService() {
    override fun onCreate() {
        super.onCreate()
        LibreCR.init(this)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        buildData(type, previewReading(), null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        // In-memory live value first (instant); DataStore only when the service isn't running (cold).
        // A fresh unavailable live reading shows "OOR"; real sensor errors come from patch-status.
        val live = LibreCR.manager.glucose.value
        val appearance = LibreCR.appearance.current()
        val status = LibreCR.store.loadSensorStatus()
        val attention = status?.attention ?: Libre3SensorAttention.None
        val sensorError = attention != Libre3SensorAttention.None
        val liveUnavailable = live.isActiveGlucoseUnavailable()
        val reading = if (sensorError || liveUnavailable) null else live?.toLastGlucose() ?: LibreCR.store.loadLastGlucose()
        val unavailable = !sensorError && (liveUnavailable || !GlucoseComplicationRenderer.isFresh(reading))
        // The OS asked us for fresh complication data — i.e. the watch face / AOD is about
        // to repaint with this reading. Gap to STORE_UPDATED is the complication-refresh throttle.
        reading?.let { GlucoseLatencyTracer.mark(it.lifeCount, GlucoseLatencyTracer.Stage.AOD_UPDATED) }
        BleLog.log("WATCH_COMPLICATION_REQUEST lc=${reading?.lifeCount ?: -1} sensorError=$sensorError unavailable=$unavailable service=AgeDeltaComplicationService type=${request.complicationType}")
        return buildData(
            request.complicationType, reading, tapAction(), appearance, attention,
            sensorError = sensorError,
            sensorErrorAtMs = if (sensorError) status?.observedAtMs ?: live?.receivedAtMs ?: 0L else 0L,
            unavailable = unavailable,
            unavailableAtMs = if (liveUnavailable) live?.receivedAtMs ?: 0L else reading?.receivedAtMs ?: 0L,
        )
    }

    private fun buildData(
        type: ComplicationType,
        reading: SensorStateStore.LastGlucose?,
        tapAction: PendingIntent?,
        appearance: WearAppearanceSettings = WearAppearanceSettings(),
        attention: Libre3SensorAttention = Libre3SensorAttention.None,
        sensorError: Boolean = false,
        sensorErrorAtMs: Long = 0L,
        unavailable: Boolean = false,
        unavailableAtMs: Long = 0L,
    ): ComplicationData {
        val image = Icon.createWithBitmap(
            GlucoseComplicationRenderer.buildAgeDeltaBitmap(
                this, reading, appearance, attention = attention,
                sensorError = sensorError, sensorErrorAtMs = sensorErrorAtMs,
                unavailable = unavailable, unavailableAtMs = unavailableAtMs,
            ),
        )
        val description = PlainComplicationText.Builder(
            GlucoseComplicationRenderer.contentDescription("Glucose time and delta per minute", reading, attention, appearance.unit, sensorError, unavailable)
        ).build()
        return when (type) {
            ComplicationType.PHOTO_IMAGE -> PhotoImageComplicationData.Builder(image, description)
                .setTapAction(tapAction)
                .build()
            else -> SmallImageComplicationData.Builder(
                SmallImage.Builder(image, SmallImageType.PHOTO).build(),
                description,
            )
                .setTapAction(tapAction)
                .build()
        }
    }

    private fun tapAction(): PendingIntent =
        PendingIntent.getActivity(
            this,
            41,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun previewReading() = SensorStateStore.LastGlucose(
        lifeCount = 1234,
        mgDL = 101,
        trend = "STABLE",
        receivedAtMs = System.currentTimeMillis() - 2 * 60_000L,
        deltaMgDlPerMin = 1.2,
    )
}
