package failure

import (
	"encoding/json"
	"errors"
	"fmt"
)

var ErrInvalidFailure = errors.New("invalid canonical failure")

type Category string

const (
	NetworkUnavailable     Category = "network_unavailable"
	DNSFailure             Category = "dns_failure"
	RouteRejected          Category = "route_rejected"
	TLSFailure             Category = "tls_failure"
	AuthenticationRequired Category = "authentication_required"
	RateLimited            Category = "rate_limited"
	RepresentationChanged  Category = "representation_changed"
	RangeContradiction     Category = "range_contradiction"
	StorageUnavailable     Category = "storage_unavailable"
	IntegrityFailure       Category = "integrity_failure"
	BackendUnavailable     Category = "backend_unavailable"
	MediaUnsupported       Category = "media_unsupported"
	ExternalToolFailure    Category = "external_tool_failure"
	InvalidRequest         Category = "invalid_request"
	Cancelled              Category = "cancelled"
	InternalFailure        Category = "internal_failure"
)

var categories = []Category{
	NetworkUnavailable, DNSFailure, RouteRejected, TLSFailure,
	AuthenticationRequired, RateLimited, RepresentationChanged,
	RangeContradiction, StorageUnavailable, IntegrityFailure,
	BackendUnavailable, MediaUnsupported, ExternalToolFailure,
	InvalidRequest, Cancelled, InternalFailure,
}

type RetryDisposition string

const (
	RetryNever   RetryDisposition = "never"
	RetryNow     RetryDisposition = "now"
	RetryBackoff RetryDisposition = "backoff"
	RetryAtTime  RetryDisposition = "at_time"
	RetryHold    RetryDisposition = "hold"
)

type UserAction string

const (
	ActionNone          UserAction = "none"
	ActionCredentials   UserAction = "credentials"
	ActionApproveRoute  UserAction = "approve_route"
	ActionFreeStorage   UserAction = "free_storage"
	ActionChooseBackend UserAction = "choose_backend"
	ActionChooseMedia   UserAction = "choose_media"
	ActionRetryManually UserAction = "retry_manually"
)

type Policy struct {
	Retry      RetryDisposition `json:"retry"`
	UserAction UserAction       `json:"user_action"`
}

var defaultPolicy = map[Category]Policy{
	NetworkUnavailable:     {RetryBackoff, ActionNone},
	DNSFailure:             {RetryBackoff, ActionNone},
	RouteRejected:          {RetryHold, ActionApproveRoute},
	TLSFailure:             {RetryNever, ActionRetryManually},
	AuthenticationRequired: {RetryHold, ActionCredentials},
	RateLimited:            {RetryAtTime, ActionNone},
	RepresentationChanged:  {RetryNever, ActionRetryManually},
	RangeContradiction:     {RetryNever, ActionRetryManually},
	StorageUnavailable:     {RetryHold, ActionFreeStorage},
	IntegrityFailure:       {RetryNever, ActionRetryManually},
	BackendUnavailable:     {RetryBackoff, ActionChooseBackend},
	MediaUnsupported:       {RetryNever, ActionChooseMedia},
	ExternalToolFailure:    {RetryBackoff, ActionRetryManually},
	InvalidRequest:         {RetryNever, ActionRetryManually},
	Cancelled:              {RetryNever, ActionNone},
	InternalFailure:        {RetryBackoff, ActionRetryManually},
}

var publicMessages = map[Category]string{
	NetworkUnavailable:     "Network is unavailable.",
	DNSFailure:             "The server name could not be resolved.",
	RouteRejected:          "The destination is blocked by network safety policy.",
	TLSFailure:             "The secure connection could not be established.",
	AuthenticationRequired: "Authentication is required.",
	RateLimited:            "The server asked XDM to retry later.",
	RepresentationChanged:  "The remote file changed while downloading.",
	RangeContradiction:     "The server returned an incompatible byte range.",
	StorageUnavailable:     "The destination storage is unavailable.",
	IntegrityFailure:       "Downloaded data failed integrity verification.",
	BackendUnavailable:     "The selected download backend is unavailable.",
	MediaUnsupported:       "This media protection or format is not supported.",
	ExternalToolFailure:    "An external media tool failed.",
	InvalidRequest:         "The download request is invalid.",
	Cancelled:              "The operation was cancelled.",
	InternalFailure:        "The engine encountered an internal failure.",
}

func Categories() []Category { return append([]Category(nil), categories...) }

func (c Category) Valid() bool {
	_, ok := defaultPolicy[c]
	return ok
}

func DefaultPolicy(c Category) (Policy, bool) {
	p, ok := defaultPolicy[c]
	return p, ok
}

func validateRetry(v RetryDisposition) bool {
	switch v {
	case RetryNever, RetryNow, RetryBackoff, RetryAtTime, RetryHold:
		return true
	default:
		return false
	}
}

func validateAction(v UserAction) bool {
	switch v {
	case ActionNone, ActionCredentials, ActionApproveRoute, ActionFreeStorage, ActionChooseBackend, ActionChooseMedia, ActionRetryManually:
		return true
	default:
		return false
	}
}

type Failure struct {
	Category   Category         `json:"category"`
	Retry      RetryDisposition `json:"retry"`
	UserAction UserAction       `json:"user_action"`
	cause      error
}

func New(category Category, retry RetryDisposition, action UserAction, cause error) (Failure, error) {
	if !category.Valid() || !validateRetry(retry) || !validateAction(action) {
		return Failure{}, ErrInvalidFailure
	}
	return Failure{Category: category, Retry: retry, UserAction: action, cause: cause}, nil
}

func NewDefault(category Category, cause error) (Failure, error) {
	policy, ok := DefaultPolicy(category)
	if !ok {
		return Failure{}, ErrInvalidFailure
	}
	return New(category, policy.Retry, policy.UserAction, cause)
}

func (f Failure) Validate() error {
	if !f.Category.Valid() || !validateRetry(f.Retry) || !validateAction(f.UserAction) {
		return ErrInvalidFailure
	}
	return nil
}

func (f Failure) Error() string {
	if message := publicMessages[f.Category]; message != "" {
		return message
	}
	return "The engine encountered a failure."
}

func (f Failure) PublicMessage() string { return f.Error() }
func (f Failure) Unwrap() error         { return f.cause }

func (f Failure) MarshalJSON() ([]byte, error) {
	if err := f.Validate(); err != nil {
		return nil, err
	}
	type safeFailure struct {
		Category   Category         `json:"category"`
		Retry      RetryDisposition `json:"retry"`
		UserAction UserAction       `json:"user_action"`
		Message    string           `json:"message"`
	}
	return json.Marshal(safeFailure{f.Category, f.Retry, f.UserAction, f.PublicMessage()})
}

func (f *Failure) UnmarshalJSON(data []byte) error {
	var raw struct {
		Category   Category         `json:"category"`
		Retry      RetryDisposition `json:"retry"`
		UserAction UserAction       `json:"user_action"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	next, err := New(raw.Category, raw.Retry, raw.UserAction, nil)
	if err != nil {
		return err
	}
	*f = next
	return nil
}

func (f Failure) GoString() string {
	return fmt.Sprintf("Failure{Category:%q Retry:%q UserAction:%q}", f.Category, f.Retry, f.UserAction)
}
