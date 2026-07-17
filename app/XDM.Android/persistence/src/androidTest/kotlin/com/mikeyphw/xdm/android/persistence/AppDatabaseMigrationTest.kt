package com.mikeyphw.xdm.android.persistence

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @Test
    fun migrate1To2AddsUserLabel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${System.nanoTime()}.db"
        createVersionOne(context, name).close()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        Migrations.Migration1To2.migrate(db)
                    }
                })
                .build(),
        )

        helper.writableDatabase.query("PRAGMA table_info(downloads)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == "userLabel") found = true
            }
            assertTrue("Migration must add userLabel", found)
        }
        helper.close()
        context.deleteDatabase(name)
    }


    @Test
    fun migrate2To3AddsDestinationOwnership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-v3-${System.nanoTime()}.db"
        val seed = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE backend_tasks (id TEXT NOT NULL PRIMARY KEY, downloadId TEXT NOT NULL, backend TEXT NOT NULL, backendTaskId TEXT NOT NULL, ownershipGeneration INTEGER NOT NULL, lastSynchronizedAtEpochMs INTEGER NOT NULL)""")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        seed.writableDatabase
        seed.close()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        Migrations.Migration2To3.migrate(db)
                    }
                })
                .build()
        )
        helper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name='destination_claims'").use { cursor ->
            assertTrue("Migration must create destination_claims", cursor.moveToFirst())
        }
        helper.close()
        context.deleteDatabase(name)
    }


    @Test
    fun migrate3To4AddsDestinationPolicyAndHealth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-v4-${System.nanoTime()}.db"
        val seed = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE downloads (id TEXT NOT NULL PRIMARY KEY, fileName TEXT NOT NULL, sourceUrl TEXT NOT NULL, destinationUri TEXT NOT NULL, state TEXT NOT NULL, backend TEXT NOT NULL, bytesReceived INTEGER NOT NULL, totalBytes INTEGER, speedBytesPerSecond INTEGER NOT NULL, queueId TEXT, priority INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, errorMessage TEXT, userLabel TEXT)""")
                        db.execSQL("""CREATE TABLE destination_permissions (uri TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, providerType TEXT NOT NULL, persistedRead INTEGER NOT NULL, persistedWrite INTEGER NOT NULL, lastValidatedAtEpochMs INTEGER NOT NULL)""")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        seed.writableDatabase
        seed.close()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        Migrations.Migration3To4.migrate(db)
                    }
                })
                .build(),
        )
        helper.writableDatabase.query("PRAGMA table_info(downloads)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) columns += cursor.getString(nameColumn)
            assertTrue("Migration must add conflictPolicy", "conflictPolicy" in columns)
            assertTrue("Migration must add mimeType", "mimeType" in columns)
        }
        helper.writableDatabase.query("PRAGMA table_info(destination_permissions)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) columns += cursor.getString(nameColumn)
            assertTrue("Migration must add status", "status" in columns)
            assertTrue("Migration must add lastError", "lastError" in columns)
        }
        helper.close()
        context.deleteDatabase(name)
    }


    @Test
    fun migrate4To5AddsRuntimeAndReconciliationMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-v5-${System.nanoTime()}.db"
        val seed = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE backend_tasks (id TEXT NOT NULL PRIMARY KEY, downloadId TEXT NOT NULL, backend TEXT NOT NULL, backendTaskId TEXT NOT NULL, destinationKey TEXT NOT NULL DEFAULT '', partialIdentity TEXT NOT NULL DEFAULT '', ownershipGeneration INTEGER NOT NULL, ownershipStatus TEXT NOT NULL DEFAULT 'Active', lastSynchronizedAtEpochMs INTEGER NOT NULL)""")
                        db.execSQL("""CREATE TABLE destination_claims (destinationKey TEXT NOT NULL PRIMARY KEY, downloadId TEXT NOT NULL, backend TEXT NOT NULL, partialIdentity TEXT NOT NULL, generation INTEGER NOT NULL, status TEXT NOT NULL, claimedAtEpochMs INTEGER NOT NULL, synchronizedAtEpochMs INTEGER NOT NULL)""")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        seed.writableDatabase
        seed.close()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        Migrations.Migration4To5.migrate(db)
                    }
                })
                .build(),
        )
        listOf("backend_tasks", "destination_claims").forEach { table ->
            helper.writableDatabase.query("PRAGMA table_info($table)").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(nameColumn)
                assertTrue("Migration must add artifactFormat to $table", "artifactFormat" in columns)
                assertTrue("Migration must add backendInstanceId to $table", "backendInstanceId" in columns)
                assertTrue("Migration must add backendSessionId to $table", "backendSessionId" in columns)
                assertTrue("Migration must add reconciliation to $table", "reconciliation" in columns)
            }
        }
        helper.close()
        context.deleteDatabase(name)
    }


    @Test
    fun migrate5To6ReplacesUnsafeLegacyAria2Mappings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-v6-${System.nanoTime()}.db"
        val seed = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE aria2_session_mappings (id TEXT NOT NULL PRIMARY KEY, downloadId TEXT NOT NULL, gid TEXT NOT NULL, sessionFilePath TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL)""")
                        db.execSQL("CREATE UNIQUE INDEX index_aria2_session_mappings_downloadId ON aria2_session_mappings(downloadId)")
                        db.execSQL("CREATE UNIQUE INDEX index_aria2_session_mappings_gid ON aria2_session_mappings(gid)")
                        db.execSQL("INSERT INTO aria2_session_mappings VALUES ('legacy', 'download', 'gid', '/legacy/session', 1)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        seed.writableDatabase
        seed.close()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        Migrations.Migration5To6.migrate(db)
                    }
                })
                .build(),
        )
        helper.writableDatabase.query("PRAGMA table_info(aria2_session_mappings)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) columns += cursor.getString(nameColumn)
            assertTrue("Migration must add source identity", "sourceUrl" in columns)
            assertTrue("Migration must add destination identity", "destinationKey" in columns)
            assertTrue("Migration must add ownership generation", "ownershipGeneration" in columns)
            assertTrue("Migration must add physical output path", "outputPath" in columns)
        }
        helper.writableDatabase.query("SELECT COUNT(*) FROM aria2_session_mappings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("Unsafe legacy mappings must not be adopted", cursor.getLong(0) == 0L)
        }
        helper.close()
        context.deleteDatabase(name)
    }

    private fun createVersionOne(context: Context, name: String): SupportSQLiteOpenHelper {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE downloads (
                                id TEXT NOT NULL PRIMARY KEY,
                                fileName TEXT NOT NULL,
                                sourceUrl TEXT NOT NULL,
                                destinationUri TEXT NOT NULL,
                                state TEXT NOT NULL,
                                backend TEXT NOT NULL,
                                bytesReceived INTEGER NOT NULL,
                                totalBytes INTEGER,
                                speedBytesPerSecond INTEGER NOT NULL,
                                queueId TEXT,
                                priority INTEGER NOT NULL,
                                createdAtEpochMs INTEGER NOT NULL,
                                updatedAtEpochMs INTEGER NOT NULL,
                                errorMessage TEXT
                            )""".trimIndent(),
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase
        return helper
    }
}
