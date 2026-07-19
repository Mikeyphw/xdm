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

    val Migration6To7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN requestedBackend TEXT NOT NULL DEFAULT 'Automatic'")
            db.execSQL("ALTER TABLE downloads ADD COLUMN backendSelectionReason TEXT NOT NULL DEFAULT 'DefaultNative'")
            db.execSQL("ALTER TABLE downloads ADD COLUMN backendSelectionExplanation TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE downloads ADD COLUMN allowBackendFallback INTEGER NOT NULL DEFAULT 1")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS backend_migrations (
                    id TEXT NOT NULL PRIMARY KEY,
                    downloadId TEXT NOT NULL,
                    sourceBackend TEXT NOT NULL,
                    targetBackend TEXT NOT NULL,
                    sourceGeneration INTEGER NOT NULL,
                    targetGeneration INTEGER,
                    sourceTaskId TEXT,
                    targetTaskId TEXT,
                    stage TEXT NOT NULL,
                    sourceArtifactIdentity TEXT NOT NULL,
                    targetArtifactIdentity TEXT,
                    restartFromZero INTEGER NOT NULL,
                    message TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL,
                    updatedAtEpochMs INTEGER NOT NULL
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX index_backend_migrations_downloadId ON backend_migrations(downloadId)")
            db.execSQL("CREATE INDEX index_backend_migrations_stage ON backend_migrations(stage)")
            db.execSQL("CREATE INDEX index_backend_migrations_updatedAtEpochMs ON backend_migrations(updatedAtEpochMs)")
        }
    }

    val Migration7To8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE checksum_results ADD COLUMN bytesVerified INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE checksum_results ADD COLUMN expectedHex TEXT")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS verification_records (
                    id TEXT NOT NULL PRIMARY KEY,
                    downloadId TEXT NOT NULL,
                    status TEXT NOT NULL,
                    algorithm TEXT,
                    bytesVerified INTEGER NOT NULL,
                    totalBytes INTEGER,
                    message TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL,
                    updatedAtEpochMs INTEGER NOT NULL
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX index_verification_records_downloadId ON verification_records(downloadId)")
            db.execSQL("CREATE INDEX index_verification_records_status ON verification_records(status)")
            db.execSQL("CREATE INDEX index_verification_records_updatedAtEpochMs ON verification_records(updatedAtEpochMs)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS trusted_block_manifests (
                    id TEXT NOT NULL PRIMARY KEY,
                    downloadId TEXT NOT NULL,
                    fileLength INTEGER NOT NULL,
                    blockSize INTEGER NOT NULL,
                    algorithm TEXT NOT NULL,
                    blocksJson TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL
                )""".trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX index_trusted_block_manifests_downloadId ON trusted_block_manifests(downloadId)")
            db.execSQL("CREATE INDEX index_trusted_block_manifests_createdAtEpochMs ON trusted_block_manifests(createdAtEpochMs)")
        }
    }

    val Migration8To9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE recovery_records ADD COLUMN recommendedAction TEXT NOT NULL DEFAULT 'Validate'")
            db.execSQL("ALTER TABLE recovery_records ADD COLUMN safeToResume INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recovery_records_recommendedAction ON recovery_records(recommendedAction)")
            db.execSQL("ALTER TABLE finalization_journals ADD COLUMN stagingPath TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE finalization_journals ADD COLUMN bytesExpected INTEGER")
            db.execSQL("ALTER TABLE finalization_journals ADD COLUMN bytesPromoted INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE finalization_journals ADD COLUMN checksumAlgorithm TEXT")
            db.execSQL("ALTER TABLE finalization_journals ADD COLUMN checksumHex TEXT")
            db.execSQL("ALTER TABLE finalization_journals ADD COLUMN message TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE finalization_journals ADD COLUMN createdAtEpochMs INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_finalization_journals_stage ON finalization_journals(stage)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_finalization_journals_updatedAtEpochMs ON finalization_journals(updatedAtEpochMs)")
        }
    }


    val Migration9To10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS media_captures (
                    id TEXT NOT NULL PRIMARY KEY,
                    sourceUrl TEXT NOT NULL,
                    pageUrl TEXT,
                    title TEXT NOT NULL,
                    status TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    mimeType TEXT,
                    container TEXT,
                    codecs TEXT,
                    durationMs INTEGER,
                    thumbnailUrl TEXT,
                    fileName TEXT NOT NULL,
                    variantCount INTEGER NOT NULL,
                    downloadId TEXT,
                    createdAtEpochMs INTEGER NOT NULL,
                    updatedAtEpochMs INTEGER NOT NULL
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX index_media_captures_downloadId ON media_captures(downloadId)")
            db.execSQL("CREATE INDEX index_media_captures_status ON media_captures(status)")
            db.execSQL("CREATE INDEX index_media_captures_kind ON media_captures(kind)")
            db.execSQL("CREATE INDEX index_media_captures_updatedAtEpochMs ON media_captures(updatedAtEpochMs)")
        }
    }


    val Migration10To11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE media_captures ADD COLUMN selectedVariantId TEXT")
            db.execSQL("ALTER TABLE media_captures ADD COLUMN selectedVariantUrl TEXT")
            db.execSQL("ALTER TABLE media_captures ADD COLUMN manifestExpiresAtEpochMs INTEGER")
            db.execSQL("ALTER TABLE media_captures ADD COLUMN lastResolvedAtEpochMs INTEGER")
            db.execSQL("ALTER TABLE media_captures ADD COLUMN resolutionStatus TEXT NOT NULL DEFAULT 'Unresolved'")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS media_variants (
                    id TEXT NOT NULL PRIMARY KEY,
                    captureId TEXT NOT NULL,
                    url TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    mimeType TEXT,
                    width INTEGER,
                    height INTEGER,
                    bitrateBitsPerSecond INTEGER,
                    codecs TEXT,
                    language TEXT,
                    position INTEGER NOT NULL,
                    displayLabel TEXT NOT NULL DEFAULT '',
                    expiresAtEpochMs INTEGER
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_media_variants_captureId ON media_variants(captureId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_media_variants_kind ON media_variants(kind)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_media_variants_position ON media_variants(position)")
        }
    }


    val Migration11To12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS automation_commands (
                    id TEXT NOT NULL PRIMARY KEY,
                    idempotencyKey TEXT NOT NULL,
                    source TEXT NOT NULL,
                    action TEXT NOT NULL,
                    url TEXT,
                    fileName TEXT,
                    pageTitle TEXT,
                    pageUrl TEXT,
                    mediaCaptureId TEXT,
                    downloadId TEXT,
                    status TEXT NOT NULL,
                    resultMessage TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL,
                    updatedAtEpochMs INTEGER NOT NULL
                )""".trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX index_automation_commands_idempotencyKey ON automation_commands(idempotencyKey)")
            db.execSQL("CREATE INDEX index_automation_commands_source ON automation_commands(source)")
            db.execSQL("CREATE INDEX index_automation_commands_action ON automation_commands(action)")
            db.execSQL("CREATE INDEX index_automation_commands_status ON automation_commands(status)")
            db.execSQL("CREATE INDEX index_automation_commands_updatedAtEpochMs ON automation_commands(updatedAtEpochMs)")
        }
    }

    val Migration12To13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE automation_commands ADD COLUMN originPackage TEXT")
            db.execSQL("ALTER TABLE automation_commands ADD COLUMN originHost TEXT")
            db.execSQL("ALTER TABLE automation_commands ADD COLUMN sanitizedHeaders TEXT")
            db.execSQL("ALTER TABLE automation_commands ADD COLUMN rejectionReason TEXT NOT NULL DEFAULT 'None'")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_automation_commands_originHost ON automation_commands(originHost)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_automation_commands_rejectionReason ON automation_commands(rejectionReason)")
        }
    }

}
