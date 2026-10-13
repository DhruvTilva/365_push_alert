package com.dhruvtilva.pushalert.collect

import com.dhruvtilva.pushalert.TestClock
import com.dhruvtilva.pushalert.domain.AppSettings
import com.dhruvtilva.pushalert.newStore
import com.dhruvtilva.pushalert.wa
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionEngineTest {
    private val clock = TestClock()
    private val store = newStore(clock = clock)
    private var cfg = AppSettings()
    private val engine = CollectionEngine(store, { cfg })

    private suspend fun group(name: String, enabled: Boolean = true) {
        val id = (store.addGroup(name) as com.dhruvtilva.pushalert.data.GroupResult.Saved).id
        if (!enabled) store.setGroupEnabled(id, false)
    }

    @Test fun nothingIsCollectedByDefaultWhenNoGroupsAreConfigured() = runTest {
        val o = engine.process(wa(title = "Project Updates", text = "hi"))
        assertEquals(RejectReason.UNKNOWN_GROUP, o.rejected)
        assertTrue(store.pendingMessages().isEmpty())
    }

    @Test fun matchingEnabledGroupIsCollectedWithConfiguredName() = runTest {
        group("Project Updates")
        val o = engine.process(wa(title = "  project UPDATES ", text = "Deploy finished"))
        assertEquals(1, o.added)
        val m = store.pendingMessages().single()
        assertEquals("Project Updates", m.groupName)
        assertEquals("Deploy finished", m.originalText)
        assertEquals(1, store.groups().single().collectedCount)
        assertTrue(store.diagnostics().first().accepted)
    }

    @Test fun unknownGroupIsRejectedAndLeavesNoContentBehind() = runTest {
        group("Project Updates")
        val o = engine.process(wa(title = "Secret Family Chat", text = "private words"))
        assertEquals(RejectReason.UNKNOWN_GROUP, o.rejected)
        assertTrue(store.pendingMessages().isEmpty())
        val diag = store.diagnostics()
        assertEquals(1, diag.size)
        assertFalse(diag.joinToString { it.detail + it.code }.contains("private words"))
        assertFalse(diag.joinToString { it.detail + it.code }.contains("Secret Family Chat"))
    }

    @Test fun disabledGroupIsIgnored() = runTest {
        group("Project Updates", enabled = false)
        assertEquals(RejectReason.GROUP_DISABLED, engine.process(wa(text = "hi")).rejected)
        assertTrue(store.pendingMessages().isEmpty())
    }

    @Test fun otherAppsAreRejectedWithoutAnyDiagnosticTrace() = runTest {
        group("Project Updates")
        assertEquals(RejectReason.NOT_WHATSAPP, engine.process(wa(pkg = "com.bank.app", text = "OTP 123456")).rejected)
        assertTrue(store.diagnostics().isEmpty())
        assertTrue(store.pendingMessages().isEmpty())
    }

    @Test fun attachmentOnlyNotificationsAreNotStored() = runTest {
        group("Project Updates")
        assertEquals(RejectReason.ATTACHMENT_ONLY, engine.process(wa(text = "📷 Photo")).rejected)
        assertTrue(store.pendingMessages().isEmpty())
    }

    @Test fun summariesAreNotStored() = runTest {
        group("Project Updates")
        assertEquals(RejectReason.SUMMARY, engine.process(wa(text = "5 new messages")).rejected)
        assertTrue(store.pendingMessages().isEmpty())
    }

    @Test fun collectionSwitchStopsEverything() = runTest {
        group("Project Updates")
        cfg = cfg.copy(collectionEnabled = false)
        assertEquals(RejectReason.COLLECTION_DISABLED, engine.process(wa(text = "hi")).rejected)
        assertTrue(store.pendingMessages().isEmpty())
    }

    @Test fun individualChatsAreRejectedUnlessExplicitlyEnabled() = runTest {
        group("Alice")
        val n = wa(title = "Alice", text = "hello", isGroup = false)
        assertEquals(RejectReason.INDIVIDUAL_CHAT, engine.process(n).rejected)
        cfg = cfg.copy(allowIndividualChats = true)
        assertEquals(1, engine.process(wa(title = "Alice", text = "hello again", isGroup = false)).added)
    }

    @Test fun conflictingGroupMetadataIsRejectedAsAmbiguous() = runTest {
        group("Project Updates"); group("AI/ML Updates")
        val o = engine.process(wa(title = "AI/ML Updates", conversationTitle = "Project Updates", text = "hi"))
        assertEquals(RejectReason.AMBIGUOUS_GROUP, o.rejected)
        assertTrue(store.pendingMessages().isEmpty())
    }

    @Test fun identicalNotificationPostedTwiceCreatesOneEntry() = runTest {
        group("Project Updates")
        val n = wa(text = "Same text", messages = listOf(RawMessage("Same text", 5000, "Alice")))
        assertEquals(1, engine.process(n).added)
        val second = engine.process(n)
        assertEquals(0, second.added)
        assertEquals(1, second.duplicates)
        assertEquals(RejectReason.DUPLICATE, second.rejected)
        assertEquals(1, store.pendingMessages().size)
    }

    @Test fun messagingStyleRepostsOfEarlierMessagesAreNotDuplicated() = runTest {
        group("Project Updates")
        val m1 = RawMessage("one", 1000, "Alice"); val m2 = RawMessage("two", 2000, "Bob"); val m3 = RawMessage("three", 3000, "Alice")
        engine.process(wa(messages = listOf(m1)))
        engine.process(wa(messages = listOf(m1, m2)))
        val o = engine.process(wa(messages = listOf(m1, m2, m3)))
        assertEquals(1, o.added)
        assertEquals(2, o.duplicates)
        assertEquals(listOf("one", "two", "three"), store.pendingMessages().map { it.originalText })
    }

    @Test fun legitimateRepeatedTextWithDifferentTimestampsIsKept() = runTest {
        group("Project Updates")
        engine.process(wa(messages = listOf(RawMessage("ok", 1000, "Alice"))))
        engine.process(wa(messages = listOf(RawMessage("ok", 9000, "Alice"))))
        assertEquals(2, store.pendingMessages().size)
    }

    @Test fun untimedRepeatInsideWindowIsDuplicateButLaterRepeatIsKept() = runTest {
        group("Project Updates")
        val n = wa(text = "Alice: Standup now")
        assertEquals(1, engine.process(n).added)
        clock.now += 30_000
        assertEquals(0, engine.process(n).added)
        clock.now += 3 * 60_000
        assertEquals(1, engine.process(n).added)
        assertEquals(2, store.pendingMessages().size)
    }

    @Test fun deletedOrSentMessagesAreNotResurrectedByAReposting() = runTest {
        group("Project Updates")
        val n = wa(messages = listOf(RawMessage("one", 1000, "Alice")))
        engine.process(n)
        store.deleteMessages(listOf(store.pendingMessages().single().id))
        assertEquals(0, engine.process(n).added)
        assertTrue(store.pendingMessages().isEmpty())
    }

    @Test fun differentSendersWithSameTextAreDistinct() = runTest {
        group("Project Updates")
        engine.process(wa(text = "Alice: +1"))
        engine.process(wa(text = "Bob: +1"))
        assertEquals(2, store.pendingMessages().size)
    }

    @Test fun senderAndTimestampAreStored() = runTest {
        group("Project Updates")
        engine.process(wa(messages = listOf(RawMessage("hello", 4242, "Alice"))))
        val m = store.pendingMessages().single()
        assertEquals("Alice", m.senderName)
        assertEquals(4242L, m.receivedAt)
    }

    @Test fun diagnosticsCanBeSwitchedOff() = runTest {
        cfg = cfg.copy(diagnosticsEnabled = false)
        engine.process(wa(text = "hi"))
        assertTrue(store.diagnostics().isEmpty())
    }

    private val marketPost = "Keep your eye out on Kospi\nKospi -3.65%\n\nNifty weekly expiry today\nNifty -157 pts\n\nResult Reaction\nHCL Tech; Neutral\n\n#Cues #Markets"

    @Test fun oneNotificationWithSeveralBlocksBecomesSeparateInboxMessagesInOrder() = runTest {
        group("Project Updates")
        val o = engine.process(wa(text = marketPost))
        assertEquals(4, o.added)
        assertEquals(
            listOf("Keep your eye out on Kospi\nKospi -3.65%", "Nifty weekly expiry today\nNifty -157 pts", "Result Reaction\nHCL Tech; Neutral", "#Cues #Markets"),
            store.pendingMessages().map { it.originalText },
        )
    }

    @Test fun splitPartsOfMessagingStyleEntriesStayOrderedAndAreNotDuplicatedOnRepost() = runTest {
        group("Project Updates")
        val n = wa(messages = listOf(RawMessage("one\n\ntwo", 1000, "Alice"), RawMessage("three", 5000, "Bob")))
        assertEquals(3, engine.process(n).added)
        assertEquals(0, engine.process(n).added)
        assertEquals(listOf("one", "two", "three"), store.pendingMessages().map { it.originalText })
    }

    @Test fun splittingCanBeSwitchedOff() = runTest {
        group("Project Updates")
        cfg = cfg.copy(splitAtBlankLines = false)
        assertEquals(1, engine.process(wa(text = marketPost)).added)
        assertEquals(marketPost, store.pendingMessages().single().originalText)
    }
}
