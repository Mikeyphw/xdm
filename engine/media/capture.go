package media

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"sort"
	"strings"
	"time"
	"unicode"
)

const (
	CaptureEnvelopeVersion = 1
	MaxEnvelopeBytes       = 64 * 1024
	MaxURLBytes            = 4096
	MaxHeaderCount         = 64
	MaxHeaderNameBytes     = 128
	MaxHeaderValueBytes    = 4096
	MaxFrameOriginBytes    = 1024
	MaxMediaHints          = 32
	MaxHintValueBytes      = 1024
)

var (
	ErrInvalidCaptureEnvelope = errors.New("invalid capture envelope")
	ErrOversizedCaptureField  = errors.New("oversized capture field")
	ErrSensitiveCaptureValue  = errors.New("sensitive capture material must use references")
)

type Header struct {
	Name  string `json:"name"`
	Value string `json:"value"`
}

type BodyEvidence struct {
	Reference   string `json:"reference"`
	ContentType string `json:"content_type,omitempty"`
	Length      *int64 `json:"length,omitempty"`
	Replayable  bool   `json:"replayable"`
}

type RequestEvidence struct {
	URL     string        `json:"url"`
	Method  string        `json:"method"`
	Headers []Header      `json:"headers,omitempty"`
	Body    *BodyEvidence `json:"body,omitempty"`
}

type PageContext struct {
	PageURL            string `json:"page_url"`
	FrameOrigin        string `json:"frame_origin,omitempty"`
	SessionID          string `json:"session_id"`
	DocumentGeneration int64  `json:"document_generation"`
	ParentFrameOrigin  string `json:"parent_frame_origin,omitempty"`
}

type ResponseMetadata struct {
	StatusCode    int       `json:"status_code,omitempty"`
	Headers       []Header  `json:"headers,omitempty"`
	ContentType   string    `json:"content_type,omitempty"`
	ContentLength *int64    `json:"content_length,omitempty"`
	ObservedAt    time.Time `json:"observed_at"`
}

type MediaHint struct {
	Kind  string `json:"kind"`
	Value string `json:"value"`
}

type CredentialScope struct {
	Origin     string `json:"origin,omitempty"`
	PathPrefix string `json:"path_prefix,omitempty"`
	Ref        string `json:"ref,omitempty"`
}

type CaptureEnvelope struct {
	Version         int              `json:"version"`
	Request         RequestEvidence  `json:"request"`
	Page            PageContext      `json:"page"`
	Response        ResponseMetadata `json:"response,omitempty"`
	MediaHints      []MediaHint      `json:"media_hints,omitempty"`
	CredentialScope CredentialScope  `json:"credential_scope,omitempty"`
}

type SafeDiagnostic struct {
	Version            int      `json:"version"`
	RequestURL         string   `json:"request_url"`
	Method             string   `json:"method"`
	HeaderNames        []string `json:"header_names,omitempty"`
	HasBodyReference   bool     `json:"has_body_reference"`
	PageURL            string   `json:"page_url"`
	FrameOrigin        string   `json:"frame_origin,omitempty"`
	SessionID          string   `json:"session_id"`
	DocumentGeneration int64    `json:"document_generation"`
	StatusCode         int      `json:"status_code,omitempty"`
}

func ParseCaptureEnvelopeJSON(data []byte) (CaptureEnvelope, error) {
	if len(data) == 0 || len(data) > MaxEnvelopeBytes {
		return CaptureEnvelope{}, fmt.Errorf("%w: envelope", ErrOversizedCaptureField)
	}
	var env CaptureEnvelope
	if err := json.Unmarshal(data, &env); err != nil {
		return CaptureEnvelope{}, fmt.Errorf("%w: json", ErrInvalidCaptureEnvelope)
	}
	return NewCaptureEnvelope(env)
}

