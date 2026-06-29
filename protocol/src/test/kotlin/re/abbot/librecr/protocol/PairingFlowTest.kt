package re.abbot.librecr.protocol

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.AesCcm
import re.abbot.librecr.protocol.crypto.LibAes
import re.abbot.librecr.protocol.pairing.BleCharRef
import re.abbot.librecr.protocol.pairing.CommandPairingTransport
import re.abbot.librecr.protocol.pairing.PairingFlow
import re.abbot.librecr.protocol.pairing.Phase5Challenge
import re.abbot.librecr.protocol.pairing.PhoneCert

/**
 * Loopback "sensor" that completes the handshake using the same crypto, so the
 * full PairingFlow state machine + Phase 5 build + Phase 6 decrypt/verify is
 * exercised offline. Validates ordering, command gating, and session-key
 * derivation without a device.
 */
private class ScriptedSensor(
    private val phase5RawKey: ByteArray,
    private val sensorR1: ByteArray,
    private val nonce7: ByteArray,
    private val phase6Nonce: ByteArray,
    private val kEnc: ByteArray,
    private val ivEnc: ByteArray,
    private val expectedTail4: ByteArray,
) : CommandPairingTransport {
    private val cmdQueue = ArrayDeque<ByteArray>()
    private val certQueue = ArrayDeque<ByteArray>()
    private val challengeQueue = ArrayDeque<ByteArray>()
    var capturedR2: ByteArray? = null
        private set

    override suspend fun write(message: ByteArray, characteristic: BleCharRef) {
        if (characteristic == BleCharRef.CHALLENGE) {
            // Phase 5 from the phone: decrypt, verify, build Phase 6.
            val phase5 = Phase5Challenge.decode(message)
            val block = LibAes.phase5BlockEncryptor(phase5RawKey)
            val plaintext = AesCcm.decrypt(nonce7, phase5.ciphertext, phase5.tag, aes = block)
            assertArrayEquals(sensorR1, plaintext.copyOfRange(0, 16), "phase5 R1 echo")
            assertArrayEquals(expectedTail4, plaintext.copyOfRange(32, 36), "phase5 tail4")
            val r2 = plaintext.copyOfRange(16, 32)
            capturedR2 = r2
            val p6pt = r2 + sensorR1 + kEnc + ivEnc
            val enc = AesCcm.encrypt(phase6Nonce, p6pt, tagLength = 4, aes = block)
            challengeQueue.addLast(enc.ciphertext + enc.tag + phase6Nonce)
        }
        // CERT_HANDSHAKE writes (phone cert / ephemeral) are ignored.
    }

    override suspend fun awaitNotify(characteristic: BleCharRef, exactly: Int): ByteArray {
        val q = if (characteristic == BleCharRef.CERT_HANDSHAKE) certQueue else challengeQueue
        return q.removeFirst()
    }

    override suspend fun writeCommand(command: Int) {
        when (command) {
            0x03 -> cmdQueue.addLast(byteArrayOf(0x04))
            0x09 -> { cmdQueue.addLast(byteArrayOf(0x0a)); certQueue.addLast(fakeSensorCert()) }
            0x0e -> { cmdQueue.addLast(byteArrayOf(0x0f)); certQueue.addLast(fakeSensorEph()) }
            0x11 -> { cmdQueue.addLast(byteArrayOf(0x08)); challengeQueue.addLast(sensorR1 + nonce7) }
            0x08 -> cmdQueue.addLast(byteArrayOf(0x08))
        }
    }

    override suspend fun awaitCommandResponse(timeoutMs: Long): ByteArray = cmdQueue.removeFirst()

    private fun fakeSensorCert(): ByteArray = ByteArray(140).also { it[11] = 0x04 }
    private fun fakeSensorEph(): ByteArray = ByteArray(65).also { it[0] = 0x04 }
}

class PairingFlowTest {
    private val rawKey = hexToBytes("3b16168843c299ad7fa311ba2440d58a")
    private val sensorR1 = hexToBytes("00112233445566778899aabbccddeeff")
    private val nonce7 = hexToBytes("210400008f8c4b")
    private val phase6Nonce = hexToBytes("35040000a4e148")
    private val kEnc = hexToBytes("4bbce496a63cc9a435adeeb4f78e1617")
    private val ivEnc = hexToBytes("0000000067c72c01")
    private val tail4 = hexToBytes("3225ec72")
    private val r2 = hexToBytes("8c5b0b7441a7486d930806db08acdf1e")

    @Test
    fun cachedReconnectDerivesSessionKeys() = runBlocking {
        val sensor = ScriptedSensor(rawKey, sensorR1, nonce7, phase6Nonce, kEnc, ivEnc, tail4)
        val flow = PairingFlow(sensor, logger = null)
        val result = flow.runCachedReconnectHandshake(tail4, rawKey, r2Provider = { r2 })
        assertArrayEquals(kEnc, result.sessionMaterial.kEnc, "kEnc")
        assertArrayEquals(ivEnc, result.sessionMaterial.ivEnc, "ivEnc")
        assertArrayEquals(sensorR1, result.sessionMaterial.sensorR1, "R1 echo")
        assertArrayEquals(r2, result.sessionMaterial.phoneR2, "R2 echo")
        assertEquals(40, result.phase5Sent.logicalBytes.size, "phase5 wire size")
    }

    @Test
    fun commandGatedAuthorizationDerivesSessionKeys() = runBlocking {
        val sensor = ScriptedSensor(rawKey, sensorR1, nonce7, phase6Nonce, kEnc, ivEnc, tail4)
        // Skip ECDSA verification (no real Abbott-signed cert in the loopback).
        val flow = PairingFlow(
            sensor,
            phoneCert = re.abbot.librecr.protocol.pairing.PhoneCert.bundledFirstPair(),
            sensorCertSigningKeys = emptyList(),
            logger = null,
        )
        val result = flow.runCommandGatedAuthorizationHandshake(tail4, rawKey, r2Provider = { r2 })
        assertArrayEquals(kEnc, result.sessionMaterial.kEnc, "kEnc")
        assertArrayEquals(ivEnc, result.sessionMaterial.ivEnc, "ivEnc")
    }

    @Test
    fun liveFirstPairPhoneCertIsBundled() {
        val cert = PhoneCert.bundled162b()
        assertEquals(162, cert.raw.size)
        assertArrayEquals(byteArrayOf(0x03, 0x03), cert.raw.copyOfRange(0, 2))
        assertEquals(0x04, cert.staticPub[0].toInt())
    }

    @Test
    fun liveFirstPairPhoneCertUsesIndex1StaticScalarOverride() {
        val cert = PhoneCert.bundled162b()
        assertNotNull(cert.phase5StaticScalarWindowOverride)
        val override = requireNotNull(cert.phase5StaticScalarWindowOverride)
        assertEquals(70, override.size)
        assertEquals(
            "978d11ed646ee3559336d5feba587ce984123198cd9e880d34bad0fac8a997bf",
            override.copyOfRange(0, 32).toHex(),
        )

        val firstPair = PhoneCert.bundledFirstPair()
        assertNull(firstPair.phase5StaticScalarWindowOverride)
    }
}
