package route

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"net/netip"
	"net/url"
	"strings"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/security/urlpolicy"
)

var (
	ErrDenied          = errors.New("network route denied")
	ErrInvalidApproval = errors.New("invalid network approval")
)

type Class string

const (
	Public      Class = "public"
	Private     Class = "private"
	Loopback    Class = "loopback"
	LinkLocal   Class = "link_local"
	Reserved    Class = "reserved"
	Multicast   Class = "multicast"
	Unspecified Class = "unspecified"
)

type DenialReason string

const (
	DenialMalformedTarget        DenialReason = "malformed_target"
	DenialResolutionRequired     DenialReason = "route_resolution_required"
	DenialScopedApprovalRequired DenialReason = "scoped_private_route_approval_required"
	DenialApprovalScopeMismatch  DenialReason = "approval_scope_mismatch"
)

type Denial struct {
	Reason  DenialReason `json:"reason"`
	Class   Class        `json:"class,omitempty"`
	Address netip.Addr   `json:"address,omitempty"`
}

func (d *Denial) Error() string {
	if d.Address.IsValid() {
		return fmt.Sprintf("%v: %s (%s %s)", ErrDenied, d.Reason, d.Class, d.Address)
	}
	return fmt.Sprintf("%v: %s", ErrDenied, d.Reason)
}
func (d *Denial) Unwrap() error { return ErrDenied }

var (
	v4Reserved = []netip.Prefix{
		netip.MustParsePrefix("0.0.0.0/8"),
		netip.MustParsePrefix("100.64.0.0/10"),
		netip.MustParsePrefix("192.0.0.0/24"),
		netip.MustParsePrefix("192.0.2.0/24"),
		netip.MustParsePrefix("198.18.0.0/15"),
		netip.MustParsePrefix("198.51.100.0/24"),
		netip.MustParsePrefix("203.0.113.0/24"),
		netip.MustParsePrefix("240.0.0.0/4"),
	}
	v6Reserved = []netip.Prefix{
		netip.MustParsePrefix("100::/64"),
		netip.MustParsePrefix("2001:2::/48"),
		netip.MustParsePrefix("2001:db8::/32"),
		netip.MustParsePrefix("2001:10::/28"),
	}
)

func Classify(addr netip.Addr) Class {
	if !addr.IsValid() {
		return Unspecified
	}
	addr = addr.Unmap()
	if addr.IsUnspecified() {
		return Unspecified
	}
	if addr.IsLoopback() {
		return Loopback
	}
	if addr.IsMulticast() {
		return Multicast
	}
	if addr.IsLinkLocalUnicast() {
		return LinkLocal
	}
	if addr.IsPrivate() {
		return Private
	}
	reserved := v6Reserved
	if addr.Is4() {
		reserved = v4Reserved
	}
	for _, prefix := range reserved {
		if prefix.Contains(addr) {
			return Reserved
		}
	}
	return Public
}

func RequiresApproval(class Class) bool { return class != Public }

type Approval struct {
	ID          string             `json:"id"`
	RequestID   identity.RequestID `json:"request_id"`
	Resource    resource.Identity  `json:"resource"`
	Origin      urlpolicy.Origin   `json:"origin"`
	TargetScope string             `json:"target_scope"`
	Class       Class              `json:"class"`
}

func approvalID(requestID identity.RequestID, res resource.Identity, origin urlpolicy.Origin, scope string, class Class) string {
	material := strings.Join([]string{requestID.String(), res.String(), origin.Scheme, origin.Host, origin.Port, scope, string(class)}, "\x00")
	sum := sha256.Sum256([]byte(material))
	return "approval_v1_" + hex.EncodeToString(sum[:])
}

func NewApproval(requestID identity.RequestID, res resource.Identity, targetURL string, class Class) (Approval, error) {
	if requestID.IsZero() || res.IsZero() || !RequiresApproval(class) {
		return Approval{}, ErrInvalidApproval
	}
	origin, scope, err := targetScope(targetURL)
	if err != nil {
		return Approval{}, ErrInvalidApproval
	}
	return Approval{
		ID:          approvalID(requestID, res, origin, scope, class),
		RequestID:   requestID,
		Resource:    res,
		Origin:      origin,
		TargetScope: scope,
		Class:       class,
	}, nil
}

