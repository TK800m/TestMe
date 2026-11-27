package nl.tapo.deurbel.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import nl.tapo.deurbel.core.CameraHttps
import nl.tapo.deurbel.core.Hashes
import nl.tapo.deurbel.core.Recording
import nl.tapo.deurbel.core.TapoClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
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
    val dataDir = File(System.getenv("DATA_DIR") ?: "/data").apply { mkdirs() }
    val bind = System.getenv("BIND") ?: "127.0.0.1"
    val port = (System.getenv("PORT") ?: "8080").toInt()
    val go2rtc = System.getenv("GO2RTC_URL") ?: "http://127.0.0.1:1984"

    val store = Store(dataDir)
    store.vapid()
    val app = App(store, dataDir, go2rtc)
    // older single-device installations
    val envIp = System.getenv("TAPO_IP")?.trim().orEmpty()
    val envPw = System.getenv("TAPO_PASSWORD").orEmpty()
    if (store.getString("devices") == null) {
        val ip = store.getString("tapo_ip") ?: envIp.ifEmpty { null }
        val pw = store.getString("tapo_pw") ?: envPw.ifEmpty { null }
        if (ip != null && pw != null) {
            store.putString("tapo_pw", pw)
            store.putString("devices", JSONArray().put(JSONObject().put("id", "d1").put("name", "Voordeur").put("host", ip)).toString())
        }
    }
    app.startAll()
    Api(store, app, go2rtc).serve(bind, port)
    Log.i("Deurbel-server draait op http://$bind:$port (${app.devices.size} apparaten)")
    Thread({ while (true) { Thread.sleep(3600_000); app.media.values.forEach { it.trimCache() } } }, "cache-trim")
        .apply { isDaemon = true; start() }
}

/** All doorbells and cameras, plus the settings that apply to all of them. */
class App(val store: Store, private val dataDir: File, private val go2rtc: String) {
    val devices = java.util.concurrent.CopyOnWriteArrayList<Device>()
    val media = java.util.concurrent.ConcurrentHashMap<String, Media>()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val pushPool = Executors.newFixedThreadPool(4)
    val password: String? get() = store.getString("tapo_pw")

    private fun deviceList(): List<JSONObject> = store.getString("devices")?.let { JSONArray(it) }
        ?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    private fun saveList(list: List<JSONObject>) = store.putString("devices", JSONArray(list).toString())

    @Synchronized
    fun startAll() {
        devices.forEach { it.stop() }; devices.clear(); media.clear()
        val pw = password ?: return
        deviceList().forEach { o ->
            val d = Device(o.getString("id"), o.optString("name", "Apparaat"), o.getString("host"), pw, this)
            devices.add(d); media[d.id] = Media(dataDir, d); d.start()
            Thread({ ensureGo2rtc(d, pw) }, "go2rtc-${d.id}").apply { isDaemon = true; start() }
        }
    }

    fun device(id: String?): Device =
        (if (id.isNullOrEmpty()) devices.firstOrNull() else devices.firstOrNull { it.id == id })
            ?: throw HttpError(if (devices.isEmpty()) 503 else 404, if (devices.isEmpty()) "Nog geen apparaat gekoppeld" else "Apparaat niet gevonden")

    fun mediaOf(d: Device) = media[d.id]!!

    /** Tests the login and adds the device. The TP-Link password is the same for all devices. */
    fun addDevice(ip: String, pw: String, name: String?): JSONObject {
        if (deviceList().any { it.getString("host") == ip }) throw HttpError(400, "Dit apparaat is al gekoppeld")
        var pin: String? = null
        val c = TapoClient(ip, pw, CameraHttps(null) { pin = it })
        val info = c.deviceInfo()
        val id = "d" + (1 + (deviceList().maxOfOrNull { it.getString("id").removePrefix("d").toIntOrNull() ?: 0 } ?: 0))
        val doorbell = c.isDoorbell(info)
        val nice = name?.trim()?.ifEmpty { null } ?: info.optString("device_alias").ifEmpty { null }
            ?: if (doorbell) "Voordeur" else "Camera"
        store.putString("tapo_pw", pw)
        store.putString("dev.$id.cert_pin", pin)
        store.putString("dev.$id.kind", if (doorbell) "doorbell" else "camera")
        store.putString("dev.$id.model", info.optString("device_model"))
        saveList(deviceList() + JSONObject().put("id", id).put("name", nice).put("host", ip))
        startAll()
        return info.put("id", id).put("name", nice)
    }

