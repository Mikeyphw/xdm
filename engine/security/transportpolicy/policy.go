package transportpolicy

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strings"

	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/runtime/platform"
	"github.com/subhra74/xdm/engine/security/credentials"
)

var (
	ErrPlatformPolicyRequired = errors.New("platform network policy required")
	ErrCleartextDenied        = errors.New("cleartext transport denied")
	ErrCleartextCredentials   = errors.New("credentials over cleartext require exact approval")
	ErrInvalidProxy           = errors.New("invalid proxy configuration")
	ErrProxyResolution        = errors.New("proxy resolution failed")
)

type CleartextDecision struct {
	Known                    bool   `json:"known"`
	Allowed                  bool   `json:"allowed"`
	Host                     string `json:"host"`
	CredentialApprovalTarget string `json:"credential_approval_target,omitempty"`
}

func HasCredentialBearingQuery(raw string) bool {
	u, e := url.Parse(raw)
	if e != nil {
		return true
	}
	for key, values := range u.Query() {
		k := strings.ToLower(key)
		if strings.Contains(k, "token") || strings.Contains(k, "secret") || strings.Contains(k, "password") || strings.Contains(k, "auth") || strings.HasSuffix(k, "key") || k == "sig" || k == "signature" {
			for _, v := range values {
				if v != "" {
					return true
				}
			}
		}
	}
	return false
}

func CheckCleartext(raw string, hasCredentials bool, decision CleartextDecision) error {
	u, e := url.Parse(raw)
	if e != nil || u.Hostname() == "" {
		return ErrCleartextDenied
	}
	s := strings.ToLower(u.Scheme)
	if s == "https" || s == "ftps" {
		return nil
	}
	if s != "http" && s != "ftp" {
		return ErrCleartextDenied
	}
	host := strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
	if !decision.Known || !strings.EqualFold(strings.TrimSuffix(decision.Host, "."), host) {
		return ErrPlatformPolicyRequired
	}
	if !decision.Allowed {
		return ErrCleartextDenied
	}
	if hasCredentials {
		approved, e := url.Parse(decision.CredentialApprovalTarget)
		if e != nil || approved.Scheme == "" || approved.Host == "" {
			return ErrCleartextCredentials
		}
		approved.Scheme = strings.ToLower(approved.Scheme)
		approved.Host = strings.ToLower(approved.Host)
		u.Scheme = strings.ToLower(u.Scheme)
		u.Host = strings.ToLower(u.Host)
		if approved.String() != u.String() {
			return ErrCleartextCredentials
		}
	}
	return nil
}

type ProxyMode string

const (
	ProxyDirect      ProxyMode = "direct"
	ProxyHTTP        ProxyMode = "http"
	ProxySOCKS       ProxyMode = "socks"
	ProxySystem      ProxyMode = "system"
	ProxyPACResolved ProxyMode = "pac_resolved"
)

type ProxyIntent struct {
	Mode           ProxyMode                           `json:"mode"`
	Endpoint       string                              `json:"endpoint,omitempty"`
	CredentialRefs []domainrequest.CredentialReference `json:"credential_refs,omitempty"`
}
type ProxyDecision struct {
	Mode     ProxyMode `json:"mode"`
	Endpoint string    `json:"endpoint,omitempty"`
	Source   string    `json:"source,omitempty"`
}

func validProxyEndpoint(mode ProxyMode, raw string) bool {
	u, e := url.Parse(raw)
	if e != nil || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return false
	}
	switch mode {
	case ProxyHTTP:
		return u.Scheme == "http" || u.Scheme == "https"
	case ProxySOCKS:
		return u.Scheme == "socks5" || u.Scheme == "socks5h"
	}
	return false
}
func ResolveProxy(intent ProxyIntent, resolved *ProxyDecision) (ProxyDecision, error) {
	for _, r := range intent.CredentialRefs {
		if r.Kind != domainrequest.CredentialProxyAuthorization {
			return ProxyDecision{}, ErrInvalidProxy
		}
	}
	switch intent.Mode {
	case ProxyDirect:
		return ProxyDecision{Mode: ProxyDirect, Source: "engine"}, nil
	case ProxyHTTP, ProxySOCKS:
		if !validProxyEndpoint(intent.Mode, intent.Endpoint) {
			return ProxyDecision{}, ErrInvalidProxy
		}
		return ProxyDecision{Mode: intent.Mode, Endpoint: intent.Endpoint, Source: "engine"}, nil
	case ProxySystem, ProxyPACResolved:
		if resolved == nil {
			return ProxyDecision{}, ErrProxyResolution
		}
		if resolved.Mode == ProxyDirect {
			return *resolved, nil
		}
		if resolved.Mode != ProxyHTTP && resolved.Mode != ProxySOCKS || !validProxyEndpoint(resolved.Mode, resolved.Endpoint) {
			return ProxyDecision{}, ErrProxyResolution
		}
		return *resolved, nil
	default:
		return ProxyDecision{}, ErrInvalidProxy
	}
}

