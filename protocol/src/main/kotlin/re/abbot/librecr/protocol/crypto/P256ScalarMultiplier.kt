package re.abbot.librecr.protocol.crypto

import java.math.BigInteger

/**
 * Clean-room P-256 scalar multiplication used by the first-pair Phase 5 source
 * builder (Swift `P256ScalarMultiplier` + `Field`). Point validation stays
 * value-for-value with the clean-room field implementation; scalar multiplication
 * uses the runtime BigInteger engine and reduces the scalar modulo the P-256 group
 * order before the Jacobian loop.
 *
 * Verified against the JDK EC provider (k·G) in tests.
 */
internal object P256ScalarMultiplier {

    class AffinePoint {
        val x: Field
        val y: Field

        constructor(xBE: ByteArray, yBE: ByteArray) {
            val x = Field.fromBigEndian32(xBE)
            val y = Field.fromBigEndian32(yBE)
            val rhs = x.squared() * x - x.times3() + Field.B
            if (y.squared() != rhs) throw FirstPairSourceException.InvalidP256Point
            this.x = x
            this.y = y
        }

        constructor(x: Field, y: Field) {
            this.x = x
            this.y = y
        }
    }

    fun multiply(scalarLE: ByteArray, point: AffinePoint): AffinePoint {
        val reducedScalarLE = reduceScalar(scalarLE)
        val topBit = highestSetBit(reducedScalarLE) ?: throw FirstPairSourceException.InvalidP256Point
        val addend = BigAffinePoint(point.x.toBigInteger(), point.y.toBigInteger())
        var result = BigJacobianPoint.INFINITY
        for (bit in topBit downTo 0) {
            result = double(result)
            if (scalarBit(reducedScalarLE, bit)) result = addMixed(result, addend)
        }
        return affine(result)
    }

    private fun reduceScalar(scalarLE: ByteArray): ByteArray {
        val reduced = BigInteger(1, scalarLE.reversedArray()).mod(P256_ORDER)
        if (reduced.signum() == 0) throw FirstPairSourceException.InvalidP256Point
        return toFixed32BE(reduced).reversedArray()
    }

    private fun highestSetBit(scalarLE: ByteArray): Int? {
        for (byteIndex in scalarLE.indices.reversed()) {
            val b = scalarLE[byteIndex].toInt() and 0xff
            if (b == 0) continue
            for (bit in 7 downTo 0) if (b and (1 shl bit) != 0) return byteIndex * 8 + bit
        }
        return null
    }

    private fun scalarBit(scalarLE: ByteArray, bit: Int): Boolean =
        ((scalarLE[bit / 8].toInt() and 0xff) shr (bit and 7)) and 1 != 0

    private data class BigAffinePoint(val x: BigInteger, val y: BigInteger)

