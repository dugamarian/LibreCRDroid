package re.abbot.librecr.protocol.pairing

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import re.abbot.librecr.protocol.crypto.Phase5KeySchedule

/**
 * First-pair Phase 5 material derivation, mirroring Swift `SessionKey`. Ties the
 * white-box `FirstPairSourceSlice` builders into the two operations the
 * command-gated first-pair handshake needs:
 *
 *   1. [makeFirstPairNativeEphemeral] — before connecting, sample accepted
 *      null-branch entropy and derive BOTH the on-device null scalar window AND
 *      the `process2(5)` phone-ephemeral public key (65-byte `0x04‖x‖y`) sent on
 *      the wire. The ~30s null-scalar search runs here, OUTSIDE the time-sensitive
 *      handshake window.
 *   2. [deriveFirstPairPhase5Material] — once the sensor's ephemeral + static
 *      P-256 points arrive, mix them with the SAME entropy/scalar to produce the
 *      66-byte Phase 5 source and the 16-byte Phase 5 raw key. Row 0 uses the
 *      sensor ephemeral point; row 59 uses the sensor certificate's static point.
 *
 * The pre-computed `nullEntropy11A`/`nullScalarWindow` passed to step 2 MUST come
 * from the same step-1 result, otherwise the derived key won't match the key the
 * sensor computes from the phone ephemeral.
 */
object SessionKey {
    /** Default bundled 532-byte entry source for the row-0 low-seed path. */
    val bundledEntrySource: ByteArray get() = FirstPairSourceSlice.bundled6388f0LowSeedEntrySource

