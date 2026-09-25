package header

import (
	"errors"
	"fmt"
	"net/http"
	"net/textproto"
	"net/url"
	"strconv"
	"strings"
	"unicode"

	domainrequest "github.com/subhra74/xdm/engine/domain/request"
)

var (
	ErrHeaderInjection = errors.New("header injection rejected")
	ErrInvalidHeader   = errors.New("invalid request header")
	ErrTransportOwned  = errors.New("transport-owned header")
	ErrSensitiveValue  = errors.New("sensitive header value must use credential reference")
)

type Source string

const (
	SourceTrustedEngine   Source = "trusted_engine"
	SourceExternalHandoff Source = "external_handoff"
)

var transportOwned = map[string]struct{}{
	"host": {}, "content-length": {}, "connection": {}, "transfer-encoding": {},
	"proxy-connection": {}, "proxy-authenticate": {}, "keep-alive": {}, "upgrade": {}, "te": {}, "trailer": {},
}

var externalAllowed = map[string]struct{}{
	"accept": {}, "accept-encoding": {}, "accept-language": {}, "cache-control": {}, "dnt": {},
	"origin": {}, "pragma": {}, "referer": {}, "range": {}, "user-agent": {}, "x-requested-with": {},
	"if-range": {}, "if-none-match": {}, "if-modified-since": {},
}

func isToken(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r > unicode.MaxASCII || unicode.IsControl(r) || unicode.IsSpace(r) || strings.ContainsRune("()<>@,;:\\\"/[]?={}\t", r) {
			return false
		}
	}
	return true
}

func SensitiveName(name string) bool {
	n := strings.ToLower(strings.TrimSpace(name))
	switch n {
	case "authorization", "cookie", "proxy-authorization", "x-api-key", "api-key", "x-auth-token", "x-access-token", "x-csrf-token":
		return true
	}
	return strings.Contains(n, "token") || strings.HasSuffix(n, "-key")
}

// Admit validates and canonicalizes the persisted non-secret header surface.
// Arbitrary syntactically safe custom headers are allowed for trusted engine
// requests to preserve Desktop behavior; untrusted browser handoffs retain the
// Android donor's conservative allowlist.
func Admit(in []domainrequest.Header, source Source) (http.Header, error) {
	out := make(http.Header, len(in))
	for _, h := range in {
		name, value := h.Name, h.Value
		if strings.ContainsAny(name, "\r\n") || strings.ContainsAny(value, "\r\n") {
			return nil, ErrHeaderInjection
		}
		if !isToken(name) {
			return nil, ErrInvalidHeader
		}
		lower := strings.ToLower(name)
		if _, ok := transportOwned[lower]; ok {
			return nil, fmt.Errorf("%w: %s", ErrTransportOwned, name)
		}
		if SensitiveName(name) {
			return nil, fmt.Errorf("%w: %s", ErrSensitiveValue, name)
		}
		if source == SourceExternalHandoff {
			if _, ok := externalAllowed[lower]; !ok && !strings.HasPrefix(lower, "sec-fetch-") {
				return nil, fmt.Errorf("%w: external header %s", ErrInvalidHeader, name)
			}
		}
		canonical := textproto.CanonicalMIMEHeaderKey(name)
		if canonical == "" {
			return nil, ErrInvalidHeader
		}
		out.Set(canonical, value)
	}
	return out, nil
}

func DiagnosticSafe(headers http.Header, refs []domainrequest.CredentialReference) map[string]string {
	out := make(map[string]string, len(headers)+len(refs))
	for k, v := range headers {
		if SensitiveName(k) {
			out[k] = "<redacted>"
		} else {
			out[k] = strings.Join(v, ", ")
		}
	}
	for _, r := range refs {
		name := r.HeaderName
		if name == "" {
			switch r.Kind {
			case domainrequest.CredentialAuthorization:
				name = "Authorization"
			case domainrequest.CredentialCookie:
				name = "Cookie"
			case domainrequest.CredentialProxyAuthorization:
				name = "Proxy-Authorization"
			default:
				name = "Credential"
			}
		}
		out[name] = "<redacted-ref>"
	}
	return out
}

type Origin struct {
	Scheme string `json:"scheme"`
	Host   string `json:"host"`
	Port   int    `json:"port"`
}

func (o Origin) String() string {
	defaultPort := 0
	switch o.Scheme {
	case "http":
		defaultPort = 80
	case "https":
		defaultPort = 443
	case "ftp":
		defaultPort = 21
	case "ftps":
		defaultPort = 990
	}
	host := o.Host
	if strings.Contains(host, ":") && !strings.HasPrefix(host, "[") {
		host = "[" + host + "]"
	}
	if o.Port == defaultPort || o.Port == 0 {
		return o.Scheme + "://" + host
	}
	return fmt.Sprintf("%s://%s:%d", o.Scheme, host, o.Port)
}

func (o Origin) Equal(other Origin) bool {
	return o.Scheme == other.Scheme && o.Host == other.Host && o.Port == other.Port
}

func ParseOrigin(raw string) (Origin, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Scheme == "" || u.Hostname() == "" || u.User != nil {
		return Origin{}, ErrInvalidHeader
	}
	scheme := strings.ToLower(u.Scheme)
	port := 0
	switch scheme {
	case "http":
		port = 80
	case "https":
		port = 443
	case "ftp":
		port = 21
	case "ftps":
		port = 990
	default:
		return Origin{}, ErrInvalidHeader
	}
	if p := u.Port(); p != "" {
		parsed, err := strconv.Atoi(p)
		if err != nil || parsed < 1 || parsed > 65535 {
			return Origin{}, ErrInvalidHeader
		}
		port = parsed
	}
	return Origin{Scheme: scheme, Host: strings.ToLower(strings.TrimSuffix(u.Hostname(), ".")), Port: port}, nil
}
