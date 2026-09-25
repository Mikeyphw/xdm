//go:build cgo

package sqlite

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
)

func mustDownloadID(t *testing.T, s string) identity.DownloadID {
	t.Helper()
	v, e := identity.ParseDownloadID(s)
	if e != nil {
		t.Fatal(e)
	}
	return v
}
func mustRequestID(t *testing.T, s string) identity.RequestID {
	t.Helper()
	v, e := identity.ParseRequestID(s)
	if e != nil {
		t.Fatal(e)
	}
	return v
}
func mustRevision(t *testing.T, v int64) identity.Revision {
	t.Helper()
	r, e := identity.NewRevision(v)
	if e != nil {
		t.Fatal(e)
	}
	return r
}

func seedRepository(t *testing.T, db *DB) (*Repository, identity.DownloadID) {
	t.Helper()
	ctx := context.Background()
	if err := Migrate(ctx, db, 1); err != nil {
		t.Fatal(err)
	}
	repo, _ := NewRepository(db)
	req := mustRequestID(t, "req_00000000000000000000000000000000")
	if err := repo.CreateRequest(ctx, RequestRecord{ID: req, Revision: mustRevision(t, 1), ResourceIdentity: "res:fixture", Method: "GET", SafeSpecJSON: `{"resource":"fixture"}`, CreatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	dl := mustDownloadID(t, "dl_00000000000000000000000000000000")
	if err := repo.CreateDownload(ctx, DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: mustRevision(t, 1), State: "open", Revision: mustRevision(t, 1), CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	return repo, dl
}

func TestDownloadCASRejectsStaleWriter(t *testing.T) {
	db, _ := openTestDB(t)
	repo, id := seedRepository(t, db)
	ctx := context.Background()
	before, err := repo.GetDownload(ctx, id)
	if err != nil {
		t.Fatal(err)
	}
	first, err := repo.UpdateDownload(ctx, id, before.Revision, DownloadPatch{State: "completed", UpdatedAtUnixMS: 2})
	if err != nil {
		t.Fatal(err)
	}
	if first.Revision.Int64() != 2 {
		t.Fatalf("revision=%d", first.Revision.Int64())
	}
	_, err = repo.UpdateDownload(ctx, id, before.Revision, DownloadPatch{State: "deleted", UpdatedAtUnixMS: 3})
	if !errors.Is(err, ErrStaleWrite) {
		t.Fatalf("want stale write, got %v", err)
	}
	final, _ := repo.GetDownload(ctx, id)
	if final.State != "completed" || final.Revision.Int64() != 2 {
		t.Fatalf("stale writer mutated state: %+v", final)
	}
}

func TestConcurrentSameRevisionExactlyOneWriterWins(t *testing.T) {
	db, path := openTestDB(t)
	_, id := seedRepository(t, db)
	ctx := context.Background()
	const writers = 24
	var wins atomic.Int64
	var stale atomic.Int64
	var unexpected atomic.Int64
	var wg sync.WaitGroup
	for i := 0; i < writers; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			conn, err := Open(path, DefaultOptions())
			if err != nil {
				unexpected.Add(1)
				return
			}
			defer conn.Close()
			repo, _ := NewRepository(conn)
			_, err = repo.UpdateDownload(ctx, id, mustRevision(t, 1), DownloadPatch{State: "winner", UpdatedAtUnixMS: int64(10 + i)})
			if err == nil {
				wins.Add(1)
			} else if errors.Is(err, ErrStaleWrite) {
				stale.Add(1)
			} else {
				unexpected.Add(1)
			}
		}(i)
	}
	wg.Wait()
	if wins.Load() != 1 || stale.Load() != writers-1 || unexpected.Load() != 0 {
		t.Fatalf("wins=%d stale=%d unexpected=%d", wins.Load(), stale.Load(), unexpected.Load())
	}
}

func TestCASNeverAutoRetriesStaleRevision(t *testing.T) {
	db, _ := openTestDB(t)
	repo, id := seedRepository(t, db)
	ctx := context.Background()
	rev := mustRevision(t, 1)
	if _, err := repo.UpdateDownload(ctx, id, rev, DownloadPatch{State: "first", UpdatedAtUnixMS: 2}); err != nil {
		t.Fatal(err)
	}
	if _, err := repo.UpdateDownload(ctx, id, rev, DownloadPatch{State: "second", UpdatedAtUnixMS: 3}); !errors.Is(err, ErrStaleWrite) {
		t.Fatalf("want stale write, got %v", err)
	}
	got, _ := repo.GetDownload(ctx, id)
	if got.State != "first" {
		t.Fatalf("implicit retry overwrote state: %+v", got)
	}
}
