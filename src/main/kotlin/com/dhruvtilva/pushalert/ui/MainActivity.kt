package com.dhruvtilva.pushalert.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dhruvtilva.pushalert.CrashReporter
import com.dhruvtilva.pushalert.PushAlertApp
import com.dhruvtilva.pushalert.collect.PushAlertListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : Activity() {
    private var scope: CoroutineScope? = null
    private var screens: List<Screen> = emptyList()
    private lateinit var tabs: List<TextView>
    private lateinit var content: FrameLayout
    private lateinit var ui: Ui
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previousCrash = CrashReporter.readLast(this)
        if (previousCrash != null) {
            showCrashReport(previousCrash)
            return
        }
        try {
            buildUi(savedInstanceState)
        } catch (t: Throwable) {
            CrashReporter.record(this, t)
            showCrashReport(CrashReporter.readLast(this) ?: t.toString())
        }
    }

    /** Shown instead of the app when the previous run (or this start-up) crashed, so the error is visible. */
    private fun showCrashReport(report: String) {
        screens = emptyList()
        val p = Palette.of(this)
        val ui = Ui(this, p)
        val col = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); setBackgroundColor(p.bg) }
        col.addView(ui.text("365 Push Alert hit an error", 20f, p.danger, true))
        col.addView(ui.text("Tap Copy and send the text below to the developer. Nothing was sent anywhere automatically.", 14f, p.text), ui.lp(top = 6, bottom = 8))
        val row = ui.hbox()
        row.addView(ui.button("Copy report", filled = true) {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("crash report", report))
            android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show()
        }, ui.lp(w = 0, weight = 1f, end = 4))
        row.addView(ui.button("Clear and retry") {
            CrashReporter.clear(this)
            recreate()
        }, ui.lp(w = 0, weight = 1f, start = 4))
        col.addView(row)
        val body = ui.text(report, 12f, p.text).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        col.addView(body, ui.lp(top = 10))
        setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(p.bg); addView(col) })
    }

    private fun buildUi(savedInstanceState: Bundle?) {
        val graph = (application as PushAlertApp).graph
        ui = Ui(this, Palette.of(this))
        val p = ui.p
        current = savedInstanceState?.getInt(KEY_TAB) ?: 0

        screens = listOf(InboxScreen(this, graph, ui), HistoryScreen(this, graph, ui), SettingsScreen(this, graph, ui))

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        val header = ui.text("365 Push Alert", 20f, p.onPrimary, true).apply {
            setBackgroundColor(p.primary)
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
        }
        root.addView(header, ViewGroup.LayoutParams(-1, -2))
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(p.surface) }
        val labels = listOf("Inbox", "Sent", "Settings")
        tabs = labels.mapIndexed { i, label ->
            ui.text(label, 15f, p.subtext, true).apply {
                gravity = Gravity.CENTER
                minHeight = ui.dp(52)
                setOnClickListener { select(i) }
            }.also { nav.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        root.addView(ui.divider())
        root.addView(nav, ViewGroup.LayoutParams(-1, -2))
        setContentView(root)
        select(current)
    }

    private fun select(index: Int) {
        current = index
        content.removeAllViews()
        content.addView(screens[index].view, FrameLayout.LayoutParams(-1, -1))
        tabs.forEachIndexed { i, t ->
            t.setTextColor(if (i == index) ui.p.primary else ui.p.subtext)
            t.setBackgroundColor(if (i == index) ui.p.chip else ui.p.surface)
        }
    }

    override fun onStart() {
        super.onStart()
        if (screens.isEmpty()) return
        val s = CoroutineScope(SupervisorJob() + UiDispatcher)
        scope = s
        screens.forEach { it.start(s) }
        PushAlertListenerService.requestRebindIfNeeded(this)
    }

    override fun onResume() {
        super.onResume()
        if (screens.isEmpty()) return
        // The user may have just come back from Android's notification-access screen.
        (screens[0] as InboxScreen).refreshAccess()
        (screens[2] as SettingsScreen).refreshAccess()
    }

    override fun onStop() {
        scope?.cancel()
        scope = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (screens.isNotEmpty()) outState.putInt(KEY_TAB, current)
    }

    private companion object {
        const val KEY_TAB = "tab"
    }
}
