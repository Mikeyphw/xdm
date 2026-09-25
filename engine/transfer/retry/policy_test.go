//go:build cgo

package retry

import (
	"context"
	"net/http"
	"path/filepath"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

type fixedClock struct{ t time.Time }

func (f fixedClock) Now() time.Time { return f.t }

type fixedJitter struct{ add time.Duration }

func (f fixedJitter) Apply(d time.Duration, _ int) time.Duration { return d + f.add }

func mustFailure(t *testing.T, c failure.Category) failure.Failure {
	t.Helper()
	f, err := failure.NewDefault(c, nil)
	if err != nil {
		t.Fatal(err)
	}
	return f
}

func defaultQueue() QueuePolicy {
	return QueuePolicy{Enabled: true, MaxAttempts: 5, BaseDelay: time.Second, MaxDelay: 30 * time.Second}
}

func TestRetryAfterSecondsAndHTTPDate(t *testing.T) {
	now := time.Unix(1000, 0).UTC()
	e := Engine{Clock: fixedClock{now}}
	d, err := e.Decide(Input{Failure: mustFailure(t, failure.RateLimited), AttemptCount: 3, RetryAfter: "120", Queue: defaultQueue(), NetworkAvailable: true, Replayable: true})
	if err != nil {
		t.Fatal(err)
	}
	if d.Kind != RetryAt || d.RetryAtUnixMS != now.Add(120*time.Second).UnixMilli() {
		t.Fatalf("decision=%+v", d)
	}
	header := now.Add(45 * time.Second).Format(http.TimeFormat)
	d, err = e.Decide(Input{Failure: mustFailure(t, failure.RateLimited), AttemptCount: 2, RetryAfter: header, Queue: defaultQueue(), NetworkAvailable: true, Replayable: true})
	if err != nil {
		t.Fatal(err)
	}
	if d.RetryAtUnixMS != now.Add(45*time.Second).UnixMilli() {
		t.Fatalf("date decision=%+v", d)
	}
}

func TestBackoffDeterministicAndAttemptBounded(t *testing.T) {
	now := time.Unix(2000, 0)
	e := Engine{Clock: fixedClock{now}, Jitter: fixedJitter{250 * time.Millisecond}}
	d, err := e.Decide(Input{Failure: mustFailure(t, failure.NetworkUnavailable), AttemptCount: 3, Queue: defaultQueue(), NetworkAvailable: true, Replayable: true})
	if err != nil {
		t.Fatal(err)
	}
	want := now.Add(4250 * time.Millisecond).UnixMilli()
	if d.Kind != RetryAt || d.RetryAtUnixMS != want {
		t.Fatalf("decision=%+v want=%d", d, want)
	}
	d, err = e.Decide(Input{Failure: mustFailure(t, failure.NetworkUnavailable), AttemptCount: 5, Queue: defaultQueue(), NetworkAvailable: true, Replayable: true})
	if err != nil {
		t.Fatal(err)
	}
	if d.Kind != Terminal || d.Reason != "attempt_limit" {
		t.Fatalf("decision=%+v", d)
	}
}

func TestTerminalHoldAndReplayability(t *testing.T) {
	e := Engine{Clock: fixedClock{time.Unix(1, 0)}}
	cases := []struct {
		name string
		in   Input
		kind DecisionKind
	}{
		{"tls", Input{Failure: mustFailure(t, failure.TLSFailure), AttemptCount: 1, Queue: defaultQueue(), NetworkAvailable: true, Replayable: true}, Terminal},
		{"auth", Input{Failure: mustFailure(t, failure.AuthenticationRequired), AttemptCount: 1, Queue: defaultQueue(), NetworkAvailable: true, Replayable: true}, Hold},
		{"offline", Input{Failure: mustFailure(t, failure.DNSFailure), AttemptCount: 1, Queue: defaultQueue(), NetworkAvailable: false, Replayable: true}, Hold},
		{"post_one_shot", Input{Failure: mustFailure(t, failure.NetworkUnavailable), AttemptCount: 1, Queue: defaultQueue(), NetworkAvailable: true, Replayable: false}, Terminal},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			d, err := e.Decide(tc.in)
			if err != nil {
				t.Fatal(err)
			}
			if d.Kind != tc.kind {
				t.Fatalf("decision=%+v", d)
			}
		})
	}
}

