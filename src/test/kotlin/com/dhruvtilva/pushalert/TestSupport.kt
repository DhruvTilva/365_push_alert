package com.dhruvtilva.pushalert

import com.dhruvtilva.pushalert.collect.RawMessage
import com.dhruvtilva.pushalert.collect.RawNotification
import com.dhruvtilva.pushalert.data.Db
import com.dhruvtilva.pushalert.data.KeyValueStore
import com.dhruvtilva.pushalert.data.Row
import com.dhruvtilva.pushalert.data.SecretCipher
import com.dhruvtilva.pushalert.data.Store
import com.dhruvtilva.pushalert.discord.HttpRequest
import com.dhruvtilva.pushalert.discord.HttpResult
import com.dhruvtilva.pushalert.discord.HttpTransport
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** [Db] on a real SQLite engine (sqlite-jdbc) so production SQL and migrations run in JVM tests. */
class JdbcDb(private val conn: Connection) : Db {
    private var depth = 0

    init { conn.autoCommit = true }

    private fun bind(st: java.sql.PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val v = when (a) { is Boolean -> if (a) 1L else 0L; is Int -> a.toLong(); else -> a }
            st.setObject(i + 1, v)
        }
    }

    @Synchronized override fun exec(sql: String, vararg args: Any?) {
        conn.prepareStatement(sql).use { bind(it, args); it.execute() }
    }

    @Synchronized override fun update(sql: String, vararg args: Any?): Int =
        conn.prepareStatement(sql).use { bind(it, args); it.executeUpdate() }

    @Synchronized override fun insert(sql: String, vararg args: Any?): Long {
        conn.prepareStatement(sql).use { bind(it, args); it.executeUpdate() }
        conn.createStatement().use { st -> st.executeQuery("select last_insert_rowid()").use { it.next(); return it.getLong(1) } }
    }

    @Synchronized override fun query(sql: String, vararg args: Any?): List<Row> =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val md = rs.metaData
                val out = ArrayList<Row>()
                while (rs.next()) {
                    out += Row((1..md.columnCount).associate { md.getColumnLabel(it) to rs.getObject(it) })
                }
                out
            }
        }

    @Synchronized override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        depth++
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            depth--
            conn.autoCommit = true
        }
    }

    fun userVersion(): Int = query("PRAGMA user_version").first().int("user_version") ?: 0
    fun close() = conn.close()

    companion object {
        fun memory(): JdbcDb = JdbcDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
        fun file(f: File): JdbcDb = JdbcDb(DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}"))
    }
}

class TestClock(var now: Long = 1_700_000_000_000L) : () -> Long {
    override fun invoke(): Long = now
}

fun newStore(db: Db = JdbcDb.memory().also { com.dhruvtilva.pushalert.data.Schema.create(it) }, clock: () -> Long = TestClock()) =
    Store(db, Dispatchers.Unconfined, clock)

class MemoryKv : KeyValueStore {
    val map = HashMap<String, Any?>()
    override fun getString(key: String) = map[key] as String?
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    override fun getBoolean(key: String, default: Boolean) = map[key] as Boolean? ?: default
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
}

/** Real AES-GCM with a software key, standing in for the Android Keystore cipher. */
class SoftwareCipher(keyBytes: ByteArray = ByteArray(32) { it.toByte() }) : SecretCipher {
    private val key = SecretKeySpec(keyBytes, "AES")
    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(plain)
    }
    override fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

/** Scripted transport: returns queued results in order and records every request. */
class FakeTransport(private val script: MutableList<HttpResult> = mutableListOf()) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var onRequest: (HttpRequest, Int) -> Unit = { _, _ -> }
    var fallback: HttpResult = HttpResult.Response(200, """{"id":"900"}""", emptyMap())

    fun enqueue(vararg results: HttpResult) { script.addAll(results) }

    override fun execute(request: HttpRequest): HttpResult {
        requests += request
        onRequest(request, requests.size)
        return if (script.isNotEmpty()) script.removeAt(0) else fallback
    }
}

fun ok(id: String = "111", headers: Map<String, String> = emptyMap()) = HttpResult.Response(200, """{"id":"$id","content":"x"}""", headers)
fun http(code: Int, body: String = "", headers: Map<String, String> = emptyMap()) = HttpResult.Response(code, body, headers)

/** Obviously fake, test-only webhook (the ids/token are made up and match no real Discord webhook). */
const val VALID_WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/FAKE-TEST-TOKEN_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_AbCdEfGhIjKlMnOpQrSt"

/** Builds a WhatsApp-like notification. */
fun wa(
    title: String? = "Project Updates",
    text: String? = "Hello team",
    pkg: String = "com.whatsapp",
    bigText: String? = null,
    summary: Boolean = false,
    ongoing: Boolean = false,
    conversationTitle: String? = null,
    isGroup: Boolean? = null,
    messages: List<RawMessage> = emptyList(),
    shortcutId: String? = null,
    postTime: Long = 1_700_000_000_000L,
) = RawNotification(
    packageName = pkg, postTime = postTime, shortcutId = shortcutId,
    isGroupSummary = summary, isOngoing = ongoing,
    title = title, text = text, bigText = bigText,
    conversationTitle = conversationTitle, isGroupConversation = isGroup, messages = messages,
)
