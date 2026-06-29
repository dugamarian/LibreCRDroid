package re.abbot.librecr.protocol.dataplane

import re.abbot.librecr.protocol.u16le

/**
 * Decoded clinical-stream record (char 0x08981ab8): a single per-minute
 * time-point record with seven 16-bit fields. Port of Swift
 * `ClinicalReadingRecord`. `currentGlucoseRaw` (word[5]) is the per-minute
 * value keyed at this record's own `lifeCount`.
 */
class ClinicalReadingRecord(plaintext: ByteArray) {
    val lifeCount: Int
    val rawSensorWord1: Int
    val rawSensorWord2: Int
    val rawSensorWord3: Int
    val reservedWord: Int
    val currentGlucoseRaw: Int
    val historicGlucoseRaw: Int

    init {
        if (plaintext.size != PLAINTEXT_SIZE) throw ClinicalReadingRecordException.WrongPlaintextSize(plaintext.size)
        val w = IntArray(7) { plaintext.u16le(it * 2) }
        lifeCount = w[0]
        rawSensorWord1 = w[1]
        rawSensorWord2 = w[2]
        rawSensorWord3 = w[3]
        reservedWord = w[4]
        currentGlucoseRaw = w[5]
        historicGlucoseRaw = w[6]
    }

    val currentGlucose: Libre3GlucoseValueStatus get() = Libre3GlucoseValueStatus.fromRaw(currentGlucoseRaw)
    val currentGlucoseMgDL: Int? get() = currentGlucose.displayMgDL
    val historicGlucose: Libre3GlucoseValueStatus get() = Libre3GlucoseValueStatus.fromRaw(historicGlucoseRaw)
    val historicGlucoseMgDL: Int? get() = historicGlucose.displayMgDL

    /**
     * Best-effort estimate of the lifeCount represented by historicGlucoseRaw.
     * Abbott's fixed protocol parameters are a 17-minute finalization latency
     * and a 5-minute historical life-count interval.
     */
    val historicLifeCountEstimate: Int?
        get() {
            val lagged = lifeCount - HISTORIC_POINT_LATENCY_MINUTES
            if (lagged < 0) return null
            return lagged - (lagged % HISTORIC_LIFECOUNT_INTERVAL_MINUTES)
        }

    companion object {
        const val PLAINTEXT_SIZE = 14
        const val HISTORIC_POINT_LATENCY_MINUTES = 17
        const val HISTORIC_LIFECOUNT_INTERVAL_MINUTES = 5
    }
}

sealed class ClinicalReadingRecordException(message: String) : Exception(message) {
    class WrongPlaintextSize(val size: Int) : ClinicalReadingRecordException("wrong plaintext size $size")
}
