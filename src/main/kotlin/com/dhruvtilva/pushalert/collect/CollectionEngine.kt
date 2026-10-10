package com.dhruvtilva.pushalert.collect

import com.dhruvtilva.pushalert.data.Store
import com.dhruvtilva.pushalert.domain.AddResult
import com.dhruvtilva.pushalert.domain.AppSettings
import com.dhruvtilva.pushalert.domain.NewMessage
import com.dhruvtilva.pushalert.domain.TextRules
import java.security.MessageDigest

data class CollectionOutcome(
    val added: Int = 0,
    val duplicates: Int = 0,
    val rejected: RejectReason? = null,
    val groupName: String? = null,
)

/**
 * Parser + group filter + de-duplication + persistence.
 *
 * Privacy boundary: nothing reaches the inbox unless the conversation title exactly matches an
 * *enabled* configured group. Rejected notifications leave only a reason code behind — never
 * their text or title.
 */
class CollectionEngine(
    private val store: Store,
    private val settings: () -> AppSettings,
    private val parser: NotificationParser = NotificationParser(),
) {
    suspend fun process(raw: RawNotification): CollectionOutcome {
        val cfg = settings()
        if (raw.packageName !in cfg.whatsappPackages) return CollectionOutcome(rejected = RejectReason.NOT_WHATSAPP)
        if (!cfg.collectionEnabled) return reject(cfg, RejectReason.COLLECTION_DISABLED)

        val parsed = when (val r = parser.parse(raw, cfg.whatsappPackages)) {
            is ParseResult.Rejected -> return reject(cfg, r.reason)
            is ParseResult.Success -> r.notification
        }

        val groups = store.groups()
        val filter = when (val m = GroupMatcher.match(parsed.titleCandidates, groups)) {
            is MatchResult.Matched -> m.filter
            MatchResult.Disabled -> return reject(cfg, RejectReason.GROUP_DISABLED)
            MatchResult.Ambiguous -> return reject(cfg, RejectReason.AMBIGUOUS_GROUP)
            MatchResult.Unknown -> return reject(
                cfg, RejectReason.UNKNOWN_GROUP,
                if (groups.none { it.enabled }) "no groups are enabled yet" else null,
            )
        }
        if (parsed.kind == ConversationKind.INDIVIDUAL && !cfg.allowIndividualChats) {
            return reject(cfg, RejectReason.INDIVIDUAL_CHAT)
        }

        var added = 0
        var duplicates = 0
        for (m in parsed.messages) {
            val timed = m.timeMs != null
            val candidate = NewMessage(
                sourcePackage = parsed.packageName,
                groupName = filter.groupName,
                groupFilterId = filter.id,
                conversationId = parsed.conversationId,
                senderName = m.sender,
                text = m.text,
                receivedAt = m.timeMs ?: parsed.postTime,
                fingerprint = fingerprint(parsed.packageName, filter.normalizedName, m.sender, m.text, m.timeMs),
                timed = timed,
                possiblyTruncated = m.possiblyTruncated,
            )
            when (store.addIfNew(candidate)) {
                is AddResult.Added -> added++
                AddResult.Duplicate -> duplicates++
            }
        }
        if (cfg.diagnosticsEnabled) {
            if (added > 0) {
                store.addDiagnostic(true, "ACCEPTED", "Accepted: conversation title matched an enabled group ($added new, $duplicates duplicate).")
            } else {
                store.addDiagnostic(false, RejectReason.DUPLICATE.code, "Rejected: ${RejectReason.DUPLICATE.description}.")
            }
        }
        return CollectionOutcome(
            added = added,
            duplicates = duplicates,
            rejected = if (added == 0) RejectReason.DUPLICATE else null,
            groupName = filter.groupName,
        )
    }

    private suspend fun reject(cfg: AppSettings, reason: RejectReason, extra: String? = null): CollectionOutcome {
        if (cfg.diagnosticsEnabled) {
            val detail = "Rejected: ${reason.description}" + (extra?.let { " ($it)" } ?: "") + "."
            store.addDiagnostic(false, reason.code, detail)
        }
        return CollectionOutcome(rejected = reason)
    }

    companion object {
        /**
         * SHA-256 over source app, normalised group, normalised sender, whitespace-collapsed text and,
         * when the notification supplies one, the message's own timestamp.
         */
        fun fingerprint(pkg: String, normalizedGroup: String, sender: String?, text: String, timeMs: Long?): String {
            val parts = listOf(
                pkg, normalizedGroup, sender?.let { TextRules.normalizeKey(it) } ?: "",
                text.replace(Regex("\\s+"), " ").trim(), timeMs?.toString() ?: "",
            )
            val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
