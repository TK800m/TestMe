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

/** App settings. The TP-Link password is encrypted with a key in the Android Keystore. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("deurbel", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v.trim()).apply()

    var password: String
        get() = sp.getString("pw", null)?.let { SecretBox.decrypt(it) } ?: ""
        set(v) = sp.edit().putString("pw", SecretBox.encrypt(v)).apply()

    var certPin: String?
        get() = sp.getString("pin", null)
        set(v) = sp.edit().putString("pin", v).apply()

    var notifications: Boolean
        get() = sp.getBoolean("notify", true)
        set(v) = sp.edit().putBoolean("notify", v).apply()

    /** Event types that trigger a notification; empty = every event. */
    var ringTypes: Set<String>
        get() = sp.getStringSet("ring_types", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("ring_types", v).apply()

    var seenTypes: Set<String>
        get() = sp.getStringSet("seen_types", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("seen_types", v).apply()

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

    var hiddenClips: Set<String>
        get() = sp.getStringSet("hidden", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()

    var responsesJson: String?
        get() = sp.getString("responses", null)
        set(v) = sp.edit().putString("responses", v).apply()

    val configured get() = host.isNotBlank() && password.isNotBlank()
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
