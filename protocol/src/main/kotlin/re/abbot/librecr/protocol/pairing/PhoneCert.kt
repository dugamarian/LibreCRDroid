package re.abbot.librecr.protocol.pairing

import re.abbot.librecr.protocol.hexToBytes

/**
 * Phone certificate (162 bytes), sent in Phase 1. Port of Swift `PhoneCert`.
 *   [33..98) phone STATIC pubkey (65B uncompressed, 0x04 prefix)
 */
class PhoneCert(val raw: ByteArray) {
    val staticPub: ByteArray

    init {
        if (raw.size != TOTAL_SIZE) throw PhoneCertException.WrongSize(raw.size)
        val pub = raw.copyOfRange(33, 98)
        if (pub[0].toInt() != 0x04) throw PhoneCertException.NotUncompressedPoint
        staticPub = pub
    }

    /**
     * First-pair Phase 5 static-scalar policy keyed on certificate family.
     * The live-sensor `03 03` family uses the native index-1 scalar window.
     */
    val phase5StaticScalarWindowOverride: ByteArray?
        get() = if (raw[0] == 0x03.toByte() && raw[1] == 0x03.toByte()) {
            FirstPairStaticScalarWindow.firstPairIndex1
        } else {
            null
        }

    companion object {
        const val TOTAL_SIZE = 162

        /**
         * Loads the `03 00` candidate used by tests and controlled experiments.
         * Live fresh sensors reject this family; use [bundled162b] for pairing.
         */
        fun bundledFirstPair(): PhoneCert = bundled("phone_cert_firstpair")

        /**
         * Loads the bundled universal `03 03` certificate that pairs live fresh
         * sensors when coupled to the native-ephemeral Phase 5 derivation.
         */
        fun bundled162b(): PhoneCert = bundled("phone_cert_162b")

        @Deprecated("Use bundled162b(); the certificate is a universal static artifact, not user-specific.")
        fun bundledCapturedUser(): PhoneCert = bundled162b()

        fun bundled(resource: String): PhoneCert {
            val path = "/runtime_tables/$resource.bin"
            val stream = PhoneCert::class.java.getResourceAsStream(path)
                ?: throw PhoneCertException.BundledResourceMissing(resource)
            return PhoneCert(stream.use { it.readBytes() })
        }
    }
}

object FirstPairStaticScalarWindow {
    private const val INDEX1_PREFIX =
        "978d11ed646ee3559336d5feba587ce984123198cd9e880d34bad0fac8a997bf"

    /** Native `0x6388f0` row-59 static scalar window for the `03 03` cert family. */
    val firstPairIndex1: ByteArray = hexToBytes(INDEX1_PREFIX) + ByteArray(38)
}

sealed class PhoneCertException(message: String) : Exception(message) {
    class WrongSize(val size: Int) : PhoneCertException("wrong size $size")
    object NotUncompressedPoint : PhoneCertException("not an uncompressed point")
    class BundledResourceMissing(val resource: String) : PhoneCertException("missing $resource")
}
