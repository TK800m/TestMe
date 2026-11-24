package nl.tapo.deurbel.core

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.Base64

class TapoException(val code: Int?, msg: String) : Exception(msg)

/** Encrypted request channel to the camera's control API (port 443). */
interface Transport {
    /** Sends [request] and returns the decrypted JSON reply. */
    fun send(request: JSONObject): JSONObject
    fun close()
    /** Password hash the media port (8800) should try first. */
    val preferSha256: Boolean
}

private fun headers(host: String, contentType: String) = mapOf(
    "Referer" to "https://$host",
    "Accept" to contentType,
    "User-Agent" to "Tapo CameraClient Android",
    "requestByApp" to "true",
    "Content-Type" to contentType,
)

/** Login used by firmware with the "encrypt_type 3" (securePassthrough) channel. */
class LegacyTransport(
    private val host: String,
    private val password: String,
    private val https: CameraHttps,
    private val user: String = "admin",
) : Transport {
    private val md5Pw = Hashes.md5HexUpper(password)
    private val shaPw = Hashes.sha256HexUpper(password)
    private var stok: String? = null
    private var secure: Boolean? = null
    private var useSha = false
    private var cnonce = ""
    private var lsk: ByteArray? = null
    private var ivb: ByteArray? = null
    private var seq: Long? = null

    override val preferSha256 get() = useSha

    private val baseUrl get() = "https://$host"
    private fun hashed() = if (useSha) shaPw else md5Pw

    private fun post(url: String, json: String, extra: Map<String, String> = emptyMap()): JSONObject {
        val r = https.post(url, json.toByteArray(), headers(host, "application/json") + extra)
        return try { JSONObject(r.text) } catch (e: Exception) { throw TapoException(null, "Ongeldig antwoord (${r.code})") }
    }

    private fun isSecure(): Boolean {
        secure?.let { return it }
        val probe = JSONObject().put("method", "login").put(
            "params", JSONObject().put("encrypt_type", "3").put("username", user).put("cnonce", Hashes.nonceHex(16))
        )
        val res = post(baseUrl, probe.toString())
        val enc = res.optJSONObject("result")?.optJSONObject("data")?.optJSONArray("encrypt_type")
        val s = res.optInt("error_code") == -40413 && enc != null && (0 until enc.length()).any { enc.optString(it) == "3" }
        secure = s
        return s
    }

    @Synchronized
    private fun login() {
        cnonce = Hashes.nonceHex(16)
        if (isSecure()) {
            val first = post(baseUrl, JSONObject().put("method", "login").put(
                "params", JSONObject().put("cnonce", cnonce).put("encrypt_type", "3").put("username", user)
            ).toString())
            val data = first.optJSONObject("result")?.optJSONObject("data")
                ?: throw TapoException(first.optInt("error_code"), "Inloggen mislukt")
            val nonce = data.getString("nonce")
            val confirm = data.getString("device_confirm")
            val withSha = Hashes.sha256HexUpper(cnonce + shaPw + nonce) + nonce + cnonce
            val withMd5 = Hashes.sha256HexUpper(cnonce + md5Pw + nonce) + nonce + cnonce
            useSha = when (confirm) {
                withSha -> true
                withMd5 -> false
                else -> throw TapoException(-40411, "Onjuist wachtwoord (TP-Link account wachtwoord)")
            }
            val digest = Hashes.sha256HexUpper(hashed() + cnonce + nonce)
            val second = post(baseUrl, JSONObject().put("method", "login").put(
                "params", JSONObject().put("cnonce", cnonce).put("encrypt_type", "3")
                    .put("digest_passwd", digest + cnonce + nonce).put("username", user)
            ).toString())
            checkLockout(second)
            val result = second.optJSONObject("result") ?: throw TapoException(second.optInt("error_code"), "Inloggen mislukt")
            if (result.has("start_seq")) {
                lsk = token("lsk", nonce)
                ivb = token("ivb", nonce)
                seq = result.getLong("start_seq")
            }
            stok = result.optString("stok").ifEmpty { throw TapoException(second.optInt("error_code"), "Geen sessie ontvangen") }
        } else {
            useSha = false
            val res = post(baseUrl, JSONObject().put("method", "login").put(
                "params", JSONObject().put("hashed", true).put("password", md5Pw).put("username", user)
            ).toString())
            checkLockout(res)
            stok = res.optJSONObject("result")?.optString("stok")?.ifEmpty { null }
                ?: throw TapoException(res.optInt("error_code"), "Inloggen mislukt (${res.optInt("error_code")})")
        }
    }

    private fun checkLockout(res: JSONObject) {
        val d = res.optJSONObject("result")?.optJSONObject("data") ?: res.optJSONObject("data")
        val left = d?.optInt("sec_left") ?: 0
        if (left > 0) throw TapoException(-40404, "Deurbel tijdelijk geblokkeerd, probeer over $left seconden opnieuw")
    }

    private fun token(type: String, nonce: String): ByteArray {
        val hashedKey = Hashes.sha256HexUpper(cnonce + hashed() + nonce)
        return Hashes.sha256((type + cnonce + nonce + hashedKey).toByteArray()).copyOf(16)
    }

    @Synchronized
    override fun send(request: JSONObject): JSONObject {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                if (stok == null) login()
                val url = "$baseUrl/stok=$stok/ds"
                val res: JSONObject
                val s = seq
                if (s != null && lsk != null) {
                    val enc = Hashes.aesCbcEncrypt(lsk!!, ivb!!, request.toString().toByteArray())
                    val full = JSONObject().put("method", "securePassthrough")
                        .put("params", JSONObject().put("request", Base64.getEncoder().encodeToString(enc))).toString()
                    val tag1 = Hashes.sha256HexUpper(hashed() + cnonce)
                    val tag = Hashes.sha256HexUpper(tag1 + full + s)
                    seq = s + 1
                    val raw = post(url, full, mapOf("Seq" to s.toString(), "Tapo_tag" to tag))
                    val encResp = raw.optJSONObject("result")?.optString("response")
                    res = if (!encResp.isNullOrEmpty()) {
                        JSONObject(String(Hashes.aesCbcDecrypt(lsk!!, ivb!!, Base64.getDecoder().decode(encResp))))
                    } else raw
                } else {
                    res = post(url, request.toString())
                }
                val code = res.optInt("error_code", 0)
                if (code == -40401 || code == -1 || code == -40413) {
                    stok = null; seq = null
                    throw TapoException(code, "Sessie verlopen")
                }
                return res
            } catch (e: CameraHttps.PinMismatch) {
                throw e
            } catch (e: TapoException) {
                if (e.code == -40411 || e.code == -40404) throw e
                lastError = e
                stok = null; seq = null
                if (attempt == 0) Thread.sleep(500)
            } catch (e: Exception) {
                lastError = e
                stok = null; seq = null
                if (attempt == 0) Thread.sleep(500)
            }
        }
        throw lastError ?: TapoException(null, "Onbekende fout")
    }

    override fun close() { stok = null; seq = null }
}

