package httpbackend

import (
	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	headerpolicy "github.com/subhra74/xdm/engine/security/header"
	"github.com/subhra74/xdm/engine/security/redirect"
)

type SafeBodyDiagnostic struct {
	Kind          domainrequest.BodySourceKind    `json:"kind"`
	Replayability domainrequest.BodyReplayability `json:"replayability"`
	ContentType   string                          `json:"content_type,omitempty"`
	Length        *int64                          `json:"length,omitempty"`
}

type SafeDiagnostic struct {
	URL        string              `json:"url"`
	Method     string              `json:"method"`
	Headers    map[string]string   `json:"headers,omitempty"`
	Body       *SafeBodyDiagnostic `json:"body,omitempty"`
	Replayable bool                `json:"replayable"`
	Resumable  bool                `json:"range_resumable"`
}

// Diagnostic returns safe operational metadata only. Query strings, body refs,
// body bytes and resolved credential material are intentionally absent.
func (f *Factory) Diagnostic() (SafeDiagnostic, error) {
	if f == nil {
		return SafeDiagnostic{}, ErrInvalidFactory
	}
	headers, err := headerpolicy.Admit(f.intent.Headers, headerpolicy.SourceTrustedEngine)
	if err != nil {
		return SafeDiagnostic{}, err
	}
	d := SafeDiagnostic{
		URL:        redirect.DiagnosticURL(f.intent.TransportURL),
		Method:     f.intent.Method,
		Headers:    headerpolicy.DiagnosticSafe(headers, f.intent.Credentials),
		Replayable: f.intent.Replayable(),
		Resumable:  f.intent.RangeResumeAllowed(),
	}
	if f.intent.Body != nil {
		b := *f.intent.Body
		d.Body = &SafeBodyDiagnostic{Kind: b.Kind, Replayability: b.Replayability, ContentType: b.ContentType, Length: b.Length}
	}
	return d, nil
}
