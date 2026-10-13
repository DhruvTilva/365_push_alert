package com.dhruvtilva.pushalert.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRulesTest {
    @Test fun cleanMessageKeepsMeaningfulNewlines() {
        assertEquals("line1\nline2", TextRules.cleanMessage("  line1  \r\nline2‎ \n"))
    }

    @Test fun cleanMessageCollapsesBlankLineRuns() {
        assertEquals("a\n\nb", TextRules.cleanMessage("a\n\n\n\n\nb"))
    }

    @Test fun normalizeKeyIgnoresCaseSpacesAndInvisibleMarks() {
        assertEquals("ai/ml updates", TextRules.normalizeKey("  ‎AI/ML   Updates "))
    }

    @Test fun validateRejectsBlank() {
        assertEquals(ValidationResult.Empty, TextRules.validate("   \n "))
    }

    @Test fun validateLimitIsInclusive() {
        assertTrue(TextRules.validate("a".repeat(2000)).isValid)
        val tooLong = TextRules.validate("a".repeat(2001))
        assertTrue(tooLong is ValidationResult.TooLong && tooLong.length == 2001)
    }

    @Test fun lengthCountsCodePointsNotUtf16Units() {
        val emoji = "😀".repeat(1500) // 1500 characters, 3000 UTF-16 units
        assertEquals(1500, TextRules.length(emoji))
        assertTrue(TextRules.validate(emoji).isValid)
    }

    @Test fun splitParagraphsSeparatesBlocksAtBlankLines() {
        assertEquals(listOf("a\nb", "c", "d"), TextRules.splitParagraphs("a\nb\n\nc\n \n\n\nd\n"))
        assertEquals(listOf("single\nline pair"), TextRules.splitParagraphs("single\nline pair"))
        assertEquals(emptyList<String>(), TextRules.splitParagraphs(" \n\n "))
    }
}
