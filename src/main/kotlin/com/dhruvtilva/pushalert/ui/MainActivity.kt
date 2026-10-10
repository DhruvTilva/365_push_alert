package com.dhruvtilva.pushalert.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dhruvtilva.pushalert.PushAlertApp
import com.dhruvtilva.pushalert.collect.PushAlertListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : Activity() {
    private var scope: CoroutineScope? = null
    private lateinit var screens: List<Screen>
    private lateinit var tabs: List<TextView>
    private lateinit var content: FrameLayout
    private lateinit var ui: Ui
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        screens.forEach { it.start(s) }
        PushAlertListenerService.requestRebindIfNeeded(this)
    }

    override fun onResume() {
        super.onResume()
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
        outState.putInt(KEY_TAB, current)
    }

    private companion object {
        const val KEY_TAB = "tab"
    }
}
