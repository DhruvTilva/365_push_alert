package com.dhruvtilva.pushalert.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.dhruvtilva.pushalert.PushAlertApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Launches the real Application + Activity on Robolectric's real framework code (real SQLite, real Views). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchSmokeTest {

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun settle() {
        // Flows run on IO threads and deliver to the main looper; give them time, then drain the looper.
        repeat(20) { Thread.sleep(100); shadowOf(android.os.Looper.getMainLooper()).idle() }
    }

    @Test fun applicationAndMainActivityStartWithoutAnyUncaughtException() {
        val errors = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> errors += e }
        try {
            RuntimeEnvironment.getApplication() as PushAlertApp
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
            settle()
            val all = texts(controller.get().window.decorView)
            assertTrue(all.toString(), all.any { it.startsWith("Pending: 0") })
            assertTrue(all.any { it.contains("Notification access") })
            // Switch to the other tabs (builds/updates their views with real data).
            listOf("Sent", "Settings").forEach { label ->
                findByText(controller.get().window.decorView, label)?.performClick()
                settle()
            }
            val settings = texts(controller.get().window.decorView)
            assertTrue(settings.toString(), settings.any { it.contains("Group filters") })
            controller.pause().stop().destroy()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertEquals("uncaught exceptions: " + errors.joinToString { it.toString() + it.stackTrace.take(6) }, 0, errors.size)
    }

    private fun findByText(v: View, text: String): View? {
        if (v is TextView && v.text.toString() == text && v.isClickable) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findByText(v.getChildAt(i), text)?.let { return it }
        return null
    }

    @Test fun aRecordedCrashIsShownOnScreenAndCanBeCleared() {
        val app = RuntimeEnvironment.getApplication() as PushAlertApp
        com.dhruvtilva.pushalert.CrashReporter.record(app, IllegalStateException("simulated startup failure"))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        val shown = texts(controller.get().window.decorView)
        assertTrue(shown.toString(), shown.any { it.contains("hit an error") })
        assertTrue(shown.toString(), shown.any { it.contains("simulated startup failure") })
        assertTrue(shown.none { it.startsWith("Pending:") })
        com.dhruvtilva.pushalert.CrashReporter.clear(app)
        assertEquals(null, com.dhruvtilva.pushalert.CrashReporter.readLast(app))
    }

    @Test fun uncaughtExceptionHandlerWritesTheReport() {
        val app = RuntimeEnvironment.getApplication() as PushAlertApp
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
            com.dhruvtilva.pushalert.CrashReporter.install(app)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("boom-xyz"))
            assertTrue(com.dhruvtilva.pushalert.CrashReporter.readLast(app)!!.contains("boom-xyz"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            com.dhruvtilva.pushalert.CrashReporter.clear(app)
        }
    }
}
