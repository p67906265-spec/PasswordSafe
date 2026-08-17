package it.paolo.passwordsafe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class VaultItem(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var username: String,
    var password: String,
    var url: String = "",
    var notes: String = "",
    var category: String = "Altro",
    var type: String = "LOGIN"
)

class VaultStore(private val context: Context) {
    private val file = context.filesDir.resolve("vault.bin")
    private val alias = "passwordsafe_vault_key"

    fun load(): MutableList<VaultItem> {
        if (!file.exists()) return mutableListOf()
        return runCatching { fromJson(String(decryptDevice(file.readBytes()))) }.getOrDefault(mutableListOf())
    }

    fun save(items: List<VaultItem>) { file.writeBytes(encryptDevice(toJson(items).toByteArray())) }

    fun createBackup(items: List<VaultItem>, pin: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = derive(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(toJson(items).toByteArray())
        return JSONObject().put("format", 1)
            .put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(encrypted))
            .toString().toByteArray()
    }

    fun restoreBackup(bytes: ByteArray, pin: String): MutableList<VaultItem> {
        val obj = JSONObject(String(bytes))
        require(obj.getInt("format") == 1)
        val salt = unb64(obj.getString("salt")); val iv = unb64(obj.getString("iv"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(pin, salt), GCMParameterSpec(128, iv))
        return fromJson(String(cipher.doFinal(unb64(obj.getString("data")))))
    }

    private fun encryptDevice(clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey())
        return cipher.iv + cipher.doFinal(clear)
    }
    private fun decryptDevice(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return cipher.doFinal(data.copyOfRange(12, data.size))
    }
    private fun deviceKey(): java.security.Key {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(alias, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    private fun derive(pin: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 250_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword(); return SecretKeySpec(key, "AES")
    }
    private fun toJson(items: List<VaultItem>) = JSONArray().apply { items.forEach { i ->
        put(JSONObject().put("id", i.id).put("title", i.title).put("username", i.username)
            .put("password", i.password).put("url", i.url).put("notes", i.notes).put("category", i.category).put("type", i.type))
    }}.toString()
    private fun fromJson(json: String): MutableList<VaultItem> {
        val arr = JSONArray(json); return MutableList(arr.length()) { n -> arr.getJSONObject(n).let { o ->
            VaultItem(o.getString("id"), o.getString("title"), o.optString("username"), o.optString("password"),
                o.optString("url"), o.optString("notes"), o.optString("category", "Altro"), o.optString("type", "LOGIN"))
        }}
    }
    private fun b64(data: ByteArray) = Base64.encodeToString(data, Base64.NO_WRAP)
    private fun unb64(data: String) = Base64.decode(data, Base64.NO_WRAP)
}
