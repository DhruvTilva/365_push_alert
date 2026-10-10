package com.dhruvtilva.pushalert.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement

/**
 * Minimal SQL surface used by [Store]. Android's SQLite implements it in production; unit tests
 * implement it with a JDBC SQLite so the real SQL and migrations run on the JVM.
 */
interface Db {
    fun exec(sql: String, vararg args: Any?)
    fun update(sql: String, vararg args: Any?): Int
    fun insert(sql: String, vararg args: Any?): Long
    fun query(sql: String, vararg args: Any?): List<Row>
    fun <T> transaction(block: () -> T): T
}

class Row(private val values: Map<String, Any?>) {
    fun string(column: String): String? = values[column]?.toString()
    fun long(column: String): Long? = (values[column] as? Number)?.toLong()
    fun int(column: String): Int? = (values[column] as? Number)?.toInt()
    fun bool(column: String): Boolean = (long(column) ?: 0L) != 0L
    fun requireString(column: String): String = string(column) ?: error("column $column is null")
    fun requireLong(column: String): Long = long(column) ?: error("column $column is null")
}

internal fun normalizeArg(arg: Any?): Any? = when (arg) {
    is Boolean -> if (arg) 1L else 0L
    is Int -> arg.toLong()
    else -> arg
}

class AndroidDb(private val db: SQLiteDatabase) : Db {
    override fun exec(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.map(::normalizeArg).toTypedArray())
    }

    override fun update(sql: String, vararg args: Any?): Int =
        db.compileStatement(sql).use { bind(it, args); it.executeUpdateDelete() }

    override fun insert(sql: String, vararg args: Any?): Long =
        db.compileStatement(sql).use { bind(it, args); it.executeInsert() }

    override fun query(sql: String, vararg args: Any?): List<Row> {
        val strArgs = args.map { requireNotNull(normalizeArg(it)) { "null query argument" }.toString() }.toTypedArray()
        db.rawQuery(sql, strArgs).use { c ->
            val out = ArrayList<Row>(c.count)
            while (c.moveToNext()) out += rowOf(c)
            return out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun bind(stmt: SQLiteStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, raw ->
            val index = i + 1
            when (val a = normalizeArg(raw)) {
                null -> stmt.bindNull(index)
                is Long -> stmt.bindLong(index, a)
                is Double -> stmt.bindDouble(index, a)
                is ByteArray -> stmt.bindBlob(index, a)
                else -> stmt.bindString(index, a.toString())
            }
        }
    }

    private fun rowOf(c: Cursor): Row {
        val map = HashMap<String, Any?>()
        for (i in 0 until c.columnCount) {
            map[c.getColumnName(i)] = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                else -> c.getString(i)
            }
        }
        return Row(map)
    }
}

/** Schema definition and ordered migrations. */
object Schema {
    const val VERSION = 2

    private val V1 = listOf(
        """CREATE TABLE group_filters(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            group_name TEXT NOT NULL,
            normalized_name TEXT NOT NULL UNIQUE,
            enabled INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL)""",
        """CREATE TABLE messages(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            source_package TEXT NOT NULL,
            group_name TEXT NOT NULL,
            conversation_id TEXT,
            sender_name TEXT,
            original_text TEXT NOT NULL,
            edited_text TEXT,
            received_at INTEGER NOT NULL,
            fingerprint TEXT NOT NULL,
            status TEXT NOT NULL,
            selected INTEGER NOT NULL DEFAULT 0,
            last_error TEXT,
            discord_message_id TEXT,
            sent_at INTEGER,
            updated_at INTEGER NOT NULL,
            attempt_count INTEGER NOT NULL DEFAULT 0,
            truncated INTEGER NOT NULL DEFAULT 0)""",
        "CREATE INDEX idx_messages_order ON messages(received_at, id)",
        """CREATE TABLE sent_history(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            original_message_id INTEGER NOT NULL,
            group_name TEXT NOT NULL,
            sender_name TEXT,
            published_text TEXT NOT NULL,
            received_at INTEGER NOT NULL,
            sent_at INTEGER NOT NULL,
            discord_message_id TEXT,
            delivery_status TEXT NOT NULL)""",
        """CREATE TABLE seen_fingerprints(
            fingerprint TEXT PRIMARY KEY,
            seen_at INTEGER NOT NULL,
            timed INTEGER NOT NULL)""",
    )

    /** 1 -> 2: per-group collected counter and the collection diagnostics log. */
    private val MIGRATION_1_2 = listOf(
        "ALTER TABLE group_filters ADD COLUMN collected_count INTEGER NOT NULL DEFAULT 0",
        """UPDATE group_filters SET collected_count =
            (SELECT COUNT(*) FROM messages m WHERE lower(m.group_name) = group_filters.normalized_name)
            + (SELECT COUNT(*) FROM sent_history h WHERE lower(h.group_name) = group_filters.normalized_name)""",
        """CREATE TABLE diagnostics(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            at INTEGER NOT NULL,
            accepted INTEGER NOT NULL,
            code TEXT NOT NULL,
            detail TEXT NOT NULL)""",
    )

    private val MIGRATIONS: Map<Int, List<String>> = mapOf(1 to MIGRATION_1_2)

    /** Creates a brand-new database at the latest version. */
    fun create(db: Db) {
        V1.forEach { db.exec(it) }
        migrate(db, 1, VERSION)
    }

    /** Applies every migration step in [from, to). */
    fun migrate(db: Db, from: Int, to: Int) {
        for (v in from until to) {
            val steps = MIGRATIONS[v] ?: error("No migration from version $v")
            steps.forEach { db.exec(it) }
        }
    }

    /** Test/legacy helper: create the original v1 schema only. */
    fun createV1(db: Db) = V1.forEach { db.exec(it) }
}

class AppDatabaseHelper(context: Context, name: String = "pushalert.db") :
    SQLiteOpenHelper(context, name, null, Schema.VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) = Schema.create(AndroidDb(db))

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        Schema.migrate(AndroidDb(db), oldVersion, newVersion)
}
