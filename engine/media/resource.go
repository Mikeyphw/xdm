package media

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"sort"
	"strings"
)

const (
	DefaultMaxMediaGraphItems      = 128
	DefaultMaxMediaGraphSources    = 512
	DefaultMaxMediaGraphVariants   = 512
	DefaultMaxMediaGraphTracks     = 512
	DefaultMaxMediaGraphRenditions = 512
	DefaultMaxMediaGraphFragments  = 1024
)

var (
	ErrInvalidMediaResource = errors.New("invalid media resource")
	ErrCredentialDenied     = errors.New("credential forwarding denied")
	ErrMediaGraphLimit      = errors.New("media graph growth bound exceeded")
)

type SignedURLIdentityPolicy string

const (
	SignedURLTokensAreTransportOnly SignedURLIdentityPolicy = "transport_only"
	SignedURLTokensAreIdentity      SignedURLIdentityPolicy = "identity_relevant"
)

type ResourceKind string

const (
	ResourceKindUnknown  ResourceKind = "unknown"
	ResourceKindManifest ResourceKind = "manifest"
	ResourceKindSegment  ResourceKind = "segment"
	ResourceKindKey      ResourceKind = "key"
	ResourceKindMedia    ResourceKind = "media"
)

type ResourceIdentityPolicy struct {
	SignedURLPolicy   SignedURLIdentityPolicy
	IgnoreQueryKeys   []string
	IdentityQueryKeys []string
}

type MediaResourceInput struct {
	TransportURL      string
	Origin            string
	CredentialScope   CredentialScope
	SignedURLPolicy   SignedURLIdentityPolicy
	IgnoreQueryKeys   []string
	IdentityQueryKeys []string
}

type MediaResource struct {
	TransportURL      string
	CanonicalURL      string
	ResourceID        string
	Origin            string
	CredentialScope   CredentialScope
	SignedURLPolicy   SignedURLIdentityPolicy
	IgnoredQueryKeys  []string
	IdentityQueryKeys []string
}

type ChildCredentialPolicy struct {
	Parent                   MediaResource
	ChildURL                 string
	ChildKind                ResourceKind
	AllowCrossOriginManifest bool
	AllowCrossOriginSegments bool
	AllowCrossOriginKeys     bool
	AllowedOrigins           []string
}

type CredentialForwardDecision struct {
	Forward       bool
	Reason        string
	ChildOrigin   string
	ChildURL      string
	CredentialRef string
}

func NewMediaResource(input MediaResourceInput) (MediaResource, error) {
	normalizedTransport, err := normalizeHTTPURL(input.TransportURL, "media.transport_url")
	if err != nil {
		return MediaResource{}, fmt.Errorf("%w: %v", ErrInvalidMediaResource, err)
	}
	u, _ := url.Parse(normalizedTransport)
	origin := strings.TrimSpace(input.Origin)
	if origin == "" {
		origin = u.Scheme + "://" + u.Host
	}
	originNorm, err := normalizeOrigin(origin, "media.origin")
	if err != nil {
		return MediaResource{}, err
	}
	scope, err := normalizeCredentialScope(input.CredentialScope)
	if err != nil {
		return MediaResource{}, fmt.Errorf("%w: credential_scope", ErrInvalidMediaResource)
	}
	if scope.Origin == "" && scope.Ref != "" {
		scope.Origin = originNorm
	}
	pol := ResourceIdentityPolicy{SignedURLPolicy: input.SignedURLPolicy, IgnoreQueryKeys: input.IgnoreQueryKeys, IdentityQueryKeys: input.IdentityQueryKeys}
	canonical, ignored, identity, err := canonicalResourceURL(normalizedTransport, pol)
	if err != nil {
		return MediaResource{}, err
	}
	return MediaResource{
		TransportURL:      normalizedTransport,
		CanonicalURL:      canonical,
		ResourceID:        digestID("mediares", canonical),
		Origin:            originNorm,
		CredentialScope:   scope,
		SignedURLPolicy:   normalizeSignedPolicy(input.SignedURLPolicy),
		IgnoredQueryKeys:  ignored,
		IdentityQueryKeys: identity,
	}, nil
}

