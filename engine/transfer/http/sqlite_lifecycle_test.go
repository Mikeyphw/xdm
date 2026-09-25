//go:build cgo

package httptransfer

import (
	"context"
	"path/filepath"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

func TestSQLiteLifecyclePersistsRunningAndTransportComplete(t *testing.T) {
	ctx := context.Background()
	db, err := store.Open(filepath.Join(t.TempDir(), "engine.sqlite"), store.DefaultOptions())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := store.Migrate(ctx, db, 1); err != nil {
		t.Fatal(err)
	}
	repo, _ := store.NewRepository(db)
	req, _ := identity.ParseRequestID("req_00000000000000000000000000000042")
	dl, _ := identity.ParseDownloadID("dl_00000000000000000000000000000042")
	rev, _ := identity.NewRevision(1)
	if err := repo.CreateRequest(ctx, store.RequestRecord{ID: req, Revision: rev, ResourceIdentity: "res:transfer", Method: "GET", SafeSpecJSON: `{"transfer":true}`, CreatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	if err := repo.CreateDownload(ctx, store.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: rev, State: "open", Revision: rev, CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	attempt, _, err := repo.ReserveAttemptGeneration(ctx, dl, rev, "native", 2)
	if err != nil {
		t.Fatal(err)
	}
	lifecycle := &SQLiteLifecycle{Repository: repo, DownloadID: dl, Generation: attempt.Generation, Revision: attempt.Revision, State: "reserved"}
	if err := lifecycle.Start(ctx); err != nil {
		t.Fatal(err)
	}
	rows, err := db.Query(ctx, `SELECT state,revision FROM download_attempts WHERE download_id=? AND attempt_generation=?`, dl.String(), attempt.Generation.Int64())
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0][0].Text != "running" || rows[0][1].I64 != 3 {
		t.Fatalf("rows=%#v", rows)
	}
	if err := lifecycle.Pause(ctx); err != nil {
		t.Fatal(err)
	}
	if err := lifecycle.Start(ctx); err != nil {
		t.Fatal(err)
	}
	if err := lifecycle.Complete(ctx); err != nil {
		t.Fatal(err)
	}
	rows, _ = db.Query(ctx, `SELECT state,revision FROM download_attempts WHERE download_id=? AND attempt_generation=?`, dl.String(), attempt.Generation.Int64())
	if rows[0][0].Text != "transport_complete" || rows[0][1].I64 != 6 {
		t.Fatalf("rows=%#v", rows)
	}
}
