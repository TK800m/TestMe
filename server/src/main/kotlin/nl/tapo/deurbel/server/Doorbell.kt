package nl.tapo.deurbel.server

import nl.tapo.deurbel.core.CameraHttps
import nl.tapo.deurbel.core.TapoClient
import nl.tapo.deurbel.core.TapoException
import org.json.JSONObject
import java.util.concurrent.Executors

/** Connection to the doorbell + background watcher that sends push notifications. */
class Doorbell(private val host: String, private val password: String, private val store: Store) {
    @Volatile private var client: TapoClient? = null
    @Volatile var status: String = "Verbinden…"
        private set
    @Volatile var lastOk: Long = 0
        private set
    @Volatile var audioUlaw = false
    @Volatile var audioRate = 8000
    private val pushPool = Executors.newFixedThreadPool(4)

    @Synchronized
    fun client(): TapoClient {
        client?.let { return it }
        val https = CameraHttps(store.getString("cert_pin")) { store.putString("cert_pin", it) }
        return TapoClient(host, password, https).also {
            client = it
            try {
                val mic = it.audioConfig().optJSONObject("audio_config")?.optJSONObject("microphone")
                audioUlaw = mic?.optString("encode_type")?.lowercase()?.contains("ulaw") == true
                audioRate = maxOf(8000, (mic?.optString("sampling_rate")?.toIntOrNull() ?: 8) * 1000)
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun reset() {
        try { client?.close() } catch (_: Exception) {}
        client = null
    }

    fun start() = Thread({ loop() }, "doorbell-watch").apply { isDaemon = true; start() }

    private fun loop() {
        val seen = HashMap<String, Long>()
        var primed = false
        while (true) {
            try {
                val c = client()
                val corr = c.timeCorrection()
                val camNow = System.currentTimeMillis() / 1000 - corr
                val events = c.events(camNow - 300, camNow + 60)
                for (e in events) {
                    val key = "${e.startTime}/${e.type}"
                    if (seen.containsKey(key)) continue
                    seen[key] = e.startTime
                    if (e.type.isNotEmpty() && e.type !in store.stringSet("seen_types")) store.putStringSet("seen_types", store.stringSet("seen_types") + e.type)
                    val types = store.stringSet("ring_types")
                    if (primed && (types.isEmpty() || e.type in types)) ring(e.startTime + corr, e.type)
                }
                seen.entries.removeIf { it.value < camNow - 900 }
                primed = true
                status = "Verbonden"
                lastOk = System.currentTimeMillis()
                Thread.sleep(2500)
            } catch (e: Exception) {
                status = "Geen verbinding: ${e.message}"
                Log.w("Deurbel: ${e.message}")
                if (e is TapoException) reset()
                Thread.sleep(10_000)
            }
        }
    }

    private fun ring(epochSec: Long, type: String) {
        store.addRing(epochSec, type)
        Log.i("Aanbelling/gebeurtenis type=$type")
        if (!store.getBool("notifications", true)) return
        notifyAll(JSONObject().put("title", "Er staat iemand voor de deur").put("body", "Tik om te kijken wie het is")
            .put("time", epochSec).put("url", "/?live=1"))
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

    companion object {
        const val SUBJECT = "mailto:deurbel@example.com"
    }
}

object Log {
    fun i(m: String) = println("${java.time.LocalDateTime.now().withNano(0)} $m")
    fun w(m: String) = System.err.println("${java.time.LocalDateTime.now().withNano(0)} WAARSCHUWING $m")
}
