package com.dhruvtilva.pushalert.discord

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tiny JSON helpers; avoids org.json so the code runs in plain JVM unit tests. */
internal object Json {
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    fun stringField(body: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)

    fun numberField(body: String, key: String): Double? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
}

sealed interface PostOutcome {
    /** Discord confirmed creation (HTTP 200 with wait=true, or 204). */
    data class Delivered(val messageId: String?, val rateLimitResetAfterMs: Long?) : PostOutcome
    /** HTTP 429: Discord did not process the request, so retrying is safe. */
    data class RateLimited(val retryAfterMs: Long) : PostOutcome
    /** HTTP 4xx other than auth/not-found: the content itself was refused. Permanent for this text. */
    data class Rejected(val httpCode: Int, val message: String) : PostOutcome
    /** HTTP 401/403/404: the webhook is invalid, revoked or deleted. */
    data class WebhookInvalid(val httpCode: Int, val message: String) : PostOutcome
    /** HTTP 5xx. */
    data class ServerError(val httpCode: Int) : PostOutcome
    /** Failed before anything was sent (offline, DNS, TLS, connect timeout). */
    data class NotSent(val message: String) : PostOutcome
    /** Failed after the request was sent: Discord may or may not have created the message. */
    data class Uncertain(val message: String) : PostOutcome
}

sealed interface TestOutcome {
    data class Ok(val webhookName: String?) : TestOutcome
    data class Failed(val message: String) : TestOutcome
}

class DiscordClient(
    private val transport: HttpTransport,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun post(webhook: WebhookUrl, content: String): PostOutcome {
        val body = "{\"content\":${Json.quote(content)},\"allowed_mentions\":{\"parse\":[]}}"
        val result = withContext(dispatcher) { transport.execute(HttpRequest("POST", webhook.executeUrl(), body)) }
        return classify(result)
    }

    /** Reads webhook metadata with GET; does not post anything to the channel. */
    suspend fun test(webhook: WebhookUrl): TestOutcome {
        val result = withContext(dispatcher) { transport.execute(HttpRequest("GET", webhook.fullUrl())) }
        return when (result) {
            is HttpResult.NotSent -> TestOutcome.Failed(notSentMessage(result.kind))
            is HttpResult.Uncertain -> TestOutcome.Failed("The connection to Discord was interrupted. Try again.")
            is HttpResult.Response -> when (result.code) {
                200 -> TestOutcome.Ok(Json.stringField(result.body, "name"))
                401, 403, 404 -> TestOutcome.Failed("Discord rejected this webhook (HTTP ${result.code}). It may have been deleted; create a new one.")
                429 -> TestOutcome.Failed("Discord is rate limiting this webhook. Wait a moment and try again.")
                in 500..599 -> TestOutcome.Failed("Discord had a server problem (HTTP ${result.code}). Try again later.")
                else -> TestOutcome.Failed("Unexpected response from Discord (HTTP ${result.code}).")
            }
        }
    }

    internal fun classify(result: HttpResult): PostOutcome = when (result) {
        is HttpResult.NotSent -> PostOutcome.NotSent(notSentMessage(result.kind))
        is HttpResult.Uncertain -> PostOutcome.Uncertain(
            when (result.kind) {
                UncertainKind.TIMEOUT -> "Timed out waiting for Discord's answer. The message may or may not have been posted."
                UncertainKind.CONNECTION_LOST -> "Connection dropped while sending. The message may or may not have been posted."
            },
        )
        is HttpResult.Response -> classifyResponse(result)
    }

    private fun classifyResponse(r: HttpResult.Response): PostOutcome = when (r.code) {
        200, 201, 204 -> {
            val remaining = r.headers["x-ratelimit-remaining"]?.trim()
            val resetMs = r.headers["x-ratelimit-reset-after"]?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            PostOutcome.Delivered(
                messageId = Json.stringField(r.body, "id"),
                rateLimitResetAfterMs = if (remaining == "0") resetMs else null,
            )
        }
        429 -> {
            val seconds = Json.numberField(r.body, "retry_after")
                ?: r.headers["retry-after"]?.toDoubleOrNull()
                ?: 1.0
            PostOutcome.RateLimited((seconds * 1000).toLong().coerceAtLeast(0L))
        }
        401, 403, 404 -> PostOutcome.WebhookInvalid(
            r.code, "Discord rejected the webhook (HTTP ${r.code}). Check the webhook in Settings.",
        )
        in 500..599 -> PostOutcome.ServerError(r.code)
        else -> PostOutcome.Rejected(r.code, "Discord refused the message (HTTP ${r.code}).")
    }

    private fun notSentMessage(kind: NotSentKind): String = when (kind) {
        NotSentKind.NO_NETWORK -> "No internet connection. Nothing was sent."
        NotSentKind.DNS -> "Could not find Discord (DNS failure). Nothing was sent."
        NotSentKind.CONNECT_TIMEOUT -> "Could not connect to Discord in time. Nothing was sent."
        NotSentKind.TLS -> "Secure connection to Discord failed. Nothing was sent."
        NotSentKind.OTHER -> "Could not reach Discord. Nothing was sent."
    }
}
