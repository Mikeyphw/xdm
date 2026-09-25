package sqlite

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
)

const CurrentSchemaVersion = 1

// SchemaV1 deliberately models canonical engine ownership rather than mirroring
// either Room or the legacy desktop JSON shape.
const SchemaV1 = `
CREATE TABLE IF NOT EXISTS engine_metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY CHECK(version > 0),
    checksum TEXT NOT NULL,
    applied_at_unix_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS download_requests (
    request_id TEXT NOT NULL,
    request_revision INTEGER NOT NULL CHECK(request_revision > 0),
    resource_identity TEXT NOT NULL,
    method TEXT NOT NULL,
    spec_json TEXT NOT NULL,
    created_at_unix_ms INTEGER NOT NULL,
    PRIMARY KEY(request_id, request_revision)
);
CREATE TABLE IF NOT EXISTS downloads (
    download_id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL,
    current_request_revision INTEGER NOT NULL CHECK(current_request_revision > 0),
    current_attempt_generation INTEGER CHECK(current_attempt_generation > 0),
    current_artifact_generation INTEGER CHECK(current_artifact_generation > 0),
    state TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at_unix_ms INTEGER NOT NULL,
    updated_at_unix_ms INTEGER NOT NULL,
    FOREIGN KEY(request_id, current_request_revision)
      REFERENCES download_requests(request_id, request_revision)
      ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS download_attempts (
    download_id TEXT NOT NULL,
    attempt_generation INTEGER NOT NULL CHECK(attempt_generation > 0),
    backend_kind TEXT NOT NULL,
    state TEXT NOT NULL,
    failure_category TEXT,
    failure_payload_json TEXT,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at_unix_ms INTEGER NOT NULL,
    updated_at_unix_ms INTEGER NOT NULL,
    PRIMARY KEY(download_id, attempt_generation),
    FOREIGN KEY(download_id) REFERENCES downloads(download_id) ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS backend_ownership (
    download_id TEXT NOT NULL,
    attempt_generation INTEGER NOT NULL CHECK(attempt_generation > 0),
    backend_kind TEXT NOT NULL,
    runtime_identity TEXT,
    staging_identity TEXT,
    ownership_state TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at_unix_ms INTEGER NOT NULL,
    updated_at_unix_ms INTEGER NOT NULL,
    PRIMARY KEY(download_id, attempt_generation),
    FOREIGN KEY(download_id, attempt_generation)
      REFERENCES download_attempts(download_id, attempt_generation)
      ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS backend_tasks (
    backend_task_id TEXT PRIMARY KEY,
    download_id TEXT NOT NULL,
    attempt_generation INTEGER NOT NULL CHECK(attempt_generation > 0),
    backend_kind TEXT NOT NULL,
    external_task_id TEXT,
    runtime_identity TEXT,
    task_state TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at_unix_ms INTEGER NOT NULL,
    updated_at_unix_ms INTEGER NOT NULL,
    FOREIGN KEY(download_id, attempt_generation)
      REFERENCES download_attempts(download_id, attempt_generation)
      ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS artifacts (
    download_id TEXT NOT NULL,
    artifact_generation INTEGER NOT NULL CHECK(artifact_generation > 0),
    source_attempt_generation INTEGER NOT NULL CHECK(source_attempt_generation > 0),
    staging_identity TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK(size_bytes >= 0),
    verification_state TEXT NOT NULL,
    publication_state TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at_unix_ms INTEGER NOT NULL,
    updated_at_unix_ms INTEGER NOT NULL,
    PRIMARY KEY(download_id, artifact_generation),
    FOREIGN KEY(download_id, source_attempt_generation)
      REFERENCES download_attempts(download_id, attempt_generation)
      ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS publication_transactions (
    publication_id TEXT PRIMARY KEY,
    download_id TEXT NOT NULL,
    artifact_generation INTEGER NOT NULL CHECK(artifact_generation > 0),
    state TEXT NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    platform_receipt TEXT,
    published_location TEXT,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at_unix_ms INTEGER NOT NULL,
    updated_at_unix_ms INTEGER NOT NULL,
    FOREIGN KEY(download_id, artifact_generation)
      REFERENCES artifacts(download_id, artifact_generation)
      ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS segments (
    download_id TEXT NOT NULL,
    attempt_generation INTEGER NOT NULL CHECK(attempt_generation > 0),
    segment_index INTEGER NOT NULL CHECK(segment_index >= 0),
    start_byte INTEGER NOT NULL CHECK(start_byte >= 0),
    end_byte INTEGER NOT NULL CHECK(end_byte >= start_byte),
    state TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    PRIMARY KEY(download_id, attempt_generation, segment_index),
    FOREIGN KEY(download_id, attempt_generation)
      REFERENCES download_attempts(download_id, attempt_generation)
      ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS checkpoint_blocks (
    download_id TEXT NOT NULL,
    attempt_generation INTEGER NOT NULL CHECK(attempt_generation > 0),
    block_index INTEGER NOT NULL CHECK(block_index >= 0),
    start_byte INTEGER NOT NULL CHECK(start_byte >= 0),
    committed_length INTEGER NOT NULL CHECK(committed_length >= 0),
    hash_algorithm TEXT NOT NULL,
    hash_hex TEXT NOT NULL,
    state TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    committed_at_unix_ms INTEGER NOT NULL,
    PRIMARY KEY(download_id, attempt_generation, block_index),
    FOREIGN KEY(download_id, attempt_generation)
      REFERENCES download_attempts(download_id, attempt_generation)
      ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS verification_records (
    verification_id TEXT PRIMARY KEY,
    download_id TEXT NOT NULL,
    attempt_generation INTEGER NOT NULL CHECK(attempt_generation > 0),
    artifact_generation INTEGER CHECK(artifact_generation > 0),
    algorithm TEXT NOT NULL,
    expected_value TEXT,
    actual_value TEXT,
    result TEXT NOT NULL,
    verifier_version TEXT NOT NULL,
    created_at_unix_ms INTEGER NOT NULL,
    FOREIGN KEY(download_id, attempt_generation)
      REFERENCES download_attempts(download_id, attempt_generation)
      ON DELETE RESTRICT
);
CREATE TABLE IF NOT EXISTS queues (
    queue_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
    priority INTEGER NOT NULL,
    policy_json TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at_unix_ms INTEGER NOT NULL,
    updated_at_unix_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS queue_membership (
    queue_id TEXT NOT NULL,
    download_id TEXT NOT NULL UNIQUE,
    position INTEGER NOT NULL CHECK(position >= 0),
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    PRIMARY KEY(queue_id, download_id),
    FOREIGN KEY(queue_id) REFERENCES queues(queue_id) ON DELETE CASCADE,
    FOREIGN KEY(download_id) REFERENCES downloads(download_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS schedule_definitions (
    schedule_id TEXT PRIMARY KEY,
    definition_json TEXT NOT NULL,
    enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at_unix_ms INTEGER NOT NULL,
    updated_at_unix_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS media_items (
    media_id TEXT PRIMARY KEY,
    logical_identity TEXT NOT NULL UNIQUE,
    media_kind TEXT NOT NULL,
    metadata_json TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at_unix_ms INTEGER NOT NULL,
    updated_at_unix_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS media_sources (
    source_id TEXT PRIMARY KEY,
    media_id TEXT NOT NULL,
    resource_identity TEXT NOT NULL,
    transport_url_safe TEXT NOT NULL,
    origin TEXT NOT NULL,
    credential_scope_ref TEXT,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at_unix_ms INTEGER NOT NULL,
    updated_at_unix_ms INTEGER NOT NULL,
    FOREIGN KEY(media_id) REFERENCES media_items(media_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS media_variants (
    variant_id TEXT PRIMARY KEY,
    media_id TEXT NOT NULL,
    attributes_json TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at_unix_ms INTEGER NOT NULL,
    updated_at_unix_ms INTEGER NOT NULL,
    FOREIGN KEY(media_id) REFERENCES media_items(media_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS media_tracks (
    track_id TEXT PRIMARY KEY,
    media_id TEXT NOT NULL,
    variant_id TEXT,
    role TEXT NOT NULL,
    attributes_json TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision > 0),
    created_at_unix_ms INTEGER NOT NULL,
    updated_at_unix_ms INTEGER NOT NULL,
    FOREIGN KEY(media_id) REFERENCES media_items(media_id) ON DELETE CASCADE,
    FOREIGN KEY(variant_id) REFERENCES media_variants(variant_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS diagnostic_events (
    event_id TEXT PRIMARY KEY,
    subsystem TEXT NOT NULL,
    operation_id TEXT,
    download_id TEXT,
    attempt_generation INTEGER,
    severity TEXT NOT NULL,
    event_type TEXT NOT NULL,
    safe_payload_json TEXT NOT NULL,
    created_at_unix_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_attempts_state ON download_attempts(state);
CREATE INDEX IF NOT EXISTS idx_backend_tasks_attempt ON backend_tasks(download_id, attempt_generation);
CREATE INDEX IF NOT EXISTS idx_artifacts_download ON artifacts(download_id, artifact_generation);
CREATE INDEX IF NOT EXISTS idx_publication_state ON publication_transactions(state);
CREATE INDEX IF NOT EXISTS idx_checkpoint_attempt ON checkpoint_blocks(download_id, attempt_generation, block_index);
CREATE INDEX IF NOT EXISTS idx_queue_position ON queue_membership(queue_id, position);
CREATE INDEX IF NOT EXISTS idx_media_source_media ON media_sources(media_id);
CREATE INDEX IF NOT EXISTS idx_diagnostic_download ON diagnostic_events(download_id, created_at_unix_ms);
`

