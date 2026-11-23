package nl.tapo.deurbel.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import nl.tapo.deurbel.core.Recording
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

fun main() {
    val host = System.getenv("TAPO_IP")?.trim().orEmpty()
    val password = System.getenv("TAPO_PASSWORD").orEmpty()
    require(host.isNotEmpty() && password.isNotEmpty()) { "Stel TAPO_IP en TAPO_PASSWORD in (.env)" }
    val dataDir = File(System.getenv("DATA_DIR") ?: "/data").apply { mkdirs() }
    val bind = System.getenv("BIND") ?: "127.0.0.1"
    val port = (System.getenv("PORT") ?: "8080").toInt()
    val go2rtc = System.getenv("GO2RTC_URL") ?: "http://127.0.0.1:1984"

    val store = Store(dataDir)
    store.vapid()
    val bell = Doorbell(host, password, store)
    val media = Media(dataDir, bell, store)
    bell.start()
    Api(store, bell, media, go2rtc).serve(bind, port)
    Log.i("Deurbel-server draait op http://$bind:$port")
}

class HttpError(val code: Int, msg: String) : Exception(msg)

class Api(private val store: Store, private val bell: Doorbell, private val media: Media, private val go2rtc: String) {
    private val proxy = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun serve(bind: String, port: Int) {
        val server = HttpServer.create(InetSocketAddress(bind, port), 64)
        server.executor = Executors.newFixedThreadPool(16)
        server.createContext("/") { ex ->
            try {
                handle(ex)
            } catch (e: HttpError) {
                json(ex, e.code, JSONObject().put("error", e.message))
            } catch (e: Exception) {
                Log.w("${ex.requestMethod} ${ex.requestURI.path}: ${e.message}")
                try { json(ex, 500, JSONObject().put("error", e.message ?: e.javaClass.simpleName)) } catch (_: Exception) {}
            } finally {
                ex.close()
            }
        }
        server.start()
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val m = ex.requestMethod
        val q = query(ex)
        when {
            path == "/api/status" -> json(ex, 200, status())
            path == "/api/settings" && m == "POST" -> {
                val b = body(ex)
                if (b.has("notifications")) store.putBool("notifications", b.getBoolean("notifications"))
                b.optJSONArray("ringTypes")?.let { a -> store.putStringSet("ring_types", (0 until a.length()).map { a.getString(it) }.toSet()) }
                json(ex, 200, status())
            }
            path == "/api/chime" && m == "POST" -> {
                val on = body(ex).getBoolean("on")
                bell.client().setChimeEnabled(on)
                chimeCache = System.currentTimeMillis() to on
                json(ex, 200, JSONObject().put("ok", true))
            }
            path == "/api/days" -> {
                val end = Calendar.getInstance(); val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -90) }
                val days = bell.client().recordingDays(dayFmt.format(start.time), dayFmt.format(end.time)).distinct().sortedDescending()
                json(ex, 200, JSONArray(days))
            }
            path == "/api/clips" -> {
                val c = bell.client()
                val corr = c.timeCorrection()
                val hidden = store.stringSet("hidden")
                val list = c.recordingsOfDay(q["date"] ?: throw HttpError(400, "date ontbreekt"))
                    .filter { it.startTime.toString() !in hidden }.sortedByDescending { it.startTime }
                json(ex, 200, JSONArray(list.map {
                    JSONObject().put("start", it.startTime).put("end", it.endTime).put("time", it.startTime + corr)
                        .put("duration", it.endTime - it.startTime).put("cached", media.cached(it.startTime) != null)
                }))
            }
            path == "/api/clip" && m == "GET" -> {
                val start = q["start"]?.toLongOrNull() ?: throw HttpError(400, "start ontbreekt")
                val end = q["end"]?.toLongOrNull() ?: throw HttpError(400, "end ontbreekt")
                val f = media.clip(Recording(start, end, 0))
                media.trimCache()
                sendFile(ex, f, if (q["download"] == "1") "Deurbel_${q["name"] ?: start}.${f.extension}" else null)
            }
            path == "/api/clip" && m == "DELETE" -> {
                media.deleteClip(q["start"]?.toLongOrNull() ?: throw HttpError(400, "start ontbreekt"))
                json(ex, 200, JSONObject().put("ok", true))
            }
            path == "/api/replies" && m == "GET" -> json(ex, 200, replies())
            path == "/api/replies" && m == "POST" -> {
                val text = body(ex).optString("text").trim().ifEmpty { throw HttpError(400, "Tekst ontbreekt") }
                store.saveReplies(store.replies() + Reply(UUID.randomUUID().toString().take(8), text, false))
                json(ex, 200, replies())
            }
            path.startsWith("/api/replies/") -> reply(ex, path.removePrefix("/api/replies/").split("/"), m)
            path == "/api/push/subscribe" && m == "POST" -> {
                val b = body(ex)
                val keys = b.getJSONObject("keys")
                store.addSubscription(PushSubscription(b.getString("endpoint"), keys.getString("p256dh"), keys.getString("auth")))
                json(ex, 200, JSONObject().put("ok", true))
            }
            path == "/api/push/test" && m == "POST" -> {
                bell.notifyAll(JSONObject().put("title", "Testmelding").put("body", "Meldingen van de deurbel werken!").put("url", "/"))
                json(ex, 200, JSONObject().put("ok", true).put("devices", store.subscriptions().size))
            }
            path.startsWith("/go2rtc/") -> proxyGo2rtc(ex, path.removePrefix("/go2rtc/"))
            path.startsWith("/api/") -> throw HttpError(404, "Onbekend")
            else -> static(ex, path)
        }
    }

    @Volatile private var chimeCache: Pair<Long, Boolean?>? = null

    private fun chimeState(): Boolean? {
        chimeCache?.let { if (System.currentTimeMillis() - it.first < 60_000) return it.second }
        val v = if (System.currentTimeMillis() - bell.lastOk > 30_000) null
        else try { bell.client().chimeRingPlan()?.optString("enabled")?.let { it == "on" } } catch (_: Exception) { null }
        chimeCache = System.currentTimeMillis() to v
        return v
    }

    private fun status(): JSONObject {
        val chime = chimeState()
        val rings = store.rings()
        val recent = JSONArray((maxOf(0, rings.length() - 20) until rings.length()).reversed().map { rings.get(it) })
        return JSONObject()
            .put("status", bell.status).put("connected", System.currentTimeMillis() - bell.lastOk < 30_000)
            .put("notifications", store.getBool("notifications", true))
            .put("chime", chime ?: JSONObject.NULL)
            .put("rings", recent)
            .put("seenTypes", JSONArray(store.stringSet("seen_types").sorted()))
            .put("ringTypes", JSONArray(store.stringSet("ring_types").sorted()))
            .put("vapidKey", WebPush.encode(WebPush.rawPublic(store.vapid().public as java.security.interfaces.ECPublicKey)))
            .put("devices", store.subscriptions().size)
    }

    private fun replies() = JSONArray(store.replies().map {
        JSONObject().put("id", it.id).put("text", it.text).put("useVoice", it.useVoice).put("hasVoice", media.hasVoice(it.id))
    })

    private fun reply(ex: HttpExchange, parts: List<String>, m: String) {
        val id = parts[0]
        val r = store.replies().firstOrNull { it.id == id } ?: throw HttpError(404, "Antwoord niet gevonden")
        when {
            parts.size == 1 && m == "PUT" -> {
                val b = body(ex)
                val updated = r.copy(text = b.optString("text", r.text).trim().ifEmpty { r.text },
                    useVoice = if (b.has("useVoice")) b.getBoolean("useVoice") else r.useVoice)
                store.saveReplies(store.replies().map { if (it.id == id) updated else it })
                json(ex, 200, replies())
            }
            parts.size == 1 && m == "DELETE" -> {
                store.saveReplies(store.replies().filter { it.id != id })
                media.voiceFile(id).delete()
                json(ex, 200, replies())
            }
            parts.getOrNull(1) == "play" && m == "POST" -> {
                media.playAtDoor(r)
                json(ex, 200, JSONObject().put("ok", true))
            }
            parts.getOrNull(1) == "preview.wav" -> {
                val wav = media.preview(r)
                ex.responseHeaders.add("Content-Type", "audio/wav")
                ex.sendResponseHeaders(200, wav.size.toLong())
                ex.responseBody.use { it.write(wav) }
            }
            parts.getOrNull(1) == "voice" && m == "POST" -> {
                val data = ex.requestBody.readBytes()
                if (data.size < 1000) throw HttpError(400, "Opname is te kort")
                media.saveVoice(id, data)
                store.saveReplies(store.replies().map { if (it.id == id) it.copy(useVoice = true) else it })
                json(ex, 200, replies())
            }
            parts.getOrNull(1) == "voice" && m == "DELETE" -> {
                media.voiceFile(id).delete()
                store.saveReplies(store.replies().map { if (it.id == id) it.copy(useVoice = false) else it })
                json(ex, 200, replies())
            }
            else -> throw HttpError(404, "Onbekend")
        }
    }

    // ---------------- helpers ----------------
    private fun query(ex: HttpExchange): Map<String, String> = (ex.requestURI.rawQuery ?: "").split("&").filter { it.contains("=") }
        .associate { URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }

    private fun body(ex: HttpExchange): JSONObject {
        val t = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        return if (t.isBlank()) JSONObject() else JSONObject(t)
    }

    private fun json(ex: HttpExchange, code: Int, o: Any) {
        val b = o.toString().toByteArray()
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    /** Serves a file with HTTP range support (needed for video on iPhone). */
    private fun sendFile(ex: HttpExchange, f: File, downloadName: String?) {
        val type = if (f.extension == "mp4") "video/mp4" else "video/mp2t"
        val len = f.length()
        var start = 0L; var end = len - 1; var code = 200
        ex.requestHeaders.getFirst("Range")?.let { r ->
            val m = Regex("bytes=(\\d*)-(\\d*)").find(r)
            if (m != null) {
                val (a, b) = m.destructured
                if (a.isEmpty()) { start = maxOf(0, len - b.toLong()) } else { start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), len - 1) }
                code = 206
            }
        }
        ex.responseHeaders.set("Content-Type", type)
        ex.responseHeaders.set("Accept-Ranges", "bytes")
        downloadName?.let { ex.responseHeaders.set("Content-Disposition", "attachment; filename=\"$it\"") }
        if (code == 206) ex.responseHeaders.set("Content-Range", "bytes $start-$end/$len")
        val count = end - start + 1
        ex.sendResponseHeaders(code, count)
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(65536); var left = count
            ex.responseBody.use { out ->
                while (left > 0) { val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
            }
        }
    }

    private fun proxyGo2rtc(ex: HttpExchange, rest: String) {
        if (!rest.matches(Regex("[a-z0-9_-]+\\.js"))) throw HttpError(404, "Onbekend")
        val res = proxy.send(HttpRequest.newBuilder(URI("$go2rtc/$rest")).timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofByteArray())
        ex.responseHeaders.set("Content-Type", "text/javascript; charset=utf-8")
        ex.sendResponseHeaders(res.statusCode(), res.body().size.toLong())
        ex.responseBody.use { it.write(res.body()) }
    }

    private val types = mapOf("html" to "text/html; charset=utf-8", "js" to "text/javascript; charset=utf-8",
        "css" to "text/css; charset=utf-8", "png" to "image/png", "webmanifest" to "application/manifest+json", "svg" to "image/svg+xml")

    private fun static(ex: HttpExchange, path: String) {
        val name = if (path == "/" || path.isEmpty()) "index.html" else path.trimStart('/')
        if (name.contains("..")) throw HttpError(404, "Onbekend")
        val bytes = javaClass.getResourceAsStream("/web/$name")?.use { it.readBytes() } ?: throw HttpError(404, "Niet gevonden")
        ex.responseHeaders.set("Content-Type", types[name.substringAfterLast('.')] ?: "application/octet-stream")
        ex.responseHeaders.set("Cache-Control", if (name == "sw.js" || name == "index.html") "no-cache" else "max-age=300")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
