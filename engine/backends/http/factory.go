package httpbackend

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"

	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/security/credentials"
	headerpolicy "github.com/subhra74/xdm/engine/security/header"
)

var (
	ErrInvalidFactory           = errors.New("invalid http backend request factory")
	ErrUnsupportedTransport     = errors.New("http backend requires http or https transport")
	ErrIntentURLMismatch        = errors.New("transfer URL does not match canonical intent")
	ErrUnsafeRangeResume        = errors.New("request semantics do not permit byte-range resume")
	ErrOneShotUnsupported       = errors.New("one-shot request body is unsupported for durable downloads")
	ErrBodyMaterialUnavailable  = errors.New("request body material is unavailable")
	ErrCredentialUnavailable    = errors.New("credential material is unavailable")
	ErrUnsafeCredentialMaterial = errors.New("resolved credential contains unsafe header bytes")
)

// BodyProvider resolves an opaque body reference at execution time. Implementors
// own the storage details for immutable bytes, immutable files and secret-backed
// body material; those details never enter persisted NetworkIntent state.
type BodyProvider interface {
	OpenBody(context.Context, domainrequest.BodySourceKind, domainrequest.BodyReference) (io.ReadCloser, error)
}

// SecretProvider resolves credential references only for the duration of a
// concrete request. Returned material is never included in SafeDiagnostic.
type SecretProvider interface {
	ResolveSecret(context.Context, domainrequest.SecretReference) ([]byte, error)
}

type Factory struct {
	intent  domainrequest.NetworkIntent
	bodies  BodyProvider
	secrets SecretProvider
}

func NewFactory(in domainrequest.NetworkIntent, bodies BodyProvider, secrets SecretProvider) (*Factory, error) {
	normalized, err := domainrequest.NewNetworkIntent(in)
	if err != nil {
		return nil, err
	}
	if !strings.HasPrefix(normalized.TransportURL, "http://") && !strings.HasPrefix(normalized.TransportURL, "https://") {
		return nil, ErrUnsupportedTransport
	}
	return &Factory{intent: normalized, bodies: bodies, secrets: secrets}, nil
}

func (f *Factory) Replayable() bool {
	return f != nil && f.intent.Replayable()
}

func (f *Factory) RangeResumeAllowed() bool {
	return f != nil && f.intent.RangeResumeAllowed()
}

func (f *Factory) openBody(ctx context.Context) (io.ReadCloser, error) {
	if f == nil || f.intent.Body == nil {
		return nil, nil
	}
	body := *f.intent.Body
	if body.Kind == domainrequest.BodySourceOneShot {
		return nil, ErrOneShotUnsupported
	}
	if f.bodies == nil {
		return nil, ErrBodyMaterialUnavailable
	}
	r, err := f.bodies.OpenBody(ctx, body.Kind, body.Ref)
	if err != nil {
		// Provider diagnostics may contain storage paths or body material. Keep
		// the safe execution error intentionally opaque.
		return nil, ErrBodyMaterialUnavailable
	}
	if r == nil {
		return nil, ErrBodyMaterialUnavailable
	}
	return r, nil
}

func (f *Factory) applyCredentials(ctx context.Context, req *http.Request) error {
	refs, err := credentials.ForDestination(f.intent.Credentials, f.intent.TransportURL, f.intent.Resource)
	if err != nil {
		return err
	}
	if len(refs) == 0 {
		return nil
	}
	if f.secrets == nil {
		return ErrCredentialUnavailable
	}
	for _, ref := range refs {
		raw, resolveErr := f.secrets.ResolveSecret(ctx, ref.Ref)
		if resolveErr != nil {
			// Secret-provider errors are not trusted for safe diagnostics.
			return ErrCredentialUnavailable
		}
		value := string(raw)
		for i := range raw {
			raw[i] = 0
		}
		if strings.ContainsAny(value, "\r\n") {
			return ErrUnsafeCredentialMaterial
		}
		switch ref.Kind {
		case domainrequest.CredentialAuthorization:
			req.Header.Set("Authorization", value)
		case domainrequest.CredentialCookie:
			req.Header.Add("Cookie", value)
		case domainrequest.CredentialAPIKey:
			if ref.HeaderName == "" {
				return ErrInvalidFactory
			}
			req.Header.Set(ref.HeaderName, value)
		case domainrequest.CredentialProxyAuthorization:
			// Proxy credentials are intentionally resolved by the proxy transport,
			// never attached to the origin request.
		default:
			return ErrInvalidFactory
		}
	}
	return nil
}

// NewRequest implements transfer/http.RequestFactory. The canonical intent is
// the only source of method/body/header semantics; startOffset is execution
// metadata used solely to reject unsafe POST/body Range resume.
func (f *Factory) NewRequest(ctx context.Context, rawURL string, startOffset int64) (*http.Request, error) {
	if f == nil || startOffset < 0 {
		return nil, ErrInvalidFactory
	}
	if rawURL != f.intent.TransportURL {
		return nil, ErrIntentURLMismatch
	}
	if startOffset > 0 && !f.intent.RangeResumeAllowed() {
		return nil, ErrUnsafeRangeResume
	}
	if f.intent.Body != nil && f.intent.Body.Kind == domainrequest.BodySourceOneShot {
		return nil, ErrOneShotUnsupported
	}

	headers, err := headerpolicy.Admit(f.intent.Headers, headerpolicy.SourceTrustedEngine)
	if err != nil {
		return nil, err
	}

	var body io.ReadCloser
	if f.intent.Body != nil {
		body, err = f.openBody(ctx)
		if err != nil {
			return nil, err
		}
	}
	req, err := http.NewRequestWithContext(ctx, f.intent.Method, f.intent.TransportURL, body)
	if err != nil {
		if body != nil {
			_ = body.Close()
		}
		return nil, err
	}
	req.Header = headers.Clone()
	if f.intent.Body != nil {
		bodySpec := *f.intent.Body
		if bodySpec.ContentType != "" {
			req.Header.Set("Content-Type", bodySpec.ContentType)
		}
		if bodySpec.Length != nil {
			req.ContentLength = *bodySpec.Length
		}
		if bodySpec.IsReplayable() {
			baseCtx := context.WithoutCancel(ctx)
			req.GetBody = func() (io.ReadCloser, error) {
				return f.openBody(baseCtx)
			}
		}
	}
	if err := f.applyCredentials(ctx, req); err != nil {
		if req.Body != nil {
			_ = req.Body.Close()
		}
		return nil, err
	}
	return req, nil
}
