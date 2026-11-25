package re.abbot.librecr.app.wear.complication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DateWeatherFramesTest {
    private val zone = ZoneId.of("Europe/Brussels")
    private val midnight = Instant.parse("2026-11-11T23:00:00Z")

    @Test
    fun keepsWeatherAcrossMidnightAndMarksItStaleWhenItExpires() {
        val observation = midnight.minusSeconds(30 * 60)
        val weather = CurrentWeather(6, WeatherCondition.OVERCAST, observation)

        val (fresh, stale) = dateWeatherFrames(midnight.minusSeconds(60), zone, weather)

        assertEquals(midnight, fresh.start)
        assertEquals(LocalDate.of(2026, 11, 12), fresh.date)
        assertEquals("6°C", fresh.weather?.temperatureText)
        assertFalse(fresh.weather!!.isStale)
        assertEquals(fresh.end, stale.start)
        assertEquals(observation.plusSeconds(2 * 60 * 60), stale.start)
        assertEquals("~6°C", stale.weather?.temperatureText)
        assertTrue(stale.weather!!.isStale)
    }

    @Test
    fun keepsAnAlreadyStaleReadingAfterTheDateChanges() {
        val weather = CurrentWeather(6, WeatherCondition.OVERCAST, midnight.minusSeconds(4 * 60 * 60))
        val now = midnight.minusSeconds(60)

        val tomorrow = dateWeatherFrames(now, zone, weather.at(now)).single()

        assertEquals(midnight, tomorrow.start)
        assertEquals("~6°C", tomorrow.weather?.temperatureText)
        assertEquals(WeatherCondition.OVERCAST, tomorrow.weather?.condition)
    }

    @Test
    fun marksAReadingStaleBeforeMidnightWhenRefreshIsDelayed() {
        val weather = CurrentWeather(6, WeatherCondition.OVERCAST, midnight.minusSeconds(3 * 60 * 60))
        val (today, tomorrow) = dateWeatherFrames(midnight.minusSeconds(2 * 60 * 60), zone, weather)

        assertEquals(midnight.minusSeconds(60 * 60), today.start)
        assertEquals(midnight, today.end)
        assertEquals("~6°C", today.weather?.temperatureText)
        assertEquals("~6°C", tomorrow.weather?.temperatureText)
    }
}
