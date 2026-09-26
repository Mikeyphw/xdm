package runtime

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/publication"
	"github.com/subhra74/xdm/engine/ops"
	"github.com/subhra74/xdm/engine/runtime/platform"
)

func nextDesktopFrame(t *testing.T, e *Engine, want string) []byte {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	for {
		frame, err := e.NextFrame(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if frame.Kind == want {
			return frame.Payload
		}
	}
}

func answerDesktopRequest(t *testing.T, e *Engine, payload []byte, ok bool, reply any, code string) platform.Request {
	t.Helper()
	var req platform.Request
	if err := json.Unmarshal(payload, &req); err != nil {
		t.Fatal(err)
	}
	raw := json.RawMessage(nil)
	if reply != nil {
		var err error
		raw, err = json.Marshal(reply)
		if err != nil {
			t.Fatal(err)
		}
	}
	if err := e.PlatformReply(platform.Reply{RequestID: req.ID, Session: req.Session, OK: ok, Payload: raw, ErrorCode: code}); err != nil {
		t.Fatal(err)
	}
	return req
}

func TestDesktopPublicationCrossesPlatformBrokerAndReturnsReceipt(t *testing.T) {
	e := New(Config{EventBuffer: 32})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	defer e.Shutdown(context.Background())
	pub, _ := identity.ParsePublicationID("pub_00000000000000000000000000000076")
	dl, _ := identity.ParseDownloadID("dl_00000000000000000000000000000076")
	art, _ := identity.NewArtifactGeneration(7)
	req := DesktopPublicationRequest{Commit: publication.CommitRequest{PublicationID: pub, DownloadID: dl, Artifact: art, IdempotencyKey: "desktop:76", StagingIdentity: "stage:76"}, StagingPath: "/tmp/xdm.part", DestinationPath: "/tmp/xdm.bin", CollisionPolicy: "replace"}
	raw, _ := json.Marshal(req)
	if err := e.Submit(context.Background(), cmd(t, "00000000000000000000000000000076", "00000000000000000000000000000076", DesktopPublicationCommitKind, string(raw))); err != nil {
		t.Fatal(err)
	}
	platformFrame := nextDesktopFrame(t, e, "platform.request")
	got := answerDesktopRequest(t, e, platformFrame, true, publication.Receipt{PublicationID: pub, ReceiptID: "desktop-receipt:76", Location: "/tmp/xdm.bin"}, "")
	if got.Kind != platform.Publication || got.Refs.PublicationID != pub {
		t.Fatalf("unexpected request: %+v", got)
	}
	committed := nextDesktopFrame(t, e, "desktop.publication.committed")
	if !strings.Contains(string(committed), "desktop-receipt:76") {
		t.Fatalf("missing receipt: %s", committed)
	}
}

func TestDesktopPlatformServicesStayMechanismOnlyAndDoNotLeakSecrets(t *testing.T) {
	e := New(Config{EventBuffer: 64})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	defer e.Shutdown(context.Background())

	cases := []struct {
		token, kind, payload string
		pKind                platform.Kind
		reply                any
	}{
		{"77", DesktopSystemProxyKind, `{"target_url":"https://example.test/file"}`, platform.SystemProxy, map[string]any{"mode": "system", "proxy_uri": "http://proxy.test:8080", "pac_url": "https://proxy.test/proxy.pac"}},
		{"78", DesktopWakeupKind, `{"wake_id":"wake:78","deadline_ms":4102444800000,"reason":"retry"}`, platform.Wakeup, map[string]any{"registered": true, "wake_id": "wake:78"}},
	}
	for _, tc := range cases {
		tok := strings.Repeat("0", 30) + tc.token
		if err := e.Submit(context.Background(), cmd(t, tok, tok, tc.kind, tc.payload)); err != nil {
			t.Fatal(err)
		}
		p := nextDesktopFrame(t, e, "platform.request")
		got := answerDesktopRequest(t, e, p, true, tc.reply, "")
		if got.Kind != tc.pKind {
			t.Fatalf("kind=%s want=%s", got.Kind, tc.pKind)
		}
	}

	ref := ops.SecretRef{ID: "server-example", Scope: "https://example.test", Generation: 3}
	raw, _ := json.Marshal(DesktopSecretLookupRequest{Ref: ref})
	tok := "00000000000000000000000000000079"
	if err := e.Submit(context.Background(), cmd(t, tok, tok, DesktopSecretLookupKind, string(raw))); err != nil {
		t.Fatal(err)
	}
	p := nextDesktopFrame(t, e, "platform.request")
	got := answerDesktopRequest(t, e, p, true, map[string]any{"value": "super-secret-desktop-token"}, "")
	if got.Kind != platform.SecretLookup {
		t.Fatalf("kind=%s", got.Kind)
	}
	ack := nextDesktopFrame(t, e, "desktop.secret.resolved")
	if strings.Contains(string(ack), "super-secret-desktop-token") {
		t.Fatalf("secret leaked in ack: %s", ack)
	}
	if !strings.Contains(string(ack), "secret://server-example#g3") {
		t.Fatalf("safe ref missing: %s", ack)
	}
}
