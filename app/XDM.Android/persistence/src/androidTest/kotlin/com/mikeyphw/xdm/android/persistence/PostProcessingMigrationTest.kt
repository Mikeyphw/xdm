package com.mikeyphw.xdm.android.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PostProcessingMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate15To17ValidatesDurablePostProcessingSchema() {
        val name = "post-processing-15-17-${System.nanoTime()}"
        helper.createDatabase(name, 15).close()
        val db = helper.runMigrationsAndValidate(name, 17, true, Migrations.Migration15To16, Migrations.Migration16To17)
        assertPostProcessingSchema(db)
        db.close()
    }

    @Test
    fun migrate14To17ValidatesSecurityThenPostProcessing() {
        val name = "post-processing-14-17-${System.nanoTime()}"
        helper.createDatabase(name, 14).close()
        val db = helper.runMigrationsAndValidate(
            name,
            17,
            true,
            Migrations.Migration14To15,
            Migrations.Migration15To16,
            Migrations.Migration16To17,
        )
        assertPostProcessingSchema(db)
        db.close()
    }

    private fun assertPostProcessingSchema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val tables = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        assertTrue("Durable post-processing job table is missing", "post_processing_jobs" in tables)
        assertTrue("Durable post-processing claim table is missing", "post_processing_claims" in tables)

        val foreignKeys = mutableListOf<Triple<String, String, String>>()
        db.query("PRAGMA foreign_key_list(post_processing_claims)").use { cursor ->
            val table = cursor.getColumnIndexOrThrow("table")
            val from = cursor.getColumnIndexOrThrow("from")
            val onDelete = cursor.getColumnIndexOrThrow("on_delete")
            while (cursor.moveToNext()) foreignKeys += Triple(cursor.getString(table), cursor.getString(from), cursor.getString(onDelete))
        }
        assertTrue(foreignKeys.contains(Triple("post_processing_jobs", "jobId", "CASCADE")))

        val indices = mutableSetOf<String>()
        db.query("PRAGMA index_list(post_processing_claims)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) indices += cursor.getString(name)
        }
        assertTrue(indices.contains("index_post_processing_claims_subjectId_subjectGeneration_trigger_ruleId_actionId"))

        val jobColumns = mutableSetOf<String>()
        db.query("PRAGMA table_info(post_processing_jobs)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) jobColumns += cursor.getString(name)
        }
        assertTrue(jobColumns.containsAll(setOf(
            "publicationState",
            "publicationDisplayName",
            "publicationExpectedBytes",
            "publicationExpectedSha256",
            "committedOutputUri",
            "committedBytes",
            "committedSha256",
            "sideEffectOutcome",
        )))

        db.query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(17, cursor.getInt(0))
        }
    }
}
