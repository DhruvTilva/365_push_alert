package com.dhruvtilva.pushalert.discord

import com.dhruvtilva.pushalert.domain.DividerStyle
import com.dhruvtilva.pushalert.domain.PendingMessage

/**
 * Builds the text actually posted to Discord. Discord merges consecutive posts from the same sender under
 * one avatar/name header, so each message starts with a short divider such as `### Terminal365 Update #12`
 * (Discord markdown) to keep every message clearly separate. The received text is never changed or split.
 */
object MessageFormat {
    /** The divider text (without the message), or null for [DividerStyle.NONE]. */
    fun divider(style: DividerStyle, title: String, number: Long): String? = when (style) {
        DividerStyle.HEADING -> "### $title #$number"
        DividerStyle.SMALL -> "-# $title #$number"
        DividerStyle.LINE -> "\u2501".repeat(18) + "\n**$title #$number**"
        DividerStyle.NONE -> null
    }

    /** divider, a blank line for breathing room, then the message text exactly as it is. */
    fun render(m: PendingMessage, style: DividerStyle, title: String = "Terminal365 Update", number: Long = 1): String =
        divider(style, title, number)?.let { it + "\n\n" + m.currentText } ?: m.currentText
}
