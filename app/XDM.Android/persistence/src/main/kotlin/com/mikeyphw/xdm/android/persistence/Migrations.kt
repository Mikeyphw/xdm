package com.mikeyphw.xdm.android.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    val Migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN userLabel TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_updatedAtEpochMs ON downloads(updatedAtEpochMs)")
        }
    }

    val Migration2To3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE backend_tasks ADD COLUMN destinationKey TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE backend_tasks ADD COLUMN partialIdentity TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE backend_tasks ADD COLUMN ownershipStatus TEXT NOT NULL DEFAULT 'Active'")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS destination_claims (
                    destinationKey TEXT NOT NULL PRIMARY KEY,
                    downloadId TEXT NOT NULL,
                    backend TEXT NOT NULL,
                    partialIdentity TEXT NOT NULL,
                    generation INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    claimedAtEpochMs INTEGER NOT NULL,
                    synchronizedAtEpochMs INTEGER NOT NULL
                )""".trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_destination_claims_downloadId ON destination_claims(downloadId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ownership_counters (name TEXT NOT NULL PRIMARY KEY, value INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO ownership_counters(name, value) VALUES ('backend-ownership', 1)")
        }
    }

    val Migration3To4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN conflictPolicy TEXT NOT NULL DEFAULT 'Rename'")
            db.execSQL("ALTER TABLE downloads ADD COLUMN mimeType TEXT")
            db.execSQL("ALTER TABLE destination_permissions ADD COLUMN status TEXT NOT NULL DEFAULT 'Unknown'")
            db.execSQL("ALTER TABLE destination_permissions ADD COLUMN lastError TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_destination_permissions_providerType ON destination_permissions(providerType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_destination_permissions_status ON destination_permissions(status)")
        }
    }

    val Migration4To5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf("backend_tasks", "destination_claims").forEach { table ->
                db.execSQL("ALTER TABLE $table ADD COLUMN artifactFormat TEXT NOT NULL DEFAULT 'legacy-partial-v1'")
                db.execSQL("ALTER TABLE $table ADD COLUMN companionArtifactIdentities TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $table ADD COLUMN backendInstanceId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $table ADD COLUMN backendSessionId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $table ADD COLUMN reconciliation TEXT NOT NULL DEFAULT 'Pending'")
                db.execSQL("ALTER TABLE $table ADD COLUMN reconciliationMessage TEXT")
                db.execSQL("ALTER TABLE $table ADD COLUMN reconciledAtEpochMs INTEGER")
            }
        }
    }

    val Migration5To6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS aria2_session_mappings_v6 (
                    id TEXT NOT NULL PRIMARY KEY,
                    downloadId TEXT NOT NULL,
                    gid TEXT NOT NULL,
                    sourceUrl TEXT NOT NULL,
                    mirrorUrls TEXT NOT NULL DEFAULT '',
                    destinationUri TEXT NOT NULL,
                    destinationKey TEXT NOT NULL,
                    fileName TEXT NOT NULL,
                    conflictPolicy TEXT NOT NULL,
                    mimeType TEXT,
                    outputPath TEXT NOT NULL,
                    controlPath TEXT NOT NULL,
                    ownershipMetadataPath TEXT NOT NULL,
                    sessionFilePath TEXT NOT NULL,
                    expectedLength INTEGER,
                    ownershipGeneration INTEGER NOT NULL,
                    backendInstanceId TEXT NOT NULL,
                    backendSessionId TEXT NOT NULL,
                    status TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL,
                    updatedAtEpochMs INTEGER NOT NULL,
                    lastSynchronizedAtEpochMs INTEGER NOT NULL,
                    lastErrorCode TEXT,
                    lastErrorMessage TEXT
                )""".trimIndent(),
            )
            db.execSQL("DROP TABLE aria2_session_mappings")
            db.execSQL("ALTER TABLE aria2_session_mappings_v6 RENAME TO aria2_session_mappings")
            db.execSQL("CREATE UNIQUE INDEX index_aria2_session_mappings_downloadId ON aria2_session_mappings(downloadId)")
            db.execSQL("CREATE UNIQUE INDEX index_aria2_session_mappings_gid ON aria2_session_mappings(gid)")
            db.execSQL("CREATE INDEX index_aria2_session_mappings_status ON aria2_session_mappings(status)")
        }
    }

}
