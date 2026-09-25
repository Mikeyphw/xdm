package urlpolicy

import (
	"errors"
	"fmt"
	"net"
	"net/url"
	"strings"
)

var ErrInvalidURL = errors.New("invalid transport URL")

type Origin struct {
	Scheme string `json:"scheme"`
	Host   string `json:"host"`
	Port   string `json:"port"`
}

func (o Origin) String() string {
	host := o.Host
	if strings.Contains(host, ":") && !strings.HasPrefix(host, "[") {
		host = "[" + host + "]"
	}
	if o.Port != "" && o.Port != defaultPort(o.Scheme) {
		return o.Scheme + "://" + net.JoinHostPort(strings.Trim(host, "[]"), o.Port)
	}
	return o.Scheme + "://" + host
}

func defaultPort(scheme string) string {
	switch scheme {
	case "http":
		return "80"
	case "https":
		return "443"
	case "ftp":
		return "21"
	case "ftps":
		return "990"
	default:
		return ""
	}
}

// TransportURL is the network location used for a request. It is intentionally
// distinct from the logical resource identity that survives mirrors/redirects.
type TransportURL struct {
	raw       string
	canonical string
	origin    Origin
}

func ParseTransport(raw string) (TransportURL, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return TransportURL{}, ErrInvalidURL
	}
	u, err := url.Parse(raw)
	if err != nil || u.Scheme == "" || u.Host == "" || u.User != nil || u.Fragment != "" {
		return TransportURL{}, ErrInvalidURL
	}
	scheme := strings.ToLower(u.Scheme)
	switch scheme {
	case "http", "https", "ftp", "ftps":
	default:
		return TransportURL{}, fmt.Errorf("%w: unsupported scheme %q", ErrInvalidURL, scheme)
	}
	host := strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
	if host == "" {
		return TransportURL{}, ErrInvalidURL
	}
	port := u.Port()
	if port == "" {
		port = defaultPort(scheme)
	}
	if port == "" {
		return TransportURL{}, ErrInvalidURL
	}
	if _, err := net.LookupPort("tcp", port); err != nil {
		// Numeric ports are accepted by LookupPort as service only inconsistently,
		// so validate them through SplitHostPort below when needed.
		if _, _, splitErr := net.SplitHostPort(net.JoinHostPort(host, port)); splitErr != nil {
			return TransportURL{}, ErrInvalidURL
		}
	}

	u.Scheme = scheme
	if port == defaultPort(scheme) {
		if strings.Contains(host, ":") {
			u.Host = "[" + host + "]"
		} else {
			u.Host = host
		}
	} else {
		u.Host = net.JoinHostPort(host, port)
	}
	if u.Path == "" {
		u.Path = "/"
	}
	canonical := u.String()
	return TransportURL{
		raw:       raw,
		canonical: canonical,
		origin:    Origin{Scheme: scheme, Host: host, Port: port},
	}, nil
}

func (u TransportURL) Raw() string                    { return u.raw }
func (u TransportURL) String() string                 { return u.canonical }
func (u TransportURL) Origin() Origin                 { return u.origin }
func (u TransportURL) IsZero() bool                   { return u.canonical == "" }
func (u TransportURL) SameOrigin(v TransportURL) bool { return u.origin == v.origin }

// MustOrigin returns the canonical origin for an already-validated transport URL.
// Invalid input returns the zero Origin and is intended only for internal call sites
// that have already passed ParseTransport.
func MustOrigin(raw string) Origin {
	t, err := ParseTransport(raw)
	if err != nil {
		return Origin{}
	}
	return t.Origin()
}