func (a Approval) Validate() error {
	if a.RequestID.IsZero() || a.Resource.IsZero() || !RequiresApproval(a.Class) || a.TargetScope == "" || a.Origin.Host == "" || a.Origin.Scheme == "" || a.Origin.Port == "" {
		return ErrInvalidApproval
	}
	if a.ID != approvalID(a.RequestID, a.Resource, a.Origin, a.TargetScope, a.Class) {
		return ErrInvalidApproval
	}
	return nil
}

func (a Approval) Matches(requestID identity.RequestID, res resource.Identity, targetURL string, class Class) bool {
	if a.Validate() != nil || requestID != a.RequestID || res != a.Resource || class != a.Class {
		return false
	}
	origin, scope, err := targetScope(targetURL)
	if err != nil {
		return false
	}
	return a.Origin == origin && a.TargetScope == scope
}

type Target struct {
	RequestID identity.RequestID
	Resource  resource.Identity
	URL       string
	Addresses []netip.Addr
}

type AddressDecision struct {
	Address netip.Addr `json:"address"`
	Class   Class      `json:"class"`
}

type Decision struct {
	Origin    urlpolicy.Origin  `json:"origin"`
	Scope     string            `json:"target_scope"`
	Addresses []AddressDecision `json:"addresses"`
}

func Evaluate(target Target, approvals []Approval) (Decision, error) {
	if target.RequestID.IsZero() || target.Resource.IsZero() {
		return Decision{}, &Denial{Reason: DenialMalformedTarget}
	}
	transport, err := urlpolicy.ParseTransport(target.URL)
	if err != nil {
		return Decision{}, &Denial{Reason: DenialMalformedTarget}
	}
	origin, scope, err := targetScope(target.URL)
	if err != nil {
		return Decision{}, &Denial{Reason: DenialMalformedTarget}
	}
	addresses := append([]netip.Addr(nil), target.Addresses...)
	parsed, _ := url.Parse(transport.String())
	if literal, parseErr := netip.ParseAddr(parsed.Hostname()); parseErr == nil {
		literal = literal.Unmap()
		found := false
		for _, candidate := range addresses {
			if candidate.IsValid() && candidate.Unmap() == literal {
				found = true
				break
			}
		}
		if !found {
			addresses = append(addresses, literal)
		}
	}
	if len(addresses) == 0 {
		return Decision{}, &Denial{Reason: DenialResolutionRequired}
	}

	result := Decision{Origin: origin, Scope: scope}
	seen := map[netip.Addr]struct{}{}
	for _, raw := range addresses {
		if !raw.IsValid() {
			return Decision{}, &Denial{Reason: DenialMalformedTarget}
		}
		addr := raw.Unmap()
		if _, ok := seen[addr]; ok {
			continue
		}
		seen[addr] = struct{}{}
		class := Classify(addr)
		if RequiresApproval(class) {
			matched := false
			for _, approval := range approvals {
				if approval.Matches(target.RequestID, target.Resource, target.URL, class) {
					matched = true
					break
				}
			}
			if !matched {
				reason := DenialScopedApprovalRequired
				if len(approvals) > 0 {
					reason = DenialApprovalScopeMismatch
				}
				return Decision{}, &Denial{Reason: reason, Class: class, Address: addr}
			}
		}
		result.Addresses = append(result.Addresses, AddressDecision{Address: addr, Class: class})
	}
	return result, nil
}

func targetScope(raw string) (urlpolicy.Origin, string, error) {
	transport, err := urlpolicy.ParseTransport(raw)
	if err != nil {
		return urlpolicy.Origin{}, "", err
	}
	sum := sha256.Sum256([]byte(transport.String()))
	return transport.Origin(), "scope_v1_" + hex.EncodeToString(sum[:]), nil
}