/** SPAKE2+ login and AES-128-CCM channel ("TPAP") of recent Tapo firmware. */
class TpapTransport(
    private val host: String,
    private val password: String,
    private val https: CameraHttps,
    private var tpapInfo: JSONObject?,
) : Transport {
    private var stok: String? = null
    private var key: ByteArray? = null
    private var nonce: ByteArray? = null
    private var seq = 0L
    private var expiresAt = 0L
    private var passcodeIndex: Int? = null

    override val preferSha256 = true
    private val baseUrl get() = "https://$host"

    private fun postLogin(params: JSONObject): JSONObject {
        val body = JSONObject().put("method", "login").put("params", params).toString()
        val r = https.post("$baseUrl/", body.toByteArray(), headers(host, "application/json"))
        return JSONObject(r.text)
    }

    private fun username() = if (tpapInfo?.optInt("user_hash_type") == 1) Hashes.sha256HexUpper("admin") else Hashes.md5Hex("admin")

    private fun loggedIn() = stok != null && System.currentTimeMillis() < expiresAt

    private fun login() {
        val passcodes = listOf(Hashes.md5Hex(password), Hashes.sha256HexUpper(password))
        val order = passcodeIndex?.let { listOf(it, it) } ?: passcodes.indices.toList()
        var last: Exception? = null
        for ((n, idx) in order.withIndex()) {
            if (n > 0 && idx == order[n - 1]) Thread.sleep(2000)
            try {
                loginWith(passcodes[idx]); passcodeIndex = idx; return
            } catch (e: TapoException) {
                last = e
                if (e.code != -40401) throw e
            }
        }
        throw TapoException(-40411, "Onjuist wachtwoord (TP-Link account wachtwoord)").also { it.initCause(last) }
    }

    private fun loginWith(passcode: String) {
        val userRandom = Base64.getEncoder().encodeToString(Hashes.randomBytes(32))
        val reg = postLogin(JSONObject().put("sub_method", "pake_register").put("username", username())
            .put("user_random", userRandom).put("cipher_suites", JSONArray().put(1))
            .put("encryption", JSONArray().put("aes_128_ccm")).put("passcode_type", "userpw"))
        val result = reg.optJSONObject("result") ?: throw TapoException(reg.optInt("error_code"), "pake_register mislukt")
        val credential = Spake2pClient.applyExtraCrypt(passcode, result.optJSONObject("extra_crypt"))
        val client = Spake2pClient(result, userRandom, credential, BigInteger(1, Hashes.randomBytes(32)))
        val enc = Base64.getEncoder()
        val share = postLogin(JSONObject().put("sub_method", "pake_share")
            .put("user_share", enc.encodeToString(client.xb)).put("user_confirm", enc.encodeToString(client.userConfirm)))
        val sr = share.optJSONObject("result")
        if (sr == null) {
            val left = listOf(share.optJSONObject("error_info"), share.optJSONObject("data"))
                .mapNotNull { it?.optInt("sec_left") }.maxOrNull() ?: 0
            if (left > 0) throw TapoException(-40404, "Deurbel tijdelijk geblokkeerd, probeer over $left seconden opnieuw")
            throw TapoException(share.optInt("error_code"), "pake_share mislukt (${share.optInt("error_code")})")
        }
        if (!client.deviceConfirmOk(Base64.getDecoder().decode(sr.getString("dev_confirm"))))
            throw TapoException(null, "Deurbel kon niet worden geverifieerd")
        val (k, n) = client.sessionKeyAndNonce()
        key = k; nonce = n
        stok = sr.getString("stok")
        seq = sr.get("start_seq").toString().toLong()
        expiresAt = System.currentTimeMillis() + (sr.optString("expired", "3600").toLongOrNull() ?: 3600L) * 1000
    }

    private fun nonceFor(s: Long): ByteArray =
        nonce!!.copyOf(8) + ByteBuffer.allocate(4).putInt((s and 0xffffffffL).toInt()).array()

    private fun ccm(encrypt: Boolean, s: Long, data: ByteArray): ByteArray {
        val c = CCMBlockCipher.newInstance(AESEngine.newInstance())
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonceFor(s)))
        val out = ByteArray(c.getOutputSize(data.size))
        val len = c.processBytes(data, 0, data.size, out, 0)
        val fin = c.doFinal(out, len)
        return out.copyOf(len + fin)
    }

    private fun roundTrip(payload: ByteArray): JSONObject {
        val s = seq++
        val body = ByteBuffer.allocate(4).putInt((s and 0xffffffffL).toInt()).array() + ccm(true, s, payload)
        val r = https.post("$baseUrl/stok=$stok/ds", body, headers(host, "application/octet-stream"))
        val raw = r.body
        if (raw.isNotEmpty() && raw[0] == '{'.code.toByte()) {
            val code = try { JSONObject(String(raw)).optInt("error_code") } catch (e: Exception) { null }
            throw TapoException(code, "Verzoek geweigerd ($code)")
        }
        if (raw.size < 20) throw TapoException(null, "Kort antwoord (${raw.size} bytes, HTTP ${r.code})")
        val replySeq = ByteBuffer.wrap(raw, 0, 4).int.toLong() and 0xffffffffL
        val plain = try { ccm(false, replySeq, raw.copyOfRange(4, raw.size)) } catch (e: Exception) {
            throw TapoException(null, "Antwoord kon niet worden ontsleuteld")
        }
        return JSONObject(String(plain))
    }

    @Synchronized
    override fun send(request: JSONObject): JSONObject {
        // this channel only accepts multipleRequest (as the Tapo app sends it)
        val wrapped = if (request.optString("method") == "multipleRequest") request
        else JSONObject().put("method", "multipleRequest").put("params", JSONObject().put("requests", JSONArray().put(request)))
        val payload = wrapped.toString().toByteArray()
        for (attempt in 0..1) {
            try {
                if (!loggedIn()) login()
                return roundTrip(payload)
            } catch (e: TapoException) {
                stok = null
                if (attempt == 1 || (e.code != -40401 && e.code != -40421)) throw e
            } catch (e: java.io.IOException) {
                stok = null
                if (attempt == 1) throw e
            }
        }
        throw TapoException(null, "Onbekende fout")
    }

    override fun close() { stok = null }

    companion object {
        /** Asks the camera which login it offers. */
        fun discover(host: String, https: CameraHttps): JSONObject? = try {
            val body = """{"method":"login","params":{"sub_method":"discover"}}"""
            JSONObject(https.post("https://$host/", body.toByteArray(), headers(host, "application/json")).text)
        } catch (e: CameraHttps.PinMismatch) { throw e } catch (e: Exception) { null }

        fun tpapFromDiscover(res: JSONObject?): JSONObject? {
            val t = res?.optJSONObject("result")?.optJSONObject("tpap") ?: return null
            val pake = t.optJSONArray("pake") ?: return null
            return if ((0 until pake.length()).any { pake.optInt(it) == 2 }) t else null
        }

        fun legacyOffered(res: JSONObject?): Boolean {
            if (res == null || res.optInt("error_code") != -40413) return false
            val enc = res.optJSONObject("result")?.optJSONObject("data")?.optJSONArray("encrypt_type") ?: return false
            return (0 until enc.length()).any { enc.optString(it) == "3" }
        }

        fun rejectsLegacy(host: String, https: CameraHttps): Boolean = try {
            val body = JSONObject().put("method", "login").put("params", JSONObject().put("encrypt_type", "3")
                .put("username", "admin").put("cnonce", Hashes.nonceHex(16))).toString()
            JSONObject(https.post("https://$host/", body.toByteArray(), headers(host, "application/json")).text).optInt("error_code") == -40211
        } catch (e: Exception) { false }
    }
}