    private data class BigJacobianPoint(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val infinity: Boolean = false,
    ) {
        companion object {
            val INFINITY = BigJacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO, true)
        }
    }

    private fun double(p: BigJacobianPoint): BigJacobianPoint {
        if (p.infinity || p.y.signum() == 0) return BigJacobianPoint.INFINITY
        val yy = square(p.y)
        val yyyy = square(yy)
        val zz = square(p.z)
        val zzzz = square(zz)
        val s = times4(mul(p.x, yy))
        val m = times3(sub(square(p.x), zzzz))
        val x3 = sub(square(m), s, s)
        val y3 = sub(mul(m, sub(s, x3)), times8(yyyy))
        val z3 = times2(mul(p.y, p.z))
        return BigJacobianPoint(x3, y3, z3)
    }

    private fun addMixed(p: BigJacobianPoint, addend: BigAffinePoint): BigJacobianPoint {
        if (p.infinity) return BigJacobianPoint(addend.x, addend.y, BigInteger.ONE)
        val z1z1 = square(p.z)
        val u2 = mul(addend.x, z1z1)
        val s2 = mul(addend.y, mul(p.z, z1z1))
        val h = sub(u2, p.x)
        if (h.signum() == 0) return if (s2 == p.y) double(p) else BigJacobianPoint.INFINITY
        val hh = square(h)
        val i = times4(hh)
        val j = mul(h, i)
        val r = times2(sub(s2, p.y))
        val v = mul(p.x, i)
        val x3 = sub(square(r), j, v, v)
        val y3 = sub(mul(r, sub(v, x3)), times2(mul(p.y, j)))
        val z3 = sub(square(add(p.z, h)), z1z1, hh)
        return BigJacobianPoint(x3, y3, z3)
    }

    private fun affine(p: BigJacobianPoint): AffinePoint {
        if (p.infinity) throw FirstPairSourceException.InvalidP256Point
        val zInv = p.z.modInverse(P256_PRIME)
        val zInv2 = square(zInv)
        val zInv3 = mul(zInv2, zInv)
        val x = mul(p.x, zInv2)
        val y = mul(p.y, zInv3)
        return AffinePoint(Field.fromBigEndian32(toFixed32BE(x)), Field.fromBigEndian32(toFixed32BE(y)))
    }

    private fun add(a: BigInteger, b: BigInteger): BigInteger = a.add(b).mod(P256_PRIME)
    private fun sub(a: BigInteger, b: BigInteger): BigInteger = a.subtract(b).mod(P256_PRIME)
    private fun sub(a: BigInteger, vararg rest: BigInteger): BigInteger {
        var out = a
        for (v in rest) out = out.subtract(v)
        return out.mod(P256_PRIME)
    }
    private fun mul(a: BigInteger, b: BigInteger): BigInteger = a.multiply(b).mod(P256_PRIME)
    private fun square(a: BigInteger): BigInteger = a.multiply(a).mod(P256_PRIME)
    private fun times2(a: BigInteger): BigInteger = a.shiftLeft(1).mod(P256_PRIME)
    private fun times3(a: BigInteger): BigInteger = a.multiply(BigInteger.valueOf(3)).mod(P256_PRIME)
    private fun times4(a: BigInteger): BigInteger = a.shiftLeft(2).mod(P256_PRIME)
    private fun times8(a: BigInteger): BigInteger = a.shiftLeft(3).mod(P256_PRIME)

    private fun toFixed32BE(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        if (raw.size <= 32) {
            raw.copyInto(out, 32 - raw.size)
        } else {
            raw.copyInto(out, 0, raw.size - 32, raw.size)
        }
        return out
    }

    private val P256_PRIME = BigInteger(
        "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
        16,
    )

    private val P256_ORDER = BigInteger(
        "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
        16,
    )
}

/** secp256r1 prime-field element (4×64-bit limbs, little-endian limb order). */
internal class Field(val l0: ULong, val l1: ULong, val l2: ULong, val l3: ULong) : Comparable<Field> {

    val limbs: ULongArray get() = ulongArrayOf(l0, l1, l2, l3)

    override fun equals(other: Any?): Boolean =
        other is Field && l0 == other.l0 && l1 == other.l1 && l2 == other.l2 && l3 == other.l3

    override fun hashCode(): Int = (((l0.hashCode() * 31 + l1.hashCode()) * 31 + l2.hashCode()) * 31 + l3.hashCode())

    override fun compareTo(other: Field): Int = compare(this, other)

    val littleEndianPadded70: ByteArray
        get() {
            val out = ByteArray(70)
            for ((idx, limb) in limbs.withIndex()) {
                var v = limb
                for (b in 0 until 8) {
                    out[idx * 8 + b] = (v and 0xffUL).toByte()
                    v = v shr 8
                }
            }
            return out
        }

    operator fun plus(rhs: Field): Field {
        val (sum, carry) = addRaw(this, rhs)
        if (carry) {
            val (corrected, _) = addRaw(sum, CARRY_CORRECTION)
            return if (corrected >= MODULUS) subRaw(corrected, MODULUS) else corrected
        }
        return if (sum >= MODULUS) subRaw(sum, MODULUS) else sum
    }

    operator fun minus(rhs: Field): Field {
        if (this >= rhs) return subRaw(this, rhs)
        val diff = subRaw(rhs, this)
        return subRaw(MODULUS, diff)
    }

    // Multiply/reduce and inverse go through java.math.BigInteger (native, constant-ish cost) instead
    // of the previous 512-iteration bit-by-bit reduce and 256-round Fermat inverse — each of those did
    // thousands of 64-bit shift/compare/subtract steps per field op, which the watch's 32-bit CPU
    // emulates limb-by-limb (measured ~3600x JVM→watch on the P-256-heavy ephemeral path). Add/sub keep
    // the fast single-pass limb path; equality, comparison and the byte layout are unchanged, so the
    // golden derivation output is byte-identical (guarded by P256Test / CryptoGoldenTest / SessionKey).
    operator fun times(rhs: Field): Field =
        fromBigInteger(toBigInteger().multiply(rhs.toBigInteger()).mod(PRIME))

