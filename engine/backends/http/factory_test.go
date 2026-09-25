package httpbackend

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/security/redirect"
	"github.com/subhra74/xdm/engine/security/transportpolicy"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
	httptransfer "github.com/subhra74/xdm/engine/transfer/http"
	"github.com/subhra74/xdm/engine/transfer/retry"
)

type memoryMaterial struct {
	mu      sync.Mutex
	bodies  map[string][]byte
	secrets map[domainrequest.SecretReference][]byte
	opens   map[string]int
}

func bodyKey(kind domainrequest.BodySourceKind, ref domainrequest.BodyReference) string {
	return string(kind) + ":" + string(ref)
}

func (m *memoryMaterial) OpenBody(_ context.Context, kind domainrequest.BodySourceKind, ref domainrequest.BodyReference) (io.ReadCloser, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	key := bodyKey(kind, ref)
	raw, ok := m.bodies[key]
	if !ok {
		return nil, errors.New("missing body")
	}
	m.opens[key]++
	return io.NopCloser(bytes.NewReader(append([]byte(nil), raw...))), nil
}

func (m *memoryMaterial) ResolveSecret(_ context.Context, ref domainrequest.SecretReference) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	raw, ok := m.secrets[ref]
	if !ok {
		return nil, errors.New("missing secret")
	}
	return append([]byte(nil), raw...), nil
}

