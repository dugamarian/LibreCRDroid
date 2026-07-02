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
import re.abbot.librecr.app.ble.toLastGlucose
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.WearAppearanceSettings
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.log.GlucoseLatencyTracer
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention

class TrendAboveValueComplicationService : SuspendingComplicationDataSourceService() {
    override fun onCreate() {
        super.onCreate()
        LibreCR.init(this)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        buildData(type, previewReading(), null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        // In-memory live value first (instant); DataStore only when the service isn't running (cold).
        val reading = LibreCR.manager.glucose.value?.toLastGlucose() ?: LibreCR.store.loadLastGlucose()
        val appearance = LibreCR.appearance.current()
        val attention = LibreCR.store.loadSensorStatus()?.attention ?: Libre3SensorAttention.None
        // The OS asked us for fresh complication data — i.e. the watch face / AOD is about
        // to repaint with this reading. Gap to STORE_UPDATED is the complication-refresh throttle.
        reading?.let { GlucoseLatencyTracer.mark(it.lifeCount, GlucoseLatencyTracer.Stage.AOD_UPDATED) }
        BleLog.log("WATCH_COMPLICATION_REQUEST lc=${reading?.lifeCount ?: -1} service=TrendAboveValueComplicationService type=${request.complicationType}")
        return buildData(request.complicationType, reading, tapAction(), appearance, attention)
    }

    private fun buildData(
        type: ComplicationType,
        reading: SensorStateStore.LastGlucose?,
        tapAction: PendingIntent?,
        appearance: WearAppearanceSettings = WearAppearanceSettings(),
        attention: Libre3SensorAttention = Libre3SensorAttention.None,
    ): ComplicationData {
        val image = Icon.createWithBitmap(
            GlucoseComplicationRenderer.buildTrendValueBitmap(this, reading, appearance, attention = attention),
        )
        val description = PlainComplicationText.Builder(
            GlucoseComplicationRenderer.contentDescription("Glucose trend above value", reading, attention, appearance.unit)
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
            42,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun previewReading() = SensorStateStore.LastGlucose(
        lifeCount = 1234,
        mgDL = 101,
        trend = "RISING",
        receivedAtMs = System.currentTimeMillis() - 2 * 60_000L,
        deltaMgDlPerMin = 1.2,
    )
}
