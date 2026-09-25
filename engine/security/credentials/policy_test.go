package credentials_test

import (
	"github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/security/credentials"
	"testing"
)

func res(t *testing.T) resource.Identity {
	r, e := resource.DeriveIdentity("url", "logical")
	if e != nil {
		t.Fatal(e)
	}
	return r
}
func ref(t *testing.T, s string) request.SecretReference {
	r, e := request.NewSecretReference(s)
	if e != nil {
		t.Fatal(e)
	}
	return r
}
func TestCredentialScopingAndCrossOriginStripping(t *testing.T) {
	r := res(t)
	refs := []request.CredentialReference{{Kind: request.CredentialAuthorization, Ref: ref(t, "secret/a"), Scope: request.CredentialScope{Origin: "https://a.test", Resource: r}}, {Kind: request.CredentialCookie, Ref: ref(t, "secret/c"), Scope: request.CredentialScope{Origin: "https://a.test", PathPrefix: "/media"}}, {Kind: request.CredentialProxyAuthorization, Ref: ref(t, "secret/p")}}
	same, _ := credentials.ForDestination(refs, "https://a.test/media/x", r)
	if len(same) != 2 {
		t.Fatalf("same=%+v", same)
	}
	cross, _ := credentials.ForDestination(refs, "https://b.test/media/x", r)
	if len(cross) != 0 {
		t.Fatalf("cross leaked=%+v", cross)
	}
	pathMiss, _ := credentials.ForDestination(refs, "https://a.test/other", r)
	if len(pathMiss) != 1 || pathMiss[0].Kind != request.CredentialAuthorization {
		t.Fatalf("path leak=%+v", pathMiss)
	}
	if len(credentials.ProxyReferences(refs)) != 1 {
		t.Fatal("proxy separation")
	}
}
func TestOriginNormalizationTreatsDefaultPortsAsSame(t *testing.T) {
	if !credentials.SameOrigin("https://A.TEST:443/a", "https://a.test/b") {
		t.Fatal("default port origin mismatch")
	}
	if credentials.SameOrigin("https://a.test", "http://a.test") {
		t.Fatal("scheme origin mismatch")
	}
}
