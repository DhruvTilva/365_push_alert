package com.dhruvtilva.pushalert.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dhruvtilva.pushalert.AppGraph
import com.dhruvtilva.pushalert.collect.PushAlertListenerService
import com.dhruvtilva.pushalert.data.GroupResult
import com.dhruvtilva.pushalert.data.SecretState
import com.dhruvtilva.pushalert.discord.TestOutcome
import com.dhruvtilva.pushalert.discord.WebhookUrl
import com.dhruvtilva.pushalert.domain.GroupFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SettingsScreen(private val activity: Activity, private val graph: AppGraph, private val ui: Ui) : Screen {
    private val p = ui.p
    private var scope: CoroutineScope? = null

    private val accessStatus = ui.text("", 14f)
    private val groupsBox = ui.vbox()
    private val webhookStatus = ui.text("", 14f)
    private val webhookInput = ui.input("https://discord.com/api/webhooks/…")
    private val testResult = ui.text("", 14f)
    private val packagesInput = ui.input("com.whatsapp, com.whatsapp.w4b")
    private val collectionCheck: CheckBox
    private val individualCheck: CheckBox
    private val diagnosticsCheck: CheckBox
    private val historyCheck: CheckBox

    override val view: View

    init {
        val col = ui.vbox().apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(24)) }
        val s = graph.settings

        // ---- A. WhatsApp collection
        col.addView(ui.title("WhatsApp collection"))
        col.addView(accessStatus)
        col.addView(ui.button("Open notification access settings") {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, ui.lp(top = 6))
        col.addView(ui.button("Open app info (for \"Allow restricted settings\")") {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        }, ui.lp(top = 6))
        col.addView(ui.sub("Android 13+ blocks notification access for apps installed from an APK file until you open App info → ⋮ → \"Allow restricted settings\"."), ui.lp(top = 4))

        collectionCheck = ui.check("Collect WhatsApp notifications", s.settings.value.collectionEnabled) { s.setCollectionEnabled(it) }
        col.addView(collectionCheck)
        col.addView(ui.text("Divider above every Discord message", 14f, p.text, true), ui.lp(top = 10))
        val styleSpinner = android.widget.Spinner(activity)
        val styles = com.dhruvtilva.pushalert.domain.DividerStyle.values().toList()
        styleSpinner.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, styles.map { it.label })
        styleSpinner.setSelection(styles.indexOf(s.settings.value.dividerStyle))
        styleSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { s.setDividerStyle(styles[pos]) }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        col.addView(styleSpinner)
        val titleInput = ui.input("Divider title").apply { setText(s.settings.value.headerTitle) }
        val numberInput = ui.input("Next number").apply {
            setText(s.settings.value.nextUpdateNumber.toString()); inputType = InputType.TYPE_CLASS_NUMBER
        }
        col.addView(ui.sub("Example: ### <title> #<number>. You can put emojis in the title, e.g. \uD83D\uDCE2 \uD83D\uDEA8 \uD83D\uDCC8. The received message text is never changed or split."), ui.lp(top = 4))
        col.addView(titleInput)
        col.addView(numberInput)
        col.addView(ui.button("Save divider") {
            val err = s.setHeader(titleInput.text.toString(), numberInput.text.toString())
            toast(err ?: "Saved. Next message: ${s.settings.value.headerTitle} #${s.settings.value.nextUpdateNumber}")
        }, ui.lp(top = 4))
        individualCheck = ui.check("Also collect individual chats whose contact name matches a configured name", s.settings.value.allowIndividualChats) { s.setAllowIndividualChats(it) }
        col.addView(individualCheck)

        col.addView(ui.text("WhatsApp package names", 14f, p.text, true), ui.lp(top = 8))
        packagesInput.setText(s.settings.value.whatsappPackages.joinToString(", "))
        col.addView(packagesInput)
        val pkgRow = ui.hbox()
        pkgRow.addView(ui.button("Save packages") {
            val err = s.setWhatsappPackages(packagesInput.text.toString())
            toast(err ?: "Saved.")
        }, ui.lp(w = 0, weight = 1f, end = 4))
        pkgRow.addView(ui.button("Reset") {
            s.resetWhatsappPackages()
            packagesInput.setText(s.settings.value.whatsappPackages.joinToString(", "))
        }, ui.lp(w = 0, weight = 1f, start = 4))
        col.addView(pkgRow, ui.lp(top = 4))

        col.addView(ui.text("Group filters", 16f, p.text, true), ui.lp(top = 12))
        col.addView(ui.sub("Only notifications whose conversation title exactly matches an enabled name (ignoring case and extra spaces) are collected."))
        col.addView(groupsBox, ui.lp(top = 6))
        col.addView(ui.button("Add group", filled = true) { showGroupDialog(null) }, ui.lp(top = 6))

        diagnosticsCheck = ui.check("Record collection diagnostics (reason codes only, never message text)", s.settings.value.diagnosticsEnabled) { s.setDiagnosticsEnabled(it) }
        col.addView(diagnosticsCheck, ui.lp(top = 8))
        val diagRow = ui.hbox()
        diagRow.addView(ui.button("View diagnostics") { showDiagnostics() }, ui.lp(w = 0, weight = 1f, end = 4))
        diagRow.addView(ui.button("Clear diagnostics") { scope?.launch { graph.store.clearDiagnostics() } }, ui.lp(w = 0, weight = 1f, start = 4))
        col.addView(diagRow)

        // ---- B. Discord
        col.addView(ui.title("Discord"))
        col.addView(webhookStatus)
        webhookInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        webhookInput.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        col.addView(webhookInput, ui.lp(top = 6))
        col.addView(ui.check("Show what I type", false) { show ->
            val cursor = webhookInput.selectionStart
            webhookInput.inputType = InputType.TYPE_CLASS_TEXT or
                if (show) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            webhookInput.setSelection(cursor.coerceAtLeast(0))
            // Prevent screenshots / screen recording while a secret is on screen.
            if (show) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        })
        val hookRow = ui.hbox()
        hookRow.addView(ui.button("Save", filled = true) { saveWebhook() }, ui.lp(w = 0, weight = 1f, end = 4))
        hookRow.addView(ui.button("Test connection") { testWebhook() }, ui.lp(w = 0, weight = 1f, start = 4, end = 4))
        hookRow.addView(ui.button("Clear") { confirmClearWebhook() }, ui.lp(w = 0, weight = 1f, start = 4))
        col.addView(hookRow)
        col.addView(testResult, ui.lp(top = 6))
        col.addView(ui.sub("The webhook URL is stored encrypted with an Android Keystore key and is never shown in full or logged. \"Test connection\" only reads the webhook's metadata; it posts nothing to your channel. A webhook can only post messages to its one channel."), ui.lp(top = 4))

        // ---- C. Inbox
        col.addView(ui.title("Inbox & history"))
        col.addView(ui.button("Clear selected pending messages") {
            confirm("Delete selected pending messages?", "They will not be published.") { scope?.launch { toast("${graph.store.deleteSelected()} deleted.") } }
        })
        col.addView(ui.button("Clear all pending messages") {
            confirm("Delete ALL pending messages?", "Every message in the inbox (including failed and uncertain ones) is deleted and will not be published.") {
                scope?.launch { toast("${graph.store.deleteAllPending()} deleted.") }
            }
        }, ui.lp(top = 6))
        historyCheck = ui.check("Keep Sent History", s.settings.value.historyEnabled) { s.setHistoryEnabled(it) }
        col.addView(historyCheck, ui.lp(top = 6))
        col.addView(ui.sub("When off, a message's content is deleted as soon as it is published."))
        col.addView(ui.button("Clear Sent History") {
            confirm("Clear Sent History?", "Deletes the local record of published messages. Discord is not affected.") { scope?.launch { graph.store.clearHistory() } }
        }, ui.lp(top = 6))

        // ---- D. Privacy
        col.addView(ui.title("Privacy"))
        col.addView(ui.text(PRIVACY_TEXT, 14f))
        col.addView(ui.button("Delete all locally stored message content") {
            confirm("Delete all message content?", "Deletes pending messages and Sent History from this phone. Settings and group names are kept.") {
                scope?.launch { graph.store.deleteAllPending(); graph.store.clearHistory(); graph.store.clearDiagnostics(); toast("Deleted.") }
            }
        }, ui.lp(top = 8))

        // ---- E. About
        col.addView(ui.title("About"))
        col.addView(ui.text(aboutText(), 14f))
        col.addView(ui.title("Troubleshooting"))
        col.addView(ui.text(TROUBLESHOOTING_TEXT, 14f))

        view = ScrollView(activity).apply { addView(col) }
    }

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        refreshAccess()
        scope.launch { graph.store.observeGroups().collect { renderGroups(it) } }
        scope.launch { graph.secrets.webhook.collect { renderWebhook(it) } }
        scope.launch { PushAlertListenerService.connected.collect { refreshAccess() } }
        testResult.text = graph.lastWebhookTestResult.orEmpty()
    }

    fun refreshAccess() {
        val granted = PushAlertListenerService.isAccessGranted(activity)
        val connected = PushAlertListenerService.connected.value
        accessStatus.text = when {
            !granted -> "Notification access: DISABLED"
            connected -> "Notification access: Enabled (listener connected)"
            else -> "Notification access: enabled in Android, listener not connected yet"
        }
        accessStatus.setTextColor(if (granted && connected) p.ok else if (granted) p.warn else p.danger)
    }

    private fun renderGroups(list: List<GroupFilter>) {
        groupsBox.removeAllViews()
        if (list.isEmpty()) {
            groupsBox.addView(ui.text("No groups configured. Nothing is collected until you add at least one.", 14f, p.warn))
            return
        }
        list.forEach { g ->
            val card = ui.card()
            val top = ui.hbox()
            top.addView(ui.text(g.groupName, 15f, p.text, true), ui.lp(w = 0, weight = 1f))
            top.addView(ui.check("Enabled", g.enabled) { on -> scope?.launch { graph.store.setGroupEnabled(g.id, on) } })
            card.addView(top)
            card.addView(ui.sub("Collected so far: ${g.collectedCount}"))
            val row = ui.hbox()
            row.addView(ui.button("Edit") { showGroupDialog(g) }, ui.lp(w = 0, weight = 1f, end = 4))
            row.addView(ui.button("Delete") {
                confirm("Delete group \"${g.groupName}\"?", "Messages already collected stay in the inbox; new ones from this group will be ignored.") {
                    scope?.launch { graph.store.deleteGroup(g.id) }
                }
            }, ui.lp(w = 0, weight = 1f, start = 4))
            card.addView(row, ui.lp(top = 4))
            groupsBox.addView(card, ui.lp(bottom = 6))
        }
    }

    private fun renderWebhook(state: SecretState) {
        when (state) {
            SecretState.Missing -> { webhookStatus.text = "Webhook: not configured"; webhookStatus.setTextColor(p.warn) }
            is SecretState.Present -> { webhookStatus.text = "Webhook: configured\n${state.webhook.masked()}"; webhookStatus.setTextColor(p.ok) }
            SecretState.Unreadable -> {
                webhookStatus.text = "Webhook: the stored value could not be read. Please enter it again."
                webhookStatus.setTextColor(p.danger)
            }
        }
    }

    private fun showGroupDialog(existing: GroupFilter?) {
        val input = ui.input("Exact WhatsApp group name").apply { setText(existing?.groupName.orEmpty()); setSelection(text.length) }
        val box = ui.vbox().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        box.addView(input)
        box.addView(ui.sub("Type the group's name exactly as it appears in WhatsApp. Case and extra spaces are ignored; partial names do not match."))
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "Add group" else "Edit group")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString()
            scope?.launch {
                val r = if (existing == null) graph.store.addGroup(name) else graph.store.renameGroup(existing.id, name)
                when (r) {
                    is GroupResult.Saved -> dialog.dismiss()
                    is GroupResult.Invalid -> input.error = r.message
                    GroupResult.Duplicate -> input.error = "A group with this name already exists."
                    GroupResult.NotFound -> { toast("That group no longer exists."); dialog.dismiss() }
                }
            }
        }
    }

    private fun saveWebhook() {
        when (val r = WebhookUrl.parse(webhookInput.text.toString())) {
            is WebhookUrl.ParseResult.Invalid -> { webhookInput.error = r.reason }
            is WebhookUrl.ParseResult.Valid -> {
                scope?.launch {
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.secrets.save(r.url) }
                        webhookInput.setText("")
                        toast("Webhook saved (encrypted).")
                    } catch (e: Exception) {
                        toast("Could not store the webhook securely on this device.")
                    }
                }
            }
        }
    }

    private fun testWebhook() {
        val typed = webhookInput.text.toString().trim()
        val hook: WebhookUrl? = if (typed.isNotEmpty()) {
            when (val r = WebhookUrl.parse(typed)) {
                is WebhookUrl.ParseResult.Valid -> r.url
                is WebhookUrl.ParseResult.Invalid -> { webhookInput.error = r.reason; return }
            }
        } else graph.secrets.current()
        if (hook == null) { setTest("Enter and save a webhook URL first."); return }
        setTest("Testing…")
        scope?.launch {
            val msg = when (val r = graph.client.test(hook)) {
                is TestOutcome.Ok -> "Connection OK" + (r.webhookName?.let { " – webhook \"$it\"" } ?: "") + ". Nothing was posted."
                is TestOutcome.Failed -> "Test failed: ${r.message}"
            }
            setTest(msg)
        }
    }

    private fun setTest(msg: String) {
        graph.lastWebhookTestResult = msg
        testResult.text = msg
    }

    private fun confirmClearWebhook() {
        confirm("Remove webhook?", "The stored webhook is deleted from this phone. Publishing is disabled until you add one.") {
            graph.secrets.clear()
            setTest("")
        }
    }

    private fun showDiagnostics() {
        scope?.launch {
            val events = graph.store.diagnostics()
            val text = if (events.isEmpty()) "No events recorded yet." else events.take(60).joinToString("\n\n") {
                "${ui.formatTime(it.at)}\n${it.detail}"
            }
            val tv = ui.text(text, 13f).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
            AlertDialog.Builder(activity).setTitle("Collection diagnostics")
                .setView(ScrollView(activity).apply { addView(tv) })
                .setPositiveButton("Close", null).show()
        }
    }

    private fun confirm(title: String, msg: String, action: () -> Unit) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    private fun aboutText(): String {
        val info = try { activity.packageManager.getPackageInfo(activity.packageName, 0) } catch (e: Exception) { null }
        val version = info?.versionName ?: "?"
        @Suppress("DEPRECATION")
        val code = info?.versionCode ?: 0
        return "365 Push Alert $version (build $code)\nApplication ID: ${activity.packageName}\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private companion object {
        const val PRIVACY_TEXT = "• Collected: for notifications from the configured WhatsApp packages whose conversation title matches an enabled group, the message text, sender name (if shown) and time. Nothing from other apps is read.\n" +
            "• Not collected: media, attachments, other groups, other apps. Rejected notifications are not stored; only a reason code is logged.\n" +
            "• Stored: only on this phone, in the app's private storage. There is no server, analytics or telemetry. App backup is disabled.\n" +
            "• Sent to Discord: only the messages you select and confirm, only to your webhook. Nothing is ever published automatically.\n" +
            "• Notification access lets an app read notification content; you can revoke it any time in Android settings or switch collection off here."
        const val TROUBLESHOOTING_TEXT = "• Nothing arrives? Check that notification access is Enabled and the group name matches exactly; see \"View diagnostics\".\n" +
            "• WhatsApp must show message previews: Android settings → Notifications → WhatsApp must not hide sensitive content, and WhatsApp notifications must be on for that group (not muted).\n" +
            "• A muted or silenced group posts no notification, so nothing can be collected.\n" +
            "• Battery optimisation: exclude this app from \"battery optimisation / sleeping apps\" so Android keeps the listener alive.\n" +
            "• If the status says \"not connected\", toggle notification access off and on again.\n" +
            "• Android cannot guarantee every notification is delivered; some messages can be missed."
    }
}
