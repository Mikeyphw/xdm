package ops

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

func TestSecretBrokerRedactionAndTimeout(t *testing.T) {
	now := time.Unix(10, 0)
	broker := NewSecretBroker(time.Minute, func() time.Time { return now })
	ref := SecretRef{ID: "download-cookie", Scope: "https://example.test/path", Generation: 1}
	v, err := broker.Resolve(context.Background(), ref, func(context.Context, SecretRef) (string, error) { return "super-secret", nil })
	if err != nil || v != "super-secret" {
		t.Fatalf("resolve failed %q %v", v, err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err = broker.Resolve(ctx, SecretRef{ID: "other", Scope: "s", Generation: 1}, func(context.Context, SecretRef) (string, error) { return "raw-token", nil })
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected cancel, got %v", err)
	}
	headers := RedactHeaders(map[string]string{"Authorization": "Bearer abc", "Accept": "video/mp4", "X-Trace": "token=raw-token"})
	if headers["Authorization"] != Redacted || strings.Contains(headers["X-Trace"], "raw-token") || headers["Accept"] != "video/mp4" {
		t.Fatalf("bad redaction: %+v", headers)
	}
	url := SafeURL("https://user:pass@example.test/file?sig=abc&name=ok&token=raw-token")
	if strings.Contains(url, "abc") || strings.Contains(url, "raw-token") || strings.Contains(url, "pass") {
		t.Fatalf("url leaked: %s", url)
	}
}
