package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/subhra74/xdm/engine/backends/router"
	"github.com/subhra74/xdm/engine/domain/identity"
	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/security/transportpolicy"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

func routingGet(raw string) domainrequest.NetworkIntent {
	in, err := domainrequest.NewNetworkIntent(domainrequest.NetworkIntent{TransportURL: raw, Resource: mustResource("route-" + raw), Method: "GET", AllowBackendFallback: true})
	if err != nil {
		panic(err)
	}
	return in
}

func routingPost() domainrequest.NetworkIntent {
	payload := []byte("route-post")
	return mustPost("https://example.test/export", domainrequest.BodySourceImmutableBytes, mustBodyRef("body/route/post"), payload)
}

func routingOp(req domainrequest.NetworkIntent) router.Operation {
	return router.Operation{Request: req, Requirements: router.Requirements{Destination: router.DestinationStaging, Proxy: transportpolicy.ProxyDirect, Media: router.MediaDirectFile, Resume: router.ResumeOptional}}
}

func hasCompatReason(r router.CompatibilityResult, reason router.CompatibilityReason) bool {
	for _, issue := range r.Rejects {
		if issue.Reason == reason {
			return true
		}
	}
	return false
}

func compatibilityRequestKindsCase() error {
	cases := []struct {
		name   string
		op     router.Operation
		native bool
		aria2  bool
	}{
		{"https_get", routingOp(routingGet("https://example.test/file.bin")), true, true},
		{"https_post", routingOp(routingPost()), true, false},
		{"ftp_get", routingOp(routingGet("ftp://example.test/file.bin")), true, true},
		{"ftps_get", routingOp(routingGet("ftps://example.test/file.bin")), true, false},
	}
	for _, tc := range cases {
		if got := router.NativeBackend().CanExecute(tc.op).Compatible; got != tc.native {
			return fmt.Errorf("%s native=%t want=%t", tc.name, got, tc.native)
		}
		if got := router.Aria2Backend().CanExecute(tc.op).Compatible; got != tc.aria2 {
			return fmt.Errorf("%s aria2=%t want=%t", tc.name, got, tc.aria2)
		}
	}
	return nil
}

func compatibilityMethodBodyCase() error {
	r := router.Aria2Backend().CanExecute(routingOp(routingPost()))
	if r.Compatible || !hasCompatReason(r, router.ReasonMethodBody) {
		return fmt.Errorf("aria2 post=%+v", r)
	}
	return nil
}

func compatibilityDestinationCase() error {
	op := routingOp(routingGet("https://example.test/file.bin"))
	op.Requirements.Destination = router.DestinationPlatformStream
	if r := router.NativeBackend().CanExecute(op); !r.Compatible {
		return fmt.Errorf("native platform=%+v", r)
	}
	if r := router.Aria2Backend().CanExecute(op); r.Compatible || !hasCompatReason(r, router.ReasonDestination) {
		return fmt.Errorf("aria2 platform=%+v", r)
	}
	return nil
}

func compatibilityCredentialCase() error {
	op := routingOp(routingGet("https://example.test/file.bin"))
	ref := mustSecretRef("secret/route/auth")
	op.Request.Credentials = []domainrequest.CredentialReference{{Kind: domainrequest.CredentialAuthorization, Ref: ref, Scope: domainrequest.CredentialScope{Origin: "https://example.test", Resource: op.Request.Resource}}}
	var err error
	op.Request, err = domainrequest.NewNetworkIntent(op.Request)
	if err != nil {
		return err
	}
	if r := router.NativeBackend().CanExecute(op); !r.Compatible {
		return fmt.Errorf("native credential=%+v", r)
	}
	if r := router.Aria2Backend().CanExecute(op); r.Compatible || !hasCompatReason(r, router.ReasonCredentialMode) {
		return fmt.Errorf("aria2 credential=%+v", r)
	}
	return nil
}

func compatibilityProxyCase() error {
	op := routingOp(routingGet("https://example.test/file.bin"))
	op.Requirements.Proxy = transportpolicy.ProxyHTTP
	if r := router.NativeBackend().CanExecute(op); !r.Compatible {
		return fmt.Errorf("native proxy=%+v", r)
	}
	if r := router.Aria2Backend().CanExecute(op); r.Compatible || !hasCompatReason(r, router.ReasonProxy) {
		return fmt.Errorf("aria2 proxy=%+v", r)
	}
	return nil
}

