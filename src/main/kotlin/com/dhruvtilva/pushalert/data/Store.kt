package com.dhruvtilva.pushalert.data

import com.dhruvtilva.pushalert.domain.AddResult
import com.dhruvtilva.pushalert.domain.DiagnosticEvent
import com.dhruvtilva.pushalert.domain.GroupFilter
import com.dhruvtilva.pushalert.domain.MessageStatus
import com.dhruvtilva.pushalert.domain.NewMessage
import com.dhruvtilva.pushalert.domain.PendingMessage
import com.dhruvtilva.pushalert.domain.SentRecord
import com.dhruvtilva.pushalert.domain.TextRules
import com.dhruvtilva.pushalert.domain.ValidationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

sealed interface EditResult {
    data object Saved : EditResult
    data object NotFound : EditResult
    data object Locked : EditResult
    data class Invalid(val validation: ValidationResult) : EditResult
}

sealed interface GroupResult {
    data class Saved(val id: Long) : GroupResult
    data class Invalid(val message: String) : GroupResult
    data object Duplicate : GroupResult
    data object NotFound : GroupResult
}

/** All local persistence: inbox, sent history, group filters, de-duplication memory, diagnostics. */
class Store(
    private val db: Db,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val changes = MutableStateFlow(0L)

    private suspend fun <T> io(block: () -> T): T = withContext(dispatcher) { block() }
    private fun changed() { changes.update { it + 1 } }

    // ---------------------------------------------------------------- inbox

    /**
     * Inserts the message unless it is a duplicate. De-duplication policy:
     *  - a fingerprint that includes the notification's per-message timestamp is a duplicate forever
     *    (until pruned), because Android re-posts earlier MessagingStyle messages in later updates;
     *  - a fingerprint without a timestamp is a duplicate only within [UNTIMED_WINDOW_MS], so a
     *    deliberate repeat of the same text later is kept.
     * The fingerprint memory survives deletion/sending of the message, so deleted or sent messages
     * are not resurrected by a later re-post. It stores hashes only, never message text.
     */
    suspend fun addIfNew(m: NewMessage): AddResult = io {
        val now = clock()
        val result = db.transaction {
            val seen = db.query("SELECT seen_at, timed FROM seen_fingerprints WHERE fingerprint = ?", m.fingerprint)
                .firstOrNull()
            if (seen != null) {
                if (seen.bool("timed") || now - seen.requireLong("seen_at") < UNTIMED_WINDOW_MS) {
                    return@transaction AddResult.Duplicate
                }
                db.update("UPDATE seen_fingerprints SET seen_at = ? WHERE fingerprint = ?", now, m.fingerprint)
            } else {
                db.exec(
                    "INSERT INTO seen_fingerprints(fingerprint, seen_at, timed) VALUES(?,?,?)",
                    m.fingerprint, now, m.timed,
                )
            }
            val id = db.insert(
                """INSERT INTO messages(source_package, group_name, conversation_id, sender_name, original_text,
                    received_at, fingerprint, status, selected, updated_at, truncated)
                   VALUES(?,?,?,?,?,?,?,?,0,?,?)""",
                m.sourcePackage, m.groupName, m.conversationId, m.senderName, m.text,
                m.receivedAt, m.fingerprint, MessageStatus.PENDING.name, now, m.possiblyTruncated,
            )
            if (m.groupFilterId != null) {
                db.exec("UPDATE group_filters SET collected_count = collected_count + 1 WHERE id = ?", m.groupFilterId)
            }
            AddResult.Added(id)
        }
        if (result is AddResult.Added) changed()
        result
    }

    suspend fun getMessage(id: Long): PendingMessage? = io { loadMessage(id) }

    suspend fun pendingMessages(): List<PendingMessage> = io { loadMessages() }

    fun observePending(): Flow<List<PendingMessage>> = changes.map { loadMessages() }.flowOn(dispatcher)

    /** Applies an edit. Passing the original text again reverts the message to "not edited". */
    suspend fun editText(id: Long, newText: String): EditResult = io {
        val text = TextRules.cleanMessage(newText)
        val validation = TextRules.validate(text)
        if (!validation.isValid) return@io EditResult.Invalid(validation)
        val result = db.transaction {
            val m = loadMessage(id) ?: return@transaction EditResult.NotFound
            if (!m.status.isEditable) return@transaction EditResult.Locked
            val edited = if (text == m.originalText) null else text
            val status = when {
                m.status == MessageStatus.PENDING && edited != null -> MessageStatus.EDITED
                m.status == MessageStatus.EDITED && edited == null -> MessageStatus.PENDING
                else -> m.status
            }
            db.update(
                "UPDATE messages SET edited_text = ?, status = ?, updated_at = ? WHERE id = ?",
                edited, status.name, clock(), id,
            )
            EditResult.Saved
        }
        if (result == EditResult.Saved) changed()
        result
    }

    /**
     * Splits a waiting message at blank lines into separate inbox messages (same group/sender, kept in
     * order). Returns the number of messages afterwards (1 = nothing to split).
     */
    suspend fun splitMessage(id: Long): Int = io {
        val result = db.transaction {
            val m = loadMessage(id) ?: return@transaction 0
            if (m.status != MessageStatus.PENDING && m.status != MessageStatus.EDITED) return@transaction 1
            val parts = TextRules.splitParagraphs(m.currentText)
            if (parts.size < 2) return@transaction 1
            val now = clock()
            db.update(
                "UPDATE messages SET original_text = ?, edited_text = NULL, status = ?, updated_at = ? WHERE id = ?",
                parts[0], MessageStatus.PENDING.name, now, id,
            )
            parts.drop(1).forEachIndexed { i, text ->
                val fp = m.fingerprint + "#split" + (i + 1)
                db.exec(
                    "INSERT OR IGNORE INTO seen_fingerprints(fingerprint, seen_at, timed) VALUES(?,?,1)", fp, now,
                )
                db.insert(
                    """INSERT INTO messages(source_package, group_name, conversation_id, sender_name, original_text,
                        received_at, fingerprint, status, selected, updated_at, truncated)
                       VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                    m.sourcePackage, m.groupName, m.conversationId, m.senderName, text,
                    m.receivedAt, fp, MessageStatus.PENDING.name, m.selected, now,
                    m.possiblyTruncated && i == parts.size - 2,
                )
            }
            parts.size
        }
        if (result > 1) changed()
        result
    }

    suspend fun restoreOriginal(id: Long): EditResult {
        val m = getMessage(id) ?: return EditResult.NotFound
        return editText(id, m.originalText)
    }

    suspend fun setSelected(ids: Collection<Long>, selected: Boolean) = io {
        if (ids.isEmpty()) return@io
        db.transaction { ids.forEach { db.update("UPDATE messages SET selected = ? WHERE id = ? AND status != 'SENDING'", selected, it) } }
        changed()
    }

    suspend fun setAllSelected(selected: Boolean) = io {
        db.update("UPDATE messages SET selected = ? WHERE status != 'SENDING'", selected)
        changed()
    }

    /**
     * Atomically claims a message for sending (any sendable state -> SENDING).
     * Returns null if it no longer exists or is not sendable, which prevents double sends.
     */
    suspend fun beginSending(id: Long): PendingMessage? = io {
        val claimed = db.transaction {
            val m = loadMessage(id) ?: return@transaction null
            if (!m.status.isSendable) return@transaction null
            db.update(
                "UPDATE messages SET status = ?, attempt_count = attempt_count + 1, last_error = NULL, updated_at = ? WHERE id = ?",
                MessageStatus.SENDING.name, clock(), id,
            )
            loadMessage(id)
        }
        if (claimed != null) changed()
        claimed
    }

    /** SENDING -> SENT: removes the row from the inbox and (optionally) records Sent History. */
    suspend fun markSent(id: Long, discordMessageId: String?, keepHistory: Boolean): Boolean =
        finishSent(id, discordMessageId, keepHistory, MessageStatus.SENDING, SentRecord.STATUS_SENT)

    /** Resolves an uncertain delivery that the user confirmed on Discord. */
    suspend fun confirmDelivered(id: Long, keepHistory: Boolean): Boolean =
        finishSent(id, null, keepHistory, MessageStatus.DELIVERY_UNCERTAIN, SentRecord.STATUS_CONFIRMED_BY_USER)

    private suspend fun finishSent(
        id: Long, discordMessageId: String?, keepHistory: Boolean, from: MessageStatus, label: String,
    ): Boolean = io {
        val ok = db.transaction {
            val m = loadMessage(id) ?: return@transaction false
            if (m.status != from || !m.status.canTransitionTo(MessageStatus.SENT)) return@transaction false
            val now = clock()
            if (keepHistory) {
                db.insert(
                    """INSERT INTO sent_history(original_message_id, group_name, sender_name, published_text,
                        received_at, sent_at, discord_message_id, delivery_status) VALUES(?,?,?,?,?,?,?,?)""",
                    m.id, m.groupName, m.senderName, m.currentText, m.receivedAt, now, discordMessageId, label,
                )
            }
            db.update("DELETE FROM messages WHERE id = ?", id)
            true
        }
        if (ok) changed()
        ok
    }

    suspend fun markFailed(id: Long, error: String): Boolean = transition(id, MessageStatus.FAILED, error)

    suspend fun markUncertain(id: Long, note: String): Boolean = transition(id, MessageStatus.DELIVERY_UNCERTAIN, note)

    /** The user checked Discord and the message was not delivered: it becomes retryable (FAILED). */
    suspend fun confirmNotDelivered(id: Long): Boolean =
        transition(id, MessageStatus.FAILED, "Confirmed not delivered; safe to retry.", from = MessageStatus.DELIVERY_UNCERTAIN)

    /** SENDING -> back to PENDING/EDITED without having contacted Discord successfully. */
    suspend fun releaseSending(id: Long, note: String?): Boolean = io {
        val ok = db.transaction {
            val m = loadMessage(id) ?: return@transaction false
            if (m.status != MessageStatus.SENDING) return@transaction false
            val next = if (m.isEdited) MessageStatus.EDITED else MessageStatus.PENDING
            db.update(
                "UPDATE messages SET status = ?, last_error = ?, updated_at = ? WHERE id = ?",
                next.name, note, clock(), id,
            )
            true
        }
        if (ok) changed()
        ok
    }

    private suspend fun transition(
        id: Long, to: MessageStatus, note: String?, from: MessageStatus = MessageStatus.SENDING,
    ): Boolean = io {
        val ok = db.transaction {
            val m = loadMessage(id) ?: return@transaction false
            if (m.status != from || !m.status.canTransitionTo(to)) return@transaction false
            db.update(
                "UPDATE messages SET status = ?, last_error = ?, updated_at = ? WHERE id = ?",
                to.name, note, clock(), id,
            )
            true
        }
        if (ok) changed()
        ok
    }

    /** After a crash or process kill a SENDING row may or may not have reached Discord. */
    suspend fun recoverInterrupted(): Int = io {
        val n = db.update(
            "UPDATE messages SET status = ?, last_error = ?, updated_at = ? WHERE status = 'SENDING'",
            MessageStatus.DELIVERY_UNCERTAIN.name,
            "The app stopped while this message was being sent. Check Discord before retrying.",
            clock(),
        )
        if (n > 0) changed()
        n
    }

    suspend fun deleteMessages(ids: Collection<Long>): Int = io {
        val n = db.transaction { ids.sumOf { db.update("DELETE FROM messages WHERE id = ? AND status != 'SENDING'", it) } }
        if (n > 0) changed()
        n
    }

    suspend fun deleteSelected(): Int = io {
        val n = db.update("DELETE FROM messages WHERE selected = 1 AND status != 'SENDING'")
        if (n > 0) changed()
        n
    }

    suspend fun deleteAllPending(): Int = io {
        val n = db.update("DELETE FROM messages WHERE status != 'SENDING'")
        if (n > 0) changed()
        n
    }

    // -------------------------------------------------------------- history

    fun observeHistory(): Flow<List<SentRecord>> = changes.map { loadHistory() }.flowOn(dispatcher)

    suspend fun history(): List<SentRecord> = io { loadHistory() }

    suspend fun deleteHistory(id: Long) = io {
        db.update("DELETE FROM sent_history WHERE id = ?", id)
        changed()
    }

    suspend fun clearHistory(): Int = io {
        val n = db.update("DELETE FROM sent_history")
        changed()
        n
    }

    // --------------------------------------------------------------- groups

    fun observeGroups(): Flow<List<GroupFilter>> = changes.map { loadGroups() }.flowOn(dispatcher)

    suspend fun groups(): List<GroupFilter> = io { loadGroups() }

    suspend fun addGroup(name: String): GroupResult = io {
        val problem = com.dhruvtilva.pushalert.collect.GroupMatcher.validateName(name)
        if (problem != null) return@io GroupResult.Invalid(problem)
        val display = TextRules.stripInvisible(name).trim().replace(Regex("\\s+"), " ")
        val key = TextRules.normalizeKey(display)
        val result = db.transaction {
            if (db.query("SELECT id FROM group_filters WHERE normalized_name = ?", key).isNotEmpty()) {
                return@transaction GroupResult.Duplicate
            }
            val now = clock()
            GroupResult.Saved(
                db.insert(
                    "INSERT INTO group_filters(group_name, normalized_name, enabled, created_at, updated_at) VALUES(?,?,1,?,?)",
                    display, key, now, now,
                ),
            )
        }
        if (result is GroupResult.Saved) changed()
        result
    }

    suspend fun renameGroup(id: Long, name: String): GroupResult = io {
        val problem = com.dhruvtilva.pushalert.collect.GroupMatcher.validateName(name)
        if (problem != null) return@io GroupResult.Invalid(problem)
        val display = TextRules.stripInvisible(name).trim().replace(Regex("\\s+"), " ")
        val key = TextRules.normalizeKey(display)
        val result = db.transaction {
            if (db.query("SELECT id FROM group_filters WHERE id = ?", id).isEmpty()) return@transaction GroupResult.NotFound
            if (db.query("SELECT id FROM group_filters WHERE normalized_name = ? AND id != ?", key, id).isNotEmpty()) {
                return@transaction GroupResult.Duplicate
            }
            db.update(
                "UPDATE group_filters SET group_name = ?, normalized_name = ?, updated_at = ? WHERE id = ?",
                display, key, clock(), id,
            )
            GroupResult.Saved(id)
        }
        if (result is GroupResult.Saved) changed()
        result
    }

    suspend fun setGroupEnabled(id: Long, enabled: Boolean) = io {
        db.update("UPDATE group_filters SET enabled = ?, updated_at = ? WHERE id = ?", enabled, clock(), id)
        changed()
    }

    suspend fun deleteGroup(id: Long) = io {
        db.update("DELETE FROM group_filters WHERE id = ?", id)
        changed()
    }

    // ---------------------------------------------------------- diagnostics

    fun observeDiagnostics(): Flow<List<DiagnosticEvent>> = changes.map { loadDiagnostics() }.flowOn(dispatcher)

    suspend fun diagnostics(): List<DiagnosticEvent> = io { loadDiagnostics() }

    suspend fun addDiagnostic(accepted: Boolean, code: String, detail: String) = io {
        db.transaction {
            db.insert(
                "INSERT INTO diagnostics(at, accepted, code, detail) VALUES(?,?,?,?)",
                clock(), accepted, code, detail,
            )
            db.update("DELETE FROM diagnostics WHERE id <= (SELECT MAX(id) FROM diagnostics) - $DIAGNOSTICS_LIMIT")
        }
        changed()
    }

    suspend fun clearDiagnostics() = io {
        db.update("DELETE FROM diagnostics")
        changed()
    }

    // ---------------------------------------------------------- maintenance

    /** Forget fingerprints (hashes only) older than [maxAgeMs]. */
    suspend fun pruneSeen(maxAgeMs: Long = SEEN_RETENTION_MS): Int = io {
        db.update("DELETE FROM seen_fingerprints WHERE seen_at < ?", clock() - maxAgeMs)
    }

    // -------------------------------------------------------------- mapping

    private fun loadMessage(id: Long): PendingMessage? =
        db.query("SELECT * FROM messages WHERE id = ?", id).firstOrNull()?.toMessage()

    private fun loadMessages(): List<PendingMessage> =
        db.query("SELECT * FROM messages ORDER BY received_at ASC, id ASC").map { it.toMessage() }

    private fun loadHistory(): List<SentRecord> =
        db.query("SELECT * FROM sent_history ORDER BY sent_at DESC, id DESC").map {
            SentRecord(
                id = it.requireLong("id"),
                originalMessageId = it.requireLong("original_message_id"),
                groupName = it.requireString("group_name"),
                senderName = it.string("sender_name"),
                publishedText = it.requireString("published_text"),
                receivedAt = it.requireLong("received_at"),
                sentAt = it.requireLong("sent_at"),
                discordMessageId = it.string("discord_message_id"),
                deliveryStatus = it.requireString("delivery_status"),
            )
        }

    private fun loadGroups(): List<GroupFilter> =
        db.query("SELECT * FROM group_filters ORDER BY lower(group_name) ASC, id ASC").map {
            GroupFilter(
                id = it.requireLong("id"),
                groupName = it.requireString("group_name"),
                normalizedName = it.requireString("normalized_name"),
                enabled = it.bool("enabled"),
                collectedCount = it.int("collected_count") ?: 0,
                createdAt = it.requireLong("created_at"),
                updatedAt = it.requireLong("updated_at"),
            )
        }

    private fun loadDiagnostics(): List<DiagnosticEvent> =
        db.query("SELECT * FROM diagnostics ORDER BY id DESC").map {
            DiagnosticEvent(
                id = it.requireLong("id"),
                at = it.requireLong("at"),
                accepted = it.bool("accepted"),
                code = it.requireString("code"),
                detail = it.requireString("detail"),
            )
        }

    private fun Row.toMessage() = PendingMessage(
        id = requireLong("id"),
        sourcePackage = requireString("source_package"),
        groupName = requireString("group_name"),
        conversationId = string("conversation_id"),
        senderName = string("sender_name"),
        originalText = requireString("original_text"),
        editedText = string("edited_text"),
        receivedAt = requireLong("received_at"),
        fingerprint = requireString("fingerprint"),
        status = MessageStatus.parse(string("status")),
        selected = bool("selected"),
        lastError = string("last_error"),
        discordMessageId = string("discord_message_id"),
        sentAt = long("sent_at"),
        updatedAt = requireLong("updated_at"),
        attemptCount = int("attempt_count") ?: 0,
        possiblyTruncated = bool("truncated"),
    )

    companion object {
        const val UNTIMED_WINDOW_MS = 2 * 60 * 1000L
        const val SEEN_RETENTION_MS = 3 * 24 * 60 * 60 * 1000L
        const val DIAGNOSTICS_LIMIT = 100
    }
}
