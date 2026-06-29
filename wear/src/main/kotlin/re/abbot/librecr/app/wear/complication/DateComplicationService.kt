package re.abbot.librecr.app.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeFormatComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.log.BleLog

class DateComplicationService : SuspendingComplicationDataSourceService() {
    override fun onCreate() {
        super.onCreate()
        LibreCR.init(this)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        buildData(tapAction = null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        BleLog.log("WATCH_COMPLICATION_REQUEST service=DateComplicationService type=${request.complicationType}")
        return buildData(tapAction())
    }

    private fun buildData(tapAction: PendingIntent?): ComplicationData =
        ShortTextComplicationData.Builder(
            TimeFormatComplicationText.Builder(DATE_PATTERN).build(),
            PlainComplicationText.Builder("Date").build(),
        )
            .setTapAction(tapAction)
            .build()

    private fun tapAction(): PendingIntent =
        PendingIntent.getActivity(
            this,
            43,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private companion object {
        private const val DATE_PATTERN = "dd.MM"
    }
}
