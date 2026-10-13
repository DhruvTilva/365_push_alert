package com.dhruvtilva.pushalert.discord

import com.dhruvtilva.pushalert.domain.AppSettings
import com.dhruvtilva.pushalert.domain.MessageStatus
import com.dhruvtilva.pushalert.domain.PendingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.TimeZone

class MessageFormatTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val text = "JUST IN\n\nTata Trusts\n\n\u2022 bullet"
    private fun msg(t: String = text) =
        PendingMessage(1, "com.whatsapp", "Rukmani Financial Services 2", null, "~ Vaibhav Agrawal", t, null, 1_760_346_480_000L, "fp", MessageStatus.PENDING, false, null, null, null, 0, 0, false)

    @Test fun defaultDividerIsAHeadingWithEmojiAndNumber() {
        assertEquals("### \uD83D\uDCE2 Terminal365 Update #12", MessageFormat.divider(AppSettings.DEFAULT_DIVIDER, msg(), 12, utc))
    }

    @Test fun renderPutsDividerThenBlankLineThenTheUntouchedText() {
        assertEquals("### T #3\n\n$text", MessageFormat.render(msg(), "### T #{n}", 3, utc))
    }

    @Test fun anyUserTemplateIsUsedAsIs() {
        assertEquals("\uD83D\uDEA8\uD83D\uDEA8 BREAKING \u2022 No. 5 \uD83D\uDEA8\uD83D\uDEA8", MessageFormat.divider("\uD83D\uDEA8\uD83D\uDEA8 BREAKING \u2022 No. {n} \uD83D\uDEA8\uD83D\uDEA8", msg(), 5, utc))
        assertEquals("-# small #1", MessageFormat.divider("-# small #{n}", msg(), 1, utc))
    }

    @Test fun multiLineTemplatesWork() {
        assertEquals("\u2501\u2501\u2501\n**Update 2**\n\n$text", MessageFormat.render(msg(), "\u2501\u2501\u2501\n**Update {n}**", 2, utc))
    }

    @Test fun placeholdersForGroupSenderDateAndTime() {
        assertEquals(
            "Rukmani Financial Services 2 | ~ Vaibhav Agrawal | 13 Oct | 09:08 | #4",
            MessageFormat.divider("{group} | {sender} | {date} | {time} | #{n}", msg(), 4, utc),
        )
    }

    @Test fun blankTemplateAddsNothing() {
        assertEquals(text, MessageFormat.render(msg(), "", 1, utc))
        assertEquals(text, MessageFormat.render(msg(), "  \n ", 1, utc))
    }

    @Test fun theReceivedTextIsNeverChangedOrSplit() {
        for (tpl in listOf(AppSettings.DEFAULT_DIVIDER, "x", "", "a\n\nb")) {
            assertEquals(tpl, true, MessageFormat.render(msg(), tpl, 5, utc).endsWith(text))
        }
    }

    @Test fun defaultAddsNoGroupSenderTimeOrEqualsRuns() {
        val out = MessageFormat.render(msg(), AppSettings.DEFAULT_DIVIDER, 1, utc)
        assertFalse(out.contains("Rukmani") || out.contains("Vaibhav") || out.contains("======"))
    }

    @Test fun editedTextIsWhatGetsRendered() {
        assertEquals("### T #1\n\nnew", MessageFormat.render(msg().copy(editedText = "new"), "### T #{n}", 1, utc))
    }

    @Test fun extraEntersAtTheEndAddExtraBlankLines() {
        assertEquals("### T #1\n\n$text", MessageFormat.render(msg(), "### T #{n}", 1, utc))
        assertEquals("### T #1\n\n\n$text", MessageFormat.render(msg(), "### T #{n}\n", 1, utc))
        assertEquals("### T #1\n\n\n\n$text", MessageFormat.render(msg(), "### T #{n}\n\n", 1, utc))
        assertEquals(0, MessageFormat.extraBlankLines("abc"))
        assertEquals(2, MessageFormat.extraBlankLines("abc \n \n"))
        assertEquals(5, MessageFormat.extraBlankLines("abc" + "\n".repeat(20)))
    }

    @Test fun enterInTheMiddleIsAnOrdinaryLineBreakAndNoExtraSpace() {
        assertEquals("A\nB\n\n$text", MessageFormat.render(msg(), "A\nB", 1, utc))
    }
}
