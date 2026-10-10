package com.dhruvtilva.pushalert.domain

/**
 * Lifecycle of a collected message.
 *
 * PENDING / EDITED  -> waiting in the inbox (EDITED = text differs from the notification text)
 * SENDING           -> a webhook request is in flight
 * SENT              -> terminal; the row leaves the inbox (optionally recorded in Sent History)
 * FAILED            -> Discord definitely did not accept it; stays in the inbox for retry
 * DELIVERY_UNCERTAIN-> the request may or may not have reached Discord; never retried automatically
 */
enum class MessageStatus {
    PENDING, EDITED, SENDING, SENT, FAILED, DELIVERY_UNCERTAIN;

    fun canTransitionTo(next: MessageStatus): Boolean = next in allowedNext()

    private fun allowedNext(): Set<MessageStatus> = when (this) {
        PENDING -> setOf(EDITED, SENDING)
        EDITED -> setOf(PENDING, SENDING)
        SENDING -> setOf(SENT, FAILED, DELIVERY_UNCERTAIN, PENDING, EDITED)
        FAILED -> setOf(SENDING)
        DELIVERY_UNCERTAIN -> setOf(SENDING, SENT, FAILED)
        SENT -> emptySet()
    }

    /** Whether a publish attempt may start from this state. */
    val isSendable: Boolean
        get() = this == PENDING || this == EDITED || this == FAILED || this == DELIVERY_UNCERTAIN

    /** Whether the text may still be edited. */
    val isEditable: Boolean
        get() = isSendable

    companion object {
        fun parse(value: String?): MessageStatus = values().firstOrNull { it.name == value } ?: PENDING
    }
}

data class PendingMessage(
    val id: Long,
    val sourcePackage: String,
    val groupName: String,
    val conversationId: String?,
    val senderName: String?,
    val originalText: String,
    val editedText: String?,
    val receivedAt: Long,
    val fingerprint: String,
    val status: MessageStatus,
    val selected: Boolean,
    val lastError: String?,
    val discordMessageId: String?,
    val sentAt: Long?,
    val updatedAt: Long,
    val attemptCount: Int,
    val possiblyTruncated: Boolean,
) {
    /** The text that will be published. */
    val currentText: String get() = editedText ?: originalText
    val isEdited: Boolean get() = editedText != null && editedText != originalText
}

data class GroupFilter(
    val id: Long,
    val groupName: String,
    val normalizedName: String,
    val enabled: Boolean,
    val collectedCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

data class SentRecord(
    val id: Long,
    val originalMessageId: Long,
    val groupName: String,
    val senderName: String?,
    val publishedText: String,
    val receivedAt: Long,
    val sentAt: Long,
    val discordMessageId: String?,
    val deliveryStatus: String,
) {
    companion object {
        const val STATUS_SENT = "SENT"
        const val STATUS_CONFIRMED_BY_USER = "CONFIRMED_BY_USER"
    }
}

data class DiagnosticEvent(
    val id: Long,
    val at: Long,
    val accepted: Boolean,
    val code: String,
    val detail: String,
)

/** A message ready to be inserted into the inbox. */
data class NewMessage(
    val sourcePackage: String,
    val groupName: String,
    val groupFilterId: Long?,
    val conversationId: String?,
    val senderName: String?,
    val text: String,
    val receivedAt: Long,
    val fingerprint: String,
    /** True when the fingerprint contains a per-message timestamp from the notification. */
    val timed: Boolean,
    val possiblyTruncated: Boolean,
)

sealed interface AddResult {
    data class Added(val id: Long) : AddResult
    data object Duplicate : AddResult
}

data class AppSettings(
    val collectionEnabled: Boolean = true,
    val allowIndividualChats: Boolean = false,
    val historyEnabled: Boolean = true,
    val diagnosticsEnabled: Boolean = true,
    val whatsappPackages: Set<String> = DEFAULT_WHATSAPP_PACKAGES,
) {
    companion object {
        val DEFAULT_WHATSAPP_PACKAGES: Set<String> = linkedSetOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
