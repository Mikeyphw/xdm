package failure

// DonorMapping is executable migration documentation. MatchKey is a stable
// semantic identifier from donor code/tests, not a localized exception string.
type DonorMapping struct {
	Donor      string           `json:"donor"`
	MatchKey   string           `json:"match_key"`
	Category   Category         `json:"category"`
	Retry      RetryDisposition `json:"retry"`
	UserAction UserAction       `json:"user_action"`
}

var donorMappings = []DonorMapping{
	{"android", "NETWORK_UNAVAILABLE", NetworkUnavailable, RetryBackoff, ActionNone},
	{"android", "PRIVATE_ROUTE_REJECTED", RouteRejected, RetryHold, ActionApproveRoute},
	{"android", "TLS_FAILURE", TLSFailure, RetryNever, ActionRetryManually},
	{"android", "AUTHENTICATION_REQUIRED", AuthenticationRequired, RetryHold, ActionCredentials},
	{"android", "RANGE_CONTRADICTION", RangeContradiction, RetryNever, ActionRetryManually},
	{"android", "STORAGE_UNAVAILABLE", StorageUnavailable, RetryHold, ActionFreeStorage},
	{"android", "INTEGRITY_FAILURE", IntegrityFailure, RetryNever, ActionRetryManually},
	{"android", "BACKEND_UNAVAILABLE", BackendUnavailable, RetryBackoff, ActionChooseBackend},
	{"desktop", "DNS_FAILURE", DNSFailure, RetryBackoff, ActionNone},
	{"desktop", "RATE_LIMITED", RateLimited, RetryAtTime, ActionNone},
	{"desktop", "REPRESENTATION_CHANGED", RepresentationChanged, RetryNever, ActionRetryManually},
	{"desktop", "MEDIA_UNSUPPORTED", MediaUnsupported, RetryNever, ActionChooseMedia},
	{"desktop", "EXTERNAL_TOOL_FAILURE", ExternalToolFailure, RetryBackoff, ActionRetryManually},
}

func DonorMappings() []DonorMapping { return append([]DonorMapping(nil), donorMappings...) }
