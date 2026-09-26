package androidhost

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"sort"
	"strings"
	"time"

	"github.com/subhra74/xdm/engine/ops"
	"github.com/subhra74/xdm/engine/scheduler"
)

var (
	ErrCleartextDenied      = errors.New("android cleartext policy denied")
	ErrSecretNotFound       = errors.New("android secret not found")
	ErrInvalidNetworkPolicy = errors.New("invalid android network policy")
	ErrInvalidProxy         = errors.New("invalid android proxy configuration")
)

type CleartextDecision struct {
	Host        string `json:"host"`
	Allowed     bool   `json:"allowed"`
	Source      string `json:"source"`
	Explanation string `json:"explanation"`
}

type AndroidNetworkSecurityPolicy struct {
	GlobalCleartextAllowed bool            `json:"global_cleartext_allowed"`
	HostCleartextAllowed   map[string]bool `json:"host_cleartext_allowed,omitempty"`
	CertificateStore       string          `json:"certificate_store"`
	TrustUserCAs           bool            `json:"trust_user_cas"`
	PinnedTLSRequired      bool            `json:"pinned_tls_required"`
}

func (p AndroidNetworkSecurityPolicy) Validate() error {
	for host := range p.HostCleartextAllowed {
		if strings.TrimSpace(host) == "" || strings.Contains(host, "://") {
			return fmt.Errorf("%w: cleartext host %q", ErrInvalidNetworkPolicy, host)
		}
	}
	switch p.CertificateStore {
	case "", "android_network_security_config", "platform_default", "pinned_only":
		return nil
	default:
		return fmt.Errorf("%w: certificate store %q", ErrInvalidNetworkPolicy, p.CertificateStore)
	}
}

func (p AndroidNetworkSecurityPolicy) Cleartext(host string) (CleartextDecision, error) {
	if err := p.Validate(); err != nil {
		return CleartextDecision{}, err
	}
	host = canonicalHost(host)
	if host == "" {
		return CleartextDecision{}, fmt.Errorf("%w: empty host", ErrInvalidNetworkPolicy)
	}
	if allowed, ok := p.HostCleartextAllowed[host]; ok {
		decision := CleartextDecision{Host: host, Allowed: allowed, Source: "host_override"}
		if allowed {
			decision.Explanation = "Android network-security-config host override allows cleartext for this host."
			return decision, nil
		}
		decision.Explanation = "Android network-security-config host override denies cleartext for this host."
		return decision, ErrCleartextDenied
	}
	decision := CleartextDecision{Host: host, Allowed: p.GlobalCleartextAllowed, Source: "global_policy"}
	if p.GlobalCleartextAllowed {
		decision.Explanation = "Android global cleartext policy allows the request."
		return decision, nil
	}
	decision.Explanation = "Android global cleartext policy denies the request."
	return decision, ErrCleartextDenied
}

type TLSIntegrationPlan struct {
	CertificateStore  string   `json:"certificate_store"`
	TrustUserCAs      bool     `json:"trust_user_cas"`
	PinnedTLSRequired bool     `json:"pinned_tls_required"`
	TestRequirements  []string `json:"test_requirements"`
}

func (p AndroidNetworkSecurityPolicy) TLSPlan() (TLSIntegrationPlan, error) {
	if err := p.Validate(); err != nil {
		return TLSIntegrationPlan{}, err
	}
	store := p.CertificateStore
	if store == "" {
		store = "android_network_security_config"
	}
	return TLSIntegrationPlan{CertificateStore: store, TrustUserCAs: p.TrustUserCAs, PinnedTLSRequired: p.PinnedTLSRequired, TestRequirements: []string{"device/emulator HTTPS test server", "certificate failure maps to typed engine transport error", "no certificate material in logcat/support snapshot"}}, nil
}

type AndroidSecretProvider interface {
	ResolveAndroidSecret(context.Context, ops.SecretRef) (string, error)
}

type AndroidSecureSecretStore struct {
	values map[string]string
}

func NewAndroidSecureSecretStore(values map[ops.SecretRef]string) *AndroidSecureSecretStore {
	store := &AndroidSecureSecretStore{values: map[string]string{}}
	for ref, value := range values {
		store.values[secretKey(ref)] = value
	}
	return store
}

func (s *AndroidSecureSecretStore) ResolveAndroidSecret(ctx context.Context, ref ops.SecretRef) (string, error) {
	select {
	case <-ctx.Done():
		return "", ctx.Err()
	default:
	}
	if s == nil {
		return "", ErrSecretNotFound
	}
	value, ok := s.values[secretKey(ref)]
	if !ok || value == "" {
		return "", ErrSecretNotFound
	}
	return value, nil
}

