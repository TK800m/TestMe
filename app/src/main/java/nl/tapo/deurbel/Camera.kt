package nl.tapo.deurbel

import android.content.Context
import nl.tapo.deurbel.core.CameraHttps
import nl.tapo.deurbel.core.TapoClient

/** Shared connection to the doorbell. */
object Camera {
    private var client: TapoClient? = null
    private var key: String? = null

    /** Microphone format of the doorbell (for visitor audio). */
    @Volatile var audioUlaw = false
    @Volatile var audioRate = 8000
    private var audioKnown = false

    @Synchronized
    fun client(context: Context): TapoClient {
        val p = Prefs(context)
        require(p.configured) { "Stel eerst het IP-adres en wachtwoord in" }
        val k = p.host + "\u0000" + p.password
        client?.takeIf { key == k }?.let { return it }
        val https = CameraHttps(p.certPin) { fp -> Prefs(context).certPin = fp }
        return TapoClient(p.host, p.password, https).also { client = it; key = k; audioKnown = false }
    }

    @Synchronized
    fun reset() {
        try { client?.close() } catch (_: Exception) {}
        client = null; key = null; audioKnown = false
    }

    fun loadAudioFormat(c: TapoClient) {
        if (audioKnown) return
        try {
            val mic = c.audioConfig().optJSONObject("audio_config")?.optJSONObject("microphone")
            audioUlaw = mic?.optString("encode_type")?.lowercase()?.contains("ulaw") == true
            audioRate = (mic?.optString("sampling_rate")?.toIntOrNull() ?: 8) * 1000
            if (audioRate < 8000) audioRate = 8000
        } catch (_: Exception) {}
        audioKnown = true
    }

    fun friendly(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return when (e) {
            is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
                "Deurbel niet bereikbaar. Zit je telefoon op het thuis-wifi en klopt het IP-adres?"
            else -> m
        }
    }
}
