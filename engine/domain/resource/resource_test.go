package resource_test

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	"github.com/subhra74/xdm/engine/domain/resource"
)

func TestResourceIdentityIsStableOpaqueAndRoundTrips(t *testing.T) {
	raw := "https://example.test/file?token=super-secret"
	first, err := resource.DeriveIdentity("url", raw)
	if err != nil {
		t.Fatal(err)
	}
	second, err := resource.DeriveIdentity("url", raw)
	if err != nil {
		t.Fatal(err)
	}
	if first != second {
		t.Fatal("same canonical material produced different identities")
	}
	if strings.Contains(first.String(), "secret") || strings.Contains(first.String(), "example.test") {
		t.Fatalf("resource identity leaked source material: %s", first)
	}
	payload, err := json.Marshal(first)
	if err != nil {
		t.Fatal(err)
	}
	var restored resource.Identity
	if err := json.Unmarshal(payload, &restored); err != nil {
		t.Fatal(err)
	}
	if restored != first {
		t.Fatalf("restored=%s want=%s", restored, first)
	}
}

func TestSensitiveTextRedactsFormattingAndJSON(t *testing.T) {
	secret := resource.NewSensitiveText("Bearer top-secret-value")
	rendered := fmt.Sprintf("%s|%v|%#v", secret, secret, secret)
	if strings.Contains(rendered, "top-secret-value") {
		t.Fatalf("format leaked secret: %s", rendered)
	}
	payload, err := json.Marshal(secret)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(payload), "top-secret-value") {
		t.Fatalf("json leaked secret: %s", payload)
	}
	if secret.Reveal() != "Bearer top-secret-value" {
		t.Fatal("explicit reveal lost original value")
	}
}

func FuzzParseIdentity(f *testing.F) {
	valid, _ := resource.DeriveIdentity("url", "https://example.test/a")
	f.Add(valid.String())
	f.Add("")
	f.Fuzz(func(t *testing.T, value string) {
		parsed, err := resource.ParseIdentity(value)
		if err != nil {
			return
		}
		if parsed.IsZero() || parsed.String() != value {
			t.Fatalf("inconsistent parse: %q", parsed)
		}
	})
}
