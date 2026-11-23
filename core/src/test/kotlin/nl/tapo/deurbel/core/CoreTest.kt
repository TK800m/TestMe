package nl.tapo.deurbel.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class CoreTest {
    private fun hex(s: String) = P256.hex(s)

    @Test fun shaCryptMatchesOpenssl() {
        assertEquals("\$5\$saltstring\$5B8vYYiY.CVt1RlTTf8KbXBH3hsxY/GNooZaBBGWEc5", ShaCrypt.sha256Crypt("Hello world!", "\$5\$saltstring"))
        assertEquals("\$5\$Ab12Cd34\$QhJlX8tdGxihHV2PfjoenGQ7WUZYekKYlNlrTgPLG8D",
            ShaCrypt.sha256Crypt("e10adc3949ba59abbe56e057f20f883e", "\$5\$Ab12Cd34\$"))
        assertEquals("\$5\$rounds=1200\$saltsaltsaltsalt\$SZ7T/lcIaScbEzp02n.OaOhZRFfnuuGKH5NB1TF.NJ8",
            ShaCrypt.sha256Crypt("abc", "\$5\$rounds=1200\$saltsaltsaltsaltXX\$"))
    }

    @Test fun pbkdf2MatchesPython() {
        val r = Hashes.pbkdf2Sha256("cred\$5\$x".toByteArray(), "saltsaltsaltsalt".toByteArray(), 1000, 80)
        assertEquals("9f1e71d830343b84db8500f7266e52bdad4d4ca95fec71bd3a9bb05c61d510f86d5e0e88a832bcc803dd687e3653801c8b1661031ca043329495fc9969be39c2a82a4bb286a03db8018292626dde2fab", Hashes.hex(r))
    }

    @Test fun hkdfRfc5869() {
        val okm = Hashes.hkdfSha256(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex("000102030405060708090a0b0c"),
            hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", Hashes.hex(okm))
    }

    @Test fun p256MatchesJca() {
        val kpg = KeyPairGenerator.getInstance("EC"); kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val priv = (kp.private as java.security.interfaces.ECPrivateKey).s
        val pub = (kp.public as ECPublicKey).w
        val p = P256.mul(priv, P256.G)!!
        assertEquals(pub.affineX, p.x); assertEquals(pub.affineY, p.y)
        // compressed M/N points decode to points on the curve
        for (pt in listOf(P256.M, P256.NP)) {
            val lhs = pt.y.modPow(BigInteger.TWO, P256.P)
            val rhs = pt.x.modPow(BigInteger.valueOf(3), P256.P).add(P256.A.multiply(pt.x)).add(P256.B).mod(P256.P)
            assertEquals(lhs, rhs)
        }
    }

    /** Simulates the doorbell side of SPAKE2+ (RFC 9383) and checks both confirmations. */
    @Test fun spake2pAgreesWithDevice() {
        val credential = "cred-secret"
        val salt = "saltsaltsaltsalt".toByteArray()
        val dk = Hashes.pbkdf2Sha256(credential.toByteArray(), salt, 1000, 80)
        val w0 = BigInteger(1, dk.copyOfRange(0, 40)).mod(P256.N)
        val w1 = BigInteger(1, dk.copyOfRange(40, 80)).mod(P256.N)
        val y = BigInteger("1234567890ABCDEF1234567890ABCDEF", 16)
        val bigY = P256.add(P256.mul(y, P256.G), P256.mul(w0, P256.NP))!!
        val devRandom = ByteArray(32) { 7 }; val userRandom = ByteArray(32) { 9 }
        val b64 = Base64.getEncoder()
        val reg = JSONObject().put("dev_salt", b64.encodeToString(salt)).put("iterations", 1000)
            .put("dev_share", b64.encodeToString(P256.encode(bigY))).put("dev_random", b64.encodeToString(devRandom))
        val c = Spake2pClient(reg, b64.encodeToString(userRandom), credential, BigInteger("987654321987654321"))
        // device side
        val bigX = P256.decode(c.xb)!!
        val z = P256.mul(y, P256.add(bigX, P256.neg(P256.mul(w0, P256.M)))!!)
        val v = P256.mul(y, P256.mul(w1, P256.G)!!)
        val ctx = Hashes.sha256("PAKE V1".toByteArray() + userRandom + devRandom)
        val tr = java.io.ByteArrayOutputStream()
        for (chunk in listOf(ctx, ByteArray(0), ByteArray(0), P256.encode(P256.M), P256.encode(P256.NP), c.xb, P256.encode(bigY),
            P256.encode(z), P256.encode(v), P256.to32(w0))) {
            var l = chunk.size.toLong(); repeat(8) { tr.write((l and 0xff).toInt()); l = l ushr 8 }; tr.write(chunk)
        }
        val ke = Hashes.sha256(tr.toByteArray())
        val conf = Hashes.hkdfSha256(ke, null, "ConfirmationKeys".toByteArray(), 64)
        assertArrayEquals(Hashes.hmacSha256(conf.copyOf(32), P256.encode(bigY)), c.userConfirm)
        assertTrue(c.deviceConfirmOk(Hashes.hmacSha256(conf.copyOfRange(32, 64), c.xb)))
        val (key, nonce) = c.sessionKeyAndNonce()
        assertEquals(16, key.size); assertEquals(12, nonce.size)
    }

    @Test fun extraCryptShadow() {
        val extra = JSONObject().put("type", "password_shadow").put("params", JSONObject().put("passwd_id", 5).put("passwd_prefix", "\$5\$Ab12Cd34\$"))
        assertEquals("\$5\$Ab12Cd34\$QhJlX8tdGxihHV2PfjoenGQ7WUZYekKYlNlrTgPLG8D", Spake2pClient.applyExtraCrypt("e10adc3949ba59abbe56e057f20f883e", extra))
    }

    @Test fun talkMuxerRoundTrip() {
        val m = TalkMuxer()
        val hdr = m.header()
        assertEquals(376, hdr.size)
        // CRC over section+crc must be 0 (MPEG-2 property)
        val patSection = hdr.copyOfRange(5, 5 + 12 + 4)
        assertEquals(0, TalkMuxer.crc32Mpeg(patSection))
        val audio = ByteArray(400) { (it * 3).toByte() }
        val pkts = m.packets(audio)
        assertEquals(0, pkts.size % 188)
        val got = java.io.ByteArrayOutputStream()
        var type = -1
        val d = TsDemuxer { st, _, pts, payload -> type = st; got.write(payload); assertEquals(0L, pts) }
        d.feed(hdr); d.feed(pkts); d.flush()
        assertEquals(0x90, type)
        assertArrayEquals(audio, got.toByteArray())
    }

    @Test fun g711RoundTrip() {
        for (s in listOf(0, 100, -100, 1000, -1000, 20000, -20000, 32767, -32768)) {
            val back = G711.decode(byteArrayOf(G711.encodeAlaw(s.toShort())), false)[0].toInt()
            assertTrue("$s -> $back", kotlin.math.abs(back - s) <= maxOf(16, kotlin.math.abs(s) / 16))
        }
    }

    @Test fun mediaAesKey() {
        // key = md5(nonce:SHA256(pw) upper), iv = md5(user:nonce)
        val hashed = Hashes.sha256HexUpper("cloudpw")
        val key = Hashes.md5("ABCDEF0123:$hashed".toByteArray())
        val iv = Hashes.md5("admin:ABCDEF0123".toByteArray())
        val ct = Hashes.aesCbcEncrypt(key, iv, "hello tapo world".toByteArray())
        assertEquals("hello tapo world", String(Hashes.aesCbcDecrypt(key, iv, ct)))
    }
}