    fun removeDevice(id: String) { saveList(deviceList().filter { it.getString("id") != id }); startAll() }

    fun renameDevice(id: String, name: String) {
        saveList(deviceList().map { if (it.getString("id") == id) it.put("name", name) else it })
        devices.firstOrNull { it.id == id }?.name = name
    }

    // ---- automatic firmware updates (all devices, chosen time) ----
    val autoUpdate get() = store.getBool("auto_update", true)
    val autoUpdateTime get() = store.getString("auto_update_time") ?: "01:00"

    fun setAutoUpdate(on: Boolean, time: String) {
        require(time.matches(Regex("\\d{2}:\\d{2}"))) { "Ongeldige tijd" }
        store.putBool("auto_update", on); store.putString("auto_update_time", time)
        devices.forEach { d -> Thread { try { applyAutoUpdate(d.client()) } catch (_: Exception) {} }.start() }
    }

    fun applyAutoUpdate(c: TapoClient) {
        try { c.setAutoUpgrade(autoUpdate, autoUpdateTime) } catch (e: Exception) { Log.w("Automatisch bijwerken instellen mislukt: ${e.message}") }
    }

    // ---- do not disturb (all devices) ----
    fun dndUntil(): Long = store.getString("dnd_until")?.toLongOrNull() ?: 0L
    fun dndActive() = dndUntil() > System.currentTimeMillis()

    @Synchronized
    fun setDnd(untilMs: Long) {
        val wasActive = dndActive()
        if (untilMs > System.currentTimeMillis()) {
            if (!wasActive) devices.filter { it.isDoorbell }.forEach { d ->
                val on = try { d.client().chimeRingPlan()?.optString("enabled") == "on" } catch (_: Exception) { false }
                d.ds.putBool("dnd_chime_prev", on)
                if (on) try { d.client().setChimeEnabled(false) } catch (e: Exception) { Log.w("Gong uitzetten mislukt: ${e.message}") }
            }
            store.putString("dnd_until", untilMs.toString())
        } else {
            store.putString("dnd_until", null)
            if (wasActive) devices.filter { it.isDoorbell && it.ds.getBool("dnd_chime_prev", false) }.forEach { d ->
                try { d.client().setChimeEnabled(true) } catch (e: Exception) { Log.w("Gong aanzetten mislukt: ${e.message}") }
                d.ds.putBool("dnd_chime_prev", false)
            }
        }
    }

    init {
        Thread({
            while (true) {
                Thread.sleep(30_000)
                val until = dndUntil()
                if (until in 1..System.currentTimeMillis()) { setDnd(0); Log.i("Niet storen afgelopen") }
            }
        }, "dnd").apply { isDaemon = true; start() }
    }

    fun notifyAll(payload: JSONObject) {
        val vapid = store.vapid()
        for (s in store.subscriptions()) {
            pushPool.submit {
                try {
                    val code = WebPush.send(s, payload, vapid, SUBJECT)
                    if (code == 404 || code == 410) store.removeSubscription(s.endpoint)
                    else if (code >= 300) Log.w("Push geweigerd ($code)")
                } catch (e: Exception) { Log.w("Push mislukt: ${e.message}") }
            }
        }
    }

