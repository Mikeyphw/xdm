//go:build cgo

package sqlite

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func openTestDB(t *testing.T) (*DB, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "engine.sqlite")
	db, err := Open(path, DefaultOptions())
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return db, path
}

func TestOpenConfiguresWALForeignKeysAndBusyTimeout(t *testing.T) {
	db, _ := openTestDB(t)
	ctx := context.Background()
	cases := []struct {
		q        string
		wantKind Kind
		wantText string
		wantMin  int64
	}{
		{"PRAGMA journal_mode", Text, "wal", 0},
		{"PRAGMA foreign_keys", Integer, "", 1},
		{"PRAGMA busy_timeout", Integer, "", 1},
	}
	for _, tc := range cases {
		rows, err := db.Query(ctx, tc.q)
		if err != nil {
			t.Fatalf("%s: %v", tc.q, err)
		}
		if len(rows) != 1 || len(rows[0]) != 1 || rows[0][0].Kind != tc.wantKind {
			t.Fatalf("%s unexpected rows: %#v", tc.q, rows)
		}
		if tc.wantText != "" && rows[0][0].Text != tc.wantText {
			t.Fatalf("%s = %q want %q", tc.q, rows[0][0].Text, tc.wantText)
		}
		if tc.wantMin > 0 && rows[0][0].I64 < tc.wantMin {
			t.Fatalf("%s = %d", tc.q, rows[0][0].I64)
		}
	}
}

func TestTransactionCommitRollbackAndReopen(t *testing.T) {
	db, path := openTestDB(t)
	ctx := context.Background()
	if err := db.ExecScript(ctx, `CREATE TABLE sample(id INTEGER PRIMARY KEY, value TEXT NOT NULL);`); err != nil {
		t.Fatal(err)
	}
	tx, err := db.BeginImmediate(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := tx.Exec(ctx, `INSERT INTO sample(id,value) VALUES(1,'commit')`); err != nil {
		t.Fatal(err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	tx, err = db.BeginImmediate(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := tx.Exec(ctx, `INSERT INTO sample(id,value) VALUES(2,'rollback')`); err != nil {
		t.Fatal(err)
	}
	if err := tx.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(path, DefaultOptions())
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	rows, err := reopened.Query(ctx, `SELECT id,value FROM sample ORDER BY id`)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0][0].I64 != 1 || rows[0][1].Text != "commit" {
		t.Fatalf("unexpected rows: %#v", rows)
	}
}

func TestConstraintAndCorruptionClassification(t *testing.T) {
	db, _ := openTestDB(t)
	ctx := context.Background()
	if err := db.ExecScript(ctx, `CREATE TABLE parent(id INTEGER PRIMARY KEY); CREATE TABLE child(parent_id INTEGER NOT NULL REFERENCES parent(id));`); err != nil {
		t.Fatal(err)
	}
	_, err := db.Exec(ctx, `INSERT INTO child(parent_id) VALUES(?)`, int64(7))
	var sqlErr *Error
	if !errors.As(err, &sqlErr) || !sqlErr.Constraint() {
		t.Fatalf("want constraint error, got %T %v", err, err)
	}

	corrupt := filepath.Join(t.TempDir(), "corrupt.sqlite")
	if err := os.WriteFile(corrupt, []byte("definitely not sqlite"), 0600); err != nil {
		t.Fatal(err)
	}
	bad, err := Open(corrupt, DefaultOptions())
	if err == nil {
		defer bad.Close()
		err = Migrate(ctx, bad, time.Now().UnixMilli())
	}
	if err == nil {
		t.Fatal("expected corrupt/not-a-database error")
	}
	if errors.As(err, &sqlErr) && !sqlErr.Corrupt() {
		t.Fatalf("want corruption classification, got %+v", sqlErr)
	}
}

func TestConcurrentConnectionsRespectBusyTimeout(t *testing.T) {
	_, path := openTestDB(t)
	ctx := context.Background()
	a, err := Open(path, Options{BusyTimeoutMS: 50})
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	b, err := Open(path, Options{BusyTimeoutMS: 50})
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if err := a.ExecScript(ctx, `CREATE TABLE IF NOT EXISTS locks(id INTEGER PRIMARY KEY);`); err != nil {
		t.Fatal(err)
	}
	tx, err := a.BeginImmediate(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(context.Background())
	started := time.Now()
	_, err = b.Exec(ctx, `INSERT INTO locks(id) VALUES(1)`)
	if err == nil {
		t.Fatal("expected busy/locked error")
	}
	var sqlErr *Error
	if !errors.As(err, &sqlErr) || (!sqlErr.Busy() && !sqlErr.Locked()) {
		t.Fatalf("want busy/locked, got %T %v", err, err)
	}
	if time.Since(started) < 30*time.Millisecond {
		t.Fatalf("busy timeout returned too quickly: %v", time.Since(started))
	}
}
