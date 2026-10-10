package com.dhruvtilva.pushalert.collect

import com.dhruvtilva.pushalert.domain.GroupFilter
import com.dhruvtilva.pushalert.domain.TextRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GroupMatcherTest {
    private fun filter(id: Long, name: String, enabled: Boolean = true) =
        GroupFilter(id, name, TextRules.normalizeKey(name), enabled, 0, 0, 0)

    private val groups = listOf(
        filter(1, "AI/ML Updates"),
        filter(2, "Project Updates"),
        filter(3, "Research Announcements", enabled = false),
    )

    private fun match(vararg c: String) = GroupMatcher.match(c.toList(), groups)

    @Test fun exactCaseInsensitiveMatch() {
        assertEquals(1L, (match("ai/ml updates") as MatchResult.Matched).filter.id)
        assertEquals(2L, (match("PROJECT UPDATES") as MatchResult.Matched).filter.id)
    }

    @Test fun surroundingAndRepeatedWhitespaceIsIgnored() {
        assertEquals(2L, (match("  Project   Updates \n") as MatchResult.Matched).filter.id)
    }

    @Test fun disabledGroupIsNotMatched() {
        assertEquals(MatchResult.Disabled, match("Research Announcements"))
    }

    @Test fun unknownGroupIsNotMatched() {
        assertEquals(MatchResult.Unknown, match("Family Chat"))
    }

    @Test fun similarButDifferentNamesDoNotMatch() {
        assertEquals(MatchResult.Unknown, match("Project Updates 2"))
        assertEquals(MatchResult.Unknown, match("Project"))
        assertEquals(MatchResult.Unknown, match("Old Project Updates"))
        assertEquals(MatchResult.Unknown, match("AI Updates"))
        assertEquals(MatchResult.Unknown, match("Project Update"))
    }

    @Test fun genericWhatsAppTitleNeverMatches() {
        val withGeneric = groups + filter(9, "WhatsApp")
        assertEquals(MatchResult.Unknown, GroupMatcher.match(listOf("WhatsApp"), withGeneric))
    }

    @Test fun ambiguousMetadataMatchingTwoEnabledGroupsIsRejected() {
        assertEquals(MatchResult.Ambiguous, match("AI/ML Updates", "Project Updates"))
    }

    @Test fun anyCandidateMayMatchWhenOnlyOneGroupDoes() {
        assertEquals(2L, (match("Alice: something", "Project Updates") as MatchResult.Matched).filter.id)
    }

    @Test fun enabledGroupWinsWhenAnotherCandidateIsDisabled() {
        assertNotNull((match("Research Announcements", "Project Updates") as? MatchResult.Matched))
    }

    @Test fun noFiltersMeansNothingMatches() {
        assertEquals(MatchResult.Unknown, GroupMatcher.match(listOf("Project Updates"), emptyList()))
    }

    @Test fun nameValidation() {
        assertNull(GroupMatcher.validateName("AI/ML Updates"))
        assertNotNull(GroupMatcher.validateName("   "))
        assertNotNull(GroupMatcher.validateName("WhatsApp"))
        assertNotNull(GroupMatcher.validateName("5 new messages"))
        assertNotNull(GroupMatcher.validateName("x".repeat(101)))
    }
}