    private val bundledStaticScalarWindow: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        FirstPairSourceSlice.builder633fa8StaticScalarWindowFromEntrySource(bundledEntrySource)
    }

    private val bundledRow0LowSeedPreimages: FirstPairSourceSlice.Builder6388f0Row0LowSeedPreimages by lazy(LazyThreadSafetyMode.PUBLICATION) {
        FirstPairSourceSlice.builder6388f0Row0LowSeedPreimagesFromEntrySource(bundledEntrySource)
    }

    private fun isBundledEntrySource(entrySource: ByteArray): Boolean =
        entrySource === bundledEntrySource || entrySource.contentEquals(bundledEntrySource)

    private fun staticScalarWindowFor(entrySource: ByteArray, override: ByteArray?, bundled: Boolean): ByteArray =
        override ?: if (bundled) {
            bundledStaticScalarWindow
        } else {
            FirstPairSourceSlice.builder633fa8StaticScalarWindowFromEntrySource(entrySource)
        }

    private fun row0LowSeedPreimagesFor(entrySource: ByteArray, bundled: Boolean): FirstPairSourceSlice.Builder6388f0Row0LowSeedPreimages =
        if (bundled) {
            bundledRow0LowSeedPreimages
        } else {
            FirstPairSourceSlice.builder6388f0Row0LowSeedPreimagesFromEntrySource(entrySource)
        }

    private fun firstPairStreamSeeds(
        nullScalarWindow: ByteArray,
        staticScalarWindow: ByteArray,
        nullEntropy11A: ByteArray,
        nullAttempts: Int,
        low: FirstPairSourceSlice.Builder6388f0Row0LowSeedPreimages,
        row0High: FirstPairSourceSlice.Builder6388f0HighSeedStreamStartSeeds,
        row59High: FirstPairSourceSlice.Builder6388f0HighSeedStreamStartSeeds,
    ): FirstPairSourceSlice.Builder6388f0FirstPairStreamSeeds =
        FirstPairSourceSlice.Builder6388f0FirstPairStreamSeeds(
            nullScalarWindow,
            staticScalarWindow,
            nullEntropy11A,
            nullAttempts,
            low.out4,
            low.out3,
            low.out2,
            row0High.out1,
            row0High.out0,
            row59High.out1,
            row59High.out0,
        )

    class FirstPairNativeEphemeral(
        val phoneEphemeralPub65: ByteArray,
        val nullEntropy11A: ByteArray,
        val nullScalarWindow: ByteArray,
        val attempts: Int,
    )

    class FirstPairPhase5Material(
        val source66: ByteArray,
        val rawKey: ByteArray,
        val nullEntropy11A: ByteArray,
        val nullScalarWindow: ByteArray,
        val nullAttempts: Int,
    )

    /**
     * Sample accepted null-branch entropy and derive the phone ephemeral that
     * first-pair sends. `entropySource(count)` must return `count` (0x11a) random
     * bytes per call (e.g. from `SecureRandom`); it is called until the entropy
     * passes the 633fa8 acceptance checks (or [maxAttempts] is hit).
     */
    fun makeFirstPairNativeEphemeral(maxAttempts: Int = 64, entropySource: (Int) -> ByteArray): FirstPairNativeEphemeral {
        val result = FirstPairSourceSlice.builder633fa8NullScalarWindowFromEntropySource(maxAttempts, entropySource)
        val pub65 = FirstPairSourceSlice.builderProcess2P5PublicKey65FromEntropy(result.entropy11A)
        return FirstPairNativeEphemeral(pub65, result.entropy11A, result.scalarWindow, result.attempts)
    }

    private fun uncompressedPointXYBE(point65: ByteArray, label: String): ByteArray {
        require(point65.size == 65 && point65[0].toInt() == 0x04) { "invalid $label point (len=${point65.size})" }
        return point65.copyOfRange(1, 65)
    }

    /**
     * Fast path: reuse the `nullEntropy11A`/`nullScalarWindow` produced by
     * [makeFirstPairNativeEphemeral] so the slow null-scalar search is NOT re-run
     * inside the handshake window. Only the fast ephemeral/static mixing runs.
     */
    fun deriveFirstPairPhase5Material(
        sensorEphemeralPub65: ByteArray,
        sensorStaticPub65: ByteArray,
        nullEntropy11A: ByteArray,
        nullScalarWindow: ByteArray,
        entrySource: ByteArray = bundledEntrySource,
        staticScalarWindow: ByteArray? = null,
        nullAttempts: Int = 1,
    ): FirstPairPhase5Material {
        val row0 = uncompressedPointXYBE(sensorEphemeralPub65, "sensor ephemeral")
        val row59 = uncompressedPointXYBE(sensorStaticPub65, "sensor static")
        val bundled = isBundledEntrySource(entrySource)
        val staticScalar = staticScalarWindowFor(entrySource, staticScalarWindow, bundled)
        val low = row0LowSeedPreimagesFor(entrySource, bundled)
        val row0High = FirstPairSourceSlice.builder6388f0HighSeedStreamStartSeedsFromScalarP256(nullScalarWindow, row0)
        val row59High = FirstPairSourceSlice.builder6388f0HighSeedStreamStartSeedsFromScalarP256(staticScalar, row59)
        val seeds = firstPairStreamSeeds(nullScalarWindow, staticScalar, nullEntropy11A, nullAttempts, low, row0High, row59High)
        val source = FirstPairSourceSlice.deriveFrom6388f0FirstPairStreamSeeds(seeds)
        val rawKey = Phase5KeySchedule.deriveRawKey(source)
        return FirstPairPhase5Material(source, rawKey, nullEntropy11A, nullScalarWindow, nullAttempts)
    }

    suspend fun deriveFirstPairPhase5MaterialParallel(
        sensorEphemeralPub65: ByteArray,
        sensorStaticPub65: ByteArray,
        nullEntropy11A: ByteArray,
        nullScalarWindow: ByteArray,
        entrySource: ByteArray = bundledEntrySource,
        staticScalarWindow: ByteArray? = null,
        nullAttempts: Int = 1,
    ): FirstPairPhase5Material = coroutineScope {
        val row0 = uncompressedPointXYBE(sensorEphemeralPub65, "sensor ephemeral")
        val row59 = uncompressedPointXYBE(sensorStaticPub65, "sensor static")
        val bundled = isBundledEntrySource(entrySource)
        val staticScalar = staticScalarWindowFor(entrySource, staticScalarWindow, bundled)

        val lowDeferred = async {
            row0LowSeedPreimagesFor(entrySource, bundled)
        }
        val row0HighDeferred = async {
            FirstPairSourceSlice.builder6388f0HighSeedStreamStartSeedsFromScalarP256(nullScalarWindow, row0)
        }
        val row59HighDeferred = async {
            FirstPairSourceSlice.builder6388f0HighSeedStreamStartSeedsFromScalarP256(staticScalar, row59)
        }

        val low = lowDeferred.await()
        val row0High = row0HighDeferred.await()
        val row59High = row59HighDeferred.await()
        val seeds = firstPairStreamSeeds(nullScalarWindow, staticScalar, nullEntropy11A, nullAttempts, low, row0High, row59High)
        val source = FirstPairSourceSlice.deriveFrom6388f0FirstPairStreamSeeds(seeds)
        val rawKey = Phase5KeySchedule.deriveRawKey(source)
        FirstPairPhase5Material(source, rawKey, nullEntropy11A, nullScalarWindow, nullAttempts)
    }

    /** Convenience: derive material directly from raw entropy (re-runs the null scalar derivation). */
    fun deriveFirstPairPhase5Material(
        sensorEphemeralPub65: ByteArray,
        sensorStaticPub65: ByteArray,
        nullEntropy11A: ByteArray,
        entrySource: ByteArray = bundledEntrySource,
        staticScalarWindow: ByteArray? = null,
    ): FirstPairPhase5Material = deriveFirstPairPhase5Material(
        sensorEphemeralPub65, sensorStaticPub65, nullEntropy11A,
        FirstPairSourceSlice.builder633fa8NullScalarWindowFromEntropy(nullEntropy11A),
        entrySource, staticScalarWindow, 1)
}
