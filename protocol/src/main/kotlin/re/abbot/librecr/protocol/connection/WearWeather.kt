package re.abbot.librecr.protocol.connection

import java.nio.ByteBuffer

data class WearWeatherReading(val observedAtSeconds: Long, val temperatureC: Int, val code: Int, val isDay: Boolean)

/** A null reading explicitly clears weather. The publication time orders updates and clears. */
data class WearWeather(val publishedAtMillis: Long, val reading: WearWeatherReading?) {
    fun encode(): ByteArray = ByteBuffer.allocate(if (reading == null) HEADER_SIZE else READING_SIZE)
        .putLong(publishedAtMillis)
        .apply {
            reading?.let {
                putLong(it.observedAtSeconds)
                putInt(it.temperatureC)
                putInt(it.code)
                put(if (it.isDay) 1.toByte() else 0.toByte())
            }
        }.array()

    companion object {
        const val PATH = "/librecr/weather/v1"
        private const val HEADER_SIZE = 8
        private const val READING_SIZE = HEADER_SIZE + 17

        fun decode(bytes: ByteArray): WearWeather? {
            if (bytes.size != HEADER_SIZE && bytes.size != READING_SIZE) return null
            val buffer = ByteBuffer.wrap(bytes)
            val publishedAt = buffer.long
            if (publishedAt <= 0) return null
            if (bytes.size == HEADER_SIZE) return WearWeather(publishedAt, null)

            val observedAt = buffer.long
            val temperature = buffer.int
            val code = buffer.int
            val day = buffer.get().toInt()
            if (observedAt <= 0 || observedAt > publishedAt / 1000 + 300 ||
                temperature !in -100..70 || code !in 0..255 || day !in 0..1
            ) return null
            return WearWeather(publishedAt, WearWeatherReading(observedAt, temperature, code, day == 1))
        }
    }
}
