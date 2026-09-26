package ops

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

const Redacted = "<redacted>"

var (
	ErrInvalidSecretRef = errors.New("invalid secret ref")
	ErrSecretExpired    = errors.New("secret expired")
	ErrSecretNotFound   = errors.New("secret not found")
)

type SecretRef struct {
	ID         string `json:"id"`
	Scope      string `json:"scope"`
	Generation int64  `json:"generation"`
}

func (r SecretRef) Validate() error {
	if strings.TrimSpace(r.ID) == "" || strings.ContainsAny(r.ID, "\r\n\t ") {
		return fmt.Errorf("%w: id", ErrInvalidSecretRef)
	}
	if strings.TrimSpace(r.Scope) == "" || strings.ContainsAny(r.Scope, "\r\n") {
		return fmt.Errorf("%w: scope", ErrInvalidSecretRef)
	}
	if r.Generation < 0 {
		return fmt.Errorf("%w: generation", ErrInvalidSecretRef)
	}
	return nil
}

func (r SecretRef) Canonical() string {
	return fmt.Sprintf("secret://%s#g%d", r.ID, r.Generation)
}

type SecretResolver func(context.Context, SecretRef) (string, error)

type cachedSecret struct {
	value     string
	expiresAt time.Time
}

type SecretBroker struct {
	mu    sync.Mutex
	now   func() time.Time
	ttl   time.Duration
	cache map[string]cachedSecret
}

func NewSecretBroker(ttl time.Duration, now func() time.Time) *SecretBroker {
	if now == nil {
		now = time.Now
	}
	if ttl <= 0 {
		ttl = time.Minute
	}
	return &SecretBroker{now: now, ttl: ttl, cache: map[string]cachedSecret{}}
}

func (b *SecretBroker) Resolve(ctx context.Context, ref SecretRef, resolver SecretResolver) (string, error) {
	if err := ref.Validate(); err != nil {
		return "", err
	}
	key := ref.Canonical()
	b.mu.Lock()
	if c, ok := b.cache[key]; ok {
		if b.now().Before(c.expiresAt) {
			v := c.value
			b.mu.Unlock()
			return v, nil
		}
		delete(b.cache, key)
	}
	b.mu.Unlock()
	if resolver == nil {
		return "", ErrSecretNotFound
	}
	value, err := resolver(ctx, ref)
	if err != nil {
		return "", err
	}
	select {
	case <-ctx.Done():
		return "", ctx.Err()
	default:
	}
	b.mu.Lock()
	b.cache[key] = cachedSecret{value: value, expiresAt: b.now().Add(b.ttl)}
	b.mu.Unlock()
	return value, nil
}

func (b *SecretBroker) Forget(ref SecretRef) {
	b.mu.Lock()
	defer b.mu.Unlock()
	delete(b.cache, ref.Canonical())
}

type SafeHeaders map[string]string

var signedQueryKeys = map[string]bool{
	"sig": true, "signature": true, "token": true, "auth": true, "x-amz-signature": true,
	"x-amz-credential": true, "x-amz-security-token": true, "expires": true,
}

func RedactHeaders(headers map[string]string) SafeHeaders {
	out := SafeHeaders{}
	keys := make([]string, 0, len(headers))
	for k := range headers {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		if IsSensitiveHeader(k) {
			out[k] = Redacted
		} else {
			out[k] = RedactInlineSecrets(headers[k])
		}
	}
	return out
}

func IsSensitiveHeader(name string) bool {
	n := strings.ToLower(strings.TrimSpace(name))
	switch n {
	case "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token":
		return true
	default:
		return strings.Contains(n, "token") || strings.Contains(n, "secret")
	}
}

func SafeURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return RedactInlineSecrets(raw)
	}
	if u.User != nil {
		u.User = url.User(Redacted)
	}
	q := u.Query()
	for k := range q {
		if signedQueryKeys[strings.ToLower(k)] || strings.Contains(strings.ToLower(k), "token") || strings.Contains(strings.ToLower(k), "secret") {
			q.Set(k, Redacted)
		}
	}
	u.RawQuery = q.Encode()
	return u.String()
}

var inlineSecretPattern = regexp.MustCompile(`(?i)(token|secret|password|signature|authorization)=([^\s&]+)`)

func RedactInlineSecrets(value string) string {
	return inlineSecretPattern.ReplaceAllString(value, "$1="+Redacted)
}

func RedactSupportValue(v any) any {
	switch x := v.(type) {
	case string:
		return RedactInlineSecrets(SafeURL(x))
	case map[string]string:
		return RedactHeaders(x)
	case map[string]any:
		out := map[string]any{}
		for k, val := range x {
			lk := strings.ToLower(k)
			if strings.Contains(lk, "secret") || strings.Contains(lk, "token") || strings.Contains(lk, "password") || strings.Contains(lk, "credential") {
				out[k] = Redacted
			} else {
				out[k] = RedactSupportValue(val)
			}
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, val := range x {
			out[i] = RedactSupportValue(val)
		}
		return out
	default:
		return v
	}
}
