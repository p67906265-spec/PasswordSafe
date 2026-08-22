package it.paolo.passwordsafe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SyncCredentialStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("passwordsafe_sync_auth", Context.MODE_PRIVATE)
    private val alias = "passwordsafe_sync_refresh"

    fun save(email: String, refreshToken: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(refreshToken.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("email", email)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun email(): String = prefs.getString("email", "") ?: ""

    fun token(): String? = runCatching {
        val iv = Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)
        val encrypted = Base64.decode(prefs.getString("token", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    fun clear() = prefs.edit().clear().apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }
}
