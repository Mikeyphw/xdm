package dial

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strings"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/security/route"
)

type resolverFunc func(context.Context, string) (Resolution, error)

func (f resolverFunc) Resolve(c context.Context, h string) (Resolution, error) { return f(c, h) }

type fakeConn struct{ net.Conn }

func ids(t *testing.T) (identity.RequestID, resource.Identity) {
	t.Helper()
	q, _ := identity.ParseRequestID("req_00000000000000000000000000000001")
	r, e := resource.DeriveIdentity("dial-test", "r")
	if e != nil {
		t.Fatal(e)
	}
	return q, r
}

func TestBoundDialerRetriesOnlyValidatedCandidatesAndPreservesHostname(t *testing.T) {
	q, r := ids(t)
	calls := []string{}
	d := BoundDialer{
		Resolver: resolverFunc(func(_ context.Context, h string) (Resolution, error) {
			return Resolution{Host: h, Candidates: []netip.Addr{netip.MustParseAddr("8.8.8.8"), netip.MustParseAddr("1.1.1.1")}, Source: "test"}, nil
		}),
		DialContext: func(_ context.Context, _ string, addr string) (net.Conn, error) {
			calls = append(calls, addr)
			if strings.HasPrefix(addr, "8.8.8.8:") {
				return nil, errors.New("first down")
			}
			return &fakeConn{}, nil
		},
	}
	_, b, e := d.Dial(context.Background(), route.Target{RequestID: q, Resource: r, URL: "https://Example.Test/file"}, nil)
	if e != nil {
		t.Fatal(e)
	}
	if len(calls) != 2 || !strings.HasPrefix(calls[0], "8.8.8.8:") || !strings.HasPrefix(calls[1], "1.1.1.1:") {
		t.Fatalf("calls=%v", calls)
	}
	if b.TLSServerName != "example.test" || !strings.HasPrefix(b.HTTPHost, "example.test") || b.Connected != netip.MustParseAddr("1.1.1.1") {
		t.Fatalf("binding=%+v", b)
	}
}

func TestRebindingCannotEscapeValidatedSet(t *testing.T) {
	q, r := ids(t)
	dialed := []string{}
	d := BoundDialer{Resolver: resolverFunc(func(_ context.Context, h string) (Resolution, error) {
		return Resolution{Host: h, Candidates: []netip.Addr{netip.MustParseAddr("8.8.4.4")}}, nil
	}), DialContext: func(_ context.Context, _ string, a string) (net.Conn, error) {
		dialed = append(dialed, a)
		return nil, errors.New("fail")
	}}
	_, _, _ = d.Dial(context.Background(), route.Target{RequestID: q, Resource: r, URL: "https://rebinding.test/x"}, nil)
	if len(dialed) != 1 || !strings.HasPrefix(dialed[0], "8.8.4.4:") {
		t.Fatalf("dialed=%v", dialed)
	}
	for _, a := range dialed {
		if strings.Contains(a, "127.0.0.1") {
			t.Fatal("unvalidated rebinding address dialed")
		}
	}
}

func TestPrivateCandidateWithoutApprovalNeverDialed(t *testing.T) {
	q, r := ids(t)
	called := false
	d := BoundDialer{Resolver: resolverFunc(func(_ context.Context, h string) (Resolution, error) {
		return Resolution{Host: h, Candidates: []netip.Addr{netip.MustParseAddr("10.0.0.2")}}, nil
	}), DialContext: func(context.Context, string, string) (net.Conn, error) { called = true; return &fakeConn{}, nil }}
	if _, _, e := d.Dial(context.Background(), route.Target{RequestID: q, Resource: r, URL: "https://private.test/x"}, nil); !errors.Is(e, ErrNoApprovedRoute) {
		t.Fatalf("err=%v", e)
	}
	if called {
		t.Fatal("private candidate dialed")
	}
}

func TestResolverHostMismatchFailsClosed(t *testing.T) {
	q, r := ids(t)
	d := BoundDialer{Resolver: resolverFunc(func(context.Context, string) (Resolution, error) {
		return Resolution{Host: "other.test", Candidates: []netip.Addr{netip.MustParseAddr("8.8.8.8")}}, nil
	}), DialContext: func(context.Context, string, string) (net.Conn, error) { t.Fatal("dial called"); return nil, nil }}
	if _, _, e := d.Dial(context.Background(), route.Target{RequestID: q, Resource: r, URL: "https://a.test/x"}, nil); !errors.Is(e, ErrResolutionFailed) {
		t.Fatalf("err=%v", e)
	}
}
