package request

import (
	"encoding/json"
	"errors"
	"github.com/subhra74/xdm/engine/domain/resource"
	"strings"
	"testing"
)

func rid(t *testing.T, s string) resource.Identity {
	t.Helper()
	r, e := resource.DeriveIdentity("network", s)
	if e != nil {
		t.Fatal(e)
	}
	return r
}
func bref(t *testing.T, s string) BodyReference {
	t.Helper()
	r, e := NewBodyReference(s)
	if e != nil {
		t.Fatal(e)
	}
	return r
}
func sref(t *testing.T, s string) SecretReference {
	t.Helper()
	r, e := NewSecretReference(s)
	if e != nil {
		t.Fatal(e)
	}
	return r
}
func TestNetworkIntentGETRoundTripPreservesSupersetFields(t *testing.T) {
	length := int64(42)
	in := NetworkIntent{TransportURL: "HTTPS://Origin.Example:443/file?sig=opaque", Resource: rid(t, "logical"), Method: "get", Headers: []Header{{Name: "Accept", Value: "application/octet-stream"}}, Credentials: []CredentialReference{{Kind: CredentialAuthorization, Ref: sref(t, "secret/auth"), Scope: CredentialScope{Origin: "https://origin.example", Resource: rid(t, "logical")}}}, Mirrors: []string{"https://mirror.example/file", "https://mirror.example/file"}, ExpectedLength: &length, Validators: ValidatorSet{ETag: `"abc"`}, Checksums: []Checksum{{Algorithm: "SHA256", Digest: strings.Repeat("ab", 32)}}, Source: SourceMetadata{PageURL: "https://page.example/watch", Capture: "cap-1", FrameOrigin: "https://page.example", BrowserRequestID: "req-1"}, BackendPreference: "native", ApprovalRefs: []ApprovalReference{"approval/private/exact"}}
	n, e := NewNetworkIntent(in)
	if e != nil {
		t.Fatal(e)
	}
	if n.TransportURL != "https://origin.example/file?sig=opaque" || len(n.Mirrors) != 1 || n.Checksums[0].Algorithm != "sha256" {
		t.Fatalf("normalized=%+v", n)
	}
	data, e := n.SafeJSON()
	if e != nil {
		t.Fatal(e)
	}
	var round NetworkIntent
	if e = json.Unmarshal(data, &round); e != nil {
		t.Fatal(e)
	}
	if _, e = NewNetworkIntent(round); e != nil {
		t.Fatal(e)
	}
}
func TestNetworkIntentPOSTBodyReferenceAndRuntimeBytesNeverSerialize(t *testing.T) {
	b := bref(t, "body/export/1")
	n, e := NewNetworkIntent(NetworkIntent{TransportURL: "https://api.example/export", Resource: rid(t, "post"), Method: "POST", Body: &Body{Ref: b, Replayability: BodyReplayable, ContentType: "application/json"}})
	if e != nil {
		t.Fatal(e)
	}
	data, _ := n.SafeJSON()
	if strings.Contains(string(data), "super-secret") {
		t.Fatal("safe form leaked secret")
	}
	if _, e := json.Marshal(RuntimeMaterial{BodyBytes: []byte("super-secret"), SecretValues: map[SecretReference][]byte{sref(t, "secret/x"): []byte("secret")}}); !errors.Is(e, ErrRuntimeMaterialSerialization) {
		t.Fatalf("runtime marshal=%v", e)
	}
}
func TestNetworkIntentTypedMethodBodyFailures(t *testing.T) {
	tests := []struct {
		in     NetworkIntent
		reason IntentFailureReason
	}{{NetworkIntent{TransportURL: "https://x.test/a", Resource: rid(t, "g"), Method: "GET", Body: &Body{Ref: bref(t, "body/a"), Replayability: BodyReplayable}}, IntentBodyNotAllowed}, {NetworkIntent{TransportURL: "https://x.test/a", Resource: rid(t, "p"), Method: "POST"}, IntentBodyRequired}, {NetworkIntent{TransportURL: "https://x.test/a", Resource: rid(t, "x"), Method: "PATCH"}, IntentUnsupportedMethod}}
	for _, tc := range tests {
		_, e := NewNetworkIntent(tc.in)
		var v *IntentValidationError
		if !errors.As(e, &v) || v.Reason != tc.reason {
			t.Fatalf("err=%v reason=%v", e, v)
		}
	}
}
func TestLogicalResourceSurvivesTransportRotation(t *testing.T) {
	r := rid(t, "logical")
	a, _ := NewNetworkIntent(NetworkIntent{TransportURL: "https://a.example/x", Resource: r, Method: "GET"})
	b, _ := NewNetworkIntent(NetworkIntent{TransportURL: "https://b.example/y", Resource: r, Method: "GET"})
	if a.Resource != b.Resource || a.TransportURL == b.TransportURL {
		t.Fatal("logical and transport identity collapsed")
	}
}
