package credentials

import (
	"errors"
	"net/url"
	"path"
	"strings"

	"github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
)

var ErrInvalidDestination = errors.New("invalid credential destination")

func origin(raw string) (string, *url.URL, error) {
	u, e := url.Parse(raw)
	if e != nil || u.Scheme == "" || u.Host == "" || u.User != nil {
		return "", nil, ErrInvalidDestination
	}
	scheme := strings.ToLower(u.Scheme)
	host := strings.ToLower(u.Hostname())
	port := u.Port()
	if (scheme == "https" && port == "443") || (scheme == "http" && port == "80") {
		port = ""
	}
	authority := host
	if port != "" {
		authority = u.Host
	}
	return scheme + "://" + strings.ToLower(authority), u, nil
}

// ForDestination returns only origin credentials whose explicit scope matches
// the exact destination origin, optional logical resource and cookie path.
// Proxy credentials are deliberately excluded from origin requests.
func ForDestination(refs []request.CredentialReference, destination string, res resource.Identity) ([]request.CredentialReference, error) {
	o, u, e := origin(destination)
	if e != nil {
		return nil, e
	}
	out := []request.CredentialReference{}
	for _, r := range refs {
		if r.Kind == request.CredentialProxyAuthorization {
			continue
		}
		if r.Scope.Origin != o {
			continue
		}
		if !r.Scope.Resource.IsZero() && r.Scope.Resource != res {
			continue
		}
		if r.Kind == request.CredentialCookie && r.Scope.PathPrefix != "" {
			clean := path.Clean(u.EscapedPath())
			prefix := path.Clean(r.Scope.PathPrefix)
			if clean != prefix && !strings.HasPrefix(clean, prefix+"/") {
				continue
			}
		}
		out = append(out, r)
	}
	return out, nil
}
func ProxyReferences(refs []request.CredentialReference) []request.CredentialReference {
	out := []request.CredentialReference{}
	for _, r := range refs {
		if r.Kind == request.CredentialProxyAuthorization {
			out = append(out, r)
		}
	}
	return out
}
func SameOrigin(a, b string) bool {
	oa, _, ea := origin(a)
	ob, _, eb := origin(b)
	return ea == nil && eb == nil && oa == ob
}
