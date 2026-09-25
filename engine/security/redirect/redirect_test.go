package redirect

import (
	"errors"
	"net/netip"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/security/route"
	"github.com/subhra74/xdm/engine/security/transportpolicy"
)

func ids(t *testing.T) (identity.RequestID, resource.Identity) {
	q, _ := identity.ParseRequestID("req_00000000000000000000000000000001")
	r, e := resource.DeriveIdentity("redirect-test", "r")
	if e != nil {
		t.Fatal(e)
	}
	return q, r
}
func secret(s string) domainrequest.SecretReference {
	r, _ := domainrequest.NewSecretReference(s)
	return r
}
func body() *domainrequest.Body {
	b, _ := domainrequest.NewBodyReference("body/1")
	return &domainrequest.Body{Ref: b, Replayability: domainrequest.BodyReplayable}
}
func intent(t *testing.T, url string) domainrequest.NetworkIntent {
	qres := func() resource.Identity { _, r := ids(t); return r }()
	in, e := domainrequest.NewNetworkIntent(domainrequest.NetworkIntent{TransportURL: url, Resource: qres, Method: "POST", Body: body(), Credentials: []domainrequest.CredentialReference{{Kind: domainrequest.CredentialAuthorization, Ref: secret("secret/a"), Scope: domainrequest.CredentialScope{Origin: "https://a.test", Resource: qres}}}})
	if e != nil {
		t.Fatal(e)
	}
	return in
}

func TestSameOriginAndCrossOriginCredentialPolicy(t *testing.T) {
	q, r := ids(t)
	in := intent(t, "https://a.test/start")
	s, e := Next(q, r, in, Chain{}, 302, "/next", []netip.Addr{netip.MustParseAddr("8.8.8.8")}, nil, transportpolicy.CleartextDecision{})
	if e != nil {
		t.Fatal(e)
	}
	if s.Method != "GET" || s.Body != nil || len(s.Credentials) != 1 || s.CrossOrigin {
		t.Fatalf("%+v", s)
	}
	s, e = Next(q, r, in, Chain{}, 302, "https://b.test/next", []netip.Addr{netip.MustParseAddr("8.8.4.4")}, nil, transportpolicy.CleartextDecision{})
	if e != nil {
		t.Fatal(e)
	}
	if len(s.Credentials) != 0 || !s.CrossOrigin {
		t.Fatalf("%+v", s)
	}
}

func TestPublicToPrivateRequiresFreshApproval(t *testing.T) {
	q, r := ids(t)
	in := intent(t, "https://a.test/start")
	if _, e := Next(q, r, in, Chain{}, 302, "https://private.test/x", []netip.Addr{netip.MustParseAddr("10.0.0.2")}, nil, transportpolicy.CleartextDecision{}); e == nil {
		t.Fatal("private redirect accepted")
	}
	a, _ := route.NewApproval(q, r, "https://private.test/x", route.Private)
	if _, e := Next(q, r, in, Chain{}, 302, "https://private.test/x", []netip.Addr{netip.MustParseAddr("10.0.0.2")}, []route.Approval{a}, transportpolicy.CleartextDecision{}); e != nil {
		t.Fatal(e)
	}
}

func TestHTTPSDowngradeNeedsPlatformPolicy(t *testing.T) {
	q, r := ids(t)
	in := intent(t, "https://a.test/start")
	if _, e := Next(q, r, in, Chain{}, 302, "http://a.test/x", []netip.Addr{netip.MustParseAddr("8.8.8.8")}, nil, transportpolicy.CleartextDecision{}); !errors.Is(e, ErrDowngrade) {
		t.Fatalf("%v", e)
	}
	d := transportpolicy.CleartextDecision{Known: true, Allowed: true, Host: "a.test", CredentialApprovalTarget: "http://a.test/x"}
	if _, e := Next(q, r, in, Chain{}, 302, "http://a.test/x", []netip.Addr{netip.MustParseAddr("8.8.8.8")}, nil, d); e != nil {
		t.Fatal(e)
	}
}

func Test307OneShotRejectedAndLoopBounded(t *testing.T) {
	q, r := ids(t)
	in := intent(t, "https://a.test/start")
	in.Body.Replayability = domainrequest.BodyOneShot
	if _, e := Next(q, r, in, Chain{}, 307, "/next", []netip.Addr{netip.MustParseAddr("8.8.8.8")}, nil, transportpolicy.CleartextDecision{}); !errors.Is(e, ErrBodyNotReplayable) {
		t.Fatalf("%v", e)
	}
	in.Body.Replayability = domainrequest.BodyReplayable
	if _, e := Next(q, r, in, Chain{Visited: []string{"https://a.test/next"}}, 302, "/next", []netip.Addr{netip.MustParseAddr("8.8.8.8")}, nil, transportpolicy.CleartextDecision{}); !errors.Is(e, ErrRedirectLoop) {
		t.Fatalf("%v", e)
	}
	if _, e := Next(q, r, in, Chain{Max: 1, Visited: []string{"https://a.test/start", "https://a.test/one"}}, 302, "/two", []netip.Addr{netip.MustParseAddr("8.8.8.8")}, nil, transportpolicy.CleartextDecision{}); !errors.Is(e, ErrRedirectLimit) {
		t.Fatalf("%v", e)
	}
}

func TestDiagnosticChainRedactsQuery(t *testing.T) {
	got := DiagnosticChain(Chain{Visited: []string{"https://a.test/path?token=super-secret#frag"}})
	if len(got) != 1 || got[0] != "https://a.test/path" {
		t.Fatalf("diagnostic=%v", got)
	}
}