func compatibilityMediaCase() error {
	op := routingOp(routingGet("https://example.test/master.m3u8"))
	op.Requirements.Media = router.MediaExternalTool
	for _, b := range []router.Backend{router.NativeBackend(), router.Aria2Backend()} {
		r := b.CanExecute(op)
		if r.Compatible || !hasCompatReason(r, router.ReasonMediaShape) {
			return fmt.Errorf("%s external media=%+v", b.Kind(), r)
		}
	}
	return nil
}

func compatibilityResumeCase() error {
	op := routingOp(routingPost())
	op.Requirements.Resume = router.ResumeRequired
	r := router.NativeBackend().CanExecute(op)
	if r.Compatible || !hasCompatReason(r, router.ReasonResume) {
		return fmt.Errorf("post resume=%+v", r)
	}
	return nil
}

func compatibilityMirrorsCase() error {
	op := routingOp(routingGet("https://example.test/file.bin"))
	op.Request.Mirrors = []string{"https://mirror.test/file.bin"}
	var err error
	op.Request, err = domainrequest.NewNetworkIntent(op.Request)
	if err != nil {
		return err
	}
	if r := router.NativeBackend().CanExecute(op); r.Compatible || !hasCompatReason(r, router.ReasonMirrorSemantics) {
		return fmt.Errorf("native mirrors=%+v", r)
	}
	if r := router.Aria2Backend().CanExecute(op); !r.Compatible {
		return fmt.Errorf("aria2 mirrors=%+v", r)
	}
	return nil
}

func compatibilityPreflightExactCase() error {
	ops := []router.Operation{routingOp(routingGet("https://example.test/x")), routingOp(routingPost()), routingOp(routingGet("ftp://example.test/x")), routingOp(routingGet("ftps://example.test/x"))}
	for _, b := range []router.Backend{router.NativeBackend(), router.Aria2Backend()} {
		for _, op := range ops {
			r := b.CanExecute(op)
			err := b.Preflight(op)
			if r.Compatible && err != nil {
				return fmt.Errorf("%s accepted then preflight failed: %v", b.Kind(), err)
			}
			if !r.Compatible {
				var pe *router.PreflightError
				if !errors.As(err, &pe) || pe.Issue.Reason != r.FirstReason() {
					return fmt.Errorf("%s compatibility/preflight mismatch result=%+v err=%v", b.Kind(), r, err)
				}
			}
		}
	}
	return nil
}

func runCompatibilityMatrix() report {
	cases := []caseResult{
		runCase("canonical_request_kinds", compatibilityRequestKindsCase),
		runCase("method_body", compatibilityMethodBodyCase),
		runCase("destination", compatibilityDestinationCase),
		runCase("credential_mode", compatibilityCredentialCase),
		runCase("proxy", compatibilityProxyCase),
		runCase("media_shape", compatibilityMediaCase),
		runCase("resume", compatibilityResumeCase),
		runCase("mirror_semantics", compatibilityMirrorsCase),
		runCase("preflight_exactness", compatibilityPreflightExactCase),
	}
	return finalizeReport("compatibility", cases)
}

func healthyRouterStates() map[router.Kind]router.BackendState {
	return map[router.Kind]router.BackendState{
		router.Native: {RuntimeAvailable: true, Health: router.HealthHealthy, MigrationCost: router.MigrationNone},
		router.Aria2:  {RuntimeAvailable: true, Health: router.HealthHealthy, MigrationCost: router.MigrationNone},
	}
}

func selectionDeterministicCase() error {
	input := router.SelectionInput{Operation: routingOp(routingGet("https://example.test/file.bin")), States: healthyRouterStates()}
	first, err := router.Select(input)
	if err != nil {
		return err
	}
	if len(first.Candidates) != 2 || first.Candidates[0].Backend != first.Selected || first.Explanation == "" {
		return fmt.Errorf("unranked or unexplained decision=%+v", first)
	}
	for _, candidate := range first.Candidates {
		for _, rejection := range candidate.Rejections {
			if rejection.Reason == "" || rejection.Detail == "" {
				return fmt.Errorf("unexplained rejection=%+v", rejection)
			}
		}
	}
	raw, _ := json.Marshal(first)
	for i := 0; i < 50; i++ {
		next, e := router.Select(input)
		if e != nil {
			return e
		}
		got, _ := json.Marshal(next)
		if string(raw) != string(got) {
			return errors.New("same selection input produced different output")
		}
	}
	return nil
}