    fun squared(): Field = this * this
    fun doubled(): Field = this + this
    fun times3(): Field = this.doubled() + this
    fun times4(): Field = this.doubled().doubled()
    fun times8(): Field = this.times4().doubled()

    fun inverted(): Field = fromBigInteger(toBigInteger().modInverse(PRIME))

    fun toBigInteger(): java.math.BigInteger {
        val be = ByteArray(32)
        writeU64BE(be, 0, l3); writeU64BE(be, 8, l2); writeU64BE(be, 16, l1); writeU64BE(be, 24, l0)
        return java.math.BigInteger(1, be)
    }

    companion object {
        val ZERO = Field(0UL, 0UL, 0UL, 0UL)
        val ONE = Field(1UL, 0UL, 0UL, 0UL)
        val MODULUS = Field(0xffff_ffff_ffff_ffffUL, 0x0000_0000_ffff_ffffUL, 0UL, 0xffff_ffff_0000_0001UL)
        private val PRIME = java.math.BigInteger(
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16,
        )

        fun fromBigInteger(v: java.math.BigInteger): Field {
            val raw = v.toByteArray()
            val be = ByteArray(32)
            if (raw.size <= 32) raw.copyInto(be, 32 - raw.size) else raw.copyInto(be, 0, raw.size - 32, raw.size)
            return fromBigEndian32(be)
        }

        private fun writeU64BE(out: ByteArray, offset: Int, value: ULong) {
            var v = value
            for (i in 7 downTo 0) { out[offset + i] = (v and 0xffUL).toByte(); v = v shr 8 }
        }
        val CARRY_CORRECTION = Field(1UL, 0xffff_ffff_0000_0000UL, 0xffff_ffff_ffff_ffffUL, 0x0000_0000_ffff_fffeUL)
        val B = Field(0x3bce_3c3e_27d2_604bUL, 0x651d_06b0_cc53_b0f6UL, 0xb3eb_bd55_7698_86bcUL, 0x5ac6_35d8_aa3a_93e7UL)

        fun fromBigEndian32(bytes: ByteArray): Field {
            if (bytes.size != 32) throw FirstPairSourceException.InvalidP256PointLength(bytes.size)
            return Field(readU64BE(bytes, 24), readU64BE(bytes, 16), readU64BE(bytes, 8), readU64BE(bytes, 0))
        }

        private fun compare(lhs: Field, rhs: Field): Int {
            val a = lhs.limbs
            val b = rhs.limbs
            for (index in 3 downTo 0) {
                if (a[index] < b[index]) return -1
                if (a[index] > b[index]) return 1
            }
            return 0
        }

        private fun addRaw(lhs: Field, rhs: Field): Pair<Field, Boolean> {
            val a = lhs.limbs
            val b = rhs.limbs
            val out = ULongArray(4)
            var carry = false
            for (index in 0 until 4) {
                val s1 = a[index] + b[index]
                val o1 = s1 < a[index]
                val s2 = s1 + (if (carry) 1UL else 0UL)
                val o2 = s2 < s1
                out[index] = s2
                carry = o1 || o2
            }
            return Field(out[0], out[1], out[2], out[3]) to carry
        }

        private fun subRaw(lhs: Field, rhs: Field): Field {
            val a = lhs.limbs
            val b = rhs.limbs
            val out = ULongArray(4)
            var borrow = false
            for (index in 0 until 4) {
                val d1 = a[index] - b[index]
                val o1 = a[index] < b[index]
                val d2 = d1 - (if (borrow) 1UL else 0UL)
                val o2 = d1 < (if (borrow) 1UL else 0UL)
                out[index] = d2
                borrow = o1 || o2
            }
            return Field(out[0], out[1], out[2], out[3])
        }

        private fun readU64BE(bytes: ByteArray, offset: Int): ULong {
            var value = 0UL
            for (index in 0 until 8) value = (value shl 8) or (bytes[offset + index].toULong() and 0xffUL)
            return value
        }
    }
}

sealed class FirstPairSourceException(message: String) : Exception(message) {
    object InvalidP256Point : FirstPairSourceException("invalid P-256 point")
    class InvalidP256PointLength(len: Int) : FirstPairSourceException("invalid P-256 point length $len")
    class InvalidP256ScalarLength(len: Int) : FirstPairSourceException("invalid P-256 scalar length $len")
}
