package com.dhruvtilva.pushalert.data

import com.dhruvtilva.pushalert.JdbcDb
import com.dhruvtilva.pushalert.TestClock
import com.dhruvtilva.pushalert.domain.AddResult
import com.dhruvtilva.pushalert.domain.MessageStatus
import com.dhruvtilva.pushalert.domain.NewMessage
import com.dhruvtilva.pushalert.domain.SentRecord
import com.dhruvtilva.pushalert.domain.ValidationResult
import com.dhruvtilva.pushalert.newStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = TestClock()
    private val store = newStore(clock = clock)
    private var counter = 0

    private fun msg(text: String, group: String = "Project Updates", at: Long = 1000L + counter) =
        NewMessage("com.whatsapp", group, null, null, "Alice", text, at, "fp-${counter++}", true, false)

    private suspend fun add(text: String, at: Long = 1000L + counter): Long =
        (store.addIfNew(msg(text, at = at)) as AddResult.Added).id

    // ------------------------------------------------------------ editing

    @Test fun editAndSaveKeepsOriginalAndMarksEdited() = runTest {
        val id = add("original")
        assertEquals(EditResult.Saved, store.editText(id, "  changed text \n"))
        val m = store.getMessage(id)!!
        assertEquals("original", m.originalText)
        assertEquals("changed text", m.editedText)
        assertEquals("changed text", m.currentText)
        assertTrue(m.isEdited)
        assertEquals(MessageStatus.EDITED, m.status)
    }

    @Test fun cancellingAnEditChangesNothing() = runTest {
        val id = add("original")
        val before = store.getMessage(id)!!
        // "Cancel" never calls the store; the message must be exactly as before.
        assertEquals(before, store.getMessage(id))
        assertEquals(MessageStatus.PENDING, before.status)
        assertNull(before.editedText)
    }

    @Test fun emptyEditIsRejectedAndNothingChanges() = runTest {
        val id = add("original")
        val r = store.editText(id, "   \n ")
        assertEquals(EditResult.Invalid(ValidationResult.Empty), r)
        assertEquals("original", store.getMessage(id)!!.currentText)
    }

    @Test fun overlongEditIsRejectedNotTruncated() = runTest {
        val id = add("original")
        val r = store.editText(id, "x".repeat(2001))
        assertTrue(r is EditResult.Invalid && r.validation is ValidationResult.TooLong)
        assertEquals("original", store.getMessage(id)!!.currentText)
        assertEquals(EditResult.Saved, store.editText(id, "x".repeat(2000)))
        assertEquals(2000, store.getMessage(id)!!.currentText.length)
    }

    @Test fun editingBackToTheOriginalClearsTheEditedState() = runTest {
        val id = add("original")
        store.editText(id, "changed")
        store.editText(id, "original")
        val m = store.getMessage(id)!!
        assertNull(m.editedText)
        assertFalse(m.isEdited)
        assertEquals(MessageStatus.PENDING, m.status)
    }

    @Test fun restoreOriginalWorks() = runTest {
        val id = add("original")
        store.editText(id, "changed")
        assertEquals(EditResult.Saved, store.restoreOriginal(id))
        assertEquals("original", store.getMessage(id)!!.currentText)
    }

    @Test fun editingFailedMessageKeepsFailedStatusAndError() = runTest {
        val id = add("original")
        store.beginSending(id); store.markFailed(id, "boom")
        store.editText(id, "fixed")
        val m = store.getMessage(id)!!
        assertEquals(MessageStatus.FAILED, m.status)
        assertEquals("fixed", m.currentText)
        assertEquals("boom", m.lastError)
    }

    @Test fun sendingMessageCannotBeEdited() = runTest {
        val id = add("original")
        store.beginSending(id)
        assertEquals(EditResult.Locked, store.editText(id, "other"))
        assertEquals(EditResult.NotFound, store.editText(999, "other"))
    }

    // -------------------------------------------------------- lifecycle

    @Test fun beginSendingIsExclusive() = runTest {
        val id = add("a")
        assertNotNull(store.beginSending(id))
        assertNull(store.beginSending(id))
        assertEquals(1, store.getMessage(id)!!.attemptCount)
    }

    @Test fun markSentMovesMessageToHistoryWithPublishedText() = runTest {
        val id = add("original")
        store.editText(id, "edited version")
        store.beginSending(id)
        clock.now += 5
        assertTrue(store.markSent(id, "discord-1", keepHistory = true))
        assertNull(store.getMessage(id))
        val h = store.history().single()
        assertEquals("edited version", h.publishedText)
        assertEquals("discord-1", h.discordMessageId)
        assertEquals(SentRecord.STATUS_SENT, h.deliveryStatus)
        assertEquals(clock.now, h.sentAt)
    }

    @Test fun markSentWithHistoryDisabledLeavesNoContentBehind() = runTest {
        val id = add("sensitive")
        store.beginSending(id)
        assertTrue(store.markSent(id, "d", keepHistory = false))
        assertTrue(store.history().isEmpty())
        assertTrue(store.pendingMessages().isEmpty())
    }

    @Test fun markSentRequiresSendingState() = runTest {
        val id = add("a")
        assertFalse(store.markSent(id, "d", true))
        assertEquals(MessageStatus.PENDING, store.getMessage(id)!!.status)
    }

    @Test fun failedMessagesStayInInboxAndCanBeRetried() = runTest {
        val id = add("a")
        store.beginSending(id)
        assertTrue(store.markFailed(id, "HTTP 400"))
        val m = store.getMessage(id)!!
        assertEquals(MessageStatus.FAILED, m.status)
        assertEquals("HTTP 400", m.lastError)
        assertNotNull(store.beginSending(id))
        assertNull(store.getMessage(id)!!.lastError)
    }

    @Test fun uncertainDeliveryCanBeConfirmedDelivered() = runTest {
        val id = add("a")
        store.beginSending(id); store.markUncertain(id, "timeout")
        assertEquals(MessageStatus.DELIVERY_UNCERTAIN, store.getMessage(id)!!.status)
        assertTrue(store.confirmDelivered(id, keepHistory = true))
        assertNull(store.getMessage(id))
        assertEquals(SentRecord.STATUS_CONFIRMED_BY_USER, store.history().single().deliveryStatus)
    }

    @Test fun uncertainDeliveryCanBeConfirmedNotDelivered() = runTest {
        val id = add("a")
        store.beginSending(id); store.markUncertain(id, "timeout")
        assertTrue(store.confirmNotDelivered(id))
        assertEquals(MessageStatus.FAILED, store.getMessage(id)!!.status)
    }

    @Test fun confirmDeliveredOnlyAppliesToUncertainMessages() = runTest {
        val id = add("a")
        assertFalse(store.confirmDelivered(id, true))
        assertFalse(store.confirmNotDelivered(id))
    }

    @Test fun releaseSendingRestoresPreviousKind() = runTest {
        val a = add("a"); val b = add("b")
        store.editText(b, "b2")
        store.beginSending(a); store.beginSending(b)
        store.releaseSending(a, "cancelled"); store.releaseSending(b, null)
        assertEquals(MessageStatus.PENDING, store.getMessage(a)!!.status)
        assertEquals(MessageStatus.EDITED, store.getMessage(b)!!.status)
    }

    @Test fun interruptedSendsBecomeUncertainAfterRestart() = runTest {
        val id = add("a")
        store.beginSending(id)
        assertEquals(1, store.recoverInterrupted())
        val m = store.getMessage(id)!!
        assertEquals(MessageStatus.DELIVERY_UNCERTAIN, m.status)
        assertTrue(m.lastError!!.contains("Check Discord"))
    }

    // ------------------------------------------------------ selection / clearing

    @Test fun selectionAndBulkSelection() = runTest {
        val a = add("a"); val b = add("b"); val c = add("c")
        store.setSelected(listOf(a, c), true)
        assertEquals(listOf(true, false, true), store.pendingMessages().map { it.selected })
        store.setAllSelected(false)
        assertTrue(store.pendingMessages().none { it.selected })
        store.setAllSelected(true)
        assertTrue(store.pendingMessages().all { it.selected })
        assertEquals(setOf(a, b, c), store.pendingMessages().map { it.id }.toSet())
    }

    @Test fun inboxIsOrderedByReceiveTime() = runTest {
        add("late", at = 9000); add("early", at = 1000); add("middle", at = 5000)
        assertEquals(listOf("early", "middle", "late"), store.pendingMessages().map { it.originalText })
        assertEquals(listOf("early", "middle", "late"), store.observePending().first().map { it.originalText })
    }

    @Test fun clearSelectedAndClearAllPending() = runTest {
        val a = add("a"); add("b"); add("c")
        store.setSelected(listOf(a), true)
        assertEquals(1, store.deleteSelected())
        assertEquals(2, store.pendingMessages().size)
        assertEquals(2, store.deleteAllPending())
        assertTrue(store.pendingMessages().isEmpty())
    }

    @Test fun clearingNeverDeletesAMessageThatIsBeingSent() = runTest {
        val a = add("a"); add("b")
        store.beginSending(a)
        store.deleteAllPending()
        assertEquals(listOf(a), store.pendingMessages().map { it.id })
        assertEquals(0, store.deleteMessages(listOf(a)))
    }

    @Test fun clearHistoryAndDeleteSingleHistoryEntry() = runTest {
        repeat(3) { val id = add("m$it"); store.beginSending(id); store.markSent(id, "d$it", true) }
        assertEquals(3, store.history().size)
        store.deleteHistory(store.history().first().id)
        assertEquals(2, store.history().size)
        assertEquals(2, store.clearHistory())
        assertTrue(store.history().isEmpty())
    }

    // ------------------------------------------------------------- groups

    @Test fun groupLifecycle() = runTest {
        val id = (store.addGroup("  AI/ML   Updates ") as GroupResult.Saved).id
        assertEquals("AI/ML Updates", store.groups().single().groupName)
        assertEquals(GroupResult.Duplicate, store.addGroup("ai/ml updates"))
        assertTrue(store.addGroup("WhatsApp") is GroupResult.Invalid)
        assertTrue(store.addGroup("   ") is GroupResult.Invalid)

        val other = (store.addGroup("Project Updates") as GroupResult.Saved).id
        assertEquals(GroupResult.Duplicate, store.renameGroup(other, "AI/ML UPDATES"))
        assertEquals(GroupResult.Saved(other), store.renameGroup(other, "Project Updates v2"))
        assertEquals(GroupResult.NotFound, store.renameGroup(12345, "Nope"))

        store.setGroupEnabled(id, false)
        assertFalse(store.groups().first { it.id == id }.enabled)
        store.deleteGroup(id)
        assertEquals(listOf("Project Updates v2"), store.groups().map { it.groupName })
    }

    @Test fun renamingKeepsCollectedCount() = runTest {
        val gid = (store.addGroup("A Group") as GroupResult.Saved).id
        store.addIfNew(msg("x").copy(groupFilterId = gid))
        store.renameGroup(gid, "B Group")
        assertEquals(1, store.groups().single().collectedCount)
    }

    // -------------------------------------------------------- diagnostics

    @Test fun diagnosticsAreCappedAndClearable() = runTest {
        repeat(Store.DIAGNOSTICS_LIMIT + 30) { store.addDiagnostic(false, "X", "event $it") }
        assertEquals(Store.DIAGNOSTICS_LIMIT, store.diagnostics().size)
        assertEquals("event ${Store.DIAGNOSTICS_LIMIT + 29}", store.diagnostics().first().detail)
        store.clearDiagnostics()
        assertTrue(store.diagnostics().isEmpty())
    }

    @Test fun pruneSeenForgetsOldFingerprintsOnly() = runTest {
        store.addIfNew(msg("old"))
        clock.now += Store.SEEN_RETENTION_MS + 1
        store.addIfNew(msg("new"))
        assertEquals(1, store.pruneSeen())
    }

    // -------------------------------------------------------- persistence

    @Test fun everythingSurvivesAProcessRestart() = runTest {
        val file = tmp.newFile("pushalert.db")
        val db1 = JdbcDb.file(file)
        Schema.create(db1)
        val s1 = com.dhruvtilva.pushalert.newStore(db1, clock)
        val gid = (s1.addGroup("Project Updates") as GroupResult.Saved).id
        s1.setGroupEnabled(gid, true)
        val pending = (s1.addIfNew(msg("kept pending").copy(groupFilterId = gid)) as AddResult.Added).id
        val failed = (s1.addIfNew(msg("kept failed")) as AddResult.Added).id
        val sent = (s1.addIfNew(msg("went out")) as AddResult.Added).id
        s1.editText(pending, "edited text")
        s1.setSelected(listOf(pending), true)
        s1.beginSending(failed); s1.markFailed(failed, "HTTP 400")
        s1.beginSending(sent); s1.markSent(sent, "d-9", true)
        db1.close()

        // "Restart": a brand-new connection, schema check and store over the same file.
        val db2 = JdbcDb.file(file)
        val s2 = com.dhruvtilva.pushalert.newStore(db2, clock)
        val msgs = s2.pendingMessages()
        assertEquals(2, msgs.size)
        val p = msgs.first { it.id == pending }
        assertEquals("edited text", p.currentText); assertTrue(p.selected); assertEquals(MessageStatus.EDITED, p.status)
        assertEquals(MessageStatus.FAILED, msgs.first { it.id == failed }.status)
        assertEquals("went out", s2.history().single().publishedText)
        assertEquals("Project Updates", s2.groups().single().groupName)
        assertEquals(1, s2.groups().single().collectedCount)
        // De-duplication memory also survives: the same notification is still recognised.
        assertTrue(s2.addIfNew(msg("kept pending").copy(fingerprint = p.fingerprint)) is AddResult.Duplicate)
        db2.close()
    }
}
