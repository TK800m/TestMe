package nl.tapo.deurbel.core

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.Base64

/**
 * SPAKE2+ (P-256) client used by the "TPAP" login of newer Tapo firmware.
 * Ported from pytapo (transport/tpap/spake2p.py, MIT licence).
 */
object P256 {
    // declared first: used while initialising M and NP below
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    val A: BigInteger = P.subtract(BigInteger.valueOf(3))
    val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    val N = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    val G = Point(
        BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16),
        BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
    )
    val M = decode(hex("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"))!!
    val NP = decode(hex("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"))!!

    data class Point(val x: BigInteger, val y: BigInteger)

    private fun inv(x: BigInteger) = x.mod(P).modPow(P.subtract(TWO), P)

    fun add(p1: Point?, p2: Point?): Point? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        if (p1.x == p2.x && p1.y.add(p2.y).mod(P).signum() == 0) return null
        val m = if (p1 == p2) {
            THREE.multiply(p1.x).multiply(p1.x).add(A).multiply(inv(TWO.multiply(p1.y))).mod(P)
        } else {
            p2.y.subtract(p1.y).multiply(inv(p2.x.subtract(p1.x))).mod(P)
        }
        val x3 = m.multiply(m).subtract(p1.x).subtract(p2.x).mod(P)
        return Point(x3, m.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(P))
    }

    fun mul(k0: BigInteger, p: Point): Point? {
        var k = k0.mod(N)
        var result: Point? = null
        var addend: Point? = p
        while (k.signum() > 0) {
            if (k.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            k = k.shiftRight(1)
        }
        return result
    }

    fun neg(p: Point?): Point? = p?.let { Point(it.x, it.y.negate().mod(P)) }

    fun decode(b: ByteArray): Point? {
        return when (b[0].toInt()) {
            0x04 -> Point(BigInteger(1, b.copyOfRange(1, 33)), BigInteger(1, b.copyOfRange(33, 65)))
            0x02, 0x03 -> {
                val x = BigInteger(1, b.copyOfRange(1, 33))
                var y = x.modPow(THREE, P).add(A.multiply(x)).add(B).mod(P)
                    .modPow(P.add(BigInteger.ONE).shiftRight(2), P)
                if (y.testBit(0) != ((b[0].toInt() and 1) == 1)) y = P.subtract(y)
                Point(x, y)
            }
            else -> throw IllegalArgumentException("bad point encoding")
        }
    }

    fun to32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    fun encode(p: Point?): ByteArray {
        requireNotNull(p)
        return byteArrayOf(4) + to32(p.x) + to32(p.y)
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

object ShaCrypt {
    private const val B64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val ORDER = arrayOf(
        intArrayOf(0, 10, 20), intArrayOf(21, 1, 11), intArrayOf(12, 22, 2), intArrayOf(3, 13, 23),
        intArrayOf(24, 4, 14), intArrayOf(15, 25, 5), intArrayOf(6, 16, 26), intArrayOf(27, 7, 17),
        intArrayOf(18, 28, 8), intArrayOf(9, 19, 29)
    )

    private fun to64(v0: Int, n: Int): String {
        var v = v0
        val sb = StringBuilder()
        repeat(n) { sb.append(B64[v and 0x3f]); v = v ushr 6 }
        return sb.toString()
    }

    private fun repeat(d: ByteArray, len: Int): ByteArray {
        val out = ByteArrayOutputStream()
        while (out.size() < len) out.write(d)
        return out.toByteArray().copyOf(len)
    }

    private fun sha(vararg parts: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    /** SHA-256-crypt ("$5$") as used by the Tapo app's password_shadow transform. */
    fun sha256Crypt(key: String, prefix: String): String {
        var rest = if (prefix.startsWith("$5$")) prefix.substring(3) else prefix
        var rounds = 5000
        var explicit = false
        if (rest.startsWith("rounds=")) {
            val idx = rest.indexOf('$')
            if (idx >= 0 && idx + 1 <= rest.length && rest.substring(idx + 1).isNotEmpty()) {
                rounds = rest.substring(7, idx).toInt().coerceIn(1000, 999_999_999)
                rest = rest.substring(idx + 1)
                explicit = true
            }
        }
        val end = rest.indexOf('$')
        val salt = rest.substring(0, minOf(if (end > 0) end else rest.length, 16))
        val k = key.toByteArray()
        val s = salt.toByteArray()
        val b = sha(k, s, k)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(k); md.update(s); md.update(repeat(b, k.size))
        var n = k.size
        while (n != 0) {
            md.update(if (n and 1 != 0) b else k)
            n = n ushr 1
        }
        val a = md.digest()
        val kk = ByteArrayOutputStream().apply { repeat(k.size) { write(k) } }.toByteArray()
        val p = repeat(sha(kk), k.size)
        val ss = ByteArrayOutputStream().apply { repeat(16 + (a[0].toInt() and 0xff)) { write(s) } }.toByteArray()
        val sb = repeat(sha(ss), s.size)
        var c = a
        for (i in 0 until rounds) {
            val h = java.security.MessageDigest.getInstance("SHA-256")
            h.update(if (i and 1 != 0) p else c)
            if (i % 3 != 0) h.update(sb)
            if (i % 7 != 0) h.update(p)
            h.update(if (i and 1 != 0) c else p)
            c = h.digest()
        }
        fun u(i: Int) = c[i].toInt() and 0xff
        val enc = StringBuilder()
        for (o in ORDER) enc.append(to64((u(o[0]) shl 16) or (u(o[1]) shl 8) or u(o[2]), 4))
        enc.append(to64((u(31) shl 8) or u(30), 3))
        val roundsPart = if (explicit) "rounds=$rounds$" else ""
        return "$5$$roundsPart$salt$$enc"
    }
}

class Spake2pClient(
    registerResult: org.json.JSONObject,
    userRandomB64: String,
    credential: String,
    randomScalar: BigInteger
) {
    val xb: ByteArray
    val userConfirm: ByteArray
    private val kcb: ByteArray
    private val sharedKey: ByteArray

    init {
        val dec = Base64.getDecoder()
        val devSalt = dec.decode(registerResult.getString("dev_salt"))
        val iterations = registerResult.get("iterations").toString().toInt()
        val dk = Hashes.pbkdf2Sha256(credential.toByteArray(), devSalt, iterations, 80)
        val w0 = BigInteger(1, dk.copyOfRange(0, 40)).mod(P256.N)
        val w1 = BigInteger(1, dk.copyOfRange(40, 80)).mod(P256.N)
        val y = P256.decode(dec.decode(registerResult.getString("dev_share")))!!
        val x = randomScalar.mod(P256.N.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        val bigX = P256.add(P256.mul(x, P256.G), P256.mul(w0, P256.M))
        val h = P256.add(y, P256.neg(P256.mul(w0, P256.NP)))!!
        xb = P256.encode(bigX)
        val yb = P256.encode(y)
        val context = Hashes.sha256(
            "PAKE V1".toByteArray() + dec.decode(userRandomB64) + dec.decode(registerResult.getString("dev_random"))
        )
        val transcript = lenPrefixed(
            context, ByteArray(0), ByteArray(0),
            P256.encode(P256.M), P256.encode(P256.NP), xb, yb,
            P256.encode(P256.mul(x, h)), P256.encode(P256.mul(w1, h)), P256.to32(w0)
        )
        val ke = Hashes.sha256(transcript)
        val conf = Hashes.hkdfSha256(ke, null, "ConfirmationKeys".toByteArray(), 64)
        kcb = conf.copyOfRange(32, 64)
        userConfirm = Hashes.hmacSha256(conf.copyOfRange(0, 32), yb)
        sharedKey = Hashes.hkdfSha256(ke, null, "SharedKey".toByteArray(), 32)
    }

    fun deviceConfirmOk(devConfirm: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(devConfirm, Hashes.hmacSha256(kcb, xb))

    /** AES-128 key and 12-byte base nonce for the CCM channel. */
    fun sessionKeyAndNonce(): Pair<ByteArray, ByteArray> {
        val key = Hashes.hkdfSha256(sharedKey, "tp-kdf-salt-aes128-key".toByteArray(), "tp-kdf-info-aes128-key".toByteArray(), 32).copyOf(16)
        val nonce = Hashes.hkdfSha256(sharedKey, "tp-kdf-salt-aes128-iv".toByteArray(), "tp-kdf-info-aes128-iv".toByteArray(), 32).copyOf(12)
        return key to nonce
    }

    companion object {
        private fun lenPrefixed(vararg chunks: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            for (c in chunks) {
                var l = c.size.toLong()
                repeat(8) { out.write((l and 0xff).toInt()); l = l ushr 8 }
                out.write(c)
            }
            return out.toByteArray()
        }

        /** Turns the passcode into the credential the camera asks for (extra_crypt). */
        fun applyExtraCrypt(passcode: String, extra: org.json.JSONObject?): String {
            if (extra == null || extra.length() == 0) return passcode
            val kind = extra.optString("type").lowercase()
            val params = extra.optJSONObject("params") ?: org.json.JSONObject()
            if (kind == "password_shadow") {
                return when (params.optString("passwd_id", "0").toInt()) {
                    5 -> ShaCrypt.sha256Crypt(passcode, params.optString("passwd_prefix", ""))
                    2 -> Hashes.hex(Hashes.sha1(passcode.toByteArray()))
                    else -> throw IllegalStateException("Onbekende password_shadow ${params.opt("passwd_id")}")
                }
            }
            throw IllegalStateException("Onbekend extra_crypt type $kind")
        }
    }
}