func testResource(t *testing.T, name string) resource.Identity {
	t.Helper()
	r, err := resource.DeriveIdentity("xgo36", name)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func testBodyRef(t *testing.T, name string) domainrequest.BodyReference {
	t.Helper()
	r, err := domainrequest.NewBodyReference(name)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func testSecretRef(t *testing.T, name string) domainrequest.SecretReference {
	t.Helper()
	r, err := domainrequest.NewSecretReference(name)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func postIntent(t *testing.T, rawURL string, kind domainrequest.BodySourceKind, ref domainrequest.BodyReference, length int64) domainrequest.NetworkIntent {
	t.Helper()
	in, err := domainrequest.NewNetworkIntent(domainrequest.NetworkIntent{
		TransportURL: rawURL,
		Resource:     testResource(t, "post"),
		Method:       http.MethodPost,
		Body: &domainrequest.Body{
			Kind:          kind,
			Ref:           ref,
			Replayability: domainrequest.BodyReplayable,
			ContentType:   "application/x-www-form-urlencoded",
			Length:        &length,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	return in
}

func TestReplayableBodyKindsReopenAndOneShotIsExplicitlyUnsupported(t *testing.T) {
	ctx := context.Background()
	for _, kind := range []domainrequest.BodySourceKind{
		domainrequest.BodySourceImmutableBytes,
		domainrequest.BodySourceImmutableFile,
		domainrequest.BodySourceSecretReference,
	} {
		t.Run(string(kind), func(t *testing.T) {
			ref := testBodyRef(t, "body/"+string(kind))
			payload := []byte("payload-" + string(kind))
			provider := &memoryMaterial{bodies: map[string][]byte{bodyKey(kind, ref): payload}, secrets: map[domainrequest.SecretReference][]byte{}, opens: map[string]int{}}
			intent := postIntent(t, "https://example.test/export", kind, ref, int64(len(payload)))
			factory, err := NewFactory(intent, provider, provider)
			if err != nil {
				t.Fatal(err)
			}
			req, err := factory.NewRequest(ctx, intent.TransportURL, 0)
			if err != nil {
				t.Fatal(err)
			}
			got, _ := io.ReadAll(req.Body)
			_ = req.Body.Close()
			if !bytes.Equal(got, payload) || req.GetBody == nil || !factory.Replayable() || factory.RangeResumeAllowed() {
				t.Fatalf("request=%+v body=%q", req, got)
			}
			replay, err := req.GetBody()
			if err != nil {
				t.Fatal(err)
			}
			replayed, _ := io.ReadAll(replay)
			_ = replay.Close()
			if !bytes.Equal(replayed, payload) || provider.opens[bodyKey(kind, ref)] != 2 {
				t.Fatalf("replayed=%q opens=%d", replayed, provider.opens[bodyKey(kind, ref)])
			}
		})
	}

	ref := testBodyRef(t, "body/one-shot")
	oneShot, err := domainrequest.NewNetworkIntent(domainrequest.NetworkIntent{
		TransportURL: "https://example.test/export",
		Resource:     testResource(t, "one-shot"),
		Method:       http.MethodPost,
		Body: &domainrequest.Body{
			Kind:          domainrequest.BodySourceOneShot,
			Ref:           ref,
			Replayability: domainrequest.BodyOneShot,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	factory, err := NewFactory(oneShot, &memoryMaterial{}, &memoryMaterial{})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = factory.NewRequest(ctx, oneShot.TransportURL, 0); !errors.Is(err, ErrOneShotUnsupported) {
		t.Fatalf("one-shot request err=%v", err)
	}
	if factory.Replayable() {
		t.Fatal("one-shot request reported replayable")
	}
}

type testLifecycle struct {
	mu     sync.Mutex
	states []string
	failed failure.Category
}

func (l *testLifecycle) add(s string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.states = append(l.states, s)
}
func (l *testLifecycle) Start(context.Context) error    { l.add("running"); return nil }
func (l *testLifecycle) Complete(context.Context) error { l.add("transport_complete"); return nil }
func (l *testLifecycle) Pause(context.Context) error    { l.add("paused"); return nil }
func (l *testLifecycle) Fail(_ context.Context, f failure.Failure) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.failed = f.Category
	l.states = append(l.states, "failed")
	return nil
}

type testCommitter struct{}

func (testCommitter) CommitBlock(_ context.Context, file checkpoint.File, req checkpoint.CommitRequest) (store.CheckpointBlockRecord, error) {
	n, err := file.WriteAt(req.Data, req.StartByte)
	if err != nil {
		return store.CheckpointBlockRecord{}, err
	}
	if n != len(req.Data) {
		return store.CheckpointBlockRecord{}, io.ErrShortWrite
	}
	if err := file.Sync(); err != nil {
		return store.CheckpointBlockRecord{}, err
	}
	return store.CheckpointBlockRecord{DownloadID: req.DownloadID, Generation: req.Generation, BlockIndex: req.BlockIndex, StartByte: req.StartByte, CommittedLength: int64(len(req.Data)), HashAlgorithm: "sha256"}, nil
}

func TestCredentialedPOSTRunsThroughCanonicalTransferExecutorWithoutRange(t *testing.T) {
	requestPayload := []byte("token=abc&format=zip")
	responsePayload := []byte("artifact")
	var gotMethod, gotBody, gotAuthorization, gotRange string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		raw, _ := io.ReadAll(r.Body)
		gotBody = string(raw)
		gotAuthorization = r.Header.Get("Authorization")
		gotRange = r.Header.Get("Range")
		w.Header().Set("Content-Length", "8")
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write(responsePayload)
	}))
	defer srv.Close()

	bodyRef := testBodyRef(t, "body/post-transfer")
	secretRef := testSecretRef(t, "secret/auth")
	provider := &memoryMaterial{
		bodies:  map[string][]byte{bodyKey(domainrequest.BodySourceImmutableBytes, bodyRef): requestPayload},
		secrets: map[domainrequest.SecretReference][]byte{secretRef: []byte("Bearer runtime-secret")},
		opens:   map[string]int{},
	}
	intent := postIntent(t, srv.URL, domainrequest.BodySourceImmutableBytes, bodyRef, int64(len(requestPayload)))
	intent.Credentials = []domainrequest.CredentialReference{{
		Kind: domainrequest.CredentialAuthorization,
		Ref:  secretRef,
		Scope: domainrequest.CredentialScope{
			Origin:   srv.URL,
			Resource: intent.Resource,
		},
	}}
	intent, err := domainrequest.NewNetworkIntent(intent)
	if err != nil {
		t.Fatal(err)
	}
	factory, err := NewFactory(intent, provider, provider)
	if err != nil {
		t.Fatal(err)
	}

	downloadID, _ := identity.ParseDownloadID("dl_00000000000000000000000000000036")
	generation, _ := identity.NewAttemptGeneration(1)
	representation, err := httptransfer.RepresentationFromProbe(intent.Resource, httptransfer.ProbeResult{EffectiveURL: intent.TransportURL, Length: ptrInt64(int64(len(responsePayload))), ETag: `"post-v1"`})
	if err != nil {
		t.Fatal(err)
	}
	file, err := os.CreateTemp(t.TempDir(), "post-*.bin")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	lifecycle := &testLifecycle{}
	result, err := httptransfer.Execute(context.Background(), srv.Client(), httptransfer.ExecutePlan{
		URL:             intent.TransportURL,
		Representation:  representation,
		DownloadID:      downloadID,
		Generation:      generation,
		BufferBytes:     4096,
		CheckpointBytes: 4096,
		File:            file,
		Committer:       testCommitter{},
		Lifecycle:       lifecycle,
		RequestFactory:  factory,
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.Committed != int64(len(responsePayload)) || gotMethod != http.MethodPost || gotBody != string(requestPayload) || gotAuthorization != "Bearer runtime-secret" || gotRange != "" {
		t.Fatalf("result=%+v method=%q body=%q auth=%q range=%q", result, gotMethod, gotBody, gotAuthorization, gotRange)
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	stored, _ := io.ReadAll(file)
	if !bytes.Equal(stored, responsePayload) || lifecycle.failed != "" {
		t.Fatalf("stored=%q states=%v failed=%s", stored, lifecycle.states, lifecycle.failed)
	}
	if _, err := factory.NewRequest(context.Background(), intent.TransportURL, 4); !errors.Is(err, ErrUnsafeRangeResume) {
		t.Fatalf("POST range resume err=%v", err)
	}
}

func ptrInt64(v int64) *int64 { return &v }

type fixedClock struct{ now time.Time }

func (c fixedClock) Now() time.Time { return c.now }

func TestRetryEligibilityUsesWholeRequestReplayability(t *testing.T) {
	ref := testBodyRef(t, "body/retry")
	payload := []byte("replay-me")
	replayable := postIntent(t, "https://example.test/retry", domainrequest.BodySourceImmutableBytes, ref, int64(len(payload)))
	f, _ := failure.NewDefault(failure.NetworkUnavailable, errors.New("disconnect"))
	engine := retry.Engine{Clock: fixedClock{time.Unix(100, 0)}}
	queue := retry.QueuePolicy{Enabled: true, MaxAttempts: 4, BaseDelay: time.Second, MaxDelay: time.Minute}
	decision, err := engine.Decide(retry.Input{Failure: f, AttemptCount: 1, Queue: queue, NetworkAvailable: true, Replayable: replayable.Replayable()})
	if err != nil {
		t.Fatal(err)
	}
	if decision.Kind != retry.RetryAt {
		t.Fatalf("replayable decision=%+v", decision)
	}

	oneShot, err := domainrequest.NewNetworkIntent(domainrequest.NetworkIntent{
		TransportURL: "https://example.test/retry",
		Resource:     testResource(t, "retry-one-shot"),
		Method:       http.MethodPost,
		Body:         &domainrequest.Body{Kind: domainrequest.BodySourceOneShot, Ref: testBodyRef(t, "body/retry-one-shot"), Replayability: domainrequest.BodyOneShot},
	})
	if err != nil {
		t.Fatal(err)
	}
	decision, err = engine.Decide(retry.Input{Failure: f, AttemptCount: 1, Queue: queue, NetworkAvailable: true, Replayable: oneShot.Replayable()})
	if err != nil {
		t.Fatal(err)
	}
	if decision.Kind != retry.Terminal || decision.Reason != "request_not_replayable" {
		t.Fatalf("one-shot decision=%+v", decision)
	}
}

func TestRedirectSemanticsReuseCanonicalSecurityStateMachine(t *testing.T) {
	requestID, _ := identity.ParseRequestID("req_00000000000000000000000000000036")
	ref := testBodyRef(t, "body/redirect")
	current := postIntent(t, "https://a.test/export", domainrequest.BodySourceImmutableBytes, ref, 4)
	address := []netip.Addr{netip.MustParseAddr("8.8.8.8")}

	getIntent, _, err := RedirectIntent(requestID, current, redirect.Chain{}, http.StatusSeeOther, "/result", address, nil, transportpolicy.CleartextDecision{})
	if err != nil {
		t.Fatal(err)
	}
	if getIntent.Method != http.MethodGet || getIntent.Body != nil {
		t.Fatalf("303 intent=%+v", getIntent)
	}

	postIntent307, _, err := RedirectIntent(requestID, current, redirect.Chain{}, http.StatusTemporaryRedirect, "/retry", address, nil, transportpolicy.CleartextDecision{})
	if err != nil {
		t.Fatal(err)
	}
	if postIntent307.Method != http.MethodPost || postIntent307.Body == nil || !postIntent307.Body.IsReplayable() {
		t.Fatalf("307 intent=%+v", postIntent307)
	}

	oneShot, err := domainrequest.NewNetworkIntent(domainrequest.NetworkIntent{
		TransportURL: "https://a.test/export",
		Resource:     current.Resource,
		Method:       http.MethodPost,
		Body:         &domainrequest.Body{Kind: domainrequest.BodySourceOneShot, Ref: testBodyRef(t, "body/redirect-one-shot"), Replayability: domainrequest.BodyOneShot},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err = RedirectIntent(requestID, oneShot, redirect.Chain{}, http.StatusTemporaryRedirect, "/retry", address, nil, transportpolicy.CleartextDecision{}); !errors.Is(err, redirect.ErrBodyNotReplayable) {
		t.Fatalf("one-shot redirect err=%v", err)
	}
}

func TestSafeDiagnosticOmitsBodyMaterialReferencesSecretsAndQuery(t *testing.T) {
	bodyRef := testBodyRef(t, "body/private-name")
	secretRef := testSecretRef(t, "secret/private-name")
	intent := postIntent(t, "https://example.test/export?token=query-secret", domainrequest.BodySourceSecretReference, bodyRef, 11)
	intent.Credentials = []domainrequest.CredentialReference{{Kind: domainrequest.CredentialAuthorization, Ref: secretRef, Scope: domainrequest.CredentialScope{Origin: "https://example.test", Resource: intent.Resource}}}
	intent, err := domainrequest.NewNetworkIntent(intent)
	if err != nil {
		t.Fatal(err)
	}
	factory, err := NewFactory(intent, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	diagnostic, err := factory.Diagnostic()
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(diagnostic)
	text := string(raw)
	for _, forbidden := range []string{"query-secret", string(bodyRef), string(secretRef), "runtime-body-secret", "runtime-credential-secret"} {
		if strings.Contains(text, forbidden) {
			t.Fatalf("safe diagnostic leaked %q: %s", forbidden, text)
		}
	}
	if diagnostic.URL != "https://example.test/export" || diagnostic.Body == nil || diagnostic.Body.Kind != domainrequest.BodySourceSecretReference || diagnostic.Headers["Authorization"] != "<redacted-ref>" {
		t.Fatalf("diagnostic=%+v", diagnostic)
	}
}

type leakingBodyProvider struct{}

func (leakingBodyProvider) OpenBody(context.Context, domainrequest.BodySourceKind, domainrequest.BodyReference) (io.ReadCloser, error) {
	return nil, errors.New("provider leaked runtime-body-secret at /private/body/path")
}

type leakingSecretProvider struct{}

func (leakingSecretProvider) ResolveSecret(context.Context, domainrequest.SecretReference) ([]byte, error) {
	return nil, errors.New("provider leaked runtime-credential-secret")
}

func TestProviderFailuresAreOpaqueAndDoNotLeakRuntimeMaterial(t *testing.T) {
	bodyRef := testBodyRef(t, "body/error-redaction")
	intent := postIntent(t, "https://example.test/export", domainrequest.BodySourceImmutableBytes, bodyRef, 7)
	factory, err := NewFactory(intent, leakingBodyProvider{}, nil)
	if err != nil {
		t.Fatal(err)
	}
	_, err = factory.NewRequest(context.Background(), intent.TransportURL, 0)
	if !errors.Is(err, ErrBodyMaterialUnavailable) {
		t.Fatalf("body error=%v", err)
	}
	if strings.Contains(err.Error(), "runtime-body-secret") || strings.Contains(err.Error(), "/private/body/path") {
		t.Fatalf("body provider error leaked unsafe material: %v", err)
	}

	payload := []byte("payload")
	secretRef := testSecretRef(t, "secret/error-redaction")
	provider := &memoryMaterial{
		bodies:  map[string][]byte{bodyKey(domainrequest.BodySourceImmutableBytes, bodyRef): payload},
		secrets: map[domainrequest.SecretReference][]byte{},
		opens:   map[string]int{},
	}
	intent.Credentials = []domainrequest.CredentialReference{{
		Kind: domainrequest.CredentialAuthorization,
		Ref:  secretRef,
		Scope: domainrequest.CredentialScope{
			Origin:   "https://example.test",
			Resource: intent.Resource,
		},
	}}
	intent, err = domainrequest.NewNetworkIntent(intent)
	if err != nil {
		t.Fatal(err)
	}
	factory, err = NewFactory(intent, provider, leakingSecretProvider{})
	if err != nil {
		t.Fatal(err)
	}
	_, err = factory.NewRequest(context.Background(), intent.TransportURL, 0)
	if !errors.Is(err, ErrCredentialUnavailable) {
		t.Fatalf("credential error=%v", err)
	}
	if strings.Contains(err.Error(), "runtime-credential-secret") {
		t.Fatalf("secret provider error leaked unsafe material: %v", err)
	}
}
