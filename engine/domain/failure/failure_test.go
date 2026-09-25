package failure_test

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"

	"github.com/subhra74/xdm/engine/domain/failure"
)

func TestEveryCategoryHasExplicitPolicyAndSafeMessage(t *testing.T) {
	for _, category := range failure.Categories() {
		policy, ok := failure.DefaultPolicy(category)
		if !ok {
			t.Fatalf("missing policy for %s", category)
		}
		f, err := failure.New(category, policy.Retry, policy.UserAction, errors.New("Bearer TOP-SECRET token=abc"))
		if err != nil {
			t.Fatal(err)
		}
		rendered := fmt.Sprintf("%s %#v", f, f)
		if strings.Contains(rendered, "TOP-SECRET") || strings.Contains(rendered, "token=abc") {
			t.Fatalf("public formatting leaked cause: %s", rendered)
		}
		if !errors.Is(f, errors.Unwrap(f)) {
			t.Fatalf("cause is not preserved for %s", category)
		}
	}
}

func TestFailureJSONRoundTripExcludesCause(t *testing.T) {
	sentinel := errors.New("Cookie: private-cookie")
	first, _ := failure.NewDefault(failure.NetworkUnavailable, sentinel)
	payload, err := json.Marshal(first)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(payload), "private-cookie") || strings.Contains(string(payload), "Cookie") {
		t.Fatalf("serialized failure leaked cause: %s", payload)
	}
	var restored failure.Failure
	if err := json.Unmarshal(payload, &restored); err != nil {
		t.Fatal(err)
	}
	if restored.Category != first.Category || restored.Retry != first.Retry || restored.UserAction != first.UserAction {
		t.Fatalf("round trip changed metadata: %#v != %#v", restored, first)
	}
	if errors.Unwrap(restored) != nil {
		t.Fatal("deserialized failure unexpectedly restored process-local cause")
	}
}

func TestDonorMappingsAreUniqueAndExplicit(t *testing.T) {
	seen := map[string]bool{}
	for _, m := range failure.DonorMappings() {
		key := m.Donor + ":" + m.MatchKey
		if seen[key] {
			t.Fatalf("duplicate mapping %s", key)
		}
		seen[key] = true
		f, err := failure.New(m.Category, m.Retry, m.UserAction, nil)
		if err != nil {
			t.Fatalf("mapping %s invalid: %v", key, err)
		}
		if f.Retry == "" || f.UserAction == "" {
			t.Fatalf("mapping %s lacks explicit policy", key)
		}
	}
}
