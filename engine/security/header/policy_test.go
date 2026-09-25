package header

import (
	"errors"
	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"testing"
)

func TestAdmitRejectsTransportOwnedCRLFAndSensitiveValues(t *testing.T) {
	cases := []struct {
		h    domainrequest.Header
		want error
	}{{domainrequest.Header{Name: "Host", Value: "evil"}, ErrTransportOwned}, {domainrequest.Header{Name: "Content-Length", Value: "5"}, ErrTransportOwned}, {domainrequest.Header{Name: "X-Test", Value: "ok\r\nAuthorization: x"}, ErrHeaderInjection}, {domainrequest.Header{Name: "Authorization", Value: "Bearer secret"}, ErrSensitiveValue}}
	for _, tc := range cases {
		if _, e := Admit([]domainrequest.Header{tc.h}, SourceTrustedEngine); !errors.Is(e, tc.want) {
			t.Fatalf("got %v want %v", e, tc.want)
		}
	}
}
func TestTrustedCustomHeaderAllowedExternalRestricted(t *testing.T) {
	if _, e := Admit([]domainrequest.Header{{Name: "X-Custom-Metadata", Value: "ok"}}, SourceTrustedEngine); e != nil {
		t.Fatal(e)
	}
	if _, e := Admit([]domainrequest.Header{{Name: "X-Custom-Metadata", Value: "ok"}}, SourceExternalHandoff); e == nil {
		t.Fatal("external custom header accepted")
	}
	if _, e := Admit([]domainrequest.Header{{Name: "Sec-Fetch-Site", Value: "same-origin"}}, SourceExternalHandoff); e != nil {
		t.Fatal(e)
	}
}
func TestCanonicalizationPreservesValueSemantics(t *testing.T) {
	h, e := Admit([]domainrequest.Header{{Name: "x-test", Value: "  A,B ; q=1  "}}, SourceTrustedEngine)
	if e != nil {
		t.Fatal(e)
	}
	if got := h.Get("X-Test"); got != "  A,B ; q=1  " {
		t.Fatalf("value=%q", got)
	}
}
func TestDiagnosticSafeRedactsCredentialReferences(t *testing.T) {
	r, _ := domainrequest.NewSecretReference("secret/auth")
	got := DiagnosticSafe(map[string][]string{"User-Agent": {"XGO"}}, []domainrequest.CredentialReference{{Kind: domainrequest.CredentialAuthorization, Ref: r}})
	if got["Authorization"] != "<redacted-ref>" || got["User-Agent"] != "XGO" {
		t.Fatalf("diagnostic=%v", got)
	}
}
