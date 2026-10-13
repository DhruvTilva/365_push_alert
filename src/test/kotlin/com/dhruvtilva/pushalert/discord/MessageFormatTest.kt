package com.dhruvtilva.pushalert.discord

import com.dhruvtilva.pushalert.domain.MessageStatus
import com.dhruvtilva.pushalert.domain.PendingMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class MessageFormatTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun msg(group: String = "Rukmani Financial Services 2", sender: String? = "~ Vaibhav Agrawal", text: String = "JUST IN\n\nTata Trusts") =
        PendingMessage(1, "com.whatsapp", group, null, sender, text, null, 1_760_346_480_000L, "fp", MessageStatus.PENDING, false, null, null, null, 0, 0, false)

    @Test fun headerHasBoldGroupSenderAndTime() {
        assertEquals("**Rukmani Financial Services 2** \u00b7 \\~ Vaibhav Agrawal \u00b7 13 Oct, 09:08", MessageFormat.header(msg(), utc))
    }

    @Test fun renderPutsTheHeaderAboveTheUnchangedText() {
        assertEquals(
            "====== Terminal365 Update #3 ======\n" + MessageFormat.header(msg(), utc) + "\nJUST IN\n\nTata Trusts",
            MessageFormat.render(msg(), true, "Terminal365 Update", 3, utc),
        )
        assertEquals("JUST IN\n\nTata Trusts", MessageFormat.render(msg(), false, zone = utc))
    }

    @Test fun senderIsOmittedWhenUnknownAndMarkdownIsEscaped() {
        assertEquals("**A\\_B \\*C\\*** \u00b7 13 Oct, 09:08", MessageFormat.header(msg(group = "A_B *C*", sender = null), utc))
    }

    @Test fun editedTextIsWhatGetsRendered() {
        assertEquals("====== T #1 ======\n**G** \u00b7 13 Oct, 09:08\nnew", MessageFormat.render(msg(group = "G", sender = null).copy(editedText = "new"), true, "T", 1, utc))
    }
}
