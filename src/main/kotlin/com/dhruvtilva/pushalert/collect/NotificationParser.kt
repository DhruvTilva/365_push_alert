package com.dhruvtilva.pushalert.collect

import com.dhruvtilva.pushalert.domain.TextRules

enum class RejectReason(val code: String, val description: String) {
    NOT_WHATSAPP("NOT_WHATSAPP", "source application was not WhatsApp"),
    COLLECTION_DISABLED("COLLECTION_DISABLED", "collection is switched off"),
    SUMMARY("SUMMARY", "notification was a summary, not an individual message"),
    ONGOING("ONGOING", "notification was an ongoing or system notification"),
    NO_TITLE("NO_TITLE", "group name was not identifiable"),
    GENERIC_TITLE("GENERIC_TITLE", "conversation title was generic"),
    NO_TEXT("NO_TEXT", "no readable text was available"),
    ATTACHMENT_ONLY("ATTACHMENT_ONLY", "only attachment placeholders were present"),
    UNKNOWN_GROUP("UNKNOWN_GROUP", "group is not in the configured list"),
    GROUP_DISABLED("GROUP_DISABLED", "group is not enabled"),
    AMBIGUOUS_GROUP("AMBIGUOUS_GROUP", "conversation matched more than one configured group"),
    INDIVIDUAL_CHAT("INDIVIDUAL_CHAT", "individual chats are not collected"),
    DUPLICATE("DUPLICATE", "message was already collected"),
}

enum class ConversationKind { GROUP, INDIVIDUAL, UNKNOWN }

data class ParsedMessage(
    val text: String,
    val sender: String?,
    val timeMs: Long?,
    val possiblyTruncated: Boolean,
)

data class ParsedNotification(
    val packageName: String,
    val conversationId: String?,
    /** Possible conversation names, most trustworthy first. Matched only by exact comparison. */
    val titleCandidates: List<String>,
    val kind: ConversationKind,
    val messages: List<ParsedMessage>,
    val skippedAttachments: Int,
    val skippedSystem: Int,
    val postTime: Long,
)

sealed interface ParseResult {
    data class Success(val notification: ParsedNotification) : ParseResult
    data class Rejected(val reason: RejectReason) : ParseResult
}

/**
 * Turns a [RawNotification] into zero or more candidate text messages.
 *
 * It never invents content: if individual messages cannot be extracted it rejects the
 * notification instead of guessing. It does not know about the user's group filters.
 */
class NotificationParser {

    fun parse(raw: RawNotification, allowedPackages: Set<String>): ParseResult {
        if (raw.packageName !in allowedPackages) return ParseResult.Rejected(RejectReason.NOT_WHATSAPP)
        if (raw.isGroupSummary) return ParseResult.Rejected(RejectReason.SUMMARY)
        if (raw.isOngoing) return ParseResult.Rejected(RejectReason.ONGOING)

        val conversationTitle = clean(raw.conversationTitle)
        val title = clean(raw.title)
        val primary = conversationTitle ?: title ?: return ParseResult.Rejected(RejectReason.NO_TITLE)
        if (SUMMARY_COUNT.matches(primary)) return ParseResult.Rejected(RejectReason.SUMMARY)
        if (isGenericName(primary)) return ParseResult.Rejected(RejectReason.GENERIC_TITLE)

        // Old WhatsApp layout: "Sender @ Group".
        var atSender: String? = null
        val candidates = LinkedHashSet<String>()
        for (source in listOfNotNull(conversationTitle, title)) {
            candidates += source
            val stripped = COUNT_SUFFIX.replace(source, "").trim()
            if (stripped.isNotEmpty()) candidates += stripped
            val at = AT_PATTERN.matchEntire(source)
            if (at != null) {
                atSender = at.groupValues[1].trim()
                candidates += at.groupValues[2].trim()
            }
        }
        val titleCandidates = candidates.filter { it.isNotEmpty() && !isGenericName(it) }
        if (titleCandidates.isEmpty()) return ParseResult.Rejected(RejectReason.GENERIC_TITLE)

        val jid = (listOfNotNull(raw.shortcutId, raw.tag)).joinToString(" ")
        val conversationId = GROUP_JID.find(jid)?.value
        val kind = detectKind(raw.isGroupConversation, jid, atSender != null)

        val structured = raw.messages.isNotEmpty()
        val entries: List<RawMessage> = if (structured) {
            raw.messages
        } else {
            val body = pickBody(raw.text, raw.bigText)
            if (body != null && SUMMARY_COUNT.matches(body)) return ParseResult.Rejected(RejectReason.SUMMARY)
            if (body == null) emptyList() else listOf(RawMessage(body, null, atSender))
        }

        val parsed = ArrayList<ParsedMessage>()
        var attachments = 0
        var system = 0
        for (entry in entries) {
            val cleaned = clean(entry.text) ?: continue
            var sender = clean(entry.senderName)
            var body = cleaned
            if (sender == null && kind != ConversationKind.INDIVIDUAL) {
                val m = SENDER_PREFIX.matchEntire(body)
                if (m != null) {
                    sender = m.groupValues[1].trim().ifEmpty { null }
                    body = m.groupValues[2].trim()
                }
            }
            when {
                body.isEmpty() -> continue
                isSystemText(body) -> system++
                isAttachmentText(body) || isAttachmentText(cleaned) -> attachments++
                else -> {
                    val time = entry.timeMs?.takeIf { it > 0 }
                    parsed += ParsedMessage(body, sender, time, body.endsWith("…") || body.endsWith("..."))
                }
            }
        }
        if (parsed.isEmpty()) {
            return ParseResult.Rejected(
                if (attachments > 0) RejectReason.ATTACHMENT_ONLY else RejectReason.NO_TEXT,
            )
        }
        return ParseResult.Success(
            ParsedNotification(
                packageName = raw.packageName,
                conversationId = conversationId,
                titleCandidates = titleCandidates,
                kind = kind,
                messages = parsed,
                skippedAttachments = attachments,
                skippedSystem = system,
                postTime = raw.postTime,
            ),
        )
    }

