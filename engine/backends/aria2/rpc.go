package aria2

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

const (
	defaultTimeout   = 5 * time.Second
	maxResponseBytes = 2 << 20
	maxListCount     = 1000
	jsonRPCVersion   = "2.0"
	jsonContentType  = "application/json; charset=utf-8"
)

var (
	ErrInvalidEndpoint   = errors.New("invalid aria2 RPC endpoint")
	ErrInvalidArgument   = errors.New("invalid aria2 RPC argument")
	ErrHTTPStatus        = errors.New("aria2 RPC HTTP status failure")
	ErrTimeout           = errors.New("aria2 RPC timeout")
	ErrMalformedResponse = errors.New("malformed aria2 RPC response")
	ErrCorrelation       = errors.New("aria2 RPC response ID mismatch")
	ErrRemote            = errors.New("aria2 RPC remote error")
)

// HTTPStatusError describes an HTTP-layer failure without copying a possibly
// secret-bearing response body into diagnostics.
type HTTPStatusError struct {
	StatusCode int
}

func (e *HTTPStatusError) Error() string {
	return fmt.Sprintf("%v: status=%d", ErrHTTPStatus, e.StatusCode)
}
func (e *HTTPStatusError) Unwrap() error { return ErrHTTPStatus }

// TimeoutError identifies the RPC method whose bounded call timed out.
type TimeoutError struct {
	Method string
}

func (e *TimeoutError) Error() string { return fmt.Sprintf("%v: method=%s", ErrTimeout, e.Method) }
func (e *TimeoutError) Unwrap() error { return ErrTimeout }

// ProtocolError is returned for malformed JSON-RPC envelopes/results. Detail is
// intentionally structural and never includes the raw response body.
type ProtocolError struct {
	Kind   error
	Method string
	Detail string
}

func (e *ProtocolError) Error() string {
	if e.Method == "" {
		return fmt.Sprintf("%v: %s", e.Kind, e.Detail)
	}
	return fmt.Sprintf("%v: method=%s %s", e.Kind, e.Method, e.Detail)
}
func (e *ProtocolError) Unwrap() error { return e.Kind }

// RPCError is an aria2 JSON-RPC error reply. The server message is redacted
// against the configured token before this value is returned.
type RPCError struct {
	Code    int
	Message string
}

func (e *RPCError) Error() string {
	return fmt.Sprintf("%v: code=%d message=%s", ErrRemote, e.Code, e.Message)
}
func (e *RPCError) Unwrap() error { return ErrRemote }

// OptionValue is the restricted JSON value family accepted by aria2 options.
// aria2 options are textual, with a small number (notably header) accepting an
// array of strings. Keeping this typed prevents accidental JSON numbers/bools
// from drifting between hosts.
type OptionValue struct {
	scalar *string
	list   []string
}

func StringOption(value string) OptionValue {
	v := value
	return OptionValue{scalar: &v}
}

func StringsOption(values ...string) OptionValue {
	return OptionValue{list: append([]string(nil), values...)}
}

func (v OptionValue) MarshalJSON() ([]byte, error) {
	if v.scalar != nil && v.list != nil {
		return nil, fmt.Errorf("%w: option cannot be scalar and list", ErrInvalidArgument)
	}
	if v.scalar != nil {
		if err := validateOptionText(*v.scalar); err != nil {
			return nil, err
		}
		return json.Marshal(*v.scalar)
	}
	if v.list != nil {
		for _, item := range v.list {
			if err := validateOptionText(item); err != nil {
				return nil, err
			}
		}
		return json.Marshal(v.list)
	}
	return nil, fmt.Errorf("%w: empty option value", ErrInvalidArgument)
}

// Options is deterministic by virtue of encoding/json's sorted map keys.
type Options map[string]OptionValue

func (o Options) validate() error {
	keys := make([]string, 0, len(o))
	for key := range o {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		value := o[key]
		if strings.TrimSpace(key) == "" || strings.ContainsAny(key, "\r\n\x00") {
			return fmt.Errorf("%w: unsafe option key", ErrInvalidArgument)
		}
		if _, err := value.MarshalJSON(); err != nil {
			return fmt.Errorf("%w: option=%s", err, key)
		}
	}
	return nil
}

func validateOptionText(value string) error {
	if strings.ContainsAny(value, "\r\n\x00") {
		return fmt.Errorf("%w: unsafe option text", ErrInvalidArgument)
	}
	return nil
}

type Client struct {
	endpoint   *url.URL
	secret     string
	httpClient *http.Client
	timeout    time.Duration
	nextID     atomic.Uint64
}

