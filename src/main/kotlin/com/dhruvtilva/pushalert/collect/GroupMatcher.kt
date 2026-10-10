package com.dhruvtilva.pushalert.collect

import com.dhruvtilva.pushalert.domain.GroupFilter
import com.dhruvtilva.pushalert.domain.TextRules

sealed interface MatchResult {
    data class Matched(val filter: GroupFilter) : MatchResult
    data object Disabled : MatchResult
    data object Unknown : MatchResult
    data object Ambiguous : MatchResult
}

/**
 * Matches the identifiable conversation title against the user's group filters.
 *
 * Comparison is a normalised (trimmed, whitespace-collapsed, Unicode-folded, case-insensitive)
 * *exact* comparison. There is deliberately no substring or fuzzy matching.
 */
object GroupMatcher {
    fun match(candidates: List<String>, filters: List<GroupFilter>): MatchResult {
        val byKey = filters.associateBy { it.normalizedName }
        val keys = candidates.filterNot { NotificationParser.isGenericName(it) }
            .map { TextRules.normalizeKey(it) }
            .distinct()
        val hits = keys.mapNotNull { byKey[it] }.distinctBy { it.id }
        val enabled = hits.filter { it.enabled }
        return when {
            enabled.size > 1 -> MatchResult.Ambiguous
            enabled.size == 1 -> MatchResult.Matched(enabled.first())
            hits.isNotEmpty() -> MatchResult.Disabled
            else -> MatchResult.Unknown
        }
    }

    /** Validates a user-entered group name; returns an error message or null when acceptable. */
    fun validateName(raw: String): String? {
        val name = TextRules.stripInvisible(raw).trim()
        return when {
            name.isEmpty() -> "Enter a group name."
            name.length > TextRules.GROUP_NAME_MAX_LENGTH -> "Group name is too long."
            NotificationParser.isGenericName(name) ->
                "\"$name\" is too generic; use the exact name of the WhatsApp group."
            else -> null
        }
    }
}
