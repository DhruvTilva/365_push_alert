package com.dhruvtilva.pushalert.discord

import com.dhruvtilva.pushalert.domain.PendingMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the text actually posted to Discord. Discord merges consecutive posts from the same sender under
 * one avatar/name header, so by default each message starts with its own bold header line
 * (group, sender, time) to keep every message visually separate.
 */
object MessageFormat {
    private val MARKDOWN = Regex("([\\\\*_~`])")

    /** Escapes characters that Discord would otherwise render as formatting. */
    fun escape(text: String): String = MARKDOWN.replace(text) { "\\" + it.value }

    fun header(m: PendingMessage, zone: TimeZone = TimeZone.getDefault()): String {
        val time = SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH).apply { timeZone = zone }.format(Date(m.receivedAt))
        val parts = listOfNotNull(
            "**" + escape(m.groupName) + "**",
            m.senderName?.trim()?.takeIf { it.isNotEmpty() }?.let { escape(it) },
            time,
        )
        return parts.joinToString(" \u00B7 ")
    }

    /** The visible divider line, e.g. `====== Terminal365 Update #12 ======`. */
    fun separator(title: String, number: Long): String = "====== $title #$number ======"

    /** divider line, then group/sender/time line, then the message text. */
    fun render(
        m: PendingMessage,
        withHeader: Boolean,
        title: String = "Terminal365 Update",
        number: Long = 1,
        zone: TimeZone = TimeZone.getDefault(),
    ): String =
        if (withHeader) separator(title, number) + "\n" + header(m, zone) + "\n" + m.currentText else m.currentText
}
