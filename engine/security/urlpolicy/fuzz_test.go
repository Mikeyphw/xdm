package urlpolicy

import "testing"

func FuzzParseTransportURL(f *testing.F) {
	for _, seed := range []string{
		"https://example.test/file.bin",
		"HTTP://Example.Test:80/path?q=1",
		"HTTP://Exa:mple.Test:80/path?q=1", // XGO-85 regression: malformed colon host must be rejected.
		"https://[2001:db8::1]/asset",
		"ftp://mirror.example.test/pub/file",
		"https://user:pass@example.test/secret",
		"javascript:alert(1)",
	} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, raw string) {
		if len(raw) > 16*1024 {
			raw = raw[:16*1024]
		}
		parsed, err := ParseTransport(raw)
		if err != nil {
			return
		}
		if parsed.IsZero() || parsed.Origin().Host == "" || parsed.Origin().Scheme == "" {
			t.Fatalf("successful parse produced zero transport: %#v", parsed)
		}
		reparsed, err := ParseTransport(parsed.String())
		if err != nil {
			t.Fatalf("canonical transport did not reparse: %q: %v", parsed.String(), err)
		}
		if reparsed.String() != parsed.String() || reparsed.Origin() != parsed.Origin() {
			t.Fatalf("canonicalization not idempotent: first=%q second=%q", parsed.String(), reparsed.String())
		}
	})
}