func ProxyCredentialRefs(all []domainrequest.CredentialReference) []domainrequest.CredentialReference {
	return credentials.ProxyReferences(all)
}

type RootStrategy string

const (
	PlatformRoots RootStrategy = "platform_roots"
	BundledRoots  RootStrategy = "bundled_roots"
	CustomRoots   RootStrategy = "custom_roots"
)

type TLSPlan struct {
	ServerName string       `json:"server_name"`
	MinVersion uint16       `json:"min_version"`
	Roots      RootStrategy `json:"roots"`
}

func PlanTLS(raw string, roots RootStrategy) (TLSPlan, error) {
	u, e := url.Parse(raw)
	if e != nil || (strings.ToLower(u.Scheme) != "https" && strings.ToLower(u.Scheme) != "ftps") || u.Hostname() == "" {
		return TLSPlan{}, fmt.Errorf("invalid tls target")
	}
	if roots != PlatformRoots && roots != BundledRoots && roots != CustomRoots {
		return TLSPlan{}, fmt.Errorf("invalid root strategy")
	}
	return TLSPlan{ServerName: strings.ToLower(strings.TrimSuffix(u.Hostname(), ".")), MinVersion: tls.VersionTLS12, Roots: roots}, nil
}

type TLSFailure string

const (
	TLSHostnameMismatch   TLSFailure = "hostname_mismatch"
	TLSCertificateInvalid TLSFailure = "certificate_invalid"
	TLSHandshakeFailure   TLSFailure = "handshake_failure"
)

func MapTLSError(err error) TLSFailure {
	if err == nil {
		return ""
	}
	var host x509.HostnameError
	if errors.As(err, &host) {
		return TLSHostnameMismatch
	}
	var unknown x509.UnknownAuthorityError
	if errors.As(err, &unknown) {
		return TLSCertificateInvalid
	}
	var invalid x509.CertificateInvalidError
	if errors.As(err, &invalid) {
		return TLSCertificateInvalid
	}
	return TLSHandshakeFailure
}

func RequestCleartextPolicy(ctx context.Context, b *platform.Broker, host string) (CleartextDecision, error) {
	payload, _ := json.Marshal(map[string]any{"operation": "cleartext_allowed", "host": host})
	reply, e := b.Request(ctx, platform.NetworkPolicy, platform.Refs{}, payload)
	if e != nil {
		return CleartextDecision{}, e
	}
	if !reply.OK {
		return CleartextDecision{}, ErrCleartextDenied
	}
	var out CleartextDecision
	if e := json.Unmarshal(reply.Payload, &out); e != nil {
		return CleartextDecision{}, e
	}
	if !out.Known {
		return CleartextDecision{}, ErrPlatformPolicyRequired
	}
	return out, nil
}
func RequestSystemProxy(ctx context.Context, b *platform.Broker, target string, mode ProxyMode) (ProxyDecision, error) {
	payload, _ := json.Marshal(map[string]any{"operation": "resolve_proxy", "target": target, "mode": mode})
	reply, e := b.Request(ctx, platform.SystemProxy, platform.Refs{}, payload)
	if e != nil {
		return ProxyDecision{}, e
	}
	if !reply.OK {
		return ProxyDecision{}, ErrProxyResolution
	}
	var out ProxyDecision
	if e := json.Unmarshal(reply.Payload, &out); e != nil {
		return ProxyDecision{}, e
	}
	return out, nil
}
