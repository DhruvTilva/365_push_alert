package com.dhruvtilva.pushalert

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Local-only crash log. An uncaught exception is written to a private file; the next launch shows it
 * on screen (with a Copy button) so a startup crash is diagnosable instead of the app silently closing.
 * Nothing is sent anywhere.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    private const val MAX_CHARS = 8000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(app, error, thread.name)
            } catch (_: Throwable) {
                // never let the reporter itself hide the original crash
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(context: Context, error: Throwable, threadName: String = "main") {
        val text = buildString {
            append("365 Push Alert crash report\n")
            append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Thread: ").append(threadName).append("\n\n")
            append(Log.getStackTraceString(error))
        }
        File(context.filesDir, FILE).writeText(text.take(MAX_CHARS))
    }

    fun readLast(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
