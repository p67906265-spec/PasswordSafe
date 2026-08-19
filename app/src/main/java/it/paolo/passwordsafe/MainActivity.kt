package it.paolo.passwordsafe

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.CheckBox
import android.widget.SeekBar
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
        items = mutableListOf()
        if (security.configured || security.masterConfigured) showLogin() else showSetup()
    }

    private fun showSetup() {
        val body = column()
        body.addView(title("Crea la tua cassaforte", "Scegli una password principale di almeno 12 caratteri."))
        val password=field("Password principale");password.second.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val confirm=field("Ripeti la password");confirm.second.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        body.addView(password.first);body.addView(confirm.first)
        val error = errorText(); body.addView(error)
        body.addView(button("CREA CASSAFORTE") {
            val p=password.second.text.toString();if(!strongMasterPassword(p))return@button showError(error,"Usa almeno 12 caratteri, con maiuscola, minuscola e numero.")
            if(p!=confirm.second.text.toString())return@button showError(error,"Le due password non coincidono.")
            val recovery=generateRecoveryCode();security.configureMaster(p);vault.initialize(emptyList(),p,recovery);items=vault.load();showRecoveryCode(recovery)
        })
        setPage(body)
    }

    private fun showLogin(tryBiometric:Boolean=true){if(vault.modern)showMasterLogin(tryBiometric)else showLegacyLogin(tryBiometric)}

    private fun showLegacyLogin(tryBiometric: Boolean = true) {
        window.statusBarColor = Color.rgb(39,35,132)
        val body = column(Gravity.CENTER_HORIZONTAL).apply { setPadding(dp(28),dp(18),dp(28),dp(24)) }
        body.addView(TextView(this).apply{text="Cassaforte";textSize=28f;setTextColor(Color.WHITE);setTypeface(typeface,1);gravity=Gravity.CENTER})
        body.addView(TextView(this).apply{text="La tua sicurezza, sempre con te";textSize=14f;setTextColor(Color.rgb(215,211,255));gravity=Gravity.CENTER;setPadding(0,dp(4),0,0)})
        loginSafe = SafeView(this)
        body.addView(loginSafe, LinearLayout.LayoutParams(-1,dp(300)).apply{setMargins(0,dp(12),0,dp(2))})
        val pin = field("PIN di 6 cifre", true); body.addView(pin.first)
        val error = errorText(); body.addView(error)
        body.addView(button("APRI CASSAFORTE") {
            val wait=security.lockRemainingMillis();if(wait>0)return@button showError(error,"Troppi tentativi. Riprova tra ${formatWait(wait)}.")
            if (security.verifyPin(pin.second.text.toString())) {
                security.failedAttempts = 0;security.recordMasterSuccess();showMasterMigration()
            } else {
                security.failedAttempts++;val delay=security.recordMasterFailure()
                showError(error,if(delay>0)"PIN errato. Accesso sospeso per ${formatWait(delay)}." else "PIN errato.")
            }
        })
        val links=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER
            addView(MaterialButton(this@MainActivity).apply{text="Impronta";isAllCaps=false;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{authenticate()}},LinearLayout.LayoutParams(0,dp(52),1f))
            addView(MaterialButton(this@MainActivity).apply{text="Accesso dimenticato";isAllCaps=false;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{showRecovery()}},LinearLayout.LayoutParams(0,dp(52),1f))}
        body.addView(links)
        setContentView(ScrollView(this).apply{setBackgroundColor(Color.rgb(48,43,151));addView(body)})
        if (tryBiometric && canUseBiometric() && vault.modern) authenticate()
    }

    private fun showMasterLogin(tryBiometric:Boolean=true){
        loginSafe=null
        window.statusBarColor=Color.rgb(9,7,27);val body=column(Gravity.CENTER_HORIZONTAL).apply{setPadding(dp(28),dp(26),dp(28),dp(24))}
        // Simbolo statico: evita di avviare contemporaneamente animazione e biometria.
        body.addView(TextView(this).apply{
            text="◉";textSize=104f;setTextColor(Color.rgb(238,196,57));gravity=Gravity.CENTER
            setPadding(0,dp(18),0,dp(8))
        },LinearLayout.LayoutParams(-1,dp(190)))
        body.addView(TextView(this).apply{text="Cassaforte";textSize=28f;setTextColor(Color.WHITE);setTypeface(typeface,1);gravity=Gravity.CENTER})
        body.addView(TextView(this).apply{text="La tua sicurezza, sempre con te";textSize=14f;setTextColor(Color.rgb(194,180,235));gravity=Gravity.CENTER;setPadding(0,dp(4),0,dp(28))})
        val password=purpleLoginField("Password principale");body.addView(password)
        val error=errorText();body.addView(error)
        body.addView(button("APRI CASSAFORTE"){
            val wait=security.lockRemainingMillis();if(wait>0)return@button showError(error,"Troppi tentativi. Riprova tra ${formatWait(wait)}.")
            val value=password.text.toString();if(security.verifyMaster(value)&&vault.unlockWithPassword(value)){security.recordMasterSuccess();items=vault.load();openSafe()}else{val delay=security.recordMasterFailure();showError(error,if(delay>0)"Password errata. Accesso sospeso per ${formatWait(delay)}." else "Password errata.")}
        })
        val links=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;addView(MaterialButton(this@MainActivity).apply{text="Impronta";isAllCaps=false;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{authenticate()}},LinearLayout.LayoutParams(0,dp(52),1f));addView(MaterialButton(this@MainActivity).apply{text="Codice di recupero";isAllCaps=false;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{showModernRecovery()}},LinearLayout.LayoutParams(0,dp(52),1f))};body.addView(links)
        setContentView(ScrollView(this).apply{setBackgroundColor(Color.rgb(15,11,39));addView(body)});if(tryBiometric&&canUseBiometric())authenticate()
    }

    private fun openSafe() {
        showVault()
    }

    private fun authenticate() {
        if (!canUseBiometric()) return showLogin(false)
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { val cipher=result.cryptoObject?.cipher;if(cipher!=null&&vault.unlockWithBiometric(cipher)){items=vault.load();openSafe()}else showMasterLogin(false) }
            })
        val cipher=vault.biometricCipher()?:return showMasterLogin(false)
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sblocca Password Safe").setSubtitle("Usa l’impronta digitale")
            .setNegativeButtonText("Usa password").build(),BiometricPrompt.CryptoObject(cipher))
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
            if (security.verifyAnswers(a1.second.text.toString(), a2.second.text.toString())) showMasterMigration()
            else showError(error, "Una o entrambe le risposte non sono corrette.")
        })
        body.addView(button("INDIETRO") { showLogin(false) })
        setPage(body)
    }

    private fun showMasterMigration(){
        val legacy=runCatching{vault.loadLegacy()}.getOrElse{return toast("Impossibile leggere la vecchia cassaforte")}
        val body=column();body.addView(title("Aggiornamento sicurezza","Crea la nuova password principale. Le password salvate verranno convertite automaticamente."))
        val p1=field("Nuova password principale");p1.second.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val p2=field("Ripeti la password");p2.second.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        body.addView(p1.first);body.addView(p2.first);val error=errorText();body.addView(error)
        body.addView(button("CONVERTI CASSAFORTE") migrate@{val p=p1.second.text.toString();if(!strongMasterPassword(p)){showError(error,"Usa almeno 12 caratteri, con maiuscola, minuscola e numero.");return@migrate};if(p!=p2.second.text.toString()){showError(error,"Le due password non coincidono.");return@migrate};val recovery=generateRecoveryCode();vault.migrateLegacy(legacy,p,recovery);security.configureMaster(p);items=vault.load();showRecoveryCode(recovery)})
        setPage(body)
    }

    private fun showRecoveryCode(code:String){
        val body=column();body.addView(title("Codice di recupero","Conservalo fuori dal telefono. Servirà se dimentichi la password principale."))
        body.addView(TextView(this).apply{text=code;textSize=22f;setTextColor(Color.rgb(23,32,51));setTypeface(typeface,1);gravity=Gravity.CENTER;setPadding(dp(12),dp(26),dp(12),dp(26));setTextIsSelectable(true)})
        body.addView(button("COPIA CODICE"){copySecure("Codice di recupero",code)});body.addView(button("HO SALVATO IL CODICE"){showVault()});setPage(body)
    }

    private fun showModernRecovery(){
        val body=column();body.addView(title("Recupera la cassaforte","Inserisci il codice di recupero e scegli una nuova password."))
        val code=field("Codice di recupero");val p1=field("Nuova password");val p2=field("Ripeti la password");p1.second.inputType=129;p2.second.inputType=129
        body.addView(code.first);body.addView(p1.first);body.addView(p2.first);val error=errorText();body.addView(error)
        body.addView(button("CAMBIA PASSWORD") recover@{val p=p1.second.text.toString();if(!strongMasterPassword(p)||p!=p2.second.text.toString()){showError(error,"Controlla la nuova password: almeno 12 caratteri e i due campi uguali.");return@recover};if(!vault.unlockWithRecovery(code.second.text.toString().trim().uppercase())){showError(error,"Codice di recupero non valido.");return@recover};vault.changePassword(p);security.configureMaster(p);items=vault.load();showVault()});body.addView(button("INDIETRO"){showMasterLogin(false)});setPage(body)
    }

    private fun showChangeMasterPassword(){
        val body=column();body.addView(title("Cambia password","Inserisci la password attuale e quella nuova."));val old=field("Password attuale");val p1=field("Nuova password");val p2=field("Ripeti nuova password");listOf(old,p1,p2).forEach{it.second.inputType=129;body.addView(it.first)};val error=errorText();body.addView(error)
        body.addView(button("SALVA") change@{val p=p1.second.text.toString();if(!security.verifyMaster(old.second.text.toString())||!vault.unlockWithPassword(old.second.text.toString())){showError(error,"Password attuale errata.");return@change};if(!strongMasterPassword(p)||p!=p2.second.text.toString()){showError(error,"La nuova password deve avere almeno 12 caratteri e i campi devono coincidere.");return@change};vault.changePassword(p);security.configureMaster(p);items=vault.load();showSettingsMenu()});setPage(body)
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
        window.statusBarColor=Color.rgb(9,7,27)
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(22,16,52));setPadding(dp(22),dp(22),dp(22),dp(110))}
        body.addView(LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;addView(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;addView(TextView(this@MainActivity).apply{text="La tua cassaforte";textSize=25f;setTextColor(Color.WHITE);setTypeface(typeface,1)});addView(TextView(this@MainActivity).apply{text="${items.size} elementi salvati";textSize=13f;setTextColor(Color.rgb(194,180,235));setPadding(0,dp(2),0,0)})},LinearLayout.LayoutParams(0,-2,1f));addView(TextView(this@MainActivity).apply{text="⚙";textSize=22f;gravity=Gravity.CENTER;setTextColor(Color.rgb(238,199,62));setOnClickListener{showSettingsMenu()}},LinearLayout.LayoutParams(dp(48),dp(48)))})
        val search=darkEditorField("Cerca...","").apply{layoutParams=LinearLayout.LayoutParams(-1,dp(54)).apply{setMargins(0,dp(24),0,dp(14))}};body.addView(search)
        val chips=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};listOf("ACCOUNT" to "Account","PIN" to "PIN","LOGIN" to "Login","EMAIL" to "Email").forEach{(type,label)->chips.addView(filterChip(label,type),LinearLayout.LayoutParams(0,dp(44),1f).apply{setMargins(dp(2),0,dp(2),0)})};body.addView(chips)
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(14),0,0)};body.addView(list)
        fun refresh(query:String){list.removeAllViews();val shown=items.filter{(vaultTypeFilter=="NONE"||it.type==vaultTypeFilter)&&(query.isBlank()||it.title.contains(query,true)||it.username.contains(query,true))}.sortedBy{it.title.lowercase()};if(shown.isEmpty())list.addView(infoText("Nessun elemento trovato."))else shown.forEach{list.addView(itemListRow(it))}}
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){refresh(s?.toString().orEmpty())};override fun afterTextChanged(s:Editable?){}});refresh("");setDarkScreen(body,true)
    }

    private fun filterChip(label:String,type:String)=MaterialButton(this).apply{text=label;textSize=10f;isAllCaps=false;minWidth=0;insetTop=0;insetBottom=0;setPadding(dp(2),0,dp(2),0);cornerRadius=dp(22);val selected=(type=="TUTTI"&&vaultTypeFilter=="NONE")||vaultTypeFilter==type;setTextColor(if(selected)Color.WHITE else Color.rgb(205,195,235));setBackgroundColor(if(selected)Color.rgb(105,87,238) else Color.rgb(41,32,88));setOnClickListener{vaultTypeFilter=if(type=="TUTTI")"NONE" else type;showCategoryMenu()}}

    private fun darkHeader(label:String, back:Boolean)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(20),dp(10),dp(16),dp(16));setBackgroundColor(Color.rgb(27,52,78))
        if(back)addView(TextView(this@MainActivity).apply{text="‹";textSize=38f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setOnClickListener{fadeTo{showCategoryMenu()}}},LinearLayout.LayoutParams(dp(48),dp(52)))
        addView(TextView(this@MainActivity).apply{text=label;textSize=23f;setTextColor(Color.WHITE);setTypeface(typeface,1);gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(52),1f))
        if(!back)addView(TextView(this@MainActivity).apply{text="⚡";textSize=20f;gravity=Gravity.CENTER;setOnClickListener{showGeneratedPassword()}},LinearLayout.LayoutParams(dp(48),dp(52)))
        if(back)addView(TextView(this@MainActivity).apply{text="⌕";textSize=29f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setOnClickListener{showVaultSearch()}},LinearLayout.LayoutParams(dp(52),dp(52)))
    }

    private fun categoryMenuRow(label:String,count:Int,type:String)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(28),0,dp(26),0)
        background=GradientDrawable().apply{setColor(Color.rgb(31,68,98));setStroke(dp(1),Color.rgb(66,96,120))}
        addView(TextView(this@MainActivity).apply{text=label;textSize=18f;setTextColor(Color.rgb(153,219,239));gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(64),1f))
        addView(TextView(this@MainActivity).apply{text=count.toString();textSize=12f;gravity=Gravity.CENTER;setTextColor(Color.rgb(205,225,233));background=GradientDrawable().apply{setColor(Color.rgb(73,100,120));shape=GradientDrawable.OVAL}},LinearLayout.LayoutParams(dp(34),dp(34)).apply{setMargins(0,0,dp(14),0)})
        addView(TextView(this@MainActivity).apply{text="›";textSize=31f;gravity=Gravity.CENTER;setTextColor(Color.rgb(153,219,239))},LinearLayout.LayoutParams(dp(28),dp(54)))
        setOnClickListener{vaultTypeFilter=type;fadeTo{renderVault("")}}
    }

    private fun menuActionRow(label:String,icon:String,action:()->Unit)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(28),0,dp(26),0)
        background=GradientDrawable().apply{setColor(Color.rgb(28,22,62));setStroke(dp(1),Color.rgb(67,56,132))}
        addView(TextView(this@MainActivity).apply{text=icon;textSize=20f;gravity=Gravity.CENTER;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(dp(40),dp(62)).apply{setMargins(0,0,dp(12),0)})
        addView(TextView(this@MainActivity).apply{text=label;textSize=17f;setTextColor(Color.rgb(205,195,235));gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(62),1f))
        addView(TextView(this@MainActivity).apply{text="›";textSize=31f;gravity=Gravity.CENTER;setTextColor(Color.rgb(238,199,62))},LinearLayout.LayoutParams(dp(28),dp(54)))
        setOnClickListener{action()}
    }

    private fun showVaultSearch() {
        val input=dialogField("Cerca","")
        AlertDialog.Builder(this).setTitle("Cerca nella categoria").setView(input).setNegativeButton("Annulla",null).setPositiveButton("Cerca"){_,_->renderVault(input.text.toString().trim())}.show()
    }

    private fun setDarkScreen(content:View, showFab:Boolean) {
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.rgb(9,7,27));addView(content)}
        val frame=FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(9,7,27));addView(scroll,FrameLayout.LayoutParams(-1,-1))
            if(showFab)addView(MaterialButton(this@MainActivity).apply{text="＋";textSize=27f;cornerRadius=dp(30);setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(105,87,238));elevation=12f;setOnClickListener{if(vaultTypeFilter=="NONE")showCreateTypeMenu()else showItemDialog(null,vaultTypeFilter)}},FrameLayout.LayoutParams(dp(60),dp(60),Gravity.BOTTOM or Gravity.END).apply{setMargins(0,0,dp(24),dp(24))})
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
        setDarkScreen(body, true)
    }

    private fun itemListRow(item:VaultItem)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(5),0,0,0);background=GradientDrawable().apply{setColor(Color.rgb(238,199,62));cornerRadius=dp(18).toFloat()};layoutParams=LinearLayout.LayoutParams(-1,dp(76)).apply{setMargins(0,dp(5),0,dp(5))}
        addView(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(14),0);background=GradientDrawable().apply{setColor(Color.rgb(38,30,83));cornerRadius=dp(16).toFloat()};addView(TextView(this@MainActivity).apply{text=item.title.trim().firstOrNull()?.uppercase()?:"?";textSize=20f;gravity=Gravity.CENTER;setTextColor(Color.rgb(238,199,62));setTypeface(typeface,1);background=GradientDrawable().apply{setColor(Color.rgb(49,40,112));cornerRadius=dp(12).toFloat()}},LinearLayout.LayoutParams(dp(50),dp(50)).apply{setMargins(0,0,dp(14),0)});addView(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;addView(TextView(this@MainActivity).apply{text=item.title;textSize=16f;maxLines=1;setTextColor(Color.WHITE);setTypeface(typeface,1)});if(item.type!="ACCOUNT") addView(TextView(this@MainActivity).apply{text=if(item.type=="PIN")"••••••" else item.username;textSize=12f;maxLines=1;setTextColor(Color.rgb(194,180,235));setPadding(0,dp(3),0,0)})},LinearLayout.LayoutParams(0,-1,1f))},LinearLayout.LayoutParams(-1,-1))
        setOnClickListener{vaultTypeFilter=item.type;showItemDialog(item)}
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
    private fun showSettingsMenu() {
        val body=column().apply {
            setPadding(0,0,0,dp(50));setBackgroundColor(Color.rgb(22,16,52));addView(pageHeader("Impostazioni"){showCategoryMenu()})
            addView(menuActionRow("Dashboard sicurezza","◉"){showSecurityDashboard()})
            addView(menuActionRow("Backup e ripristino","↥"){showBackupPage()})
            addView(menuActionRow("Cambia password","●"){showChangeMasterPassword()})
            addView(toggleMenuRow("Accesso con impronta","◎",security.biometricEnabled){security.biometricEnabled=it})
            addView(menuActionRow("Generatore password","⚡"){showGeneratedPassword()})
            addView(menuActionRow("Blocca cassaforte","▣"){vault.lock();items.clear();showLogin(false)})
        };setDarkScreen(body,false)
    }
    private fun showBackupPage(){val body=column().apply{setPadding(0,0,0,dp(50));setBackgroundColor(Color.rgb(22,16,52));addView(pageHeader("Backup e ripristino"){showSettingsMenu()});addView(infoText("Il file è cifrato e può essere salvato su Google Drive dal selettore Android."));addView(menuActionRow("Crea backup cifrato","↥"){askBackupPin()});addView(menuActionRow("Ripristina un backup","↧"){openBackupFile.launch(arrayOf("application/octet-stream","application/json","*/*"))})};setDarkScreen(body,false)}
    private fun showSecurityDashboard(){
        val protected=items.filter{it.password.isNotBlank()};val reusedValues=protected.groupingBy{it.password}.eachCount().filterValues{it>1}.keys
        val reused=protected.filter{it.password in reusedValues};val weak=protected.filter{!isStrongPassword(it.password)};val strong=protected.filter{isStrongPassword(it.password)&&it.password !in reusedValues}
        val body=column().apply{setPadding(0,0,0,dp(60));setBackgroundColor(Color.rgb(22,16,52));addView(pageHeader("Dashboard sicurezza"){showSettingsMenu()});addView(SecurityChartView(this@MainActivity).apply{setValues(strong.size,weak.size,reused.size)},LinearLayout.LayoutParams(-1,dp(190)));addView(securityLegend("Password sicure",strong.size,Color.rgb(121,166,26)));addView(securityLegend("Password deboli",weak.size,Color.rgb(205,38,38)));addView(securityLegend("Password riutilizzate",reused.size,Color.rgb(170,170,170)));addView(sectionLabel("APPROFONDIMENTI SULLA SICUREZZA"));addView(issueRow("Password deboli",weak.distinctBy{it.id},"!"));addView(issueRow("Password riutilizzate",reused.distinctBy{it.id},"↻"));addView(infoText("Il controllo avviene solo sul telefono: nessuna password viene inviata online."))};setDarkScreen(body,false)
    }
    private fun isStrongPassword(value:String)=value.length>=12&&value.any{it.isUpperCase()}&&value.any{it.isLowerCase()}&&value.any{it.isDigit()}&&value.any{!it.isLetterOrDigit()}
    private fun showSecurityIssues(title:String,problemItems:List<VaultItem>){val body=column().apply{setPadding(0,0,0,dp(40));setBackgroundColor(Color.rgb(22,16,52));addView(pageHeader(title){showSecurityDashboard()})};if(problemItems.isEmpty())body.addView(infoText("Nessun problema trovato."))else problemItems.forEach{body.addView(itemListRow(it))};setDarkScreen(body,false)}
    private fun issueRow(label:String,problemItems:List<VaultItem>,icon:String)=menuActionRow("$label   ${problemItems.size}",icon){showSecurityIssues(label,problemItems)}
    private fun securityLegend(label:String,count:Int,color:Int)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(34),dp(5),dp(34),dp(5));addView(View(this@MainActivity).apply{background=GradientDrawable().apply{setColor(color);shape=GradientDrawable.OVAL}},LinearLayout.LayoutParams(dp(14),dp(14)).apply{setMargins(0,0,dp(12),0)});addView(TextView(this@MainActivity).apply{text=label;textSize=15f;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(0,dp(36),1f));addView(TextView(this@MainActivity).apply{text=count.toString();textSize=15f;setTextColor(Color.WHITE);setTypeface(typeface,1)})}
    private fun sectionLabel(value:String)=TextView(this).apply{text=value;textSize=12f;setTextColor(Color.rgb(205,225,233));setPadding(dp(20),dp(26),dp(20),dp(12));setBackgroundColor(Color.rgb(27,52,78))}
    private fun infoText(value:String)=TextView(this).apply{text=value;textSize=13f;setTextColor(Color.rgb(180,205,216));setPadding(dp(28),dp(22),dp(28),dp(22))}
    private fun pageHeader(label:String,onBack:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(10),dp(16),dp(16));setBackgroundColor(Color.rgb(15,11,39));addView(TextView(this@MainActivity).apply{text="‹";textSize=38f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setOnClickListener{onBack()}},LinearLayout.LayoutParams(dp(50),dp(52)));addView(TextView(this@MainActivity).apply{text=label;textSize=22f;setTextColor(Color.WHITE);setTypeface(typeface,1);gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(52),1f))}
    private fun toggleMenuRow(label:String,icon:String,checked:Boolean,onChange:(Boolean)->Unit):View=menuActionRow("$label   ${if(checked) "Sì" else "No"}",icon){onChange(!checked);showSettingsMenu()}

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
        }) else box.addView(View(this@MainActivity), LinearLayout.LayoutParams(1,dp(10)))
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
        val typeLabel = when(itemType) { "PIN" -> "PIN"; "ACCOUNT" -> "Account"; "EMAIL" -> "Email"; else -> "Login" }
        val box=column().apply{setPadding(0,0,0,dp(70));setBackgroundColor(Color.rgb(22,16,52));addView(pageHeader("${if(existing==null) "Nuovo" else "Modifica"} $typeLabel"){showCategoryMenu()})}
        val fields=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(26),dp(24),dp(10))};box.addView(fields)
        val titleF=darkEditorField(when(itemType){"PIN"->"Nome banca";"ACCOUNT"->"Nome account";else->"Nome del sito"},existing?.title?:"")
        val userF=darkEditorField(if(itemType=="ACCOUNT"||itemType=="EMAIL")"Email" else "Nome utente / email",existing?.username?:"")
        val passF=darkEditorField(if(itemType=="PIN")"PIN" else "Password",existing?.password?:"")
        if(itemType=="PIN") passF.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        if(itemType!="EMAIL")addEditorField(fields,if(itemType=="PIN")"NOME BANCA" else "TITOLO",titleF);if(itemType!="PIN")addEditorField(fields,if(itemType=="ACCOUNT"||itemType=="EMAIL")"EMAIL" else "EMAIL / UTENTE",userF);addEditorField(fields,if(itemType=="PIN")"PIN" else "PASSWORD",passF)
        if(itemType=="PIN")fields.addView(darkActionButton("MOSTRA / NASCONDI PIN"){val visible=passF.inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD==0;passF.inputType=InputType.TYPE_CLASS_NUMBER or if(visible)InputType.TYPE_NUMBER_VARIATION_PASSWORD else InputType.TYPE_NUMBER_VARIATION_NORMAL;passF.setSelection(passF.text.length)})
        else {
            fields.addView(darkActionButton("GENERA PASSWORD"){showPasswordGenerator { passF.setText(it) }})
            if(existing!=null && (itemType=="ACCOUNT" || itemType=="EMAIL")) {
                fields.addView(LinearLayout(this).apply{
                    orientation=LinearLayout.HORIZONTAL
                    addView(darkActionButton("COPIA MAIL"){copySecure("Email",userF.text.toString())},LinearLayout.LayoutParams(0,dp(50),1f).apply{setMargins(0,0,dp(5),0)})
                    addView(darkActionButton("COPIA PASSWORD"){copySecure("Password",passF.text.toString())},LinearLayout.LayoutParams(0,dp(50),1f).apply{setMargins(dp(5),0,0,0)})
                })
            }
        }
        fields.addView(darkActionButton("SALVA") save@{
                if ((itemType!="EMAIL"&&titleF.text.toString().isBlank()) || passF.text.toString().isBlank()){toast(if(itemType=="PIN")"Inserisci banca e PIN" else "Completa i campi richiesti");return@save}
                if(itemType!="PIN"&&userF.text.toString().isBlank()){toast(if(itemType=="ACCOUNT"||itemType=="EMAIL")"Inserisci l’email" else "Inserisci utente o email");return@save}
                val savedTitle=if(itemType=="EMAIL")userF.text.toString()else titleF.text.toString()
                if (existing == null) items.add(VaultItem(title=savedTitle, username=userF.text.toString(), password=passF.text.toString(), category=typeLabel, type=itemType))
                else { existing.title=savedTitle; existing.username=userF.text.toString(); existing.password=passF.text.toString(); existing.category=typeLabel; existing.type=itemType }
                vault.save(items);vaultTypeFilter=itemType;showCategoryMenu()
        });fields.addView(darkActionButton(if(itemType=="PIN") "ESCI" else "ANNULLA"){showCategoryMenu()})
        if(existing!=null){var confirm=false;val delete=darkTextButton("Elimina elemento"){};delete.setOnClickListener{if(!confirm){confirm=true;delete.text="Tocca ancora per eliminare";delete.setTextColor(Color.rgb(255,105,105))}else{items.remove(existing);vault.save(items);showCategoryMenu()}};fields.addView(delete)}
        setDarkScreen(box,false)
    }

    private fun showGeneratedPassword() {
        showPasswordGenerator { value ->
            AlertDialog.Builder(this).setTitle("Password generata").setMessage(value).setNegativeButton("Chiudi",null)
                .setPositiveButton("Copia") { _,_-> copySecure("Password",value) }.show()
        }
    }

    private fun showPasswordGenerator(onGenerated:(String)->Unit) {
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)}
        val letters=CheckBox(this).apply{text="Lettere";isChecked=true}
        val numbers=CheckBox(this).apply{text="Numeri";isChecked=true}
        val symbols=CheckBox(this).apply{text="Caratteri speciali";isChecked=true}
        val lengthLabel=TextView(this).apply{text="Lunghezza: 16";textSize=16f;setTextColor(Color.rgb(23,32,51));setPadding(0,dp(12),0,dp(4))}
        val seek=SeekBar(this).apply{max=24;progress=8}
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s:SeekBar?,progress:Int,fromUser:Boolean){lengthLabel.text="Lunghezza: ${progress+8}"}
            override fun onStartTrackingTouch(s:SeekBar?){}
            override fun onStopTrackingTouch(s:SeekBar?){}
        })
        box.addView(letters);box.addView(numbers);box.addView(symbols);box.addView(lengthLabel);box.addView(seek)
        val dialog=AlertDialog.Builder(this).setTitle("Genera password").setView(box).setNegativeButton("Annulla",null)
            .setPositiveButton("GENERA",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            if(!letters.isChecked&&!numbers.isChecked&&!symbols.isChecked){toast("Seleziona almeno un tipo di carattere");return@setOnClickListener}
            val value=generatedPassword(seek.progress+8,letters.isChecked,numbers.isChecked,symbols.isChecked)
            dialog.dismiss();onGenerated(value)
        }}
        dialog.show()
    }

    private fun generatedPassword(length:Int=20,letters:Boolean=true,numbers:Boolean=true,symbols:Boolean=true): String {
        val pool=buildString{if(letters)append("ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz");if(numbers)append("23456789");if(symbols)append("!@#%&*+-_")}
        val random=java.security.SecureRandom()
        return (1..length).joinToString(""){pool[random.nextInt(pool.length)].toString()}
    }

    private fun showBackupMenu() {
        AlertDialog.Builder(this).setTitle("Backup cifrato").setItems(arrayOf("Salva backup su Google Drive", "Ripristina da Google Drive")) { _, which ->
            if(which==0) askBackupPin() else openBackupFile.launch(arrayOf("application/octet-stream","application/json","*/*"))
        }.show()
    }
    private fun askBackupPin() {
        val box=column().apply{setPadding(dp(18),dp(8),dp(18),dp(4))};val input=dialogField("Password dedicata al backup","").apply{inputType=129};val confirm=dialogField("Ripeti la password","").apply{inputType=129};box.addView(input);box.addView(confirm)
        AlertDialog.Builder(this).setTitle("Proteggi il backup").setMessage("Usa una password diversa da quella principale e conservala al sicuro.").setView(box)
            .setNegativeButton("Annulla",null).setPositiveButton("Continua") { _,_->
                val password=input.text.toString();if(password.length>=12&&password==confirm.text.toString()){pendingBackup=vault.createBackup(items,password);createBackupFile.launch("PasswordSafe-backup.psafe")}else toast("La password del backup deve avere almeno 12 caratteri e coincidere")
            }.show()
    }
    private fun askRestorePin(bytes: ByteArray) {
        val input=dialogField("Password del backup","").apply { inputType=129 }
        AlertDialog.Builder(this).setTitle("Ripristina backup").setMessage("Inserisci la password dedicata usata quando hai creato il backup.").setView(input)
            .setNegativeButton("Annulla",null).setPositiveButton("Ripristina") { _,_->
                runCatching { vault.restoreBackup(bytes,input.text.toString()) }.onSuccess { items=it; vault.save(items); vaultTypeFilter="NONE";showCategoryMenu();toast("Backup ripristinato") }.onFailure { toast("Password errata o backup non valido") }
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
    private fun darkEditorField(hint:String,value:String)=EditText(this).apply{this.hint=hint;setText(value);textSize=16f;setTextColor(Color.WHITE);setHintTextColor(Color.rgb(139,126,190));background=GradientDrawable().apply{setColor(Color.rgb(38,31,83));cornerRadius=dp(14).toFloat();setStroke(dp(1),Color.rgb(74,61,155))};layoutParams=LinearLayout.LayoutParams(-1,dp(58)).apply{setMargins(0,dp(4),0,dp(10))};setPadding(dp(16),0,dp(16),0)}
    private fun addEditorField(parent:LinearLayout,label:String,field:EditText){parent.addView(TextView(this).apply{text=label;textSize=11f;setTextColor(Color.rgb(194,180,235));setTypeface(typeface,1);setPadding(dp(3),dp(8),0,0)});parent.addView(field)}
    private fun darkActionButton(label:String,action:()->Unit)=MaterialButton(this).apply{text=label;textSize=13f;setTextColor(if(label=="SALVA")Color.WHITE else Color.rgb(238,199,62));setBackgroundColor(if(label=="SALVA")Color.rgb(105,87,238) else Color.rgb(51,43,126));cornerRadius=dp(12);setOnClickListener{action()};layoutParams=LinearLayout.LayoutParams(-1,dp(50)).apply{setMargins(0,dp(10),0,0)}}
    private fun darkTextButton(label:String,action:()->Unit)=MaterialButton(this).apply{text=label;textSize=13f;isAllCaps=false;setTextColor(Color.rgb(255,105,105));setBackgroundColor(Color.TRANSPARENT);setOnClickListener{action()};layoutParams=LinearLayout.LayoutParams(-1,dp(48)).apply{setMargins(0,dp(5),0,0)}}
    private fun purpleLoginField(hint:String)=EditText(this).apply{this.hint=hint;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;textSize=16f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setHintTextColor(Color.rgb(139,126,190));background=GradientDrawable().apply{setColor(Color.rgb(38,31,83));cornerRadius=dp(14).toFloat();setStroke(dp(1),Color.rgb(74,61,155))};layoutParams=LinearLayout.LayoutParams(-1,dp(62));setPadding(dp(16),0,dp(16),0)}
    private fun smallButton(text:String, action:()->Unit)=MaterialButton(this).apply { this.text=text; textSize=11f; setOnClickListener{action()}; setTextColor(Color.WHITE); setBackgroundColor(blue) }
    private fun compactDialogButton(text:String,action:()->Unit)=smallButton(text,action).apply{maxLines=1;isSingleLine=true;insetTop=0;insetBottom=0;setPadding(dp(8),0,dp(8),0)}
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun strongMasterPassword(value:String)=value.length>=12&&value.any{it.isUpperCase()}&&value.any{it.isLowerCase()}&&value.any{it.isDigit()}
    private fun generateRecoveryCode():String{val chars="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";val random=java.security.SecureRandom();return (1..6).joinToString("-"){(1..5).map{chars[random.nextInt(chars.length)]}.joinToString("")}}
    private fun formatWait(ms:Long):String=when{ms>=60_000->"${(ms+59_999)/60_000} minuti";else->"${(ms+999)/1000} secondi"}

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
        this.text = text; textSize = 15f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(105,87,238))
        cornerRadius = 22; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, 138).apply { setMargins(0, 10, 0, 10) }
    }
    private fun errorText() = TextView(this).apply { setTextColor(Color.rgb(190,30,45)); textSize = 14f; visibility = View.GONE; setPadding(4,8,4,8) }
    private fun showError(view: TextView, message: String) { view.text = message; view.visibility = View.VISIBLE }
}
