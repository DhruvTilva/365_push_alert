package com.dhruvtilva.pushalert.discord

import com.dhruvtilva.pushalert.data.Store
import com.dhruvtilva.pushalert.domain.AppSettings
import com.dhruvtilva.pushalert.domain.PendingMessage
import com.dhruvtilva.pushalert.domain.TextRules
import com.dhruvtilva.pushalert.domain.ValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class BatchProgress(
    val state: State = State.IDLE,
    val total: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val uncertain: Int = 0,
    val skipped: Int = 0,
    val current: Int = 0,
    val cancelRequested: Boolean = false,
    val stopReason: String? = null,
) {
    enum class State { IDLE, RUNNING, FINISHED }

    val processed: Int get() = sent + failed + uncertain + skipped
    /** Messages not attempted (they stay in the inbox untouched). */
    val remaining: Int get() = (total - processed).coerceAtLeast(0)
}

data class Preflight(
    val webhookConfigured: Boolean,
    /** Sendable messages in the requested order. */
    val messages: List<PendingMessage>,
    val invalid: List<Pair<PendingMessage, ValidationResult>>,
    val uncertain: List<PendingMessage>,
)

sealed interface StartResult {
    data class Started(val count: Int) : StartResult
    data object AlreadyRunning : StartResult
    data object NoWebhook : StartResult
    data object NothingToSend : StartResult
}

/**
 * Sends messages to Discord one at a time, in the order given.
 *
 * Delivery rules:
 *  - 200/204: delivered. 429: Discord did not process it, wait `retry_after` and retry (bounded).
 *  - 5xx: bounded retries with backoff, then FAILED.
 *  - 400-class refusals: FAILED, batch continues. 401/403/404: FAILED, batch stops (webhook is bad).
 *  - failure before sending (offline/DNS/TLS): FAILED, batch stops.
 *  - failure after sending (timeout, dropped connection): DELIVERY_UNCERTAIN, never retried
 *    automatically, batch stops so the user can check Discord first.
 */
