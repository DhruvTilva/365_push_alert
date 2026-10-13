package com.dhruvtilva.pushalert.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.dhruvtilva.pushalert.AppGraph
import com.dhruvtilva.pushalert.collect.PushAlertListenerService
import com.dhruvtilva.pushalert.data.EditResult
import com.dhruvtilva.pushalert.discord.BatchProgress
import com.dhruvtilva.pushalert.discord.StartResult
import com.dhruvtilva.pushalert.domain.GroupFilter
import com.dhruvtilva.pushalert.domain.MessageStatus
import com.dhruvtilva.pushalert.domain.PendingMessage
import com.dhruvtilva.pushalert.domain.TextRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class InboxScreen(private val activity: Activity, private val graph: AppGraph, private val ui: Ui) : Screen {
    private val p = ui.p
    private var scope: CoroutineScope? = null

    private var all: List<PendingMessage> = emptyList()
    private var visible: List<PendingMessage> = emptyList()
    private var groups: List<GroupFilter> = emptyList()
    private var progress = BatchProgress()
    private var accessText = ""

    private val accessBanner = ui.text("", 14f, p.text)
    private val accessButton = ui.button("Open notification access settings") {
        activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    private val restrictedButton = ui.button("Step 1: Allow restricted settings (App info)") {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${activity.packageName}")),
        )
    }
    private val restrictedHelp = ui.text(
        "If the notification-access switch is greyed out (\"Controlled by restricted setting\"): tap the greyed switch once, press OK, " +
            "then open App info here → ⋮ (top right) → Allow restricted settings → confirm. Then use the button above to enable access.",
        13f, p.warn,
    )
    private val hint = ui.text("", 13f, p.warn)
    private val summary = ui.text("", 15f, p.text, true)
    private val search = ui.input("Search messages").apply { setText(graph.inboxSearch) }
    private val groupSpinner = Spinner(activity)
    private val pushButton: Button
    private val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
    private val progressText = ui.text("", 14f, p.text)
    private val cancelButton: Button
    private val dismissButton: Button
    private val progressCard = ui.card()
    private val listView = ListView(activity)
    private val empty = ui.text("", 15f, p.subtext).apply { gravity = android.view.Gravity.CENTER; setPadding(ui.dp(24), ui.dp(32), ui.dp(24), 0) }
    private val adapter = MessageAdapter()
    private var groupChoices: List<String> = listOf(ALL_GROUPS)
    private var suppressSpinner = false

    override val view: View

    init {
        val root = ui.vbox().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), 0) }

        val accessCard = ui.card().apply {
            addView(accessBanner)
            addView(accessButton, ui.lp(top = 6))
            addView(restrictedButton, ui.lp(top = 6))
            addView(restrictedHelp, ui.lp(top = 4))
            addView(hint, ui.lp(top = 4))
        }
        root.addView(accessCard, ui.lp(bottom = 8))
        root.addView(summary, ui.lp(bottom = 4))

        val filterRow = ui.hbox()
        filterRow.addView(search, ui.lp(w = 0, weight = 1f))
        filterRow.addView(groupSpinner, ui.lp(w = ui.dp(150)))
        root.addView(filterRow)

        val selectRow = ui.hbox()
        selectRow.addView(ui.button("Select all") { selectAllVisible() }, ui.lp(w = 0, weight = 1f, end = 4))
        selectRow.addView(ui.button("Deselect all") { deselectAll() }, ui.lp(w = 0, weight = 1f, start = 4))
        root.addView(selectRow, ui.lp(top = 6))

        pushButton = ui.button("Push Selected to Discord", filled = true) { onPush() }
        root.addView(pushButton, ui.lp(top = 6, bottom = 6))

        cancelButton = ui.button("Cancel after current") { graph.publisher.cancel() }
        dismissButton = ui.button("Dismiss") { graph.publisher.dismiss() }
        progressBar.max = 100
        progressCard.addView(progressText)
        progressCard.addView(progressBar, ui.lp(top = 6))
        val progressButtons = ui.hbox()
        progressButtons.addView(cancelButton, ui.lp(w = 0, weight = 1f, end = 4))
        progressButtons.addView(dismissButton, ui.lp(w = 0, weight = 1f, start = 4))
        progressCard.addView(progressButtons, ui.lp(top = 6))
        progressCard.visibility = View.GONE
        root.addView(progressCard, ui.lp(bottom = 6))

        listView.adapter = adapter
        listView.divider = null
        listView.isFocusable = false
        listView.emptyView = empty
        val listFrame = android.widget.FrameLayout(activity)
        listFrame.addView(listView)
        listFrame.addView(empty, android.widget.FrameLayout.LayoutParams(-1, -2))
        root.addView(listFrame, ui.lp(h = 0, weight = 1f))
        view = root

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                graph.inboxSearch = s?.toString().orEmpty()
                applyFilter()
            }
        })
        groupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressSpinner) return
                graph.inboxGroupFilter = groupChoices.getOrNull(pos)?.takeIf { it != ALL_GROUPS }
                applyFilter()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        refreshAccess()
        scope.launch {
            combine(graph.store.observePending(), graph.store.observeGroups()) { m, g -> m to g }.collect { (m, g) ->
                all = m
                groups = g
                rebuildGroupSpinner()
                applyFilter()
            }
        }
        scope.launch { graph.publisher.progress.collect { progress = it; renderProgress() } }
        scope.launch { PushAlertListenerService.connected.collect { refreshAccess() } }
        scope.launch { graph.settings.settings.collect { refreshAccess() } }
    }

    fun refreshAccess() {
        val granted = PushAlertListenerService.isAccessGranted(activity)
        val connected = PushAlertListenerService.connected.value
        val (text, color) = when {
            !granted -> "Notification access: DISABLED – nothing is being collected." to p.danger
            !graph.settings.settings.value.collectionEnabled -> "Notification access: enabled, but collection is switched off in Settings." to p.warn
            connected -> "Notification access: Enabled and connected." to p.ok
            else -> "Notification access: enabled in Android, waiting for the listener to connect." to p.warn
        }
        accessText = text
        accessBanner.text = text
        accessBanner.setTextColor(color)
        accessButton.visibility = if (granted) View.GONE else View.VISIBLE
        val showRestrictedHelp = !granted && android.os.Build.VERSION.SDK_INT >= 33
        restrictedButton.visibility = if (showRestrictedHelp) View.VISIBLE else View.GONE
        restrictedHelp.visibility = if (showRestrictedHelp) View.VISIBLE else View.GONE
        if (granted && !connected) PushAlertListenerService.requestRebindIfNeeded(activity)
        renderHint()
    }

    private fun renderHint() {
        val noEnabledGroups = groups.none { it.enabled }
        hint.text = if (noEnabledGroups) "No WhatsApp group is enabled, so nothing will be collected. Add group names in Settings." else ""
        hint.visibility = if (noEnabledGroups) View.VISIBLE else View.GONE
    }

    private fun rebuildGroupSpinner() {
        val names = (groups.map { it.groupName } + all.map { it.groupName }).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val choices = listOf(ALL_GROUPS) + names
        if (choices == groupChoices) return
        groupChoices = choices
        suppressSpinner = true
        groupSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, choices)
        val sel = choices.indexOf(graph.inboxGroupFilter).takeIf { it >= 0 } ?: 0
        groupSpinner.setSelection(sel)
        if (sel == 0) graph.inboxGroupFilter = null
        groupSpinner.post { suppressSpinner = false }
    }

    private fun applyFilter() {
        val q = graph.inboxSearch.trim().lowercase()
        val g = graph.inboxGroupFilter
        visible = all.filter { m ->
            (g == null || m.groupName == g) &&
                (q.isEmpty() || m.currentText.lowercase().contains(q) || m.groupName.lowercase().contains(q) ||
                    (m.senderName?.lowercase()?.contains(q) == true))
        }
        val selected = all.count { it.selected }
        summary.text = "Pending: ${all.size}   ·   Selected: $selected" + if (visible.size != all.size) "   ·   Showing: ${visible.size}" else ""
        pushButton.text = if (selected > 0) "Push Selected to Discord ($selected)" else "Push Selected to Discord"
        empty.text = if (all.isEmpty()) "No collected messages yet.\nEnable notification access and add your WhatsApp group names in Settings; matching text messages will appear here."
        else "No messages match the current search / group filter."
        adapter.notifyDataSetChanged()
        renderProgress()
    }

    private fun renderProgress() {
        val running = progress.state == BatchProgress.State.RUNNING
        pushButton.isEnabled = !running
        progressCard.visibility = if (progress.state == BatchProgress.State.IDLE) View.GONE else View.VISIBLE
        cancelButton.visibility = if (running) View.VISIBLE else View.GONE
        cancelButton.isEnabled = running && !progress.cancelRequested
        dismissButton.visibility = if (running) View.GONE else View.VISIBLE
        progressBar.progress = if (progress.total == 0) 0 else progress.processed * 100 / progress.total
        progressText.text = if (running) {
            "Publishing ${progress.current} of ${progress.total}\nPublished ${progress.sent} · Failed ${progress.failed} · Uncertain ${progress.uncertain} · Remaining ${progress.remaining}" +
                if (progress.cancelRequested) "\nCancelling after the current message…" else ""
        } else {
            "Finished: published ${progress.sent}, failed ${progress.failed}, uncertain ${progress.uncertain}, not attempted ${progress.remaining}." +
                (progress.stopReason?.let { "\n$it" } ?: "")
        }
    }

    // ------------------------------------------------------------ actions

    private fun selectAllVisible() {
        val ids = visible.filter { it.status != MessageStatus.SENDING }.map { it.id }
        scope?.launch { graph.store.setSelected(ids, true) }
    }

    private fun deselectAll() {
        scope?.launch { graph.store.setAllSelected(false) }
    }

    private fun onPush() {
        val sc = scope ?: return
        if (progress.state == BatchProgress.State.RUNNING) return
        sc.launch {
            val ids = graph.store.pendingMessages().filter { it.selected && it.status.isSendable }.map { it.id }
            if (ids.isEmpty()) { toast("Select at least one message first."); return@launch }
            val pre = graph.publisher.preflight(ids)
            if (!pre.webhookConfigured) {
                alert("Discord webhook not configured", "Open Settings → Discord, paste your webhook URL and save it first.")
                return@launch
            }
            if (pre.invalid.isNotEmpty()) {
                val lines = pre.invalid.joinToString("\n") { (m, v) -> "• ${m.groupName}: ${v.userMessage()}" }
                alert("Fix these messages first", "Edit or deselect:\n$lines")
                return@launch
            }
            confirmPublish(pre.messages, pre.uncertain.size)
        }
    }

    private fun confirmPublish(messages: List<PendingMessage>, uncertain: Int) {
        val sb = StringBuilder("These ${messages.size} message(s) will be posted to Discord, one after another, in this order:\n\n")
        messages.take(30).forEachIndexed { i, m ->
            val t = m.currentText.replace('\n', ' ')
            sb.append("${i + 1}. [${m.groupName}] ${if (t.length > 120) t.take(120) + "…" else t}\n")
        }
        if (messages.size > 30) sb.append("… and ${messages.size - 30} more\n")
        if (uncertain > 0) {
            sb.append("\n⚠ $uncertain of these had an uncertain earlier delivery. If the earlier attempt actually arrived, sending again will create a duplicate on Discord.")
        }
        val tv = ui.text(sb.toString(), 14f)
        tv.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        val sv = ScrollView(activity).apply { addView(tv) }
        AlertDialog.Builder(activity)
            .setTitle("Publish ${messages.size} message(s)?")
            .setView(sv)
            .setPositiveButton("Send") { _, _ ->
                when (graph.publisher.start(messages.map { it.id })) {
                    is StartResult.Started -> Unit
                    StartResult.AlreadyRunning -> toast("A batch is already running.")
                    StartResult.NoWebhook -> toast("Webhook is not configured.")
                    StartResult.NothingToSend -> toast("Nothing to send.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditDialog(m: PendingMessage) {
        val box = ui.vbox().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val original = ui.sub("Original notification text:\n${m.originalText}").apply { maxLines = 6; ellipsize = android.text.TextUtils.TruncateAt.END }
        val edit = ui.input("Message", multiline = true).apply { setText(m.currentText) }
        val counter = ui.sub("")
        val editedFlag = ui.text("", 13f, p.warn, true)
        box.addView(original)
        box.addView(edit, ui.lp(top = 8))
        box.addView(counter)
        box.addView(editedFlag)
        val builder = AlertDialog.Builder(activity)
            .setTitle("Edit message")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
        if (m.isEdited) builder.setNeutralButton("Restore original", null)
        val dialog = builder.create()
        dialog.show()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

        fun refresh() {
            val text = TextRules.cleanMessage(edit.text.toString())
            val v = TextRules.validate(text)
            counter.text = "${TextRules.length(text)} / ${TextRules.DISCORD_MAX_LENGTH}" + (v.userMessage()?.let { "  –  $it" } ?: "")
            counter.setTextColor(if (v.isValid) p.subtext else p.danger)
            editedFlag.text = if (text != m.originalText) "Edited – differs from the original notification text" else "Not edited"
            editedFlag.setTextColor(if (text != m.originalText) p.warn else p.subtext)
            save.isEnabled = v.isValid
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        refresh()
        save.setOnClickListener {
            scope?.launch {
                when (val r = graph.store.editText(m.id, edit.text.toString())) {
                    EditResult.Saved -> dialog.dismiss()
                    EditResult.Locked -> { toast("This message can no longer be edited."); dialog.dismiss() }
                    EditResult.NotFound -> { toast("This message no longer exists."); dialog.dismiss() }
                    is EditResult.Invalid -> toast(r.validation.userMessage() ?: "Invalid message.")
                }
            }
        }
        if (m.isEdited) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                scope?.launch { graph.store.restoreOriginal(m.id); dialog.dismiss() }
            }
        }
    }

    private fun splitMessage(m: PendingMessage) {
        scope?.launch {
            val n = graph.store.splitMessage(m.id)
            toast(if (n > 1) "Split into $n separate messages." else "Nothing to split.")
        }
    }

    private fun confirmDelete(m: PendingMessage) {
        AlertDialog.Builder(activity)
            .setTitle("Delete message?")
            .setMessage("It will be removed from the inbox and will not be published.")
            .setPositiveButton("Delete") { _, _ -> scope?.launch { graph.store.deleteMessages(listOf(m.id)) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resolveUncertain(m: PendingMessage) {
        AlertDialog.Builder(activity)
            .setTitle("Delivery uncertain")
            .setMessage("The request may or may not have reached Discord. Check the channel, then tell the app what you found. To send it again anyway, select it and push (this may create a duplicate).")
            .setPositiveButton("It was delivered") { _, _ ->
                scope?.launch { graph.store.confirmDelivered(m.id, graph.settings.settings.value.historyEnabled) }
            }
            .setNeutralButton("It was NOT delivered") { _, _ -> scope?.launch { graph.store.confirmNotDelivered(m.id) } }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun alert(title: String, msg: String) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ adapter

    private inner class MessageAdapter : BaseAdapter() {
        override fun getCount() = visible.size
        override fun getItem(position: Int) = visible[position]
        override fun getItemId(position: Int) = visible[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val m = visible[position]
            val row = ui.card()

            val top = ui.hbox()
            val check = CheckBox(activity).apply {
                isChecked = m.selected
                isEnabled = m.status != MessageStatus.SENDING
                contentDescription = "Select message"
                setOnClickListener { val c = isChecked; scope?.launch { graph.store.setSelected(listOf(m.id), c) } }
            }
            top.addView(check, ui.lp(w = ui.dp(48), h = ui.dp(48)))
            val meta = ui.vbox()
            meta.addView(ui.text(m.groupName, 14f, p.primary, true))
            val who = listOfNotNull(m.senderName).joinToString("")
            meta.addView(ui.sub((if (who.isNotEmpty()) "$who · " else "") + ui.formatTime(m.receivedAt)))
            top.addView(meta, ui.lp(w = 0, weight = 1f))
            row.addView(top)

            val body = ui.text(m.currentText, 15f).apply { maxLines = 6; ellipsize = android.text.TextUtils.TruncateAt.END }
            body.setOnClickListener {
                val c = !m.selected
                scope?.launch { graph.store.setSelected(listOf(m.id), c) }
            }
            row.addView(body, ui.lp(top = 2))

            statusLine(m)?.let { (txt, color) -> row.addView(ui.text(txt, 13f, color, true), ui.lp(top = 4)) }

            val actions = ui.hbox()
            actions.addView(ui.button("Edit") { showEditDialog(m) }, ui.lp(w = 0, weight = 1f, end = 4))
            if ((m.status == MessageStatus.PENDING || m.status == MessageStatus.EDITED) && TextRules.splitParagraphs(m.currentText).size > 1) {
                actions.addView(ui.button("Split") { splitMessage(m) }, ui.lp(w = 0, weight = 1f, start = 4, end = 4))
            }
            if (m.status == MessageStatus.DELIVERY_UNCERTAIN) {
                actions.addView(ui.button("Resolve") { resolveUncertain(m) }, ui.lp(w = 0, weight = 1f, start = 4, end = 4))
            }
            actions.addView(ui.button("Delete") { confirmDelete(m) }, ui.lp(w = 0, weight = 1f, start = 4))
            if (m.status == MessageStatus.SENDING) actions.visibility = View.GONE
            row.addView(actions, ui.lp(top = 4))
            return FrameWrap(row)
        }

        private fun statusLine(m: PendingMessage): Pair<String, Int>? {
            val parts = ArrayList<String>()
            var color = p.subtext
            when (m.status) {
                MessageStatus.PENDING -> {}
                MessageStatus.EDITED -> parts += "Edited"
                MessageStatus.SENDING -> { parts += "Sending…"; color = p.warn }
                MessageStatus.FAILED -> { parts += "Failed" + (m.lastError?.let { ": $it" } ?: ""); color = p.danger }
                MessageStatus.DELIVERY_UNCERTAIN -> { parts += "Delivery uncertain" + (m.lastError?.let { ": $it" } ?: ""); color = p.warn }
                MessageStatus.SENT -> parts += "Sent"
            }
            if (m.status == MessageStatus.FAILED || m.status == MessageStatus.DELIVERY_UNCERTAIN) {
                if (m.isEdited) parts += "Edited"
            }
            if (m.possiblyTruncated) parts += "⚠ may be truncated by WhatsApp"
            return if (parts.isEmpty()) null else parts.joinToString(" · ") to color
        }

        private fun FrameWrap(card: View): View {
            val frame = android.widget.FrameLayout(activity)
            frame.setPadding(0, 0, 0, ui.dp(8))
            frame.addView(card, android.widget.FrameLayout.LayoutParams(-1, -2))
            return frame
        }
    }

    private companion object {
        const val ALL_GROUPS = "All groups"
    }
}
