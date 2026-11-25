package re.abbot.librecr.app.idrive

import de.bmw.idrive.BMWRemoting
import de.bmw.idrive.BMWRemotingServer
import de.bmw.idrive.BaseBMWRemotingClient
import io.bimmergestalt.idriveconnectkit.IDriveConnection
import io.bimmergestalt.idriveconnectkit.RHMIDimensions
import io.bimmergestalt.idriveconnectkit.SidebarRHMIDimensions
import io.bimmergestalt.idriveconnectkit.RHMIUtils.rhmi_setResourceCached
import io.bimmergestalt.idriveconnectkit.android.security.SecurityAccess
import io.bimmergestalt.idriveconnectkit.rhmi.*
import io.bimmergestalt.idriveconnectkit.rhmi.deserialization.loadFromXML
import java.io.ByteArrayInputStream
import re.abbot.librecr.app.data.BmwIDriveDisplayMode

/** All calls, including remoting callbacks, are serialized on the service's transport dispatcher. */
internal class BmwIDriveCarApp(
    private val renderer: IDriveGlucoseRenderer,
    private val title: String,
    dispatch: (() -> Unit) -> Unit,
    private val onStage: (String) -> Unit = {},
) : AutoCloseable {
    private var server: BMWRemotingServer? = null
    private var rhmiHandle: Int? = null
    private var amHandle: Int? = null
    private var menuInfo: Map<Int, Any>? = null
    private var app: RHMIApplication? = null
    private var view: IDriveGlucoseView? = null
    private var content: IDriveGlucosePresentation? = null
    private var displayMode = BmwIDriveDisplayMode.AUTO

    internal val listener = object : BaseBMWRemotingClient() {
        override fun am_onAppEvent(handle: Int?, ident: String?, appId: String?, event: BMWRemoting.AMEvent?) {
            if (appId == MENU_APP_ID) dispatch {
                view?.open()
                // This launcher does not get replaced by a same-ID RHMI app. Clear its spinner.
                registerMenu()
            }
        }

        override fun rhmi_onActionEvent(handle: Int?, ident: String?, actionId: Int?, args: MutableMap<*, *>?) {
            dispatch {
                try {
                    app?.actions?.get(actionId)?.asRAAction()?.rhmiActionCallback?.onActionEvent(args)
                } finally {
                    server?.rhmi_ackActionEvent(handle, actionId, 1, true)
                }
            }
        }

        override fun rhmi_onHmiEvent(handle: Int?, ident: String?, componentId: Int?, eventId: Int?, args: MutableMap<*, *>?) {
            dispatch {
                // SPLITSCREEN is an application event; it need not target our state/image ID.
                view?.onHmiEvent(eventId, args)
                app?.states?.get(componentId)?.onHmiEvent(eventId, args)
                app?.components?.get(componentId)?.onHmiEvent(eventId, args)
            }
        }
    }

    fun connect(connection: IDriveEndpoint, security: SecurityAccess, resources: AAIdriveResources) {
        onStage("TCP")
        val remote = IDriveConnection.getEtchConnection(connection.host, connection.port, listener)
        server = remote
        initialize(remote, resources.certificate(security), resources.description, resources.images) {
            security.signChallenge(challenge = it)
        }
    }

    /** Injectable RPC boundary lets tests check the complete registration, not just an offline view. */
    internal fun initialize(
        remote: BMWRemotingServer,
        certificate: ByteArray,
        description: ByteArray,
        images: ByteArray,
        signChallenge: (ByteArray) -> ByteArray,
    ) {
        server = remote
        onStage("SAS_CERTIFICATE")
        val challenge = remote.sas_certificate(certificate)
        onStage("SAS_LOGIN")
        remote.sas_login(signChallenge(challenge))
        onStage("CAPABILITIES")
        val capabilities = remote.rhmi_getCapabilities("", 255).entries.associate { it.key.toString() to it.value.toString() }
        val dimensions = RHMIDimensions.create(capabilities)
        onStage("RHMI_CREATE")
        val handle = remote.rhmi_create(null,
            BMWRemoting.RHMIMetaData(APP_ID, BMWRemoting.VersionInfo(0, 1, 0), APP_ID, "GlucoRitm"))
        rhmiHandle = handle
        onStage("RHMI_RESOURCES")
        remote.rhmi_setResourceCached(handle, BMWRemoting.RHMIResourceType.DESCRIPTION, ByteArrayInputStream(description))
        remote.rhmi_setResourceCached(handle, BMWRemoting.RHMIResourceType.IMAGEDB, ByteArrayInputStream(images))
        onStage("RHMI_INITIALIZE")
        remote.rhmi_initialize(handle)
        val application = RHMIApplicationSynchronized(
            RHMIApplicationIdempotent(RHMIApplicationEtch(remote, handle)), remote)
        application.loadFromXML(description)
        app = application
        onStage("RHMI_LAYOUT")
        view = IDriveGlucoseView(application, dimensions, title, displayMode = { displayMode }) { width, height ->
            content?.let { renderer.render(it, width, height, fullWidth = dimensions.visibleWidth) }
        }
        onStage("RHMI_EVENTS")
        remote.rhmi_addActionEventHandler(handle, APP_ID, -1)
        remote.rhmi_addHmiEventHandler(handle, APP_ID, -1, -1)

        onStage("AM_CREATE")
        val menuHandle = remote.am_create("0", byteArrayOf(0, 0, 0, 0, 0, 2, 0, 0))
        amHandle = menuHandle
        remote.am_addAppEventHandler(menuHandle, APP_ID)
        val info = mutableMapOf<Int, Any>(
            0 to 145, 1 to title, 2 to renderer.icon(), 3 to "OnlineServices",
            4 to true, 5 to 1500, 8 to -1,
        )
        (101..123).forEach { info[it] = title }
        menuInfo = info
        onStage("AM_REGISTER")
        registerMenu()
        onStage("REGISTERED")
    }

    private fun registerMenu() {
        val handle = amHandle ?: return
        val info = menuInfo ?: return
        server?.am_registerApp(handle, MENU_APP_ID, info)
    }

    /** Called after the first presentation is ready, so automatic opening never shows an empty page. */
    fun open() {
        checkNotNull(view) { "Glucose screen is not initialized" }.open()
    }

    fun update(value: IDriveGlucosePresentation, mode: BmwIDriveDisplayMode = BmwIDriveDisplayMode.AUTO) {
        if (content == value && displayMode == mode) return
        content = value
        displayMode = mode
        view?.refresh()
    }

    override fun close() {
        val remote = server ?: return
        server = null
        // Each step is best effort: a disconnected car must not prevent closing the TCP tunnel.
        amHandle?.let { runCatching { remote.am_dispose(it) } }
        rhmiHandle?.let { runCatching { remote.rhmi_dispose(it) } }
        runCatching { IDriveConnection.disconnectEtchConnection(remote) }
        app = null
        view = null
        content = null
        menuInfo = null
    }

    companion object {
        const val APP_ID = "re.abbot.librecr.glucose"
        // Same-ID AM placeholders can be replaced by the signed layout's inherited entry.
        const val MENU_APP_ID = "$APP_ID.launcher"
    }
}

