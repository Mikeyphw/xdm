package com.mikeyphw.xdm.android.persistence

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationIntegrityMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate17To18AddsGenerationAndRepairsGraphIntegrity() {
        val name = "generation-integrity-17-18-${System.nanoTime()}"
        val legacy = helper.createDatabase(name, 17)
        legacy.execSQL(
            """INSERT INTO downloads
                (id,fileName,sourceUrl,destinationUri,state,backend,bytesReceived,totalBytes,speedBytesPerSecond,queueId,priority,createdAtEpochMs,updatedAtEpochMs,errorMessage,userLabel,mimeType)
                VALUES ('download-1','file.bin','https://example.com/file.bin','content://downloads','Queued','Native',0,NULL,0,'default',0,1,1,NULL,NULL,NULL)""",
        )
        legacy.execSQL("INSERT INTO checksum_expectations (id,downloadId,algorithm,expectedHex,source) VALUES ('checksum-valid','download-1','SHA256','abcd','UserInput')")
        legacy.execSQL("INSERT INTO checksum_expectations (id,downloadId,algorithm,expectedHex,source) VALUES ('checksum-orphan','missing-download','SHA256','ef01','UserInput')")
        legacy.execSQL(
            """INSERT INTO automation_commands
                (id,idempotencyKey,source,action,url,fileName,pageTitle,pageUrl,mediaCaptureId,downloadId,status,resultMessage,createdAtEpochMs,updatedAtEpochMs)
                VALUES ('command-1','key-1','Tasker','EnqueueDownload','https://example.com/file.bin',NULL,NULL,NULL,NULL,'missing-download','Received','legacy',1,1)""",
        )
        legacy.close()

        val db = helper.runMigrationsAndValidate(name, 18, true, Migrations.Migration17To18)
        assertEquals(1L, db.longValue("SELECT attemptGeneration FROM downloads WHERE id='download-1'"))
        assertEquals(1L, db.longValue("SELECT attemptGeneration FROM checksum_expectations WHERE id='checksum-valid'"))
        assertEquals(0L, db.longValue("SELECT createdAtEpochMs FROM checksum_expectations WHERE id='checksum-valid'"))
        assertFalse(db.exists("SELECT 1 FROM checksum_expectations WHERE id='checksum-orphan'"))
        assertTrue(db.isNull("SELECT downloadId FROM automation_commands WHERE id='command-1'"))
        assertTrue(db.columnNames("automation_commands").contains("metadataJson"))
        assertTrue(db.hasForeignKey("checksum_expectations", "downloadId", "downloads", "CASCADE"))
        assertTrue(db.hasForeignKey("automation_commands", "downloadId", "downloads", "SET NULL"))
        assertEquals(18L, db.longValue("PRAGMA user_version"))
        db.close()
    }

    @Test
    fun migrate4To18ValidatesOldestExportedProductionChain() {
        val name = "generation-integrity-4-18-${System.nanoTime()}"
        helper.createDatabase(name, 4).close()
        val db = helper.runMigrationsAndValidate(
            name,
            18,
            true,
            Migrations.Migration4To5,
            Migrations.Migration5To6,
            Migrations.Migration6To7,
            Migrations.Migration7To8,
            Migrations.Migration8To9,
            Migrations.Migration9To10,
            Migrations.Migration10To11,
            Migrations.Migration11To12,
            Migrations.Migration12To13,
            Migrations.Migration13To14,
            Migrations.Migration14To15,
            Migrations.Migration15To16,
            Migrations.Migration16To17,
            Migrations.Migration17To18,
        )
        assertEquals(18L, db.longValue("PRAGMA user_version"))
        assertTrue(db.columnNames("downloads").contains("attemptGeneration"))
        assertTrue(db.hasForeignKey("checksum_expectations", "downloadId", "downloads", "CASCADE"))
        db.close()
    }

    @Test
    fun migrate14To18ValidatesFullProductionChain() {
        val name = "generation-integrity-14-18-${System.nanoTime()}"
        helper.createDatabase(name, 14).close()
        val db = helper.runMigrationsAndValidate(
            name,
            18,
            true,
            Migrations.Migration14To15,
            Migrations.Migration15To16,
            Migrations.Migration16To17,
            Migrations.Migration17To18,
        )
        assertEquals(18L, db.longValue("PRAGMA user_version"))
        assertTrue(db.columnNames("downloads").contains("attemptGeneration"))
        db.close()
    }

    private fun SupportSQLiteDatabase.longValue(sql: String): Long = query(sql).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun SupportSQLiteDatabase.exists(sql: String): Boolean = query(sql).use { it.moveToFirst() }

    private fun SupportSQLiteDatabase.isNull(sql: String): Boolean = query(sql).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.isNull(0)
    }

    private fun SupportSQLiteDatabase.columnNames(table: String): Set<String> = buildSet {
        query("PRAGMA table_info($table)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(name))
        }
    }

    private fun SupportSQLiteDatabase.hasForeignKey(table: String, fromColumn: String, parentTable: String, onDelete: String): Boolean =
        query("PRAGMA foreign_key_list($table)").use { cursor ->
            val parent = cursor.getColumnIndexOrThrow("table")
            val from = cursor.getColumnIndexOrThrow("from")
            val delete = cursor.getColumnIndexOrThrow("on_delete")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(parent) == parentTable && cursor.getString(from) == fromColumn && cursor.getString(delete) == onDelete) {
                    found = true
                    break
                }
            }
            found
        }
}
