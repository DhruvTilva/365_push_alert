package com.dhruvtilva.pushalert.data

import com.dhruvtilva.pushalert.JdbcDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationTest {
    private fun columns(db: Db, table: String): Set<String> =
        db.query("PRAGMA table_info($table)").map { it.requireString("name") }.toSet()

    private fun tables(db: Db): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").map { it.requireString("name") }.toSet()

    @Test fun migratingV1ToLatestPreservesDataAndBackfillsCounters() {
        val db = JdbcDb.memory()
        Schema.createV1(db)
        db.exec("INSERT INTO group_filters(group_name, normalized_name, enabled, created_at, updated_at) VALUES('Project Updates','project updates',1,1,1)")
        db.exec(
            """INSERT INTO messages(source_package, group_name, original_text, received_at, fingerprint, status, selected, updated_at)
               VALUES('com.whatsapp','Project Updates','hello',10,'f1','PENDING',1,10)""",
        )
        db.exec(
            """INSERT INTO sent_history(original_message_id, group_name, published_text, received_at, sent_at, delivery_status)
               VALUES(7,'Project Updates','old',5,6,'SENT')""",
        )

        Schema.migrate(db, 1, Schema.VERSION)

        assertEquals("hello", db.query("SELECT original_text FROM messages").single().string("original_text"))
        assertEquals("old", db.query("SELECT published_text FROM sent_history").single().string("published_text"))
        assertEquals(2, db.query("SELECT collected_count FROM group_filters").single().int("collected_count"))
        assertTrue("diagnostics" in tables(db))
    }

    @Test fun migratedSchemaEqualsFreshlyCreatedSchema() {
        val migrated = JdbcDb.memory().also { Schema.createV1(it); Schema.migrate(it, 1, Schema.VERSION) }
        val fresh = JdbcDb.memory().also { Schema.create(it) }
        assertEquals(tables(fresh), tables(migrated))
        for (t in tables(fresh)) assertEquals("columns of $t", columns(fresh, t), columns(migrated, t))
    }

    @Test fun v1RowsGetDefaultsForNewColumns() {
        val db = JdbcDb.memory()
        Schema.createV1(db)
        db.exec("INSERT INTO group_filters(group_name, normalized_name, enabled, created_at, updated_at) VALUES('G','g',1,1,1)")
        Schema.migrate(db, 1, 2)
        assertEquals(0, db.query("SELECT collected_count FROM group_filters").single().int("collected_count"))
    }

    @Test(expected = IllegalStateException::class)
    fun missingMigrationStepFailsLoudly() {
        Schema.migrate(JdbcDb.memory(), 5, 6)
    }

    @Test fun noopWhenAlreadyCurrent() {
        val db = JdbcDb.memory().also { Schema.create(it) }
        Schema.migrate(db, Schema.VERSION, Schema.VERSION)
        assertEquals(setOf("group_filters", "messages", "sent_history", "seen_fingerprints", "diagnostics", "sqlite_sequence").minus("sqlite_sequence"), tables(db))
    }
}
