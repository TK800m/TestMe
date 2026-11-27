package nl.tapo.deurbel.server

import nl.tapo.deurbel.core.CameraHttps
import nl.tapo.deurbel.core.EventTypes
import nl.tapo.deurbel.core.PowerInfo
import nl.tapo.deurbel.core.TapoClient
import nl.tapo.deurbel.core.TapoException
import org.json.JSONObject

/** Settings of one device, stored under "dev.<id>.<key>". */
class DevStore(private val s: Store, private val id: String) {
    private fun k(key: String) = "dev.$id.$key"
    fun getString(key: String) = s.getString(k(key))
    fun putString(key: String, v: String?) = s.putString(k(key), v)
    fun getBool(key: String, def: Boolean) = s.getBool(k(key), def)
    fun putBool(key: String, v: Boolean) = s.putBool(k(key), v)
    fun stringSet(key: String) = s.stringSet(k(key))
    fun putStringSet(key: String, v: Set<String>) = s.putStringSet(k(key), v)
}

/**
 * One Tapo doorbell or camera: connection, background watcher (events, battery)
 * and its own settings. Battery devices are asked far less often to save energy.
 */
class Device(
    val id: String,
    @Volatile var name: String,
    val host: String,
    private val password: String,
    private val app: App,
) {
    val ds = DevStore(app.store, id)
    @Volatile private var client: TapoClient? = null
    @Volatile var status: String = "Verbinden…"; private set
    @Volatile var lastOk: Long = 0; private set
    @Volatile var power: PowerInfo? = null; private set
    @Volatile var audioUlaw = false
    @Volatile var audioRate = 8000
    @Volatile private var running = true
    private var lastPowerCheck = 0L

    val stream get() = "dev_$id"
    val kind: String get() = ds.getString("kind") ?: "doorbell"
    val isDoorbell get() = kind == "doorbell"
    val model: String get() = ds.getString("model") ?: ""

    /** Battery devices without mains power sleep a lot; don't wake them every few seconds. */
    val onBattery get() = power?.let { it.hasBattery && it.external != true } ?: (ds.getString("battery") == "1")

    @Synchronized
    fun client(): TapoClient {
        client?.let { return it }
        val https = CameraHttps(ds.getString("cert_pin")) { ds.putString("cert_pin", it) }
        return TapoClient(host, password, https).also {
            client = it
            try {
                val info = it.deviceInfo()
                ds.putString("model", info.optString("device_model"))
                ds.putString("kind", if (it.isDoorbell(info)) "doorbell" else "camera")
            } catch (_: Exception) {}
            try {
                val mic = it.audioConfig().optJSONObject("audio_config")?.optJSONObject("microphone")
                audioUlaw = mic?.optString("encode_type")?.lowercase()?.contains("ulaw") == true
                audioRate = maxOf(8000, (mic?.optString("sampling_rate")?.toIntOrNull() ?: 8) * 1000)
            } catch (_: Exception) {}
            app.applyAutoUpdate(it)
        }
    }

    @Synchronized
    fun reset() {
        try { client?.close() } catch (_: Exception) {}
        client = null
    }

    fun start() = Thread({ loop() }, "watch-$id").apply { isDaemon = true; start() }
    fun stop() { running = false; reset() }

    private fun loop() {
        val seen = HashMap<String, Long>()
        var primed = false
        while (running) {
            try {
                val c = client()
                val corr = c.timeCorrection()
                val camNow = System.currentTimeMillis() / 1000 - corr
                for (e in c.events(camNow - 300, camNow + 60)) {
                    val key = "${e.startTime}/${e.type}"
                    if (seen.containsKey(key)) continue
                    seen[key] = e.startTime
                    if (e.type.isNotEmpty()) {
                        if (e.type !in ds.stringSet("seen_types")) ds.putStringSet("seen_types", ds.stringSet("seen_types") + e.type)
                        ds.putString("type_stats", EventTypes.record(typeStats(), e.type, e.startTime + corr).toString())
                    }
                    val types = ds.stringSet("ring_types")
                    if (primed && (types.isEmpty() || e.type in types)) event(e.startTime + corr, e.type)
                }
                seen.entries.removeIf { it.value < camNow - 900 }
                primed = true
                val powerEvery = if (onBattery) 30 * 60_000 else 5 * 60_000
                if (System.currentTimeMillis() - lastPowerCheck > powerEvery) { lastPowerCheck = System.currentTimeMillis(); checkPower(c) }
                status = "Verbonden"
                lastOk = System.currentTimeMillis()
                Thread.sleep(if (onBattery) 60_000 else 2500)
            } catch (e: Exception) {
                status = "Geen verbinding: ${e.message}"
                Log.w("$name: ${e.message}")
                if (e is TapoException) reset()
                Thread.sleep(if (onBattery) 120_000 else 10_000)
            }
        }
    }

    // ---- battery / power ----
    private fun checkPower(c: TapoClient) {
        val p = try { c.power() } catch (e: Exception) { return }
        val before = power
        power = p
        ds.putString("battery", if (p.hasBattery) "1" else "0")
        val now = System.currentTimeMillis()
        if (before?.external == true && p.external == false) {
            Log.w("$name: netvoeding/zonnepaneel weggevallen")
            app.notifyAll(JSONObject().put("title", "$name krijgt geen stroom meer").put("body",
                "Draait nu op de batterij" + (p.percent?.let { " ($it%)" } ?: "") + ". Controleer de voeding.").put("url", "/"))
        }
        if (p.low && p.charging != true) {
            val last = ds.getString("low_battery_notified")?.toLongOrNull() ?: 0
            if (now - last > 12 * 3600_000L) {
                ds.putString("low_battery_notified", now.toString())
                app.notifyAll(JSONObject().put("title", "Batterij $name bijna leeg").put("body",
                    "Nog ${p.percent}%. Laad op of controleer de voeding.").put("url", "/"))
            }
        }
    }

    // ---- event types ----
    fun typeStats() = JSONObject(ds.getString("type_stats") ?: "{}")
    fun typeLabels() = JSONObject(ds.getString("type_labels") ?: "{}")

    fun setLabel(type: String, label: String) {
        val l = typeLabels()
        if (label == "ring") l.keys().asSequence().toList().forEach { if (l.optString(it) == "ring") l.remove(it) }
        l.put(type, label)
        ds.putString("type_labels", l.toString())
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
        ds.putStringSet("seen_types", ds.stringSet("seen_types") + candidates)
        ds.putStringSet("ring_types", setOf(ring))
        return ring
    }

    private fun event(epochSec: Long, type: String) {
        app.store.addRing(epochSec, type, id)
        Log.i("$name: gebeurtenis type=$type")
        if (!app.store.getBool("notifications", true) || app.dndActive()) return
        val title = when (val l = typeLabels().optString(type)) {
            "ring" -> "Er wordt aangebeld"
            "", "person" -> if (isDoorbell) "Er staat iemand voor de deur" else "Iemand gezien"
            else -> EventTypes.LABELS[l] ?: "Gebeurtenis"
        }
        val multi = app.devices.size > 1
        app.notifyAll(JSONObject().put("title", if (multi) "$name: $title" else title).put("body", "Tik om te kijken")
            .put("time", epochSec).put("url", "/?live=1&dev=$id"))
    }
}

object Log {
    fun i(m: String) = println("${java.time.LocalDateTime.now().withNano(0)} $m")
    fun w(m: String) = System.err.println("${java.time.LocalDateTime.now().withNano(0)} WAARSCHUWING $m")
}
