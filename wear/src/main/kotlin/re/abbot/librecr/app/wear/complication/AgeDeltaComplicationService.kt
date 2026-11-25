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
import re.abbot.librecr.app.GlucoseAgeAlarm
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.R
import re.abbot.librecr.app.ble.isActiveGlucoseUnavailable
import re.abbot.librecr.app.ble.toLastGlucose
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.WearAppearanceSettings
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.log.GlucoseLatencyTracer
import re.abbot.librecr.app.nextGlucoseAgeRefreshDelayMs
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention

class AgeDeltaComplicationService : TrackedComplicationDataSourceService() {
    override fun onCreate() {
        super.onCreate()
        GlucoRitm.init(this)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        buildData(type, previewReading(), null)

    override suspend fun onTrackedComplicationRequest(request: ComplicationRequest): ComplicationData {
        // In-memory live value first (instant); DataStore only when the service isn't running (cold).
        // A fresh unavailable live reading shows "--" in the delta field; patch-status errors do too.
        val live = GlucoRitm.manager.glucose.value
        val appearance = GlucoRitm.appearance.current()
        val status = GlucoRitm.manager.sensorStatus.value
            ?: if (live == null) GlucoRitm.store.loadSensorStatus() else null
        val attention = status?.attention ?: Libre3SensorAttention.None
        val sensorError = attention != Libre3SensorAttention.None
        val liveUnavailable = live.isActiveGlucoseUnavailable()
        val reading = when {
            sensorError || liveUnavailable -> null
            live != null -> live.toLastGlucose()
            else -> GlucoRitm.store.loadLastGlucose()
        }
        val unavailable = !sensorError && (liveUnavailable || !GlucoseComplicationRenderer.isFresh(reading))
        // The OS asked us for fresh complication data — i.e. the watch face / AOD is about
        // to repaint from live memory. This may legitimately happen before STORE_UPDATED.
        reading?.let { GlucoseLatencyTracer.mark(it.lifeCount, GlucoseLatencyTracer.Stage.AOD_UPDATED) }
        val returnedValue = when {
            sensorError -> "SENSOR_ERROR"
            unavailable -> "SENSOR_UNAVAILABLE"
            else -> reading?.mgDL?.toString() ?: "NONE"
        }
        BleLog.log(
            "COMPLICATION_DATA_READ lc=${reading?.lifeCount ?: live?.lifeCount ?: -1} " +
                "source=${if (live != null) "LIVE_CACHE" else "DATASTORE"} " +
                "value=$returnedValue timestampMs=${live?.receivedAtMs ?: reading?.receivedAtMs ?: 0L} " +
                "service=AgeDeltaComplicationService " +
                "instanceId=${request.complicationInstanceId} type=${request.complicationType} " +
                "immediate=${request.immediateResponseRequired}",
        )
        val nowMs = System.currentTimeMillis()
        val sensorErrorAtMs = if (sensorError) status?.observedAtMs ?: live?.receivedAtMs ?: 0L else 0L
        val unavailableAtMs = if (liveUnavailable) live?.receivedAtMs ?: 0L else reading?.receivedAtMs ?: 0L
        val shownAtMs = when {
            sensorError -> sensorErrorAtMs
            unavailable -> unavailableAtMs
            else -> reading?.receivedAtMs ?: 0L
        }
        // The image's "<1 m." changes only when we repaint it: wake for the next minute or expiry.
        GlucoseAgeAlarm.update(
            this, GlucoseAgeAlarm.Surface.AGE_COMPLICATION, nextGlucoseAgeRefreshDelayMs(shownAtMs, nowMs), nowMs,
        )
        return buildData(
            request.complicationType, reading, tapAction(), appearance, attention,
            sensorError = sensorError,
            sensorErrorAtMs = sensorErrorAtMs,
            unavailable = unavailable,
            unavailableAtMs = unavailableAtMs,
            nowMs = nowMs,
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
        nowMs: Long = System.currentTimeMillis(),
    ): ComplicationData {
        val image = Icon.createWithBitmap(
            GlucoseComplicationRenderer.buildAgeDeltaBitmap(
                this, reading, appearance, attention = attention,
                sensorError = sensorError, sensorErrorAtMs = sensorErrorAtMs,
                unavailable = unavailable, unavailableAtMs = unavailableAtMs,
                nowMs = nowMs,
            ),
        )
        val description = PlainComplicationText.Builder(
            GlucoseComplicationRenderer.contentDescription(
                this,
                getString(R.string.complication_age_delta),
                reading,
                attention,
                appearance.unit,
                sensorError,
                unavailable,
            )
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
        receivedAtMs = System.currentTimeMillis() - 60_000L,
        deltaMgDlPerMin = 1.2,
    )
}
