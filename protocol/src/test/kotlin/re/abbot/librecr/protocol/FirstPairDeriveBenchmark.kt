package re.abbot.librecr.protocol

import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import re.abbot.librecr.protocol.crypto.P256ScalarMultiplier
import re.abbot.librecr.protocol.pairing.SessionKey

/**
 * Timing breakdown of the first-pair Phase 5 derivation — the path that must fit inside the
 * sensor's ~24s mid-handshake patience on the watch. Not a correctness test (golden tests cover
 * that); it prints a per-component profile so optimization work targets the real hotspot, and the
 * before/after ratio approximates the on-watch improvement. Uses the same fixtures as
 * [SessionKeyTest] so every timed call is on the golden-verified path.
 */
class FirstPairDeriveBenchmark {
    private val entrySource = ByteArray(0x214) { ((it * 5 + 1) and 7).toByte() }
    private val nullEntropy = ByteArray(0x11a) { ((it * 11 + 3) and 0xff).toByte() }
    private val generatorXY = hexToBytes(
        "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
    )
    private val generator65 = byteArrayOf(0x04) + generatorXY

    private inline fun timeMs(warmup: Int = 5, iterations: Int = 9, block: () -> Unit): Double {
        repeat(warmup) { block() }
        val times = LongArray(iterations)
        for (i in 0 until iterations) {
            val t0 = System.nanoTime()
            block()
            times[i] = System.nanoTime() - t0
        }
        times.sort()
        return times[iterations / 2] / 1e6
    }

    @Test
    fun printDeriveBreakdown() {
        val scalarWindow = FirstPairSourceSlice.builder633fa8NullScalarWindowFromEntropy(nullEntropy)

        val rawMultiply = timeMs {
            P256ScalarMultiplier.multiply(
                scalarWindow.copyOf(70),
                P256ScalarMultiplier.AffinePoint(generatorXY.copyOfRange(0, 32), generatorXY.copyOfRange(32, 64)),
            )
        }
        val highSeedRow = timeMs {
            FirstPairSourceSlice.builder6388f0HighSeedStreamStartSeedsFromScalarP256(scalarWindow, generatorXY)
        }
        val staticScalar = timeMs {
            FirstPairSourceSlice.builder633fa8StaticScalarWindowFromEntrySource(entrySource)
        }
        val lowSeeds = timeMs {
            FirstPairSourceSlice.builder6388f0Row0LowSeedPreimagesFromEntrySource(entrySource)
        }
        val staticScalarWindow = FirstPairSourceSlice.builder633fa8StaticScalarWindowFromEntrySource(entrySource)
        val seeds = FirstPairSourceSlice.builder6388f0FirstPairStreamSeedsFromScalarsAndSensorPoints(
            entrySource, scalarWindow, staticScalarWindow,
            generatorXY, generatorXY, nullEntropy, 1,
        )
        val streamDerive = timeMs {
            FirstPairSourceSlice.deriveFrom6388f0FirstPairStreamSeeds(seeds)
        }
        val fullDeriveSynthetic = timeMs {
            SessionKey.deriveFirstPairPhase5Material(
                sensorEphemeralPub65 = generator65,
                sensorStaticPub65 = generator65,
                nullEntropy11A = nullEntropy,
                entrySource = entrySource,
            )
        }
        val fullDeriveBundled = timeMs {
            SessionKey.deriveFirstPairPhase5Material(
                sensorEphemeralPub65 = generator65,
                sensorStaticPub65 = generator65,
                nullEntropy11A = nullEntropy,
                nullScalarWindow = scalarWindow,
            )
        }
        val ephemeral = timeMs {
            SessionKey.makeFirstPairNativeEphemeral { nullEntropy }
        }

        println("=== FirstPair derive breakdown (median ms, JVM) ===")
        println("P256 multiply (1 scalar mult)          : %10.2f".format(rawMultiply))
        println("highSeed row (mult + whitebox pipeline): %10.2f".format(highSeedRow))
        println("  -> whitebox pipeline share           : %10.2f".format(highSeedRow - rawMultiply))
        println("static scalar window (entry source)    : %10.2f".format(staticScalar))
        println("row0 low-seed preimages                : %10.2f".format(lowSeeds))
        println("stream derive (seeds -> source66)      : %10.2f".format(streamDerive))
        println("FULL online phase5 derive (synthetic)  : %10.2f".format(fullDeriveSynthetic))
        println("FULL online phase5 derive (bundled)    : %10.2f   <-- watch default path".format(fullDeriveBundled))
        println("ephemeral generation (pre-connect)     : %10.2f".format(ephemeral))
    }
}
