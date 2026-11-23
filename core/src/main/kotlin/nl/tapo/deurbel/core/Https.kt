package nl.tapo.deurbel.core

import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTPS to the doorbell on the local network. The camera uses a self-signed
 * certificate, so we pin it on first use (trust-on-first-use): the SHA-256 of
 * the certificate is stored and every later connection must present the same one.
 */
class CameraHttps(
    @Volatile var pinnedSha256: String?,
    private val onFirstSeen: (String) -> Unit
) {
    class PinMismatch(msg: String) : Exception(msg)

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw PinMismatch("Geen certificaat")
            val fp = Hashes.hex(Hashes.sha256(cert.encoded))
            val pin = pinnedSha256
            if (pin.isNullOrEmpty()) {
                pinnedSha256 = fp
                onFirstSeen(fp)
            } else if (!pin.equals(fp, ignoreCase = true)) {
                throw PinMismatch("Certificaat van de deurbel is veranderd. Reset de koppeling in Instellingen als dit klopt.")
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val socketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustManager), null) }.socketFactory
    }

    class Response(val code: Int, val body: ByteArray) {
        val text get() = String(body, Charsets.UTF_8)
    }

    fun post(url: String, body: ByteArray, headers: Map<String, String>, timeoutMs: Int = 10_000): Response {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = socketFactory
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true } // certificate is pinned instead
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.useCaches = false
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.setFixedLengthStreamingMode(body.size)
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Response(code, bytes)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        @Suppress("unused")
        private fun unused(c: HttpURLConnection) = c
    }
}
