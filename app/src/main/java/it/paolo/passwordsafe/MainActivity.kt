package it.paolo.passwordsafe

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    private lateinit var security: SecurityStore
    private lateinit var vault: VaultStore
    private var items = mutableListOf<VaultItem>()
    private var pendingBackup: ByteArray? = null
    private var vaultTypeFilter = "NONE"
    private val revealedPins = mutableSetOf<String>()
    private val blue = Color.rgb(22, 93, 255)
    private var loginSafe: SafeView? = null

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
        WindowCompat.setDecorFitsSystemWindows(window, true)
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
        window.statusBarColor = Color.rgb(39,35,132)
        val body = column(Gravity.CENTER_HORIZONTAL).apply { setPadding(dp(28),dp(18),dp(28),dp(24)) }
        body.addView(TextView(this).apply{text="Cassaforte";textSize=28f;setTextColor(Color.WHITE);setTypeface(typeface,1);gravity=Gravity.CENTER})
        body.addView(TextView(this).apply{text="La tua sicurezza, sempre con te";textSize=14f;setTextColor(Color.rgb(215,211,255));gravity=Gravity.CENTER;setPadding(0,dp(4),0,0)})
        loginSafe = SafeView(this)
        body.addView(loginSafe, LinearLayout.LayoutParams(-1,dp(300)).apply{setMargins(0,dp(12),0,dp(2))})
        val pin = field("PIN di 6 cifre", true); body.addView(pin.first)
        val error = errorText(); body.addView(error)
        body.addView(button("APRI CASSAFORTE") {
            if (security.verifyPin(pin.second.text.toString())) {
                security.failedAttempts = 0; openSafe()
            } else {
                security.failedAttempts++
                showError(error, if (security.failedAttempts >= 5) "PIN errato. Ora puoi recuperare l’accesso." else "PIN errato (${security.failedAttempts}/5).")
            }
        })
        val links=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER
            addView(MaterialButton(this@MainActivity).apply{text="Impronta";isAllCaps=false;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{authenticate()}},LinearLayout.LayoutParams(0,dp(52),1f))
            addView(MaterialButton(this@MainActivity).apply{text="Accesso dimenticato";isAllCaps=false;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{showRecovery()}},LinearLayout.LayoutParams(0,dp(52),1f))}
        body.addView(links)
        setContentView(ScrollView(this).apply{setBackgroundColor(Color.rgb(48,43,151));addView(body)})
        if (tryBiometric && canUseBiometric()) authenticate()
    }

    private fun openSafe() {
        val safe = loginSafe
        if (safe == null) showVault() else safe.open { showVault() }
    }

    private fun authenticate() {
        if (!canUseBiometric()) return showLogin(false)
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { openSafe() }
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
        vaultTypeFilter = "NONE"
        showCategoryMenu()
    }

    private fun fadeTo(next: () -> Unit) {
        val old = window.decorView.findViewById<View>(android.R.id.content)
        old.animate().alpha(0f).setDuration(170).withEndAction {
            next()
            val fresh = window.decorView.findViewById<View>(android.R.id.content)
            fresh.alpha = 0f
            fresh.animate().alpha(1f).setDuration(230).start()
        }.start()
    }

    private fun showCategoryMenu() {
        window.statusBarColor = Color.rgb(27,52,78)
        val body = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(30,65,94));setPadding(0,dp(12),0,dp(110))
            addView(darkHeader("Cassaforte", false))
            addView(categoryMenuRow("Account",items.count{it.type=="ACCOUNT"},"ACCOUNT"))
            addView(categoryMenuRow("PIN",items.count{it.type=="PIN"},"PIN"))
            addView(categoryMenuRow("Login",items.count{it.type=="LOGIN"},"LOGIN"))
            addView(categoryMenuRow("Email",items.count{it.type=="EMAIL"},"EMAIL"))
            addView(menuActionRow("Impostazioni","⚙") { showSettingsMenu() })
        }
        setDarkScreen(body)
    }

    private fun darkHeader(label:String, back:Boolean)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(20),dp(10),dp(16),dp(16));setBackgroundColor(Color.rgb(27,52,78))
        if(back)addView(TextView(this@MainActivity).apply{text="‹";textSize=44f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setOnClickListener{fadeTo{showCategoryMenu()}}},LinearLayout.LayoutParams(dp(54),dp(58)))
        addView(TextView(this@MainActivity).apply{text=label;textSize=26f;setTextColor(Color.WHITE);setTypeface(typeface,1);gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(58),1f))
        if(!back)addView(TextView(this@MainActivity).apply{text="⚡";textSize=23f;gravity=Gravity.CENTER;setOnClickListener{showGeneratedPassword()}},LinearLayout.LayoutParams(dp(52),dp(58)))
        if(back)addView(TextView(this@MainActivity).apply{text="⌕";textSize=34f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setOnClickListener{showVaultSearch()}},LinearLayout.LayoutParams(dp(58),dp(58)))
    }

    private fun categoryMenuRow(label:String,count:Int,type:String)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(28),0,dp(26),0)
        background=GradientDrawable().apply{setColor(Color.rgb(31,68,98));setStroke(dp(1),Color.rgb(66,96,120))}
        addView(TextView(this@MainActivity).apply{text=label;textSize=21f;setTextColor(Color.rgb(153,219,239));gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(74),1f))
        addView(TextView(this@MainActivity).apply{text=count.toString();textSize=14f;gravity=Gravity.CENTER;setTextColor(Color.rgb(205,225,233));background=GradientDrawable().apply{setColor(Color.rgb(73,100,120));shape=GradientDrawable.OVAL}},LinearLayout.LayoutParams(dp(38),dp(38)).apply{setMargins(0,0,dp(16),0)})
        addView(TextView(this@MainActivity).apply{text="›";textSize=36f;gravity=Gravity.CENTER;setTextColor(Color.rgb(153,219,239))},LinearLayout.LayoutParams(dp(30),dp(60)))
        setOnClickListener{vaultTypeFilter=type;fadeTo{renderVault("")}}
    }

    private fun menuActionRow(label:String,icon:String,action:()->Unit)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(28),0,dp(26),0)
        background=GradientDrawable().apply{setColor(Color.rgb(27,58,84));setStroke(dp(1),Color.rgb(66,96,120))}
        addView(TextView(this@MainActivity).apply{text=icon;textSize=23f;gravity=Gravity.CENTER;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(dp(44),dp(70)).apply{setMargins(0,0,dp(14),0)})
        addView(TextView(this@MainActivity).apply{text=label;textSize=20f;setTextColor(Color.rgb(153,219,239));gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(70),1f))
        addView(TextView(this@MainActivity).apply{text="›";textSize=36f;gravity=Gravity.CENTER;setTextColor(Color.rgb(153,219,239))},LinearLayout.LayoutParams(dp(30),dp(60)))
        setOnClickListener{action()}
    }

    private fun showVaultSearch() {
        val input=dialogField("Cerca","")
        AlertDialog.Builder(this).setTitle("Cerca nella categoria").setView(input).setNegativeButton("Annulla",null).setPositiveButton("Cerca"){_,_->renderVault(input.text.toString().trim())}.show()
    }

    private fun setDarkScreen(content:View) {
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.rgb(17,38,55));addView(content)}
        val frame=FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(17,38,55));addView(scroll,FrameLayout.LayoutParams(-1,-1))
            addView(MaterialButton(this@MainActivity).apply{text="＋";textSize=31f;cornerRadius=dp(34);setTextColor(Color.rgb(22,55,78));setBackgroundColor(Color.rgb(151,220,239));elevation=12f;setOnClickListener{showCreateTypeMenu()}},FrameLayout.LayoutParams(dp(68),dp(68),Gravity.BOTTOM or Gravity.END).apply{setMargins(0,0,dp(28),dp(28))})
        }
        ViewCompat.setOnApplyWindowInsetsListener(frame){view,insets->val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());view.setPadding(0,bars.top,0,bars.bottom);insets}
        setContentView(frame)
    }

    private fun renderVault(filter: String) {
        window.statusBarColor = Color.rgb(27,52,78)
        val body = column().apply { setPadding(0,0,0,dp(110));setBackgroundColor(Color.rgb(17,38,55));addView(darkHeader(when(vaultTypeFilter){"ACCOUNT"->"Account";"PIN"->"PIN";"LOGIN"->"Login";else->"Email"},true),LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,0,0,0)}) }
        val filtered=items.filter{it.type==vaultTypeFilter&&(filter.isBlank()||it.title.contains(filter,true)||it.username.contains(filter,true))}
        if(vaultTypeFilter!="NONE" && filtered.isEmpty())body.addView(TextView(this).apply{text="Nessun elemento in questa categoria";gravity=Gravity.CENTER;textSize=17f;setTextColor(Color.DKGRAY);setPadding(20,60,20,60)})
        filtered.sortedBy{it.title.lowercase()}.forEach{body.addView(itemListRow(it))}
        setDarkScreen(body)
    }

    private fun itemListRow(item:VaultItem)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18),0,dp(16),0)
        background=GradientDrawable().apply{setColor(Color.rgb(17,38,55));setStroke(dp(1),Color.rgb(60,83,101))}
        val symbol=when(item.type){"PIN"->"▦";"ACCOUNT"->"▣";"EMAIL"->"✉";else->"▢"}
        addView(TextView(this@MainActivity).apply{text=symbol;textSize=30f;gravity=Gravity.CENTER;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(dp(58),dp(84)).apply{setMargins(0,0,dp(10),0)})
        addView(TextView(this@MainActivity).apply{text=item.title;textSize=21f;maxLines=1;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(84),1f))
        addView(TextView(this@MainActivity).apply{text="›";textSize=38f;gravity=Gravity.CENTER;setTextColor(Color.rgb(153,219,239))},LinearLayout.LayoutParams(dp(34),dp(70)))
        setOnClickListener{showItemDialog(item)}
    }

    private fun categoryTile(label:String,count:Int,type:String,color:Int)=MaterialCardView(this).apply{
        radius=dp(14).toFloat();cardElevation=if(vaultTypeFilter==type)6f else 2f;setCardBackgroundColor(color);strokeWidth=if(vaultTypeFilter==type)3 else 0;strokeColor=blue;alpha=if(vaultTypeFilter=="NONE"||vaultTypeFilter==type)1f else .28f
        addView(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(2),dp(12),dp(2),dp(10));addView(TextView(this@MainActivity).apply{text=label;textSize=14f;maxLines=1;setTextColor(Color.rgb(28,36,70));setTypeface(typeface,1);gravity=Gravity.CENTER});addView(TextView(this@MainActivity).apply{text=count.toString();textSize=10f;setTextColor(Color.GRAY);gravity=Gravity.CENTER;setPadding(0,dp(3),0,0)})});setOnClickListener{vaultTypeFilter=if(vaultTypeFilter==type)"NONE" else type;renderVault("")}
    }

    private fun vaultBottomBar()=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;setPadding(dp(6),dp(10),dp(6),dp(14));setBackgroundColor(Color.WHITE);elevation=16f
        addView(navButton("🔐\nCassaforte"){vaultTypeFilter="NONE";renderVault("")},LinearLayout.LayoutParams(0,dp(76),1f));addView(navButton("⚡\nGenera"){showGeneratedPassword()},LinearLayout.LayoutParams(0,dp(76),1f));addView(MaterialButton(this@MainActivity).apply{text="＋";textSize=30f;cornerRadius=dp(36);setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(255,100,92));setOnClickListener{showCreateTypeMenu()}},LinearLayout.LayoutParams(dp(70),dp(70)).apply{setMargins(dp(5),0,dp(5),0)});addView(navButton("⚙\nImpostazioni"){showSettingsMenu()},LinearLayout.LayoutParams(0,dp(76),1f))
    }
    private fun navButton(label:String,action:()->Unit)=MaterialButton(this).apply{text=label;textSize=10f;isAllCaps=false;maxLines=2;minWidth=0;setPadding(0,0,0,0);setTextColor(Color.rgb(55,62,88));setBackgroundColor(Color.TRANSPARENT);setOnClickListener{action()}}
    private fun showSettingsMenu(){AlertDialog.Builder(this).setTitle("Impostazioni").setItems(arrayOf("Backup / Ripristino","Blocca cassaforte")){_,which->if(which==0)showBackupMenu()else showLogin(false)}.setNegativeButton("Chiudi",null).show()}

    private fun itemCard(item: VaultItem) = MaterialCardView(this).apply {
        radius = 30f; setCardBackgroundColor(Color.WHITE); cardElevation = 5f
        val box = column().apply { setPadding(dp(20),dp(18),dp(20),dp(18)) }
        val kind = when(item.type) { "PIN" -> "PIN"; "ACCOUNT" -> "ACCOUNT"; "EMAIL" -> "EMAIL"; else -> "LOGIN" }
        val accent=when(item.type){"PIN"->Color.rgb(255,100,92);"ACCOUNT"->Color.rgb(70,72,205);"EMAIL"->Color.rgb(218,155,20);else->Color.rgb(30,175,112)}
        box.addView(TextView(this@MainActivity).apply { text = "●  $kind"; textSize = if(item.type=="EMAIL")11f else 13f; setTextColor(accent); setTypeface(typeface,1) })
        box.addView(TextView(this@MainActivity).apply { text = item.title; textSize = if(item.type=="EMAIL")18f else 22f; maxLines=1;setTextColor(Color.rgb(23,32,51)); setTypeface(typeface,1); setPadding(0,dp(5),0,0) })
        if(item.type!="EMAIL")box.addView(TextView(this@MainActivity).apply {
            text = if(item.type=="PIN") if(revealedPins.contains(item.id)) item.password else "✱ ✱ ✱ ✱ ✱ ✱" else item.username
            textSize = if(item.type=="PIN" && revealedPins.contains(item.id)) 32f else 15f
            gravity = if(item.type=="PIN" && revealedPins.contains(item.id)) Gravity.CENTER else Gravity.START
            if(item.type=="PIN" && revealedPins.contains(item.id))setTypeface(typeface,1)
            setTextColor(Color.DKGRAY); setPadding(0,dp(8),0,dp(14))
        }) else box.addView(TextView(this@MainActivity).apply { text="Credenziali protette";textSize=12f;setTextColor(Color.GRAY);setPadding(0,dp(4),0,dp(10)) })
        val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        if(item.type=="PIN") actions.addView(smallButton(if(revealedPins.contains(item.id)) "NASCONDI PIN" else "MOSTRA PIN") { if(revealedPins.contains(item.id))revealedPins.remove(item.id)else revealedPins.add(item.id);renderVault("") },LinearLayout.LayoutParams(-1,dp(48)))
        else { actions.addView(smallButton(if(item.type=="ACCOUNT"||item.type=="EMAIL") "EMAIL" else "UTENTE") { copySecure(if(item.type=="ACCOUNT"||item.type=="EMAIL") "Email" else "Nome utente", item.username) }, LinearLayout.LayoutParams(0,-2,1f));actions.addView(smallButton("PASSWORD") { copySecure("Password", item.password) }, LinearLayout.LayoutParams(0,-2,1f));actions.addView(smallButton("APRI") { showItemDialog(item) }, LinearLayout.LayoutParams(0,-2,1f)) }
        box.addView(actions); addView(box)
        if(item.type=="PIN")setOnLongClickListener{showItemDialog(item);true}
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,10,0,14) }
    }

    private fun showCreateTypeMenu() {
        val labels = arrayOf("👤  Account", "💳  PIN", "🌐  Login", "✉  Email")
        AlertDialog.Builder(this).setTitle("Cosa vuoi creare?").setItems(labels) { _, which ->
            showItemDialog(null, when(which) { 0 -> "ACCOUNT"; 1 -> "PIN"; 2 -> "LOGIN"; else -> "EMAIL" })
        }.setNegativeButton("Annulla",null).show()
    }

    private fun showItemDialog(existing: VaultItem?, requestedType: String? = null) {
        val itemType = existing?.type ?: requestedType ?: "LOGIN"
        val box = column().apply{setPadding(dp(18),dp(18),dp(18),dp(8))}
        val typeLabel = when(itemType) { "PIN" -> "PIN"; "ACCOUNT" -> "Account"; "EMAIL" -> "Email"; else -> "Login" }
        val typeIcon=when(itemType){"PIN"->"•••";"ACCOUNT"->"👤";"EMAIL"->"✉";else->"🔒"}
        box.addView(LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(22),dp(20),dp(18),dp(20));background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.rgb(62,55,174),Color.rgb(94,75,198))).apply{cornerRadius=dp(22).toFloat()};addView(TextView(this@MainActivity).apply{text=typeIcon;textSize=30f});addView(TextView(this@MainActivity).apply{text="  ${if(existing==null) "Nuovo" else "Modifica"} $typeLabel";textSize=25f;setTextColor(Color.WHITE);setTypeface(typeface,1)})},LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,0,0,dp(18))})
        val titleF = styledDialogField(when(itemType) { "PIN" -> "Nome banca"; "ACCOUNT" -> "Nome account"; else -> "Nome del sito" }, existing?.title ?: "")
        val userF = styledDialogField(if(itemType=="ACCOUNT"||itemType=="EMAIL") "Email" else "Nome utente / email", existing?.username ?: "")
        val passF = styledDialogField(if(itemType=="PIN") "PIN" else "Password", existing?.password ?: "")
        if(itemType=="PIN") passF.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        if(itemType!="EMAIL")box.addView(titleF)
        if(itemType!="PIN") box.addView(userF)
        box.addView(passF)
        if(itemType!="PIN") box.addView(LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;addView(compactDialogButton("GENERA") { passF.setText(generatedPassword()) },LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(0,0,dp(5),0)});addView(compactDialogButton("COPIA") { copySecure("Password",passF.text.toString()) },LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(dp(5),0,0,0)})},LinearLayout.LayoutParams(-1,dp(52)).apply{setMargins(0,dp(8),0,0)})
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva", null)
            .apply { if(existing!=null) setNeutralButton("Elimina", null) }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if ((itemType!="EMAIL"&&titleF.text.toString().isBlank()) || passF.text.toString().isBlank()) return@setOnClickListener toast(if(itemType=="PIN") "Inserisci banca e PIN" else "Completa i campi richiesti")
                if (itemType!="PIN" && userF.text.toString().isBlank()) return@setOnClickListener toast(if(itemType=="ACCOUNT"||itemType=="EMAIL") "Inserisci l’email" else "Inserisci utente o email")
                val savedTitle=if(itemType=="EMAIL")userF.text.toString()else titleF.text.toString()
                if (existing == null) items.add(VaultItem(title=savedTitle, username=userF.text.toString(), password=passF.text.toString(), category=typeLabel, type=itemType))
                else { existing.title=savedTitle; existing.username=userF.text.toString(); existing.password=passF.text.toString(); existing.category=typeLabel; existing.type=itemType }
                vault.save(items); dialog.dismiss(); vaultTypeFilter=itemType; renderVault("")
            }
            if(existing!=null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this).setTitle("Eliminare ${existing.title}?").setNegativeButton("No",null).setPositiveButton("Elimina") { _,_-> items.remove(existing); vault.save(items); dialog.dismiss(); renderVault("") }.show()
            }
        }; dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(62,55,174));dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(90,90,110))
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
                runCatching { vault.restoreBackup(bytes,input.text.toString()) }.onSuccess { items=it; vault.save(items); vaultTypeFilter="NONE";showCategoryMenu();toast("Backup ripristinato") }.onFailure { toast("PIN errato o backup non valido") }
            }.show()
    }
    private fun copySecure(label:String,value:String) {
        if(value.isBlank()) return toast("Campo vuoto")
        val clipboard=getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label,value)); toast("$label copiato; sarà cancellato tra 30 secondi")
        android.os.Handler(mainLooper).postDelayed({ if(clipboard.hasPrimaryClip()) clipboard.setPrimaryClip(ClipData.newPlainText("", "")) },30000)
    }
    private fun dialogField(hint:String,value:String)=EditText(this).apply { this.hint=hint; setText(value); setTextColor(Color.rgb(23,32,51)); setHintTextColor(Color.GRAY); setPadding(18,20,18,20) }
    private fun styledDialogField(hint:String,value:String)=dialogField(hint,value).apply{background=GradientDrawable().apply{setColor(Color.rgb(248,247,252));cornerRadius=dp(16).toFloat();setStroke(dp(1),Color.rgb(218,216,231))};layoutParams=LinearLayout.LayoutParams(-1,dp(58)).apply{setMargins(0,dp(5),0,dp(5))};setPadding(dp(18),0,dp(18),0)}
    private fun smallButton(text:String, action:()->Unit)=MaterialButton(this).apply { this.text=text; textSize=11f; setOnClickListener{action()}; setTextColor(Color.WHITE); setBackgroundColor(blue) }
    private fun compactDialogButton(text:String,action:()->Unit)=smallButton(text,action).apply{maxLines=1;isSingleLine=true;insetTop=0;insetBottom=0;setPadding(dp(8),0,dp(8),0)}
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()

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
