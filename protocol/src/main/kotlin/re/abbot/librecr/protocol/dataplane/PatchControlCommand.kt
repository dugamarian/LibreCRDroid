package re.abbot.librecr.protocol.dataplane

/** Backfill stream selector. historical=0x00, clinical=0x01 (protocol.md). */
enum class BackfillStream(val wireValue: Int, val label: String) {
    HISTORICAL(0x00, "historical backfill"),
    CLINICAL(0x01, "clinical backfill"),
}

/** Plaintext builders for PatchDataControl writes (7 bytes pre-encryption).
 *  Direct port of Swift `PatchControlCommand`. */
class PatchControlCommand private constructor(val label: String, val plaintext: ByteArray) {

    companion object {
        fun of(label: String, plaintext: ByteArray): PatchControlCommand {
            require(plaintext.size == 7) { "patch control plaintext must be 7 bytes, got ${plaintext.size}" }
            return PatchControlCommand(label, plaintext)
        }

        fun historicalBackfillGreaterEqual(lifeCount: Int, selector: Int = 0x01) =
            backfillGreaterEqual(BackfillStream.HISTORICAL, lifeCount, selector)

        fun clinicalBackfillGreaterEqual(lifeCount: Int, selector: Int = 0x01) =
            backfillGreaterEqual(BackfillStream.CLINICAL, lifeCount, selector)

        fun backfillGreaterEqual(stream: BackfillStream, lifeCount: Int, selector: Int = 0x01): PatchControlCommand {
            val bytes = byteArrayOf(
                0x01,
                stream.wireValue.toByte(),
                selector.toByte(),
                (lifeCount and 0xff).toByte(),
                ((lifeCount ushr 8) and 0xff).toByte(),
                0x00,
                0x00,
            )
            return PatchControlCommand("${stream.label} >= $lifeCount", bytes)
        }

        fun eventLog(index: Int) =
            PatchControlCommand("event log >= $index", byteArrayOf(0x04, index.toByte(), 0, 0, 0, 0, 0))

        fun factoryData() =
            PatchControlCommand("factory data", byteArrayOf(0x06, 0, 0, 0, 0, 0, 0))

        /**
         * Terminal sensor shutdown/end-session command. This is not part of
         * routine disconnect, reconnect, or bounded backfill.
         */
        fun shutdownPatch() =
            PatchControlCommand("shutdown patch", byteArrayOf(0x05, 0, 0, 0, 0, 0, 0))
    }
}
