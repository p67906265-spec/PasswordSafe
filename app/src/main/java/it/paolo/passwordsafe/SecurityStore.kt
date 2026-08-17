package it.paolo.passwordsafe

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SecurityStore(context: Context) {
    private val prefs = context.getSharedPreferences("security", Context.MODE_PRIVATE)

    val configured: Boolean get() = prefs.getBoolean("configured", false)
    var biometricEnabled: Boolean
        get() = prefs.getBoolean("biometric", true)
        set(value) { prefs.edit().putBoolean("biometric", value).apply() }
    var failedAttempts: Int
        get() = prefs.getInt("failed", 0)
        set(value) { prefs.edit().putInt("failed", value).apply() }

    fun configure(pin: String, question1: String, answer1: String, question2: String, answer2: String) {
        val pinSalt = salt()
        val answerSalt1 = salt()
        val answerSalt2 = salt()
        prefs.edit()
            .putString("pin_salt", pinSalt)
            .putString("pin_hash", derive(pin, pinSalt))
            .putString("q1", question1.trim())
            .putString("a1_salt", answerSalt1)
            .putString("a1_hash", derive(normalize(answer1), answerSalt1))
            .putString("q2", question2.trim())
            .putString("a2_salt", answerSalt2)
            .putString("a2_hash", derive(normalize(answer2), answerSalt2))
            .putBoolean("configured", true)
            .putInt("failed", 0)
            .apply()
    }

    fun verifyPin(pin: String): Boolean = verify(pin, "pin")
    fun question1(): String = prefs.getString("q1", "") ?: ""
    fun question2(): String = prefs.getString("q2", "") ?: ""
    fun verifyAnswers(a1: String, a2: String): Boolean =
        verify(normalize(a1), "a1") and verify(normalize(a2), "a2")

    fun resetPin(pin: String) {
        val salt = salt()
        prefs.edit().putString("pin_salt", salt).putString("pin_hash", derive(pin, salt))
            .putInt("failed", 0).apply()
    }

    private fun verify(value: String, prefix: String): Boolean {
        val salt = prefs.getString("${prefix}_salt", null) ?: return false
        val expected = prefs.getString("${prefix}_hash", null) ?: return false
        return MessageDigest.isEqual(derive(value, salt).toByteArray(), expected.toByteArray())
    }

    private fun normalize(value: String) = value.trim().lowercase().replace(Regex("\\s+"), " ")
    private fun salt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.NO_WRAP) }
    private fun derive(value: String, salt: String): String {
        val spec = PBEKeySpec(value.toCharArray(), Base64.decode(salt, Base64.NO_WRAP), 210_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