    /** Tells go2rtc (live view + talk) how to reach the device. */
    private fun ensureGo2rtc(d: Device, pw: String) {
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val sha = Hashes.sha256HexUpper(pw)
        val sources = listOf("tapo://${enc(pw)}@${d.host}", "tapo://admin:$sha@${d.host}")
        val q = "name=${d.stream}&" + sources.joinToString("&") { "src=" + enc(it) }
        repeat(30) {
            try {
                val res = http.send(HttpRequest.newBuilder(URI("$go2rtc/api/streams?$q")).timeout(Duration.ofSeconds(10))
                    .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
                if (res.statusCode() < 300) { Log.i("Live beeld ${d.name} ingesteld in go2rtc"); return }
                Log.w("go2rtc antwoordde ${res.statusCode()}")
            } catch (e: Exception) { /* go2rtc is still starting */ }
            Thread.sleep(5000)
        }
        Log.w("go2rtc niet bereikbaar; live beeld werkt nog niet")
    }

    /** Finds Tapo devices on the local network (they listen on port 8800). */
    fun scan(): List<String> {
        val prefixes = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("docker") && !it.name.startsWith("br-") &&
                !it.name.startsWith("veth") && !it.name.startsWith("tailscale") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>().filter { it.isSiteLocalAddress }
            .map { it.hostAddress.substringBeforeLast('.') + "." }.distinct()
        val known = deviceList().map { it.getString("host") }.toSet()
        val pool = Executors.newFixedThreadPool(64)
        try {
            val jobs = prefixes.flatMap { p -> (1..254).map { p + it } }.filter { it !in known }.map { ip ->
                pool.submit<String?> {
                    try { java.net.Socket().use { it.connect(InetSocketAddress(ip, 8800), 500) }; ip } catch (_: Exception) { null }
                }
            }
            return jobs.mapNotNull { it.get() }
        } finally { pool.shutdownNow() }
    }

    companion object { const val SUBJECT = "mailto:deurbel@example.com" }
}

class HttpError(val code: Int, msg: String) : Exception(msg)

class Api(private val store: Store, private val app: App, private val go2rtc: String) {
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

