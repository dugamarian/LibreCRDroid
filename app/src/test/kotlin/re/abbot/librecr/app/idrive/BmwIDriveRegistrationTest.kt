package re.abbot.librecr.app.idrive

import de.bmw.idrive.BMWRemoting
import de.bmw.idrive.BMWRemotingServer
import org.junit.Assert.*
import android.graphics.BitmapFactory
import re.abbot.librecr.app.data.BmwIDriveDisplayMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BmwIDriveRegistrationTest {
    private data class Call(val method: String, val args: List<Any?>)
    private val calls = mutableListOf<Call>()
    private val stages = mutableListOf<String>()

    private fun remote(failInitialize: Boolean = false, width: Int = 800): BMWRemotingServer = Proxy.newProxyInstance(
        BMWRemotingServer::class.java.classLoader, arrayOf(BMWRemotingServer::class.java),
    ) { _, method, args ->
        calls += Call(method.name, args?.toList().orEmpty())
        when (method.name) {
            "sas_certificate" -> byteArrayOf(1, 2)
            "rhmi_getCapabilities" -> mapOf("hmi.display-width" to width.toString(), "hmi.display-height" to "480")
            "rhmi_create" -> 7
            "am_create" -> 11
            "rhmi_checkResource" -> false
            "rhmi_initialize" -> {
                if (failInitialize) throw IllegalStateException("layout rejected")
                null
            }
            "toString" -> "Test head unit"
            "hashCode" -> 1
            "equals" -> false
            else -> null
        }
    } as BMWRemotingServer

    private fun app() = BmwIDriveCarApp(
        IDriveGlucoseRenderer(RuntimeEnvironment.getApplication()), "GlucoRitm · Glucose",
        dispatch = { it() }, onStage = { stages += it },
    )

    private val layout = """
        <pluginApps><pluginApp>
          <models><raDataModel id="1"/><raImageModel id="2"/><raListModel id="3"/><raIntModel id="4"/></models>
          <actions><raAction id="5"/></actions>
          <hmiStates><hmiState id="10" textModel="1"><components>
            <image id="11" model="2"/><list id="12" model="3" action="5">
              <properties><property id="6" value="57,100,*"/></properties>
            </list>
          </components></hmiState></hmiStates>
          <events><focusEvent id="20" targetModel="4"/></events>
        </pluginApp></pluginApps>
    """.trimIndent().toByteArray()

    @Test fun `application split events resize the JPEG even without a state component id`() {
        val app = app()
        app.initialize(remote(width = 1280), byteArrayOf(), layout, byteArrayOf()) { byteArrayOf() }
        val content = IDriveGlucosePresentation(
            re.abbot.librecr.app.widget.WidgetReadingStatus.CURRENT,
            "123", "mg/dL", "STABLE", "+1", 0L, 1_000L,
        )
        fun lastImageWidth(): Int {
            val resource = calls.last { it.method == "rhmi_setData" && it.args[1] == 2 }.args[2] as BMWRemoting.RHMIResourceData
            val data = resource.data
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            return bitmap.width.also { bitmap.recycle() }
        }
        app.update(content)
        app.open()
        assertEquals(743, lastImageWidth())
        app.listener.rhmi_onHmiEvent(7, BmwIDriveCarApp.APP_ID, 0, 7, mutableMapOf<Any, Any>(7.toByte() to false))
        assertEquals(1211, lastImageWidth())
        app.listener.rhmi_onHmiEvent(7, BmwIDriveCarApp.APP_ID, -1, 7, mutableMapOf<Any, Any>(7.toByte() to true))
        assertEquals(743, lastImageWidth())
        app.update(content, BmwIDriveDisplayMode.FULL_SCREEN)
        assertEquals(1211, lastImageWidth())
        assertEquals(1, calls.count { it.method == "rhmi_triggerEvent" })
        app.close()
    }

    @Test fun `standalone menu identity survives RHMI replacement and selecting it clears loading state`() {
        val app = app()
        app.initialize(remote(), byteArrayOf(), layout, byteArrayOf()) { byteArrayOf(3, 4) }
        val metadata = calls.single { it.method == "rhmi_create" }.args[1] as BMWRemoting.RHMIMetaData
        val menu = calls.single { it.method == "am_registerApp" }
        assertNotEquals(metadata.id, menu.args[1])
        assertEquals(BmwIDriveCarApp.MENU_APP_ID, menu.args[1])
        assertEquals("GlucoRitm · Glucose", (menu.args[2] as Map<*, *>)[1])
        assertEquals("REGISTERED", stages.last())

        app.listener.am_onAppEvent(11, BmwIDriveCarApp.APP_ID, BmwIDriveCarApp.MENU_APP_ID, null)
        val focus = calls.single { it.method == "rhmi_triggerEvent" }
        assertEquals(10, (focus.args[2] as Map<*, *>)[0.toByte()])
        assertEquals(2, calls.count { it.method == "am_registerApp" })
        assertEquals(1, calls.count { it.method == "rhmi_create" })
        app.close()
        assertEquals(11, calls.single { it.method == "am_dispose" }.args[0])
        assertEquals(7, calls.single { it.method == "rhmi_dispose" }.args[0])
    }

    @Test fun `automatic opening renders prepared content and later updates do not steal focus`() {
        val app = app()
        app.initialize(remote(), byteArrayOf(), layout, byteArrayOf()) { byteArrayOf() }
        val content = IDriveGlucosePresentation(
            re.abbot.librecr.app.widget.WidgetReadingStatus.CURRENT,
            "123", "mg/dL", "→", "+1", 0L, 1_000L,
        )
        app.update(content)
        assertEquals(0, calls.count { it.method == "rhmi_triggerEvent" })
        app.open()
        val focus = calls.single { it.method == "rhmi_triggerEvent" }
        assertEquals(10, (focus.args[2] as Map<*, *>)[0.toByte()])
        assertTrue(calls.any { it.method == "rhmi_setData" && it.args[1] == 2 })

        // The driver leaves the glucose page. New readings must leave that choice intact.
        app.listener.rhmi_onHmiEvent(7, BmwIDriveCarApp.APP_ID, 10, 1,
            mutableMapOf<Any, Any>(4.toByte() to false))
        val imageUpdates = calls.count { it.method == "rhmi_setData" && it.args[1] == 2 }
        app.update(content.copy(value = "124"))
        assertEquals(1, calls.count { it.method == "rhmi_triggerEvent" })
        assertEquals(imageUpdates, calls.count { it.method == "rhmi_setData" && it.args[1] == 2 })
        app.close()
    }

    @Test fun `unrelated menu events cannot open the glucose page`() {
        val app = app()
        app.initialize(remote(), byteArrayOf(), layout, byteArrayOf()) { byteArrayOf() }
        app.listener.am_onAppEvent(11, BmwIDriveCarApp.APP_ID, "other.app", null)
        assertEquals(0, calls.count { it.method == "rhmi_triggerEvent" })
        assertEquals(1, calls.count { it.method == "am_registerApp" })
        app.close()
    }

    @Test fun `failed RHMI initialization does not claim a successful menu registration`() {
        val app = app()
        try {
            app.initialize(remote(failInitialize = true), byteArrayOf(), layout, byteArrayOf()) { byteArrayOf() }
            fail("Expected a layout rejection")
        } catch (expected: IllegalStateException) {
            assertEquals("RHMI_INITIALIZE", stages.last())
            assertEquals(0, calls.count { it.method == "am_registerApp" })
        } finally {
            app.close()
        }
        assertEquals(7, calls.single { it.method == "rhmi_dispose" }.args[0])
    }
}
