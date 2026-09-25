package router

import (
	"fmt"
	"net/url"
	"strings"

	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/security/transportpolicy"
)

type exactBackend struct{ kind Kind }

func NativeBackend() Backend { return exactBackend{kind: Native} }
func Aria2Backend() Backend  { return exactBackend{kind: Aria2} }

func (b exactBackend) Kind() Kind { return b.kind }

func schemeOf(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	return strings.ToLower(u.Scheme)
}

func addIssue(out *CompatibilityResult, reason CompatibilityReason, detail string) {
	out.Rejects = append(out.Rejects, CompatibilityIssue{Reason: reason, Detail: detail})
}

func nativeCredentialCompatible(scheme string, c domainrequest.CredentialReference, proxy transportpolicy.ProxyMode) bool {
	switch scheme {
	case "http", "https":
		switch c.Kind {
		case domainrequest.CredentialAuthorization, domainrequest.CredentialCookie, domainrequest.CredentialAPIKey:
			return true
		case domainrequest.CredentialProxyAuthorization:
			return proxy == transportpolicy.ProxyHTTP
		default:
			return false
		}
	case "ftp", "ftps":
		return c.Kind == domainrequest.CredentialFTPPassword
	default:
		return false
	}
}

func nativeResumeCapable(op Operation, scheme string) bool {
	if op.Request.Method != "GET" || op.Request.Body != nil {
		return false
	}
	switch scheme {
	case "http", "https":
		return op.Request.RangeResumeAllowed()
	case "ftp", "ftps":
		return true
	default:
		return false
	}
}

func (b exactBackend) CanExecute(raw Operation) CompatibilityResult {
	out := CompatibilityResult{Backend: b.kind}
	op, err := NewOperation(raw)
	if err != nil {
		addIssue(&out, ReasonProtocol, "operation is not a valid canonical network intent")
		out.Compatible = false
		return out
	}
	scheme := schemeOf(op.Request.TransportURL)

	switch b.kind {
	case Native:
		if scheme != "http" && scheme != "https" && scheme != "ftp" && scheme != "ftps" {
			addIssue(&out, ReasonProtocol, fmt.Sprintf("native does not execute %s", scheme))
		}
		if (scheme == "ftp" || scheme == "ftps") && (op.Request.Method != "GET" || op.Request.Body != nil) {
			addIssue(&out, ReasonMethodBody, "native FTP/FTPS executes bodyless GET only")
		}
		if (scheme == "http" || scheme == "https") && op.Request.Method != "GET" && op.Request.Method != "POST" {
			addIssue(&out, ReasonMethodBody, "native HTTP executes GET or POST only")
		}
		if op.Requirements.Destination != DestinationStaging && op.Requirements.Destination != DestinationPlatformStream {
			addIssue(&out, ReasonDestination, "native requires staging or platform stream destination")
		}
		for _, c := range op.Request.Credentials {
			if !nativeCredentialCompatible(scheme, c, op.Requirements.Proxy) {
				addIssue(&out, ReasonCredentialMode, fmt.Sprintf("native %s cannot execute credential kind %s", scheme, c.Kind))
				break
			}
		}
		switch scheme {
		case "http", "https":
			if op.Requirements.Proxy != transportpolicy.ProxyDirect && op.Requirements.Proxy != transportpolicy.ProxyHTTP && op.Requirements.Proxy != transportpolicy.ProxySOCKS {
				addIssue(&out, ReasonProxy, "native HTTP requires a resolved direct/HTTP/SOCKS proxy decision")
			}
		case "ftp", "ftps":
			if op.Requirements.Proxy != transportpolicy.ProxyDirect {
				addIssue(&out, ReasonProxy, "native FTP/FTPS adapter is direct-connect only")
			}
		}
		if op.Requirements.Media != MediaDirectFile && op.Requirements.Media != MediaDirectMedia {
			addIssue(&out, ReasonMediaShape, "native transfer backend does not execute adaptive/external media workflows")
		}
		if op.Requirements.Resume == ResumeRequired && !nativeResumeCapable(op, scheme) {
			addIssue(&out, ReasonResume, "required byte resume is not compatible with this native request")
		}
		if op.Requirements.SelectiveRepair && (scheme != "http" && scheme != "https" || !op.Request.RangeResumeAllowed()) {
			addIssue(&out, ReasonResume, "selective repair requires resumable native HTTP GET")
		}
		if len(op.Request.Mirrors) > 0 {
			addIssue(&out, ReasonMirrorSemantics, "native adapters do not own multi-source mirror scheduling")
		}
		if op.Request.Method == "POST" {
			out.Hints = append(out.Hints, HintNativePOST)
		}
		if scheme == "ftps" {
			out.Hints = append(out.Hints, HintNativeFTPS)
		}
		if len(op.Request.Credentials) > 0 || len(op.Request.Headers) > 0 {
			out.Hints = append(out.Hints, HintNativeCredential)
		}
		if op.Requirements.Destination == DestinationPlatformStream {
			out.Hints = append(out.Hints, HintNativeDestination)
		}
		if op.Requirements.Media == MediaDirectMedia {
			out.Hints = append(out.Hints, HintNativeDirectMedia)
		}

	case Aria2:
		if scheme != "http" && scheme != "https" && scheme != "ftp" {
			addIssue(&out, ReasonProtocol, fmt.Sprintf("aria2 contract does not execute %s", scheme))
		}
		if op.Request.Method != "GET" || op.Request.Body != nil {
			addIssue(&out, ReasonMethodBody, "aria2 routing executes bodyless GET only")
		}
		if op.Requirements.Destination != DestinationStaging {
			addIssue(&out, ReasonDestination, "aria2 writes only engine-owned staging destinations")
		}
		if len(op.Request.Credentials) > 0 || len(op.Request.Headers) > 0 {
			addIssue(&out, ReasonCredentialMode, "aria2 compatibility excludes captured headers and credential references")
		}
		if op.Requirements.Proxy != transportpolicy.ProxyDirect {
			addIssue(&out, ReasonProxy, "aria2 compatibility requires direct routing until proxy option execution is canonical")
		}
		if op.Requirements.Media != MediaDirectFile && op.Requirements.Media != MediaDirectMedia {
			addIssue(&out, ReasonMediaShape, "aria2 backend is not the adaptive/external media pipeline")
		}
		if op.Requirements.Resume == ResumeRequired && (op.Request.Method != "GET" || op.Request.Body != nil) {
			addIssue(&out, ReasonResume, "aria2 resume requires bodyless GET")
		}
		if op.Requirements.SelectiveRepair {
			addIssue(&out, ReasonResume, "aria2 does not expose canonical selective-repair checkpoints")
		}
		if len(op.Request.Mirrors) > 0 {
			out.Hints = append(out.Hints, HintAria2Mirrors)
		}
		if scheme == "ftp" {
			out.Hints = append(out.Hints, HintAria2FTP)
		}
		if op.Request.ExpectedLength != nil && *op.Request.ExpectedLength >= 512*1024*1024 {
			out.Hints = append(out.Hints, HintAria2Large)
		}
	default:
		addIssue(&out, ReasonProtocol, "unknown backend")
	}

	sortIssues(out.Rejects)
	out.Compatible = len(out.Rejects) == 0
	return out
}

func (b exactBackend) Preflight(op Operation) error {
	result := b.CanExecute(op)
	if result.Compatible {
		return nil
	}
	issue := CompatibilityIssue{Reason: ReasonProtocol, Detail: "incompatible operation"}
	if len(result.Rejects) > 0 {
		issue = result.Rejects[0]
	}
	return &PreflightError{Backend: b.kind, Issue: issue}
}
