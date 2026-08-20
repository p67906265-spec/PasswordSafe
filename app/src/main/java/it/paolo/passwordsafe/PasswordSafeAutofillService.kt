package it.paolo.passwordsafe

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews

class PasswordSafeAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }

        val vault = VaultStore(this)
        if (!vault.isUnlocked()) {
            callback.onSuccess(null)
            return
        }

        val allItems = runCatching { vault.load() }.getOrElse {
            callback.onSuccess(null)
            return
        }
        val credentials = allItems.filter {
            it.type in setOf("ACCOUNT", "LOGIN", "EMAIL") &&
                it.username.isNotBlank() && it.password.isNotBlank()
        }
        if (credentials.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        val targets = FillTargets()
        for (windowIndex in 0 until structure.windowNodeCount) {
            collectTargets(structure.getWindowNodeAt(windowIndex).rootViewNode, targets)
        }
        if (targets.userIds.isEmpty() && targets.passwordIds.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        val packageName = structure.activityComponent?.packageName.orEmpty()
        val target = (targets.webDomain ?: packageName).lowercase()
        val matched = credentials.filter { matchesTarget(it, target) }
        val candidates = (if (matched.isNotEmpty()) matched else credentials).take(10)

        val response = FillResponse.Builder()
        candidates.forEach { item ->
            val presentation = RemoteViews(this.packageName, R.layout.autofill_dataset).apply {
                setTextViewText(R.id.autofill_label, "PasswordSafe · ${item.title}")
            }
            val dataset = Dataset.Builder(presentation)
            targets.userIds.forEach { id ->
                dataset.setValue(id, AutofillValue.forText(item.username))
            }
            targets.passwordIds.forEach { id ->
                dataset.setValue(id, AutofillValue.forText(item.password))
            }
            response.addDataset(dataset.build())
        }
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    private data class FillTargets(
        val userIds: MutableList<AutofillId> = mutableListOf(),
        val passwordIds: MutableList<AutofillId> = mutableListOf(),
        var webDomain: String? = null
    )

    private fun collectTargets(node: AssistStructure.ViewNode, targets: FillTargets) {
        if (targets.webDomain.isNullOrBlank() && !node.webDomain.isNullOrBlank()) {
            targets.webDomain = node.webDomain
        }

        val id = node.autofillId
        if (id != null) {
            val hints = node.autofillHints?.map { it.lowercase() }.orEmpty()
            val descriptor = listOfNotNull(node.hint, node.idEntry, node.text?.toString())
                .joinToString(" ")
                .lowercase()
            val variation = node.inputType and InputType.TYPE_MASK_VARIATION

            val password = hints.any { it.contains("password") } ||
                descriptor.contains("password") || descriptor.contains("passwd") || descriptor.contains("pwd") ||
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

            val user = !password && (
                hints.any { it.contains("username") || it.contains("email") } ||
                    descriptor.contains("username") || descriptor.contains("user name") ||
                    descriptor.contains("email") || descriptor.contains("e-mail") ||
                    descriptor.contains("login")
                )

            if (password && id !in targets.passwordIds) targets.passwordIds.add(id)
            if (user && id !in targets.userIds) targets.userIds.add(id)
        }

        for (i in 0 until node.childCount) {
            collectTargets(node.getChildAt(i), targets)
        }
    }

    private fun matchesTarget(item: VaultItem, target: String): Boolean {
        if (target.isBlank()) return false
        val normalizedTarget = target.lowercase().trim()
        val appPackage = item.appPackage.lowercase().trim()
        if (appPackage.isNotBlank() && normalizedTarget == appPackage) return true

        val title = item.title.lowercase().replace(" ", "")
        val domain = normalizeDomain(item.url)
        val compactTarget = normalizedTarget.replace(" ", "")
        return (domain.isNotBlank() && (compactTarget.contains(domain) || domain.contains(compactTarget))) ||
            (title.length >= 3 && compactTarget.contains(title))
    }

    private fun normalizeDomain(value: String): String = value
        .lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore('/')
        .substringBefore('?')
        .trim()
}
