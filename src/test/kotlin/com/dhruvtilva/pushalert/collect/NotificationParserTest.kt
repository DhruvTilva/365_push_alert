package com.dhruvtilva.pushalert.collect

import com.dhruvtilva.pushalert.wa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParserTest {
    private val parser = NotificationParser()
    private val pkgs = setOf("com.whatsapp", "com.whatsapp.w4b")

    private fun parse(n: RawNotification) = parser.parse(n, pkgs)
    private fun ok(n: RawNotification) = (parse(n) as ParseResult.Success).notification
    private fun rejected(n: RawNotification) = (parse(n) as ParseResult.Rejected).reason

    @Test fun normalSingleTextMessage() {
        val n = ok(wa(title = "AI/ML Updates", text = "New AI model released today."))
        assertEquals(listOf("New AI model released today."), n.messages.map { it.text })
        assertTrue("AI/ML Updates" in n.titleCandidates)
        assertEquals("com.whatsapp", n.packageName)
    }

    @Test fun multilineMessageIsPreserved() {
        val n = ok(wa(text = "Agenda:", bigText = "Agenda:\n1. Deploy\n2. Review"))
        assertEquals("Agenda:\n1. Deploy\n2. Review", n.messages.single().text)
    }

    @Test fun missingTitleIsRejected() {
        assertEquals(RejectReason.NO_TITLE, rejected(wa(title = null, text = "hi")))
        assertEquals(RejectReason.NO_TITLE, rejected(wa(title = "   ", text = "hi")))
    }

    @Test fun missingTextIsRejected() {
        assertEquals(RejectReason.NO_TEXT, rejected(wa(text = null)))
        assertEquals(RejectReason.NO_TEXT, rejected(wa(text = "  \n ")))
    }

    @Test fun groupSummaryFlagIsRejected() {
        assertEquals(RejectReason.SUMMARY, rejected(wa(title = "WhatsApp", text = "5 new messages from 2 chats", summary = true)))
    }

    @Test fun summaryCountTextWithoutFlagIsRejectedNotTreatedAsMessage() {
        assertEquals(RejectReason.SUMMARY, rejected(wa(text = "5 new messages")))
        assertEquals(RejectReason.SUMMARY, rejected(wa(title = "3 new messages", text = "Alice: hi")))
    }

    @Test fun imageOnlyNotificationIsIgnored() {
        assertEquals(RejectReason.ATTACHMENT_ONLY, rejected(wa(text = "📷 Photo")))
        assertEquals(RejectReason.ATTACHMENT_ONLY, rejected(wa(text = "Photo")))
        assertEquals(RejectReason.ATTACHMENT_ONLY, rejected(wa(text = "Alice: 📷 Photo")))
    }

    @Test fun videoOnlyNotificationIsIgnored() {
        assertEquals(RejectReason.ATTACHMENT_ONLY, rejected(wa(text = "🎥 Video")))
        assertEquals(RejectReason.ATTACHMENT_ONLY, rejected(wa(text = "Video (0:12)")))
    }

    @Test fun voiceMessageNotificationIsIgnored() {
        assertEquals(RejectReason.ATTACHMENT_ONLY, rejected(wa(text = "🎤 Voice message (0:13)")))
        assertEquals(RejectReason.ATTACHMENT_ONLY, rejected(wa(text = "Voice message")))
    }

    @Test fun stickerDocumentGifAndLocationPlaceholdersAreIgnored() {
        listOf("Sticker", "GIF", "Document", "📄 report.pdf", "Location", "<Media omitted>", "2 photos").forEach {
            assertEquals(it, RejectReason.ATTACHMENT_ONLY, rejected(wa(text = it)))
        }
    }

    @Test fun ordinaryTextMentioningMediaWordsIsKept() {
        listOf("Please check the image I sent", "video call tomorrow at 11 AM", "The photo contest is open", "Voice message from CEO arrives soon").forEach {
            assertEquals(it, listOf(it), ok(wa(text = it)).messages.map { m -> m.text })
        }
    }

    @Test fun ambiguousMediaMarkerWithCaptionIsRejected() {
        assertEquals(RejectReason.ATTACHMENT_ONLY, rejected(wa(text = "📷 Look at this chart")))
    }

    @Test fun notificationFromAnotherAppIsRejected() {
        assertEquals(RejectReason.NOT_WHATSAPP, rejected(wa(pkg = "com.example.chat")))
    }

    @Test fun alternativeConfiguredPackageIsAccepted() {
        assertEquals("com.whatsapp.w4b", ok(wa(pkg = "com.whatsapp.w4b")).packageName)
    }

    @Test fun genericTitleIsRejected() {
        assertEquals(RejectReason.GENERIC_TITLE, rejected(wa(title = "WhatsApp", text = "hello")))
        assertEquals(RejectReason.GENERIC_TITLE, rejected(wa(title = "whatsapp business", text = "hello")))
    }

    @Test fun ongoingNotificationIsRejected() {
        assertEquals(RejectReason.ONGOING, rejected(wa(ongoing = true, text = "WhatsApp Web is currently active")))
    }

    @Test fun deletedMessagePlaceholderIsNotCollected() {
        assertEquals(RejectReason.NO_TEXT, rejected(wa(text = "This message was deleted")))
    }

    @Test fun truncatedTextIsFlagged() {
        val m = ok(wa(text = "This update is very long and was cut off by the…")).messages.single()
        assertTrue(m.possiblyTruncated)
    }

    @Test fun bigTextReplacesTruncatedText() {
        val m = ok(wa(text = "This update is long…", bigText = "This update is long and complete.")).messages.single()
        assertEquals("This update is long and complete.", m.text)
        assertFalse(m.possiblyTruncated)
    }

    @Test fun unrelatedBigTextDoesNotOverrideText() {
        assertEquals("actual text", ok(wa(text = "actual text", bigText = "something else entirely")).messages.single().text)
    }

    @Test fun bundledMessagingStyleYieldsOneMessageEach() {
        val n = ok(
            wa(
                conversationTitle = "Project Updates", isGroup = true, title = "Project Updates (3 messages)", text = "3 new messages",
                messages = listOf(
                    RawMessage("First", 1000, "Alice"),
                    RawMessage("📷 Photo", 2000, "Bob"),
                    RawMessage("Second", 3000, "Carol"),
                    RawMessage(null, 4000, "Dan"),
                ),
            ),
        )
        assertEquals(listOf("First", "Second"), n.messages.map { it.text })
        assertEquals(listOf("Alice", "Carol"), n.messages.map { it.sender })
        assertEquals(listOf(1000L, 3000L), n.messages.map { it.timeMs })
        assertEquals(1, n.skippedAttachments)
        assertEquals(ConversationKind.GROUP, n.kind)
        assertTrue("Project Updates" in n.titleCandidates)
    }

    @Test fun bundledNotificationWithOnlyAttachmentsIsRejected() {
        val r = rejected(wa(messages = listOf(RawMessage("Photo", 1, "A"), RawMessage("🎤 Voice message (0:03)", 2, "B"))))
        assertEquals(RejectReason.ATTACHMENT_ONLY, r)
    }

    @Test fun senderPrefixIsSplitForGroupStyleText() {
        val m = ok(wa(title = "Project Updates", text = "Alice: Deploy finished")).messages.single()
        assertEquals("Alice", m.sender)
        assertEquals("Deploy finished", m.text)
    }

    @Test fun senderPrefixIsNotSplitForIndividualChats() {
        val n = ok(wa(title = "Alice", text = "Reminder: submit report", isGroup = false))
        assertEquals(ConversationKind.INDIVIDUAL, n.kind)
        assertEquals("Reminder: submit report", n.messages.single().text)
        assertNull(n.messages.single().sender)
    }

    @Test fun oldStyleSenderAtGroupTitleIsUnderstood() {
        val n = ok(wa(title = "Alice @ Project Updates", text = "Deploy finished"))
        assertTrue("Project Updates" in n.titleCandidates)
        assertEquals("Alice", n.messages.single().sender)
        assertEquals(ConversationKind.GROUP, n.kind)
    }

    @Test fun messageCountSuffixIsStrippedFromTitleCandidates() {
        assertTrue("Project Updates" in ok(wa(title = "Project Updates (5 messages)")).titleCandidates)
    }

    @Test fun groupJidInShortcutIdIdentifiesGroupAndIsExtracted() {
        val n = ok(wa(shortcutId = "120363041234567890@g.us"))
        assertEquals(ConversationKind.GROUP, n.kind)
        assertEquals("120363041234567890@g.us", n.conversationId)
    }

    @Test fun individualJidMarksIndividualChat() {
        assertEquals(ConversationKind.INDIVIDUAL, ok(wa(shortcutId = "15551234567@s.whatsapp.net")).kind)
    }

    @Test fun conflictingSignalsAreUnknownNotGuessed() {
        assertEquals(ConversationKind.UNKNOWN, ok(wa(isGroup = false, shortcutId = "1203@g.us")).kind)
    }

    @Test fun invisibleDirectionMarksAreRemovedFromTitleAndText() {
        val n = ok(wa(title = "‎Project Updates", text = "‎hello"))
        assertEquals("Project Updates", n.titleCandidates.first())
        assertEquals("hello", n.messages.single().text)
    }
}
