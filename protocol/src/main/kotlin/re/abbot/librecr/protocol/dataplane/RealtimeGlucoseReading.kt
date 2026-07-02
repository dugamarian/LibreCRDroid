package re.abbot.librecr.protocol.dataplane

import re.abbot.librecr.protocol.u16le

/**
 * Decoded realtime glucose plaintext (29 bytes). Direct port of Swift
 * `RealtimeGlucoseReading`. Field offsets and packed-word handling are exact.
 */
class RealtimeGlucoseReading(plaintext: ByteArray) {
    val lifeCount: Int
    val currentWord: Int
    val readingMgDL: Int
    val dqErrorRaw: Int
    val dqError: Libre3DataQualityError
    val sensorConditionRaw: Int
    val sensorCondition: Libre3SensorCondition
    val currentGlucoseMgDL: Int?
    val isCurrentGlucoseValid: Boolean
    val rateOfChangeRaw: Short
    val rateOfChangeMgDLPerMinute: Float?
    val trendRaw: Int
    val temperatureStatus: Int
    val projectedGlucose: Int
    val historicalLifeCount: Int
    val historicalWord: Int
    val historicalReading: Int
    val historicalReadingDQErrorRaw: Int
    val historicResultRangeStatusRaw: Int
    val historicResultRangeStatus: Libre3ResultRangeStatus
    val historicalGlucoseMgDL: Int?
    val trendAndStatusByte: Int
    val trend: Int
    val trendKind: Libre3Trend
    val rest: Int
    val actionableStatus: Int
    val actionability: Libre3ActionableStatus
    val uncappedCurrentMgDL: Int
    val uncappedHistoricMgDL: Int
    val temperature: Int
    val fastData: ByteArray
    val trailingByte: Int

    val currentGlucoseStatus: Libre3GlucoseValueStatus get() = Libre3GlucoseValueStatus.fromRaw(uncappedCurrentMgDL)
    /** Uncapped-below-floor value for charts/history only (see [Libre3GlucoseValueStatus.chartMgDL]). */
    val currentGlucoseChartMgDL: Int? get() = currentGlucoseStatus.chartMgDL
    val historicalGlucoseStatus: Libre3GlucoseValueStatus get() = Libre3GlucoseValueStatus.fromRaw(uncappedHistoricMgDL)
    val isCurrentDQGood: Boolean get() = dqError.isGood && sensorCondition == Libre3SensorCondition.OK
    val isCurrentGlucoseUsable: Boolean get() = isCurrentGlucoseValid && isCurrentDQGood

    init {
        if (plaintext.size != PLAINTEXT_SIZE) throw RealtimeGlucoseReadingException.WrongPlaintextSize(plaintext.size)
        val pt = plaintext
        val curWord = pt.u16le(2)
        val histWord = pt.u16le(12)
        val trendAndRest = pt[14].toInt() and 0xff
        val rocRaw = pt.u16le(4).toShort()
        val uncappedCur = pt.u16le(15)
        val uncappedHist = pt.u16le(17)

        lifeCount = pt.u16le(0)
        currentWord = curWord
        readingMgDL = glucoseValue(curWord)
        dqErrorRaw = dqErrorRaw(curWord)
        dqError = Libre3DataQualityError(dqErrorRaw)
        sensorConditionRaw = statusBits13to14(curWord)
        sensorCondition = Libre3SensorCondition.fromRaw(sensorConditionRaw)
        currentGlucoseMgDL = Libre3GlucoseValueStatus.fromRaw(uncappedCur).displayMgDL
        isCurrentGlucoseValid = currentGlucoseMgDL != null
        rateOfChangeRaw = rocRaw
        rateOfChangeMgDLPerMinute = if (rocRaw == Short.MIN_VALUE) null else rocRaw / 100.0f
        trendRaw = trendAndRest and 0x07
        temperatureStatus = pt.u16le(6)
        projectedGlucose = pt.u16le(8)
        historicalLifeCount = pt.u16le(10)
        historicalWord = histWord
        historicalReading = glucoseValue(histWord)
        historicalReadingDQErrorRaw = dqErrorRaw(histWord)
        historicResultRangeStatusRaw = statusBits13to14(histWord)
        historicResultRangeStatus = Libre3ResultRangeStatus.fromRaw(historicResultRangeStatusRaw)
        historicalGlucoseMgDL = Libre3GlucoseValueStatus.fromRaw(uncappedHist).displayMgDL
        trendAndStatusByte = trendAndRest
        trend = trendRaw
        trendKind = Libre3Trend.fromRaw(trendRaw)
        rest = trendAndRest ushr 3
        actionableStatus = if (trendAndRest and 0x08 == 0) 0 else 1
        actionability = Libre3ActionableStatus.fromRaw(actionableStatus)
        uncappedCurrentMgDL = uncappedCur
        uncappedHistoricMgDL = uncappedHist
        temperature = pt.u16le(19)
        fastData = pt.copyOfRange(21, 29)
        trailingByte = pt[pt.size - 1].toInt() and 0xff
    }

    companion object {
        const val PLAINTEXT_SIZE = 29
        private fun glucoseValue(word: Int): Int = word and 0x1fff
        private fun dqErrorRaw(word: Int): Int = if (word and 0x8000 == 0) 0 else word
        private fun statusBits13to14(word: Int): Int = (word ushr 13) and 0x03
    }
}

sealed class RealtimeGlucoseReadingException(message: String) : Exception(message) {
    class WrongPlaintextSize(val size: Int) : RealtimeGlucoseReadingException("wrong plaintext size $size")
}