/** Uses the original signed layout, replacing its dynamic image with our glucose card. */
internal class IDriveGlucoseView(
    private val app: RHMIApplication,
    private val dimensions: RHMIDimensions,
    title: String,
    private val displayMode: () -> BmwIDriveDisplayMode = { BmwIDriveDisplayMode.AUTO },
    private val render: (Int, Int) -> ByteArray?,
) {
    val state = app.states.values.firstOrNull { state ->
        state is RHMIState.PlainState &&
            state.componentsList.filterIsInstance<RHMIComponent.Image>().any { it.getModel() is RHMIModel.RaImageModel } &&
            state.componentsList.any { it is RHMIComponent.List }
    } ?: error("AAIdrive layout has no dynamic image screen")
    private val image = state.componentsList.filterIsInstance<RHMIComponent.Image>()
        .first { it.getModel() is RHMIModel.RaImageModel }
    private val input = state.componentsList.filterIsInstance<RHMIComponent.List>().first()
    private val focus = app.events.values.filterIsInstance<RHMIEvent.FocusEvent>().first()
    private var visible = false
    private var reportedSplitScreen: Boolean? = null
    // Reserve the native sidebar until the car reports its mode. Explicit modes
    // work immediately on head units that never send SPLITSCREEN events.
    private val viewport = SidebarRHMIDimensions(dimensions) {
        when (displayMode()) {
            BmwIDriveDisplayMode.AUTO -> reportedSplitScreen == false
            BmwIDriveDisplayMode.SPLIT_SCREEN -> false
            BmwIDriveDisplayMode.FULL_SCREEN -> true
        }
    }

    init {
        state.getTextModel()?.asRaDataModel()?.value = title
        state.setProperty(RHMIProperty.PropertyId.HMISTATE_TABLETYPE, 3)
        state.setProperty(RHMIProperty.PropertyId.HMISTATE_TABLELAYOUT, "1,0,7")
        state.componentsList.forEach { it.setVisible(false) }
        image.setProperty(RHMIProperty.PropertyId.WIDTH, viewport.visibleWidth)
        image.setProperty(RHMIProperty.PropertyId.HEIGHT, viewport.visibleHeight)
        image.setProperty(RHMIProperty.PropertyId.POSITION_X, -dimensions.paddingLeft)
        image.setProperty(RHMIProperty.PropertyId.POSITION_Y, -dimensions.paddingTop)
        image.setVisible(true)
        // Keep bookmark support through an offscreen one-column list.
        input.setProperty(RHMIProperty.PropertyId.LIST_COLUMNWIDTH, "*")
        input.getModel()?.asRaListModel()?.value = RHMIModel.RaListModel.RHMIListConcrete(1).apply {
            addRow(arrayOf(title))
        }
        input.setProperty(RHMIProperty.PropertyId.POSITION_X, -50000)
        input.setProperty(RHMIProperty.PropertyId.BOOKMARKABLE, true)
        input.setVisible(true)
        input.getAction()?.asRAAction()?.rhmiActionCallback = RHMIActionCallback { open() }
        app.components.values.filterIsInstance<RHMIComponent.EntryButton>().forEach {
            // The inherited entry (if shown by this head unit) opens the glucose page too.
            it.getAction()?.asHMIAction()?.getTargetModel()?.asRaIntModel()?.value = state.id
        }
        state.focusCallback = FocusCallback { focused ->
            visible = focused
            if (focused) refresh()
        }
    }

    fun open() {
        focus.triggerEvent(mapOf(0.toByte() to state.id))
        visible = true
        refresh()
    }

    fun refresh() {
        if (visible) {
            // Resize the remote component before posting its new bitmap; resizing only the
            // JPEG leaves a full-width component which some head units stretch or crop.
            image.setProperty(RHMIProperty.PropertyId.WIDTH, viewport.visibleWidth)
            image.setProperty(RHMIProperty.PropertyId.HEIGHT, viewport.visibleHeight)
            render(viewport.visibleWidth, viewport.visibleHeight)?.let { image.getModel()?.asRaImageModel()?.value = it }
        }
    }

    fun onHmiEvent(eventId: Int?, args: Map<*, *>?) {
        if (eventId != 7) return // SPLITSCREEN / HMIEVENT_PARAM_SPLITSCREEN, both ID 7.
        val split = when (val value = args?.get(7.toByte()) ?: args?.get(7)) {
            is Boolean -> value
            is Byte, is Short, is Int, is Long -> when ((value as Number).toLong()) {
                0L -> false
                1L -> true
                else -> return
            }
            else -> return
        }
        if (reportedSplitScreen == split) return
        reportedSplitScreen = split
        if (displayMode() == BmwIDriveDisplayMode.AUTO) refresh()
    }
}
