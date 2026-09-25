package transportpolicy

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/runtime/platform"
)

func TestCleartextPolicyFailsClosedAndRequiresExactCredentialApproval(t *testing.T) {
	raw := "http://example.test/file"
	if !errors.Is(CheckCleartext(raw, false, CleartextDecision{}), ErrPlatformPolicyRequired) {
		t.Fatal("missing policy accepted")
	}
	if !errors.Is(CheckCleartext(raw, false, CleartextDecision{Known: true, Allowed: false, Host: "example.test"}), ErrCleartextDenied) {
		t.Fatal("denial ignored")
	}
	if e := CheckCleartext(raw, false, CleartextDecision{Known: true, Allowed: true, Host: "example.test"}); e != nil {
		t.Fatal(e)
	}
	if !errors.Is(CheckCleartext(raw, true, CleartextDecision{Known: true, Allowed: true, Host: "example.test"}), ErrCleartextCredentials) {
		t.Fatal("credential cleartext accepted")
	}
	if e := CheckCleartext(raw, true, CleartextDecision{Known: true, Allowed: true, Host: "example.test", CredentialApprovalTarget: raw}); e != nil {
		t.Fatal(e)
	}
}

func TestProxyResolutionAndCredentialSeparation(t *testing.T) {
	sec, _ := request.NewSecretReference("secret/proxy")
	refs := []request.CredentialReference{{Kind: request.CredentialProxyAuthorization, Ref: sec}}
	d, e := ResolveProxy(ProxyIntent{Mode: ProxyHTTP, Endpoint: "http://proxy.test:8080", CredentialRefs: refs}, nil)
	if e != nil || d.Mode != ProxyHTTP {
		t.Fatalf("%+v %v", d, e)
	}
	if len(ProxyCredentialRefs(refs)) != 1 {
		t.Fatal("proxy credential lost")
	}
	if _, e := ResolveProxy(ProxyIntent{Mode: ProxySystem}, nil); !errors.Is(e, ErrProxyResolution) {
		t.Fatalf("%v", e)
	}
	r := ProxyDecision{Mode: ProxySOCKS, Endpoint: "socks5://127.0.0.1:1080", Source: "platform"}
	if d, e := ResolveProxy(ProxyIntent{Mode: ProxySystem}, &r); e != nil || d.Endpoint != r.Endpoint {
		t.Fatalf("%+v %v", d, e)
	}
}

func TestTLSPlanPreservesHostname(t *testing.T) {
	p, e := PlanTLS("https://Example.Test/file", PlatformRoots)
	if e != nil || p.ServerName != "example.test" || p.MinVersion == 0 {
		t.Fatalf("%+v %v", p, e)
	}
}

func TestPlatformBrokerRequests(t *testing.T) {
	b := platform.NewBroker(2)
	defer b.Shutdown()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	go func() {
		for i := 0; i < 2; i++ {
			req, e := b.NextRequest(ctx)
			if e != nil {
				return
			}
			switch req.Kind {
			case platform.NetworkPolicy:
				payload, _ := json.Marshal(CleartextDecision{Known: true, Allowed: true, Host: "example.test"})
				_ = b.Reply(platform.Reply{RequestID: req.ID, Session: req.Session, OK: true, Payload: payload})
			case platform.SystemProxy:
				payload, _ := json.Marshal(ProxyDecision{Mode: ProxyHTTP, Endpoint: "http://proxy.test:8080", Source: "pac"})
				_ = b.Reply(platform.Reply{RequestID: req.ID, Session: req.Session, OK: true, Payload: payload})
			}
		}
	}()
	if d, e := RequestCleartextPolicy(ctx, b, "example.test"); e != nil || !d.Allowed {
		t.Fatalf("%+v %v", d, e)
	}
	if p, e := RequestSystemProxy(ctx, b, "https://example.test", ProxySystem); e != nil || p.Mode != ProxyHTTP {
		t.Fatalf("%+v %v", p, e)
	}
}

func TestSensitiveQueryRequiresCleartextCredentialApproval(t *testing.T) {
	raw := "http://a.test/file?access_token=secret-value"
	d := CleartextDecision{Known: true, Allowed: true, Host: "a.test"}
	if !HasCredentialBearingQuery(raw) {
		t.Fatal("sensitive query not detected")
	}
	if !errors.Is(CheckCleartext(raw, HasCredentialBearingQuery(raw), d), ErrCleartextCredentials) {
		t.Fatal("sensitive query allowed without exact approval")
	}
}

func TestMissingPlatformReplyFailsSafely(t *testing.T) {
	b := platform.NewBroker(1)
	defer b.Shutdown()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Millisecond)
	defer cancel()
	if _, e := RequestCleartextPolicy(ctx, b, "a.test"); e == nil {
		t.Fatal("missing platform reply accepted")
	}
}
