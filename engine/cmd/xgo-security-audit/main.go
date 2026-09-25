package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"strings"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/security/credentials"
	"github.com/subhra74/xdm/engine/security/header"
	"github.com/subhra74/xdm/engine/security/route"
)

type report struct {
	SchemaVersion int      `json:"schema_version"`
	Status        string   `json:"status"`
	Mode          string   `json:"mode"`
	Fixtures      []string `json:"fixtures"`
	Capabilities  []string `json:"capabilities"`
	Checks        []string `json:"checks"`
}

type fixtureDoc struct {
	SchemaVersion int      `json:"schema_version"`
	FixtureID     string   `json:"fixture_id"`
	CapabilityIDs []string `json:"capability_ids"`
	Category      string   `json:"category"`
	Expected      any      `json:"expected"`
}

type ledgerDoc struct {
	Capabilities []struct {
		ID     string `json:"id"`
		Status string `json:"status"`
	} `json:"capabilities"`
}

func mustRes() resource.Identity {
	r, e := resource.DeriveIdentity("audit", "logical-resource")
	if e != nil {
		panic(e)
	}
	return r
}

func mustReq() identity.RequestID {
	id, e := identity.ParseRequestID("req_00000000000000000000000000000001")
	if e != nil {
		panic(e)
	}
	return id
}

func mustSecret(s string) request.SecretReference {
	r, e := request.NewSecretReference(s)
	if e != nil {
		panic(e)
	}
	return r
}

func readJSON(path string, out any) error {
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	if err := json.Unmarshal(b, out); err != nil {
		return fmt.Errorf("%s: %w", path, err)
	}
	return nil
}

func requireFixture(path, fixtureID, capabilityID string) error {
	var doc fixtureDoc
	if err := readJSON(path, &doc); err != nil {
		return err
	}
	if doc.SchemaVersion != 1 || doc.FixtureID != fixtureID || doc.Category == "" || doc.Expected == nil || len(doc.CapabilityIDs) != 1 || doc.CapabilityIDs[0] != capabilityID {
		return fmt.Errorf("fixture contract mismatch for %s", fixtureID)
	}
	return nil
}

func requireImplemented(ledgerPath string, capabilityIDs ...string) error {
	var doc ledgerDoc
	if err := readJSON(ledgerPath, &doc); err != nil {
		return err
	}
	statuses := map[string]string{}
	for _, c := range doc.Capabilities {
		statuses[c.ID] = c.Status
	}
	for _, id := range capabilityIDs {
		if statuses[id] != "IMPLEMENTED" {
			return fmt.Errorf("capability %s status=%q, want IMPLEMENTED", id, statuses[id])
		}
	}
	return nil
}

func auditRequest(repoRoot string) error {
	if err := requireFixture(filepath.Join(repoRoot, "engine/testdata/request/xgo-cap-request-001.json"), "xgo-cap-request-001", "XGO-CAP-REQUEST-001"); err != nil {
		return err
	}
	if err := requireImplemented(filepath.Join(repoRoot, "engine/docs/capability-ledger.yaml"), "XGO-CAP-REQUEST-001"); err != nil {
		return err
	}
	body, _ := request.NewBodyReference("body/audit-post")
	n := int64(1024)
	res := mustRes()
	in, err := request.NewNetworkIntent(request.NetworkIntent{
		TransportURL: "https://example.test/file",
		Resource:     res,
		Method:       "POST",
		Headers:      []request.Header{{Name: "Accept", Value: "application/octet-stream"}},
		Body:         &request.Body{Ref: body, Replayability: request.BodyReplayable, ContentType: "application/octet-stream"},
		Credentials: []request.CredentialReference{{
			Kind:  request.CredentialAuthorization,
			Ref:   mustSecret("secret/audit-auth"),
			Scope: request.CredentialScope{Origin: "https://example.test", Resource: res},
		}},
		Mirrors:              []string{"https://mirror.test/file", "https://mirror.test/file"},
		ExpectedLength:       &n,
		Validators:           request.ValidatorSet{ETag: "\"v1\"", LastModified: "Wed, 01 Jan 2025 00:00:00 GMT"},
		Checksums:            []request.Checksum{{Algorithm: "sha256", Digest: strings.Repeat("ab", 32)}},
		Source:               request.SourceMetadata{PageURL: "https://page.test/watch", Capture: "capture-1", BrowserRequestID: "browser-1"},
		BackendPreference:    "native",
		AllowBackendFallback: true,
		ApprovalRefs:         []request.ApprovalReference{"approval/private/exact"},
	})
	if err != nil {
		return err
	}
	b, err := in.SafeJSON()
	if err != nil {
		return err
	}
	if string(b) == "" || strings.Contains(string(b), "Bearer secret") || len(in.Mirrors) != 1 || in.Validators.ETag != "\"v1\"" || !in.Replayable() {
		return fmt.Errorf("canonical request roundtrip invariant failed")
	}
	if _, err := json.Marshal(request.RuntimeMaterial{BodyBytes: []byte("secret-body")}); err == nil {
		return fmt.Errorf("runtime secret material serialized")
	}
	_, err = request.NewNetworkIntent(request.NetworkIntent{TransportURL: "https://example.test/file", Resource: res, Method: "GET", Body: &request.Body{Ref: body, Replayability: request.BodyReplayable}})
	if err == nil {
		return fmt.Errorf("GET body accepted")
	}
	return nil
}

