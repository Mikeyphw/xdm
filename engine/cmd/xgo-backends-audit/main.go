package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	httpbackend "github.com/subhra74/xdm/engine/backends/http"
	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/security/redirect"
	"github.com/subhra74/xdm/engine/security/transportpolicy"
	"github.com/subhra74/xdm/engine/transfer/retry"
)

type caseResult struct {
	Name   string `json:"name"`
	Passed bool   `json:"passed"`
	Detail string `json:"detail,omitempty"`
}

type report struct {
	SchemaVersion int          `json:"schema_version"`
	Mode          string       `json:"mode"`
	Status        string       `json:"status"`
	Passed        int          `json:"passed"`
	Total         int          `json:"total"`
	Cases         []caseResult `json:"cases"`
}

type material struct {
	mu      sync.Mutex
	bodies  map[string][]byte
	secrets map[domainrequest.SecretReference][]byte
	opens   map[string]int
}

func bodyKey(kind domainrequest.BodySourceKind, ref domainrequest.BodyReference) string {
	return string(kind) + ":" + string(ref)
}

func (m *material) OpenBody(_ context.Context, kind domainrequest.BodySourceKind, ref domainrequest.BodyReference) (io.ReadCloser, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	key := bodyKey(kind, ref)
	raw, ok := m.bodies[key]
	if !ok {
		return nil, errors.New("missing body material")
	}
	m.opens[key]++
	return io.NopCloser(bytes.NewReader(append([]byte(nil), raw...))), nil
}

func (m *material) ResolveSecret(_ context.Context, ref domainrequest.SecretReference) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	raw, ok := m.secrets[ref]
	if !ok {
		return nil, errors.New("missing secret material")
	}
	return append([]byte(nil), raw...), nil
}

func mustResource(name string) resource.Identity {
	r, err := resource.DeriveIdentity("xgo36-lab", name)
	if err != nil {
		panic(err)
	}
	return r
}

func mustBodyRef(name string) domainrequest.BodyReference {
	r, err := domainrequest.NewBodyReference(name)
	if err != nil {
		panic(err)
	}
	return r
}

func mustSecretRef(name string) domainrequest.SecretReference {
	r, err := domainrequest.NewSecretReference(name)
	if err != nil {
		panic(err)
	}
	return r
}

func mustPost(rawURL string, kind domainrequest.BodySourceKind, ref domainrequest.BodyReference, payload []byte) domainrequest.NetworkIntent {
	length := int64(len(payload))
	replayability := domainrequest.BodyReplayable
	if kind == domainrequest.BodySourceOneShot {
		replayability = domainrequest.BodyOneShot
	}
	in, err := domainrequest.NewNetworkIntent(domainrequest.NetworkIntent{
		TransportURL: rawURL,
		Resource:     mustResource(string(kind)),
		Method:       http.MethodPost,
		Body: &domainrequest.Body{
			Kind:          kind,
			Ref:           ref,
			Replayability: replayability,
			ContentType:   "application/octet-stream",
			Length:        &length,
		},
	})
	if err != nil {
		panic(err)
	}
	return in
}

type fixedClock struct{ now time.Time }

func (c fixedClock) Now() time.Time { return c.now }

func runCase(name string, fn func() error) caseResult {
	r := caseResult{Name: name}
	defer func() {
		if v := recover(); v != nil {
			r.Detail = fmt.Sprintf("panic: %v", v)
		}
	}()
	if err := fn(); err != nil {
		r.Detail = err.Error()
		return r
	}
	r.Passed = true
	return r
}

func postDownloadCase() error {
	payload := []byte("captured-post-body")
	ref := mustBodyRef("body/lab/post")
	mat := &material{bodies: map[string][]byte{bodyKey(domainrequest.BodySourceImmutableBytes, ref): payload}, secrets: map[domainrequest.SecretReference][]byte{}, opens: map[string]int{}}
	var gotMethod, gotBody, gotRange string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		raw, _ := io.ReadAll(r.Body)
		gotBody = string(raw)
		gotRange = r.Header.Get("Range")
		_, _ = w.Write([]byte("artifact"))
	}))
	defer srv.Close()
	intent := mustPost(srv.URL, domainrequest.BodySourceImmutableBytes, ref, payload)
	factory, err := httpbackend.NewFactory(intent, mat, mat)
	if err != nil {
		return err
	}
	req, err := factory.NewRequest(context.Background(), intent.TransportURL, 0)
	if err != nil {
		return err
	}
	resp, err := srv.Client().Do(req)
	if err != nil {
		return err
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
	if gotMethod != http.MethodPost || gotBody != string(payload) || gotRange != "" {
		return fmt.Errorf("method=%q body=%q range=%q", gotMethod, gotBody, gotRange)
	}
	return nil
}