func selectionPreferenceCase() error {
	op := routingOp(routingGet("https://example.test/file.bin"))
	op.Request.BackendPreference = "aria2"
	op.Request.AllowBackendFallback = true
	d, err := router.Select(router.SelectionInput{Operation: op, States: healthyRouterStates()})
	if err != nil {
		return err
	}
	if !d.CanStart || d.Selected != router.Aria2 || d.Reason != router.SelectUserPreference {
		return fmt.Errorf("decision=%+v", d)
	}
	return nil
}

func selectionUnavailableCase() error {
	op := routingOp(routingGet("https://example.test/file.bin"))
	op.Request.BackendPreference = "aria2"
	op.Request.AllowBackendFallback = true
	states := healthyRouterStates()
	states[router.Aria2] = router.BackendState{RuntimeAvailable: false, Health: router.HealthUnavailable, MigrationCost: router.MigrationNone}
	d, err := router.Select(router.SelectionInput{Operation: op, States: states})
	if err != nil {
		return err
	}
	if !d.CanStart || d.Selected != router.Native || !d.Fallback {
		return fmt.Errorf("decision=%+v", d)
	}
	return nil
}

func selectionDegradedCase() error {
	states := healthyRouterStates()
	states[router.Native] = router.BackendState{RuntimeAvailable: true, Health: router.HealthDegraded, MigrationCost: router.MigrationNone}
	d, err := router.Select(router.SelectionInput{Operation: routingOp(routingGet("https://example.test/file.bin")), States: states})
	if err != nil {
		return err
	}
	if d.Selected != router.Aria2 {
		return fmt.Errorf("decision=%+v", d)
	}
	return nil
}

func selectionDirectHTTPCase() error {
	d, err := router.Select(router.SelectionInput{Operation: routingOp(routingGet("https://example.test/file.bin")), States: healthyRouterStates()})
	if err != nil {
		return err
	}
	if d.Selected != router.Native || d.Reason != router.SelectHealthyDefault {
		return fmt.Errorf("decision=%+v", d)
	}
	return nil
}

func selectionMirrorFTPCase() error {
	mirror := routingOp(routingGet("https://example.test/file.bin"))
	mirror.Request.Mirrors = []string{"https://mirror.test/file.bin"}
	mirror.Request, _ = domainrequest.NewNetworkIntent(mirror.Request)
	d, err := router.Select(router.SelectionInput{Operation: mirror, States: healthyRouterStates()})
	if err != nil {
		return err
	}
	if d.Selected != router.Aria2 || d.Reason != router.SelectMirrors {
		return fmt.Errorf("mirror=%+v", d)
	}
	ftp, err := router.Select(router.SelectionInput{Operation: routingOp(routingGet("ftp://example.test/file.bin")), States: healthyRouterStates()})
	if err != nil {
		return err
	}
	if ftp.Selected != router.Aria2 || ftp.Reason != router.SelectFTP {
		return fmt.Errorf("ftp=%+v", ftp)
	}
	return nil
}

func selectionMediaCase() error {
	direct := routingOp(routingGet("https://example.test/video.mp4"))
	direct.Requirements.Media = router.MediaDirectMedia
	d, err := router.Select(router.SelectionInput{Operation: direct, States: healthyRouterStates()})
	if err != nil {
		return err
	}
	if d.Selected != router.Native || d.Reason != router.SelectDirectMedia {
		return fmt.Errorf("direct=%+v", d)
	}
	ext := routingOp(routingGet("https://example.test/master.m3u8"))
	ext.Requirements.Media = router.MediaExternalTool
	d, err = router.Select(router.SelectionInput{Operation: ext, States: healthyRouterStates()})
	if err != nil {
		return err
	}
	if d.CanStart {
		return fmt.Errorf("external=%+v", d)
	}
	return nil
}

func selectionMigrationCostCase() error {
	states := healthyRouterStates()
	states[router.Aria2] = router.BackendState{RuntimeAvailable: true, Health: router.HealthHealthy, MigrationCost: router.MigrationUnsafe}
	op := routingOp(routingGet("ftp://example.test/file.bin"))
	d, err := router.Select(router.SelectionInput{Operation: op, States: states, CurrentBackend: router.Native})
	if err != nil {
		return err
	}
	if !d.CanStart || d.Selected != router.Native {
		return fmt.Errorf("decision=%+v", d)
	}
	return nil
}

