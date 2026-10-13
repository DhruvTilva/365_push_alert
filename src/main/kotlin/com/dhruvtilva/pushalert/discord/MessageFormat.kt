package com.dhruvtilva.pushalert.discord

import com.dhruvtilva.pushalert.domain.PendingMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the text actually posted to Discord. Discord merges consecutive posts from the same sender under
 * one avatar/name header, so each message starts with a user-defined divider (default `### Terminal365 Update #12`
 * with an emoji, in Discord markdown) followed by a blank line. The received text is never changed or split.
 *
 * Divider placeholders: `{n}` running number, `{group}`, `{sender}`, `{date}` (13 Oct), `{time}` (09:08).
 */
object MessageFormat {
    fun divider(template: String, m: PendingMessage, number: Long, zone: TimeZone = TimeZone.getDefault()): String {
        if (template.isBlank()) return ""
        fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.ENGLISH).apply { timeZone = zone }.format(Date(m.receivedAt))
        return template
            .replace("{n}", number.toString())
            .replace("{group}", m.groupName)
            .replace("{sender}", m.senderName.orEmpty())
            .replace("{date}", fmt("d MMM"))
            .replace("{time}", fmt("HH:mm"))
            .trim()
    }

    /** divider, a blank line for breathing room, then the message text exactly as it is. */
    fun render(m: PendingMessage, template: String, number: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val d = divider(template, m, number, zone)
        return if (d.isEmpty()) m.currentText else d + "\n\n" + m.currentText
    }
}