    private fun detectKind(flag: Boolean?, jid: String, atPattern: Boolean): ConversationKind {
        var group = flag == true || atPattern || jid.contains("@g.us")
        var individual = flag == false || jid.contains("@s.whatsapp.net") || jid.contains("@lid")
        if (group && individual) { group = false; individual = false }
        return when {
            group -> ConversationKind.GROUP
            individual -> ConversationKind.INDIVIDUAL
            else -> ConversationKind.UNKNOWN
        }
    }

    private fun clean(value: String?): String? =
        value?.let { TextRules.cleanMessage(it) }?.takeIf { it.isNotEmpty() }

    /** Prefer BIG_TEXT only when it is a longer version of TEXT (i.e. TEXT was cut short). */
    private fun pickBody(text: String?, bigText: String?): String? {
        val t = clean(text)
        val b = clean(bigText)
        if (t == null) return b
        if (b == null) return t
        val tNoEllipsis = t.removeSuffix("…").removeSuffix("...").trimEnd()
        return if (b.length >= tNoEllipsis.length && b.startsWith(tNoEllipsis)) b else t
    }

    private fun isSystemText(body: String): Boolean = SYSTEM_TEXT.any { it.matches(body.trim()) }

    private fun isAttachmentText(body: String): Boolean {
        if (looksLikeAttachment(body)) return true
        val idx = body.indexOf(": ")
        if (idx in 1..40 && !body.substring(0, idx).contains('\n')) {
            return looksLikeAttachment(body.substring(idx + 2))
        }
        return false
    }

    private fun looksLikeAttachment(body: String): Boolean {
        val t = body.trim()
        if (t.isEmpty()) return false
        if (t.codePointAt(0) in MEDIA_MARKERS) return true
        return PLACEHOLDER.matches(t)
    }

    companion object {
        private val SUMMARY_COUNT =
            Regex("^\\d+\\s+(new\\s+)?messages?(\\s+from\\s+\\d+\\s+chats?)?\\.?$", RegexOption.IGNORE_CASE)
        private val COUNT_SUFFIX = Regex("\\s*\\(\\d+\\s+(new\\s+)?messages?\\)\\s*$", RegexOption.IGNORE_CASE)
        private val AT_PATTERN = Regex("^(.{1,60}?) @ (.{1,100})$")
        private val SENDER_PREFIX = Regex("^([^:\\n]{1,40}): (.+)$", RegexOption.DOT_MATCHES_ALL)
        private val GROUP_JID = Regex("[0-9A-Za-z._-]+@g\\.us")

        private val GENERIC_NAMES = setOf(
            "whatsapp", "whatsapp business", "whatsapp web", "wa business", "new message", "new messages",
            "message", "messages", "chats", "chat", "notification", "notifications",
        )

        /** Leading symbols WhatsApp uses for media placeholders (photo, video, audio, document, ...). */
        private val MEDIA_MARKERS: Set<Int> = setOf(
            0x1F4F7, 0x1F4F8, 0x1F4F9, 0x1F3A5, 0x1F3AC, 0x1F3A4, 0x1F399, 0x1F3B5, 0x1F3B6,
            0x1F3A7, 0x1F4C4, 0x1F4C3, 0x1F4CE, 0x1F5BC, 0x1F4CD, 0x1F464, 0x1F4CA,
        )

        /** Whole-string placeholders only, so "I sent you the image" is still an ordinary message. */
        private val PLACEHOLDER = Regex(
            "^(?:photos?|images?|videos?|gifs?|animated gif|stickers?|audio|voice message|voice note|documents?|" +
                "files?|contact|contact card|location|live location|poll|view once (?:photo|video)|" +
                "<?media omitted>?|(?:image|video|audio|sticker|document|gif) omitted|" +
                "\\d+ (?:photos|videos|images|documents|files|audios|stickers))" +
                "(?:\\s*\\([^)]*\\))?\\.?$",
            RegexOption.IGNORE_CASE,
        )

        private val SYSTEM_TEXT = listOf(
            Regex("^this message was deleted\\.?$", RegexOption.IGNORE_CASE),
            Regex("^you deleted this message\\.?$", RegexOption.IGNORE_CASE),
            Regex("^waiting for this message.*$", RegexOption.IGNORE_CASE),
            Regex("^(missed|incoming|ongoing) (voice|video|group)( call)?.*$", RegexOption.IGNORE_CASE),
            Regex("^checking for new messages\\.?$", RegexOption.IGNORE_CASE),
            Regex("^backup in progress.*$", RegexOption.IGNORE_CASE),
            Regex("^whatsapp web is currently active\\.?$", RegexOption.IGNORE_CASE),
        )

        /** True for titles that identify the app rather than a conversation. */
        fun isGenericName(name: String): Boolean {
            val key = TextRules.normalizeKey(name)
            return key.isEmpty() || key in GENERIC_NAMES || SUMMARY_COUNT.matches(key)
        }
    }
}
