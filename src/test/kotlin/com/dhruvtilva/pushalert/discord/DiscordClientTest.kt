package com.dhruvtilva.pushalert.discord

import com.dhruvtilva.pushalert.FakeTransport
import com.dhruvtilva.pushalert.VALID_WEBHOOK
import com.dhruvtilva.pushalert.http
import com.dhruvtilva.pushalert.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordClientTest {
    private val hook = (WebhookUrl.parse(VALID_WEBHOOK) as WebhookUrl.ParseResult.Valid).url
    private val transport = FakeTransport()
    private val client = DiscordClient(transport, Dispatchers.Unconfined)

    private suspend fun post(vararg r: HttpResult): PostOutcome {
        transport.enqueue(*r)
        return client.post(hook, "hi")
    }

    @Test fun http200WithBodyIsDeliveredWithMessageId() = runTest {
        assertEquals(PostOutcome.Delivered("42", null), post(ok("42")))
    }

    @Test fun http204IsDeliveredWithoutId() = runTest {
        assertEquals(PostOutcome.Delivered(null, null), post(http(204)))
    }

    @Test fun exhaustedBucketHeaderIsSurfaced() = runTest {
        val r = post(ok("1", mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset-after" to "1.5")))
        assertEquals(PostOutcome.Delivered("1", 1500L), r)
        assertEquals(PostOutcome.Delivered("2", null), post(ok("2", mapOf("x-ratelimit-remaining" to "3", "x-ratelimit-reset-after" to "1.5"))))
    }

    @Test fun http400IsAPermanentRejection() = runTest {
        assertTrue(post(http(400, """{"content":["Must be 2000 or fewer in length."]}""")) is PostOutcome.Rejected)
    }

    @Test fun authAndNotFoundMeanTheWebhookIsInvalid() = runTest {
        listOf(401, 403, 404).forEach { assertTrue("$it", post(http(it)) is PostOutcome.WebhookInvalid) }
    }

    @Test fun http429UsesRetryAfterFromBody() = runTest {
        assertEquals(PostOutcome.RateLimited(2500), post(http(429, """{"message":"You are being rate limited.","retry_after":2.5,"global":false}""")))
    }

    @Test fun http429FallsBackToHeaderThenToOneSecond() = runTest {
        assertEquals(PostOutcome.RateLimited(3000), post(http(429, "", mapOf("retry-after" to "3"))))
        assertEquals(PostOutcome.RateLimited(1000), post(http(429, "garbage")))
    }

    @Test fun serverErrors() = runTest {
        listOf(500, 502, 503, 504).forEach { assertEquals(PostOutcome.ServerError(it), post(http(it))) }
    }

    @Test fun unexpectedStatusIsRejectedNotTreatedAsSuccess() = runTest {
        assertTrue(post(http(302)) is PostOutcome.Rejected)
        assertTrue(post(http(418)) is PostOutcome.Rejected)
    }

    @Test fun failuresBeforeSendingAreNotSent() = runTest {
        NotSentKind.values().forEach { kind ->
            val r = post(HttpResult.NotSent(kind))
            assertTrue("$kind", r is PostOutcome.NotSent)
            assertTrue((r as PostOutcome.NotSent).message.contains("Nothing was sent"))
        }
    }

    @Test fun failuresAfterSendingAreUncertain() = runTest {
        UncertainKind.values().forEach { assertTrue(post(HttpResult.Uncertain(it)) is PostOutcome.Uncertain) }
    }

    @Test fun payloadEscapesContentAndSuppressesMentions() = runTest {
        transport.enqueue(ok())
        client.post(hook, "Say \"hi\"\nline2 \\ @everyone \u0001")
        val req = transport.requests.single()
        assertEquals("POST", req.method)
        assertTrue(req.url.endsWith("?wait=true"))
        assertEquals("""{"content":"Say \"hi\"\nline2 \\ @everyone \u0001","allowed_mentions":{"parse":[]}}""", req.body)
    }

    @Test fun testConnectionDoesNotPostAnything() = runTest {
        transport.enqueue(http(200, """{"id":"1","name":"Alerts","channel_id":"2"}"""))
        assertEquals(TestOutcome.Ok("Alerts"), client.test(hook))
        val req = transport.requests.single()
        assertEquals("GET", req.method)
        assertEquals(null, req.body)
        assertFalse(req.url.contains("wait"))
    }

    @Test fun testConnectionFailureMessagesNeverContainTheToken() = runTest {
        val token = VALID_WEBHOOK.substringAfterLast('/')
        listOf(http(404), http(401), http(429), http(503), http(418), HttpResult.NotSent(NotSentKind.DNS), HttpResult.Uncertain(UncertainKind.TIMEOUT)).forEach {
            transport.enqueue(it)
            val r = client.test(hook)
            assertTrue(r is TestOutcome.Failed)
            assertFalse((r as TestOutcome.Failed).message.contains(token))
        }
    }

    @Test fun jsonQuoteHandlesControlCharacters() {
        assertEquals("\"a\\tb\\r\\n\\u001f\"", Json.quote("a\tb\r\n\u001f"))
        assertEquals("\"héllo 😀\"", Json.quote("héllo 😀"))
    }
}
