package nl.tapo.deurbel.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.Signature

class WebPushTest {
    /** Example from RFC 8291, Appendix A. */
    @Test fun rfc8291Vector() {
        val sub = PushSubscription("https://push.example.net/x",
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
            "BTBZMqHH6r4Tts7J_aSIgg")
        val asPriv = WebPush.privateFromRaw(WebPush.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"))
        val asPub = WebPush.publicFromRaw(WebPush.decode("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"))
        val out = WebPush.encrypt("When I grow up, I want to be a watermelon".toByteArray(), sub,
            KeyPair(asPub, asPriv), WebPush.decode("DGv6ra1nlYgDCS1FRnbzlw"))
        assertEquals("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
            WebPush.encode(out))
    }

    @Test fun vapidJwtVerifies() {
        val kp = WebPush.generateKeys()
        val h = WebPush.vapidHeader("https://web.push.apple.com/abc", kp, "mailto:deurbel@example.com")
        val jwt = h.substringAfter("t=").substringBefore(",")
        val parts = jwt.split(".")
        val claims = String(WebPush.decode(parts[1]))
        assertTrue(claims.contains("\"aud\":\"https://web.push.apple.com\""))
        val raw = WebPush.decode(parts[2]); assertEquals(64, raw.size)
        // re-encode r||s to DER and verify
        fun derInt(b: ByteArray): ByteArray { val v = java.math.BigInteger(1, b).toByteArray(); return byteArrayOf(2, v.size.toByte()) + v }
        val seq = derInt(raw.copyOf(32)) + derInt(raw.copyOfRange(32, 64))
        val der = byteArrayOf(0x30, seq.size.toByte()) + seq
        val v = Signature.getInstance("SHA256withECDSA"); v.initVerify(kp.public); v.update("${parts[0]}.${parts[1]}".toByteArray())
        assertTrue(v.verify(der))
    }
}
