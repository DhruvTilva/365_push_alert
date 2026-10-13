package com.dhruvtilva.pushalert.discord

import com.dhruvtilva.pushalert.FakeTransport
import com.dhruvtilva.pushalert.TestClock
import com.dhruvtilva.pushalert.VALID_WEBHOOK
import com.dhruvtilva.pushalert.domain.AddResult
import com.dhruvtilva.pushalert.domain.AppSettings
import com.dhruvtilva.pushalert.domain.MessageStatus
import com.dhruvtilva.pushalert.domain.NewMessage
import com.dhruvtilva.pushalert.domain.ValidationResult
import com.dhruvtilva.pushalert.http
import com.dhruvtilva.pushalert.newStore
import com.dhruvtilva.pushalert.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatchPublisherTest {
    private val store = newStore(clock = TestClock())
    private val transport = FakeTransport()
    private val client = DiscordClient(transport, Dispatchers.Unconfined)
    private var hook: WebhookUrl? = (WebhookUrl.parse(VALID_WEBHOOK) as WebhookUrl.ParseResult.Valid).url
    private var settings = AppSettings(dividerTemplate = "")
    private var n = 0

    private fun TestScope.publisher() = BatchPublisher(store, client, { hook }, { settings }, this)

    private suspend fun add(text: String): Long =
        (store.addIfNew(NewMessage("com.whatsapp", "Project Updates", null, null, "Alice", text, 1000L + n, "fp${n++}", true, false)) as AddResult.Added).id

    private fun bodies() = transport.requests.map { it.body!! }
    private fun rl(seconds: String) = http(429, """{"message":"rate limited","retry_after":$seconds,"global":false}""")

    private suspend fun TestScope.run(p: BatchPublisher, ids: List<Long>): BatchProgress {
        assertTrue(p.start(ids) is StartResult.Started)
        advanceUntilIdle()
        return p.progress.value
    }

    @Test fun successfulBatchSendsEachMessageSeparatelyInOrderAndMovesThemToHistory() = runTest {
        val ids = listOf(add("m1"), add("m2"), add("m3"))
        val p = run(publisher(), ids)
        assertEquals(BatchProgress.State.FINISHED, p.state)
        assertEquals(3, p.sent); assertEquals(0, p.failed); assertEquals(0, p.remaining)
        assertEquals(3, transport.requests.size)
        assertTrue(bodies()[0].contains("\"m1\"") && bodies()[1].contains("\"m2\"") && bodies()[2].contains("\"m3\""))
        assertTrue(transport.requests.all { it.method == "POST" && it.url.endsWith("?wait=true") })
        assertTrue(store.pendingMessages().isEmpty())
        assertEquals(listOf("m3", "m2", "m1").toSet(), store.history().map { it.publishedText }.toSet())
        assertEquals("900", store.history().first().discordMessageId)
    }

    @Test fun publishOrderFollowsTheOrderGiven() = runTest {
        val a = add("a"); val b = add("b"); val c = add("c")
        run(publisher(), listOf(c, a, b))
        assertEquals(listOf("c", "a", "b"), bodies().map { Regex("\"content\":\"(.*?)\"").find(it)!!.groupValues[1] })
    }

    @Test fun missingWebhookRefusesToStart() = runTest {
        val id = add("m1"); hook = null
        assertEquals(StartResult.NoWebhook, publisher().start(listOf(id)))
        assertEquals(StartResult.NothingToSend, publisher().start(emptyList()))
        assertTrue(transport.requests.isEmpty())
        assertEquals(MessageStatus.PENDING, store.getMessage(id)!!.status)
    }

    @Test fun editedTextIsPublishedNotTheOriginal() = runTest {
        val id = add("original words"); store.editText(id, "edited words")
        run(publisher(), listOf(id))
        assertTrue(bodies().single().contains("edited words"))
        assertFalse(bodies().single().contains("original words"))
        assertEquals("edited words", store.history().single().publishedText)
    }

    @Test fun http400KeepsMessageForRetryAndContinuesWithTheRest() = runTest {
        val ids = listOf(add("m1"), add("m2"), add("m3"))
        transport.enqueue(ok(), http(400, """{"content":["bad"]}"""), ok())
        val p = run(publisher(), ids)
        assertEquals(2, p.sent); assertEquals(1, p.failed); assertEquals(0, p.remaining)
        val failed = store.getMessage(ids[1])!!
        assertEquals(MessageStatus.FAILED, failed.status)
        assertTrue(failed.lastError!!.contains("400"))
        assertEquals(1, store.pendingMessages().size)
    }

    @Test fun invalidWebhookStatusesStopTheBatchAndLeaveTheRestUntouched() = runTest {
        for (code in listOf(401, 403, 404)) {
            transport.requests.clear()
            val ids = listOf(add("a$code"), add("b$code"), add("c$code"))
            transport.enqueue(http(code))
            val p = run(publisher(), ids)
            assertEquals("$code", 1, transport.requests.size)
            assertEquals(1, p.failed); assertEquals(0, p.sent); assertEquals(2, p.remaining)
            assertTrue(p.stopReason!!.contains("webhook"))
            assertEquals(MessageStatus.FAILED, store.getMessage(ids[0])!!.status)
            assertEquals(MessageStatus.PENDING, store.getMessage(ids[1])!!.status)
            assertEquals(MessageStatus.PENDING, store.getMessage(ids[2])!!.status)
            store.deleteAllPending()
        }
    }

    @Test fun rateLimitWaitsForRetryAfterThenRetriesTheSameMessage() = runTest {
        val id = add("m1")
        transport.enqueue(rl("2.5"), ok())
        val p = run(publisher(), listOf(id))
        assertEquals(1, p.sent)
        assertEquals(2, transport.requests.size)
        assertEquals(bodies()[0], bodies()[1])
        assertTrue("waited ${currentTime}ms", currentTime >= 2500)
        assertEquals(1, store.history().size)
    }

    @Test fun persistentRateLimitIsBoundedAndFailsTheMessage() = runTest {
        val ids = listOf(add("m1"), add("m2"))
        repeat(10) { transport.enqueue(rl("0.1")) }
        val p = run(publisher(), ids)
        assertEquals(BatchPublisher.MAX_RATE_LIMIT_ATTEMPTS, transport.requests.size)
        assertEquals(MessageStatus.FAILED, store.getMessage(ids[0])!!.status)
        assertEquals(MessageStatus.PENDING, store.getMessage(ids[1])!!.status)
        assertTrue(p.stopReason!!.contains("rate limiting"))
    }

    @Test fun veryLongRetryAfterFailsFastInsteadOfBlocking() = runTest {
        val id = add("m1")
        transport.enqueue(rl("3600"))
        val p = run(publisher(), listOf(id))
        assertEquals(1, transport.requests.size)
        assertEquals(1, p.failed)
        assertTrue(currentTime < 5_000)
    }

    @Test fun serverErrorIsRetriedWithBackoffThenSucceeds() = runTest {
        val id = add("m1")
        transport.enqueue(http(500), http(503), ok())
        val p = run(publisher(), listOf(id))
        assertEquals(1, p.sent)
        assertEquals(3, transport.requests.size)
        assertTrue(currentTime >= 3000)
    }

    @Test fun persistentServerErrorFailsAfterBoundedAttempts() = runTest {
        val ids = listOf(add("m1"), add("m2"))
        repeat(10) { transport.enqueue(http(500)) }
        val p = run(publisher(), ids)
        assertEquals(BatchPublisher.MAX_SERVER_ATTEMPTS, transport.requests.size)
        assertEquals(MessageStatus.FAILED, store.getMessage(ids[0])!!.status)
        assertEquals(MessageStatus.PENDING, store.getMessage(ids[1])!!.status)
        assertEquals(1, p.failed)
    }

    @Test fun networkTimeoutAfterSendingIsUncertainAndNeverRetriedAutomatically() = runTest {
        val ids = listOf(add("m1"), add("m2"))
        transport.enqueue(HttpResult.Uncertain(UncertainKind.TIMEOUT), ok())
        val p = run(publisher(), ids)
        assertEquals(1, transport.requests.size)
        assertEquals(1, p.uncertain); assertEquals(0, p.failed)
        val m = store.getMessage(ids[0])!!
        assertEquals(MessageStatus.DELIVERY_UNCERTAIN, m.status)
        assertTrue(m.lastError!!.contains("may or may not"))
        assertEquals(MessageStatus.PENDING, store.getMessage(ids[1])!!.status)
        assertTrue(p.stopReason!!.contains("uncertain"))
        assertTrue(store.history().isEmpty())
    }

    @Test fun uncertainMessageCanBeRetriedOnlyByExplicitDecision() = runTest {
        val id = add("m1")
        transport.enqueue(HttpResult.Uncertain(UncertainKind.CONNECTION_LOST))
        val pub = publisher()
        run(pub, listOf(id))
        assertEquals(MessageStatus.DELIVERY_UNCERTAIN, store.getMessage(id)!!.status)

        val pre = pub.preflight(listOf(id))
        assertEquals(listOf(id), pre.uncertain.map { it.id })
        run(pub, listOf(id))
        assertEquals(2, transport.requests.size)
        assertEquals(null, store.getMessage(id))
        assertEquals(1, store.history().size)
    }

    @Test fun offlineDeviceFailsWithoutSendingAndKeepsMessagesRetryable() = runTest {
        val ids = listOf(add("m1"), add("m2"))
        transport.enqueue(HttpResult.NotSent(NotSentKind.NO_NETWORK))
        val pub = publisher()
        val p = run(pub, ids)
        assertEquals(1, transport.requests.size)
        assertEquals(1, p.failed); assertEquals(1, p.remaining)
        assertTrue(store.getMessage(ids[0])!!.lastError!!.contains("No internet"))
        assertEquals(2, store.pendingMessages().size)

        // Back online: retry both.
        pub.dismiss()
        val again = run(pub, ids)
        assertEquals(2, again.sent)
        assertTrue(store.pendingMessages().isEmpty())
    }

    @Test fun unexpectedExceptionIsTreatedAsUncertainNotSilentlyLost() = runTest {
        val id = add("m1")
        transport.onRequest = { _, _ -> throw IllegalStateException("boom") }
        val p = run(publisher(), listOf(id))
        assertEquals(1, p.uncertain)
        assertEquals(MessageStatus.DELIVERY_UNCERTAIN, store.getMessage(id)!!.status)
    }

    @Test fun secondStartWhileRunningIsRefusedSoNoDuplicateBatchCanExist() = runTest {
        val ids = listOf(add("m1"), add("m2"))
        val pub = publisher()
        assertTrue(pub.start(ids) is StartResult.Started)
        assertEquals(StartResult.AlreadyRunning, pub.start(ids))
        assertEquals(StartResult.AlreadyRunning, pub.start(ids))
        advanceUntilIdle()
        assertEquals(2, transport.requests.size)
        assertEquals(2, pub.progress.value.sent)
        // Once finished a new batch may start again (nothing left to send here, so message is skipped).
        assertTrue(pub.start(ids) is StartResult.Started)
        advanceUntilIdle()
        assertEquals(2, transport.requests.size)
    }

    @Test fun cancellationStopsAfterTheCurrentMessageAndKeepsPublishedOnesPublished() = runTest {
        val ids = listOf(add("m1"), add("m2"), add("m3"))
        val pub = publisher()
        transport.onRequest = { _, count -> if (count == 1) pub.cancel() }
        val p = run(pub, ids)
        assertEquals(1, transport.requests.size)
        assertEquals(1, p.sent); assertEquals(2, p.remaining)
        assertTrue(p.stopReason!!.contains("Cancelled"))
        assertEquals(1, store.history().size)
        assertEquals(listOf(MessageStatus.PENDING, MessageStatus.PENDING), store.pendingMessages().map { it.status })
    }

    @Test fun cancellingDuringARateLimitWaitReleasesTheMessageWithoutFailingIt() = runTest {
        val id = add("m1")
        val pub = publisher()
        transport.enqueue(rl("30"))
        transport.onRequest = { _, _ -> pub.cancel() }
        val p = run(pub, listOf(id))
        assertEquals(1, transport.requests.size)
        assertEquals(0, p.failed)
        assertEquals(MessageStatus.PENDING, store.getMessage(id)!!.status)
    }

    @Test fun partialSuccessReportsExactCounts() = runTest {
        val ids = (1..5).map { add("m$it") }
        transport.enqueue(ok(), ok(), http(400), ok(), HttpResult.Uncertain(UncertainKind.TIMEOUT))
        val p = run(publisher(), ids + add("never"))
        assertEquals(3, p.sent); assertEquals(1, p.failed); assertEquals(1, p.uncertain)
        assertEquals(1, p.remaining)
        assertEquals(6, p.total)
        assertEquals(3, store.history().size)
        assertEquals(3, store.pendingMessages().size)
    }

    @Test fun aKnownFailedMessageCanBeEditedAndRetried() = runTest {
        val id = add("too spicy")
        transport.enqueue(http(400))
        val pub = publisher()
        run(pub, listOf(id))
        assertEquals(MessageStatus.FAILED, store.getMessage(id)!!.status)
        store.editText(id, "toned down")
        pub.dismiss()
        run(pub, listOf(id))
        assertEquals(null, store.getMessage(id))
        assertEquals("toned down", store.history().single().publishedText)
    }

    @Test fun overlongMessagesAreFlaggedByPreflightAndNeverSent() = runTest {
        val long = add("x".repeat(2001))
        val ok = add("fine")
        val pub = publisher()
        val pre = pub.preflight(listOf(long, ok))
        assertEquals(1, pre.invalid.size)
        assertTrue(pre.invalid.single().second is ValidationResult.TooLong)
        // Even if a caller ignores preflight, the batch itself refuses to send it.
        val p = run(pub, listOf(long, ok))
        assertEquals(1, transport.requests.size)
        assertEquals(1, p.failed); assertEquals(1, p.sent)
        assertEquals(MessageStatus.FAILED, store.getMessage(long)!!.status)
    }

    @Test fun historyCanBeDisabledSoSentContentIsDeletedImmediately() = runTest {
        settings = settings.copy(historyEnabled = false)
        val id = add("sensitive")
        run(publisher(), listOf(id))
        assertEquals(1, transport.requests.size)
        assertTrue(store.history().isEmpty())
        assertEquals(null, store.getMessage(id))
    }

    @Test fun alreadySentOrDeletedIdsAreSkippedNotResent() = runTest {
        val a = add("a"); val b = add("b")
        val pub = publisher()
        run(pub, listOf(a))
        pub.dismiss()
        val p = run(pub, listOf(a, b))
        assertEquals(2, transport.requests.size) // a once, b once
        assertEquals(1, p.skipped); assertEquals(1, p.sent)
    }

    @Test fun preflightOnlyListsSendableMessages() = runTest {
        val a = add("a"); val b = add("b")
        store.beginSending(b)
        val pre = publisher().preflight(listOf(a, b, 999))
        assertEquals(listOf(a), pre.messages.map { it.id })
        assertTrue(pre.webhookConfigured)
        assertNotNull(pre)
    }

    @Test fun rateLimitBucketHeaderSlowsDownTheNextMessage() = runTest {
        val ids = listOf(add("m1"), add("m2"))
        transport.enqueue(ok("1", mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset-after" to "1.0")), ok("2"))
        run(publisher(), ids)
        assertTrue("waited ${currentTime}ms", currentTime >= 1000)
    }

    @Test fun headingDividerIsAddedToEveryMessageWithRunningNumbers() = runTest {
        settings = settings.copy(dividerTemplate = "### Terminal365 Update #{n}", nextUpdateNumber = 7)
        val ids = listOf(add("m1"), add("m2"))
        var published = 0
        val pub = BatchPublisher(store, client, { hook }, { settings.copy(nextUpdateNumber = 7L + published) }, this, onPublished = { published++ })
        run(pub, ids)
        assertEquals(2, transport.requests.size)
        assertTrue(bodies()[0], bodies()[0].contains("\"content\":\"### Terminal365 Update #7\\n\\nm1\""))
        assertTrue(bodies()[1], bodies()[1].contains("\"content\":\"### Terminal365 Update #8\\n\\nm2\""))
        assertFalse(bodies()[0].contains("Project Updates") || bodies()[0].contains("Alice"))
        assertEquals("m1", store.history().last().publishedText)
        assertEquals(2, published)
    }

    @Test fun updateNumberIsNotConsumedByFailedMessages() = runTest {
        settings = settings.copy(dividerTemplate = AppSettings.DEFAULT_DIVIDER)
        val id = add("m1")
        var published = 0
        val pub = BatchPublisher(store, client, { hook }, { settings.copy(nextUpdateNumber = 1L + published) }, this, onPublished = { published++ })
        transport.enqueue(http(400))
        run(pub, listOf(id))
        assertEquals(0, published)
        pub.dismiss()
        run(pub, listOf(id))
        assertTrue(bodies().last().contains("Terminal365 Update #1\\n\\n"))
        assertEquals(1, published)
    }

    @Test fun headerCountsTowardTheDiscordLimitAndIsExplained() = runTest {
        settings = settings.copy(dividerTemplate = AppSettings.DEFAULT_DIVIDER)
        val id = add("x".repeat(1990))
        val pub = publisher()
        assertEquals(1, pub.preflight(listOf(id)).invalid.size)
        run(pub, listOf(id))
        assertEquals(0, transport.requests.size)
        assertTrue(store.getMessage(id)!!.lastError!!.contains("divider"))
        settings = settings.copy(dividerTemplate = "")
        pub.dismiss()
        run(pub, listOf(id))
        assertEquals(1, transport.requests.size)
    }
}
