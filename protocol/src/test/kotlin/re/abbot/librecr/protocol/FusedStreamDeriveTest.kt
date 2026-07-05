package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import re.abbot.librecr.protocol.crypto.Phase5KeySchedule
import re.abbot.librecr.protocol.pairing.SessionKey

/**
 * Guards the fused single-pass stream derivation + the bundled-entry-source caching (the real watch
 * path). The golden [SessionKeyTest] only exercises a *synthetic* entry source, so it never hits the
 * cache path — a cache-mutation bug (shared ByteArray written in place across derivations) would ship
 * silently. The retained row-list path is used as an independent oracle.
 */
class FusedStreamDeriveTest {
    private val bundledEntry = SessionKey.bundledEntrySource
    private val nullEntropy = ByteArray(0x11a) { ((it * 11 + 3) and 0xff).toByte() }
    private val generatorXY = hexToBytes(
        "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
    )
    private val generator65 = byteArrayOf(0x04) + generatorXY

    private fun seedsFor(entry: ByteArray): FirstPairSourceSlice.Builder6388f0FirstPairStreamSeeds {
        val nullScalar = FirstPairSourceSlice.builder633fa8NullScalarWindowFromEntropy(nullEntropy)
        val staticScalar = FirstPairSourceSlice.builder633fa8StaticScalarWindowFromEntrySource(entry)
        return FirstPairSourceSlice.builder6388f0FirstPairStreamSeedsFromScalarsAndSensorPoints(
            entry, nullScalar, staticScalar, generatorXY, generatorXY, nullEntropy, 1,
        )
    }

    /** The fused single-pass path must equal the retained two-pass row-list path, byte for byte. */
    @Test
    fun fusedMatchesRowListPath() {
        for (entry in listOf(bundledEntry, ByteArray(0x214) { ((it * 5 + 1) and 7).toByte() })) {
            val seeds = seedsFor(entry)
            val fused = FirstPairSourceSlice.deriveFrom6388f0FirstPairStreamSeeds(seeds).toHex()
            val rowList = FirstPairSourceSlice.deriveFrom6388f0SeededCaller64Rows(
                FirstPairSourceSlice.builder6388f0SeededCaller64RowsFromFirstPairStreamSeeds(seeds),
            ).toHex()
            assertEquals(rowList, fused, "fused vs row-list mismatch for entry=${entry.size}B")
        }
    }

    /** The bundled cache path must be stable across repeated derivations AND match the row-list oracle. */
    @Test
    fun bundledCachePathIsStableAndCorrect() {
        val oracle = Phase5KeySchedule.deriveRawKey(
            FirstPairSourceSlice.deriveFrom6388f0SeededCaller64Rows(
                FirstPairSourceSlice.builder6388f0SeededCaller64RowsFromFirstPairStreamSeeds(seedsFor(bundledEntry)),
            ),
        ).toHex()

        repeat(3) { run ->
            val key = SessionKey.deriveFirstPairPhase5Material(
                sensorEphemeralPub65 = generator65,
                sensorStaticPub65 = generator65,
                nullEntropy11A = nullEntropy,
                entrySource = bundledEntry,
            ).rawKey.toHex()
            assertEquals(oracle, key, "bundled derive run #$run diverged (cache mutated?)")
        }
    }
}
