package com.dhruvtilva.pushalert.discord

import com.dhruvtilva.pushalert.domain.DividerStyle
import com.dhruvtilva.pushalert.domain.MessageStatus
import com.dhruvtilva.pushalert.domain.PendingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MessageFormatTest {
    private val text = "JUST IN\n\nTata Trusts\n\n\u2022 bullet"
    private fun msg(t: String = text) =
        PendingMessage(1, "com.whatsapp", "Rukmani Financial Services 2", null, "~ Vaibhav Agrawal", t, null, 1_760_346_480_000L, "fp", MessageStatus.PENDING, false, null, null, null, 0, 0, false)

    @Test fun headingStyleUsesDiscordHeadingMarkdown() {
        assertEquals("### Terminal365 Update #12", MessageFormat.divider(DividerStyle.HEADING, "Terminal365 Update", 12))
        assertEquals("### Terminal365 Update #3\n\n$text", MessageFormat.render(msg(), DividerStyle.HEADING, "Terminal365 Update", 3))
    }

    @Test fun smallStyleUsesDiscordSubtext() {
        assertEquals("-# Terminal365 Update #12", MessageFormat.divider(DividerStyle.SMALL, "Terminal365 Update", 12))
    }

    @Test fun lineStyleHasARuleAndBoldTitle() {
        assertEquals("\u2501".repeat(18) + "\n**Terminal365 Update #12**", MessageFormat.divider(DividerStyle.LINE, "Terminal365 Update", 12))
    }

    @Test fun noneAddsNothing() {
        assertNull(MessageFormat.divider(DividerStyle.NONE, "T", 1))
        assertEquals(text, MessageFormat.render(msg(), DividerStyle.NONE))
    }

    @Test fun theReceivedTextIsNeverChangedOrSplit() {
        for (style in DividerStyle.values()) {
            val out = MessageFormat.render(msg(), style, "T", 5)
            assertEquals(style.name, true, out.endsWith(text))
        }
    }

    @Test fun noGroupSenderOrTimeIsAddedAndNoEqualsRuns() {
        for (style in DividerStyle.values()) {
            val out = MessageFormat.render(msg(), style, "T", 1)
            assertFalse(out.contains("Rukmani") || out.contains("Vaibhav") || out.contains("======"))
        }
    }

    @Test fun editedTextIsWhatGetsRendered() {
        assertEquals("### T #1\n\nnew", MessageFormat.render(msg().copy(editedText = "new"), DividerStyle.HEADING, "T", 1))
    }
}