func canonicalResourceURL(raw string, policy ResourceIdentityPolicy) (string, []string, []string, error) {
	normalized, err := normalizeHTTPURL(raw, "media.resource_url")
	if err != nil {
		return "", nil, nil, fmt.Errorf("%w: url", ErrInvalidMediaResource)
	}
	u, _ := url.Parse(normalized)
	q := u.Query()
	signedPolicy := normalizeSignedPolicy(policy.SignedURLPolicy)
	ignore := map[string]bool{}
	if signedPolicy == SignedURLTokensAreTransportOnly {
		for _, k := range []string{"sig", "signature", "token", "access_token", "auth", "expires", "expiry", "x-amz-signature", "x-amz-credential", "x-amz-date", "x-amz-expires", "x-amz-security-token"} {
			ignore[strings.ToLower(k)] = true
		}
	}
	for _, k := range policy.IgnoreQueryKeys {
		k = strings.ToLower(strings.TrimSpace(k))
		if k != "" {
			ignore[k] = true
		}
	}
	explicitIdentity := map[string]bool{}
	for _, k := range policy.IdentityQueryKeys {
		k = strings.ToLower(strings.TrimSpace(k))
		if k != "" {
			explicitIdentity[k] = true
			delete(ignore, k)
		}
	}
	kept := url.Values{}
	ignored := []string{}
	identity := []string{}
	for key, values := range q {
		lkey := strings.ToLower(key)
		if ignore[lkey] {
			ignored = append(ignored, key)
			continue
		}
		vals := append([]string(nil), values...)
		sort.Strings(vals)
		for _, v := range vals {
			kept.Add(key, v)
		}
		identity = append(identity, key)
	}
	sort.Strings(ignored)
	sort.Strings(identity)
	u.RawQuery = kept.Encode()
	return u.String(), ignored, identity, nil
}

func EvaluateCredentialForwarding(policy ChildCredentialPolicy) (CredentialForwardDecision, error) {
	childURL, err := normalizeHTTPURL(policy.ChildURL, "media.child_url")
	if err != nil {
		return CredentialForwardDecision{}, fmt.Errorf("%w: child_url", ErrInvalidMediaResource)
	}
	child, _ := url.Parse(childURL)
	childOrigin := child.Scheme + "://" + child.Host
	scope := policy.Parent.CredentialScope
	if scope.Ref == "" {
		return CredentialForwardDecision{Forward: false, Reason: "no_credential_ref", ChildOrigin: childOrigin, ChildURL: childURL}, nil
	}
	if scope.Origin != "" && !sameOrigin(scope.Origin, childOrigin) {
		if !crossOriginAllowed(policy, childOrigin) {
			return CredentialForwardDecision{Forward: false, Reason: "origin_denied", ChildOrigin: childOrigin, ChildURL: childURL}, nil
		}
	}
	if scope.PathPrefix != "" && sameOrigin(scope.Origin, childOrigin) && !strings.HasPrefix(child.EscapedPath(), scope.PathPrefix) && !strings.HasPrefix(child.Path, scope.PathPrefix) {
		return CredentialForwardDecision{Forward: false, Reason: "path_denied", ChildOrigin: childOrigin, ChildURL: childURL}, nil
	}
	return CredentialForwardDecision{Forward: true, Reason: "policy_allowed", ChildOrigin: childOrigin, ChildURL: childURL, CredentialRef: scope.Ref}, nil
}

func crossOriginAllowed(policy ChildCredentialPolicy, childOrigin string) bool {
	allowedByKind := false
	switch policy.ChildKind {
	case ResourceKindManifest:
		allowedByKind = policy.AllowCrossOriginManifest
	case ResourceKindSegment, ResourceKindMedia:
		allowedByKind = policy.AllowCrossOriginSegments
	case ResourceKindKey:
		allowedByKind = policy.AllowCrossOriginKeys
	}
	if !allowedByKind {
		return false
	}
	for _, origin := range policy.AllowedOrigins {
		norm, err := normalizeOrigin(origin, "media.allowed_origin")
		if err == nil && sameOrigin(norm, childOrigin) {
			return true
		}
	}
	return false
}

func normalizeOrigin(raw, field string) (string, error) {
	normalized, err := normalizeHTTPURL(raw, field)
	if err != nil {
		return "", fmt.Errorf("%w: %s", ErrInvalidMediaResource, field)
	}
	u, _ := url.Parse(normalized)
	return u.Scheme + "://" + u.Host, nil
}

func sameOrigin(a, b string) bool {
	an, errA := normalizeOrigin(a, "origin_a")
	bn, errB := normalizeOrigin(b, "origin_b")
	return errA == nil && errB == nil && an == bn
}

func normalizeSignedPolicy(policy SignedURLIdentityPolicy) SignedURLIdentityPolicy {
	switch policy {
	case "", SignedURLTokensAreTransportOnly:
		return SignedURLTokensAreTransportOnly
	case SignedURLTokensAreIdentity:
		return SignedURLTokensAreIdentity
	default:
		return SignedURLTokensAreTransportOnly
	}
}

func digestID(prefix, material string) string {
	sum := sha256.Sum256([]byte(material))
	return prefix + "_" + hex.EncodeToString(sum[:])
}
