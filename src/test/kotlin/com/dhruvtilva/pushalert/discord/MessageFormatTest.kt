package com.dhruvtilva.pushalert.discord

import com.dhruvtilva.pushalert.domain.MessageStatus
import com.dhruvtilva.pushalert.domain.PendingMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageFormatTest {
    private fun msg(text: String = "JUST IN\n\nTata Trusts") =
        PendingMessage(1, "com.whatsapp", "Rukmani Financial Services 2", null, "~ Vaibhav Agrawal", text, null, 1_760_346_480_000L, "fp", MessageStatus.PENDING, false, null, null, null, 0, 0, false)

    @Test fun separatorMatchesTheRequestedFormat() {
        assertEquals("====== Terminal365 Update #12 ======", MessageFormat.separator("Terminal365 Update", 12))
    }

    @Test fun renderPutsOnlyTheDividerAboveTheUnchangedText() {
        assertEquals("====== Terminal365 Update #3 ======\nJUST IN\n\nTata Trusts", MessageFormat.render(msg(), true, "Terminal365 Update", 3))
    }

    @Test fun noGroupSenderOrTimeIsAdded() {
        val out = MessageFormat.render(msg(), true)
        assertEquals(false, out.contains("Rukmani") || out.contains("Vaibhav"))
    }

    @Test fun dividerCanBeSwitchedOff() {
        assertEquals("JUST IN\n\nTata Trusts", MessageFormat.render(msg(), false))
    }

    @Test fun editedTextIsWhatGetsRendered() {
        assertEquals("====== T #1 ======\nnew", MessageFormat.render(msg().copy(editedText = "new"), true, "T", 1))
    }
}