func bodyKindsCase() error {
	for _, kind := range []domainrequest.BodySourceKind{domainrequest.BodySourceImmutableBytes, domainrequest.BodySourceImmutableFile, domainrequest.BodySourceSecretReference} {
		payload := []byte("body-" + string(kind))
		ref := mustBodyRef("body/lab/" + string(kind))
		mat := &material{bodies: map[string][]byte{bodyKey(kind, ref): payload}, secrets: map[domainrequest.SecretReference][]byte{}, opens: map[string]int{}}
		intent := mustPost("https://example.test/export", kind, ref, payload)
		factory, err := httpbackend.NewFactory(intent, mat, mat)
		if err != nil {
			return err
		}
		req, err := factory.NewRequest(context.Background(), intent.TransportURL, 0)
		if err != nil {
			return err
		}
		first, _ := io.ReadAll(req.Body)
		_ = req.Body.Close()
		if req.GetBody == nil {
			return fmt.Errorf("%s missing GetBody", kind)
		}
		replay, err := req.GetBody()
		if err != nil {
			return err
		}
		second, _ := io.ReadAll(replay)
		_ = replay.Close()
		if !bytes.Equal(first, payload) || !bytes.Equal(second, payload) || mat.opens[bodyKey(kind, ref)] != 2 {
			return fmt.Errorf("%s not replayed exactly", kind)
		}
	}
	one := mustPost("https://example.test/export", domainrequest.BodySourceOneShot, mustBodyRef("body/lab/one-shot"), []byte("one"))
	factory, err := httpbackend.NewFactory(one, &material{}, &material{})
	if err != nil {
		return err
	}
	if _, err = factory.NewRequest(context.Background(), one.TransportURL, 0); !errors.Is(err, httpbackend.ErrOneShotUnsupported) {
		return fmt.Errorf("one-shot err=%v", err)
	}
	return nil
}

func retryReplayableCase() error {
	intent := mustPost("https://example.test/retry", domainrequest.BodySourceImmutableBytes, mustBodyRef("body/lab/retry"), []byte("replay"))
	f, _ := failure.NewDefault(failure.NetworkUnavailable, errors.New("disconnect"))
	d, err := (retry.Engine{Clock: fixedClock{time.Unix(100, 0)}}).Decide(retry.Input{Failure: f, AttemptCount: 1, Queue: retry.QueuePolicy{Enabled: true, MaxAttempts: 3, BaseDelay: time.Second}, NetworkAvailable: true, Replayable: intent.Replayable()})
	if err != nil {
		return err
	}
	if d.Kind != retry.RetryAt {
		return fmt.Errorf("decision=%+v", d)
	}
	return nil
}

func oneShotRetryCase() error {
	intent := mustPost("https://example.test/retry", domainrequest.BodySourceOneShot, mustBodyRef("body/lab/one-shot-retry"), []byte("one"))
	f, _ := failure.NewDefault(failure.NetworkUnavailable, errors.New("disconnect"))
	d, err := (retry.Engine{Clock: fixedClock{time.Unix(100, 0)}}).Decide(retry.Input{Failure: f, AttemptCount: 1, Queue: retry.QueuePolicy{Enabled: true, MaxAttempts: 3, BaseDelay: time.Second}, NetworkAvailable: true, Replayable: intent.Replayable()})
	if err != nil {
		return err
	}
	if d.Kind != retry.Terminal || d.Reason != "request_not_replayable" {
		return fmt.Errorf("decision=%+v", d)
	}
	return nil
}

func redirectCase() error {
	reqID, _ := identity.ParseRequestID("req_00000000000000000000000000000036")
	current := mustPost("https://a.test/export", domainrequest.BodySourceImmutableBytes, mustBodyRef("body/lab/redirect"), []byte("body"))
	addresses := []netip.Addr{netip.MustParseAddr("8.8.8.8")}
	toGet, _, err := httpbackend.RedirectIntent(reqID, current, redirect.Chain{}, http.StatusSeeOther, "/done", addresses, nil, transportpolicy.CleartextDecision{})
	if err != nil {
		return err
	}
	if toGet.Method != http.MethodGet || toGet.Body != nil {
		return fmt.Errorf("303=%+v", toGet)
	}
	toPost, _, err := httpbackend.RedirectIntent(reqID, current, redirect.Chain{}, http.StatusTemporaryRedirect, "/again", addresses, nil, transportpolicy.CleartextDecision{})
	if err != nil {
		return err
	}
	if toPost.Method != http.MethodPost || toPost.Body == nil || !toPost.Body.IsReplayable() {
		return fmt.Errorf("307=%+v", toPost)
	}
	return nil
}

