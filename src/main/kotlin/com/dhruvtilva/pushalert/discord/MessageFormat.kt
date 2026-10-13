package com.dhruvtilva.pushalert.discord

import com.dhruvtilva.pushalert.domain.PendingMessage

/**
 * Builds the text actually posted to Discord. Discord merges consecutive posts from the same sender under
 * one avatar/name header, so by default each message starts with a visible divider line such as
 * `====== Terminal365 Update #12 ======` to keep every message clearly separate.
 */
object MessageFormat {
    /** The divider line, e.g. `====== Terminal365 Update #12 ======`. */
    fun separator(title: String, number: Long): String = "====== $title #$number ======"

    /** divider line, then the message text (nothing else is added). */
    fun render(m: PendingMessage, withHeader: Boolean, title: String = "Terminal365 Update", number: Long = 1): String =
        if (withHeader) separator(title, number) + "\n" + m.currentText else m.currentText
}
