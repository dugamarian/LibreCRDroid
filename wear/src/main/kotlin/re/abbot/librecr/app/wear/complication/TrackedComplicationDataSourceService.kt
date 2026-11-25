package re.abbot.librecr.app.wear.complication

import android.content.ComponentName
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Base for all GlucoRitm complication data sources: keeps [ActiveComplicationRegistry] in sync so
 * update requests can target the instances the watch face actually shows.
 */
abstract class TrackedComplicationDataSourceService : SuspendingComplicationDataSourceService() {

    final override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        super.onComplicationActivated(complicationInstanceId, type)
        GlucoRitmComplicationUpdater.onConfigurationChanged()
        ActiveComplicationRegistry.register(complicationInstanceId, ComponentName(this, javaClass))
    }

    final override fun onComplicationDeactivated(complicationInstanceId: Int) {
        GlucoRitmComplicationUpdater.onConfigurationChanged()
        ActiveComplicationRegistry.unregister(complicationInstanceId)
        super.onComplicationDeactivated(complicationInstanceId)
    }

    final override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        // Activation is not re-delivered after a process restart; the host's own request is the
        // reliable re-registration point.
        val component = ComponentName(this, javaClass)
        // A host request is also the reactivation signal if an activation callback was missed.
        GlucoRitmComplicationUpdater.onConfigurationChanged()
        ActiveComplicationRegistry.register(request.complicationInstanceId, component)
        val token = GlucoRitmComplicationUpdater.onRequestStarted(component, request.complicationInstanceId)
        val data = onTrackedComplicationRequest(request)
        GlucoRitmComplicationUpdater.onDataReady(token, component, request.complicationInstanceId)
        return data
    }

    /** Same contract as [onComplicationRequest], invoked after instance registration. */
    protected abstract suspend fun onTrackedComplicationRequest(request: ComplicationRequest): ComplicationData
}