func credentialedCase() error {
	payload := []byte("credentialed")
	bodyRef := mustBodyRef("body/lab/credentialed")
	secretRef := mustSecretRef("secret/lab/auth")
	mat := &material{bodies: map[string][]byte{bodyKey(domainrequest.BodySourceSecretReference, bodyRef): payload}, secrets: map[domainrequest.SecretReference][]byte{secretRef: []byte("Bearer credential-secret")}, opens: map[string]int{}}
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Get("Authorization")
		_, _ = w.Write([]byte("ok"))
	}))
	defer srv.Close()
	intent := mustPost(srv.URL, domainrequest.BodySourceSecretReference, bodyRef, payload)
	intent.Credentials = []domainrequest.CredentialReference{{Kind: domainrequest.CredentialAuthorization, Ref: secretRef, Scope: domainrequest.CredentialScope{Origin: srv.URL, Resource: intent.Resource}}}
	var err error
	intent, err = domainrequest.NewNetworkIntent(intent)
	if err != nil {
		return err
	}
	factory, err := httpbackend.NewFactory(intent, mat, mat)
	if err != nil {
		return err
	}
	req, err := factory.NewRequest(context.Background(), intent.TransportURL, 0)
	if err != nil {
		return err
	}
	resp, err := srv.Client().Do(req)
	if err != nil {
		return err
	}
	_ = resp.Body.Close()
	if got != "Bearer credential-secret" {
		return fmt.Errorf("authorization=%q", got)
	}
	return nil
}

func rangeGuardCase() error {
	intent := mustPost("https://example.test/export", domainrequest.BodySourceImmutableBytes, mustBodyRef("body/lab/range"), []byte("body"))
	mat := &material{bodies: map[string][]byte{bodyKey(intent.Body.Kind, intent.Body.Ref): []byte("body")}, secrets: map[domainrequest.SecretReference][]byte{}, opens: map[string]int{}}
	factory, err := httpbackend.NewFactory(intent, mat, mat)
	if err != nil {
		return err
	}
	if _, err = factory.NewRequest(context.Background(), intent.TransportURL, 1); !errors.Is(err, httpbackend.ErrUnsafeRangeResume) {
		return fmt.Errorf("POST range err=%v", err)
	}
	if intent.RangeResumeAllowed() {
		return errors.New("POST reported range resumable")
	}
	return nil
}

func redactionCase() error {
	bodyRef := mustBodyRef("body/lab/private-ref")
	secretRef := mustSecretRef("secret/lab/private-ref")
	intent := mustPost("https://example.test/export?token=query-secret", domainrequest.BodySourceSecretReference, bodyRef, []byte("runtime-body-secret"))
	intent.Credentials = []domainrequest.CredentialReference{{Kind: domainrequest.CredentialAuthorization, Ref: secretRef, Scope: domainrequest.CredentialScope{Origin: "https://example.test", Resource: intent.Resource}}}
	var err error
	intent, err = domainrequest.NewNetworkIntent(intent)
	if err != nil {
		return err
	}
	factory, err := httpbackend.NewFactory(intent, nil, nil)
	if err != nil {
		return err
	}
	d, err := factory.Diagnostic()
	if err != nil {
		return err
	}
	raw, _ := json.Marshal(d)
	text := string(raw)
	for _, forbidden := range []string{"query-secret", string(bodyRef), string(secretRef), "runtime-body-secret", "credential-secret"} {
		if strings.Contains(text, forbidden) {
			return fmt.Errorf("diagnostic leaked %q: %s", forbidden, text)
		}
	}
	if d.Headers["Authorization"] != "<redacted-ref>" || d.URL != "https://example.test/export" {
		return fmt.Errorf("diagnostic=%+v", d)
	}
	return nil
}

func main() {
	mode := flag.String("mode", "post-replay", "audit mode")
	output := flag.String("output", "", "report path")
	flag.Parse()
	var r report
	switch *mode {
	case "post-replay":
		cases := []caseResult{
			runCase("body_source_kinds", bodyKindsCase),
			runCase("post_download", postDownloadCase),
			runCase("retry_replayable_body", retryReplayableCase),
			runCase("one_shot_retry_refused", oneShotRetryCase),
			runCase("redirect_replay_semantics", redirectCase),
			runCase("credentialed_post", credentialedCase),
			runCase("post_range_resume_rejected", rangeGuardCase),
			runCase("safe_diagnostic_redaction", redactionCase),
		}
		r = report{SchemaVersion: 1, Mode: *mode, Status: "pass", Total: len(cases), Cases: cases}
		for _, c := range cases {
			if c.Passed {
				r.Passed++
			}
		}
		if r.Passed != r.Total {
			r.Status = "fail"
		}
	case "ftp":
		r = runFTPLab()
	case "metalink":
		r = runMetalinkCorpus()
	case "compatibility":
		r = runCompatibilityMatrix()
	case "selection":
		r = runSelectionMatrix()
	default:
		fmt.Fprintln(os.Stderr, "unsupported mode:", *mode)
		os.Exit(2)
	}
	raw, _ := json.MarshalIndent(r, "", "  ")
	raw = append(raw, '\n')
	if *output != "" {
		if err := os.MkdirAll(filepath.Dir(*output), 0o755); err != nil {
			panic(err)
		}
		if err := os.WriteFile(*output, raw, 0o644); err != nil {
			panic(err)
		}
	}
	fmt.Print(string(raw))
	if r.Status != "pass" {
		os.Exit(1)
	}
}
