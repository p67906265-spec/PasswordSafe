package it.paolo.passwordsafe

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import java.util.WeakHashMap

/**
 * PasswordSafe 0.47
 * - Il tasto Indietro di Android usa prima la navigazione interna dell'app.
 * - La Home viene resa leggermente più compatta senza modificare i dati salvati.
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
        private val compactedHomes = WeakHashMap<View, Boolean>()

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
            installHomeCompaction(main)
            compactHomeIfVisible(main)
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

        private fun installHomeCompaction(activity: MainActivity) {
            if (layoutHooks.containsKey(activity)) return
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                compactHomeIfVisible(activity)
            }
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
            layoutHooks[activity] = LayoutHook(root, listener)
        }

        private fun handleInternalBack(activity: MainActivity) {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

            // Le pagine interne hanno già la freccia ‹ con la destinazione corretta.
            findFirst(root) { view ->
                view is TextView &&
                    view.isShown &&
                    view.isClickable &&
                    view.text.toString().trim() == "‹"
            }?.let {
                it.performClick()
                return
            }

            // Alcune vecchie schermate usano un pulsante testuale invece della freccia.
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

            // Nella Home, se è aperta una categoria, Indietro torna alla Home neutra.
            if (containsText(root, "La tua cassaforte")) {
                resetHomeFilter(activity)
                return
            }

            // Login/setup: non chiudere l'app con un tocco involontario su Indietro.
        }

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

        private fun compactHomeIfVisible(activity: MainActivity) {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val title = findText(root, "La tua cassaforte") ?: return
            if (compactedHomes.put(title, true) != null) return

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

            // Pannello protezione: da 138dp a circa 122dp.
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

            // Tutte le 8 card principali della Home diventano un po' più basse.
            val categories = setOf(
                "Account", "PIN", "Login", "Email", "Carte", "Passkey", "App", "Altro / Impostazioni"
            )
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

            // Anche il riquadro informativo sotto le categorie resta coerente e più compatto.
            findText(root, "Seleziona una categoria per visualizzare gli elementi.")?.let { info ->
                (info.parent as? LinearLayout)?.setPadding(dp(12), dp(8), dp(12), dp(8))
            }
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
