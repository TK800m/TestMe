package nl.tapo.deurbel.server

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyPair
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

data class Reply(val id: String, val text: String, val useVoice: Boolean)

/** Persistent server state in /data/state.json. */
class Store(private val dir: File) {
    private val file = File(dir, "state.json")
    private val state: JSONObject = if (file.exists()) JSONObject(file.readText()) else JSONObject()

    @Synchronized
    private fun save() {
        dir.mkdirs()
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(state.toString(2))
        tmp.renameTo(file)
    }

    @Synchronized fun getString(k: String): String? = state.optString(k).ifEmpty { null }
    @Synchronized fun putString(k: String, v: String?) { if (v == null) state.remove(k) else state.put(k, v); save() }
    @Synchronized fun getBool(k: String, def: Boolean) = state.optBoolean(k, def)
    @Synchronized fun putBool(k: String, v: Boolean) { state.put(k, v); save() }

    @Synchronized fun stringSet(k: String): Set<String> =
        state.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    @Synchronized fun putStringSet(k: String, v: Set<String>) { state.put(k, JSONArray(v.sorted())); save() }

    // ---- VAPID keys ----
    @Synchronized
    fun vapid(): KeyPair {
        val pub = getString("vapid_pub"); val priv = getString("vapid_priv")
        if (pub != null && priv != null) {
            return KeyPair(WebPush.publicFromRaw(WebPush.decode(pub)), WebPush.privateFromRaw(WebPush.decode(priv)))
        }
        val kp = WebPush.generateKeys()
        state.put("vapid_pub", WebPush.encode(WebPush.rawPublic(kp.public as ECPublicKey)))
        state.put("vapid_priv", WebPush.encode(WebPush.rawPrivate(kp.private as ECPrivateKey)))
        save()
        return kp
    }

    // ---- push subscriptions ----
    @Synchronized
    fun subscriptions(): List<PushSubscription> {
        val a = state.optJSONArray("subs") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { PushSubscription(it.getString("endpoint"), it.getString("p256dh"), it.getString("auth")) }
    }

    @Synchronized
    fun addSubscription(s: PushSubscription) {
        val list = subscriptions().filter { it.endpoint != s.endpoint } + s
        state.put("subs", JSONArray(list.map { JSONObject().put("endpoint", it.endpoint).put("p256dh", it.p256dh).put("auth", it.auth) }))
        save()
    }

    @Synchronized
    fun removeSubscription(endpoint: String) {
        val list = subscriptions().filter { it.endpoint != endpoint }
        state.put("subs", JSONArray(list.map { JSONObject().put("endpoint", it.endpoint).put("p256dh", it.p256dh).put("auth", it.auth) }))
        save()
    }

    // ---- quick replies ----
    private val defaultReplies = listOf(
        "Ik kom eraan!",
        "Een ogenblikje alstublieft.",
        "Wilt u het pakketje voor de deur leggen? Dank u wel!",
        "We zijn nu niet thuis. Komt u later terug?",
        "Geen interesse, dank u wel.",
        "Wie is daar?",
    )

    @Synchronized
    fun replies(): List<Reply> {
        val a = state.optJSONArray("replies") ?: return defaultReplies.mapIndexed { i, t -> Reply("d$i", t, false) }
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Reply(it.getString("id"), it.getString("text"), it.optBoolean("voice")) }
    }

    @Synchronized
    fun saveReplies(list: List<Reply>) {
        state.put("replies", JSONArray(list.map { JSONObject().put("id", it.id).put("text", it.text).put("voice", it.useVoice) }))
        save()
    }

    // ---- ring history ----
    @Synchronized
    fun addRing(epochSec: Long, type: String) {
        val a = state.optJSONArray("rings") ?: JSONArray()
        a.put(JSONObject().put("time", epochSec).put("type", type))
        while (a.length() > 100) a.remove(0)
        state.put("rings", a); save()
    }

    @Synchronized fun rings(): JSONArray = state.optJSONArray("rings") ?: JSONArray()
}
