package it.paolo.passwordsafe

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import java.util.WeakHashMap

/**
 * PasswordSafe 0.50
 * - Il tasto Indietro di Android usa prima la navigazione interna dell'app.
 * - La Home resta compatta.
 * - Ogni categoria viene presentata come pagina dedicata.
 * - Le righe degli elementi sono compatte e con testo uniforme.
 * - Nelle Impostazioni compare la firma "Paolo Free 1.0".
 */
class PasswordSafeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(UiLifecycleCallbacks())
    }

    private class UiLifecycleCallbacks : ActivityLifecycleCallbacks {
        private data class LayoutHook(
            val root: ViewGroup,
            val listener: ViewTreeObserver.OnGlobalLayoutListener
        )

        private val backCallbacks = WeakHashMap<MainActivity, OnBackPressedCallback>()
        private val layoutHooks = WeakHashMap<MainActivity, LayoutHook>()
        private val processedScreens = WeakHashMap<View, Boolean>()
        private val signedSettingsScreens = WeakHashMap<View, Boolean>()

        private val categories = setOf(
            "Account", "PIN", "Login", "Email", "Carte", "Passkey", "App", "Altro / Impostazioni"
        )

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            val main = activity as? MainActivity ?: return
            if (backCallbacks.containsKey(main)) return

            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleInternalBack(main)
                }
            }
            main.onBackPressedDispatcher.addCallback(main, callback)
            backCallbacks[main] = callback
        }

        override fun onActivityResumed(activity: Activity) {
            val main = activity as? MainActivity ?: return
            installUiHook(main)
            updateHomeOrCategory(main)
        }

        override fun onActivityDestroyed(activity: Activity) {
            val main = activity as? MainActivity ?: return
            layoutHooks.remove(main)?.let { hook ->
                if (hook.root.viewTreeObserver.isAlive) {
                    hook.root.viewTreeObserver.removeOnGlobalLayoutListener(hook.listener)
                }
            }
            backCallbacks.remove(main)?.remove()
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        private fun installUiHook(activity: MainActivity) {
            if (layoutHooks.containsKey(activity)) return
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                updateHomeOrCategory(activity)
            }
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
            layoutHooks[activity] = LayoutHook(root, listener)
        }

        private fun handleInternalBack(activity: MainActivity) {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

            // Le pagine interne (compresa la nuova pagina categoria) hanno la freccia ‹.
            findFirst(root) { view ->
                view is TextView &&
                    view.isShown &&
                    view.isClickable &&
                    view.text.toString().trim() == "‹"
            }?.let {
                it.performClick()
                return
            }

            // Alcune schermate storiche usano un pulsante testuale invece della freccia.
            val backLabels = setOf("INDIETRO", "TORNA INDIETRO", "ANNULLA", "ESCI")
            findFirst(root) { view ->
                view is TextView &&
                    view.isShown &&
                    view.isClickable &&
                    view.text.toString().trim().uppercase() in backLabels
            }?.let {
                it.performClick()
                return
            }

            // Nella Home non chiudere l'app con un tocco involontario su Indietro.
            if (containsText(root, "La tua cassaforte")) {
                resetHomeFilter(activity)
                return
            }

            // Login/setup: non chiudere l'app con un tocco involontario su Indietro.
        }

        private fun currentHomeFilter(activity: MainActivity): String = runCatching {
            MainActivity::class.java.getDeclaredField("vaultTypeFilter").apply {
                isAccessible = true
            }.get(activity) as? String ?: "NONE"
        }.getOrDefault("NONE")

        private fun resetHomeFilter(activity: MainActivity): Boolean = runCatching {
            val filterField = MainActivity::class.java.getDeclaredField("vaultTypeFilter").apply {
                isAccessible = true
            }
            val current = filterField.get(activity) as? String ?: "NONE"
            if (current == "NONE") return@runCatching false

            filterField.set(activity, "NONE")
            MainActivity::class.java.getDeclaredMethod("showCategoryMenu").apply {
                isAccessible = true
            }.invoke(activity)
            true
        }.getOrDefault(false)

        private fun updateHomeOrCategory(activity: MainActivity) {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            addSettingsSignature(activity, root)
            val title = findText(root, "La tua cassaforte") ?: return
            if (processedScreens.put(title, true) != null) return

            val filter = currentHomeFilter(activity)
            if (filter != "NONE") {
                transformFilteredHomeIntoCategoryPage(activity, root, title, filter)
            } else {
                compactHome(activity, root, title)
            }
        }


        private fun addSettingsSignature(activity: MainActivity, root: ViewGroup) {
            val settingsTitle = findText(root, "Impostazioni") ?: return
            if (signedSettingsScreens.put(settingsTitle, true) != null) return

            val header = settingsTitle.parent as? ViewGroup ?: return
            val body = header.parent as? LinearLayout ?: return
            fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

            body.addView(TextView(activity).apply {
                text = "Paolo Free 1.0"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setTextColor(Palette.CYAN)
                setPadding(dp(16), dp(28), dp(16), dp(26))
                contentDescription = "Paolo Free 1.0"
            }, LinearLayout.LayoutParams(-1, -2))
        }

        /**
         * MainActivity continua a costruire i dati con la logica già collaudata.
         * Quando una categoria è selezionata, qui nascondiamo completamente la parte Home
         * (titolo, pannello sicurezza e griglia categorie) e lasciamo a schermo solo
         * l'elenco filtrato, preceduto da un header dedicato con Indietro.
         */
        private fun transformFilteredHomeIntoCategoryPage(
            activity: MainActivity,
            root: ViewGroup,
            title: TextView,
            filter: String
        ) {
            fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

            val body = title.parent as? LinearLayout ?: return
            body.setPadding(dp(16), dp(8), dp(16), dp(96))

            // Nasconde titolo e contatore generali della Home.
            title.visibility = View.GONE
            val titleIndex = body.indexOfChild(title)
            if (titleIndex >= 0 && titleIndex + 1 < body.childCount) {
                val maybeSavedCount = body.getChildAt(titleIndex + 1) as? TextView
                if (maybeSavedCount?.text?.toString()?.contains("elementi salvati") == true) {
                    maybeSavedCount.visibility = View.GONE
                }
            }

            // Nasconde il pannello "La tua cassaforte è protetta".
            findText(root, "La tua cassaforte è protetta")?.let { protectionTitle ->
                findAncestorWithMinHeight(protectionTitle, dp(110))?.visibility = View.GONE
            }

            // Nasconde tutte le righe con i pulsanti delle categorie.
            for (i in 0 until body.childCount) {
                val child = body.getChildAt(i)
                if (containsCategoryButton(child)) child.visibility = View.GONE
            }

            // Titolo pulito della pagina selezionata.
            val label = when (filter) {
                "ACCOUNT" -> "Account"
                "PIN" -> "PIN"
                "LOGIN" -> "Login"
                "EMAIL" -> "Email"
                "CARD" -> "Carte"
                "PASSKEY" -> "Passkey"
                "APP" -> "App"
                else -> "Categoria"
            }

            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(8))

                addView(TextView(activity).apply {
                    text = "‹"
                    textSize = 38f
                    gravity = Gravity.CENTER
                    setTextColor(primaryText(activity))
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Torna alla Home"
                    setOnClickListener { resetHomeFilter(activity) }
                }, LinearLayout.LayoutParams(dp(48), dp(52)))

                addView(TextView(activity).apply {
                    text = label
                    textSize = 24f
                    setTypeface(typeface, 1)
                    setTextColor(primaryText(activity))
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, dp(52), 1f))
            }

            body.addView(header, 0, LinearLayout.LayoutParams(-1, dp(64)))

            // Rende l'intestazione interna dell'elenco più discreta: il titolo vero è già sopra.
            val internalSection = findText(root, when (filter) {
                "ACCOUNT" -> "ACCOUNT"
                "PIN" -> "PIN"
                "LOGIN" -> "LOGIN"
                "EMAIL" -> "EMAIL"
                "CARD" -> "CARTE"
                "PASSKEY" -> "PASSKEY"
                "APP" -> "APP"
                else -> ""
            })
            internalSection?.let {
                it.textSize = 10f
                it.setPadding(dp(6), dp(2), dp(6), dp(7))
            }

            // 0.49: righe più compatte e caratteri uniformi in tutte le categorie.
            compactCategoryRows(activity, body)
        }

        private fun compactCategoryRows(activity: MainActivity, root: ViewGroup) {
            fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
            fun sp(view: TextView, value: Float) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, value)
            }

            traverse(root) { view ->
                val row = view as? LinearLayout ?: return@traverse
                if (row.orientation != LinearLayout.HORIZONTAL || row.childCount != 3) return@traverse

                val logoWrap = row.getChildAt(0) as? FrameLayout ?: return@traverse
                val title = row.getChildAt(1) as? TextView ?: return@traverse
                val arrow = row.getChildAt(2) as? TextView ?: return@traverse
                if (arrow.text.toString().trim() != "›") return@traverse

                val currentHeight = row.layoutParams?.height ?: return@traverse
                if (currentHeight < dp(86)) return@traverse

                (row.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.height = dp(72)
                    lp.setMargins(lp.leftMargin, dp(4), lp.rightMargin, dp(4))
                    row.layoutParams = lp
                }
                row.setPadding(dp(10), dp(8), dp(10), dp(8))
                (row.background as? GradientDrawable)?.cornerRadius = dp(18).toFloat()

                (logoWrap.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.width = dp(46)
                    lp.height = dp(46)
                    lp.setMargins(lp.leftMargin, lp.topMargin, dp(12), lp.bottomMargin)
                    logoWrap.layoutParams = lp
                }
                (logoWrap.background as? GradientDrawable)?.cornerRadius = dp(14).toFloat()

                for (i in 0 until logoWrap.childCount) {
                    when (val child = logoWrap.getChildAt(i)) {
                        is TextView -> sp(child, 18f)
                        is android.widget.ImageView -> child.setPadding(dp(6), dp(6), dp(6), dp(6))
                    }
                }

                sp(title, 15f)
                title.maxLines = 1
                sp(arrow, 23f)
                (arrow.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.width = dp(20)
                    arrow.layoutParams = lp
                }
            }
        }

        private fun compactHome(activity: MainActivity, root: ViewGroup, title: TextView) {
            fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
            fun sp(view: TextView, value: Float) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, value)
            }

            // Margini generali della Home leggermente più stretti.
            (title.parent as? LinearLayout)?.setPadding(dp(16), dp(12), dp(16), dp(96))
            sp(title, 25f)

            traverse(root) { view ->
                if (view !is TextView) return@traverse
                val text = view.text.toString()
                when {
                    text.endsWith(" elementi salvati") || text == "1 elemento salvato" -> sp(view, 13f)
                    text == "La tua cassaforte è protetta" -> sp(view, 14.2f)
                    text == "Dati cifrati sul dispositivo" -> sp(view, 10.5f)
                    text == "Seleziona una categoria per visualizzare gli elementi." -> sp(view, 11.8f)
                }
            }

            // Pannello protezione: più compatto della 0.46.
            findText(root, "La tua cassaforte è protetta")?.let { protectionTitle ->
                val summary = findAncestorWithMinHeight(protectionTitle, dp(125))
                if (summary is LinearLayout) {
                    (summary.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                        lp.height = dp(122)
                        lp.setMargins(lp.leftMargin, dp(12), lp.rightMargin, dp(6))
                        summary.layoutParams = lp
                    }
                    summary.setPadding(dp(9), dp(8), dp(9), dp(8))
                    (summary.getChildAt(0) as? FrameLayout)?.let { shield ->
                        (shield.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                            lp.width = dp(84)
                            lp.height = dp(100)
                            lp.setMargins(0, 0, dp(5), 0)
                            shield.layoutParams = lp
                        }
                    }
                }
            }

            // Tutte le 8 card principali della Home restano compatte.
            traverse(root) { view ->
                val card = view as? LinearLayout ?: return@traverse
                if (card.orientation != LinearLayout.HORIZONTAL) return@traverse
                if (card.contentDescription?.toString() !in categories) return@traverse

                (card.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.height = dp(72)
                    lp.setMargins(dp(3), dp(3), dp(3), dp(3))
                    card.layoutParams = lp
                }
                card.setPadding(dp(10), dp(7), dp(7), dp(7))

                (card.getChildAt(0) as? FrameLayout)?.let { iconWrap ->
                    (iconWrap.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                        lp.width = dp(40)
                        lp.height = dp(40)
                        lp.setMargins(0, 0, dp(8), 0)
                        iconWrap.layoutParams = lp
                    }
                    if (iconWrap.childCount > 0) {
                        iconWrap.getChildAt(0).setPadding(dp(7), dp(7), dp(7), dp(7))
                    }
                }

                (card.getChildAt(1) as? LinearLayout)?.let { labels ->
                    (labels.getChildAt(0) as? TextView)?.let { label ->
                        sp(label, if (card.contentDescription?.toString() == "Altro / Impostazioni") 12f else 14f)
                    }
                    (labels.getChildAt(1) as? TextView)?.let { count -> sp(count, 10f) }
                }

                (card.getChildAt(card.childCount - 1) as? TextView)?.let { arrow ->
                    sp(arrow, 24f)
                    (arrow.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                        lp.width = dp(18)
                        arrow.layoutParams = lp
                    }
                }
            }

            // Il riquadro informativo della Home resta compatto.
            findText(root, "Seleziona una categoria per visualizzare gli elementi.")?.let { info ->
                (info.parent as? LinearLayout)?.setPadding(dp(12), dp(8), dp(12), dp(8))
            }
        }

        private fun primaryText(activity: MainActivity): Int {
            val dark = activity.getSharedPreferences("passwordsafe_ui", Activity.MODE_PRIVATE)
                .getBoolean("dark_theme", true)
            return if (dark) Palette.TEXT else Color.rgb(7, 24, 39)
        }

        private fun containsCategoryButton(root: View): Boolean {
            if (root.contentDescription?.toString() in categories) return true
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    if (containsCategoryButton(root.getChildAt(i))) return true
                }
            }
            return false
        }

        private fun findAncestorWithMinHeight(view: View, minHeight: Int): ViewGroup? {
            var parent = view.parent
            while (parent is ViewGroup) {
                val h = parent.layoutParams?.height ?: 0
                if (h >= minHeight) return parent
                parent = parent.parent
            }
            return null
        }

        private fun containsText(root: View, text: String): Boolean = findText(root, text) != null

        private fun findText(root: View, text: String): TextView? {
            if (root is TextView && root.text.toString() == text) return root
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    findText(root.getChildAt(i), text)?.let { return it }
                }
            }
            return null
        }

        private fun findFirst(root: View, predicate: (View) -> Boolean): View? {
            if (predicate(root)) return root
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    findFirst(root.getChildAt(i), predicate)?.let { return it }
                }
            }
            return null
        }

        private fun traverse(root: View, block: (View) -> Unit) {
            block(root)
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    traverse(root.getChildAt(i), block)
                }
            }
        }
    }
}