func NewCaptureEnvelope(env CaptureEnvelope) (CaptureEnvelope, error) {
	if env.Version != CaptureEnvelopeVersion {
		return CaptureEnvelope{}, fmt.Errorf("%w: version", ErrInvalidCaptureEnvelope)
	}
	req, err := normalizeRequestEvidence(env.Request)
	if err != nil {
		return CaptureEnvelope{}, err
	}
	page, err := normalizePageContext(env.Page)
	if err != nil {
		return CaptureEnvelope{}, err
	}
	resp, err := normalizeResponseMetadata(env.Response)
	if err != nil {
		return CaptureEnvelope{}, err
	}
	hints, err := normalizeMediaHints(env.MediaHints)
	if err != nil {
		return CaptureEnvelope{}, err
	}
	scope, err := normalizeCredentialScope(env.CredentialScope)
	if err != nil {
		return CaptureEnvelope{}, err
	}
	env.Request = req
	env.Page = page
	env.Response = resp
	env.MediaHints = hints
	env.CredentialScope = scope
	return env, nil
}

func normalizeRequestEvidence(req RequestEvidence) (RequestEvidence, error) {
	u, err := normalizeHTTPURL(req.URL, "request.url")
	if err != nil {
		return RequestEvidence{}, err
	}
	method := strings.ToUpper(strings.TrimSpace(req.Method))
	if method == "" {
		method = "GET"
	}
	for _, r := range method {
		if !unicode.IsLetter(r) && r != '-' {
			return RequestEvidence{}, fmt.Errorf("%w: request.method", ErrInvalidCaptureEnvelope)
		}
	}
	if method != "GET" && method != "POST" {
		return RequestEvidence{}, fmt.Errorf("%w: request.method", ErrInvalidCaptureEnvelope)
	}
	headers, err := normalizeHeaders(req.Headers)
	if err != nil {
		return RequestEvidence{}, err
	}
	if req.Body != nil {
		if method != "POST" {
			return RequestEvidence{}, fmt.Errorf("%w: body only valid for POST", ErrInvalidCaptureEnvelope)
		}
		body := *req.Body
		body.Reference = strings.TrimSpace(body.Reference)
		if body.Reference == "" || len(body.Reference) > 256 || strings.ContainsAny(body.Reference, " \t\r\n") {
			return RequestEvidence{}, fmt.Errorf("%w: request.body.reference", ErrInvalidCaptureEnvelope)
		}
		if len(body.ContentType) > MaxHeaderValueBytes || strings.ContainsAny(body.ContentType, "\r\n") {
			return RequestEvidence{}, fmt.Errorf("%w: request.body.content_type", ErrInvalidCaptureEnvelope)
		}
		if body.Length != nil && *body.Length < 0 {
			return RequestEvidence{}, fmt.Errorf("%w: request.body.length", ErrInvalidCaptureEnvelope)
		}
		req.Body = &body
	} else if method == "POST" {
		return RequestEvidence{}, fmt.Errorf("%w: post body reference required", ErrInvalidCaptureEnvelope)
	}
	req.URL = u
	req.Method = method
	req.Headers = headers
	return req, nil
}

func normalizePageContext(page PageContext) (PageContext, error) {
	pageURL, err := normalizeHTTPURL(page.PageURL, "page.url")
	if err != nil {
		return PageContext{}, err
	}
	page.PageURL = pageURL
	page.SessionID = strings.TrimSpace(page.SessionID)
	if page.SessionID == "" || len(page.SessionID) > 128 || strings.ContainsAny(page.SessionID, " \t\r\n") {
		return PageContext{}, fmt.Errorf("%w: page.session_id", ErrInvalidCaptureEnvelope)
	}
	if page.DocumentGeneration <= 0 {
		return PageContext{}, fmt.Errorf("%w: page.document_generation", ErrInvalidCaptureEnvelope)
	}
	for _, field := range []struct{ name, value string }{{"page.frame_origin", page.FrameOrigin}, {"page.parent_frame_origin", page.ParentFrameOrigin}} {
		if field.value == "" {
			continue
		}
		if len(field.value) > MaxFrameOriginBytes {
			return PageContext{}, fmt.Errorf("%w: %s", ErrOversizedCaptureField, field.name)
		}
		if _, err := normalizeHTTPURL(field.value, field.name); err != nil {
			return PageContext{}, err
		}
	}
	return page, nil
}

