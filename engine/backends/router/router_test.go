package router

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"strings"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/security/transportpolicy"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

func testResource(t *testing.T, name string) resource.Identity {
	t.Helper()
	r, err := resource.DeriveIdentity("router-test", name)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func testSecret(t *testing.T, name string) domainrequest.SecretReference {
	t.Helper()
	r, err := domainrequest.NewSecretReference(name)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func testBody(t *testing.T, name string) domainrequest.BodyReference {
	t.Helper()
	r, err := domainrequest.NewBodyReference(name)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func getIntent(t *testing.T, raw string) domainrequest.NetworkIntent {
	t.Helper()
	in, err := domainrequest.NewNetworkIntent(domainrequest.NetworkIntent{TransportURL: raw, Resource: testResource(t, raw), Method: "GET", AllowBackendFallback: true})
	if err != nil {
		t.Fatal(err)
	}
	return in
}

func postIntent(t *testing.T) domainrequest.NetworkIntent {
	t.Helper()
	length := int64(4)
	in, err := domainrequest.NewNetworkIntent(domainrequest.NetworkIntent{
		TransportURL: "https://example.test/export", Resource: testResource(t, "post"), Method: "POST", AllowBackendFallback: true,
		Body: &domainrequest.Body{Kind: domainrequest.BodySourceImmutableBytes, Ref: testBody(t, "body/router/post"), Replayability: domainrequest.BodyReplayable, Length: &length},
	})
	if err != nil {
		t.Fatal(err)
	}
	return in
}

func op(req domainrequest.NetworkIntent) Operation {
	return Operation{Request: req, Requirements: Requirements{Destination: DestinationStaging, Proxy: transportpolicy.ProxyDirect, Media: MediaDirectFile, Resume: ResumeOptional}}
}

func TestExactCompatibilityMatchesPreflight(t *testing.T) {
	ftp := getIntent(t, "ftp://example.test/file.bin")
	ftps := getIntent(t, "ftps://example.test/file.bin")
	mirror := getIntent(t, "https://example.test/file.bin")
	mirror.Mirrors = []string{"https://mirror.test/file.bin"}
	mirror, _ = domainrequest.NewNetworkIntent(mirror)
	cases := []Operation{
		op(getIntent(t, "https://example.test/file.bin")),
		op(postIntent(t)),
		op(ftp),
		op(ftps),
		op(mirror),
		{Request: getIntent(t, "https://example.test/media.mp4"), Requirements: Requirements{Destination: DestinationPlatformStream, Proxy: transportpolicy.ProxyDirect, Media: MediaDirectMedia, Resume: ResumeOptional}},
		{Request: getIntent(t, "https://example.test/master.m3u8"), Requirements: Requirements{Destination: DestinationStaging, Proxy: transportpolicy.ProxyDirect, Media: MediaAdaptivePlaylist, Resume: ResumeNone}},
	}
	for _, backend := range []Backend{NativeBackend(), Aria2Backend()} {
		for _, tc := range cases {
			got := backend.CanExecute(tc)
			err := backend.Preflight(tc)
			if got.Compatible && err != nil {
				t.Fatalf("%s accepted but preflight failed: %v", backend.Kind(), err)
			}
			if !got.Compatible {
				var pe *PreflightError
				if !errors.As(err, &pe) {
					t.Fatalf("%s rejected without typed preflight: %v", backend.Kind(), err)
				}
				if pe.Issue.Reason != got.FirstReason() {
					t.Fatalf("%s reason mismatch preflight=%s compatibility=%s", backend.Kind(), pe.Issue.Reason, got.FirstReason())
				}
			}
		}
	}
}

func TestCompatibilityReasonsCoverKnownDimensions(t *testing.T) {
	post := op(postIntent(t))
	if got := Aria2Backend().CanExecute(post); got.Compatible || got.FirstReason() != ReasonMethodBody {
		t.Fatalf("aria2 post=%+v", got)
	}

	ftps := op(getIntent(t, "ftps://example.test/x"))
	if got := Aria2Backend().CanExecute(ftps); got.Compatible || got.FirstReason() != ReasonProtocol {
		t.Fatalf("aria2 ftps=%+v", got)
	}

	platform := op(getIntent(t, "https://example.test/x"))
	platform.Requirements.Destination = DestinationPlatformStream
	if got := Aria2Backend().CanExecute(platform); got.Compatible || got.FirstReason() != ReasonDestination {
		t.Fatalf("aria2 destination=%+v", got)
	}

	cred := op(getIntent(t, "https://example.test/x"))
	cred.Request.Credentials = []domainrequest.CredentialReference{{Kind: domainrequest.CredentialAuthorization, Ref: testSecret(t, "secret/router/auth"), Scope: domainrequest.CredentialScope{Origin: "https://example.test", Resource: cred.Request.Resource}}}
	cred.Request, _ = domainrequest.NewNetworkIntent(cred.Request)
	if got := Aria2Backend().CanExecute(cred); got.Compatible || got.FirstReason() != ReasonCredentialMode {
		t.Fatalf("aria2 credentials=%+v", got)
	}

	proxy := op(getIntent(t, "https://example.test/x"))
	proxy.Requirements.Proxy = transportpolicy.ProxyHTTP
	if got := Aria2Backend().CanExecute(proxy); got.Compatible || got.FirstReason() != ReasonProxy {
		t.Fatalf("aria2 proxy=%+v", got)
	}

	media := op(getIntent(t, "https://example.test/x"))
	media.Requirements.Media = MediaExternalTool
	if got := NativeBackend().CanExecute(media); got.Compatible || got.FirstReason() != ReasonMediaShape {
		t.Fatalf("native media=%+v", got)
	}

	resume := op(postIntent(t))
	resume.Requirements.Resume = ResumeRequired
	if got := NativeBackend().CanExecute(resume); got.Compatible || got.FirstReason() != ReasonResume {
		t.Fatalf("native post resume=%+v", got)
	}

	mirrors := op(getIntent(t, "https://example.test/x"))
	mirrors.Request.Mirrors = []string{"https://mirror.test/x"}
	mirrors.Request, _ = domainrequest.NewNetworkIntent(mirrors.Request)
	if got := NativeBackend().CanExecute(mirrors); got.Compatible || got.FirstReason() != ReasonMirrorSemantics {
		t.Fatalf("native mirrors=%+v", got)
	}
}

func healthyStates() map[Kind]BackendState {
	return map[Kind]BackendState{
		Native: {RuntimeAvailable: true, Health: HealthHealthy, MigrationCost: MigrationNone},
		Aria2:  {RuntimeAvailable: true, Health: HealthHealthy, MigrationCost: MigrationNone},
	}
}

func TestSelectionDeterministicAndPreferenceBoundedByCompatibility(t *testing.T) {
	httpOp := op(getIntent(t, "https://example.test/file.bin"))
	first, err := Select(SelectionInput{Operation: httpOp, States: healthyStates()})
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 20; i++ {
		next, err := Select(SelectionInput{Operation: httpOp, States: healthyStates()})
		if err != nil {
			t.Fatal(err)
		}
		a, _ := json.Marshal(first)
		b, _ := json.Marshal(next)
		if string(a) != string(b) {
			t.Fatalf("nondeterministic\n%s\n%s", a, b)
		}
	}
	if first.Selected != Native {
		t.Fatalf("default=%+v", first)
	}
	if len(first.Candidates) != 2 || first.Candidates[0].Backend != first.Selected {
		t.Fatalf("candidates are not ranked with selected first: %+v", first.Candidates)
	}

	preferred := httpOp
	preferred.Request.BackendPreference = "aria2"
	preferred.Request.AllowBackendFallback = true
	d, err := Select(SelectionInput{Operation: preferred, States: healthyStates()})
	if err != nil || d.Selected != Aria2 || d.Reason != SelectUserPreference {
		t.Fatalf("preferred=%+v err=%v", d, err)
	}

	post := op(postIntent(t))
	post.Request.BackendPreference = "aria2"
	post.Request.AllowBackendFallback = true
	d, err = Select(SelectionInput{Operation: post, States: healthyStates()})
	if err != nil || d.Selected != Native || !d.Fallback || d.Reason != SelectFallback {
		t.Fatalf("post fallback=%+v err=%v", d, err)
	}
}

func TestSelectionHealthMirrorsMediaAndStartedFence(t *testing.T) {
	states := healthyStates()
	states[Native] = BackendState{RuntimeAvailable: true, Health: HealthDegraded, MigrationCost: MigrationNone}
	d, _ := Select(SelectionInput{Operation: op(getIntent(t, "https://example.test/file.bin")), States: states})
	if d.Selected != Aria2 {
		t.Fatalf("degraded native decision=%+v", d)
	}

	mirrors := op(getIntent(t, "https://example.test/file.bin"))
	mirrors.Request.Mirrors = []string{"https://mirror.test/file.bin"}
	mirrors.Request, _ = domainrequest.NewNetworkIntent(mirrors.Request)
	d, _ = Select(SelectionInput{Operation: mirrors, States: healthyStates()})
	if d.Selected != Aria2 || d.Reason != SelectMirrors {
		t.Fatalf("mirrors=%+v", d)
	}

	media := op(getIntent(t, "https://example.test/video.mp4"))
	media.Requirements.Media = MediaDirectMedia
	d, _ = Select(SelectionInput{Operation: media, States: healthyStates()})
	if d.Selected != Native || d.Reason != SelectDirectMedia {
		t.Fatalf("direct media=%+v", d)
	}

	external := op(getIntent(t, "https://example.test/master.m3u8"))
	external.Requirements.Media = MediaExternalTool
	d, _ = Select(SelectionInput{Operation: external, States: healthyStates()})
	if d.CanStart {
		t.Fatalf("external media unexpectedly selected=%+v", d)
	}

	d, _ = Select(SelectionInput{Operation: op(getIntent(t, "ftp://example.test/file.bin")), States: healthyStates(), CurrentBackend: Native, AttemptStarted: true})
	if !d.CanStart || d.Selected != Native || d.Reason != SelectExistingAttempt {
		t.Fatalf("started fence=%+v", d)
	}
	for _, c := range d.Candidates {
		if c.Backend == Aria2 {
			found := false
			for _, r := range c.Rejections {
				if r.Reason == RejectMigration {
					found = true
				}
			}
			if !found {
				t.Fatalf("aria2 missing migration fence=%+v", c)
			}
		}
	}
}

func TestPersistSelectionIsAttemptScopedAndPreStartOnly(t *testing.T) {
	if !store.Available {
		t.Skip("sqlite unavailable")
	}
	ctx := context.Background()
	db, err := store.Open(filepath.Join(t.TempDir(), "router.sqlite"), store.DefaultOptions())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err = store.Migrate(ctx, db, 1); err != nil {
		t.Fatal(err)
	}
	repo, err := store.NewRepository(db)
	if err != nil {
		t.Fatal(err)
	}
	reqID, _ := identity.ParseRequestID("req_00000000000000000000000000000039")
	dlID, _ := identity.ParseDownloadID("dl_00000000000000000000000000000039")
	rev, _ := identity.NewRevision(1)
	if err = repo.CreateRequest(ctx, store.RequestRecord{ID: reqID, Revision: rev, ResourceIdentity: "res-test", Method: "GET", SafeSpecJSON: `{}`, CreatedAtUnixMS: 2}); err != nil {
		t.Fatal(err)
	}
	if err = repo.CreateDownload(ctx, store.DownloadRecord{ID: dlID, RequestID: reqID, CurrentRequestRevision: rev, State: "created", Revision: rev, CreatedAtUnixMS: 3, UpdatedAtUnixMS: 3}); err != nil {
		t.Fatal(err)
	}
	attempt, _, err := repo.ReserveAttemptGeneration(ctx, dlID, rev, string(Native), 4)
	if err != nil {
		t.Fatal(err)
	}
	decision, err := Select(SelectionInput{Operation: op(getIntent(t, "https://example.test/file.bin")), States: healthyStates()})
	if err != nil {
		t.Fatal(err)
	}
	if err = PersistSelection(ctx, repo, attempt, decision, 5); err != nil {
		t.Fatal(err)
	}
	if err = PersistSelection(ctx, repo, attempt, decision, 5); err != nil {
		t.Fatalf("idempotent persist: %v", err)
	}
	events, err := repo.ListAttemptDiagnostics(ctx, dlID, attempt.Generation, "backend")
	if err != nil {
		t.Fatal(err)
	}
	if len(events) != 1 || events[0].EventType != "backend_selection" || !strings.Contains(events[0].SafePayload, `"selected":"native"`) {
		t.Fatalf("events=%+v", events)
	}
	attempt, err = repo.MutateAttempt(ctx, dlID, attempt.Generation, attempt.Revision, "prepared", "", "", 6)
	if err != nil {
		t.Fatal(err)
	}
	if err = PersistSelection(ctx, repo, attempt, decision, 7); !errors.Is(err, ErrAttemptStarted) {
		t.Fatalf("post-start persist err=%v", err)
	}
}
