package re.abbot.librecr.protocol.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WearWeatherTest {
    private val revision = 1_794_420_000_000L
    private val reading = WearWeatherReading(revision / 1_000 - 600, -6, 73, false)

    @Test
    fun readingAndClearRoundTrip() {
        val update = WearWeather(revision, reading)
        val clear = WearWeather(revision + 1, null)

        assertEquals(update, WearWeather.decode(update.encode()))
        assertEquals(clear, WearWeather.decode(clear.encode()))
    }

    @Test
    fun malformedOrFutureReadingDoesNotReplaceWatchCache() {
        val payload = WearWeather(revision, reading).encode()
        val futureReading = reading.copy(observedAtSeconds = revision / 1_000 + 301)

        assertNull(WearWeather.decode(payload.copyOf(payload.size - 1)))
        assertNull(WearWeather.decode(payload.copyOf().apply { this[lastIndex] = 2 }))
        assertNull(WearWeather.decode(WearWeather(revision, futureReading).encode()))
    }
}
