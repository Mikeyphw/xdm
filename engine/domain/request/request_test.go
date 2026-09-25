package request_test

import (
	"errors"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
)

func mustResource(t *testing.T) resource.Identity {
	t.Helper()
	value, err := resource.DeriveIdentity("url", "https://example.test/file?token=opaque")
	if err != nil {
		t.Fatal(err)
	}
	return value
}

func TestRequestIsImmutableAndRevisioned(t *testing.T) {
	id, _ := identity.ParseRequestID("req_00000000000000000000000000000001")
	rev1, _ := identity.NewRevision(1)
	secret, _ := request.NewSecretReference("credential/main")
	first, err := request.New(id, rev1, request.Spec{Resource: mustResource(t), Method: "get", SecretRefs: []request.SecretReference{secret}})
	if err != nil {
		t.Fatal(err)
	}

	copySpec := first.Spec()
	copySpec.SecretRefs[0] = "credential/changed"
	if got := first.Spec().SecretRefs[0]; got != secret {
		t.Fatalf("request leaked mutable slice: %q", got)
	}
	if first.Spec().Method != "GET" {
		t.Fatalf("method not normalized: %q", first.Spec().Method)
	}

	rev2, _ := identity.NewRevision(2)
	second, err := first.Revise(rev2, request.Spec{Resource: mustResource(t), Method: "POST"})
	if err != nil {
		t.Fatal(err)
	}
	if first.Revision() != rev1 || second.Revision() != rev2 || first.ID() != second.ID() {
		t.Fatal("revision mutated prior request or changed request identity")
	}
}

func TestRequestRejectsRevisionSkipsAndRawLookingRefs(t *testing.T) {
	id, _ := identity.ParseRequestID("req_00000000000000000000000000000002")
	rev1, _ := identity.NewRevision(1)
	first, _ := request.New(id, rev1, request.Spec{Resource: mustResource(t)})
	rev3, _ := identity.NewRevision(3)
	if _, err := first.Revise(rev3, request.Spec{Resource: mustResource(t)}); !errors.Is(err, request.ErrRevisionStep) {
		t.Fatalf("skip revision err=%v", err)
	}
	if _, err := request.NewSecretReference("Bearer top secret"); !errors.Is(err, request.ErrInvalidRef) {
		t.Fatalf("raw-looking spaced secret should not be accepted as ref: %v", err)
	}
}
