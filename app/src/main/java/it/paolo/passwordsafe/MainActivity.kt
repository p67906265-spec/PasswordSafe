package it.paolo.passwordsafe

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    private lateinit var security: SecurityStore
    private val blue = Color.rgb(22, 93, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        security = SecurityStore(this)
        if (security.configured) showLogin() else showSetup()
    }

    private fun showSetup() {
        val body = column()
        body.addView(title("Crea la tua cassaforte", "Scegli un PIN e due domande per recuperare l’accesso."))
        val pin = field("PIN di 6 cifre", true)
        val confirm = field("Ripeti il PIN", true)
        val q1 = field("Prima domanda di sicurezza")
        val a1 = field("Prima risposta")
        val q2 = field("Seconda domanda di sicurezza")
        val a2 = field("Seconda risposta")
        listOf(pin, confirm, q1, a1, q2, a2).forEach { body.addView(it.first) }
        val error = errorText(); body.addView(error)
        body.addView(button("CREA CASSAFORTE") {
            val p = pin.second.text.toString()
            if (!p.matches(Regex("\\d{6}"))) return@button showError(error, "Il PIN deve contenere 6 cifre.")
            if (p != confirm.second.text.toString()) return@button showError(error, "I due PIN non coincidono.")
            val values = listOf(q1, a1, q2, a2).map { it.second.text.toString().trim() }
            if (values.any { it.length < 3 }) return@button showError(error, "Completa entrambe le domande e le risposte.")
            if (values[0].equals(values[2], true)) return@button showError(error, "Scegli due domande differenti.")
            security.configure(p, values[0], values[1], values[2], values[3])
            showVault()
        })
        setPage(body)
    }

    private fun showLogin(tryBiometric: Boolean = true) {
        val body = column(Gravity.CENTER_HORIZONTAL)
        body.addView(title("Password Safe", "La tua cassaforte protetta"))
        val pin = field("Inserisci il PIN", true); body.addView(pin.first)
        val error = errorText(); body.addView(error)
        body.addView(button("SBLOCCA") {
            if (security.verifyPin(pin.second.text.toString())) {
                security.failedAttempts = 0; showVault()
            } else {
                security.failedAttempts++
                showError(error, if (security.failedAttempts >= 5) "PIN errato. Ora puoi recuperare l’accesso." else "PIN errato (${security.failedAttempts}/5).")
            }
        })
        val bio = button("USA L’IMPRONTA") { authenticate() }; body.addView(bio)
        val recover = button("PROBLEMI DI ACCESSO") { showRecovery() }; body.addView(recover)
        setPage(body)
        if (tryBiometric && canUseBiometric()) authenticate()
    }

    private fun authenticate() {
        if (!canUseBiometric()) return showLogin(false)
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { showVault() }
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sblocca Password Safe").setSubtitle("Usa l’impronta digitale")
            .setNegativeButtonText("Usa PIN").build())
    }

    private fun canUseBiometric() = security.biometricEnabled &&
        BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun showRecovery() {
        val body = column()
        body.addView(title("Recupera l’accesso", "Rispondi correttamente a entrambe le domande."))
        val a1 = field(security.question1()); val a2 = field(security.question2())
        body.addView(a1.first); body.addView(a2.first)
        val error = errorText(); body.addView(error)
        body.addView(button("VERIFICA RISPOSTE") {
            if (security.verifyAnswers(a1.second.text.toString(), a2.second.text.toString())) showNewPin()
            else showError(error, "Una o entrambe le risposte non sono corrette.")
        })
        body.addView(button("INDIETRO") { showLogin(false) })
        setPage(body)
    }

    private fun showNewPin() {
        val body = column(); body.addView(title("Crea un nuovo PIN", "Inserisci un nuovo PIN di 6 cifre."))
        val p1 = field("Nuovo PIN", true); val p2 = field("Ripeti il nuovo PIN", true)
        body.addView(p1.first); body.addView(p2.first)
        val error = errorText(); body.addView(error)
        body.addView(button("SALVA NUOVO PIN") {
            val pin = p1.second.text.toString()
            if (!pin.matches(Regex("\\d{6}")) || pin != p2.second.text.toString()) showError(error, "Controlla il PIN: servono 6 cifre uguali.")
            else { security.resetPin(pin); showVault() }
        })
        setPage(body)
    }

    private fun showVault() {
        val body = column(); body.addView(title("La mia cassaforte", "Nessuna password salvata"))
        val card = MaterialCardView(this).apply {
            radius = 28f; setCardBackgroundColor(Color.WHITE); cardElevation = 4f
            addView(TextView(this@MainActivity).apply {
                text = "🔐\n\nLa cassaforte è pronta\n\nNella prossima versione aggiungeremo account, ricerca e generatore di password."
                textSize = 18f; gravity = Gravity.CENTER; setPadding(36, 60, 36, 60)
            })
        }
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 24, 0, 24) })
        body.addView(button("BLOCCA") { showLogin(false) })
        setPage(body)
    }

    private fun setPage(body: LinearLayout) {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(244,247,252)); addView(body) }
        setContentView(scroll)
    }
    private fun column(gravityValue: Int = Gravity.NO_GRAVITY) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = gravityValue; setPadding(48, 56, 48, 48)
    }
    private fun title(head: String, sub: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply { text = head; textSize = 30f; setTextColor(Color.rgb(23,32,51)); setTypeface(typeface, 1) })
        addView(TextView(this@MainActivity).apply { text = sub; textSize = 16f; setTextColor(Color.DKGRAY); setPadding(0, 8, 0, 28) })
    }
    private fun field(hint: String, pin: Boolean = false): Pair<TextInputLayout, TextInputEditText> {
        val edit = TextInputEditText(this).apply {
            this.hint = hint
            setTextColor(Color.rgb(23, 32, 51))
            setHintTextColor(Color.rgb(92, 101, 120))
            if (pin) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val layout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE; boxBackgroundColor = Color.WHITE
            setBoxStrokeColorStateList(android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(blue, Color.rgb(150, 158, 175))
            ))
            hintTextColor = android.content.res.ColorStateList.valueOf(Color.rgb(70, 80, 100))
            if (pin) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(edit)
        }
        layout.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 7, 0, 7) }
        return layout to edit
    }
    private fun button(text: String, action: () -> Unit) = MaterialButton(this).apply {
        this.text = text; textSize = 15f; setTextColor(Color.WHITE); setBackgroundColor(blue)
        cornerRadius = 22; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, 138).apply { setMargins(0, 10, 0, 10) }
    }
    private fun errorText() = TextView(this).apply { setTextColor(Color.rgb(190,30,45)); textSize = 14f; visibility = View.GONE; setPadding(4,8,4,8) }
    private fun showError(view: TextView, message: String) { view.text = message; view.visibility = View.VISIBLE }
}
