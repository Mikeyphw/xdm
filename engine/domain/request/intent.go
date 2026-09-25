package request

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"sort"
	"strings"
	"unicode"

	"github.com/subhra74/xdm/engine/domain/resource"
)

var (
	ErrInvalidIntent                = errors.New("invalid network intent")
	ErrInvalidMethodBody            = errors.New("invalid method/body combination")
	ErrSensitiveHeaderValue         = errors.New("sensitive header values must use credential references")
	ErrRuntimeMaterialSerialization = errors.New("runtime network secret material cannot be serialized")
)

type IntentFailureReason string

const (
	IntentMalformedURL      IntentFailureReason = "malformed_url"
	IntentUnsupportedMethod IntentFailureReason = "unsupported_method"
	IntentBodyNotAllowed    IntentFailureReason = "body_not_allowed"
	IntentBodyRequired      IntentFailureReason = "body_required"
	IntentInvalidBody       IntentFailureReason = "invalid_body"
)

type IntentValidationError struct {
	Reason IntentFailureReason
	Field  string
}

func (e *IntentValidationError) Error() string {
	return fmt.Sprintf("%v: %s (%s)", ErrInvalidIntent, e.Reason, e.Field)
}
func (e *IntentValidationError) Unwrap() error { return ErrInvalidIntent }

type Header struct {
	Name  string `json:"name"`
	Value string `json:"value"`
}

type BodyReplayability string

const (
	BodyReplayable BodyReplayability = "replayable"
	BodyOneShot    BodyReplayability = "one_shot"
)

type Body struct {
	Ref           BodyReference     `json:"ref"`
	Replayability BodyReplayability `json:"replayability"`
	ContentType   string            `json:"content_type,omitempty"`
	Length        *int64            `json:"length,omitempty"`
}
type ValidatorSet struct {
	ETag         string `json:"etag,omitempty"`
	LastModified string `json:"last_modified,omitempty"`
}
type Checksum struct {
	Algorithm string `json:"algorithm"`
	Digest    string `json:"digest"`
}
type SourceMetadata struct {
	PageURL          string `json:"page_url,omitempty"`
	Capture          string `json:"capture,omitempty"`
	FrameOrigin      string `json:"frame_origin,omitempty"`
	BrowserRequestID string `json:"browser_request_id,omitempty"`
}

type CredentialKind string

const (
	CredentialAuthorization      CredentialKind = "authorization"
	CredentialCookie             CredentialKind = "cookie"
	CredentialProxyAuthorization CredentialKind = "proxy_authorization"
	CredentialAPIKey             CredentialKind = "api_key"
)

type CredentialScope struct {
	Origin     string            `json:"origin,omitempty"`
	PathPrefix string            `json:"path_prefix,omitempty"`
	Resource   resource.Identity `json:"resource,omitempty"`
}
type CredentialReference struct {
	Kind       CredentialKind  `json:"kind"`
	HeaderName string          `json:"header_name,omitempty"`
	Ref        SecretReference `json:"ref"`
	Scope      CredentialScope `json:"scope"`
}
type ApprovalReference string

type NetworkIntent struct {
	TransportURL         string                `json:"transport_url"`
	Resource             resource.Identity     `json:"resource"`
	Method               string                `json:"method"`
	Headers              []Header              `json:"headers,omitempty"`
	Body                 *Body                 `json:"body,omitempty"`
	Credentials          []CredentialReference `json:"credentials,omitempty"`
	Mirrors              []string              `json:"mirrors,omitempty"`
	ExpectedLength       *int64                `json:"expected_length,omitempty"`
	Validators           ValidatorSet          `json:"validators,omitempty"`
	Checksums            []Checksum            `json:"checksums,omitempty"`
	Source               SourceMetadata        `json:"source,omitempty"`
	BackendPreference    string                `json:"backend_preference,omitempty"`
	AllowBackendFallback bool                  `json:"allow_backend_fallback"`
	ApprovalRefs         []ApprovalReference   `json:"network_approval_refs,omitempty"`
}

