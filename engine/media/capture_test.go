package media

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"
)

func validEnvelope() CaptureEnvelope {
	l := int64(123)
	return CaptureEnvelope{
		Version: CaptureEnvelopeVersion,
		Request: RequestEvidence{
			URL:     "https://Media.Example/video?id=1",
			Method:  "POST",
			Headers: []Header{{Name: "accept", Value: "video/mp4"}},
			Body:    &BodyEvidence{Reference: "body-ref-1", ContentType: "application/json", Length: &l, Replayable: true},
		},
		Page:            PageContext{PageURL: "https://page.example/watch", FrameOrigin: "https://page.example", SessionID: "sess-1", DocumentGeneration: 8},
		Response:        ResponseMetadata{StatusCode: 206, ContentType: "video/mp4", ObservedAt: time.Unix(10, 0)},
		MediaHints:      []MediaHint{{Kind: "container", Value: "mp4"}},
		CredentialScope: CredentialScope{Origin: "https://media.example", PathPrefix: "/video", Ref: "secret-ref-1"},
	}
}

func TestCaptureEnvelopePOSTReplayEvidenceAndRedaction(t *testing.T) {
	env, err := NewCaptureEnvelope(validEnvelope())
	if err != nil {
		t.Fatalf("NewCaptureEnvelope: %v", err)
	}
	if env.Request.Method != "POST" || env.Request.Body == nil || !env.Request.Body.Replayable {
		t.Fatalf("POST body evidence not preserved: %#v", env.Request)
	}
	if env.ReplayEvidenceKey() == env.LogicalObservationKey() {
		t.Fatalf("replay evidence and logical observation keys must be separate")
	}
	diag := env.SafeDiagnostic()
	raw, err := json.Marshal(diag)
	if err != nil {
		t.Fatal(err)
	}
	text := string(raw)
	if strings.Contains(text, "body-ref-1") || strings.Contains(text, "secret-ref-1") || strings.Contains(text, "video/mp4\"") {
		t.Fatalf("diagnostic leaked runtime/sensitive evidence: %s", text)
	}
	if !diag.HasBodyReference {
		t.Fatalf("diagnostic should expose only body-ref presence")
	}
}

func TestCaptureEnvelopeRejectsSecretRawHeaders(t *testing.T) {
	env := validEnvelope()
	env.Request.Headers = append(env.Request.Headers, Header{Name: "Authorization", Value: "Bearer token"})
	_, err := NewCaptureEnvelope(env)
	if !errors.Is(err, ErrSensitiveCaptureValue) {
		t.Fatalf("expected sensitive header rejection, got %v", err)
	}
}

func TestCaptureEnvelopeRejectsMalformedAndOversized(t *testing.T) {
	bad := validEnvelope()
	bad.Request.URL = "https://example.com/" + strings.Repeat("a", MaxURLBytes+1)
	_, err := NewCaptureEnvelope(bad)
	if !errors.Is(err, ErrOversizedCaptureField) {
		t.Fatalf("expected oversized URL rejection, got %v", err)
	}

	payload := []byte(`{"version":1`)
	if _, err := ParseCaptureEnvelopeJSON(payload); err == nil {
		t.Fatalf("expected malformed json rejection")
	}

	huge := []byte(strings.Repeat("x", MaxEnvelopeBytes+1))
	if _, err := ParseCaptureEnvelopeJSON(huge); !errors.Is(err, ErrOversizedCaptureField) {
		t.Fatalf("expected oversized envelope rejection, got %v", err)
	}
}

func TestCaptureGenerationFence(t *testing.T) {
	f, err := NewGenerationFence("sess-1", 8, 2)
	if err != nil {
		t.Fatal(err)
	}
	activeMain := f.Evaluate(Observation{SessionID: "sess-1", DocumentGeneration: 8, FrameOrigin: "https://page.example", EvidenceKey: "a"})
	activeChild := f.Evaluate(Observation{SessionID: "sess-1", DocumentGeneration: 8, FrameOrigin: "https://frame.example", EvidenceKey: "a-frame"})
	if activeMain.Status != GenerationAccepted || !activeMain.MutatesActiveGraph {
		t.Fatalf("active generation decision wrong: %#v", activeMain)
	}
	if activeChild.Status != GenerationAccepted || !activeChild.MutatesActiveGraph {
		t.Fatalf("multiple frame decision wrong: %#v", activeChild)
	}
	stale := f.Evaluate(Observation{SessionID: "sess-1", DocumentGeneration: 7, EvidenceKey: "b"})
	if stale.Status != GenerationHistorical || stale.MutatesActiveGraph || !stale.HistoricalRetained {
		t.Fatalf("stale generation decision wrong: %#v", stale)
	}
	lateWrongSession := f.Evaluate(Observation{SessionID: "sess-2", DocumentGeneration: 8, EvidenceKey: "c"})
	if lateWrongSession.Status != GenerationRejected || lateWrongSession.MutatesActiveGraph {
		t.Fatalf("session mismatch decision wrong: %#v", lateWrongSession)
	}
	if err := f.Navigate(9); err != nil {
		t.Fatal(err)
	}
	oldActive := f.Evaluate(Observation{SessionID: "sess-1", DocumentGeneration: 8, EvidenceKey: "d"})
	if oldActive.Status != GenerationHistorical || oldActive.MutatesActiveGraph {
		t.Fatalf("old active should become historical after navigation: %#v", oldActive)
	}
	_ = f.Evaluate(Observation{SessionID: "sess-1", DocumentGeneration: 6, EvidenceKey: "e"})
	_ = f.Evaluate(Observation{SessionID: "sess-1", DocumentGeneration: 5, EvidenceKey: "f"})
	h := f.Historical()
	if len(h) != 2 {
		t.Fatalf("bounded retention should keep exactly 2 historical observations, got %d: %#v", len(h), h)
	}
	kept := map[string]bool{h[0].EvidenceKey: true, h[1].EvidenceKey: true}
	if !kept["e"] || !kept["f"] {
		t.Fatalf("bounded retention should keep newest stale observations, got %#v", h)
	}
}
