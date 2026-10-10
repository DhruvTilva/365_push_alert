package com.dhruvtilva.pushalert.discord

/**
 * A validated Discord webhook endpoint. [toString] is redacted so the token cannot leak through
 * logs, string templates or exception messages.
 */
class WebhookUrl private constructor(
    private val host: String,
    val id: String,
    private val token: String,
) {
    sealed interface ParseResult {
        data class Valid(val url: WebhookUrl) : ParseResult
        data class Invalid(val reason: String) : ParseResult
    }

    fun fullUrl(): String = "https://$host/api/webhooks/$id/$token"

    /** URL used to post a message; `wait=true` makes Discord return the created message. */
    fun executeUrl(): String = fullUrl() + "?wait=true"

    /** Safe-to-display form: shows only a short prefix/suffix of the id and hides the token. */
    fun masked(): String {
        val shortId = if (id.length > 8) id.take(4) + "…" + id.takeLast(4) else id
        return "$host/api/webhooks/$shortId/••••••••"
    }

    override fun toString(): String = "WebhookUrl(${masked()})"
    override fun equals(other: Any?) = other is WebhookUrl && other.fullUrl() == fullUrl()
    override fun hashCode() = fullUrl().hashCode()

    companion object {
        private val HOSTS = setOf("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com")
        private val PATTERN = Regex("^https://([a-z.]+)/api/(?:v\\d+/)?webhooks/(\\d{17,21})/([A-Za-z0-9_-]{20,120})/?$")

        fun parse(raw: String): ParseResult {
            val input = raw.trim()
            if (input.isEmpty()) return ParseResult.Invalid("Enter the webhook URL.")
            if (!input.startsWith("https://")) return ParseResult.Invalid("The webhook URL must start with https://")
            // Query strings (e.g. ?wait=true, ?thread_id=) are intentionally not accepted or stored.
            val m = PATTERN.matchEntire(input)
                ?: return ParseResult.Invalid(
                    "That does not look like a Discord webhook URL (https://discord.com/api/webhooks/<id>/<token>).",
                )
            val host = m.groupValues[1]
            if (host !in HOSTS) return ParseResult.Invalid("Only discord.com webhook URLs are accepted.")
            return ParseResult.Valid(WebhookUrl(host, m.groupValues[2], m.groupValues[3]))
        }
    }
}
