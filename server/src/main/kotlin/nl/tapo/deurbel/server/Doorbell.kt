package nl.tapo.deurbel.server

import nl.tapo.deurbel.core.CameraHttps
import nl.tapo.deurbel.core.TapoClient
import nl.tapo.deurbel.core.TapoException
import nl.tapo.deurbel.core.EventTypes
import org.json.JSONObject
import java.util.concurrent.Executors

/** Connection to the doorbell + background watcher that sends push notifications. */
class Doorbell(private val host: String, private val password: String, private val store: Store) {
    @Volatile private var client: TapoClient? = null
    @Volatile var status: String = "Verbinden…"
        private set
    @Volatile var lastOk: Long = 0
        private set
    @Volatile var power: nl.tapo.deurbel.core.PowerInfo? = null
        private set
    private var lastPowerCheck = 0L
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

    @Volatile private var running = true

    fun start() = Thread({ loop() }, "doorbell-watch").apply { isDaemon = true; start() }

    fun stop() { running = false; reset() }

    private fun loop() {
        val seen = HashMap<String, Long>()
        var primed = false
        while (running) {
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
                    if (e.type.isNotEmpty()) store.putString("type_stats", EventTypes.record(typeStats(), e.type, e.startTime + corr).toString())
                    val types = store.stringSet("ring_types")
                    if (primed && (types.isEmpty() || e.type in types)) ring(e.startTime + corr, e.type)
                }
                seen.entries.removeIf { it.value < camNow - 900 }
                primed = true
                checkDndExpired()
                if (System.currentTimeMillis() - lastPowerCheck > 5 * 60_000) { lastPowerCheck = System.currentTimeMillis(); checkPower(c) }
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

    // ---- batterij / stroom ----
    private fun checkPower(c: nl.tapo.deurbel.core.TapoClient) {
        val p = try { c.power() } catch (e: Exception) { return }
        val before = power
        power = p
        val now = System.currentTimeMillis()
        if (before?.external == true && p.external == false) {
            Log.w("Netvoeding van de deurbel weggevallen")
            notifyAll(JSONObject().put("title", "Deurbel krijgt geen stroom meer").put("body",
                "De deurbel draait nu op de batterij" + (p.percent?.let { " ($it%)" } ?: "") + ". Controleer de netvoeding.").put("url", "/"))
        }
        if (p.low && p.charging != true) {
            val last = store.getString("low_battery_notified")?.toLongOrNull() ?: 0
            if (now - last > 12 * 3600_000L) {
                store.putString("low_battery_notified", now.toString())
                notifyAll(JSONObject().put("title", "Batterij deurbel bijna leeg").put("body",
                    "Nog ${p.percent}%. Laad de deurbel op of controleer de netvoeding.").put("url", "/"))
            }
        }
    }

    // ---- niet storen ----
    fun dndUntil(): Long = store.getString("dnd_until")?.toLongOrNull() ?: 0L
    fun dndActive() = dndUntil() > System.currentTimeMillis()

    /** Turns "do not disturb" on until [untilMs] (0 = off): no notifications and the chime is silenced. */
    @Synchronized
    fun setDnd(untilMs: Long) {
        val wasActive = dndActive()
        if (untilMs > System.currentTimeMillis()) {
            if (!wasActive) {
                val chimeOn = try { client().chimeRingPlan()?.optString("enabled") == "on" } catch (_: Exception) { false }
                store.putBool("dnd_chime_prev", chimeOn)
                if (chimeOn) try { client().setChimeEnabled(false) } catch (e: Exception) { Log.w("Gong uitzetten mislukt: ${e.message}") }
            }
            store.putString("dnd_until", untilMs.toString())
        } else {
            store.putString("dnd_until", null)
            if (wasActive && store.getBool("dnd_chime_prev", false)) {
                try { client().setChimeEnabled(true) } catch (e: Exception) { Log.w("Gong aanzetten mislukt: ${e.message}") }
            }
            store.putBool("dnd_chime_prev", false)
        }
    }

    private fun checkDndExpired() {
        val until = dndUntil()
        if (until in 1..System.currentTimeMillis()) { setDnd(0); Log.i("Niet storen afgelopen") }
    }

    fun typeStats() = JSONObject(store.getString("type_stats") ?: "{}")
    fun typeLabels() = JSONObject(store.getString("type_labels") ?: "{}")

    fun setLabel(type: String, label: String) {
        val l = typeLabels()
        if (label == "ring") l.keys().asSequence().toList().forEach { if (l.optString(it) == "ring") l.remove(it) }
        l.put(type, label)
        store.putString("type_labels", l.toString())
    }

    /** Waits (max 90 s) for the user to press the bell and learns which event type that is. */
    fun learnRing(): String? {
        val c = client()
        val corr = c.timeCorrection()
        val startCam = System.currentTimeMillis() / 1000 - corr - 5
        val candidates = linkedSetOf<String>()
        var firstSeen = 0L
        val until = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < until) {
            val camNow = System.currentTimeMillis() / 1000 - corr
            c.events(startCam, camNow + 60).filter { it.startTime >= startCam && it.type.isNotEmpty() }
                .forEach { if (candidates.add(it.type) && firstSeen == 0L) firstSeen = System.currentTimeMillis() }
            if (firstSeen > 0 && System.currentTimeMillis() - firstSeen > 10_000) break
            Thread.sleep(2000)
        }
        val ring = EventTypes.pickRing(candidates, typeStats(), typeLabels()) ?: return null
        setLabel(ring, "ring")
        store.putStringSet("seen_types", store.stringSet("seen_types") + candidates)
        store.putStringSet("ring_types", setOf(ring))
        return ring
    }

    private fun ring(epochSec: Long, type: String) {
        store.addRing(epochSec, type)
        Log.i("Aanbelling/gebeurtenis type=$type")
        if (!store.getBool("notifications", true) || dndActive()) return
        val title = when (val l = typeLabels().optString(type)) {
            "ring" -> "Er wordt aangebeld"
            "", "person" -> "Er staat iemand voor de deur"
            else -> EventTypes.LABELS[l] ?: "Er staat iemand voor de deur"
        }
        notifyAll(JSONObject().put("title", title).put("body", "Tik om te kijken wie het is")
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
