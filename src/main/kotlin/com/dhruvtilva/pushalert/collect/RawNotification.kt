package com.dhruvtilva.pushalert.collect

/** One entry of a MessagingStyle notification (or of its EXTRA_MESSAGES bundle). */
data class RawMessage(val text: String?, val timeMs: Long?, val senderName: String?)

/**
 * Platform-independent snapshot of the notification fields the parser needs. Built by
 * [NotificationAdapter]; keeps the parser free of Android classes so it is unit-testable.
 */
data class RawNotification(
    val packageName: String,
    val postTime: Long,
    val tag: String? = null,
    val shortcutId: String? = null,
    val isGroupSummary: Boolean = false,
    val isOngoing: Boolean = false,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val summaryText: String? = null,
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean? = null,
    val messages: List<RawMessage> = emptyList(),
    val textLines: List<String> = emptyList(),
)
