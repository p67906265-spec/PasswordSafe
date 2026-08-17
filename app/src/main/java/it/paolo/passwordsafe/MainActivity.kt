package it.paolo.passwordsafe

import android.graphics.Color
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    private lateinit var vault: VaultStore
    private var items = mutableListOf<VaultItem>()
    private var pendingBackup: ByteArray? = null
    private val blue = Color.rgb(22, 93, 255)

    private val createBackupFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) runCatching { pendingBackup?.let { data -> contentResolver.openOutputStream(uri)?.use { it.write(data) } } }
            .onSuccess { toast("Backup cifrato salvato") }.onFailure { toast("Impossibile salvare il backup") }
        pendingBackup = null
    }
    private val openBackupFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("File vuoto") }
            .onSuccess { askRestorePin(it) }.onFailure { toast("Impossibile leggere il backup") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        security = SecurityStore(this)
        vault = VaultStore(this)
        items = vault.load()
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
        renderVault("")
    }

    private fun renderVault(filter: String) {
        val body = column()
        body.addView(title("La mia cassaforte", "${items.size} password salvate"))
        val search = EditText(this).apply {
            hint = "Cerca account o categoria"; setText(filter); setTextColor(Color.rgb(23,32,51)); setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.WHITE); setPadding(28, 18, 28, 18)
            setSingleLine(true)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { if (s.toString() != filter) renderVault(s.toString()) }
            })
        }
        body.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,18) })
        body.addView(button("＋ AGGIUNGI PASSWORD") { showItemDialog(null) })
        val filtered = items.filter { filter.isBlank() || listOf(it.title,it.username,it.category,it.url).any { v -> v.contains(filter,true) } }
        if (filtered.isEmpty()) body.addView(TextView(this).apply {
            text = if (items.isEmpty()) "🔐\n\nLa cassaforte è vuota\nAggiungi la prima password." else "Nessun risultato"
            gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.DKGRAY); setPadding(20,60,20,60)
        })
        filtered.sortedBy { it.title.lowercase() }.forEach { item -> body.addView(itemCard(item)) }
        body.addView(button("GENERA PASSWORD") { showGeneratedPassword() })
        body.addView(button("BACKUP / RIPRISTINO") { showBackupMenu() })
        body.addView(button("BLOCCA") { showLogin(false) })
        setPage(body)
        search.setSelection(search.text.length)
    }

    private fun itemCard(item: VaultItem) = MaterialCardView(this).apply {
        radius = 24f; setCardBackgroundColor(Color.WHITE); cardElevation = 3f
        val box = column().apply { setPadding(28,24,28,24) }
        box.addView(TextView(this@MainActivity).apply { text = item.title; textSize = 21f; setTextColor(Color.rgb(23,32,51)); setTypeface(typeface,1) })
        box.addView(TextView(this@MainActivity).apply { text = "${item.username}\n${item.category}"; textSize = 15f; setTextColor(Color.DKGRAY); setPadding(0,6,0,12) })
        val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(smallButton("UTENTE") { copySecure("Nome utente", item.username) }, LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(smallButton("PASSWORD") { copySecure("Password", item.password) }, LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(smallButton("APRI") { showItemDialog(item) }, LinearLayout.LayoutParams(0,-2,1f))
        box.addView(actions); addView(box)
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,10,0,10) }
    }

    private fun showItemDialog(existing: VaultItem?) {
        val box = column()
        val titleF = dialogField("Nome account", existing?.title ?: "")
        val userF = dialogField("Nome utente / email", existing?.username ?: "")
        val passF = dialogField("Password", existing?.password ?: generatedPassword())
        val categoryF = dialogField("Categoria", existing?.category ?: "Altro")
        val urlF = dialogField("Sito web", existing?.url ?: "")
        val notesF = dialogField("Note", existing?.notes ?: "")
        listOf(titleF,userF,passF,categoryF,urlF,notesF).forEach { box.addView(it) }
        box.addView(smallButton("GENERA NUOVA PASSWORD") { passF.setText(generatedPassword()) })
        val dialog = AlertDialog.Builder(this).setTitle(if(existing==null) "Nuova password" else "Modifica password")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva", null)
            .apply { if(existing!=null) setNeutralButton("Elimina", null) }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (titleF.text.toString().isBlank() || passF.text.toString().isBlank()) return@setOnClickListener toast("Inserisci nome e password")
                if (existing == null) items.add(VaultItem(title=titleF.text.toString(), username=userF.text.toString(), password=passF.text.toString(), url=urlF.text.toString(), notes=notesF.text.toString(), category=categoryF.text.toString()))
                else { existing.title=titleF.text.toString(); existing.username=userF.text.toString(); existing.password=passF.text.toString(); existing.url=urlF.text.toString(); existing.notes=notesF.text.toString(); existing.category=categoryF.text.toString() }
                vault.save(items); dialog.dismiss(); renderVault("")
            }
            if(existing!=null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this).setTitle("Eliminare ${existing.title}?").setNegativeButton("No",null).setPositiveButton("Elimina") { _,_-> items.remove(existing); vault.save(items); dialog.dismiss(); renderVault("") }.show()
            }
        }; dialog.show()
    }

    private fun showGeneratedPassword() {
        val value = generatedPassword()
        AlertDialog.Builder(this).setTitle("Password generata").setMessage(value).setNegativeButton("Chiudi",null)
            .setPositiveButton("Copia") { _,_-> copySecure("Password",value) }.show()
    }

    private fun generatedPassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%&*+-_"
        val random = java.security.SecureRandom(); return (1..20).joinToString("") { chars[random.nextInt(chars.length)].toString() }
    }

    private fun showBackupMenu() {
        AlertDialog.Builder(this).setTitle("Backup cifrato").setItems(arrayOf("Salva backup su Google Drive", "Ripristina da Google Drive")) { _, which ->
            if(which==0) askBackupPin() else openBackupFile.launch(arrayOf("application/octet-stream","application/json","*/*"))
        }.show()
    }
    private fun askBackupPin() {
        val input=dialogField("Conferma il PIN","").apply { inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("Proteggi il backup").setMessage("Il backup sarà cifrato con il PIN attuale.").setView(input)
            .setNegativeButton("Annulla",null).setPositiveButton("Continua") { _,_->
                val pin=input.text.toString(); if(security.verifyPin(pin)) { pendingBackup=vault.createBackup(items,pin); createBackupFile.launch("PasswordSafe-backup.psafe") } else toast("PIN non corretto")
            }.show()
    }
    private fun askRestorePin(bytes: ByteArray) {
        val input=dialogField("PIN usato per il backup","").apply { inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("Ripristina backup").setMessage("Le password attuali verranno sostituite.").setView(input)
            .setNegativeButton("Annulla",null).setPositiveButton("Ripristina") { _,_->
                runCatching { vault.restoreBackup(bytes,input.text.toString()) }.onSuccess { items=it; vault.save(items); renderVault(""); toast("Backup ripristinato") }.onFailure { toast("PIN errato o backup non valido") }
            }.show()
    }
    private fun copySecure(label:String,value:String) {
        if(value.isBlank()) return toast("Campo vuoto")
        val clipboard=getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label,value)); toast("$label copiato; sarà cancellato tra 30 secondi")
        android.os.Handler(mainLooper).postDelayed({ if(clipboard.hasPrimaryClip()) clipboard.setPrimaryClip(ClipData.newPlainText("", "")) },30000)
    }
    private fun dialogField(hint:String,value:String)=EditText(this).apply { this.hint=hint; setText(value); setTextColor(Color.rgb(23,32,51)); setHintTextColor(Color.GRAY); setPadding(18,20,18,20) }
    private fun smallButton(text:String, action:()->Unit)=MaterialButton(this).apply { this.text=text; textSize=11f; setOnClickListener{action()}; setTextColor(Color.WHITE); setBackgroundColor(blue) }
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_SHORT).show()

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
