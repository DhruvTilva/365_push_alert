package com.dhruvtilva.pushalert.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.dhruvtilva.pushalert.discord.WebhookUrl
import com.dhruvtilva.pushalert.domain.AppSettings
import com.dhruvtilva.pushalert.domain.DividerStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
}

/** Non-secret configuration, kept in ordinary preferences. */
class SettingsStore(private val kv: KeyValueStore) {
    private val state = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    private fun load(): AppSettings {
        val defaults = AppSettings()
        val packages = kv.getString(K_PACKAGES)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toCollection(LinkedHashSet())
            ?.takeIf { it.isNotEmpty() } ?: defaults.whatsappPackages
        return AppSettings(
            collectionEnabled = kv.getBoolean(K_COLLECTION, defaults.collectionEnabled),
            allowIndividualChats = kv.getBoolean(K_INDIVIDUAL, defaults.allowIndividualChats),
            historyEnabled = kv.getBoolean(K_HISTORY, defaults.historyEnabled),
            diagnosticsEnabled = kv.getBoolean(K_DIAGNOSTICS, defaults.diagnosticsEnabled),
            dividerStyle = kv.getString(K_STYLE)?.let { DividerStyle.parse(it) }
                ?: if (!kv.getBoolean(K_HEADER, true)) DividerStyle.NONE else defaults.dividerStyle,
            headerTitle = kv.getString(K_TITLE)?.takeIf { it.isNotBlank() } ?: defaults.headerTitle,
            nextUpdateNumber = kv.getString(K_NUMBER)?.toLongOrNull()?.takeIf { it >= 1 } ?: defaults.nextUpdateNumber,
            whatsappPackages = packages,
        )
    }

    fun setCollectionEnabled(v: Boolean) { kv.putBoolean(K_COLLECTION, v); state.value = state.value.copy(collectionEnabled = v) }
    fun setAllowIndividualChats(v: Boolean) { kv.putBoolean(K_INDIVIDUAL, v); state.value = state.value.copy(allowIndividualChats = v) }
    fun setHistoryEnabled(v: Boolean) { kv.putBoolean(K_HISTORY, v); state.value = state.value.copy(historyEnabled = v) }
    /** Returns an error message, or null when saved. */
    fun setHeader(title: String, nextNumber: String): String? {
        val t = title.trim()
        if (t.isEmpty() || t.length > 40 || t.contains('\n')) return "Title must be 1–40 characters on one line."
        val n = nextNumber.trim().toLongOrNull()
        if (n == null || n < 1 || n > 999_999_999) return "Next number must be a whole number of 1 or more."
        kv.putString(K_TITLE, t)
        kv.putString(K_NUMBER, n.toString())
        state.value = state.value.copy(headerTitle = t, nextUpdateNumber = n)
        return null
    }

    /** Called after a message was published so the next divider line gets the next number. */
    fun advanceUpdateNumber() {
        val n = state.value.nextUpdateNumber + 1
        kv.putString(K_NUMBER, n.toString())
        state.value = state.value.copy(nextUpdateNumber = n)
    }

    fun setDividerStyle(v: DividerStyle) { kv.putString(K_STYLE, v.name); state.value = state.value.copy(dividerStyle = v) }
    fun setDiagnosticsEnabled(v: Boolean) { kv.putBoolean(K_DIAGNOSTICS, v); state.value = state.value.copy(diagnosticsEnabled = v) }

    /** Returns an error message if any entry is not a plausible Android package name. */
    fun setWhatsappPackages(raw: String): String? {
        val list = raw.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return "Enter at least one package name."
        if (list.any { !PACKAGE_NAME.matches(it) }) return "Package names look like com.whatsapp."
        val set = LinkedHashSet(list)
        kv.putString(K_PACKAGES, set.joinToString(","))
        state.value = state.value.copy(whatsappPackages = set)
        return null
    }

    fun resetWhatsappPackages() {
        kv.putString(K_PACKAGES, null)
        state.value = state.value.copy(whatsappPackages = AppSettings.DEFAULT_WHATSAPP_PACKAGES)
    }

    companion object {
        private const val K_COLLECTION = "collection_enabled"
        private const val K_INDIVIDUAL = "allow_individual_chats"
        private const val K_HISTORY = "history_enabled"
        private const val K_DIAGNOSTICS = "diagnostics_enabled"
        private const val K_PACKAGES = "whatsapp_packages"
        private const val K_HEADER = "add_header" // legacy on/off switch, read once for migration
        private const val K_STYLE = "divider_style"
        private const val K_TITLE = "header_title"
        private const val K_NUMBER = "next_update_number"
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}

interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable key held in the Android Keystore. */
class KeystoreCipher(private val alias: String = "pushalert_webhook_key") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_SIZE) { "ciphertext too short" }
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_SIZE))
        return c.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

sealed interface SecretState {
    data object Missing : SecretState
    data class Present(val webhook: WebhookUrl) : SecretState
    /** A value exists but cannot be decrypted/parsed (e.g. keystore key lost). User must re-enter it. */
    data object Unreadable : SecretState
}

/** Stores the Discord webhook URL encrypted; the plaintext never touches preferences or logs. */
class SecretStore(private val kv: KeyValueStore, private val cipher: SecretCipher) {
    private val state = MutableStateFlow(read())
    val webhook: StateFlow<SecretState> = state.asStateFlow()

    fun save(url: WebhookUrl) {
        val blob = cipher.encrypt(url.fullUrl().toByteArray(Charsets.UTF_8))
        kv.putString(K_WEBHOOK, Base64.getEncoder().encodeToString(blob))
        state.value = SecretState.Present(url)
    }

    fun clear() {
        kv.putString(K_WEBHOOK, null)
        state.value = SecretState.Missing
    }

    fun current(): WebhookUrl? = (state.value as? SecretState.Present)?.webhook

    private fun read(): SecretState {
        val stored = kv.getString(K_WEBHOOK) ?: return SecretState.Missing
        return try {
            val plain = String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8)
            when (val parsed = WebhookUrl.parse(plain)) {
                is WebhookUrl.ParseResult.Valid -> SecretState.Present(parsed.url)
                is WebhookUrl.ParseResult.Invalid -> SecretState.Unreadable
            }
        } catch (e: Exception) {
            // Deliberately do not include the exception message: it must never carry secret material.
            SecretState.Unreadable
        }
    }

    companion object {
        private const val K_WEBHOOK = "webhook_v1"
    }
}
