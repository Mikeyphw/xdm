package urlpolicy

import "testing"

func TestParseTransportCanonicalizesOriginAndKeepsLogicalSeparation(t *testing.T) {
	got, err := ParseTransport("HTTPS://Example.COM:443/a/../video.bin?token=opaque")
	if err != nil {
		t.Fatal(err)
	}
	if got.String() != "https://example.com/a/../video.bin?token=opaque" {
		t.Fatalf("canonical=%q", got.String())
	}
	if got.Origin().String() != "https://example.com" {
		t.Fatalf("origin=%q", got.Origin().String())
	}
}

func TestParseTransportRejectsUserInfoFragmentAndUnsupportedScheme(t *testing.T) {
	for _, raw := range []string{
		"https://u:p@example.com/file",
		"https://example.com/file#fragment",
		"file:///tmp/x",
		"javascript:alert(1)",
	} {
		if _, err := ParseTransport(raw); err == nil {
			t.Fatalf("expected rejection: %s", raw)
		}
	}
}
