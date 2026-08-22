package it.paolo.passwordsafe

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.content.ClipData
import android.content.Intent
import android.content.ClipboardManager
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.view.View
import android.view.autofill.AutofillManager
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Space
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.security.SecureRandom
import android.util.Base64

class MainActivity : AppCompatActivity() {
    private lateinit var security: SecurityStore
    private lateinit var vault: VaultStore
    private var items = mutableListOf<VaultItem>()
    private var pendingBackup: ByteArray? = null
    private var vaultTypeFilter = "NONE"
    private val revealedPins = mutableSetOf<String>()
    private val blue = Palette.CYAN
    private var loginSafe: SafeView? = null
    private var pendingCardNumberField: EditText? = null
    private var pendingCardExpiryField: EditText? = null
    private var pendingCardHolderField: EditText? = null
    private var pendingCardPhotoUri: Uri? = null
    private var pendingCardPhotoPath: String? = null

    private data class InstalledAppInfo(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable?
    )

    private val scanCardPhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCardPhotoUri
        if (!success || uri == null) {
            cleanupCardScanFiles()
            pendingCardPhotoUri = null
            pendingCardPhotoPath = null
            toast("Scansione annullata")
        } else {
            recognizeCard(uri)
        }
    }

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
        val darkTheme = getSharedPreferences("passwordsafe_ui", MODE_PRIVATE).getBoolean("dark_theme", true)
        AppCompatDelegate.setDefaultNightMode(if(darkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        pendingCardPhotoPath = savedInstanceState?.getString("pending_card_photo_path")
        val restoredPhoto = pendingCardPhotoPath?.let { java.io.File(it) }
        if (restoredPhoto != null && restoredPhoto.exists()) {
            pendingCardPhotoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", restoredPhoto)
        } else {
            pendingCardPhotoPath = null
            pendingCardPhotoUri = null
            cleanupCardScanFiles()
        }
        WindowCompat.setDecorFitsSystemWindows(window, true)
        security = SecurityStore(this)
        vault = VaultStore(this)
        items = mutableListOf()
        showLaunchAnimation {
            if (security.configured || security.masterConfigured) showLogin() else showSetup()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingCardPhotoPath?.let { outState.putString("pending_card_photo_path", it) }
        super.onSaveInstanceState(outState)
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
        window.statusBarColor = Palette.INK
        val body = column(Gravity.CENTER_HORIZONTAL).apply { setPadding(dp(28),dp(18),dp(28),dp(24)) }
        body.addView(TextView(this).apply{text="Cassaforte";textSize=28f;setTextColor(Color.WHITE);setTypeface(typeface,1);gravity=Gravity.CENTER})
        body.addView(TextView(this).apply{text="La tua sicurezza, sempre con te";textSize=14f;setTextColor(Palette.TEXT_DIM);gravity=Gravity.CENTER;setPadding(0,dp(4),0,0)})
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
        setContentView(ScrollView(this).apply{setBackgroundColor(Palette.INK);addView(body)})
        if (tryBiometric && canUseBiometric() && vault.modern) authenticate()
    }

    private fun showMasterLogin(tryBiometric:Boolean=true){
        loginSafe=null
        window.statusBarColor=appBg();val body=column(Gravity.CENTER_HORIZONTAL).apply{setPadding(dp(28),dp(26),dp(28),dp(24))}
        // Simbolo statico: evita di avviare contemporaneamente animazione e biometria.
        body.addView(TextView(this).apply{
            text="◉";textSize=104f;setTextColor(Palette.CYAN);gravity=Gravity.CENTER
            setPadding(0,dp(18),0,dp(8))
        },LinearLayout.LayoutParams(-1,dp(190)))
        body.addView(TextView(this).apply{text="Cassaforte";textSize=28f;setTextColor(Color.WHITE);setTypeface(typeface,1);gravity=Gravity.CENTER})
        body.addView(TextView(this).apply{text="La tua sicurezza, sempre con te";textSize=14f;setTextColor(Palette.TEXT_DIM);gravity=Gravity.CENTER;setPadding(0,dp(4),0,dp(28))})
        val password=purpleLoginField("Password principale");body.addView(password)
        val error=errorText();body.addView(error)
        body.addView(button("APRI CASSAFORTE"){
            val wait=security.lockRemainingMillis();if(wait>0)return@button showError(error,"Troppi tentativi. Riprova tra ${formatWait(wait)}.")
            val value=password.text.toString();if(security.verifyMaster(value)&&vault.unlockWithPassword(value)){security.recordMasterSuccess();items=vault.load();openSafe()}else{val delay=security.recordMasterFailure();showError(error,if(delay>0)"Password errata. Accesso sospeso per ${formatWait(delay)}." else "Password errata.")}
        })
        val links=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;addView(MaterialButton(this@MainActivity).apply{text="Impronta";isAllCaps=false;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{authenticate()}},LinearLayout.LayoutParams(0,dp(52),1f));addView(MaterialButton(this@MainActivity).apply{text="Codice di recupero";isAllCaps=false;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{showModernRecovery()}},LinearLayout.LayoutParams(0,dp(52),1f))};body.addView(links)
        setContentView(ScrollView(this).apply{setBackgroundColor(Palette.INK);addView(body)});if(tryBiometric&&canUseBiometric())authenticate()
    }

    private fun openSafe() {
        showVault()
    }

    private fun showLaunchAnimation(onComplete: () -> Unit) {
        window.statusBarColor = Palette.INK
        val root = FrameLayout(this).apply {
            setBackgroundColor(Palette.INK)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val safeOuter = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Palette.SURFACE_2)
                cornerRadius = dp(28).toFloat()
                setStroke(dp(3), Palette.CYAN)
            }
        }
        val safeDoor = FrameLayout(this).apply {
            pivotX = 0f
            pivotY = (dp(88)).toFloat()
            background = GradientDrawable().apply {
                setColor(Palette.SURFACE_2)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(2), Palette.CYAN)
            }
        }
        val wheel = TextView(this).apply {
            text = "✺"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Palette.CYAN)
        }
        val beam = View(this).apply {
            alpha = 0f
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(170, 0, 212, 228), Color.TRANSPARENT)).apply {
                cornerRadius = dp(18).toFloat()
            }
        }
        val title = TextView(this).apply {
            text = "Password Safe"
            textSize = 28f
            setTypeface(typeface, 1)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0f
        }
        val subtitle = TextView(this).apply {
            text = "La tua cassaforte si apre"
            textSize = 14f
            setTextColor(Palette.TEXT_DIM)
            gravity = Gravity.CENTER
            alpha = 0f
        }

        safeDoor.addView(wheel, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))
        safeOuter.addView(beam, FrameLayout.LayoutParams(dp(118), dp(86), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = -dp(96) })
        safeOuter.addView(safeDoor, FrameLayout.LayoutParams(dp(176), dp(176), Gravity.CENTER).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        content.addView(safeOuter, LinearLayout.LayoutParams(dp(220), dp(220)))
        content.addView(Space(this), LinearLayout.LayoutParams(-1, dp(18)))
        content.addView(title)
        content.addView(subtitle)
        setContentView(root)

        val fadeTitle = ObjectAnimator.ofFloat(title, View.ALPHA, 0f, 1f).setDuration(220)
        val fadeSubtitle = ObjectAnimator.ofFloat(subtitle, View.ALPHA, 0f, 1f).setDuration(220)
        val spin = ObjectAnimator.ofFloat(wheel, View.ROTATION, 0f, 180f).setDuration(480)
        spin.interpolator = DecelerateInterpolator()
        val open = ObjectAnimator.ofFloat(safeDoor, View.ROTATION_Y, 0f, -72f).setDuration(640)
        open.interpolator = DecelerateInterpolator()
        val beamAlpha = ObjectAnimator.ofFloat(beam, View.ALPHA, 0f, 1f).setDuration(320)
        AnimatorSet().apply {
            playTogether(fadeTitle, fadeSubtitle)
            start()
        }
        Handler(mainLooper).postDelayed({
            spin.start()
            Handler(mainLooper).postDelayed({
                open.start()
                beamAlpha.start()
            }, 260)
        }, 220)
        Handler(mainLooper).postDelayed({ onComplete() }, 1500)
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

    // PasswordSafe 0.46: identità visiva navy + ciano elettrico.
    private fun isDarkTheme() = getSharedPreferences("passwordsafe_ui", MODE_PRIVATE).getBoolean("dark_theme", true)
    private fun appBg() = if (isDarkTheme()) Palette.INK else Color.rgb(244, 251, 252)
    private fun panelBg() = if (isDarkTheme()) Palette.INK else Color.rgb(244, 251, 252)
    private fun cardBg() = if (isDarkTheme()) Palette.SURFACE else Color.WHITE
    private fun chipBg() = if (isDarkTheme()) Palette.SURFACE_2 else Color.rgb(232, 249, 250)
    private fun primaryText() = if (isDarkTheme()) Palette.TEXT else Color.rgb(7, 24, 39)
    private fun secondaryText() = if (isDarkTheme()) Palette.TEXT_DIM else Color.rgb(75, 101, 112)

    private fun showCategoryMenu() {
        window.statusBarColor = appBg()
        window.navigationBarColor = appBg()

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(appBg())
            setPadding(dp(18), dp(16), dp(18), dp(112))
        }

        fun countFor(type: String): Int = when (type) {
            "APP" -> items.count { it.appPackage.isNotBlank() && it.type in setOf("ACCOUNT", "LOGIN", "EMAIL") }
            "SETTINGS" -> 0
            else -> items.count { it.type == type && it.appPackage.isBlank() }
        }

        fun countText(type: String): String {
            if (type == "SETTINGS") return "Configura"
            val count = countFor(type)
            return if (count == 1) "1 elemento" else "$count elementi"
        }

        fun statCell(value: String, label: String) = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 22f
                setTypeface(typeface, 1)
                setTextColor(Palette.CYAN)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 10.5f
                setTextColor(secondaryText())
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            })
        }

        fun homeButton(label: String, type: String, iconRes: Int): View = LinearLayout(this@MainActivity).apply {
            val selected = vaultTypeFilter == type
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(9), dp(9))
            contentDescription = label.replace("\n", " ")
            alpha = if (vaultTypeFilter == "NONE" || selected || type == "SETTINGS") 1f else .48f
            background = GradientDrawable().apply {
                setColor(if (selected && isDarkTheme()) Palette.SURFACE_2 else cardBg())
                cornerRadius = dp(17).toFloat()
                setStroke(dp(if (selected) 2 else 1), if (selected) Palette.CYAN else Palette.BORDER)
            }

            val iconWrap = FrameLayout(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isDarkTheme()) Color.argb(26, 0, 212, 228) else Color.argb(22, 0, 151, 167))
                    setStroke(dp(1), Palette.CYAN)
                }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(Palette.CYAN)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    contentDescription = label.replace("\n", " ")
                }, FrameLayout.LayoutParams(-1, -1))
            }
            addView(iconWrap, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, 0, dp(10), 0) })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = if (type == "SETTINGS") 13f else 15f
                    maxLines = 2
                    setTypeface(typeface, 1)
                    setTextColor(primaryText())
                })
                addView(TextView(this@MainActivity).apply {
                    text = countText(type)
                    textSize = 10.5f
                    setTextColor(if (selected) Palette.CYAN_SOFT else secondaryText())
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -1, 1f))

            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 27f
                gravity = Gravity.CENTER
                setTextColor(if (selected) Palette.CYAN else secondaryText())
            }, LinearLayout.LayoutParams(dp(22), -1))

            setOnClickListener {
                if (type == "SETTINGS") {
                    showSettingsMenu()
                } else {
                    vaultTypeFilter = type
                    showCategoryMenu()
                }
            }
        }

        body.addView(TextView(this).apply {
            text = "La tua cassaforte"
            textSize = 28f
            setTextColor(primaryText())
            setTypeface(typeface, 1)
        })
        body.addView(TextView(this).apply {
            text = "${items.size} elementi salvati"
            textSize = 15f
            setTextColor(Palette.CYAN)
            setPadding(0, dp(2), 0, 0)
        })

        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(10), dp(11), dp(10))
            background = GradientDrawable().apply {
                setColor(cardBg())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Palette.BORDER)
            }
        }

        val shieldWrap = FrameLayout(this).apply {
            fun ring(size: Int, alphaValue: Int): View = View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), Color.argb(alphaValue, 0, 212, 228))
                }
                layoutParams = FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER)
            }
            addView(ring(82, 62))
            addView(ring(66, 110))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_shield_lock)
                imageTintList = android.content.res.ColorStateList.valueOf(Palette.CYAN)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
        }
        summary.addView(shieldWrap, LinearLayout.LayoutParams(dp(92), dp(112)).apply { setMargins(0, 0, dp(7), 0) })

        summary.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "La tua cassaforte è protetta"
                textSize = 15.2f
                setTypeface(typeface, 1)
                setTextColor(primaryText())
                maxLines = 2
            })
            addView(TextView(this@MainActivity).apply {
                text = "Dati cifrati sul dispositivo"
                textSize = 11.2f
                setTextColor(Palette.CYAN_SOFT)
                setPadding(0, dp(3), 0, dp(7))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(statCell(items.size.toString(), "Elementi"), LinearLayout.LayoutParams(0, dp(52), 1f))
                addView(statCell("8", "Categorie"), LinearLayout.LayoutParams(0, dp(52), 1f))
                addView(statCell("✓", "Protetto"), LinearLayout.LayoutParams(0, dp(52), 1f))
            })
        }, LinearLayout.LayoutParams(0, -1, 1f))
        body.addView(summary, LinearLayout.LayoutParams(-1, dp(138)).apply { setMargins(0, dp(16), 0, dp(8)) })

        val rows = listOf(
            listOf(Triple("Account", "ACCOUNT", R.drawable.ic_account), Triple("PIN", "PIN", R.drawable.ic_pin)),
            listOf(Triple("Login", "LOGIN", R.drawable.ic_login), Triple("Email", "EMAIL", R.drawable.ic_email)),
            listOf(Triple("Carte", "CARD", R.drawable.ic_card), Triple("Passkey", "PASSKEY", R.drawable.ic_passkey)),
            listOf(Triple("App", "APP", R.drawable.ic_app), Triple("Altro /\nImpostazioni", "SETTINGS", R.drawable.ic_settings))
        )
        rows.forEach { row ->
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                row.forEach { (label, type, icon) ->
                    addView(homeButton(label, type, icon), LinearLayout.LayoutParams(0, dp(80), 1f).apply {
                        setMargins(dp(4), dp(5), dp(4), dp(5))
                    })
                }
            })
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        body.addView(list)

        fun refresh() {
            list.removeAllViews()
            if (vaultTypeFilter == "NONE") {
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = GradientDrawable().apply {
                        setColor(cardBg())
                        cornerRadius = dp(17).toFloat()
                        setStroke(dp(1), Palette.BORDER)
                    }
                    addView(TextView(this@MainActivity).apply {
                        text = "✦"
                        textSize = 21f
                        gravity = Gravity.CENTER
                        setTextColor(Palette.CYAN)
                    }, LinearLayout.LayoutParams(dp(38), dp(42)).apply { setMargins(0, 0, dp(8), 0) })
                    addView(TextView(this@MainActivity).apply {
                        text = "Seleziona una categoria per visualizzare gli elementi."
                        textSize = 12.5f
                        setTextColor(secondaryText())
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(3), dp(4), 0) })
                return
            }

            val label = when (vaultTypeFilter) {
                "ACCOUNT" -> "ACCOUNT"
                "PIN" -> "PIN"
                "LOGIN" -> "LOGIN"
                "EMAIL" -> "EMAIL"
                "CARD" -> "CARTE"
                "PASSKEY" -> "PASSKEY"
                else -> "APP"
            }
            list.addView(TextView(this).apply {
                text = label
                textSize = 11f
                letterSpacing = 0.12f
                setTypeface(typeface, 1)
                setTextColor(Palette.CYAN)
                setPadding(dp(6), dp(4), dp(6), dp(8))
            })

            val shown = if (vaultTypeFilter == "APP") {
                items.filter { it.appPackage.isNotBlank() && it.type in setOf("ACCOUNT", "LOGIN", "EMAIL") }
            } else {
                items.filter { it.type == vaultTypeFilter && it.appPackage.isBlank() }
            }.sortedBy { it.title.lowercase() }
            if (shown.isEmpty()) list.addView(infoText("Nessun elemento trovato.")) else shown.forEach { list.addView(itemListRow(it)) }
        }

        refresh()
        setDarkScreen(body, true)
    }

    private fun filterChip(label:String,type:String)=MaterialButton(this).apply{text=label;textSize=10f;isAllCaps=false;minWidth=0;insetTop=0;insetBottom=0;setPadding(dp(2),0,dp(2),0);cornerRadius=dp(22);val selected=(type=="TUTTI"&&vaultTypeFilter=="NONE")||vaultTypeFilter==type;setTextColor(if(selected)Color.WHITE else primaryText());setBackgroundColor(if(selected)Palette.CYAN else chipBg());setOnClickListener{vaultTypeFilter=if(type=="TUTTI")"NONE" else type;showCategoryMenu()}}

    private fun categoryIconButton(label:String,type:String,iconRes:Int)=LinearLayout(this).apply {
        val selected = vaultTypeFilter == type
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        contentDescription = label
        background = GradientDrawable().apply {
            setColor(if (selected) Palette.SURFACE else chipBg())
            cornerRadius = dp(18).toFloat()
            setStroke(dp(if (selected) 2 else 1), if (selected) Palette.CYAN else if (isDarkTheme()) Palette.BORDER else Color.rgb(222, 218, 235))
        }
        addView(ImageView(this@MainActivity).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(
                if (selected) Palette.CYAN else Palette.CYAN
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = label
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(0, 0, 0, dp(7)) })
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 12f
            setTypeface(typeface, if (selected) 1 else 0)
            setTextColor(if (selected) Palette.CYAN else secondaryText())
            gravity = Gravity.CENTER
        })
        setOnClickListener {
            vaultTypeFilter = type
            showCategoryMenu()
        }
    }

    private fun darkHeader(label:String, back:Boolean)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(20),dp(10),dp(16),dp(16));setBackgroundColor(if(isDarkTheme()) Palette.SURFACE else Color.rgb(238,235,248))
        if(back)addView(TextView(this@MainActivity).apply{text="‹";textSize=38f;gravity=Gravity.CENTER;setTextColor(primaryText());setOnClickListener{fadeTo{showCategoryMenu()}}},LinearLayout.LayoutParams(dp(48),dp(52)))
        addView(TextView(this@MainActivity).apply{text=label;textSize=23f;setTextColor(primaryText());setTypeface(typeface,1);gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(52),1f))
        if(!back)addView(TextView(this@MainActivity).apply{text="⚡";textSize=20f;gravity=Gravity.CENTER;setOnClickListener{showGeneratedPassword()}},LinearLayout.LayoutParams(dp(48),dp(52)))
        
    }

    private fun menuActionRow(label:String,icon:String,action:()->Unit)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(28),0,dp(26),0)
        background=GradientDrawable().apply{setColor(cardBg());setStroke(dp(1),if(isDarkTheme()) Palette.BORDER else Color.rgb(220,216,232))}
        addView(TextView(this@MainActivity).apply{text=icon;textSize=20f;gravity=Gravity.CENTER;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(dp(40),dp(62)).apply{setMargins(0,0,dp(12),0)})
        addView(TextView(this@MainActivity).apply{text=label;textSize=17f;setTextColor(primaryText());gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(62),1f))
        addView(TextView(this@MainActivity).apply{text="›";textSize=31f;gravity=Gravity.CENTER;setTextColor(Palette.CYAN)},LinearLayout.LayoutParams(dp(28),dp(54)))
        setOnClickListener{action()}
    }

    private fun menuActionRow(label:String,iconRes:Int,action:()->Unit)=LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(28),0,dp(26),0)
        background=GradientDrawable().apply{setColor(cardBg());setStroke(dp(1),if(isDarkTheme()) Palette.BORDER else Color.rgb(220,216,232))}
        addView(ImageView(this@MainActivity).apply{
            setImageResource(iconRes)
            imageTintList=android.content.res.ColorStateList.valueOf(if(isDarkTheme()) Palette.TEXT_DIM else Palette.CYAN_DARK)
            scaleType=ImageView.ScaleType.CENTER_INSIDE
            contentDescription=label
        },LinearLayout.LayoutParams(dp(28),dp(62)).apply{setMargins(dp(6),0,dp(18),0)})
        addView(TextView(this@MainActivity).apply{text=label;textSize=17f;setTextColor(primaryText());gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(62),1f))
        addView(TextView(this@MainActivity).apply{text="›";textSize=31f;gravity=Gravity.CENTER;setTextColor(Palette.CYAN)},LinearLayout.LayoutParams(dp(28),dp(54)))
        setOnClickListener{action()}
    }

    private fun setDarkScreen(content:View, showFab:Boolean) {
        val scroll=ScrollView(this).apply{setBackgroundColor(appBg());addView(content)}
        val frame=FrameLayout(this).apply {
            setBackgroundColor(appBg());addView(scroll,FrameLayout.LayoutParams(-1,-1))
            if(showFab)addView(MaterialButton(this@MainActivity).apply{text="＋";textSize=27f;cornerRadius=dp(30);setTextColor(Color.WHITE);setBackgroundColor(Palette.CYAN);elevation=12f;setOnClickListener{when(vaultTypeFilter){"NONE"->showCreateTypeMenu();"APP"->showInstalledApps("NEW",true);else->showItemDialog(null,vaultTypeFilter)}}},FrameLayout.LayoutParams(dp(60),dp(60),Gravity.BOTTOM or Gravity.END).apply{setMargins(0,0,dp(24),dp(24))})
        }
        ViewCompat.setOnApplyWindowInsetsListener(frame){view,insets->val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());view.setPadding(0,bars.top,0,bars.bottom);insets}
        setContentView(frame)
    }

    private fun renderVault(filter: String) {
        window.statusBarColor = appBg()
        val body = column().apply { setPadding(0,0,0,dp(110));setBackgroundColor(panelBg());addView(darkHeader(when(vaultTypeFilter){"ACCOUNT"->"Account";"PIN"->"PIN";"LOGIN"->"Login";"CARD"->"Carte";"PASSKEY"->"Passkey";else->"Email"},true),LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,0,0,0)}) }
        val filtered=items.filter{
            it.type==vaultTypeFilter &&
            it.appPackage.isBlank() &&
            (filter.isBlank()||it.title.contains(filter,true)||it.username.contains(filter,true))
        }
        if(vaultTypeFilter!="NONE" && filtered.isEmpty())body.addView(TextView(this).apply{text="Nessun elemento in questa categoria";gravity=Gravity.CENTER;textSize=17f;setTextColor(secondaryText());setPadding(20,60,20,60)})
        filtered.sortedBy{it.title.lowercase()}.forEach{body.addView(itemListRow(it))}
        setDarkScreen(body, true)
    }

    private fun itemListRow(item:VaultItem)=LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(cardBg())
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), if (isDarkTheme()) Palette.BORDER else Color.rgb(226, 221, 239))
        }
        elevation = dp(1).toFloat()
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, dp(96)).apply { setMargins(0, dp(6), 0, dp(8)) }

        val logoWrap = FrameLayout(this@MainActivity).apply {
            background = GradientDrawable().apply {
                setColor(if (isDarkTheme()) Palette.SURFACE_2 else Color.rgb(244, 242, 252))
                cornerRadius = dp(18).toFloat()
            }
        }
        val fallback = TextView(this@MainActivity).apply {
            text = item.title.trim().firstOrNull()?.uppercase()?.toString() ?: "?"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Palette.CYAN)
            setTypeface(typeface, 1)
        }
        val logo = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        logoWrap.addView(fallback, FrameLayout.LayoutParams(-1, -1))
        logoWrap.addView(logo, FrameLayout.LayoutParams(-1, -1))
        addView(logoWrap, LinearLayout.LayoutParams(dp(62), dp(62)).apply { setMargins(0, 0, dp(16), 0) })
        loadServiceLogo(item, logo, fallback)

        addView(TextView(this@MainActivity).apply {
            text = item.title
            textSize = 18f
            maxLines = 1
            setTextColor(primaryText())
            setTypeface(typeface, 1)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -1, 1f))

        addView(TextView(this@MainActivity).apply {
            text = "›"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(if (isDarkTheme()) Palette.BORDER else Color.rgb(150, 142, 184))
        }, LinearLayout.LayoutParams(dp(24), -1))

        setOnClickListener {
            if (vaultTypeFilter != "APP") vaultTypeFilter = item.type
            showItemDialog(item)
        }
    }

    private fun loadServiceLogo(item: VaultItem, image: ImageView, fallback: TextView) {
        if (item.type == "PIN" || item.type == "CARD") return
        if(item.appPackage.isNotBlank()) {
            val appIcon = runCatching { packageManager.getApplicationIcon(item.appPackage) }.getOrNull()
            if(appIcon != null) {
                image.setImageDrawable(appIcon)
                image.visibility=View.VISIBLE
                fallback.visibility=View.GONE
                return
            }
        }
        val domain = serviceDomain(item) ?: return
        val cacheFile = cacheDir.resolve("brand_${domain.hashCode().toUInt()}.png")
        if (cacheFile.exists()) {
            runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()?.let {
                image.setImageBitmap(it); image.visibility=View.VISIBLE; fallback.visibility=View.GONE
                return
            }
        }
        Thread {
            val bitmap = runCatching {
                val connection = (java.net.URL("https://$domain/favicon.ico").openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout=4000;readTimeout=4000;instanceFollowRedirects=true
                    setRequestProperty("User-Agent","PasswordSafe-Android")
                }
                try {
                    if(connection.responseCode !in 200..299) return@runCatching null
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally { connection.disconnect() }
            }.getOrNull()
            if(bitmap!=null){
                runCatching { cacheFile.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                runOnUiThread {
                    image.setImageBitmap(bitmap);image.visibility=View.VISIBLE;fallback.visibility=View.GONE
                }
            }
        }.start()
    }

    private fun serviceDomain(item: VaultItem): String? {
        val raw=if (item.urlLabel.equals("SITO / DOMINIO", true)) item.url.trim() else ""
        if(raw.isNotBlank()){
            val candidate=runCatching {
                val normalized=if(raw.startsWith("http://")||raw.startsWith("https://")) raw else "https://$raw"
                Uri.parse(normalized).host?.removePrefix("www.")
            }.getOrNull()
            if(!candidate.isNullOrBlank()) return candidate
        }
        val name=item.title.lowercase().trim()
        val known=listOf(
            "amazon" to "amazon.it",
            "netflix" to "netflix.com",
            "ghost vpn" to "cyberghostvpn.com",
            "cyberghost" to "cyberghostvpn.com",
            "google" to "google.com",
            "gmail" to "gmail.com",
            "facebook" to "facebook.com",
            "instagram" to "instagram.com",
            "whatsapp" to "whatsapp.com",
            "telegram" to "telegram.org",
            "microsoft" to "microsoft.com",
            "outlook" to "outlook.com",
            "apple" to "apple.com",
            "icloud" to "icloud.com",
            "spotify" to "spotify.com",
            "paypal" to "paypal.com",
            "ebay" to "ebay.it",
            "booking" to "booking.com",
            "airbnb" to "airbnb.com",
            "github" to "github.com",
            "linkedin" to "linkedin.com",
            "x / twitter" to "x.com",
            "twitter" to "x.com",
            "tiktok" to "tiktok.com",
            "disney" to "disneyplus.com",
            "prime video" to "primevideo.com",
            "dazn" to "dazn.com",
            "dropbox" to "dropbox.com",
            "adobe" to "adobe.com"
        )
        return known.firstOrNull { name.contains(it.first) }?.second
    }

    private fun categoryTile(label:String,count:Int,type:String,color:Int)=MaterialCardView(this).apply{
        radius=dp(14).toFloat();cardElevation=if(vaultTypeFilter==type)6f else 2f;setCardBackgroundColor(color);strokeWidth=if(vaultTypeFilter==type)3 else 0;strokeColor=blue;alpha=if(vaultTypeFilter=="NONE"||vaultTypeFilter==type)1f else .28f
        addView(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(2),dp(12),dp(2),dp(10));addView(TextView(this@MainActivity).apply{text=label;textSize=14f;maxLines=1;setTextColor(Color.rgb(28,36,70));setTypeface(typeface,1);gravity=Gravity.CENTER});addView(TextView(this@MainActivity).apply{text=count.toString();textSize=10f;setTextColor(Color.GRAY);gravity=Gravity.CENTER;setPadding(0,dp(3),0,0)})});setOnClickListener{vaultTypeFilter=if(vaultTypeFilter==type)"NONE" else type;renderVault("")}
    }

    private fun navButton(label:String,action:()->Unit)=MaterialButton(this).apply{text=label;textSize=10f;isAllCaps=false;maxLines=2;minWidth=0;setPadding(0,0,0,0);setTextColor(Color.rgb(55,62,88));setBackgroundColor(Color.TRANSPARENT);setOnClickListener{action()}}
    private fun showSettingsMenu() {
        val body=column().apply {
            setPadding(0,0,0,dp(50));setBackgroundColor(panelBg());addView(pageHeader("Impostazioni"){showCategoryMenu()})
            addView(menuActionRow("Dashboard sicurezza","◉"){showSecurityDashboard()})
            addView(menuActionRow("Backup e ripristino","↥"){showBackupPage()})
            addView(menuActionRow("Sincronizzazione Windows","↻"){showWindowsSync()})
            addView(menuActionRow("Cambia password","●"){showChangeMasterPassword()})
            addView(toggleMenuRow("Accesso con impronta","◎",security.biometricEnabled){security.biometricEnabled=it})
            addView(menuActionRow("Generatore password","⚡"){showGeneratedPassword()})
            addView(menuActionRow("Compilazione automatica   ${if(isAutofillEnabled()) "✓" else ""}","↯"){openAutofillSettings()})
            addView(menuActionRow("App installate",R.drawable.ic_app){showInstalledApps()})
            addView(infoText("Seleziona un'app del telefono e collega account e password. PasswordSafe non legge le password delle altre app: le inserisci tu e poi l'Autofill può proporle nell'app corretta."))
            addView(menuActionRow("Voci personalizzate Login",R.drawable.ic_login){showLoginCustomFieldsSettings()})
            addView(infoText("Aggiungi qui i nomi che vuoi usare per l’ultima casella dei Login. Le nuove voci compariranno insieme a Sito / dominio, PIN e Nome."))
            addView(menuActionRow("Passkey Android",R.drawable.ic_passkey){showPasskeyGuide()})
            addView(sectionLabel("TEMA"))
            val darkTheme=getSharedPreferences("passwordsafe_ui", MODE_PRIVATE).getBoolean("dark_theme", true)
            addView(menuActionRow("Tema chiaro   ${if(!darkTheme) "✓" else ""}","☀"){setAppTheme(false)})
            addView(menuActionRow("Tema scuro   ${if(darkTheme) "✓" else ""}","☾"){setAppTheme(true)})
            addView(menuActionRow("Blocca cassaforte","▣"){vault.lock();items.clear();showLogin(false)})
        };setDarkScreen(body,false)
    }
    private fun showBackupPage(){
        val body=column().apply{
            setPadding(0,0,0,dp(50));setBackgroundColor(panelBg())
            addView(pageHeader("Backup e ripristino"){showSettingsMenu()})
            addView(infoText("Il backup resta manuale e cifrato. Quando si apre il selettore Android scegli Google Drive come destinazione."))
            addView(menuActionRow("Backup su Google Drive","↥"){askBackupPin()})
            addView(menuActionRow("Ripristina da Google Drive","↧"){openBackupFile.launch(arrayOf("application/octet-stream","application/json","*/*"))})
            addView(infoText("PasswordSafe non carica direttamente dati sui server Google: il file passa dal selettore documenti Android e viene salvato su Drive solo se scegli Drive."))
        }
        setDarkScreen(body,false)
    }
    private fun showWindowsSync(){
        val prefs=getSharedPreferences("passwordsafe_sync",MODE_PRIVATE)
        val credentials=SyncCredentialStore(this)
        var channel=prefs.getString("channel","")?:""
        if(channel.isBlank()){channel=ByteArray(32).also{SecureRandom().nextBytes(it)}.let{Base64.encodeToString(it,Base64.NO_WRAP or Base64.URL_SAFE)};prefs.edit().putString("channel",channel).apply()}
        val code=channel
        val body=column().apply{
            setPadding(dp(18),0,dp(18),dp(50));setBackgroundColor(panelBg());addView(pageHeader("Sincronizzazione Windows"){showSettingsMenu()})
            addView(infoText("Windows può ricevere le password solo dopo la tua autorizzazione. I dati sono cifrati per una singola richiesta e Firebase non può leggerli."))
            if(credentials.token()!=null){
                addView(infoText("Telefono collegato: ${credentials.email()}\n\nCodice dispositivo:\n$code"))
                addView(button("CONTROLLA ORA"){runStoredSync(code)})
                addView(button("SCOLLEGA ACCOUNT"){credentials.clear();showWindowsSync()})
                post{runStoredSync(code)}
            }else{
                val email=dialogField("Email sincronizzazione",prefs.getString("email","")?:"").apply{inputType=InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS}
                val password=dialogField("Password account sincronizzazione","").apply{inputType=129}
                addView(email);addView(password);addView(infoText("Questi dati servono solo per il primo collegamento. Codice dispositivo da inserire una sola volta su Windows:\n$code"))
                addView(button("CREA ACCOUNT SINCRONIZZAZIONE"){runSyncLogin(email.text.toString(),password.text.toString(),code,true)})
                addView(button("COLLEGA ACCOUNT ESISTENTE"){runSyncLogin(email.text.toString(),password.text.toString(),code,false)})
            }
        };setDarkScreen(body,false)
    }
    private fun runSyncLogin(email:String,password:String,channel:String,create:Boolean){
        if(!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()||password.length<8){toast("Inserisci email valida e password di almeno 8 caratteri");return}
        getSharedPreferences("passwordsafe_sync",MODE_PRIVATE).edit().putString("email",email.trim()).apply()
        Thread{runCatching{val client=FirebaseSyncClient();val session=client.login(email.trim(),password,create);SyncCredentialStore(this).save(email.trim(),session.refreshToken);Triple(client,session,client.pending(session,channel))}.onSuccess{(client,session,req)->runOnUiThread{
            if(create&&req==null){toast("Account sincronizzazione creato e collegato");showWindowsSync()}
            else if(req==null){toast("Account collegato. Nessuna richiesta Windows in attesa");showWindowsSync()}
            else AlertDialog.Builder(this).setTitle("Autorizzare ${req.device}?").setMessage("Il PC chiede di sincronizzare la cassaforte. La richiesta scade tra pochi minuti.").setNegativeButton("Rifiuta"){_,_->Thread{runCatching{client.deny(session,channel,req)}}.start()}.setPositiveButton("Autorizza"){_,_->approveSyncBiometric(client,session,channel,req)}.show()
        }}.onFailure{e->runOnUiThread{toast("Sincronizzazione: ${e.message}")}}}.start()
    }
    private fun runStoredSync(channel:String){
        val store=SyncCredentialStore(this);val token=store.token()?:return
        Thread{runCatching{val client=FirebaseSyncClient();val session=client.refresh(token);store.save(store.email(),session.refreshToken);Triple(client,session,client.pending(session,channel))}.onSuccess{(client,session,req)->runOnUiThread{
            if(req==null)toast("Nessuna richiesta Windows in attesa")
            else AlertDialog.Builder(this).setTitle("Autorizzare ${req.device}?").setMessage("Conferma con l’impronta per aprire PasswordSafe su Windows.").setNegativeButton("Rifiuta"){_,_->Thread{runCatching{client.deny(session,channel,req)}}.start()}.setPositiveButton("Continua"){_,_->approveSyncBiometric(client,session,channel,req)}.show()
        }}.onFailure{e->runOnUiThread{store.clear();toast("Collegamento scaduto: inserisci nuovamente email e password");showWindowsSync()}}}.start()
    }
    private fun approveSyncBiometric(client:FirebaseSyncClient,session:SyncSession,channel:String,req:SyncRequest){
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){Thread{runCatching{client.approve(session,channel,req,vault.exportClearForApprovedSync(items))}.onSuccess{runOnUiThread{toast("PC autorizzato e dati inviati")}}.onFailure{e->runOnUiThread{toast("Invio non riuscito: ${e.message}")}}}.start()}})
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Autorizza PasswordSafe Windows").setSubtitle("Conferma l’invio cifrato della cassaforte").setNegativeButtonText("Annulla").build())
    }
    private fun showSecurityDashboard(){
        val protected=items.filter{it.type!="PIN" && it.type!="CARD" && it.type!="PASSKEY" && it.password.isNotBlank()};val reusedValues=protected.groupingBy{it.password}.eachCount().filterValues{it>1}.keys
        val reused=protected.filter{it.password in reusedValues};val weak=protected.filter{!isStrongPassword(it.password)};val strong=protected.filter{isStrongPassword(it.password)&&it.password !in reusedValues}
        val body=column().apply{setPadding(0,0,0,dp(60));setBackgroundColor(panelBg());addView(pageHeader("Dashboard sicurezza"){showSettingsMenu()});addView(SecurityChartView(this@MainActivity).apply{setValues(strong.size,weak.size,reused.size)},LinearLayout.LayoutParams(-1,dp(190)));addView(securityLegend("Password sicure",strong.size,Color.rgb(121,166,26)));addView(securityLegend("Password deboli",weak.size,Color.rgb(205,38,38)));addView(securityLegend("Password riutilizzate",reused.size,Color.rgb(170,170,170)));addView(sectionLabel("APPROFONDIMENTI SULLA SICUREZZA"));addView(issueRow("Password deboli",weak.distinctBy{it.id},"!"));addView(issueRow("Password riutilizzate",reused.distinctBy{it.id},"↻"));addView(infoText("Il controllo avviene solo sul telefono: nessuna password viene inviata online."))};setDarkScreen(body,false)
    }
    private fun isStrongPassword(value:String)=value.length>=12&&value.any{it.isUpperCase()}&&value.any{it.isLowerCase()}&&value.any{it.isDigit()}&&value.any{!it.isLetterOrDigit()}
    private fun showSecurityIssues(title:String,problemItems:List<VaultItem>){val body=column().apply{setPadding(0,0,0,dp(40));setBackgroundColor(panelBg());addView(pageHeader(title){showSecurityDashboard()})};if(problemItems.isEmpty())body.addView(infoText("Nessun problema trovato."))else problemItems.forEach{body.addView(itemListRow(it))};setDarkScreen(body,false)}
    private fun issueRow(label:String,problemItems:List<VaultItem>,icon:String)=menuActionRow("$label   ${problemItems.size}",icon){showSecurityIssues(label,problemItems)}
    private fun securityLegend(label:String,count:Int,color:Int)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(34),dp(5),dp(34),dp(5));addView(View(this@MainActivity).apply{background=GradientDrawable().apply{setColor(color);shape=GradientDrawable.OVAL}},LinearLayout.LayoutParams(dp(14),dp(14)).apply{setMargins(0,0,dp(12),0)});addView(TextView(this@MainActivity).apply{text=label;textSize=15f;setTextColor(primaryText())},LinearLayout.LayoutParams(0,dp(36),1f));addView(TextView(this@MainActivity).apply{text=count.toString();textSize=15f;setTextColor(primaryText());setTypeface(typeface,1)})}
    private fun sectionLabel(value:String)=TextView(this).apply{text=value;textSize=12f;setTextColor(Palette.TEXT_DIM);setPadding(dp(20),dp(26),dp(20),dp(12));setBackgroundColor(if(isDarkTheme()) Palette.SURFACE else Color.rgb(238,235,248))}
    private fun infoText(value:String)=TextView(this).apply{text=value;textSize=13f;setTextColor(secondaryText());setPadding(dp(28),dp(22),dp(28),dp(22))}
    private fun pageHeader(label:String,onBack:()->Unit)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(10),dp(16),dp(16));setBackgroundColor(appBg());addView(TextView(this@MainActivity).apply{text="‹";textSize=38f;gravity=Gravity.CENTER;setTextColor(primaryText());setOnClickListener{onBack()}},LinearLayout.LayoutParams(dp(50),dp(52)));addView(TextView(this@MainActivity).apply{text=label;textSize=22f;setTextColor(primaryText());setTypeface(typeface,1);gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(52),1f))}
    private fun toggleMenuRow(label:String,icon:String,checked:Boolean,onChange:(Boolean)->Unit):View=menuActionRow("$label   ${if(checked) "Sì" else "No"}",icon){onChange(!checked);showSettingsMenu()}

    private fun setAppTheme(dark:Boolean){
        getSharedPreferences("passwordsafe_ui", MODE_PRIVATE).edit().putBoolean("dark_theme",dark).apply()
        AppCompatDelegate.setDefaultNightMode(if(dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        recreate()
    }

    private fun installedApps(): List<InstalledAppInfo> {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launchIntent, 0)
            .mapNotNull { resolveInfo ->
                val activity = resolveInfo.activityInfo ?: return@mapNotNull null
                val pkg = activity.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                val label = runCatching { resolveInfo.loadLabel(packageManager).toString().trim() }
                    .getOrDefault(pkg)
                    .ifBlank { pkg }
                val icon = runCatching { resolveInfo.loadIcon(packageManager) }.getOrNull()
                InstalledAppInfo(label, pkg, icon)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun showInstalledApps(filter: String = "ALL", fromMain: Boolean = false) {
        val allApps = installedApps()
        val savedPackages = items.mapNotNull { item -> item.appPackage.takeIf { it.isNotBlank() } }.toSet()
        val shown = when (filter) {
            "SAVED" -> allApps.filter { it.packageName in savedPackages }
            "NEW" -> allApps.filter { it.packageName !in savedPackages }
            else -> allApps
        }

        val body = column().apply {
            setPadding(0,0,0,dp(70))
            setBackgroundColor(panelBg())
            addView(pageHeader(if(fromMain) "App" else "App installate"){ if(fromMain) showCategoryMenu() else showSettingsMenu() })
            addView(infoText(
                if(fromMain && filter == "NEW")
                    "Scegli un'app a cui associare una nuova credenziale. Dopo il salvataggio comparirà sotto il pulsante App nella schermata principale."
                else if(fromMain)
                    "Qui trovi le app che hai selezionato e a cui hai associato account o password."
                else
                    "Tocca un'app per creare una credenziale collegata. Sono mostrate le app avviabili del telefono; Android non permette a PasswordSafe di leggere le password già presenti nelle altre app."
            ))
        }

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16),0,dp(16),dp(10))
        }
        fun addFilter(label:String, value:String) {
            filters.addView(MaterialButton(this).apply {
                text=label
                textSize=11f
                isAllCaps=false
                cornerRadius=dp(18)
                setTextColor(if(filter==value) Color.WHITE else primaryText())
                setBackgroundColor(if(filter==value) Palette.CYAN else chipBg())
                setOnClickListener { showInstalledApps(value, fromMain) }
            }, LinearLayout.LayoutParams(0,dp(42),1f).apply { setMargins(dp(3),0,dp(3),0) })
        }
        if(!fromMain) {
            addFilter("Tutte","ALL")
            addFilter("Salvate","SAVED")
            addFilter("Non salvate","NEW")
            body.addView(filters)
        }

        if (shown.isEmpty()) {
            body.addView(infoText(
                if(fromMain) "Non hai ancora app salvate. Vai in Impostazioni → App installate per sceglierne una."
                else "Nessuna app in questa sezione."
            ))
        } else {
            shown.forEach { app ->
                val existing = items.filter { it.appPackage == app.packageName && it.type in setOf("ACCOUNT","LOGIN","EMAIL") }
                body.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16),dp(10),dp(14),dp(10))
                    background = GradientDrawable().apply {
                        setColor(cardBg())
                        cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), if(isDarkTheme()) Palette.BORDER else Color.rgb(226,221,239))
                    }
                    layoutParams = LinearLayout.LayoutParams(-1,dp(74)).apply { setMargins(dp(16),dp(5),dp(16),dp(5)) }
                    addView(ImageView(this@MainActivity).apply {
                        setImageDrawable(app.icon)
                        scaleType=ImageView.ScaleType.CENTER_INSIDE
                    }, LinearLayout.LayoutParams(dp(46),dp(46)).apply { setMargins(0,0,dp(14),0) })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation=LinearLayout.VERTICAL
                        gravity=Gravity.CENTER_VERTICAL
                        addView(TextView(this@MainActivity).apply {
                            text=app.label
                            textSize=16f
                            setTypeface(typeface,1)
                            setTextColor(primaryText())
                            maxLines=1
                        })
                        if(existing.isNotEmpty()) addView(TextView(this@MainActivity).apply {
                            text=if(existing.size==1) "Credenziale salvata" else "${existing.size} credenziali salvate"
                            textSize=11f
                            setTextColor(Palette.CYAN)
                            setPadding(0,dp(2),0,0)
                        })
                    }, LinearLayout.LayoutParams(0,-1,1f))
                    addView(TextView(this@MainActivity).apply {
                        text=if(existing.isEmpty()) "+" else "✓"
                        textSize=24f
                        gravity=Gravity.CENTER
                        setTextColor(if(existing.isEmpty()) Palette.CYAN_SOFT else Palette.CYAN)
                    }, LinearLayout.LayoutParams(dp(36),-1))
                    setOnClickListener {
                        if(existing.isEmpty()) {
                            showItemDialog(null,"LOGIN",app.label,app.packageName)
                        } else {
                            showItemDialog(existing.first())
                        }
                    }
                })
            }
        }
        setDarkScreen(body,false)
    }

    private fun showInstalledAppCredentialMenu(appLabel:String, appPackage:String, credentials:List<VaultItem>) {
        if(credentials.size == 1) {
            showInstalledAppCredentialActions(appLabel, appPackage, credentials.first())
            return
        }
        val labels = credentials.indices.map { "Credenziale ${it + 1}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(appLabel)
            .setItems(labels) { _, which ->
                credentials.getOrNull(which)?.let { showInstalledAppCredentialActions(appLabel, appPackage, it) }
            }
            .setNeutralButton("Aggiungi account") { _, _ ->
                showItemDialog(null, "LOGIN", appLabel, appPackage)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showInstalledAppCredentialActions(appLabel:String, appPackage:String, item:VaultItem) {
        val actions = arrayOf("Copia account / email", "Copia password", "Apri credenziale", "Aggiungi un altro account")
        AlertDialog.Builder(this)
            .setTitle(appLabel)
            .setItems(actions) { _, which ->
                when(which) {
                    0 -> copySecure("Account", item.username)
                    1 -> copySecure("Password", item.password)
                    2 -> showItemDialog(item)
                    3 -> showItemDialog(null, "LOGIN", appLabel, appPackage)
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showCreateTypeMenu() {
        val labels = arrayOf("Account", "PIN", "Login", "Email", "Carta", "Passkey", "Da app installata")
        AlertDialog.Builder(this).setTitle("Cosa vuoi creare?").setItems(labels) { _, which ->
            if(which==6) {
                showInstalledApps("NEW")
                return@setItems
            }
            val type = when (which) {
                0 -> "ACCOUNT"
                1 -> "PIN"
                2 -> "LOGIN"
                3 -> "EMAIL"
                4 -> "CARD"
                else -> "PASSKEY"
            }
            showItemDialog(null, type)
        }.setNegativeButton("Annulla",null).show()
    }

    private fun showItemDialog(existing: VaultItem?, requestedType: String? = null, prefillTitle: String? = null, prefillPackage: String? = null) {
        val itemType = existing?.type ?: requestedType ?: "LOGIN"
        val typeLabel = when (itemType) {
            "PIN" -> "PIN"
            "ACCOUNT" -> "Account"
            "EMAIL" -> "Email"
            "CARD" -> "Carta"
            "PASSKEY" -> "Passkey"
            else -> "Login"
        }

        val box = column().apply {
            setPadding(0, 0, 0, dp(70))
            setBackgroundColor(panelBg())
            addView(pageHeader("${if (existing == null) "Nuovo" else "Modifica"} $typeLabel") { showCategoryMenu() })
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(6))
        }
        box.addView(fields)

        if (itemType == "CARD") {
            val titleF = darkEditorField("Nome carta / banca", existing?.title ?: "")
            val numberF = darkEditorField("Numero carta", existing?.username ?: "").apply {
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            val expiryF = darkEditorField("MM/AA", existing?.url ?: "")
            val holderF = darkEditorField("Intestatario", existing?.notes ?: "")
            val cvvF = darkEditorField("CVV", existing?.password ?: "").apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }

            addEditorField(fields, "NOME CARTA / BANCA", titleF)
            addEditorField(fields, "NUMERO CARTA", numberF)
            addEditorField(fields, "SCADENZA", expiryF)
            addEditorField(fields, "INTESTATARIO", holderF)
            addEditorField(fields, "CVV", cvvF)

            fields.addView(darkActionButton("SCANSIONA CARTA") {
                pendingCardNumberField = numberF
                pendingCardExpiryField = expiryF
                pendingCardHolderField = holderF
                startCardScan()
            })
            fields.addView(infoText("La foto viene analizzata sul telefono e non viene salvata. Il CVV va inserito manualmente."))
            fields.addView(darkActionButton("MOSTRA / NASCONDI CVV") {
                val hidden = cvvF.inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD != 0
                cvvF.inputType = InputType.TYPE_CLASS_NUMBER or if (hidden) InputType.TYPE_NUMBER_VARIATION_NORMAL else InputType.TYPE_NUMBER_VARIATION_PASSWORD
                cvvF.setSelection(cvvF.text.length)
            })
            if (existing != null) {
                fields.addView(darkActionButton("COPIA NUMERO CARTA") { copySecure("Numero carta", numberF.text.toString()) })
            }
            fields.addView(darkActionButton("SALVA") saveCard@{
                val number = numberF.text.toString().filter(Char::isDigit)
                if (titleF.text.toString().isBlank() || number.length !in 13..19) {
                    toast("Inserisci nome carta e un numero valido")
                    return@saveCard
                }
                val saved = existing ?: VaultItem(
                    title = titleF.text.toString(),
                    username = number,
                    password = cvvF.text.toString(),
                    url = expiryF.text.toString(),
                    notes = holderF.text.toString(),
                    category = typeLabel,
                    type = itemType
                )
                saved.title = titleF.text.toString()
                saved.username = number
                saved.password = cvvF.text.toString()
                saved.url = expiryF.text.toString()
                saved.notes = holderF.text.toString()
                saved.category = typeLabel
                saved.type = itemType
                if (existing == null) items.add(saved)
                vault.save(items)
                vaultTypeFilter = itemType
                showCategoryMenu()
            })
            fields.addView(darkActionButton("ANNULLA") { showCategoryMenu() })
            if (existing != null) addDeleteButton(fields, existing)
            setDarkScreen(box, false)
            return
        }

        if (itemType == "PASSKEY") {
            val titleF = darkEditorField("Nome servizio", existing?.title ?: "")
            val userF = darkEditorField("Utente / email", existing?.username ?: "")
            val siteF = darkEditorField("Sito / dominio", existing?.url ?: "")
            val providerF = darkEditorField("Gestore passkey", existing?.password ?: "Google Password Manager")
            val noteF = darkEditorField("Note", existing?.notes ?: "")
            addEditorField(fields, "SERVIZIO", titleF)
            addEditorField(fields, "UTENTE / EMAIL", userF)
            addEditorField(fields, "SITO / DOMINIO", siteF)
            addEditorField(fields, "GESTORE PASSKEY", providerF)
            addEditorField(fields, "NOTE", noteF)
            fields.addView(infoText("La chiave privata della passkey non viene copiata in PasswordSafe. La crea il sito e la conserva il gestore credenziali Android scelto da te."))
            fields.addView(darkActionButton("APRI GESTIONE PASSKEY ANDROID") { openCredentialProviderSettings() })
            fields.addView(darkActionButton("APRI SITO PER CREARE / USARE PASSKEY") {
                val raw = siteF.text.toString().trim()
                if(raw.isBlank()) toast("Inserisci prima il sito o dominio") else openPasskeySite(raw)
            })
            fields.addView(darkActionButton("COME SI USA") { showPasskeyGuide() })
            fields.addView(darkActionButton("SALVA") save@{
                if (titleF.text.toString().isBlank() || userF.text.toString().isBlank()) {
                    toast("Inserisci servizio e utente")
                    return@save
                }
                val saved = existing ?: VaultItem(
                    title = titleF.text.toString(),
                    username = userF.text.toString(),
                    password = providerF.text.toString(),
                    url = siteF.text.toString(),
                    notes = noteF.text.toString(),
                    category = typeLabel,
                    type = itemType
                )
                saved.title = titleF.text.toString()
                saved.username = userF.text.toString()
                saved.password = providerF.text.toString()
                saved.url = siteF.text.toString()
                saved.notes = noteF.text.toString()
                saved.category = typeLabel
                saved.type = itemType
                if (existing == null) items.add(saved)
                vault.save(items)
                vaultTypeFilter = itemType
                showCategoryMenu()
            })
            fields.addView(darkActionButton("ANNULLA") { showCategoryMenu() })
            if (existing != null) addDeleteButton(fields, existing)
            setDarkScreen(box, false)
            return
        }

        val titleF = darkEditorField(
            when (itemType) {
                "PIN" -> "Nome banca"
                "ACCOUNT" -> "Nome account"
                else -> "Nome del sito"
            },
            existing?.title ?: prefillTitle.orEmpty()
        )
        val userF = darkEditorField(
            if (itemType == "ACCOUNT" || itemType == "EMAIL") "Email" else "Nome utente / email",
            existing?.username ?: ""
        )
        val passF = darkEditorField(if (itemType == "PIN") "PIN" else "Password", existing?.password ?: "")
        var extraFieldLabel = existing?.urlLabel?.takeIf { it.isNotBlank() } ?: "SITO / DOMINIO"
        val urlF = darkEditorField(extraFieldHint(extraFieldLabel), existing?.url ?: "")

        if (itemType == "PIN") {
            passF.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        if (itemType != "EMAIL") addEditorField(fields, if (itemType == "PIN") "NOME BANCA" else "TITOLO", titleF)
        if (itemType != "PIN") addEditorField(fields, if (itemType == "ACCOUNT" || itemType == "EMAIL") "EMAIL" else "EMAIL / UTENTE", userF)
        addEditorField(fields, if (itemType == "PIN") "PIN" else "PASSWORD", passF)
        if (itemType == "ACCOUNT" || itemType == "LOGIN" || itemType == "EMAIL") {
            if (itemType == "LOGIN") {
                val labelView = TextView(this).apply {
                    text = "$extraFieldLabel  ▾"
                    textSize = 9f
                    setTextColor(if (isDarkTheme()) Palette.TEXT_DIM else Palette.CYAN_DARK)
                    setTypeface(typeface, 1)
                    setPadding(dp(3), dp(4), 0, dp(2))
                    setOnClickListener {
                        showLoginExtraFieldLabelPicker(extraFieldLabel) { selected ->
                            extraFieldLabel = selected
                            text = "$extraFieldLabel  ▾"
                            urlF.hint = extraFieldHint(extraFieldLabel)
                        }
                    }
                }
                fields.addView(labelView)
                fields.addView(urlF)
            } else {
                addEditorField(fields, "SITO / DOMINIO PER AUTOFILL", urlF)
            }
            val linkedPackage = existing?.appPackage?.takeIf { it.isNotBlank() } ?: prefillPackage.orEmpty()
            if(linkedPackage.isNotBlank()) {
                fields.addView(infoText("App collegata: ${prefillTitle ?: existing?.title ?: linkedPackage}. L'Autofill userà il collegamento all'app anche senza compilare il dominio."))
            }
        }

        fun addCompactAction(label:String, action:()->Unit) {
            val btn = darkActionButton(label, action).apply { textSize = 10.5f }
            fields.addView(btn, LinearLayout.LayoutParams(-1, dp(38)).apply {
                setMargins(dp(14), dp(5), dp(14), 0)
            })
        }

        if (itemType == "PIN") {
            addCompactAction("MOSTRA / NASCONDI PIN") {
                val hidden = passF.inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD != 0
                passF.inputType = InputType.TYPE_CLASS_NUMBER or if (hidden) InputType.TYPE_NUMBER_VARIATION_NORMAL else InputType.TYPE_NUMBER_VARIATION_PASSWORD
                passF.setSelection(passF.text.length)
            }
        } else {
            addCompactAction("GENERA PASSWORD") { showPasswordGenerator { passF.setText(it) } }
            addCompactAction("VERIFICA PASSWORD") {
                val value = passF.text.toString()
                if (value.isBlank()) {
                    toast("Inserisci prima una password")
                } else {
                    toast("Controllo in corso…")
                    checkCompromisedPassword(value) { count ->
                        when {
                            count == null -> toast("Impossibile effettuare il controllo. Verifica la connessione Internet.")
                            count > 0 -> AlertDialog.Builder(this)
                                .setTitle("Password compromessa")
                                .setMessage("Questa password compare in archivi di violazioni note $count volte. È consigliato cambiarla.")
                                .setPositiveButton("OK", null)
                                .show()
                            else -> AlertDialog.Builder(this)
                                .setTitle("Nessuna compromissione trovata")
                                .setMessage("La password non è stata trovata nell’archivio Pwned Passwords.")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
            if (existing != null && (itemType == "ACCOUNT" || itemType == "EMAIL" || itemType == "LOGIN")) {
                fields.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(14), dp(6), dp(14), 0)
                    val accountLabel = if (itemType == "EMAIL") "COPIA EMAIL" else "COPIA UTENTE"
                    addView(darkActionButton(accountLabel) {
                        copySecure(if (itemType == "EMAIL") "Email" else "Utente", userF.text.toString())
                    }.apply { textSize = 9.5f }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(0, 0, dp(4), 0) })
                    addView(darkActionButton("COPIA PASSWORD") {
                        copySecure("Password", passF.text.toString())
                    }.apply { textSize = 9.5f }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(4), 0, 0, 0) })
                })
            }
        }

        addCompactAction("SALVA") save@{
            if ((itemType != "EMAIL" && titleF.text.toString().isBlank()) || passF.text.toString().isBlank()) {
                toast(if (itemType == "PIN") "Inserisci banca e PIN" else "Completa i campi richiesti")
                return@save
            }
            if (itemType != "PIN" && userF.text.toString().isBlank()) {
                toast(if (itemType == "ACCOUNT" || itemType == "EMAIL") "Inserisci l’email" else "Inserisci utente o email")
                return@save
            }
            val savedTitle = if (itemType == "EMAIL") userF.text.toString() else titleF.text.toString()
            if (existing == null) {
                items.add(VaultItem(
                    title = savedTitle,
                    username = userF.text.toString(),
                    password = passF.text.toString(),
                    url = if (itemType == "PIN") "" else urlF.text.toString(),
                    category = typeLabel,
                    type = itemType,
                    appPackage = if(itemType=="PIN") "" else prefillPackage.orEmpty(),
                    urlLabel = if (itemType == "LOGIN") extraFieldLabel else "SITO / DOMINIO"
                ))
            } else {
                existing.title = savedTitle
                existing.username = userF.text.toString()
                existing.password = passF.text.toString()
                if (itemType != "PIN") existing.url = urlF.text.toString()
                if (itemType == "LOGIN") existing.urlLabel = extraFieldLabel
                existing.category = typeLabel
                existing.type = itemType
                if(!prefillPackage.isNullOrBlank()) existing.appPackage = prefillPackage
            }
            vault.save(items)
            val linkedToApp = existing?.appPackage?.isNotBlank() == true || !prefillPackage.isNullOrBlank()
            vaultTypeFilter = if (linkedToApp) "APP" else itemType
            showCategoryMenu()
        }
        addCompactAction(if (itemType == "PIN") "ESCI" else "ANNULLA") { showCategoryMenu() }
        if (existing != null) addDeleteButton(fields, existing)
        setDarkScreen(box, false)
    }

    private fun addDeleteButton(fields: LinearLayout, existing: VaultItem) {
        var confirm = false
        val delete = darkTextButton("Elimina elemento") {}
        delete.setOnClickListener {
            if (!confirm) {
                confirm = true
                delete.text = "Tocca ancora per eliminare"
                delete.setTextColor(Color.rgb(255, 105, 105))
            } else {
                items.remove(existing)
                vault.save(items)
                showCategoryMenu()
            }
        }
        fields.addView(delete)
    }

    private fun startCardScan() {
        cleanupCardScanFiles()
        val photo = cacheDir.resolve("passwordsafe_card_scan_${System.currentTimeMillis()}.jpg")
        pendingCardPhotoPath = photo.absolutePath
        pendingCardPhotoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
        scanCardPhoto.launch(pendingCardPhotoUri)
    }

    private fun cleanupCardScanFiles() {
        runCatching {
            cacheDir.listFiles()?.filter { it.name.startsWith("passwordsafe_card_scan_") || it.name == "passwordsafe_card_scan.jpg" }
                ?.forEach { file -> runCatching { file.delete() } }
        }
    }

    private fun recognizeCard(uri: Uri) {
        val image = runCatching { InputImage.fromFilePath(this, uri) }.getOrElse {
            cleanupCardScanFiles()
            pendingCardPhotoUri = null
            pendingCardPhotoPath = null
            toast("Impossibile leggere la foto")
            return
        }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(image)
            .addOnSuccessListener { result ->
                val raw = result.text
                val number = Regex("(?:\\d[ -]?){13,19}")
                    .findAll(raw)
                    .map { it.value.filter(Char::isDigit) }
                    .filter { it.length in 13..19 }
                    .toList()
                    .let { values -> values.firstOrNull(::passesLuhn) ?: values.maxByOrNull { it.length } }

                val expiry = extractCardExpiry(raw)

                val ignored = listOf(
                    "VISA", "MASTERCARD", "DEBIT", "CREDIT", "VALID", "THRU",
                    "CARD", "BANCA", "BANK", "ELECTRON", "MAESTRO", "SCADENZA"
                )
                val holder = raw.lines()
                    .map { it.trim() }
                    .filter { line ->
                        line.length in 5..35 && line.none(Char::isDigit) &&
                            line.count { it == ' ' } in 1..4
                    }
                    .filter { line -> ignored.none { word -> line.uppercase().contains(word) } }
                    .maxByOrNull { line -> line.count(Char::isLetter) }

                number?.let { pendingCardNumberField?.setText(it.chunked(4).joinToString(" ")) }
                expiry?.let { pendingCardExpiryField?.setText(it) }
                holder?.let { pendingCardHolderField?.setText(it) }

                if (number == null && expiry == null && holder == null) {
                    toast("Non sono riuscito a leggere i dati. Prova con più luce e carta ben dritta.")
                } else {
                    toast("Dati letti. Controllali prima di salvare.")
                }
                cleanupCardScanFiles()
                pendingCardPhotoUri = null
                pendingCardPhotoPath = null
            }
            .addOnFailureListener {
                cleanupCardScanFiles()
                pendingCardPhotoUri = null
                pendingCardPhotoPath = null
                toast("Impossibile analizzare la carta")
            }
    }

    private fun extractCardExpiry(raw: String): String? {
        data class DateCandidate(val month: Int, val year: Int, val formatted: String, val score: Int)

        val normalized = raw.uppercase()
        val dateRegex = Regex("(0[1-9]|1[0-2])\\s*[/.-]\\s*(?:20)?(\\d{2})")
        val positiveWords = listOf(
            "VALID THRU", "VALID THROUGH", "VALID TO", "VALID UNTIL", "GOOD THRU",
            "EXPIRES", "EXPIRY", "EXP DATE", "EXP", "SCADENZA", "VALIDA FINO", "VALIDO FINO"
        )
        val negativeWords = listOf(
            "VALID FROM", "VALID SINCE", "VALIDA DAL", "VALIDO DAL", "ISSUED", "ISSUE DATE", "START DATE"
        )

        val candidates = dateRegex.findAll(normalized).map { match ->
            val month = match.groupValues[1].toInt()
            val yy = match.groupValues[2].toInt()
            val year = 2000 + yy
            val from = (match.range.first - 42).coerceAtLeast(0)
            val to = (match.range.last + 42).coerceAtMost(normalized.lastIndex)
            val nearby = normalized.substring(from, to + 1)

            var score = 0
            positiveWords.forEach { word ->
                val idx = nearby.indexOf(word)
                if (idx >= 0) score += if (idx <= match.range.first - from) 8 else 5
            }
            negativeWords.forEach { word ->
                val idx = nearby.indexOf(word)
                if (idx >= 0) score -= if (idx <= match.range.first - from) 10 else 6
            }
            DateCandidate(month, year, "%02d/%02d".format(month, yy), score)
        }.toList()

        if (candidates.isEmpty()) return null
        val positivelyIdentified = candidates.filter { it.score > 0 }
        return if (positivelyIdentified.isNotEmpty()) {
            positivelyIdentified.maxWithOrNull(compareBy<DateCandidate> { it.score }.thenBy { it.year }.thenBy { it.month })?.formatted
        } else {
            // Se la carta mostra sia "valida dal" sia la scadenza ma le etichette non vengono lette bene,
            // la scadenza è normalmente la data cronologicamente più lontana.
            candidates.maxWithOrNull(compareBy<DateCandidate> { it.year }.thenBy { it.month })?.formatted
        }
    }

    private fun passesLuhn(number: String): Boolean {
        var sum = 0
        var doubleDigit = false
        for (i in number.indices.reversed()) {
            var digit = number[i] - '0'
            if (doubleDigit) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            doubleDigit = !doubleDigit
        }
        return number.length in 13..19 && sum % 10 == 0
    }

    private fun openPasskeySite(raw: String) {
        runCatching {
            val target = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }.onFailure { toast("Impossibile aprire il sito") }
    }

    private fun openCredentialProviderSettings() {
        if (Build.VERSION.SDK_INT < 34) {
            toast("La gestione unificata delle passkey richiede Android 14 o successivo")
            runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            return
        }
        runCatching { startActivity(Intent("android.settings.CREDENTIAL_PROVIDER")) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } }
    }

    private fun showPasskeyGuide() {
        val body=column().apply{
            setPadding(0,0,0,dp(50));setBackgroundColor(panelBg())
            addView(pageHeader("Passkey Android"){showSettingsMenu()})
            addView(infoText("1. Apri la gestione Passkey Android e scegli il gestore credenziali che vuoi usare, per esempio Google Password Manager."))
            addView(infoText("2. Nel sito o nell'app del servizio entra in Sicurezza e scegli Crea passkey / Aggiungi passkey."))
            addView(infoText("3. Android mostrerà il gestore credenziali. Conferma con impronta, volto o PIN del telefono."))
            addView(infoText("4. In PasswordSafe puoi salvare il nome del servizio, l'utente e dove è conservata la passkey. La chiave privata resta nel gestore Android e non viene mostrata nell'app."))
            addView(menuActionRow("Apri gestione passkey Android",R.drawable.ic_passkey){openCredentialProviderSettings()})
        }
        setDarkScreen(body,false)
    }

    private fun isAutofillEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
    }

    private fun openAutofillSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toast("La compilazione automatica richiede Android 8 o successivo")
            return
        }
        val direct = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(direct) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun showGeneratedPassword() {
        showPasswordGenerator { value ->
            AlertDialog.Builder(this).setTitle("Password generata").setMessage(value).setNegativeButton("Chiudi",null)
                .setPositiveButton("Copia") { _,_-> copySecure("Password",value) }.show()
        }
    }

    private fun showPasswordGenerator(onGenerated:(String)->Unit) {
        val panelColor = Palette.SURFACE
        val rowColor = Palette.SURFACE_2
        val borderColor = Palette.CYAN
        val cyan = Palette.CYAN
        val yellow = Palette.CYAN_SOFT
        val textMain = Color.WHITE
        val textSoft = Palette.TEXT_DIM

        fun rounded(color:Int, radius:Int, strokeColor:Int?=null, strokeWidth:Int=1)=GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if(strokeColor != null) setStroke(dp(strokeWidth), strokeColor)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(17), dp(17), dp(17))
            background = rounded(panelColor, 22, borderColor, 2)
        }

        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "ϟ"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(cyan)
            }, LinearLayout.LayoutParams(dp(34), dp(38)).apply { setMargins(0,0,dp(6),0) })
            addView(TextView(this@MainActivity).apply {
                text = "GENERA PASSWORD"
                textSize = 18f
                maxLines = 1
                setTextColor(textMain)
                setTypeface(typeface, 1)
                letterSpacing = 0.02f
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
        })

        fun optionRow(label:String): Pair<LinearLayout,CheckBox> {
            val check = CheckBox(this).apply {
                isChecked = true
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(cyan, Palette.TEXT_DIM)
                )
                scaleX = 0.98f
                scaleY = 0.92f
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(12), 0)
                background = rounded(rowColor, 13, borderColor, 1)
                addView(check, LinearLayout.LayoutParams(dp(44), dp(54)))
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 16f
                    setTextColor(textSoft)
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, dp(54), 1f))
                setOnClickListener { check.isChecked = !check.isChecked }
            }
            return row to check
        }

        val lettersPair = optionRow("Lettere")
        val numbersPair = optionRow("Numeri")
        val symbolsPair = optionRow("Caratteri speciali")
        listOf(lettersPair.first, numbersPair.first, symbolsPair.first).forEachIndexed { index, row ->
            content.addView(row, LinearLayout.LayoutParams(-1, dp(54)).apply {
                setMargins(0, if(index==0) dp(13) else dp(8), 0, 0)
            })
        }
        val letters = lettersPair.second
        val numbers = numbersPair.second
        val symbols = symbolsPair.second

        val lengthLabel = TextView(this).apply {
            text = "LUNGHEZZA: 16"
            textSize = 13f
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setTypeface(typeface, 1)
            setTextColor(cyan)
            background = rounded(Color.TRANSPARENT, 18, cyan, 1)
        }
        content.addView(lengthLabel, LinearLayout.LayoutParams(dp(150), dp(36)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(16), 0, dp(7))
        })

        val seek = SeekBar(this).apply {
            max = 24
            progress = 8
            minHeight = dp(42)
            progressTintList = android.content.res.ColorStateList.valueOf(cyan)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Palette.CYAN)
            thumbTintList = android.content.res.ColorStateList.valueOf(yellow)
            scaleY = 0.92f
            setPadding(0, dp(4), 0, dp(4))
        }
        seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s:SeekBar?, progress:Int, fromUser:Boolean) {
                lengthLabel.text = "LUNGHEZZA: ${progress + 8}"
            }
            override fun onStartTrackingTouch(s:SeekBar?) {}
            override fun onStopTrackingTouch(s:SeekBar?) {}
        })
        content.addView(seek, LinearLayout.LayoutParams(-1, dp(44)))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(17), 0, 0)
        }
        val cancel = TextView(this).apply {
            text = "ANNULLA"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, 1)
            setTextColor(Palette.TEXT_DIM)
            background = rounded(Color.TRANSPARENT, 16, Palette.TEXT_DIM, 1)
        }
        val generate = TextView(this).apply {
            text = "GENERA"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, 1)
            setTextColor(Palette.SURFACE)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(yellow, cyan)
            ).apply { cornerRadius = dp(16).toFloat() }
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0,0,dp(7),0) })
        buttons.addView(generate, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(7),0,0,0) })
        content.addView(buttons)

        val wrapper = FrameLayout(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this).setView(wrapper).create()
        cancel.setOnClickListener { dialog.dismiss() }
        generate.setOnClickListener {
            if(!letters.isChecked && !numbers.isChecked && !symbols.isChecked) {
                toast("Seleziona almeno un tipo di carattere")
                return@setOnClickListener
            }
            val value = generatedPassword(seek.progress + 8, letters.isChecked, numbers.isChecked, symbols.isChecked)
            dialog.dismiss()
            onGenerated(value)
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                setDimAmount(0.70f)
                attributes = attributes.apply { width = (resources.displayMetrics.widthPixels * 0.86f).toInt() }
            }
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun checkCompromisedPassword(password:String,onResult:(Int?)->Unit) {
        Thread {
            val result = runCatching {
                val digest=java.security.MessageDigest.getInstance("SHA-1").digest(password.toByteArray(Charsets.UTF_8))
                val hash=digest.joinToString(""){"%02X".format(it)}
                val prefix=hash.substring(0,5)
                val suffix=hash.substring(5)
                val connection=(java.net.URL("https://api.pwnedpasswords.com/range/$prefix").openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout=7000;readTimeout=7000;requestMethod="GET"
                    setRequestProperty("User-Agent","PasswordSafe-Android")
                    setRequestProperty("Add-Padding","true")
                }
                try {
                    if(connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                    connection.inputStream.bufferedReader().useLines { lines ->
                        lines.mapNotNull { line ->
                            val parts=line.trim().split(":",limit=2)
                            if(parts.size==2 && parts[0].equals(suffix,true)) parts[1].trim().toIntOrNull() else null
                        }.firstOrNull() ?: 0
                    }
                } finally { connection.disconnect() }
            }.getOrNull()
            runOnUiThread { onResult(result) }
        }.start()
    }

    private fun generatedPassword(length:Int=20,letters:Boolean=true,numbers:Boolean=true,symbols:Boolean=true): String {
        val random = java.security.SecureRandom()
        val selected = mutableListOf<String>()
        if(letters) selected += "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        if(numbers) selected += "23456789"
        if(symbols) selected += "!@#%&*+-_"
        if(selected.isEmpty()) return ""

        val safeLength = length.coerceAtLeast(selected.size)
        val pool = selected.joinToString("")
        val chars = mutableListOf<Char>()

        // Garantisce almeno un carattere per ogni categoria selezionata.
        selected.forEach { group ->
            chars += group[random.nextInt(group.length)]
        }
        repeat(safeLength - chars.size) {
            chars += pool[random.nextInt(pool.length)]
        }

        // Mescola la posizione dei caratteri obbligatori usando SecureRandom.
        for(i in chars.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return chars.joinToString("")
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
    private fun dialogField(hint:String,value:String)=EditText(this).apply {
        this.hint=hint
        setText(value)
        textSize=15f
        setTextColor(primaryText())
        setHintTextColor(secondaryText())
        background=GradientDrawable().apply { setColor(cardBg()); cornerRadius=dp(14).toFloat(); setStroke(dp(1),Palette.BORDER) }
        setPadding(dp(16),dp(12),dp(16),dp(12))
    }
    private fun styledDialogField(hint:String,value:String)=dialogField(hint,value).apply {
        layoutParams=LinearLayout.LayoutParams(-1,dp(58)).apply { setMargins(0,dp(5),0,dp(5)) }
    }
    private fun extraFieldHint(label:String):String = when(label.trim().uppercase()) {
        "SITO / DOMINIO", "SITO/DOMINIO", "SITO", "DOMINIO" -> "Sito / dominio (es. amazon.it)"
        "PIN" -> "PIN"
        "NOME" -> "Nome"
        else -> label.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun loginCustomLabels(): List<String> {
        return getSharedPreferences("passwordsafe_login_fields", MODE_PRIVATE)
            .getStringSet("custom_labels", emptySet())
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }

    private fun saveLoginCustomLabel(value:String): Boolean {
        val label = value.trim()
        if (label.isBlank()) return false
        val reserved = listOf("SITO / DOMINIO", "PIN", "NOME")
        if (reserved.any { it.equals(label, true) }) return false
        val current = loginCustomLabels().toMutableList()
        if (current.any { it.equals(label, true) }) return false
        current.add(label)
        getSharedPreferences("passwordsafe_login_fields", MODE_PRIVATE)
            .edit().putStringSet("custom_labels", current.toSet()).apply()
        return true
    }

    private fun deleteLoginCustomLabel(value:String) {
        val updated = loginCustomLabels().filterNot { it.equals(value, true) }.toSet()
        getSharedPreferences("passwordsafe_login_fields", MODE_PRIVATE)
            .edit().putStringSet("custom_labels", updated).apply()
    }

    private fun showLoginCustomFieldsSettings() {
        val body = column().apply {
            setPadding(0,0,0,dp(50))
            setBackgroundColor(panelBg())
            addView(pageHeader("Voci Login personalizzate") { showSettingsMenu() })
            addView(infoText("Le voci create qui compariranno nella scelta del nome dell’ultima casella dei Login."))
            addView(menuActionRow("Aggiungi nuova voce", R.drawable.ic_login) { showAddLoginCustomFieldDialog() })
            val labels = loginCustomLabels()
            if (labels.isEmpty()) {
                addView(infoText("Nessuna voce personalizzata. Restano disponibili Sito / dominio, PIN e Nome."))
            } else {
                addView(sectionLabel("VOCI CREATE"))
                labels.forEach { label ->
                    addView(menuActionRow(label, R.drawable.ic_login) {
                        themedDialogBuilder()
                            .setTitle("Elimina voce")
                            .setMessage("Vuoi eliminare ‘$label’ dall’elenco? Le credenziali già salvate con questa etichetta non vengono modificate.")
                            .setPositiveButton("ELIMINA") { _, _ ->
                                deleteLoginCustomLabel(label)
                                showLoginCustomFieldsSettings()
                            }
                            .setNegativeButton("ANNULLA", null)
                            .show()
                    })
                }
            }
        }
        setDarkScreen(body, false)
    }

    private fun showAddLoginCustomFieldDialog() {
        val input = darkEditorField("Nome della nuova voce", "")
        input.layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(18), dp(8), dp(18), 0) }
        val dialog = themedDialogBuilder()
            .setTitle("Nuova voce Login")
            .setView(input)
            .setPositiveButton("SALVA", null)
            .setNegativeButton("ANNULLA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                when {
                    value.isBlank() -> toast("Scrivi il nome della voce")
                    saveLoginCustomLabel(value) -> { dialog.dismiss(); showLoginCustomFieldsSettings() }
                    else -> toast("Voce già presente o riservata")
                }
            }
        }
        dialog.show()
    }

    private fun themedDialogBuilder(): AlertDialog.Builder =
        AlertDialog.Builder(this, R.style.PasswordSafeDialog)

    private fun showLoginExtraFieldLabelPicker(current:String, onSelected:(String)->Unit) {
        val base = listOf("SITO / DOMINIO", "PIN", "NOME")
        val options = (base + loginCustomLabels() + listOf(current).filter { value ->
            value.isNotBlank() && base.none { it.equals(value, true) } && loginCustomLabels().none { it.equals(value, true) }
        }).distinctBy { it.lowercase() }
        val selected = options.indexOfFirst { it.equals(current, true) }
        themedDialogBuilder()
            .setTitle("Nome dell'ultima casella")
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                onSelected(options[which])
                dialog.dismiss()
            }
            .setNegativeButton("ANNULLA", null)
            .show()
    }

    private fun darkEditorField(hint:String,value:String)=EditText(this).apply{this.hint=hint;setText(value);textSize=16f;setTextColor(primaryText());setHintTextColor(secondaryText());background=GradientDrawable().apply{setColor(cardBg());cornerRadius=dp(14).toFloat();setStroke(dp(1),if(isDarkTheme()) Palette.BORDER else Color.rgb(210,204,230))};layoutParams=LinearLayout.LayoutParams(-1,dp(56)).apply{setMargins(0,dp(2),0,dp(8))};setPadding(dp(16),0,dp(16),0)}
    private fun addEditorField(parent:LinearLayout,label:String,field:EditText){parent.addView(TextView(this).apply{text=label;textSize=9f;setTextColor(secondaryText());setTypeface(typeface,1);setPadding(dp(3),dp(4),0,0)});parent.addView(field)}
    private fun darkActionButton(label:String,action:()->Unit)=MaterialButton(this).apply{text=label;textSize=11f;isAllCaps=false;maxLines=1;isSingleLine=true;ellipsize=android.text.TextUtils.TruncateAt.END;insetTop=0;insetBottom=0;setTextColor(if(label=="SALVA")Color.WHITE else if(isDarkTheme()) Palette.CYAN else Palette.CYAN_DARK);setBackgroundColor(if(label=="SALVA")Palette.CYAN else chipBg());cornerRadius=dp(12);setOnClickListener{action()};layoutParams=LinearLayout.LayoutParams(-1,dp(46)).apply{setMargins(0,dp(7),0,0)}}
    private fun darkTextButton(label:String,action:()->Unit)=MaterialButton(this).apply{text=label;textSize=13f;isAllCaps=false;setTextColor(Color.rgb(255,105,105));setBackgroundColor(Color.TRANSPARENT);setOnClickListener{action()};layoutParams=LinearLayout.LayoutParams(-1,dp(48)).apply{setMargins(0,dp(5),0,0)}}
    private fun purpleLoginField(hint:String)=EditText(this).apply {
        this.hint=hint
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        textSize=16f
        gravity=Gravity.CENTER
        setTextColor(primaryText())
        setHintTextColor(secondaryText())
        background=GradientDrawable().apply { setColor(cardBg()); cornerRadius=dp(14).toFloat(); setStroke(dp(1),Palette.BORDER) }
        layoutParams=LinearLayout.LayoutParams(-1,dp(62))
        setPadding(dp(16),0,dp(16),0)
    }
    private fun smallButton(text:String, action:()->Unit)=MaterialButton(this).apply { this.text=text; textSize=11f; setOnClickListener{action()}; setTextColor(Color.WHITE); setBackgroundColor(blue) }
    private fun compactDialogButton(text:String,action:()->Unit)=smallButton(text,action).apply{maxLines=1;isSingleLine=true;insetTop=0;insetBottom=0;setPadding(dp(8),0,dp(8),0)}
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun strongMasterPassword(value:String)=value.length>=12&&value.any{it.isUpperCase()}&&value.any{it.isLowerCase()}&&value.any{it.isDigit()}
    private fun generateRecoveryCode():String{val chars="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";val random=java.security.SecureRandom();return (1..6).joinToString("-"){(1..5).map{chars[random.nextInt(chars.length)]}.joinToString("")}}
    private fun formatWait(ms:Long):String=when{ms>=60_000->"${(ms+59_999)/60_000} minuti";else->"${(ms+999)/1000} secondi"}

    private fun setPage(body: LinearLayout) {
        body.setBackgroundColor(appBg())
        val scroll = ScrollView(this).apply { setBackgroundColor(appBg()); addView(body) }
        setContentView(scroll)
    }
    private fun column(gravityValue: Int = Gravity.NO_GRAVITY) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = gravityValue; setPadding(48, 56, 48, 48)
    }
    private fun title(head: String, sub: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply { text = head; textSize = 30f; setTextColor(primaryText()); setTypeface(typeface, 1) })
        addView(TextView(this@MainActivity).apply { text = sub; textSize = 16f; setTextColor(secondaryText()); setPadding(0, dp(8), 0, dp(28)) })
    }
    private fun field(hint: String, pin: Boolean = false): Pair<TextInputLayout, TextInputEditText> {
        val edit = TextInputEditText(this).apply {
            this.hint = hint
            setTextColor(primaryText())
            setHintTextColor(secondaryText())
            if (pin) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val layout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = cardBg()
            setBoxStrokeColorStateList(android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(Palette.CYAN, Palette.BORDER)
            ))
            hintTextColor = android.content.res.ColorStateList.valueOf(secondaryText())
            if (pin) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(edit)
        }
        layout.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(7), 0, dp(7)) }
        return layout to edit
    }
    private fun button(text: String, action: () -> Unit) = MaterialButton(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        setBackgroundColor(Palette.CYAN_DARK)
        cornerRadius = dp(18)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { setMargins(0, dp(6), 0, dp(6)) }
    }
    private fun errorText() = TextView(this).apply { setTextColor(Color.rgb(190,30,45)); textSize = 14f; visibility = View.GONE; setPadding(4,8,4,8) }
    private fun showError(view: TextView, message: String) { view.text = message; view.visibility = View.VISIBLE }
}