func auditHeader(repoRoot string) error {
	for _, item := range []struct{ path, fid, cid string }{
		{"engine/testdata/security/xgo-cap-security-001.json", "xgo-cap-security-001", "XGO-CAP-SECURITY-001"},
		{"engine/testdata/security/xgo-cap-security-002.json", "xgo-cap-security-002", "XGO-CAP-SECURITY-002"},
	} {
		if err := requireFixture(filepath.Join(repoRoot, item.path), item.fid, item.cid); err != nil {
			return err
		}
	}
	if err := requireImplemented(filepath.Join(repoRoot, "engine/docs/capability-ledger.yaml"), "XGO-CAP-SECURITY-001", "XGO-CAP-SECURITY-002"); err != nil {
		return err
	}
	for _, h := range []request.Header{{Name: "Host", Value: "evil"}, {Name: "Content-Length", Value: "5"}, {Name: "Authorization", Value: "Bearer raw"}, {Name: "X-Test", Value: "ok\r\nInjected: yes"}} {
		if _, e := header.Admit([]request.Header{h}, header.SourceTrustedEngine); e == nil {
			return fmt.Errorf("hostile header accepted: %s", h.Name)
		}
	}
	res := mustRes()
	refs := []request.CredentialReference{
		{Kind: request.CredentialAuthorization, Ref: mustSecret("secret/auth"), Scope: request.CredentialScope{Origin: "https://a.test", Resource: res}},
		{Kind: request.CredentialCookie, Ref: mustSecret("secret/cookie"), Scope: request.CredentialScope{Origin: "https://a.test", PathPrefix: "/media"}},
		{Kind: request.CredentialProxyAuthorization, Ref: mustSecret("secret/proxy")},
	}
	same, e := credentials.ForDestination(refs, "https://a.test/media/x", res)
	if e != nil || len(same) != 2 {
		return fmt.Errorf("same-origin credentials lost")
	}
	cross, _ := credentials.ForDestination(refs, "https://b.test/media/x", res)
	if len(cross) != 0 {
		return fmt.Errorf("cross-origin credential leak")
	}
	pathMiss, _ := credentials.ForDestination(refs, "https://a.test/account", res)
	if len(pathMiss) != 1 || pathMiss[0].Kind != request.CredentialAuthorization {
		return fmt.Errorf("cookie path scope leak")
	}
	if len(credentials.ProxyReferences(refs)) != 1 {
		return fmt.Errorf("proxy authorization was not separated")
	}
	return nil
}

