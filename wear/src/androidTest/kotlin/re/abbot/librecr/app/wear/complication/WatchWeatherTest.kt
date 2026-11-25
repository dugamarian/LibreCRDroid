package re.abbot.librecr.app.wear.complication

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import re.abbot.librecr.protocol.connection.WearWeather
import re.abbot.librecr.protocol.connection.WearWeatherReading

class WatchWeatherTest {
    @Test
    fun phoneReadingReplacesLegacyCacheAndLateUpdatesCannotUndoClear() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("test_$name", mode)
        }
        val prefs = context.getSharedPreferences("complication_weather", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit().clear().putString(
            "current", """{"time":${now / 1000 - 8000},"temperature_2m":2,"weather_code":3,"is_day":1}""",
        ).commit()
        try {
            assertEquals("~2°C", WatchWeather.current(context)?.temperatureText)

            val packet = WearWeather(now, WearWeatherReading(now / 1000, 6, 0, false))
            assertTrue(WatchWeather.receive(context, packet.encode()))
            assertEquals("6°C", WatchWeather.current(context)?.temperatureText)
            assertEquals(WeatherCondition.CLEAR_NIGHT, WatchWeather.current(context)?.condition)
            assertFalse(WatchWeather.receive(context, packet.encode()))

            assertTrue(WatchWeather.receive(context, WearWeather(now + 1, null).encode()))
            assertFalse(WatchWeather.receive(context, packet.encode()))
            assertNull(WatchWeather.current(context))
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