// NewClient constructs a JSON-RPC client only. It never starts, owns, or
// restarts an aria2 process; runtime ownership belongs to a later lifecycle.
func NewClient(endpoint string, secret string, httpClient *http.Client, timeout time.Duration) (*Client, error) {
	u, err := url.Parse(strings.TrimSpace(endpoint))
	if err != nil || u == nil || !u.IsAbs() || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, ErrInvalidEndpoint
	}
	if u.User != nil || u.Fragment != "" || u.RawQuery != "" {
		return nil, ErrInvalidEndpoint
	}
	if timeout <= 0 {
		timeout = defaultTimeout
	}
	base := httpClient
	if base == nil {
		base = http.DefaultClient
	}
	copyClient := *base
	// Never forward the RPC token across an HTTP redirect. aria2 endpoints are
	// exact process-local control endpoints, not navigational URLs.
	copyClient.CheckRedirect = func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }
	return &Client{endpoint: u, secret: strings.TrimSpace(secret), httpClient: &copyClient, timeout: timeout}, nil
}

type rpcRequest struct {
	JSONRPC string `json:"jsonrpc"`
	ID      string `json:"id"`
	Method  string `json:"method"`
	Params  []any  `json:"params"`
}

type rpcErrorEnvelope struct {
	Code    *int   `json:"code"`
	Message string `json:"message"`
}

type rpcResponse struct {
	JSONRPC string            `json:"jsonrpc"`
	ID      json.RawMessage   `json:"id"`
	Result  json.RawMessage   `json:"result"`
	Error   *rpcErrorEnvelope `json:"error"`
}

func (c *Client) invoke(ctx context.Context, method string, params []any, out any) error {
	if c == nil || c.endpoint == nil || c.httpClient == nil {
		return fmt.Errorf("%w: nil client", ErrInvalidArgument)
	}
	method = strings.TrimSpace(method)
	if method == "" {
		return fmt.Errorf("%w: empty method", ErrInvalidArgument)
	}
	id := strconv.FormatUint(c.nextID.Add(1), 10)
	authenticated := make([]any, 0, len(params)+1)
	if c.secret != "" {
		authenticated = append(authenticated, "token:"+c.secret)
	}
	authenticated = append(authenticated, params...)
	payload, err := json.Marshal(rpcRequest{JSONRPC: jsonRPCVersion, ID: id, Method: method, Params: authenticated})
	if err != nil {
		return fmt.Errorf("%w: method=%s encode request", ErrInvalidArgument, method)
	}
	callCtx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(callCtx, http.MethodPost, c.endpoint.String(), bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("%w: method=%s build request", ErrInvalidArgument, method)
	}
	req.Header.Set("Content-Type", jsonContentType)
	resp, err := c.httpClient.Do(req)
	if err != nil {
		if errors.Is(callCtx.Err(), context.DeadlineExceeded) {
			return &TimeoutError{Method: method}
		}
		if errors.Is(callCtx.Err(), context.Canceled) {
			return callCtx.Err()
		}
		return fmt.Errorf("aria2 RPC transport: method=%s: %w", method, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return &HTTPStatusError{StatusCode: resp.StatusCode}
	}
	limited := io.LimitReader(resp.Body, maxResponseBytes+1)
	raw, err := io.ReadAll(limited)
	if err != nil {
		return fmt.Errorf("aria2 RPC read: method=%s: %w", method, err)
	}
	if len(raw) > maxResponseBytes {
		return &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "response exceeds size limit"}
	}
	var envelope rpcResponse
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "invalid JSON"}
	}
	if envelope.JSONRPC != jsonRPCVersion {
		return &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "jsonrpc must be 2.0"}
	}
	responseID, err := normalizeResponseID(envelope.ID)
	if err != nil {
		return &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "invalid response id"}
	}
	if responseID != id {
		return &ProtocolError{Kind: ErrCorrelation, Method: method, Detail: "response id does not match request"}
	}
	if envelope.Error != nil && envelope.Result != nil {
		return &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "response contains both result and error"}
	}
	if envelope.Error != nil {
		if envelope.Error.Code == nil || strings.TrimSpace(envelope.Error.Message) == "" {
			return &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "malformed error object"}
		}
		message := c.redact(strings.TrimSpace(envelope.Error.Message))
		return &RPCError{Code: *envelope.Error.Code, Message: message}
	}
	if envelope.Result == nil {
		return &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "response missing result"}
	}
	if out == nil {
		return nil
	}
	if err := json.Unmarshal(envelope.Result, out); err != nil {
		return &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "malformed result"}
	}
	return nil
}

func normalizeResponseID(raw json.RawMessage) (string, error) {
	if len(raw) == 0 || bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		return "", errors.New("missing id")
	}
	var s string
	if err := json.Unmarshal(raw, &s); err != nil {
		return "", errors.New("response id is not a JSON string")
	}
	if strings.TrimSpace(s) == "" {
		return "", errors.New("empty id")
	}
	return s, nil
}

func (c *Client) redact(message string) string {
	if c.secret == "" {
		return message
	}
	message = strings.ReplaceAll(message, "token:"+c.secret, "<redacted-token>")
	message = strings.ReplaceAll(message, c.secret, "<redacted>")
	return message
}