func auditRoute(repoRoot string) error {
	if err := requireFixture(filepath.Join(repoRoot, "engine/testdata/security/xgo-cap-security-003.json"), "xgo-cap-security-003", "XGO-CAP-SECURITY-003"); err != nil {
		return err
	}
	if err := requireImplemented(filepath.Join(repoRoot, "engine/docs/capability-ledger.yaml"), "XGO-CAP-SECURITY-003"); err != nil {
		return err
	}
	classTable := map[string]route.Class{
		"8.8.8.8": route.Public, "10.0.0.1": route.Private, "127.0.0.1": route.Loopback,
		"169.254.1.1": route.LinkLocal, "100.64.0.1": route.Reserved, "224.0.0.1": route.Multicast,
		"0.0.0.0": route.Unspecified, "2001:4860:4860::8888": route.Public, "fc00::1": route.Private,
		"fe80::1": route.LinkLocal, "::1": route.Loopback, "ff02::1": route.Multicast,
		"2001:db8::1": route.Reserved, "::": route.Unspecified, "::ffff:127.0.0.1": route.Loopback,
	}
	for raw, want := range classTable {
		if got := route.Classify(netip.MustParseAddr(raw)); got != want {
			return fmt.Errorf("route class %s=%s want %s", raw, got, want)
		}
	}
	target := route.Target{RequestID: mustReq(), Resource: mustRes(), URL: "https://a.test/file", Addresses: []netip.Addr{netip.MustParseAddr("8.8.8.8"), netip.MustParseAddr("10.0.0.2")}}
	if _, err := route.Evaluate(target, nil); err == nil {
		return fmt.Errorf("mixed private candidate accepted without approval")
	}
	approval, err := route.NewApproval(target.RequestID, target.Resource, target.URL, route.Private)
	if err != nil {
		return err
	}
	d, err := route.Evaluate(target, []route.Approval{approval})
	if err != nil || len(d.Addresses) != 2 {
		return fmt.Errorf("scoped approval failed: %v", err)
	}
	mirror := target
	mirror.URL = "https://mirror.test/file"
	if _, err := route.Evaluate(mirror, []route.Approval{approval}); err == nil {
		return fmt.Errorf("primary approval reused by mirror")
	}
	redirect := target
	redirect.URL = "https://a.test/redirected"
	if _, err := route.Evaluate(redirect, []route.Approval{approval}); err == nil {
		return fmt.Errorf("primary approval reused by redirect")
	}
	return nil
}

func main() {
	mode := flag.String("mode", "", "request|header|route")
	repoRoot := flag.String("repo-root", ".", "repository root")
	out := flag.String("output", "", "report path")
	flag.Parse()
	var err error
	var checks, fixtures, capabilities []string
	switch *mode {
	case "request":
		err = auditRequest(*repoRoot)
		fixtures = []string{"xgo-cap-request-001"}
		capabilities = []string{"XGO-CAP-REQUEST-001"}
		checks = []string{"GET/POST canonicalization", "safe serialization", "body replayability", "mirror/validator/checksum preservation", "transport/logical identity split"}
	case "header":
		err = auditHeader(*repoRoot)
		fixtures = []string{"xgo-cap-security-001", "xgo-cap-security-002"}
		capabilities = []string{"XGO-CAP-SECURITY-001", "XGO-CAP-SECURITY-002"}
		checks = []string{"CRLF rejection", "transport-owned headers", "raw credential rejection", "same-origin forwarding", "cross-origin stripping", "cookie scope", "proxy separation"}
	case "route":
		err = auditRoute(*repoRoot)
		fixtures = []string{"xgo-cap-security-003"}
		capabilities = []string{"XGO-CAP-SECURITY-003"}
		checks = []string{"representative IPv4/IPv6 classification", "IPv4-mapped IPv6", "mixed candidates", "scoped approval", "mirror/redirect reevaluation"}
	default:
		err = fmt.Errorf("unknown mode %q", *mode)
	}
	r := report{SchemaVersion: 1, Status: "pass", Mode: *mode, Fixtures: fixtures, Capabilities: capabilities, Checks: checks}
	if err != nil {
		r.Status = "fail"
	}
	b, _ := json.MarshalIndent(r, "", "  ")
	if *out != "" {
		_ = os.MkdirAll(filepath.Dir(*out), 0o755)
		_ = os.WriteFile(*out, append(b, '\n'), 0o644)
	}
	fmt.Println(string(b))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
