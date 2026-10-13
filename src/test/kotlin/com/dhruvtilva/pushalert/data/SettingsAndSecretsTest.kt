package com.dhruvtilva.pushalert.data

import com.dhruvtilva.pushalert.MemoryKv
import com.dhruvtilva.pushalert.SoftwareCipher
import com.dhruvtilva.pushalert.VALID_WEBHOOK
import com.dhruvtilva.pushalert.discord.WebhookUrl
import com.dhruvtilva.pushalert.domain.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SettingsAndSecretsTest {
    private val hook = (WebhookUrl.parse(VALID_WEBHOOK) as WebhookUrl.ParseResult.Valid).url
    private val token = VALID_WEBHOOK.substringAfterLast('/')

    @Test fun safeDefaultsCollectNothingExtra() {
        val s = SettingsStore(MemoryKv()).settings.value
        assertEquals(AppSettings(), s)
        assertTrue(s.collectionEnabled); assertFalse(s.allowIndividualChats); assertTrue(s.historyEnabled)
        assertEquals(setOf("com.whatsapp", "com.whatsapp.w4b"), s.whatsappPackages)
    }

    @Test fun settingsSurviveRestart() {
        val kv = MemoryKv()
        SettingsStore(kv).apply {
            setCollectionEnabled(false); setAllowIndividualChats(true); setHistoryEnabled(false)
            assertNull(setWhatsappPackages("com.whatsapp, com.whatsapp.clone"))
        }
        val s = SettingsStore(kv).settings.value
        assertFalse(s.collectionEnabled); assertTrue(s.allowIndividualChats); assertFalse(s.historyEnabled)
        assertEquals(setOf("com.whatsapp", "com.whatsapp.clone"), s.whatsappPackages)
    }

    @Test fun packageListIsValidatedAndResettable() {
        val store = SettingsStore(MemoryKv())
        assertNotNull(store.setWhatsappPackages(""))
        assertNotNull(store.setWhatsappPackages("not a package!"))
        assertEquals(AppSettings.DEFAULT_WHATSAPP_PACKAGES, store.settings.value.whatsappPackages)
        store.setWhatsappPackages("com.x.y"); store.resetWhatsappPackages()
        assertEquals(AppSettings.DEFAULT_WHATSAPP_PACKAGES, store.settings.value.whatsappPackages)
    }

    @Test fun webhookRoundTripsAcrossRestart() {
        val kv = MemoryKv()
        SecretStore(kv, SoftwareCipher()).save(hook)
        val reloaded = SecretStore(kv, SoftwareCipher())
        assertEquals(hook, reloaded.current())
        assertTrue(reloaded.webhook.value is SecretState.Present)
    }

    @Test fun storedWebhookIsEncryptedNotPlaintext() {
        val kv = MemoryKv()
        SecretStore(kv, SoftwareCipher()).save(hook)
        val stored = kv.map.values.joinToString { it.toString() }
        assertFalse(stored.contains(token))
        assertFalse(stored.contains("discord"))
        assertFalse(stored.contains("webhooks"))
        val raw = Base64.getDecoder().decode(kv.getString("webhook_v1"))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(token))
    }

    @Test fun nothingIsStoredInTheNonSecretSettingsStore() {
        val kv = MemoryKv()
        SecretStore(kv, SoftwareCipher()).save(hook)
        assertEquals(setOf("webhook_v1"), kv.map.keys)
    }

    @Test fun clearRemovesTheWebhook() {
        val kv = MemoryKv()
        val s = SecretStore(kv, SoftwareCipher())
        s.save(hook); s.clear()
        assertNull(s.current()); assertTrue(kv.map.isEmpty())
        assertTrue(SecretStore(kv, SoftwareCipher()).webhook.value is SecretState.Missing)
    }

    @Test fun undecryptableValueIsReportedNotCrashingAndNotLeaking() {
        val kv = MemoryKv()
        SecretStore(kv, SoftwareCipher()).save(hook)
        val other = SecretStore(kv, SoftwareCipher(ByteArray(32) { 99 }))
        assertEquals(SecretState.Unreadable, other.webhook.value)
        assertNull(other.current())
    }

    @Test fun corruptedValueIsUnreadable() {
        val kv = MemoryKv().also { it.putString("webhook_v1", "!!!not base64!!!") }
        assertEquals(SecretState.Unreadable, SecretStore(kv, SoftwareCipher()).webhook.value)
    }

    @Test fun dividerTemplateAndNumberPersistAndAdvance() {
        val kv = MemoryKv()
        val a = SettingsStore(kv)
        assertEquals(AppSettings.DEFAULT_DIVIDER, a.settings.value.dividerTemplate)
        assertEquals(1L, a.settings.value.nextUpdateNumber)
        assertNull(a.setDivider("  \uD83D\uDEA8 My Feed #{n}  ", "41"))
        a.advanceUpdateNumber()
        val b = SettingsStore(kv).settings.value
        assertEquals("\uD83D\uDEA8 My Feed #{n}", b.dividerTemplate)
        assertEquals(42L, b.nextUpdateNumber)
    }

    @Test fun emptyDividerIsAllowedAndMeansNoDivider() {
        val kv = MemoryKv()
        assertNull(SettingsStore(kv).setDivider("", "1"))
        assertEquals("", SettingsStore(kv).settings.value.dividerTemplate)
    }

    @Test fun invalidDividerSettingsAreRejected() {
        val a = SettingsStore(MemoryKv())
        assertNotNull(a.setDivider("x".repeat(SettingsStore.MAX_DIVIDER + 1), "1"))
        assertNotNull(a.setDivider("ok", "0"))
        assertNotNull(a.setDivider("ok", "abc"))
        assertEquals(AppSettings.DEFAULT_DIVIDER, a.settings.value.dividerTemplate)
    }

    @Test fun oldHeaderOffSettingMigratesToNoDivider() {
        val kv = MemoryKv().also { it.putBoolean("add_header", false) }
        assertEquals("", SettingsStore(kv).settings.value.dividerTemplate)
    }

    @Test fun oldStyleAndTitleSettingsMigrateToATemplate() {
        val small = MemoryKv().also { it.putString("divider_style", "SMALL"); it.putString("header_title", "\uD83D\uDEA8 Feed") }
        assertEquals("-# \uD83D\uDEA8 Feed #{n}", SettingsStore(small).settings.value.dividerTemplate)
        val heading = MemoryKv().also { it.putString("divider_style", "HEADING") }
        assertEquals(AppSettings.DEFAULT_DIVIDER, SettingsStore(heading).settings.value.dividerTemplate)
        val custom = MemoryKv().also { it.putString("header_title", "\uD83D\uDEA8 My Feed") }
        assertEquals("### \uD83D\uDEA8 My Feed #{n}", SettingsStore(custom).settings.value.dividerTemplate)
    }


    @Test fun trailingEntersInTheDividerAreKeptButOtherWhitespaceIsTrimmed() {
        val kv = MemoryKv()
        val a = SettingsStore(kv)
        assertNull(a.setDivider("  ### T #{n}\n\n  ", "1"))
        assertEquals("### T #{n}\n\n", SettingsStore(kv).settings.value.dividerTemplate)
        assertNull(a.setDivider(" \n \n ", "1"))
        assertEquals("", SettingsStore(kv).settings.value.dividerTemplate)
    }
}