func normalizeResponseMetadata(resp ResponseMetadata) (ResponseMetadata, error) {
	if resp.StatusCode < 0 || resp.StatusCode > 999 {
		return ResponseMetadata{}, fmt.Errorf("%w: response.status_code", ErrInvalidCaptureEnvelope)
	}
	if len(resp.ContentType) > MaxHeaderValueBytes || strings.ContainsAny(resp.ContentType, "\r\n") {
		return ResponseMetadata{}, fmt.Errorf("%w: response.content_type", ErrInvalidCaptureEnvelope)
	}
	if resp.ContentLength != nil && *resp.ContentLength < 0 {
		return ResponseMetadata{}, fmt.Errorf("%w: response.content_length", ErrInvalidCaptureEnvelope)
	}
	headers, err := normalizeHeaders(resp.Headers)
	if err != nil {
		return ResponseMetadata{}, err
	}
	resp.Headers = headers
	if resp.ObservedAt.IsZero() {
		resp.ObservedAt = time.Unix(0, 0).UTC()
	} else {
		resp.ObservedAt = resp.ObservedAt.UTC()
	}
	return resp, nil
}

func normalizeMediaHints(hints []MediaHint) ([]MediaHint, error) {
	if len(hints) > MaxMediaHints {
		return nil, fmt.Errorf("%w: media_hints", ErrOversizedCaptureField)
	}
	out := make([]MediaHint, 0, len(hints))
	for _, h := range hints {
		h.Kind = strings.TrimSpace(strings.ToLower(h.Kind))
		h.Value = strings.TrimSpace(h.Value)
		if h.Kind == "" || h.Value == "" || len(h.Value) > MaxHintValueBytes || strings.ContainsAny(h.Kind+h.Value, "\r\n") {
			return nil, fmt.Errorf("%w: media_hints", ErrInvalidCaptureEnvelope)
		}
		out = append(out, h)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Kind == out[j].Kind {
			return out[i].Value < out[j].Value
		}
		return out[i].Kind < out[j].Kind
	})
	return out, nil
}

func normalizeCredentialScope(scope CredentialScope) (CredentialScope, error) {
	if scope.Origin != "" {
		o, err := normalizeHTTPURL(scope.Origin, "credential_scope.origin")
		if err != nil {
			return CredentialScope{}, err
		}
		u, _ := url.Parse(o)
		scope.Origin = u.Scheme + "://" + u.Host
	}
	if scope.PathPrefix != "" && (!strings.HasPrefix(scope.PathPrefix, "/") || strings.ContainsAny(scope.PathPrefix, "\r\n")) {
		return CredentialScope{}, fmt.Errorf("%w: credential_scope.path_prefix", ErrInvalidCaptureEnvelope)
	}
	if scope.Ref != "" && (len(scope.Ref) > 256 || strings.ContainsAny(scope.Ref, " \t\r\n")) {
		return CredentialScope{}, fmt.Errorf("%w: credential_scope.ref", ErrInvalidCaptureEnvelope)
	}
	return scope, nil
}

