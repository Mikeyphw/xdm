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
            db.execSQL(
                """INSERT INTO aria2_session_mappings_v6(
                    id, downloadId, gid, sourceUrl, mirrorUrls, destinationUri, destinationKey, fileName,
                    conflictPolicy, mimeType, outputPath, controlPath, ownershipMetadataPath, sessionFilePath,
                    expectedLength, ownershipGeneration, backendInstanceId, backendSessionId, status,
                    createdAtEpochMs, updatedAtEpochMs, lastSynchronizedAtEpochMs, lastErrorCode, lastErrorMessage
                )
                SELECT id, downloadId, gid, '', '', '', 'legacy:' || downloadId, 'unknown',
                    'Rename', NULL, '', '', '', sessionFilePath, NULL, 0, '', '', 'RecoveryRequired',
                    updatedAtEpochMs, updatedAtEpochMs, updatedAtEpochMs, 'LEGACY_SCHEMA',
                    'Legacy v5 aria2 mapping preserved for review instead of being discarded.'
                FROM aria2_session_mappings""".trimIndent(),
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

    val Migration13To14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_archived ON downloads(archived)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS saved_searches (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    query TEXT NOT NULL,
                    state TEXT,
                    includeArchived INTEGER NOT NULL DEFAULT 0,
                    createdAtEpochMs INTEGER NOT NULL
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_saved_searches_name ON saved_searches(name)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS duplicate_url_rules (
                    id TEXT NOT NULL PRIMARY KEY,
                    hostPattern TEXT NOT NULL,
                    action TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_duplicate_url_rules_hostPattern ON duplicate_url_rules(hostPattern)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_duplicate_url_rules_enabled ON duplicate_url_rules(enabled)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS destination_rules (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    match TEXT NOT NULL,
                    pattern TEXT NOT NULL,
                    destinationUri TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    priority INTEGER NOT NULL DEFAULT 0
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_destination_rules_enabled ON destination_rules(enabled)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_destination_rules_priority ON destination_rules(priority)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS clipboard_inbox (
                    id TEXT NOT NULL PRIMARY KEY,
                    url TEXT NOT NULL,
                    title TEXT,
                    sourceTextHash TEXT NOT NULL,
                    status TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL,
                    updatedAtEpochMs INTEGER NOT NULL
                )""".trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_clipboard_inbox_url ON clipboard_inbox(url)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_clipboard_inbox_status ON clipboard_inbox(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_clipboard_inbox_updatedAtEpochMs ON clipboard_inbox(updatedAtEpochMs)")
        }
    }


    val Migration14To15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE automation_commands ADD COLUMN claimedOriginPackage TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE automation_commands ADD COLUMN verifiedIntegrationId TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE automation_commands ADD COLUMN authorization TEXT NOT NULL DEFAULT 'Untrusted'")
            db.execSQL("ALTER TABLE automation_commands ADD COLUMN privateNetworkApproved INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE automation_commands ADD COLUMN cleartextCredentialsApproved INTEGER NOT NULL DEFAULT 0")
        }
    }

    val Migration15To16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS post_processing_jobs (
                    id TEXT NOT NULL PRIMARY KEY,
                    rootJobId TEXT NOT NULL,
                    parentJobId TEXT,
                    attemptGeneration INTEGER NOT NULL,
                    claimKey TEXT,
                    subjectId TEXT NOT NULL,
                    subjectType TEXT NOT NULL,
                    subjectGeneration INTEGER NOT NULL,
                    downloadId TEXT,
                    captureId TEXT,
                    ruleId TEXT,
                    actionId TEXT NOT NULL,
                    trigger TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    status TEXT NOT NULL,
                    title TEXT NOT NULL,
                    inputUri TEXT NOT NULL,
                    stagedInputPath TEXT,
                    inputBridgeUri TEXT,
                    outputDisplayName TEXT NOT NULL,
                    outputMimeType TEXT NOT NULL,
                    outputDestinationUri TEXT,
                    stagedOutputPath TEXT,
                    outputBridgeUri TEXT,
                    ownerBridgeUri TEXT,
                    progressBridgeUri TEXT,
                    metadataBridgeUri TEXT,
                    payloadBridgeUri TEXT,
                    finalOutputUri TEXT,
                    immutableSpecJson TEXT NOT NULL,
                    expectedSha256 TEXT,
                    actualSha256 TEXT,
                    requiredTools TEXT NOT NULL,
                    toolVersionsJson TEXT NOT NULL,
                    runId TEXT,
                    executionId INTEGER,
                    processToken TEXT,
                    processId INTEGER,
                    controlGeneration INTEGER NOT NULL,
                    requestedControl TEXT,
                    progressPercent INTEGER NOT NULL,
                    progressBytes INTEGER NOT NULL,
                    progressTotalBytes INTEGER,
                    timeoutAtEpochMs INTEGER,
                    resultStdoutLength INTEGER NOT NULL,
                    resultStderrLength INTEGER NOT NULL,
                    metadataJson TEXT,
                    message TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL,
                    updatedAtEpochMs INTEGER NOT NULL,
                    startedAtEpochMs INTEGER,
                    finishedAtEpochMs INTEGER
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_post_processing_jobs_rootJobId ON post_processing_jobs(rootJobId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_post_processing_jobs_subjectId ON post_processing_jobs(subjectId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_post_processing_jobs_downloadId ON post_processing_jobs(downloadId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_post_processing_jobs_captureId ON post_processing_jobs(captureId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_post_processing_jobs_status ON post_processing_jobs(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_post_processing_jobs_updatedAtEpochMs ON post_processing_jobs(updatedAtEpochMs)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_post_processing_jobs_runId ON post_processing_jobs(runId)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS post_processing_claims (
                    claimKey TEXT NOT NULL PRIMARY KEY,
                    subjectId TEXT NOT NULL,
                    subjectType TEXT NOT NULL,
                    subjectGeneration INTEGER NOT NULL,
                    trigger TEXT NOT NULL,
                    ruleId TEXT NOT NULL,
                    actionId TEXT NOT NULL,
                    jobId TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL,
                    FOREIGN KEY(jobId) REFERENCES post_processing_jobs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_post_processing_claims_jobId ON post_processing_claims(jobId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_post_processing_claims_subjectId ON post_processing_claims(subjectId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_post_processing_claims_subjectId_subjectGeneration_trigger_ruleId_actionId ON post_processing_claims(subjectId, subjectGeneration, trigger, ruleId, actionId)")
        }
    }

    val Migration16To17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE post_processing_jobs ADD COLUMN publicationState TEXT NOT NULL DEFAULT 'None'")
            db.execSQL("ALTER TABLE post_processing_jobs ADD COLUMN publicationDisplayName TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE post_processing_jobs ADD COLUMN publicationExpectedBytes INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE post_processing_jobs ADD COLUMN publicationExpectedSha256 TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE post_processing_jobs ADD COLUMN committedOutputUri TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE post_processing_jobs ADD COLUMN committedBytes INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE post_processing_jobs ADD COLUMN committedSha256 TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE post_processing_jobs ADD COLUMN sideEffectOutcome TEXT DEFAULT NULL")
        }
    }


    val Migration17To18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN attemptGeneration INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE recovery_records ADD COLUMN attemptGeneration INTEGER NOT NULL DEFAULT 1")

            rebuild(
                db,
                "checkpoints",
                """CREATE TABLE checkpoints_new (`id` TEXT NOT NULL, `downloadId` TEXT NOT NULL, `checkpointJson` TEXT NOT NULL, `persistedAtEpochMs` INTEGER NOT NULL, `attemptGeneration` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`), FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                """INSERT INTO checkpoints_new (id, downloadId, checkpointJson, persistedAtEpochMs, attemptGeneration)
                    SELECT id, downloadId, checkpointJson, persistedAtEpochMs, 1 FROM checkpoints
                    WHERE EXISTS(SELECT 1 FROM downloads WHERE downloads.id = checkpoints.downloadId)""",
                listOf("CREATE UNIQUE INDEX index_checkpoints_downloadId ON checkpoints(downloadId)"),
            )
            rebuild(
                db,
                "checksum_expectations",
                """CREATE TABLE checksum_expectations_new (`id` TEXT NOT NULL, `downloadId` TEXT NOT NULL, `algorithm` TEXT NOT NULL, `expectedHex` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL DEFAULT 0, `attemptGeneration` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`), FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                """INSERT INTO checksum_expectations_new (id, downloadId, algorithm, expectedHex, source, createdAtEpochMs, attemptGeneration)
                    SELECT id, downloadId, algorithm, expectedHex, source, 0, 1 FROM checksum_expectations
                    WHERE EXISTS(SELECT 1 FROM downloads WHERE downloads.id = checksum_expectations.downloadId)""",
                listOf(
                    "CREATE INDEX index_checksum_expectations_downloadId ON checksum_expectations(downloadId)",
                    "CREATE UNIQUE INDEX index_checksum_expectations_downloadId_algorithm ON checksum_expectations(downloadId, algorithm)",
                ),
            )
            rebuild(
                db,
                "checksum_results",
                """CREATE TABLE checksum_results_new (`id` TEXT NOT NULL, `downloadId` TEXT NOT NULL, `algorithm` TEXT NOT NULL, `calculatedHex` TEXT NOT NULL, `matchesExpectation` INTEGER, `verifiedAtEpochMs` INTEGER NOT NULL, `bytesVerified` INTEGER NOT NULL DEFAULT 0, `expectedHex` TEXT, `attemptGeneration` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`), FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                """INSERT INTO checksum_results_new (id, downloadId, algorithm, calculatedHex, matchesExpectation, verifiedAtEpochMs, bytesVerified, expectedHex, attemptGeneration)
                    SELECT id, downloadId, algorithm, calculatedHex, matchesExpectation, verifiedAtEpochMs, bytesVerified, expectedHex, 1 FROM checksum_results
                    WHERE EXISTS(SELECT 1 FROM downloads WHERE downloads.id = checksum_results.downloadId)""",
                listOf(
                    "CREATE INDEX index_checksum_results_downloadId ON checksum_results(downloadId)",
                    "CREATE UNIQUE INDEX index_checksum_results_downloadId_algorithm ON checksum_results(downloadId, algorithm)",
                ),
            )
            rebuild(
                db,
                "verification_records",
                """CREATE TABLE verification_records_new (`id` TEXT NOT NULL, `downloadId` TEXT NOT NULL, `status` TEXT NOT NULL, `algorithm` TEXT, `bytesVerified` INTEGER NOT NULL, `totalBytes` INTEGER, `message` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `attemptGeneration` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`), FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                """INSERT INTO verification_records_new (id, downloadId, status, algorithm, bytesVerified, totalBytes, message, createdAtEpochMs, updatedAtEpochMs, attemptGeneration)
                    SELECT id, downloadId, status, algorithm, bytesVerified, totalBytes, message, createdAtEpochMs, updatedAtEpochMs, 1 FROM verification_records
                    WHERE EXISTS(SELECT 1 FROM downloads WHERE downloads.id = verification_records.downloadId)""",
                listOf(
                    "CREATE INDEX index_verification_records_downloadId ON verification_records(downloadId)",
                    "CREATE INDEX index_verification_records_status ON verification_records(status)",
                    "CREATE INDEX index_verification_records_updatedAtEpochMs ON verification_records(updatedAtEpochMs)",
                ),
            )
            rebuild(
                db,
                "trusted_block_manifests",
                """CREATE TABLE trusted_block_manifests_new (`id` TEXT NOT NULL, `downloadId` TEXT NOT NULL, `fileLength` INTEGER NOT NULL, `blockSize` INTEGER NOT NULL, `algorithm` TEXT NOT NULL, `blocksJson` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `attemptGeneration` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`), FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                """INSERT INTO trusted_block_manifests_new (id, downloadId, fileLength, blockSize, algorithm, blocksJson, createdAtEpochMs, attemptGeneration)
                    SELECT id, downloadId, fileLength, blockSize, algorithm, blocksJson, createdAtEpochMs, 1 FROM trusted_block_manifests
                    WHERE EXISTS(SELECT 1 FROM downloads WHERE downloads.id = trusted_block_manifests.downloadId)""",
                listOf(
                    "CREATE UNIQUE INDEX index_trusted_block_manifests_downloadId ON trusted_block_manifests(downloadId)",
                    "CREATE INDEX index_trusted_block_manifests_createdAtEpochMs ON trusted_block_manifests(createdAtEpochMs)",
                ),
            )
            rebuild(
                db,
                "schedule_rules",
                """CREATE TABLE schedule_rules_new (`id` TEXT NOT NULL, `queueId` TEXT, `name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `constraintsJson` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`queueId`) REFERENCES `queues`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""",
                """INSERT INTO schedule_rules_new (id, queueId, name, enabled, constraintsJson)
                    SELECT id, CASE WHEN queueId IS NULL OR EXISTS(SELECT 1 FROM queues WHERE queues.id = schedule_rules.queueId) THEN queueId ELSE NULL END, name, enabled, constraintsJson FROM schedule_rules""",
                listOf("CREATE INDEX index_schedule_rules_queueId ON schedule_rules(queueId)"),
            )
            rebuild(
                db,
                "finalization_journals",
                """CREATE TABLE finalization_journals_new (`id` TEXT NOT NULL, `downloadId` TEXT NOT NULL, `stage` TEXT NOT NULL, `sourcePath` TEXT NOT NULL, `destinationUri` TEXT NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `stagingPath` TEXT NOT NULL DEFAULT '', `bytesExpected` INTEGER, `bytesPromoted` INTEGER NOT NULL DEFAULT 0, `checksumAlgorithm` TEXT, `checksumHex` TEXT, `message` TEXT NOT NULL DEFAULT '', `createdAtEpochMs` INTEGER NOT NULL DEFAULT 0, `attemptGeneration` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`), FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                """INSERT INTO finalization_journals_new (id, downloadId, stage, sourcePath, destinationUri, updatedAtEpochMs, stagingPath, bytesExpected, bytesPromoted, checksumAlgorithm, checksumHex, message, createdAtEpochMs, attemptGeneration)
                    SELECT id, downloadId, stage, sourcePath, destinationUri, updatedAtEpochMs, stagingPath, bytesExpected, bytesPromoted, checksumAlgorithm, checksumHex, message, createdAtEpochMs, 1 FROM finalization_journals
                    WHERE EXISTS(SELECT 1 FROM downloads WHERE downloads.id = finalization_journals.downloadId)""",
                listOf(
                    "CREATE UNIQUE INDEX index_finalization_journals_downloadId ON finalization_journals(downloadId)",
                    "CREATE INDEX index_finalization_journals_stage ON finalization_journals(stage)",
                    "CREATE INDEX index_finalization_journals_updatedAtEpochMs ON finalization_journals(updatedAtEpochMs)",
                ),
            )
            rebuild(
                db,
                "media_captures",
                """CREATE TABLE media_captures_new (`id` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `pageUrl` TEXT, `title` TEXT NOT NULL, `status` TEXT NOT NULL, `kind` TEXT NOT NULL, `mimeType` TEXT, `container` TEXT, `codecs` TEXT, `durationMs` INTEGER, `thumbnailUrl` TEXT, `fileName` TEXT NOT NULL, `variantCount` INTEGER NOT NULL, `downloadId` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `selectedVariantId` TEXT DEFAULT NULL, `selectedVariantUrl` TEXT DEFAULT NULL, `manifestExpiresAtEpochMs` INTEGER DEFAULT NULL, `lastResolvedAtEpochMs` INTEGER DEFAULT NULL, `resolutionStatus` TEXT NOT NULL DEFAULT 'Unresolved', PRIMARY KEY(`id`), FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""",
                """INSERT INTO media_captures_new SELECT id, sourceUrl, pageUrl, title, status, kind, mimeType, container, codecs, durationMs, thumbnailUrl, fileName, variantCount,
                    CASE WHEN downloadId IS NULL OR EXISTS(SELECT 1 FROM downloads WHERE downloads.id = media_captures.downloadId) THEN downloadId ELSE NULL END,
                    createdAtEpochMs, updatedAtEpochMs, selectedVariantId, selectedVariantUrl, manifestExpiresAtEpochMs, lastResolvedAtEpochMs, resolutionStatus FROM media_captures""",
                listOf(
                    "CREATE INDEX index_media_captures_downloadId ON media_captures(downloadId)",
                    "CREATE INDEX index_media_captures_status ON media_captures(status)",
                    "CREATE INDEX index_media_captures_kind ON media_captures(kind)",
                    "CREATE INDEX index_media_captures_updatedAtEpochMs ON media_captures(updatedAtEpochMs)",
                ),
            )
            rebuild(
                db,
                "media_variants",
                """CREATE TABLE media_variants_new (`id` TEXT NOT NULL, `captureId` TEXT NOT NULL, `url` TEXT NOT NULL, `kind` TEXT NOT NULL, `mimeType` TEXT, `width` INTEGER, `height` INTEGER, `bitrateBitsPerSecond` INTEGER, `codecs` TEXT, `language` TEXT, `position` INTEGER NOT NULL, `displayLabel` TEXT NOT NULL DEFAULT '', `expiresAtEpochMs` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`captureId`) REFERENCES `media_captures`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                """INSERT INTO media_variants_new SELECT * FROM media_variants WHERE EXISTS(SELECT 1 FROM media_captures WHERE media_captures.id = media_variants.captureId)""",
                listOf(
                    "CREATE INDEX index_media_variants_captureId ON media_variants(captureId)",
                    "CREATE INDEX index_media_variants_kind ON media_variants(kind)",
                    "CREATE INDEX index_media_variants_position ON media_variants(position)",
                ),
            )
            rebuild(
                db,
                "automation_commands",
                """CREATE TABLE automation_commands_new (`id` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL, `source` TEXT NOT NULL, `action` TEXT NOT NULL, `url` TEXT, `fileName` TEXT, `pageTitle` TEXT, `pageUrl` TEXT, `mediaCaptureId` TEXT, `downloadId` TEXT, `status` TEXT NOT NULL, `resultMessage` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, `originPackage` TEXT DEFAULT NULL, `claimedOriginPackage` TEXT DEFAULT NULL, `verifiedIntegrationId` TEXT DEFAULT NULL, `authorization` TEXT NOT NULL DEFAULT 'Untrusted', `privateNetworkApproved` INTEGER NOT NULL DEFAULT 0, `cleartextCredentialsApproved` INTEGER NOT NULL DEFAULT 0, `originHost` TEXT DEFAULT NULL, `sanitizedHeaders` TEXT DEFAULT NULL, `rejectionReason` TEXT NOT NULL DEFAULT 'None', `metadataJson` TEXT DEFAULT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(`mediaCaptureId`) REFERENCES `media_captures`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""",
                """INSERT INTO automation_commands_new (id, idempotencyKey, source, action, url, fileName, pageTitle, pageUrl, mediaCaptureId, downloadId, status, resultMessage, createdAtEpochMs, updatedAtEpochMs, originPackage, claimedOriginPackage, verifiedIntegrationId, authorization, privateNetworkApproved, cleartextCredentialsApproved, originHost, sanitizedHeaders, rejectionReason, metadataJson)
                    SELECT id, idempotencyKey, source, action, url, fileName, pageTitle, pageUrl,
                    CASE WHEN mediaCaptureId IS NULL OR EXISTS(SELECT 1 FROM media_captures WHERE media_captures.id = automation_commands.mediaCaptureId) THEN mediaCaptureId ELSE NULL END,
                    CASE WHEN downloadId IS NULL OR EXISTS(SELECT 1 FROM downloads WHERE downloads.id = automation_commands.downloadId) THEN downloadId ELSE NULL END,
                    status, resultMessage, createdAtEpochMs, updatedAtEpochMs, originPackage, claimedOriginPackage, verifiedIntegrationId, authorization, privateNetworkApproved, cleartextCredentialsApproved, originHost, sanitizedHeaders, rejectionReason, NULL FROM automation_commands""",
                listOf(
                    "CREATE UNIQUE INDEX index_automation_commands_idempotencyKey ON automation_commands(idempotencyKey)",
                    "CREATE INDEX index_automation_commands_source ON automation_commands(source)",
                    "CREATE INDEX index_automation_commands_action ON automation_commands(action)",
                    "CREATE INDEX index_automation_commands_status ON automation_commands(status)",
                    "CREATE INDEX index_automation_commands_updatedAtEpochMs ON automation_commands(updatedAtEpochMs)",
                    "CREATE INDEX index_automation_commands_downloadId ON automation_commands(downloadId)",
                    "CREATE INDEX index_automation_commands_mediaCaptureId ON automation_commands(mediaCaptureId)",
                ),
            )
            rebuild(
                db,
                "notification_records",
                """CREATE TABLE notification_records_new (`id` TEXT NOT NULL, `downloadId` TEXT, `title` TEXT NOT NULL, `message` TEXT NOT NULL, `severity` TEXT NOT NULL, `dismissed` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""",
                """INSERT INTO notification_records_new SELECT id,
                    CASE WHEN downloadId IS NULL OR EXISTS(SELECT 1 FROM downloads WHERE downloads.id = notification_records.downloadId) THEN downloadId ELSE NULL END,
                    title, message, severity, dismissed, createdAtEpochMs FROM notification_records""",
                listOf(
                    "CREATE INDEX index_notification_records_downloadId ON notification_records(downloadId)",
                    "CREATE INDEX index_notification_records_createdAtEpochMs ON notification_records(createdAtEpochMs)",
                ),
            )
            rebuild(
                db,
                "download_tags",
                """CREATE TABLE download_tags_new (`downloadId` TEXT NOT NULL, `tagId` TEXT NOT NULL, PRIMARY KEY(`downloadId`, `tagId`), FOREIGN KEY(`downloadId`) REFERENCES `downloads`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                """INSERT INTO download_tags_new SELECT downloadId, tagId FROM download_tags
                    WHERE EXISTS(SELECT 1 FROM downloads WHERE downloads.id = download_tags.downloadId)
                      AND EXISTS(SELECT 1 FROM tags WHERE tags.id = download_tags.tagId)""",
                listOf("CREATE INDEX index_download_tags_tagId ON download_tags(tagId)"),
            )
        }
    }

    private fun rebuild(
        db: SupportSQLiteDatabase,
        table: String,
        createSql: String,
        copySql: String,
        indices: List<String>,
    ) {
        db.execSQL(createSql)
        db.execSQL(copySql)
        db.execSQL("DROP TABLE $table")
        db.execSQL("ALTER TABLE ${table}_new RENAME TO $table")
        indices.forEach(db::execSQL)
    }

}
