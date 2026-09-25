//go:build cgo

package sqlite

import (
	"context"
	"errors"
	"fmt"
	"testing"
)

func migrateTestDB(t *testing.T) *DB {
	t.Helper()
	db, _ := openTestDB(t)
	if err := Migrate(context.Background(), db, 1234); err != nil {
		t.Fatalf("Migrate: %v", err)
	}
	return db
}

func TestSchemaV1CreatesCanonicalTablesAndIndexes(t *testing.T) {
	db := migrateTestDB(t)
	ctx := context.Background()
	rows, err := db.Query(ctx, `SELECT name,type FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%' ORDER BY name`)
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]string{}
	for _, r := range rows {
		got[r[0].Text] = r[1].Text
	}
	required := []string{"engine_metadata", "schema_migrations", "download_requests", "downloads", "download_attempts", "backend_ownership", "backend_tasks", "artifacts", "publication_transactions", "segments", "checkpoint_blocks", "verification_records", "queues", "queue_membership", "schedule_definitions", "media_items", "media_sources", "media_variants", "media_tracks", "diagnostic_events", "idx_attempts_state", "idx_checkpoint_attempt", "idx_publication_state"}
	for _, name := range required {
		if _, ok := got[name]; !ok {
			t.Errorf("missing schema object %s", name)
		}
	}
	rows, err = db.Query(ctx, "PRAGMA user_version")
	if err != nil {
		t.Fatal(err)
	}
	if gotv := rows[0][0].I64; gotv != CurrentSchemaVersion {
		t.Fatalf("user_version=%d", gotv)
	}
	rows, err = db.Query(ctx, `SELECT value FROM engine_metadata WHERE key='schema_checksum'`)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0][0].Text != SchemaChecksum() {
		t.Fatalf("schema checksum mismatch: %#v", rows)
	}
}

func TestSchemaMutableTablesCarryPositiveRevision(t *testing.T) {
	db := migrateTestDB(t)
	ctx := context.Background()
	mutable := []string{"downloads", "download_attempts", "backend_ownership", "backend_tasks", "artifacts", "publication_transactions", "segments", "checkpoint_blocks", "queues", "queue_membership", "schedule_definitions", "media_items", "media_sources", "media_variants", "media_tracks"}
	for _, table := range mutable {
		rows, err := db.Query(ctx, fmt.Sprintf("PRAGMA table_info(%s)", table))
		if err != nil {
			t.Fatal(err)
		}
		found := false
		for _, r := range rows {
			if r[1].Text == "revision" {
				found = true
				if r[3].I64 != 1 {
					t.Errorf("%s.revision not NOT NULL", table)
				}
				break
			}
		}
		if !found {
			t.Errorf("mutable table %s lacks revision", table)
		}
	}
}

func TestMigrateIsIdempotentAndRejectsNewerSchema(t *testing.T) {
	db := migrateTestDB(t)
	ctx := context.Background()
	if err := Migrate(ctx, db, 9999); err != nil {
		t.Fatalf("idempotent migrate: %v", err)
	}
	rows, err := db.Query(ctx, `SELECT count(*) FROM schema_migrations`)
	if err != nil {
		t.Fatal(err)
	}
	if rows[0][0].I64 != 1 {
		t.Fatalf("migration rows=%d", rows[0][0].I64)
	}
	if _, err := db.Exec(ctx, "PRAGMA user_version=999"); err != nil {
		t.Fatal(err)
	}
	err = Migrate(ctx, db, 10000)
	if !errors.Is(err, ErrSchemaTooNew) {
		t.Fatalf("want ErrSchemaTooNew, got %v", err)
	}
}

func TestForeignKeysRestrictInvalidCanonicalReferences(t *testing.T) {
	db := migrateTestDB(t)
	ctx := context.Background()
	_, err := db.Exec(ctx, `INSERT INTO downloads(download_id,request_id,current_request_revision,state,revision,created_at_unix_ms,updated_at_unix_ms) VALUES('dl_00000000000000000000000000','req_00000000000000000000000000',1,'open',1,1,1)`)
	var sqlErr *Error
	if !errors.As(err, &sqlErr) || !sqlErr.Constraint() {
		t.Fatalf("expected FK constraint, got %T %v", err, err)
	}
}