func normalizeHeaders(headers []Header) ([]Header, error) {
	if len(headers) > MaxHeaderCount {
		return nil, fmt.Errorf("%w: headers", ErrOversizedCaptureField)
	}
	out := make([]Header, 0, len(headers))
	for _, h := range headers {
		name := strings.TrimSpace(h.Name)
		if name == "" || len(name) > MaxHeaderNameBytes || strings.ContainsAny(name, "\r\n:") {
			return nil, fmt.Errorf("%w: header name", ErrInvalidCaptureEnvelope)
		}
		if len(h.Value) > MaxHeaderValueBytes || strings.ContainsAny(h.Value, "\r\n") {
			return nil, fmt.Errorf("%w: header value", ErrInvalidCaptureEnvelope)
		}
		if sensitiveHeader(name) {
			return nil, fmt.Errorf("%w: %s", ErrSensitiveCaptureValue, name)
		}
		out = append(out, Header{Name: canonicalHeaderName(name), Value: h.Value})
	}
	sort.SliceStable(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out, nil
}

func normalizeHTTPURL(raw, field string) (string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" || len(raw) > MaxURLBytes {
		return "", fmt.Errorf("%w: %s", ErrOversizedCaptureField, field)
	}
	u, err := url.Parse(raw)
	if err != nil || u.Scheme == "" || u.Host == "" || u.User != nil || u.Fragment != "" {
		return "", fmt.Errorf("%w: %s", ErrInvalidCaptureEnvelope, field)
	}
	u.Scheme = strings.ToLower(u.Scheme)
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", fmt.Errorf("%w: %s scheme", ErrInvalidCaptureEnvelope, field)
	}
	u.Host = strings.ToLower(strings.TrimSuffix(u.Host, "."))
	if u.Path == "" {
		u.Path = "/"
	}
	return u.String(), nil
}

func sensitiveHeader(name string) bool {
	n := strings.ToLower(strings.TrimSpace(name))
	return n == "authorization" || n == "cookie" || n == "proxy-authorization" || n == "x-api-key" || n == "api-key" || strings.Contains(n, "token") || strings.HasSuffix(n, "-key")
}

func canonicalHeaderName(name string) string {
	parts := strings.Split(strings.ToLower(name), "-")
	for i, p := range parts {
		if p == "" {
			continue
		}
		parts[i] = strings.ToUpper(p[:1]) + p[1:]
	}
	return strings.Join(parts, "-")
}

func (env CaptureEnvelope) SafeDiagnostic() SafeDiagnostic {
	names := make([]string, 0, len(env.Request.Headers))
	for _, h := range env.Request.Headers {
		names = append(names, h.Name)
	}
	sort.Strings(names)
	return SafeDiagnostic{
		Version:            env.Version,
		RequestURL:         env.Request.URL,
		Method:             env.Request.Method,
		HeaderNames:        names,
		HasBodyReference:   env.Request.Body != nil && env.Request.Body.Reference != "",
		PageURL:            env.Page.PageURL,
		FrameOrigin:        env.Page.FrameOrigin,
		SessionID:          env.Page.SessionID,
		DocumentGeneration: env.Page.DocumentGeneration,
		StatusCode:         env.Response.StatusCode,
	}
}

func (env CaptureEnvelope) ReplayEvidenceKey() string {
	h := sha256.New()
	_, _ = h.Write([]byte(env.Request.Method))
	_, _ = h.Write([]byte{0})
	_, _ = h.Write([]byte(env.Request.URL))
	for _, header := range env.Request.Headers {
		_, _ = h.Write([]byte{0})
		_, _ = h.Write([]byte(strings.ToLower(header.Name)))
		_, _ = h.Write([]byte{0})
		_, _ = h.Write([]byte(header.Value))
	}
	if env.Request.Body != nil {
		_, _ = h.Write([]byte{0})
		_, _ = h.Write([]byte(env.Request.Body.Reference))
	}
	return "caprep_" + hex.EncodeToString(h.Sum(nil))
}

func (env CaptureEnvelope) LogicalObservationKey() string {
	h := sha256.New()
	_, _ = h.Write([]byte(env.Page.SessionID))
	_, _ = h.Write([]byte{0})
	_, _ = h.Write([]byte(fmt.Sprintf("%d", env.Page.DocumentGeneration)))
	_, _ = h.Write([]byte{0})
	_, _ = h.Write([]byte(env.Page.FrameOrigin))
	_, _ = h.Write([]byte{0})
	_, _ = h.Write([]byte(env.Request.URL))
	return "capobs_" + hex.EncodeToString(h.Sum(nil))
}