// RuntimeMaterial is resolved only at execution time. Persisting or logging it
// through JSON is forbidden so secrets/body bytes cannot leak into safe state.
type RuntimeMaterial struct {
	BodyBytes    []byte
	SecretValues map[SecretReference][]byte
}

func (RuntimeMaterial) MarshalJSON() ([]byte, error) { return nil, ErrRuntimeMaterialSerialization }

func validateMethod(method string) (string, error) {
	method = strings.ToUpper(strings.TrimSpace(method))
	if method == "" {
		method = "GET"
	}
	for _, r := range method {
		if !unicode.IsLetter(r) && r != '-' {
			return "", &IntentValidationError{IntentUnsupportedMethod, "method"}
		}
	}
	if method != "GET" && method != "POST" {
		return "", &IntentValidationError{IntentUnsupportedMethod, "method"}
	}
	return method, nil
}
func normalizeURL(raw string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Scheme == "" || u.Host == "" || u.User != nil || u.Fragment != "" {
		return "", &IntentValidationError{IntentMalformedURL, "url"}
	}
	u.Scheme = strings.ToLower(u.Scheme)
	if u.Scheme != "http" && u.Scheme != "https" && u.Scheme != "ftp" && u.Scheme != "ftps" {
		return "", &IntentValidationError{IntentMalformedURL, "scheme"}
	}
	host := strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
	port := u.Port()
	def := map[string]string{"http": "80", "https": "443", "ftp": "21", "ftps": "990"}[u.Scheme]
	if port == "" || port == def {
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
	return u.String(), nil
}
func normalizeOrigin(raw string) (string, error) {
	s, err := normalizeURL(raw)
	if err != nil {
		return "", err
	}
	u, _ := url.Parse(s)
	return u.Scheme + "://" + u.Host, nil
}
func sensitiveHeader(name string) bool {
	n := strings.ToLower(strings.TrimSpace(name))
	return n == "authorization" || n == "cookie" || n == "proxy-authorization" || n == "x-api-key" || n == "api-key" || strings.Contains(n, "token") || strings.HasSuffix(n, "-key")
}
func validateHeader(h Header) error {
	if strings.TrimSpace(h.Name) == "" || strings.ContainsAny(h.Name, "\r\n:") || strings.ContainsAny(h.Value, "\r\n") {
		return fmt.Errorf("%w: header", ErrInvalidIntent)
	}
	if sensitiveHeader(h.Name) {
		return ErrSensitiveHeaderValue
	}
	return nil
}

func NewNetworkIntent(in NetworkIntent) (NetworkIntent, error) {
	if in.Resource.IsZero() {
		return NetworkIntent{}, fmt.Errorf("%w: zero resource", ErrInvalidIntent)
	}
	transport, err := normalizeURL(in.TransportURL)
	if err != nil {
		return NetworkIntent{}, err
	}
	in.TransportURL = transport
	method, err := validateMethod(in.Method)
	if err != nil {
		return NetworkIntent{}, err
	}
	in.Method = method
	if in.Body != nil {
		if method != "POST" {
			return NetworkIntent{}, &IntentValidationError{IntentBodyNotAllowed, "body"}
		}
		if _, err := parseReference(string(in.Body.Ref)); err != nil {
			return NetworkIntent{}, &IntentValidationError{IntentInvalidBody, "body.ref"}
		}
		if in.Body.Replayability != BodyReplayable && in.Body.Replayability != BodyOneShot {
			return NetworkIntent{}, &IntentValidationError{IntentInvalidBody, "body.replayability"}
		}
		if in.Body.Length != nil && *in.Body.Length < 0 {
			return NetworkIntent{}, &IntentValidationError{IntentInvalidBody, "body.length"}
		}
		if strings.ContainsAny(in.Body.ContentType, "\r\n") {
			return NetworkIntent{}, &IntentValidationError{IntentInvalidBody, "body.content_type"}
		}
	} else if method == "POST" {
		return NetworkIntent{}, &IntentValidationError{IntentBodyRequired, "body"}
	}
	for _, h := range in.Headers {
		if err := validateHeader(h); err != nil {
			return NetworkIntent{}, err
		}
	}
	for i := range in.Credentials {
		c := &in.Credentials[i]
		switch c.Kind {
		case CredentialAuthorization, CredentialCookie, CredentialProxyAuthorization, CredentialAPIKey:
		default:
			return NetworkIntent{}, fmt.Errorf("%w: credential kind", ErrInvalidIntent)
		}
		if _, err := parseReference(string(c.Ref)); err != nil {
			return NetworkIntent{}, fmt.Errorf("%w: credential ref", ErrInvalidIntent)
		}
		if c.Kind == CredentialAPIKey {
			if !sensitiveHeader(c.HeaderName) || strings.ContainsAny(c.HeaderName, "\r\n:") {
				return NetworkIntent{}, fmt.Errorf("%w: credential header", ErrInvalidIntent)
			}
		}
		if c.Kind == CredentialProxyAuthorization {
			if c.Scope.Origin != "" {
				o, e := normalizeOrigin(c.Scope.Origin)
				if e != nil {
					return NetworkIntent{}, e
				}
				c.Scope.Origin = o
			}
		} else {
			o, e := normalizeOrigin(c.Scope.Origin)
			if e != nil {
				return NetworkIntent{}, e
			}
			c.Scope.Origin = o
		}
		if c.Scope.PathPrefix != "" && !strings.HasPrefix(c.Scope.PathPrefix, "/") {
			return NetworkIntent{}, fmt.Errorf("%w: credential path", ErrInvalidIntent)
		}
	}
	seen := map[string]bool{in.TransportURL: true}
	mirrors := make([]string, 0, len(in.Mirrors))
	for _, m := range in.Mirrors {
		n, e := normalizeURL(m)
		if e != nil {
			return NetworkIntent{}, e
		}
		if !seen[n] {
			seen[n] = true
			mirrors = append(mirrors, n)
		}
	}
	in.Mirrors = mirrors
	if in.ExpectedLength != nil && *in.ExpectedLength < 0 {
		return NetworkIntent{}, fmt.Errorf("%w: expected length", ErrInvalidIntent)
	}
	for i := range in.Checksums {
		in.Checksums[i].Algorithm = strings.ToLower(strings.TrimSpace(in.Checksums[i].Algorithm))
		in.Checksums[i].Digest = strings.TrimSpace(in.Checksums[i].Digest)
		if in.Checksums[i].Algorithm == "" || in.Checksums[i].Digest == "" {
			return NetworkIntent{}, fmt.Errorf("%w: checksum", ErrInvalidIntent)
		}
	}
	for _, a := range in.ApprovalRefs {
		if _, err := parseReference(string(a)); err != nil {
			return NetworkIntent{}, fmt.Errorf("%w: approval ref", ErrInvalidIntent)
		}
	}
	headers := append([]Header(nil), in.Headers...)
	sort.SliceStable(headers, func(i, j int) bool { return strings.ToLower(headers[i].Name) < strings.ToLower(headers[j].Name) })
	in.Headers = headers
	in.Credentials = append([]CredentialReference(nil), in.Credentials...)
	in.Checksums = append([]Checksum(nil), in.Checksums...)
	in.ApprovalRefs = append([]ApprovalReference(nil), in.ApprovalRefs...)
	return in, nil
}
func (in NetworkIntent) SafeJSON() ([]byte, error) {
	n, e := NewNetworkIntent(in)
	if e != nil {
		return nil, e
	}
	return json.Marshal(n)
}
func (in NetworkIntent) Replayable() bool {
	return in.Body == nil || in.Body.Replayability == BodyReplayable
}