func TestHTTPStatusClassification(t *testing.T) {
	f, err := FailureForHTTPStatus(429)
	if err != nil {
		t.Fatal(err)
	}
	if f.Category != failure.RateLimited {
		t.Fatalf("category=%s", f.Category)
	}
	f, err = FailureForHTTPStatus(503)
	if err != nil {
		t.Fatal(err)
	}
	if f.Category != failure.NetworkUnavailable {
		t.Fatalf("category=%s", f.Category)
	}
	f, err = FailureForHTTPStatus(401)
	if err != nil {
		t.Fatal(err)
	}
	if f.Category != failure.AuthenticationRequired {
		t.Fatalf("category=%s", f.Category)
	}
}

func setupRetryRepo(t *testing.T) (*store.Repository, identity.DownloadID, identity.AttemptGeneration, identity.Revision) {
	t.Helper()
	ctx := context.Background()
	db, err := store.Open(filepath.Join(t.TempDir(), "engine.sqlite"), store.DefaultOptions())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	if err = store.Migrate(ctx, db, 1); err != nil {
		t.Fatal(err)
	}
	repo, _ := store.NewRepository(db)
	req, _ := identity.ParseRequestID("req_00000000000000000000000000000092")
	dl, _ := identity.ParseDownloadID("dl_00000000000000000000000000000092")
	rev, _ := identity.NewRevision(1)
	if err = repo.CreateRequest(ctx, store.RequestRecord{ID: req, Revision: rev, ResourceIdentity: "retry", Method: "GET", SafeSpecJSON: `{"retry":true}`, CreatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	if err = repo.CreateDownload(ctx, store.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: rev, State: "open", Revision: rev, CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); err != nil {
		t.Fatal(err)
	}
	attempt, _, err := repo.ReserveAttemptGeneration(ctx, dl, rev, "native", 2)
	if err != nil {
		t.Fatal(err)
	}
	return repo, dl, attempt.Generation, attempt.Revision
}

func TestPersistedRetryDeadlineSurvivesReload(t *testing.T) {
	repo, dl, gen, rev := setupRetryRepo(t)
	now := time.Unix(3000, 0)
	decision := Decision{Kind: RetryAt, RetryAtUnixMS: now.Add(2 * time.Minute).UnixMilli(), Reason: "retry_after"}
	f := mustFailure(t, failure.RateLimited)
	persisted, updated, err := Persist(context.Background(), repo, dl, gen, rev, f, 3, decision, now)
	if err != nil {
		t.Fatal(err)
	}
	if !updated.Revision.Valid() {
		t.Fatal("missing revision")
	}
	loaded, attempt, err := Load(context.Background(), repo, dl, gen)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.Decision.RetryAtUnixMS != persisted.Decision.RetryAtUnixMS || loaded.AttemptCount != 3 || attempt.FailureCategory != string(failure.RateLimited) {
		t.Fatalf("loaded=%+v attempt=%+v", loaded, attempt)
	}
}

func TestPersistRetryRejectsStaleAttempt(t *testing.T) {
	repo, dl, gen, rev := setupRetryRepo(t)
	download, err := repo.GetDownload(context.Background(), dl)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = repo.ReserveAttemptGeneration(context.Background(), dl, download.Revision, "native", 20); err != nil {
		t.Fatal(err)
	}
	f := mustFailure(t, failure.NetworkUnavailable)
	_, _, err = Persist(context.Background(), repo, dl, gen, rev, f, 1, Decision{Kind: RetryAt, RetryAtUnixMS: 9999, Reason: "test"}, time.Unix(4, 0))
	if err == nil {
		t.Fatal("stale attempt retry metadata accepted")
	}
	if err != store.ErrStaleAttempt {
		t.Fatalf("want stale attempt, got %v", err)
	}
}