    private fun friendlyLinkError(e: Exception, ip: String): String = when (e) {
        is HttpError -> e.message ?: "Koppelen mislukt"
        is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException -> "Apparaat niet bereikbaar op $ip"
        is javax.net.ssl.SSLException -> "Op $ip reageert geen Tapo-apparaat. Klopt het adres?"
        else -> if ((e.message ?: "").startsWith("Ongeldig antwoord")) "Op $ip reageert geen Tapo-apparaat. Klopt het adres?"
            else e.message ?: "Koppelen mislukt"
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        val m = ex.requestMethod
        val q = query(ex)
        val dev = { app.device(q["dev"]) }
        when {
            path == "/api/status" && app.devices.isEmpty() -> json(ex, 200, JSONObject().put("setup", true)
                .put("vapidKey", WebPush.encode(WebPush.rawPublic(store.vapid().public as java.security.interfaces.ECPublicKey))))
            path == "/api/setup/scan" -> json(ex, 200, JSONArray(app.scan()))
            (path == "/api/setup" || path == "/api/devices") && m == "POST" -> {
                val b = body(ex)
                val ip = b.optString("ip").trim().ifEmpty { throw HttpError(400, "Kies of vul het adres van het apparaat in") }
                val pw = b.optString("password").ifEmpty { app.password ?: throw HttpError(400, "Vul het wachtwoord in") }
                val info = try { app.addDevice(ip, pw, b.optString("name")) } catch (e: Exception) { throw HttpError(400, friendlyLinkError(e, ip)) }
                json(ex, 200, JSONObject().put("ok", true).put("model", info.optString("device_model")).put("name", info.optString("name")).put("id", info.optString("id")))
            }
            path == "/api/devices" && m == "DELETE" -> { app.removeDevice(dev().id); json(ex, 200, status()) }
            path == "/api/devices/rename" && m == "POST" -> { app.renameDevice(dev().id, body(ex).getString("name").trim().take(30)); json(ex, 200, status()) }
            path == "/api/status" -> json(ex, 200, status())
            path == "/api/settings" && m == "POST" -> {
                val b = body(ex)
                if (b.has("notifications")) store.putBool("notifications", b.getBoolean("notifications"))
                b.optJSONArray("ringTypes")?.let { a -> dev().ds.putStringSet("ring_types", (0 until a.length()).map { a.getString(it) }.toSet()) }
                json(ex, 200, status())
            }
            path == "/api/chime" && m == "POST" -> {
                val on = body(ex).getBoolean("on")
                dev().client().setChimeEnabled(on)
                chimeCache[dev().id] = System.currentTimeMillis() to on
                json(ex, 200, JSONObject().put("ok", true))
            }
            path == "/api/dnd" && m == "POST" -> {
                val b = body(ex)
                val until = when {
                    b.optBoolean("off") -> 0L
                    b.has("minutes") -> System.currentTimeMillis() + b.getLong("minutes") * 60_000
                    b.has("until") -> b.getLong("until") * 1000
                    else -> throw HttpError(400, "minutes, until of off ontbreekt")
                }
                app.setDnd(until)
                chimeCache.clear()
                json(ex, 200, status())
            }
            path == "/api/autoupdate" && m == "POST" -> {
                val b = body(ex)
                app.setAutoUpdate(b.optBoolean("on", app.autoUpdate), b.optString("time", app.autoUpdateTime))
                json(ex, 200, status())
            }
            path == "/api/firmware" && m == "GET" -> {
                val c = dev().client()
                val info = c.deviceInfo()
                val current = info.optString("sw_version")
                val o = JSONObject().put("current", current).put("model", info.optString("device_model"))
                c.firmwareStatus()?.let { o.put("status", it) }
                if (q["check"] == "1") {
                    val up = c.checkFirmware()
                    o.put("checked", true).put("available", TapoClient.firmwareAvailable(up, current))
                        .put("latest", up.optString("version")).put("releaseNote", up.optString("release_note"))
                }
                json(ex, 200, o)
            }
            path == "/api/firmware/install" && m == "POST" -> {
                dev().client().startFirmwareUpgrade()
                Log.i("Firmware-update gestart voor ${dev().name}")
                json(ex, 200, JSONObject().put("ok", true))
            }
            path == "/api/zones" && m == "GET" -> {
                val z = try { dev().client().detectionZones() } catch (e: nl.tapo.deurbel.core.TapoException) {
                    throw HttpError(400, "Zones ophalen lukte niet (code ${e.code ?: "?"}: ${e.message})")
                }
                json(ex, 200, JSONObject().put("zones", JSONArray(z.zones.map { zone ->
                    JSONArray(zone.points.map { (x, y) -> JSONArray().put(x).put(y) })
                })))
            }
            path == "/api/zones" && m == "POST" -> {
                val c = dev().client()
                val current = try { c.detectionZones() } catch (e: nl.tapo.deurbel.core.TapoException) {
                    throw HttpError(400, "Zones ophalen lukte niet (code ${e.code ?: "?"}: ${e.message})")
                }
                val arr = body(ex).getJSONArray("zones")
                val zones = (0 until arr.length()).map { i ->
                    val pts = arr.getJSONArray(i).let { p -> (0 until p.length()).map { p.getJSONArray(it).getDouble(0) to p.getJSONArray(it).getDouble(1) } }
                    current.zones.getOrNull(i)?.takeIf { it.points.size == pts.size }?.moved(pts)
                        ?: nl.tapo.deurbel.core.DetectionZones.Zone(pts, null, null)
                }
                try { c.setDetectionZones(current.withZones(zones), current) }
                catch (e: TapoClient.ZoneSaveException) {
                    Log.w("Zones opslaan ${dev().name}:\n${e.details}")
                    json(ex, 400, JSONObject().put("error", e.message).put("details", e.details)); return
                }
                json(ex, 200, JSONObject().put("ok", true))
            }
            path == "/api/recording" && m == "POST" -> {
                dev().client().setRecordEventsOnly(body(ex).getBoolean("eventsOnly"))
                recordCache.remove(dev().id)
                json(ex, 200, status())
            }
            path == "/api/types/label" && m == "POST" -> {
                val b = body(ex)
                dev().setLabel(b.getString("type"), b.getString("label"))
                json(ex, 200, status())
            }
            path == "/api/learn" && m == "POST" -> {
                val ring = dev().learnRing()
                json(ex, 200, status().put("learned", ring ?: JSONObject.NULL))
            }
            path == "/api/power" -> json(ex, 200, dev().client().power().toJson())
            path == "/api/days" -> {
                val end = Calendar.getInstance(); val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -90) }
                val days = dev().client().recordingDays(dayFmt.format(start.time), dayFmt.format(end.time)).distinct().sortedDescending()
                json(ex, 200, JSONArray(days))
            }
            path == "/api/clips" -> {
                val d = dev(); val c = d.client()
                val corr = c.timeCorrection()
                val hidden = d.ds.stringSet("hidden")
                val list = c.recordingsOfDay(q["date"] ?: throw HttpError(400, "date ontbreekt"))
                    .filter { it.startTime.toString() !in hidden }.sortedByDescending { it.startTime }
                val ringsA = store.rings()
                val ringTimes = (0 until ringsA.length()).map { ringsA.getJSONObject(it) }
                    .filter { it.optString("dev").let { x -> x.isEmpty() || x == d.id } }.map { it.getLong("time") }
                val ringTypes = d.ds.stringSet("ring_types")
                json(ex, 200, JSONArray(list.map {
                    val t = it.startTime + corr
                    val rang = ringTypes.isNotEmpty() && ringTimes.any { r -> r in (t - 10)..(t + (it.endTime - it.startTime) + 10) }
                    JSONObject().put("start", it.startTime).put("end", it.endTime).put("time", t)
                        .put("duration", it.endTime - it.startTime).put("cached", app.mediaOf(d).cached(it.startTime) != null)
                        .put("rang", rang)
                }))
            }
            path == "/api/clip" && m == "GET" -> {
                val start = q["start"]?.toLongOrNull() ?: throw HttpError(400, "start ontbreekt")
                val end = q["end"]?.toLongOrNull() ?: throw HttpError(400, "end ontbreekt")
                val md = app.mediaOf(dev())
                val f = md.clip(Recording(start, end, 0))
                md.trimCache()
                sendFile(ex, f, if (q["download"] == "1") "${dev().name}_${q["name"] ?: start}.${f.extension}" else null)
            }
            path == "/api/clip" && m == "DELETE" -> {
                app.mediaOf(dev()).deleteClip(q["start"]?.toLongOrNull() ?: throw HttpError(400, "start ontbreekt"))
                json(ex, 200, JSONObject().put("ok", true))
            }
            path == "/api/replies" && m == "GET" -> json(ex, 200, replies())
            path == "/api/replies" && m == "POST" -> {
                val text = body(ex).optString("text").trim().ifEmpty { throw HttpError(400, "Tekst ontbreekt") }
                store.saveReplies(store.replies() + Reply(UUID.randomUUID().toString().take(8), text, false))
                json(ex, 200, replies())
            }
            path.startsWith("/api/replies/") -> reply(ex, path.removePrefix("/api/replies/").split("/"), m, q)
            path == "/api/push/subscribe" && m == "POST" -> {
                val b = body(ex)
                val keys = b.getJSONObject("keys")
                store.addSubscription(PushSubscription(b.getString("endpoint"), keys.getString("p256dh"), keys.getString("auth")))
                json(ex, 200, JSONObject().put("ok", true))
            }
            path == "/api/push/test" && m == "POST" -> {
                app.notifyAll(JSONObject().put("title", "Testmelding").put("body", "Meldingen werken!").put("url", "/"))
                json(ex, 200, JSONObject().put("ok", true).put("phones", store.subscriptions().size))
            }
            path.startsWith("/go2rtc/") -> proxyGo2rtc(ex, path.removePrefix("/go2rtc/"))
            path.startsWith("/api/") -> throw HttpError(404, "Onbekend")
            else -> static(ex, path)
        }
    }

    private val chimeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean?>>()
    private val recordCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean?>>()

    private fun cached(cache: MutableMap<String, Pair<Long, Boolean?>>, d: Device, ttl: Long, f: () -> Boolean?): Boolean? {
        cache[d.id]?.let { if (System.currentTimeMillis() - it.first < ttl) return it.second }
        val v = if (System.currentTimeMillis() - d.lastOk > (if (d.onBattery) 300_000 else 30_000)) null else try { f() } catch (_: Exception) { null }
        cache[d.id] = System.currentTimeMillis() to v
        return v
    }

    private fun deviceJson(d: Device): JSONObject {
        val chime = if (d.isDoorbell) cached(chimeCache, d, 60_000) { d.client().chimeRingPlan()?.optString("enabled")?.let { it == "on" } } else null
        val rec = cached(recordCache, d, 10 * 60_000) { d.client().recordsEventsOnly() }
        return JSONObject().put("id", d.id).put("name", d.name).put("kind", d.kind).put("model", d.model).put("host", d.host)
            .put("stream", d.stream).put("status", d.status)
            .put("connected", System.currentTimeMillis() - d.lastOk < (if (d.onBattery) 300_000 else 30_000))
            .put("battery", d.onBattery)
            .put("chime", chime ?: JSONObject.NULL)
            .put("eventsOnly", rec ?: JSONObject.NULL)
            .put("power", d.power?.toJson()?.also { it.remove("raw") } ?: JSONObject.NULL)
            .put("types", JSONArray(typeList(d)))
            .put("ringTypes", JSONArray(d.ds.stringSet("ring_types").sorted()))
    }

    private fun status(): JSONObject {
        val rings = store.rings()
        val recent = JSONArray((maxOf(0, rings.length() - 20) until rings.length()).reversed().map { rings.get(it) })
        val list = app.devices.map { deviceJson(it) }
        val first = list.firstOrNull()
        return JSONObject()
            .put("devices", JSONArray(list))
            .put("status", first?.optString("status") ?: "Geen apparaat")
            .put("connected", list.isNotEmpty() && list.all { it.optBoolean("connected") })
            .put("notifications", store.getBool("notifications", true))
            .put("rings", recent)
            .put("labelNames", JSONObject(nl.tapo.deurbel.core.EventTypes.LABELS as Map<*, *>))
            .put("vapidKey", WebPush.encode(WebPush.rawPublic(store.vapid().public as java.security.interfaces.ECPublicKey)))
            .put("phones", store.subscriptions().size)
            .put("dndUntil", if (app.dndActive()) app.dndUntil() / 1000 else 0)
            .put("autoUpdate", JSONObject().put("on", app.autoUpdate).put("time", app.autoUpdateTime))
    }

    private fun typeList(d: Device): List<JSONObject> {
        val stats = d.typeStats(); val labels = d.typeLabels(); val notify = d.ds.stringSet("ring_types")
        val all = (d.ds.stringSet("seen_types") + stats.keys().asSequence().toList()).distinct()
        return all.sortedWith(compareBy({ if (labels.optString(it) == "ring") 0 else 1 }, { -(stats.optJSONObject(it)?.optInt("n") ?: 0) }))
            .map { t ->
                JSONObject().put("type", t).put("label", labels.optString(t)).put("name", nl.tapo.deurbel.core.EventTypes.name(t, labels))
                    .put("desc", nl.tapo.deurbel.core.EventTypes.describe(stats, t)).put("notify", t in notify)
            }
    }

    private val anyMedia get() = app.media.values.firstOrNull() ?: throw HttpError(503, "Nog geen apparaat gekoppeld")

    private fun replies() = JSONArray(store.replies().map {
        JSONObject().put("id", it.id).put("text", it.text).put("useVoice", it.useVoice)
            .put("hasVoice", app.media.values.firstOrNull()?.hasVoice(it.id) ?: false)
    })

    private fun reply(ex: HttpExchange, parts: List<String>, m: String, q: Map<String, String>) {
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
                anyMedia.voiceFile(id).delete()
                json(ex, 200, replies())
            }
            parts.getOrNull(1) == "play" && m == "POST" -> {
                app.mediaOf(app.device(q["dev"])).playAtDoor(r)
                json(ex, 200, JSONObject().put("ok", true))
            }
            parts.getOrNull(1) == "preview.wav" -> {
                val wav = anyMedia.preview(r)
                ex.responseHeaders.add("Content-Type", "audio/wav")
                ex.sendResponseHeaders(200, wav.size.toLong())
                ex.responseBody.use { it.write(wav) }
            }
            parts.getOrNull(1) == "voice" && m == "POST" -> {
                val data = ex.requestBody.readBytes()
                if (data.size < 1000) throw HttpError(400, "Opname is te kort")
                anyMedia.saveVoice(id, data)
                store.saveReplies(store.replies().map { if (it.id == id) it.copy(useVoice = true) else it })
                json(ex, 200, replies())
            }
            parts.getOrNull(1) == "voice" && m == "DELETE" -> {
                anyMedia.voiceFile(id).delete()
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