func SchemaChecksum() string {
	sum := sha256.Sum256([]byte(strings.TrimSpace(SchemaV1)))
	return hex.EncodeToString(sum[:])
}

func Migrate(ctx context.Context, db *DB, nowUnixMS int64) error {
	if db == nil {
		return ErrClosed
	}
	tx, err := db.BeginImmediate(ctx)
	if err != nil {
		return err
	}
	committed := false
	defer func() {
		if !committed {
			_ = tx.Rollback(context.Background())
		}
	}()

	if err := tx.ExecScript(ctx, `CREATE TABLE IF NOT EXISTS engine_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY CHECK(version > 0), checksum TEXT NOT NULL, applied_at_unix_ms INTEGER NOT NULL);`); err != nil {
		return err
	}
	rows, err := tx.Query(ctx, "PRAGMA user_version")
	if err != nil {
		return err
	}
	if len(rows) != 1 || len(rows[0]) != 1 || rows[0][0].Kind != Integer {
		return fmt.Errorf("invalid PRAGMA user_version response")
	}
	version := rows[0][0].I64
	if version > CurrentSchemaVersion {
		return fmt.Errorf("%w: have=%d supported=%d", ErrSchemaTooNew, version, CurrentSchemaVersion)
	}
	if version == 0 {
		if err := tx.ExecScript(ctx, SchemaV1); err != nil {
			return err
		}
		checksum := SchemaChecksum()
		if _, err := tx.Exec(ctx, "INSERT INTO schema_migrations(version, checksum, applied_at_unix_ms) VALUES(?,?,?)", int64(CurrentSchemaVersion), checksum, nowUnixMS); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, "INSERT INTO engine_metadata(key, value) VALUES('schema_checksum', ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", checksum); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, fmt.Sprintf("PRAGMA user_version=%d", CurrentSchemaVersion)); err != nil {
			return err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	committed = true
	return nil
}
