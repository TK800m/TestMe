package nl.tapo.deurbel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** One doorbell or camera. */
data class Dev(val id: String, val name: String, val host: String, val kind: String) {
    val isDoorbell get() = kind != "camera"
}

/**
 * App settings. The TP-Link password is encrypted with a key in the Android Keystore.
 * Device-specific settings are stored per device ("dev.<id>.<key>"); [devId] picks the
 * device, null means the device currently shown in the app.
 */
class Prefs(context: Context, private val devId: String? = null) {
    private val sp = context.getSharedPreferences("deurbel", Context.MODE_PRIVATE)

    init { migrate() }

    /** Older versions had one doorbell; move its settings to device "d1". */
    private fun migrate() {
        if (sp.contains("devices")) return
        val host = sp.getString("host", "") ?: ""
        val e = sp.edit()
        if (host.isNotBlank()) {
            e.putString("devices", JSONArray().put(JSONObject().put("id", "d1").put("name", "Voordeur").put("host", host).put("kind", "doorbell")).toString())
            e.putString("current", "d1")
            sp.getString("pin", null)?.let { e.putString("dev.d1.pin", it) }
            listOf("ring_types", "seen_types", "hidden").forEach { k -> sp.getStringSet(k, null)?.let { e.putStringSet("dev.d1.$k", it) } }
            listOf("type_stats", "type_labels").forEach { k -> sp.getString(k, null)?.let { e.putString("dev.d1.$k", it) } }
        } else e.putString("devices", "[]")
        e.apply()
    }

    // ---- devices ----
    var devices: List<Dev>
        get() = JSONArray(sp.getString("devices", "[]") ?: "[]").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Dev(it.getString("id"), it.optString("name", "Apparaat"), it.getString("host"), it.optString("kind", "doorbell")) }
        }
        set(v) = sp.edit().putString("devices", JSONArray(v.map { JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("kind", it.kind) }).toString()).apply()

    var currentId: String
        get() = sp.getString("current", null)?.takeIf { id -> devices.any { it.id == id } } ?: devices.firstOrNull()?.id ?: ""
        set(v) = sp.edit().putString("current", v).apply()

    private val id get() = devId ?: currentId
    val device: Dev? get() = devices.firstOrNull { it.id == id }

    fun newDeviceId() = "d" + (1 + (devices.maxOfOrNull { it.id.removePrefix("d").toIntOrNull() ?: 0 } ?: 0))
    fun updateDevice(d: Dev) { devices = devices.map { if (it.id == d.id) d else it } }
    fun removeDevice(rid: String) {
        devices = devices.filter { it.id != rid }
        val e = sp.edit(); sp.all.keys.filter { it.startsWith("dev.$rid.") }.forEach { e.remove(it) }; e.apply()
    }

    private fun k(key: String) = "dev.$id.$key"

    /** Address of the current device. */
    val host: String get() = device?.host ?: ""

    var password: String
        get() = sp.getString("pw", null)?.let { SecretBox.decrypt(it) } ?: ""
        set(v) = sp.edit().putString("pw", SecretBox.encrypt(v)).apply()

    var certPin: String?
        get() = sp.getString(k("pin"), null)
        set(v) = sp.edit().putString(k("pin"), v).apply()

    var notifications: Boolean
        get() = sp.getBoolean("notify", true)
        set(v) = sp.edit().putBoolean("notify", v).apply()

    /** Event types that trigger a notification; empty = every event. */
    var ringTypes: Set<String>
        get() = sp.getStringSet(k("ring_types"), emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(k("ring_types"), v).apply()

    /** Per event type: how often and when it was last seen (JSON). */
    var typeStats: JSONObject
        get() = JSONObject(sp.getString(k("type_stats"), "{}") ?: "{}")
        set(v) = sp.edit().putString(k("type_stats"), v.toString()).apply()

    /** Per event type: label key chosen by the user or learnt (JSON, see EventTypes.LABELS). */
    var typeLabels: JSONObject
        get() = JSONObject(sp.getString(k("type_labels"), "{}") ?: "{}")
        set(v) = sp.edit().putString(k("type_labels"), v.toString()).apply()

    var seenTypes: Set<String>
        get() = sp.getStringSet(k("seen_types"), emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(k("seen_types"), v).apply()

    var hiddenClips: Set<String>
        get() = sp.getStringSet(k("hidden"), emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(k("hidden"), v).apply()

    /** "system", "light" or "dark". */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    var hdLive: Boolean
        get() = sp.getBoolean("hd", true)
        set(v) = sp.edit().putBoolean("hd", v).apply()

    var rtspUser: String
        get() = sp.getString("rtsp_user", "") ?: ""
        set(v) = sp.edit().putString("rtsp_user", v.trim()).apply()

    var rtspPassword: String
        get() = sp.getString("rtsp_pw", null)?.let { SecretBox.decrypt(it) } ?: ""
        set(v) = sp.edit().putString("rtsp_pw", SecretBox.encrypt(v)).apply()

    var responsesJson: String?
        get() = sp.getString("responses", null)
        set(v) = sp.edit().putString("responses", v).apply()

    /** Firmware updates installed by the devices themselves at [autoUpdateTime] (local time). */
    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()

    var autoUpdateTime: String
        get() = sp.getString("auto_update_time", "01:00") ?: "01:00"
        set(v) = sp.edit().putString("auto_update_time", v).apply()

    /** "Niet storen" until this moment (ms); 0 = off. */
    var dndUntil: Long
        get() = sp.getLong("dnd_until", 0)
        set(v) = sp.edit().putLong("dnd_until", v).apply()
    val dndActive get() = dndUntil > System.currentTimeMillis()

    /** Last events that gave a notification: [{"t":epochSec,"dev":"d1","type":"10"}]. */
    var history: JSONArray
        get() = JSONArray(sp.getString("history", "[]") ?: "[]")
        set(v) = sp.edit().putString("history", v.toString()).apply()

    fun addHistory(epochSec: Long, dev: String, type: String) {
        val a = history
        a.put(JSONObject().put("t", epochSec).put("dev", dev).put("type", type))
        while (a.length() > 30) a.remove(0)
        history = a
    }

    val configured get() = password.isNotBlank() && devices.isNotEmpty()
}

private object SecretBox {
    private const val ALIAS = "deurbel_secret"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return kg.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(s: String): String? = try {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12))
    } catch (e: Exception) { null }
}
