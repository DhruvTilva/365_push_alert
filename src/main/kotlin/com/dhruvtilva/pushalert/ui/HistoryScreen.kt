package com.dhruvtilva.pushalert.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import com.dhruvtilva.pushalert.AppGraph
import com.dhruvtilva.pushalert.domain.SentRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class HistoryScreen(private val activity: Activity, private val graph: AppGraph, private val ui: Ui) : Screen {
    private val p = ui.p
    private var scope: CoroutineScope? = null
    private var items: List<SentRecord> = emptyList()

    private val note = ui.sub("")
    private val listView = ListView(activity)
    private val empty = ui.text("No published messages yet.", 15f, p.subtext).apply {
        gravity = android.view.Gravity.CENTER
        setPadding(ui.dp(24), ui.dp(32), ui.dp(24), 0)
    }
    private val adapter = Adapter()

    override val view: View

    init {
        val root = ui.vbox().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), 0) }
        val header = ui.hbox()
        header.addView(ui.text("Sent History", 18f, p.text, true), ui.lp(w = 0, weight = 1f))
        header.addView(ui.button("Clear all") { confirmClear() })
        root.addView(header)
        root.addView(note, ui.lp(bottom = 6))
        listView.adapter = adapter
        listView.divider = null
        val frame = FrameLayout(activity)
        frame.addView(listView)
        frame.addView(empty, FrameLayout.LayoutParams(-1, -2))
        listView.emptyView = empty
        root.addView(frame, ui.lp(h = 0, weight = 1f))
        view = root
    }

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch { graph.store.observeHistory().collect { items = it; adapter.notifyDataSetChanged() } }
        scope.launch {
            graph.settings.settings.collect {
                note.text = if (it.historyEnabled) "Messages you published are kept here until you delete them."
                else "Sent History is switched off: published messages are deleted immediately."
            }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(activity)
            .setTitle("Clear Sent History?")
            .setMessage("This deletes the local record of published messages. It does not affect Discord.")
            .setPositiveButton("Clear") { _, _ -> scope?.launch { graph.store.clearHistory() } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val r = items[position]
            val card = ui.card()
            card.addView(ui.text(r.groupName, 14f, p.primary, true))
            val who = r.senderName?.let { "$it · " } ?: ""
            card.addView(ui.sub("${who}received ${ui.formatTime(r.receivedAt)} · published ${ui.formatTime(r.sentAt)}"))
            card.addView(ui.text(r.publishedText, 15f).apply { maxLines = 8; ellipsize = android.text.TextUtils.TruncateAt.END }, ui.lp(top = 2))
            if (r.deliveryStatus == SentRecord.STATUS_CONFIRMED_BY_USER) {
                card.addView(ui.text("Delivery confirmed manually by you", 13f, p.warn, true), ui.lp(top = 4))
            }
            card.addView(ui.button("Delete") { scope?.launch { graph.store.deleteHistory(r.id) } }, ui.lp(top = 4))
            val frame = FrameLayout(activity)
            frame.setPadding(0, 0, 0, ui.dp(8))
            frame.addView(card, FrameLayout.LayoutParams(-1, -2))
            return frame
        }
    }
}
