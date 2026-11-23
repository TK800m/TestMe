package nl.tapo.deurbel.core

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** One part received from the camera's multipart media stream. */
class MediaPart(
    val mimeType: String,
    val sessionId: Int?,
    val sequence: Int?,
    val data: ByteArray,
    val json: JSONObject?,
)

/**
 * Connection to the camera's media port (8800): live view, two-way audio and
 * SD-card downloads. HTTP digest auth with the cloud password, AES-CBC payloads.
 * Protocol as implemented by pytapo / go2rtc.
 */
class MediaSession(
    private val host: String,
    private val cloudPassword: String,
    private val preferSha256: Boolean,
    private val port: Int = 8800,
) : Closeable {
    private val clientBoundary = "--client-stream-boundary--"
    private var deviceBoundary = "--device-stream-boundary--".toByteArray()
    private lateinit var socket: Socket
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null
    @Volatile var closed = false
        private set
    private var windowSize = 500
    private val parts = LinkedBlockingQueue<Any>(2048)
    private val writeLock = Any()

    fun start(windowSize: Int = 500) {
        this.windowSize = windowSize
        try {
            connectAndAuth(if (preferSha256) "sha" else "md5")
        } catch (e: HttpStatus) {
            if (e.code != 401) throw e
            closeQuietly()
            connectAndAuth(if (preferSha256) "md5" else "sha")
        }
        Thread({ readLoop() }, "tapo-media").apply { isDaemon = true; start() }
    }

    private class HttpStatus(val code: Int) : IOException("Media-poort antwoordde met HTTP $code")

    private fun connectAndAuth(firstHash: String) {
        socket = Socket()
        socket.connect(InetSocketAddress(host, port), 8000)
        socket.soTimeout = 15000
        socket.tcpNoDelay = true
        input = BufferedInputStream(socket.getInputStream(), 65536)
        output = socket.getOutputStream()
        val baseHeaders = linkedMapOf(
            "Content-Type" to "multipart/mixed;boundary=$clientBoundary",
            "Connection" to "keep-alive",
            "Content-Length" to "-1",
        )
        writeHead("POST /stream HTTP/1.1", baseHeaders)
        val (status1, h1) = readHttpHead()
        if (status1 != 401) throw HttpStatus(status1)
        val wwwAuth = h1["WWW-Authenticate"] ?: throw IOException("Geen authenticatie-uitdaging")
        val auth = parseAuth(wwwAuth.substringAfter(' '))
        val hash = if (auth["encrypt_type"] == "3" && firstHash == "sha") "sha" else firstHash
        val hashed = if (hash == "sha") Hashes.sha256HexUpper(cloudPassword) else Hashes.md5HexUpper(cloudPassword)
        val realm = auth["realm"] ?: ""
        val nonce = auth["nonce"] ?: ""
        val cnonce = Hashes.hex(Hashes.randomBytes(24))
        val nc = "00000001"
        val qop = "auth"
        val ha1 = Hashes.md5Hex("admin:$realm:$hashed")
        val ha2 = Hashes.md5Hex("POST:/stream")
        val response = Hashes.md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        val authorization = StringBuilder("Digest username=\"admin\",realm=\"$realm\",uri=\"/stream\",algorithm=MD5,")
            .append("nonce=\"$nonce\",nc=$nc,cnonce=\"$cnonce\",qop=$qop,response=\"$response\"")
        auth["opaque"]?.let { authorization.append(",opaque=\"$it\"") }
        writeHead("POST /stream HTTP/1.1", LinkedHashMap(baseHeaders).apply { put("Authorization", authorization.toString()) })
        val (status2, h2) = readHttpHead()
        if (status2 != 200) throw HttpStatus(status2)
        h2["Content-Type"]?.split(";")?.map { it.trim() }?.firstOrNull { it.startsWith("boundary=") }
            ?.let { deviceBoundary = it.substringAfter("=").toByteArray() }
        val keyExchange = h2["Key-Exchange"] ?: throw IOException("Geen Key-Exchange ontvangen")
        val kx = keyExchange.split(" ").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim().trim('"')
        }.toMap()
        val kxNonce = kx["nonce"] ?: throw IOException("Geen nonce in Key-Exchange")
        val kxUser = kx["username"] ?: "admin"
        aesKey = Hashes.md5("$kxNonce:$hashed".toByteArray())
        aesIv = Hashes.md5("$kxUser:$kxNonce".toByteArray())
    }

    private fun parseAuth(s: String): Map<String, String> = s.split(",").mapNotNull {
        val i = it.indexOf('=')
        if (i < 0) null else it.substring(0, i).trim().replace("\"", "") to it.substring(i + 1).trim().replace("\"", "")
    }.toMap()

    private fun writeHead(line: String, headers: Map<String, String>) {
        val sb = StringBuilder(line).append("\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        synchronized(writeLock) { output.write(sb.toString().toByteArray()); output.flush() }
    }

    private fun readUntil(delim: ByteArray, max: Int = 4_000_000): ByteArray {
        val buf = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("Verbinding gesloten")
            buf.write(b)
            if (b.toByte() == delim[matched]) {
                matched++
                if (matched == delim.size) return buf.toByteArray()
            } else {
                matched = if (b.toByte() == delim[0]) 1 else 0
            }
            if (buf.size() > max) throw IOException("Ongeldig antwoord")
        }
    }

    private fun parseHeaders(block: String): Map<String, String> = block.trim().split("\r\n").mapNotNull {
        val i = it.indexOf(':')
        if (i < 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
    }.toMap()

    private fun readHttpHead(): Pair<Int, Map<String, String>> {
        var head = String(readUntil("\r\n\r\n".toByteArray(), 65536))
        val p = head.indexOf("HTTP/")
        if (p > 0) head = head.substring(p) // some firmware prefixes "HTTP ERROR 401"
        val firstLine = head.substringBefore("\r\n")
        val code = firstLine.split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("Ongeldige statusregel")
        return code to parseHeaders(head.substringAfter("\r\n"))
    }

    private fun readFully(n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw IOException("Verbinding gesloten")
            off += r
        }
        return b
    }

    private fun readLoop() {
        try {
            while (!closed) {
                readUntil(deviceBoundary)
                val headers = parseHeaders(String(readUntil("\r\n\r\n".toByteArray(), 65536)))
                val mime = headers["Content-Type"] ?: ""
                val length = headers["Content-Length"]?.toIntOrNull() ?: 0
                val encrypted = headers["X-If-Encrypt"] == "1"
                var session = headers["X-Session-Id"]?.toIntOrNull()
                var seq = headers["X-Data-Sequence"]?.toIntOrNull()
                val raw = readFully(length)
                val data = if (encrypted) Hashes.aesCbcDecrypt(aesKey!!, aesIv!!, raw) else raw
                var json: JSONObject? = null
                if (mime == "application/json") {
                    json = try { JSONObject(String(data)) } catch (e: Exception) { null }
                    json?.optJSONObject("params")?.opt("session_id")?.toString()?.toIntOrNull()?.let { session = it }
                }
                val dataSeq = seq
                if (dataSeq != null && session != null && dataSeq % windowSize == 0) ack(session!!, dataSeq)
                if (json != null && json.has("seq")) seq = json.optInt("seq")
                parts.put(MediaPart(mime, session, seq, data, json))
            }
        } catch (e: Exception) {
            if (!closed) parts.offer(e)
        } finally {
            closed = true
            parts.offer(EndOfStream)
        }
    }

    private object EndOfStream

    private fun ack(session: Int, seq: Int) {
        val body = """{"type":"notification","params":{"event_type":"stream_sequence"}}""".toByteArray()
        synchronized(writeLock) {
            val head = "--$clientBoundary\r\nX-Session-Id: $session\r\nX-Data-Received: ${windowSize * (seq / windowSize)}\r\n" +
                "Content-Length: ${body.size}\r\n\r\n"
            output.write(head.toByteArray()); output.write(body); output.flush()
        }
    }

    /** Sends a JSON request. Returns the random sequence number used. */
    fun request(json: JSONObject): Int {
        val seq = Random.nextInt(1000, 0x7fff)
        json.put("seq", seq)
        val body = json.toString().toByteArray()
        synchronized(writeLock) {
            val head = "--$clientBoundary\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
                "X-Data-Window-Size: $windowSize\r\n\r\n"
            output.write(head.toByteArray()); output.write(body); output.write("\r\n".toByteArray()); output.flush()
        }
        return seq
    }

    /** Sends an MPEG-TS audio chunk to the doorbell speaker. */
    fun sendAudio(session: Int, data: ByteArray) {
        synchronized(writeLock) {
            val head = "--$clientBoundary\r\nContent-Type: audio/mp2t\r\nX-If-Encrypt: 0\r\nX-Session-Id: $session\r\n" +
                "Content-Length: ${data.size}\r\n\r\n"
            output.write(head.toByteArray()); output.write(data); output.flush()
        }
    }

    /** Next received part, or null on timeout / end of stream. Throws if the connection failed. */
    fun next(timeoutMs: Long): MediaPart? {
        val o = parts.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        return when (o) {
            is MediaPart -> o
            is Exception -> throw o
            else -> { parts.offer(EndOfStream); null }
        }
    }

    /** Sends [json] and waits for the camera's response, returning its session id. */
    fun open(json: JSONObject, timeoutMs: Long = 8000): Int {
        val seq = request(json)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p = next(deadline - System.currentTimeMillis()) ?: if (closed) throw IOException("Verbinding gesloten") else continue
            val j = p.json ?: continue
            val code = j.optJSONObject("params")?.optInt("error_code", 0) ?: j.optInt("error_code", 0)
            if (j.optString("type") == "response" && (p.sequence == seq || p.sessionId != null)) {
                if (code != 0) throw TapoException(code, TapoClient.describe(code))
                return p.sessionId ?: throw IOException("Geen sessie ontvangen")
            }
        }
        throw IOException("Deurbel reageert niet")
    }

    private fun closeQuietly() {
        try { socket.close() } catch (_: Exception) {}
    }

    override fun close() {
        closed = true
        closeQuietly()
    }

    companion object {
        fun previewRequest(hd: Boolean) = JSONObject().put("type", "request").put("seq", 1).put("params",
            JSONObject().put("method", "get").put("preview", JSONObject()
                .put("audio", org.json.JSONArray().put("default"))
                .put("channels", org.json.JSONArray().put(0))
                .put("resolutions", org.json.JSONArray().put(if (hd) "HD" else "VGA"))))

        fun talkRequest() = JSONObject().put("type", "request").put("seq", 1).put("params",
            JSONObject().put("method", "get").put("talk", JSONObject().put("mode", "aec")))

        fun downloadRequest(userId: String, start: Long, end: Long, playerId: String) = JSONObject().put("type", "request")
            .put("seq", 1).put("params", JSONObject().put("method", "get").put("download", JSONObject()
                .put("client_id", userId.toLongOrNull() ?: userId).put("channels", org.json.JSONArray().put(0)).put("media_type", 0)
                .put("start_time", start.toString()).put("end_time", end.toString()).put("player_id", playerId)))

        fun playbackRequest(userId: String, start: Long, end: Long) = JSONObject().put("type", "request")
            .put("seq", 1).put("params", JSONObject().put("method", "get").put("playback", JSONObject()
                .put("client_id", userId.toLongOrNull() ?: userId).put("channels", org.json.JSONArray().put(0).put(1)).put("scale", "1/1")
                .put("start_time", start.toString()).put("end_time", end.toString())
                .put("event_type", org.json.JSONArray().put(1).put(2))))
    }
}
