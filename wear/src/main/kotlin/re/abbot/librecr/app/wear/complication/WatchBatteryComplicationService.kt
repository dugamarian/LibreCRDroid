package re.abbot.librecr.app.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.BatteryManager
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.R
import re.abbot.librecr.app.log.BleLog
import kotlin.math.roundToInt

class WatchBatteryComplicationService : SuspendingComplicationDataSourceService() {
    override fun onCreate() {
        super.onCreate()
        LibreCR.init(this)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        buildData(type, BatterySnapshot(percent = 78, charging = false, full = false), null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val snapshot = readBatterySnapshot()
        BleLog.log("WATCH_COMPLICATION_REQUEST battery=${snapshot.percent} charging=${snapshot.charging} full=${snapshot.full} service=WatchBatteryComplicationService type=${request.complicationType}")
        return buildData(request.complicationType, snapshot, tapAction())
    }

    private fun buildData(
        type: ComplicationType,
        snapshot: BatterySnapshot,
        tapAction: PendingIntent?,
    ): ComplicationData {
        val description = PlainComplicationText.Builder(snapshot.contentDescription).build()
        val text = PlainComplicationText.Builder(snapshot.shortText).build()
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_battery_24)).build()
        return when (type) {
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(
                    snapshot.percent.toFloat(),
                    0f,
                    100f,
                    description,
                )
                    .setValueType(RangedValueComplicationData.TYPE_PERCENTAGE)
                    .setText(text)
                    .setMonochromaticImage(icon)
                    .setTapAction(tapAction)
                    .build()

            else ->
                ShortTextComplicationData.Builder(text, description)
                    .setMonochromaticImage(icon)
                    .setTapAction(tapAction)
                    .build()
        }
    }

    private fun readBatterySnapshot(): BatterySnapshot {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val propertyPercent = getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
        val intentPercent = batteryIntent?.batteryPercent()
        val percent = (propertyPercent ?: intentPercent ?: 0).coerceIn(0, 100)
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        return BatterySnapshot(
            percent = percent,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING,
            full = status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    private fun Intent.batteryPercent(): Int? {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) {
            (level * 100f / scale).roundToInt().coerceIn(0, 100)
        } else {
            null
        }
    }

    private fun tapAction(): PendingIntent =
        PendingIntent.getActivity(
            this,
            44,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private data class BatterySnapshot(
        val percent: Int,
        val charging: Boolean,
        val full: Boolean,
    ) {
        val shortText: String = "$percent%"
        val contentDescription: String = when {
            full -> "Bateria este incarcata complet, $percent la suta"
            charging -> "Bateria se incarca, $percent la suta"
            else -> "Baterie, $percent la suta"
        }
    }
}
