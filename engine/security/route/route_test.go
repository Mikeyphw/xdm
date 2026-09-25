package route_test

import (
	"errors"
	"net/netip"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/security/route"
)

func requestID(t *testing.T) identity.RequestID {
	t.Helper()
	id, err := identity.ParseRequestID("req_00000000000000000000000000000020")
	if err != nil {
		t.Fatal(err)
	}
	return id
}
func resourceID(t *testing.T, key string) resource.Identity {
	t.Helper()
	id, err := resource.DeriveIdentity("route", key)
	if err != nil {
		t.Fatal(err)
	}
	return id
}
func addr(s string) netip.Addr { return netip.MustParseAddr(s) }

func TestRepresentativeAddressClassification(t *testing.T) {
	cases := map[string]route.Class{
		"8.8.8.8":              route.Public,
		"10.0.0.1":             route.Private,
		"172.16.0.1":           route.Private,
		"192.168.1.1":          route.Private,
		"127.0.0.1":            route.Loopback,
		"169.254.1.1":          route.LinkLocal,
		"100.64.0.1":           route.Reserved,
		"192.0.2.1":            route.Reserved,
		"224.0.0.1":            route.Multicast,
		"0.0.0.0":              route.Unspecified,
		"2001:4860:4860::8888": route.Public,
		"fc00::1":              route.Private,
		"fe80::1":              route.LinkLocal,
		"::1":                  route.Loopback,
		"ff02::1":              route.Multicast,
		"2001:db8::1":          route.Reserved,
		"::":                   route.Unspecified,
		"::ffff:10.1.2.3":      route.Private,
		"::ffff:8.8.8.8":       route.Public,
	}
	for raw, want := range cases {
		if got := route.Classify(addr(raw)); got != want {
			t.Errorf("%s: got %s want %s", raw, got, want)
		}
	}
}

func TestMixedPublicPrivateDNSRequiresScopedApproval(t *testing.T) {
	target := route.Target{RequestID: requestID(t), Resource: resourceID(t, "file-a"), URL: "https://origin.example/file?a=1", Addresses: []netip.Addr{addr("8.8.8.8"), addr("10.0.0.8")}}
	_, err := route.Evaluate(target, nil)
	var denied *route.Denial
	if !errors.As(err, &denied) || denied.Reason != route.DenialScopedApprovalRequired || denied.Class != route.Private {
		t.Fatalf("err=%v denied=%+v", err, denied)
	}
	approval, err := route.NewApproval(target.RequestID, target.Resource, target.URL, route.Private)
	if err != nil {
		t.Fatal(err)
	}
	decision, err := route.Evaluate(target, []route.Approval{approval})
	if err != nil {
		t.Fatal(err)
	}
	if len(decision.Addresses) != 2 {
		t.Fatalf("decision=%+v", decision)
	}
}

func TestApprovalIsExactToRequestResourceTargetAndClass(t *testing.T) {
	rid := requestID(t)
	res := resourceID(t, "file-a")
	url := "https://private.example/a/file?token=opaque"
	approval, err := route.NewApproval(rid, res, url, route.Private)
	if err != nil {
		t.Fatal(err)
	}
	good := route.Target{RequestID: rid, Resource: res, URL: url, Addresses: []netip.Addr{addr("10.0.0.7")}}
	if _, err := route.Evaluate(good, []route.Approval{approval}); err != nil {
		t.Fatal(err)
	}

	changedURL := good
	changedURL.URL = "https://private.example/a/other?token=opaque"
	if _, err := route.Evaluate(changedURL, []route.Approval{approval}); err == nil {
		t.Fatal("approval reused for another resource URL")
	}
	changedResource := good
	changedResource.Resource = resourceID(t, "file-b")
	if _, err := route.Evaluate(changedResource, []route.Approval{approval}); err == nil {
		t.Fatal("approval reused for another logical resource")
	}
	changedRequest := good
	changedRequest.RequestID, _ = identity.ParseRequestID("req_00000000000000000000000000000021")
	if _, err := route.Evaluate(changedRequest, []route.Approval{approval}); err == nil {
		t.Fatal("approval reused for another request")
	}
}

func TestMirrorAndRedirectMustBeReevaluated(t *testing.T) {
	rid := requestID(t)
	res := resourceID(t, "file-a")
	primaryURL := "https://private.example/file"
	approval, _ := route.NewApproval(rid, res, primaryURL, route.Private)
	primary := route.Target{RequestID: rid, Resource: res, URL: primaryURL, Addresses: []netip.Addr{addr("10.1.1.1")}}
	if _, err := route.Evaluate(primary, []route.Approval{approval}); err != nil {
		t.Fatal(err)
	}
	for _, next := range []string{"https://mirror.example/file", "https://private.example/redirected"} {
		candidate := route.Target{RequestID: rid, Resource: res, URL: next, Addresses: []netip.Addr{addr("10.1.1.1")}}
		if _, err := route.Evaluate(candidate, []route.Approval{approval}); err == nil {
			t.Fatalf("approval incorrectly reused for %s", next)
		}
	}
}

func TestLiteralPrivateAddressCannotBypassEvaluation(t *testing.T) {
	target := route.Target{RequestID: requestID(t), Resource: resourceID(t, "literal"), URL: "https://127.0.0.1/file"}
	_, err := route.Evaluate(target, nil)
	var denied *route.Denial
	if !errors.As(err, &denied) || denied.Class != route.Loopback {
		t.Fatalf("err=%v denied=%+v", err, denied)
	}
}