type AndroidSecretResolution struct {
	Ref      ops.SecretRef `json:"ref"`
	Resolved bool          `json:"resolved"`
	SafeLog  string        `json:"safe_log"`
}

func ResolveAndroidSecret(ctx context.Context, provider AndroidSecretProvider, ref ops.SecretRef) (AndroidSecretResolution, string, error) {
	if provider == nil {
		return AndroidSecretResolution{}, "", ErrSecretNotFound
	}
	value, err := provider.ResolveAndroidSecret(ctx, ref)
	if err != nil {
		return AndroidSecretResolution{Ref: ref, Resolved: false, SafeLog: "secret_ref:" + ref.ID}, "", err
	}
	return AndroidSecretResolution{Ref: ref, Resolved: true, SafeLog: "secret_ref:" + ref.ID}, value, nil
}

type AndroidRuntimeConditions struct {
	ObservedAt       time.Time             `json:"observed_at"`
	Online           bool                  `json:"online"`
	Metered          bool                  `json:"metered"`
	WiFi             bool                  `json:"wifi"`
	Charging         bool                  `json:"charging"`
	BatteryPercent   int                   `json:"battery_percent"`
	StorageFreeBytes int64                 `json:"storage_free_bytes"`
	PowerSource      scheduler.PowerSource `json:"power_source"`
}

func (c AndroidRuntimeConditions) ToSchedulerRuntime() (scheduler.RuntimeSnapshot, error) {
	observed := c.ObservedAt
	if observed.IsZero() {
		observed = time.Now().UTC()
	}
	snap := scheduler.RuntimeSnapshot{ObservedAtUnixMS: observed.UnixMilli(), Online: c.Online, Metered: c.Metered, WiFi: c.WiFi, Charging: c.Charging, BatteryPercent: c.BatteryPercent, StorageFreeBytes: c.StorageFreeBytes, PowerSource: c.PowerSource}
	_, err := scheduler.EvaluateConditions(scheduler.ConditionPolicy{}, snap)
	if err != nil {
		return scheduler.RuntimeSnapshot{}, err
	}
	return snap, nil
}

type AndroidProxyMode string

const (
	ProxyNone   AndroidProxyMode = "none"
	ProxyManual AndroidProxyMode = "manual"
	ProxyPAC    AndroidProxyMode = "pac"
)

type AndroidProxyConfig struct {
	Mode    AndroidProxyMode `json:"mode"`
	Host    string           `json:"host,omitempty"`
	Port    int              `json:"port,omitempty"`
	PACURL  string           `json:"pac_url,omitempty"`
	NoProxy []string         `json:"no_proxy,omitempty"`
}

type AndroidProxyDecision struct {
	Mode       AndroidProxyMode `json:"mode"`
	ProxyURL   string           `json:"proxy_url,omitempty"`
	PACURL     string           `json:"pac_url,omitempty"`
	BypassList []string         `json:"bypass_list,omitempty"`
}

func (c AndroidProxyConfig) Decision() (AndroidProxyDecision, error) {
	switch c.Mode {
	case "", ProxyNone:
		return AndroidProxyDecision{Mode: ProxyNone}, nil
	case ProxyManual:
		host := strings.TrimSpace(c.Host)
		if host == "" || c.Port <= 0 || c.Port > 65535 {
			return AndroidProxyDecision{}, ErrInvalidProxy
		}
		bypass := append([]string(nil), c.NoProxy...)
		sort.Strings(bypass)
		return AndroidProxyDecision{Mode: ProxyManual, ProxyURL: fmt.Sprintf("http://%s:%d", host, c.Port), BypassList: bypass}, nil
	case ProxyPAC:
		if strings.TrimSpace(c.PACURL) == "" {
			return AndroidProxyDecision{}, ErrInvalidProxy
		}
		if _, err := url.ParseRequestURI(c.PACURL); err != nil {
			return AndroidProxyDecision{}, ErrInvalidProxy
		}
		return AndroidProxyDecision{Mode: ProxyPAC, PACURL: c.PACURL}, nil
	default:
		return AndroidProxyDecision{}, ErrInvalidProxy
	}
}

func canonicalHost(host string) string {
	host = strings.TrimSpace(strings.ToLower(host))
	if strings.Contains(host, "://") {
		if u, err := url.Parse(host); err == nil {
			host = u.Hostname()
		}
	}
	return strings.TrimSpace(host)
}

func secretKey(ref ops.SecretRef) string {
	return ref.ID + "|" + ref.Scope + fmt.Sprintf("|%d", ref.Generation)
}
