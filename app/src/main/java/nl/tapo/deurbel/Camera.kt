package nl.tapo.deurbel

import android.content.Context
import nl.tapo.deurbel.core.CameraHttps
import nl.tapo.deurbel.core.TapoClient

/** Shared connections to the doorbell and cameras (one per device). */
object Camera {
    private val clients = HashMap<String, Pair<String, TapoClient>>()

    /** Microphone format of the device shown live (for visitor audio). */
    @Volatile var audioUlaw = false
    @Volatile var audioRate = 8000

    /** Connection to device [devId] (default: the device shown in the app). */
    @Synchronized
    fun client(context: Context, devId: String? = null): TapoClient {
        val p = Prefs(context, devId)
        require(p.configured) { "Stel eerst het wachtwoord en een apparaat in" }
        val dev = p.device ?: throw IllegalStateException("Apparaat niet gevonden")
        val k = dev.host + "\u0000" + p.password
        clients[dev.id]?.takeIf { it.first == k }?.let { return it.second }
        val https = CameraHttps(p.certPin) { fp -> Prefs(context, dev.id).certPin = fp }
        val c = TapoClient(dev.host, p.password, https)
        clients[dev.id] = k to c
        return c
    }

    @Synchronized
    fun reset(devId: String? = null) {
        val ids = if (devId == null) clients.keys.toList() else listOf(devId)
        ids.forEach { id -> try { clients.remove(id)?.second?.close() } catch (_: Exception) {} }
    }

    fun loadAudioFormat(c: TapoClient) {
        audioUlaw = false; audioRate = 8000
        try {
            val mic = c.audioConfig().optJSONObject("audio_config")?.optJSONObject("microphone")
            audioUlaw = mic?.optString("encode_type")?.lowercase()?.contains("ulaw") == true
            audioRate = maxOf(8000, (mic?.optString("sampling_rate")?.toIntOrNull() ?: 8) * 1000)
        } catch (_: Exception) {}
    }

    /** Lets the device install firmware updates itself at the chosen time. */
    fun applyAutoUpdate(context: Context, c: TapoClient) {
        val p = Prefs(context)
        try { c.setAutoUpgrade(p.autoUpdate, p.autoUpdateTime) } catch (_: Exception) {}
    }

    fun friendly(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return when (e) {
            is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
                "Apparaat niet bereikbaar. Zit je telefoon op het thuis-wifi en klopt het IP-adres?"
            else -> m
        }
    }
}
