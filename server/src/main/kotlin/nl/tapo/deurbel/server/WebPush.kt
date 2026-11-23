package nl.tapo.deurbel.server

import nl.tapo.deurbel.core.Hashes
import org.json.JSONObject
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Duration
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A browser push subscription (from PushManager.subscribe). */
data class PushSubscription(val endpoint: String, val p256dh: String, val auth: String)

/**
 * Web Push (RFC 8030) with VAPID (RFC 8292) and aes128gcm payload encryption (RFC 8291).
 * Works for Safari on iPhone (iOS 16.4+, web app on home screen) and Chrome/Firefox on Android.
 */
object WebPush {
    private val b64u = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun decode(s: String): ByteArray = b64d.decode(s.trim().replace('+', '-').replace('/', '_').trimEnd('='))
    fun encode(b: ByteArray): String = b64u.encodeToString(b)

    fun generateKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun rawPublic(k: ECPublicKey): ByteArray = byteArrayOf(4) + to32(k.w.affineX) + to32(k.w.affineY)
    fun rawPrivate(k: ECPrivateKey): ByteArray = to32(k.s)

    fun publicFromRaw(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0].toInt() == 4) { "Ongeldige publieke sleutel" }
        val p = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(p, params)) as ECPublicKey
    }

    fun privateFromRaw(raw: ByteArray): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, raw), params)) as ECPrivateKey

    private fun to32(v: BigInteger): ByteArray {
        val r = v.toByteArray()
        return when {
            r.size == 32 -> r
            r.size > 32 -> r.copyOfRange(r.size - 32, r.size)
            else -> ByteArray(32 - r.size) + r
        }
    }

    /** RFC 8291 encryption. [senderKeys] and [salt] are only fixed in tests. */
    fun encrypt(
        plaintext: ByteArray, sub: PushSubscription,
        senderKeys: KeyPair = generateKeys(), salt: ByteArray = Hashes.randomBytes(16),
    ): ByteArray {
        val uaPublicRaw = decode(sub.p256dh)
        val authSecret = decode(sub.auth)
        val asPublicRaw = rawPublic(senderKeys.public as ECPublicKey)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(senderKeys.private)
        ka.doPhase(publicFromRaw(uaPublicRaw), true)
        val ecdh = ka.generateSecret()
        val keyInfo = "WebPush: info".toByteArray() + byteArrayOf(0) + uaPublicRaw + asPublicRaw
        val ikm = Hashes.hkdfSha256(ecdh, authSecret, keyInfo, 32)
        val cek = Hashes.hkdfSha256(ikm, salt, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0), 16)
        val nonce = Hashes.hkdfSha256(ikm, salt, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0), 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val ct = c.doFinal(plaintext + byteArrayOf(2))
        val header = ByteBuffer.allocate(16 + 4 + 1 + asPublicRaw.size)
            .put(salt).putInt(4096).put(asPublicRaw.size.toByte()).put(asPublicRaw).array()
        return header + ct
    }

    /** VAPID JWT (ES256) for the push service of [endpoint]. */
    fun vapidHeader(endpoint: String, vapid: KeyPair, subject: String): String {
        val u = URI(endpoint)
        val aud = "${u.scheme}://${u.host}" + if (u.port > 0) ":${u.port}" else ""
        val header = encode("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val claims = encode(JSONObject().put("aud", aud).put("exp", System.currentTimeMillis() / 1000 + 12 * 3600)
            .put("sub", subject).toString().toByteArray())
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(vapid.private)
        signer.update("$header.$claims".toByteArray())
        val jwt = "$header.$claims.${encode(derToRaw(signer.sign()))}"
        return "vapid t=$jwt, k=${encode(rawPublic(vapid.public as ECPublicKey))}"
    }

    /** Converts a DER ECDSA signature to the 64-byte r||s form JWT needs. */
    fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
        require(der[i].toInt() == 2); val rLen = der[i + 1].toInt(); val r = BigInteger(1, der.copyOfRange(i + 2, i + 2 + rLen))
        i += 2 + rLen
        require(der[i].toInt() == 2); val sLen = der[i + 1].toInt(); val s = BigInteger(1, der.copyOfRange(i + 2, i + 2 + sLen))
        return to32(r) + to32(s)
    }

    /** Sends a notification. Returns the HTTP status (404/410 = subscription is gone). */
    fun send(sub: PushSubscription, payload: JSONObject, vapid: KeyPair, subject: String, ttl: Int = 120): Int {
        val body = encrypt(payload.toString().toByteArray(), sub)
        val req = HttpRequest.newBuilder(URI(sub.endpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Encoding", "aes128gcm")
            .header("Content-Type", "application/octet-stream")
            .header("TTL", ttl.toString())
            .header("Urgency", "high")
            .header("Authorization", vapidHeader(sub.endpoint, vapid, subject))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }
}
