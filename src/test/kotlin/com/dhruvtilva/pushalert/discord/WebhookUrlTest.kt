package com.dhruvtilva.pushalert.discord

import com.dhruvtilva.pushalert.VALID_WEBHOOK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookUrlTest {
    private fun valid(s: String) = (WebhookUrl.parse(s) as WebhookUrl.ParseResult.Valid).url
    private fun invalid(s: String) = WebhookUrl.parse(s) is WebhookUrl.ParseResult.Invalid

    @Test fun acceptsRealisticWebhook() {
        val u = valid(VALID_WEBHOOK)
        assertEquals(VALID_WEBHOOK, u.fullUrl())
        assertEquals("$VALID_WEBHOOK?wait=true", u.executeUrl())
    }

    @Test fun acceptsKnownDiscordHostsVersionedPathAndTrailingSlash() {
        assertTrue(!invalid(VALID_WEBHOOK.replace("discord.com", "ptb.discord.com")))
        assertTrue(!invalid(VALID_WEBHOOK.replace("discord.com", "discordapp.com")))
        assertTrue(!invalid(VALID_WEBHOOK.replace("/api/", "/api/v10/")))
        assertTrue(!invalid("  $VALID_WEBHOOK/  "))
    }

    @Test fun rejectsNonHttpsAndOtherHosts() {
        assertTrue(invalid(VALID_WEBHOOK.replace("https", "http")))
        assertTrue(invalid(VALID_WEBHOOK.replace("discord.com", "evil.example.com")))
        assertTrue(invalid(VALID_WEBHOOK.replace("discord.com", "discord.com.evil.io")))
        assertTrue(invalid(VALID_WEBHOOK.replace("https://", "https://user:pw@")))
        assertTrue(invalid(VALID_WEBHOOK.replace("discord.com", "discord.com:8443")))
    }

    @Test fun rejectsImplausibleIdsAndTokensAndExtras() {
        assertTrue(invalid("https://discord.com/api/webhooks/123/abc"))
        assertTrue(invalid(VALID_WEBHOOK + "?wait=true"))
        assertTrue(invalid("$VALID_WEBHOOK/slack"))
        assertTrue(invalid("https://discord.com/api/webhooks/abc/" + "A".repeat(68)))
        assertTrue(invalid(""))
        assertTrue(invalid("not a url"))
        assertTrue(invalid("https://discord.com/channels/1/2"))
    }

    @Test fun tokenNeverAppearsInDisplayOrToStringOrErrors() {
        val u = valid(VALID_WEBHOOK)
        val token = VALID_WEBHOOK.substringAfterLast('/')
        assertFalse(u.masked().contains(token))
        assertFalse(u.toString().contains(token))
        assertFalse("$u".contains(token))
        val err = WebhookUrl.parse(VALID_WEBHOOK + "?x=1") as WebhookUrl.ParseResult.Invalid
        assertFalse(err.reason.contains(token))
    }
}