class BatchPublisher(
    private val store: Store,
    private val client: DiscordClient,
    private val webhook: () -> WebhookUrl?,
    private val settings: () -> AppSettings,
    private val scope: CoroutineScope,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val onPublished: () -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    @Volatile private var cancelRequested = false
    private val state = MutableStateFlow(BatchProgress())
    val progress: StateFlow<BatchProgress> = state.asStateFlow()

    suspend fun preflight(ids: List<Long>): Preflight {
        val loaded = ids.mapNotNull { store.getMessage(it) }.filter { it.status.isSendable }
        val cfg = settings()
        val invalid = loaded.map { it to TextRules.validate(render(it, cfg)) }.filter { !it.second.isValid }
        return Preflight(
            webhookConfigured = webhook() != null,
            messages = loaded,
            invalid = invalid,
            uncertain = loaded.filter { it.status == com.dhruvtilva.pushalert.domain.MessageStatus.DELIVERY_UNCERTAIN },
        )
    }

    /** Starts a batch. Only one batch can run at a time; a second call is refused atomically. */
    fun start(ids: List<Long>): StartResult {
        if (ids.isEmpty()) return StartResult.NothingToSend
        val hook = webhook() ?: return StartResult.NoWebhook
        if (!running.compareAndSet(false, true)) return StartResult.AlreadyRunning
        cancelRequested = false
        state.value = BatchProgress(state = BatchProgress.State.RUNNING, total = ids.size)
        scope.launch {
            try {
                runBatch(ids, hook)
            } finally {
                state.value = state.value.copy(state = BatchProgress.State.FINISHED)
                running.set(false)
            }
        }
        return StartResult.Started(ids.size)
    }

    /** Stops after the message currently in flight. Already delivered messages stay delivered. */
    fun cancel() {
        if (!running.get()) return
        cancelRequested = true
        state.value = state.value.copy(cancelRequested = true)
    }

    /** Clears a finished summary. */
    fun dismiss() {
        if (!running.get()) state.value = BatchProgress()
    }

    private enum class Step { SENT, FAILED, UNCERTAIN, RELEASED }

    private suspend fun runBatch(ids: List<Long>, hook: WebhookUrl) {
        for ((index, id) in ids.withIndex()) {
            if (cancelRequested) {
                update { it.copy(stopReason = "Cancelled. Messages already published stay published.") }
                return
            }
            update { it.copy(current = index + 1) }
            val message = store.beginSending(id)
            if (message == null) {
                update { it.copy(skipped = it.skipped + 1) }
                continue
            }
            // What is posted: the text, plus the header line when enabled (counts toward Discord's limit).
            val content = render(message, settings())
            val validation = TextRules.validate(content)
            if (!validation.isValid) {
                store.markFailed(id, (validation.userMessage() ?: "Invalid message.") + if (settings().dividerTemplate.isNotBlank()) " (includes the divider; shorten it or turn the divider off in Settings.)" else "")
                update { it.copy(failed = it.failed + 1) }
                continue
            }

            var stopReason: String? = null
            val step = try {
                deliver(message, content, hook) { stopReason = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unknown failure after the request may have been issued: do not guess.
                store.markUncertain(id, "Unexpected error while sending. Check Discord before retrying.")
                stopReason = "Stopped after an unexpected error. Check Discord before retrying."
                Step.UNCERTAIN
            }
            update {
                when (step) {
                    Step.SENT -> it.copy(sent = it.sent + 1)
                    Step.FAILED -> it.copy(failed = it.failed + 1)
                    Step.UNCERTAIN -> it.copy(uncertain = it.uncertain + 1)
                    Step.RELEASED -> it.copy(skipped = it.skipped + 1)
                }
            }
            if (stopReason != null) {
                update { it.copy(stopReason = stopReason) }
                return
            }
            if (index < ids.lastIndex) pause(INTER_MESSAGE_DELAY_MS)
        }
    }

    private suspend fun deliver(message: PendingMessage, content: String, hook: WebhookUrl, stop: (String) -> Unit): Step {
        var rateLimitAttempts = 0
        var serverAttempts = 0
        while (true) {
            when (val outcome = client.post(hook, content)) {
                is PostOutcome.Delivered -> {
                    store.markSent(message.id, outcome.messageId, settings().historyEnabled)
                    onPublished()
                    outcome.rateLimitResetAfterMs?.let { if (it > 0) pause(it.coerceAtMost(MAX_WAIT_MS)) }
                    return Step.SENT
                }
                is PostOutcome.RateLimited -> {
                    rateLimitAttempts++
                    if (rateLimitAttempts >= MAX_RATE_LIMIT_ATTEMPTS || outcome.retryAfterMs > MAX_WAIT_MS) {
                        store.markFailed(message.id, "Discord is rate limiting this webhook. Try again later.")
                        stop("Stopped: Discord is rate limiting this webhook. Remaining messages were not sent.")
                        return Step.FAILED
                    }
                    if (!waitInterruptibly(outcome.retryAfterMs + 250)) {
                        store.releaseSending(message.id, "Cancelled while waiting for Discord's rate limit.")
                        return Step.RELEASED
                    }
                }
                is PostOutcome.ServerError -> {
                    serverAttempts++
                    if (serverAttempts >= MAX_SERVER_ATTEMPTS) {
                        store.markFailed(message.id, "Discord had a server problem (HTTP ${outcome.httpCode}). Retry later.")
                        stop("Stopped: Discord is having server problems. Remaining messages were not sent.")
                        return Step.FAILED
                    }
                    if (!waitInterruptibly(1000L shl (serverAttempts - 1))) {
                        store.releaseSending(message.id, "Cancelled while retrying after a Discord server error.")
                        return Step.RELEASED
                    }
                }
                is PostOutcome.Rejected -> {
                    store.markFailed(message.id, outcome.message)
                    return Step.FAILED
                }
                is PostOutcome.WebhookInvalid -> {
                    store.markFailed(message.id, outcome.message)
                    stop("Stopped: the webhook was rejected. Check the webhook in Settings.")
                    return Step.FAILED
                }
                is PostOutcome.NotSent -> {
                    store.markFailed(message.id, outcome.message)
                    stop("Stopped: could not reach Discord. Remaining messages were not sent.")
                    return Step.FAILED
                }
                is PostOutcome.Uncertain -> {
                    store.markUncertain(message.id, outcome.message)
                    stop("Stopped: delivery of a message is uncertain. Check Discord, then decide whether to retry.")
                    return Step.UNCERTAIN
                }
            }
        }
    }

    private fun render(m: PendingMessage, cfg: AppSettings): String =
        MessageFormat.render(m, cfg.dividerTemplate, cfg.nextUpdateNumber)

    /** Waits in short slices so a cancel request is honoured; returns false if cancelled. */
    private suspend fun waitInterruptibly(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            if (cancelRequested) return false
            val slice = minOf(left, 250L)
            pause(slice)
            left -= slice
        }
        return !cancelRequested
    }

    private fun update(block: (BatchProgress) -> BatchProgress) {
        state.update(block)
    }

    companion object {
        const val INTER_MESSAGE_DELAY_MS = 400L
        const val MAX_WAIT_MS = 60_000L
        const val MAX_RATE_LIMIT_ATTEMPTS = 5
        const val MAX_SERVER_ATTEMPTS = 3
    }
}
