package re.abbot.librecr.protocol.pairing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import re.abbot.librecr.protocol.crypto.Ecc
import re.abbot.librecr.protocol.crypto.LibAes
import re.abbot.librecr.protocol.toHex
import java.security.SecureRandom

/**
 * Drives the Libre 3 BLE security handshake over a [PairingTransport]. Port of
 * the Swift `PairingFlow` orchestration (Milestone 1: reconnect paths, where the
 * Phase 5 raw key is imported rather than derived).
 *
 * Two paths, both ending at Phase 6 session material (kEnc/ivEnc):
 *   - [runCommandGatedAuthorizationHandshake]  full: cert + ephemeral + Phase 5/6
 *   - [runCachedReconnectHandshake]            cached: StartAuthorization → Phase 5/6
 *
 * Every step is logged with hex so a live session can be diffed byte-for-byte
 * against an iOS capture.
 */
class PairingFlow(
    private val transport: PairingTransport,
    private val phoneCert: PhoneCert? = null,
    private val sensorCertSigningKeys: List<ByteArray> = Libre3PatchSigningKey.known,
    private val logger: ((String) -> Unit)? = null,
) {
    private val secureRandom = SecureRandom()

    data class Preamble(
        val sensorR1: ByteArray,
        val nonce7: ByteArray,
        val sensorCert: SensorCert? = null,
        val sensorCertSigningKeyIndex: Int? = null,
        val sensorEphemeralPub65: ByteArray? = null,
        val phoneEphemeralPub65: ByteArray? = null,
    )

    data class CertificateEphemeralPreamble(
        val sensorCert: SensorCert,
        val sensorCertSigningKeyIndex: Int?,
        val sensorEphemeralPub65: ByteArray,
        val phoneEphemeralPub65: ByteArray,
    )

    data class AuthorizationResult(
        val preamble: Preamble,
        val phase5Sent: Phase5Challenge,
        val phase6Raw: ByteArray,
        val phase6: Phase6Response,
        val sessionMaterial: Phase6SessionMaterial,
        /** The Phase 5 raw key used — for first-pair this is the on-device-derived key worth caching. */
        val phase5RawKey: ByteArray,
    )

    // ---- full command-gated authorization (cert + ephemeral + Phase 5/6) ----

    suspend fun runCommandGatedAuthorizationPreamble(
        commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        notifyTimeoutMs: Long = DEFAULT_NOTIFY_TIMEOUT_MS,
        phoneEphemeralPub65Override: ByteArray? = null,
    ): Preamble {
        val certificateEphemeral = runCommandGatedCertificateEphemeralPreamble(
            commandTimeoutMs,
            notifyTimeoutMs,
            phoneEphemeralPub65Override,
        )
        return runStartAuthorizationPreamble(certificateEphemeral, commandTimeoutMs, notifyTimeoutMs)
    }

    suspend fun runCommandGatedCertificateEphemeralPreamble(
        commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        notifyTimeoutMs: Long = DEFAULT_NOTIFY_TIMEOUT_MS,
        phoneEphemeralPub65Override: ByteArray? = null,
    ): CertificateEphemeralPreamble {
        val cert = phoneCert ?: throw PairingFlowException.PhoneCertRequired
        val cmd = commandTransport()

        log("certificate/ephemeral preamble start")
        log("send StartAuthentication 0x01")
        withWriteTimeout("StartAuthentication") { cmd.writeCommand(0x01) }
        log("send LoadCertificate 0x02")
        withWriteTimeout("LoadCertificate") { cmd.writeCommand(0x02) }
        log("send phone cert len=${cert.raw.size}")
        withWriteTimeout("phoneCertWrite") { transport.write(cert.raw, BleCharRef.CERT_HANDSHAKE) }
        log("send SendCertificateLoadDone 0x03")
        withWriteTimeout("SendCertificateLoadDone") { cmd.writeCommand(0x03) }
        waitFor(cmd, byteArrayOf(0x04), "CertificateAccepted", commandTimeoutMs)

        log("send GetCertificate 0x09")
        withWriteTimeout("GetCertificate") { cmd.writeCommand(0x09) }
        waitFor(cmd, byteArrayOf(0x0a), "CertificateReady", commandTimeoutMs)

        log("await sensor cert")
        val sensorCertRaw = awaitNotify(BleCharRef.CERT_HANDSHAKE, SensorCert.TOTAL_SIZE, notifyTimeoutMs)
        val sensorCert = SensorCert(sensorCertRaw)
        val signingKeyIndex = verifySensorCertificate(sensorCert)
        log("parsed sensor cert; staticPub=${sensorCert.staticPub.toHex()} keyIndex=${signingKeyIndex ?: "skipped"}")

        log("send ValidateCertificate 0x0d")
        withWriteTimeout("ValidateCertificate") { cmd.writeCommand(0x0d) }
        val phoneEphemeralPub65 = phoneEphemeralPub65Override ?: Ecc.generateEphemeral().publicKey65
        val phase3Wire = padTo(phoneEphemeralPub65, 72)
        log("send phone ephemeral len=${phase3Wire.size} derived=${phoneEphemeralPub65Override != null}")
        withWriteTimeout("phoneEphemeralWrite") { transport.write(phase3Wire, BleCharRef.CERT_HANDSHAKE) }

        log("send SendEphemeralDone 0x0e")
        withWriteTimeout("SendEphemeralDone") { cmd.writeCommand(0x0e) }
        waitFor(cmd, byteArrayOf(0x0f), "EphemeralReady", commandTimeoutMs)

        log("await sensor ephemeral")
        val sensorEph = awaitNotify(BleCharRef.CERT_HANDSHAKE, 65, notifyTimeoutMs)
        log("sensor ephemeral pub=${sensorEph.toHex()}")

        return CertificateEphemeralPreamble(sensorCert, signingKeyIndex, sensorEph, phoneEphemeralPub65)
    }

    private suspend fun runStartAuthorizationPreamble(
        certificateEphemeral: CertificateEphemeralPreamble,
        commandTimeoutMs: Long,
        notifyTimeoutMs: Long,
    ): Preamble {
        val cmd = commandTransport()
        log("send StartAuthorization 0x11")
        withWriteTimeout("StartAuthorization") { cmd.writeCommand(0x11) }
        waitFor(cmd, byteArrayOf(0x08), "ChallengeLoadDone", commandTimeoutMs)

        log("await R1 challenge")
        val r1Wire = awaitNotify(BleCharRef.CHALLENGE, 23, notifyTimeoutMs)
        if (r1Wire.size != 23) throw PairingFlowException.SensorR1WrongSize(r1Wire.size)
        val sensorR1 = r1Wire.copyOfRange(0, 16)
        val nonce7 = r1Wire.copyOfRange(16, 23)
        log("preamble complete R1=${sensorR1.redacted()} nonce7=${nonce7.redacted()}")
        return Preamble(
            sensorR1,
            nonce7,
            certificateEphemeral.sensorCert,
            certificateEphemeral.sensorCertSigningKeyIndex,
            certificateEphemeral.sensorEphemeralPub65,
            certificateEphemeral.phoneEphemeralPub65,
        )
    }

    suspend fun runCommandGatedAuthorizationHandshake(
        tail4: ByteArray,
        phase5RawKey: ByteArray,
        r2Provider: () -> ByteArray = ::defaultR2,
        commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        notifyTimeoutMs: Long = DEFAULT_NOTIFY_TIMEOUT_MS,
    ): AuthorizationResult {
        require(tail4.size == 4) { throw PairingFlowException.Tail4WrongSize(tail4.size) }
        log("authorization handshake start tail4=${tail4.toHex()}")
        val preamble = runCommandGatedAuthorizationPreamble(commandTimeoutMs, notifyTimeoutMs)
        return finishPhase5And6(preamble, tail4, phase5RawKey, r2Provider, commandTimeoutMs, notifyTimeoutMs)
    }

    /**
     * Full first-pair handshake that derives the Phase 5 key ON-DEVICE (like iOS): sends the
     * derived `process2(5)` phone ephemeral from [ephemeral] (precomputed via
     * [SessionKey.makeFirstPairNativeEphemeral], so the slow null-scalar search runs OUTSIDE this
     * window), then mixes the sensor's ephemeral + static points with the same entropy to produce
     * the Phase 5 raw key. No imported `phase5RawKey` needed.
     */
    suspend fun runCommandGatedFirstPairHandshake(
        tail4: ByteArray,
        ephemeral: SessionKey.FirstPairNativeEphemeral,
        entrySource: ByteArray = SessionKey.bundledEntrySource,
        staticScalarWindow: ByteArray? = null,
        r2Provider: () -> ByteArray = ::defaultR2,
        commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        notifyTimeoutMs: Long = DEFAULT_NOTIFY_TIMEOUT_MS,
    ): AuthorizationResult {
        require(tail4.size == 4) { throw PairingFlowException.Tail4WrongSize(tail4.size) }
        log("first-pair handshake start (on-device derived key) tail4=${tail4.toHex()}")
        val certificateEphemeral = runCommandGatedCertificateEphemeralPreamble(
            commandTimeoutMs,
            notifyTimeoutMs,
            ephemeral.phoneEphemeralPub65,
        )
        val effectiveStaticScalarWindow = staticScalarWindow ?: phoneCert?.phase5StaticScalarWindowOverride
        val deriveStarted = System.nanoTime()
        val deriveWallStarted = System.currentTimeMillis()
        log("derive phase5 rawKey before StartAuthorization")
        val material = withContext(Dispatchers.Default) {
            SessionKey.deriveFirstPairPhase5MaterialParallel(
                sensorEphemeralPub65 = certificateEphemeral.sensorEphemeralPub65,
                sensorStaticPub65 = certificateEphemeral.sensorCert.staticPub,
                nullEntropy11A = ephemeral.nullEntropy11A,
                nullScalarWindow = ephemeral.nullScalarWindow,
                entrySource = entrySource,
                staticScalarWindow = effectiveStaticScalarWindow,
                nullAttempts = ephemeral.attempts,
            )
        }
        val deriveMs = (System.nanoTime() - deriveStarted) / 1_000_000
        val deriveWallMs = System.currentTimeMillis() - deriveWallStarted
        log(
            "derived phase5 rawKey on-device " +
                "(source66=${material.source66.size}B key=${material.rawKey.size}B " +
                "staticScalarOverride=${effectiveStaticScalarWindow?.size ?: 0}B " +
                "deriveMs=${deriveMs} wallMs=${deriveWallMs})"
        )
        val preamble = runStartAuthorizationPreamble(certificateEphemeral, commandTimeoutMs, notifyTimeoutMs)
        return finishPhase5And6(preamble, tail4, material.rawKey, r2Provider, commandTimeoutMs, notifyTimeoutMs)
    }

    // ---- cached / direct reconnect (StartAuthorization → Phase 5/6) ----

    suspend fun runCachedReconnectPreamble(
        commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        notifyTimeoutMs: Long = DEFAULT_NOTIFY_TIMEOUT_MS,
    ): Preamble {
        val cmd = commandTransport()
        log("cached reconnect preamble start")
        log("send StartAuthorization 0x11")
        withWriteTimeout("StartAuthorization") { cmd.writeCommand(0x11) }
        waitFor(cmd, byteArrayOf(0x08), "ChallengeLoadDone", commandTimeoutMs)
        log("await cached reconnect R1 challenge")
        val r1Wire = awaitNotify(BleCharRef.CHALLENGE, 23, notifyTimeoutMs)
        if (r1Wire.size != 23) throw PairingFlowException.SensorR1WrongSize(r1Wire.size)
        val sensorR1 = r1Wire.copyOfRange(0, 16)
        val nonce7 = r1Wire.copyOfRange(16, 23)
        log("cached reconnect preamble complete R1=${sensorR1.redacted()} nonce7=${nonce7.redacted()}")
        return Preamble(sensorR1, nonce7)
    }

    suspend fun runCachedReconnectHandshake(
        tail4: ByteArray,
        phase5RawKey: ByteArray,
        r2Provider: () -> ByteArray = ::defaultR2,
        commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        notifyTimeoutMs: Long = DEFAULT_NOTIFY_TIMEOUT_MS,
    ): AuthorizationResult {
        require(tail4.size == 4) { throw PairingFlowException.Tail4WrongSize(tail4.size) }
        log("cached reconnect handshake start tail4=${tail4.toHex()}")
        val preamble = runCachedReconnectPreamble(commandTimeoutMs, notifyTimeoutMs)
        return finishPhase5And6(preamble, tail4, phase5RawKey, r2Provider, commandTimeoutMs, notifyTimeoutMs)
    }

    // ---- shared Phase 5/6 tail ----

    private suspend fun finishPhase5And6(
        preamble: Preamble,
        tail4: ByteArray,
        phase5RawKey: ByteArray,
        r2Provider: () -> ByteArray,
        commandTimeoutMs: Long,
        notifyTimeoutMs: Long,
    ): AuthorizationResult {
        val r2 = r2Provider()
        if (r2.size != 16) throw ChallengeException.WrongPlaintextSize(preamble.sensorR1.size + r2.size + tail4.size)
        if (phase5RawKey.size != 16) throw ChallengeException.WrongKeySize(phase5RawKey.size)
        log("generated R2=${r2.redacted()}")

        val block = LibAes.phase5BlockEncryptor(phase5RawKey)
        val plaintext = preamble.sensorR1 + r2 + tail4
        val phase5 = Phase5Challenge.encrypt(plaintext, block, preamble.nonce7)
        val wire = phase5.logicalBytes // 40-byte logical (official Trident wire shape)
        log("send Phase 5 len=${wire.size} data=${wire.toHex()}")
        withWriteTimeout("authorizationPhase5Write") { transport.write(wire, BleCharRef.CHALLENGE) }

        sendChallengeLoadDoneIfAvailable(commandTimeoutMs)

        log("await Phase 6")
        val phase6Raw = awaitNotify(BleCharRef.CHALLENGE, Phase6Response.WIRE_SIZE, notifyTimeoutMs)
        val phase6 = Phase6Response.decode(phase6Raw)
        val material = phase6.decrypt(block)
        if (!material.phoneR2.contentEquals(r2)) {
            throw PairingFlowException.Phase6VerificationFailed("Phase 6 R2 echo mismatch")
        }
        if (!material.sensorR1.contentEquals(preamble.sensorR1)) {
            throw PairingFlowException.Phase6VerificationFailed("Phase 6 R1 echo mismatch")
        }
        log("Phase 6 verified; kEnc=${material.kEnc.redacted()} ivEnc=${material.ivEnc.redacted()}")
        return AuthorizationResult(preamble, phase5, phase6Raw, phase6, material, phase5RawKey)
    }

    private suspend fun sendChallengeLoadDoneIfAvailable(commandTimeoutMs: Long) {
        val cmd = transport as? CommandPairingTransport ?: return
        log("send SendChallengeLoadDone 0x08")
        cmd.writeCommand(0x08)
        val actual = cmd.awaitCommandResponse(commandTimeoutMs)
        log("got PatchChallengeLoadDone response=${actual.toHex()}")
        if (!actual.startsWith(byteArrayOf(0x08))) {
            throw PairingFlowException.UnexpectedCommandResponse("PatchChallengeLoadDone", byteArrayOf(0x08), actual)
        }
    }

    // ---- helpers ----

    private fun commandTransport(): CommandPairingTransport =
        transport as? CommandPairingTransport ?: throw PairingFlowException.CommandTransportRequired

    private suspend fun waitFor(cmd: CommandPairingTransport, prefix: ByteArray, label: String, timeoutMs: Long) {
        log("wait $label expectedPrefix=${prefix.toHex()}")
        val actual = cmd.awaitCommandResponse(timeoutMs)
        log("got $label response=${actual.toHex()}")
        if (!actual.startsWith(prefix)) {
            throw PairingFlowException.UnexpectedCommandResponse(label, prefix, actual)
        }
    }

    private suspend fun awaitNotify(ref: BleCharRef, exactly: Int, timeoutMs: Long): ByteArray =
        try {
            withTimeout(timeoutMs) { transport.awaitNotify(ref, exactly) }
        } catch (e: TimeoutCancellationException) {
            throw PairingFlowException.NotifyTimeout(ref, timeoutMs)
        }

    private suspend fun <T> withWriteTimeout(label: String, block: suspend () -> T): T =
        try {
            withTimeout(DEFAULT_WRITE_TIMEOUT_MS) { block() }
        } catch (e: TimeoutCancellationException) {
            throw PairingFlowException.WriteTimeout(label, DEFAULT_WRITE_TIMEOUT_MS)
        }

    private fun verifySensorCertificate(cert: SensorCert): Int? {
        if (sensorCertSigningKeys.isEmpty()) {
            log("sensor cert verification skipped")
            return null
        }
        val index = cert.verifiedSigningKeyIndex(sensorCertSigningKeys)
            ?: throw PairingFlowException.SensorCertificateVerificationFailed
        log("sensor cert verified signingKeyIndex=$index")
        return index
    }

    private fun defaultR2(): ByteArray = ByteArray(16).also { secureRandom.nextBytes(it) }

    private fun log(message: String) = logger?.invoke("PairingFlow: $message")

    /**
     * Secrets never reach the log in full: the log ships to the phone / sits in logcat, and the
     * session key material (kEnc/ivEnc) or the KDF inputs (R1/R2/nonce7) would let anyone holding
     * a log + a BLE capture decrypt the glucose stream. A 4-byte prefix keeps runs correlatable.
     */
    private fun ByteArray.redacted(): String = toHex().take(8) + "…"

    companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MS = 2_000L
        const val DEFAULT_NOTIFY_TIMEOUT_MS = 12_000L
        // Covers a full multi-chunk fragmented write (e.g. the 162B phone cert = 9 chunks). On a link
        // with non-zero slave latency (BALANCED/LOW_POWER negotiate latency≈5), each write-with-response
        // chunk waits ~240ms, so the cert needs ~2.2s — the old 2s ceiling aborted it mid-write even
        // though the sensor was still patiently connected. Generous; only bounds failure detection.
        const val DEFAULT_WRITE_TIMEOUT_MS = 8_000L

        private fun padTo(data: ByteArray, length: Int): ByteArray {
            if (data.size >= length) return data
            return data + ByteArray(length - data.size)
        }
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
}

sealed class PairingFlowException(message: String) : Exception(message) {
    object CommandTransportRequired : PairingFlowException("command transport required")
    object PhoneCertRequired : PairingFlowException("phone cert required")
    object SensorCertificateVerificationFailed : PairingFlowException("sensor certificate verification failed")
    class SensorR1WrongSize(val size: Int) : PairingFlowException("sensor R1 wrong size $size")
    class Tail4WrongSize(val size: Int) : PairingFlowException("tail4 wrong size $size")
    class Phase6VerificationFailed(val reason: String) : PairingFlowException("phase 6 verification failed: $reason")
    class NotifyTimeout(val ref: BleCharRef, val timeoutMs: Long) :
        PairingFlowException("notify timeout on $ref after ${timeoutMs}ms")
    class WriteTimeout(val label: String, val timeoutMs: Long) :
        PairingFlowException("write timeout on $label after ${timeoutMs}ms")
    class UnexpectedCommandResponse(val label: String, val expectedPrefix: ByteArray, val actual: ByteArray) :
        PairingFlowException("unexpected command response for $label: expected ${expectedPrefix.toHex()} got ${actual.toHex()}")
}