func selectionStartedFenceCase() error {
	d, err := router.Select(router.SelectionInput{Operation: routingOp(routingGet("ftp://example.test/file.bin")), States: healthyRouterStates(), CurrentBackend: router.Native, AttemptStarted: true})
	if err != nil {
		return err
	}
	if !d.CanStart || d.Selected != router.Native || d.Reason != router.SelectExistingAttempt {
		return fmt.Errorf("decision=%+v", d)
	}
	return nil
}

func selectionPersistenceCase() error {
	if !store.Available {
		return nil
	}
	dir, err := os.MkdirTemp("", "xgo-selection-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)
	db, err := store.Open(filepath.Join(dir, "engine.sqlite"), store.DefaultOptions())
	if err != nil {
		return err
	}
	defer db.Close()
	ctx := context.Background()
	if err = store.Migrate(ctx, db, 1); err != nil {
		return err
	}
	repo, err := store.NewRepository(db)
	if err != nil {
		return err
	}
	reqID, _ := identity.ParseRequestID("req_00000000000000000000000000000040")
	dlID, _ := identity.ParseDownloadID("dl_00000000000000000000000000000040")
	rev, _ := identity.NewRevision(1)
	if err = repo.CreateRequest(ctx, store.RequestRecord{ID: reqID, Revision: rev, ResourceIdentity: "res-router-lab", Method: "GET", SafeSpecJSON: `{}`, CreatedAtUnixMS: 2}); err != nil {
		return err
	}
	if err = repo.CreateDownload(ctx, store.DownloadRecord{ID: dlID, RequestID: reqID, CurrentRequestRevision: rev, State: "created", Revision: rev, CreatedAtUnixMS: 3, UpdatedAtUnixMS: 3}); err != nil {
		return err
	}
	attempt, _, err := repo.ReserveAttemptGeneration(ctx, dlID, rev, string(router.Native), 4)
	if err != nil {
		return err
	}
	decision, err := router.Select(router.SelectionInput{Operation: routingOp(routingGet("https://example.test/file.bin")), States: healthyRouterStates()})
	if err != nil {
		return err
	}
	if err = router.PersistSelection(ctx, repo, attempt, decision, 5); err != nil {
		return err
	}
	events, err := repo.ListAttemptDiagnostics(ctx, dlID, attempt.Generation, "backend")
	if err != nil {
		return err
	}
	if len(events) != 1 || events[0].EventType != "backend_selection" || !strings.Contains(events[0].SafePayload, `"reason":"healthy_default"`) {
		return fmt.Errorf("events=%+v", events)
	}
	attempt, err = repo.MutateAttempt(ctx, dlID, attempt.Generation, attempt.Revision, "prepared", "", "", 6)
	if err != nil {
		return err
	}
	if err = router.PersistSelection(ctx, repo, attempt, decision, 7); !errors.Is(err, router.ErrAttemptStarted) {
		return fmt.Errorf("post-start persistence err=%v", err)
	}
	return nil
}

func runSelectionMatrix() report {
	cases := []caseResult{
		runCase("deterministic_same_input", selectionDeterministicCase),
		runCase("preference_compatible_only", selectionPreferenceCase),
		runCase("unavailable_backend_fallback", selectionUnavailableCase),
		runCase("degraded_backend", selectionDegradedCase),
		runCase("direct_http_default", selectionDirectHTTPCase),
		runCase("mirrors_and_ftp", selectionMirrorFTPCase),
		runCase("media_external_shape", selectionMediaCase),
		runCase("migration_cost", selectionMigrationCostCase),
		runCase("started_attempt_fence", selectionStartedFenceCase),
		runCase("attempt_scoped_persistence", selectionPersistenceCase),
	}
	return finalizeReport("selection", cases)
}

func finalizeReport(mode string, cases []caseResult) report {
	r := report{SchemaVersion: 1, Mode: mode, Status: "pass", Total: len(cases), Cases: cases}
	for _, c := range cases {
		if c.Passed {
			r.Passed++
		}
	}
	if r.Passed != r.Total {
		r.Status = "fail"
	}
	return r
}
