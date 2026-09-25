package redirect

import (
	"errors"
	"fmt"
	"net/netip"
	"net/url"
	"strings"

	"github.com/subhra74/xdm/engine/domain/identity"
	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/security/credentials"
	"github.com/subhra74/xdm/engine/security/route"
	"github.com/subhra74/xdm/engine/security/transportpolicy"
	"github.com/subhra74/xdm/engine/security/urlpolicy"
)

var (
	ErrRedirectLimit     = errors.New("redirect limit exceeded")
	ErrRedirectLoop      = errors.New("redirect loop detected")
	ErrRedirectStatus    = errors.New("unsupported redirect status")
	ErrBodyNotReplayable = errors.New("redirect requires replayable body")
	ErrDowngrade         = errors.New("redirect transport downgrade denied")
)

type Chain struct {
	Max     int      `json:"max"`
	Visited []string `json:"visited"`
}

type Step struct {
	From        string                              `json:"from"`
	To          string                              `json:"to"`
	Status      int                                 `json:"status"`
	Method      string                              `json:"method"`
	Body        *domainrequest.Body                 `json:"body,omitempty"`
	Credentials []domainrequest.CredentialReference `json:"credentials,omitempty"`
	Route       route.Decision                      `json:"route"`
	CrossOrigin bool                                `json:"cross_origin"`
	Downgrade   bool                                `json:"downgrade"`
	Chain       Chain                               `json:"chain"`
}

func canonicalLocation(current, location string) (string, error) {
	base, e := url.Parse(current)
	if e != nil {
		return "", e
	}
	ref, e := url.Parse(location)
	if e != nil {
		return "", e
	}
	next := base.ResolveReference(ref)
	t, e := urlpolicy.ParseTransport(next.String())
	if e != nil {
		return "", e
	}
	return t.String(), nil
}

func redirectedMethod(status int, method string, body *domainrequest.Body) (string, *domainrequest.Body, error) {
	switch status {
	case 301, 302, 303:
		if method == "POST" {
			return "GET", nil, nil
		}
		return method, body, nil
	case 307, 308:
		if body != nil && body.Replayability != domainrequest.BodyReplayable {
			return "", nil, ErrBodyNotReplayable
		}
		return method, body, nil
	default:
		return "", nil, ErrRedirectStatus
	}
}

func DiagnosticURL(raw string) string {
	u, e := url.Parse(raw)
	if e != nil {
		return "<invalid-url>"
	}
	u.RawQuery = ""
	u.ForceQuery = false
	u.Fragment = ""
	u.User = nil
	return u.String()
}

func DiagnosticChain(chain Chain) []string {
	out := make([]string, 0, len(chain.Visited))
	for _, raw := range chain.Visited {
		out = append(out, DiagnosticURL(raw))
	}
	return out
}

func Next(req identity.RequestID, res resource.Identity, current domainrequest.NetworkIntent, chain Chain, status int, location string, addresses []netip.Addr, approvals []route.Approval, clear transportpolicy.CleartextDecision) (Step, error) {
	if chain.Max <= 0 {
		chain.Max = 10
	}
	if len(chain.Visited) == 0 {
		chain.Visited = []string{current.TransportURL}
	}
	redirectCount := len(chain.Visited) - 1
	if redirectCount >= chain.Max {
		return Step{}, ErrRedirectLimit
	}
	to, e := canonicalLocation(current.TransportURL, location)
	if e != nil {
		return Step{}, e
	}
	for _, v := range chain.Visited {
		if v == to {
			return Step{}, ErrRedirectLoop
		}
	}
	fromOrigin := urlpolicy.MustOrigin(current.TransportURL)
	toOrigin := urlpolicy.MustOrigin(to)
	cross := fromOrigin != toOrigin
	fromURL, _ := url.Parse(current.TransportURL)
	toURL, _ := url.Parse(to)
	downgrade := strings.EqualFold(fromURL.Scheme, "https") && strings.EqualFold(toURL.Scheme, "http")
	method, body, e := redirectedMethod(status, current.Method, current.Body)
	if e != nil {
		return Step{}, e
	}
	forwarded, e := credentials.ForDestination(current.Credentials, to, res)
	if e != nil {
		return Step{}, e
	}
	if downgrade {
		if e := transportpolicy.CheckCleartext(to, len(forwarded) > 0 || transportpolicy.HasCredentialBearingQuery(to), clear); e != nil {
			return Step{}, fmt.Errorf("%w: %v", ErrDowngrade, e)
		}
	} else if strings.EqualFold(toURL.Scheme, "http") {
		if e := transportpolicy.CheckCleartext(to, len(forwarded) > 0 || transportpolicy.HasCredentialBearingQuery(to), clear); e != nil {
			return Step{}, e
		}
	}
	rd, e := route.Evaluate(route.Target{RequestID: req, Resource: res, URL: to, Addresses: addresses}, approvals)
	if e != nil {
		return Step{}, e
	}
	chain.Visited = append(append([]string(nil), chain.Visited...), to)
	return Step{From: current.TransportURL, To: to, Status: status, Method: method, Body: body, Credentials: forwarded, Route: rd, CrossOrigin: cross, Downgrade: downgrade, Chain: chain}, nil
}
