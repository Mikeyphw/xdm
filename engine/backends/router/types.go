package router

import (
	"errors"
	"fmt"
	"sort"
	"strings"

	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/security/transportpolicy"
)

var (
	ErrInvalidOperation = errors.New("invalid backend operation")
	ErrIncompatible     = errors.New("backend is incompatible with operation")
	ErrNoBackend        = errors.New("no backend can start operation")
	ErrAttemptStarted   = errors.New("backend selection cannot change after attempt starts")
)

type Kind string

const (
	Native Kind = "native"
	Aria2  Kind = "aria2"
)

func (k Kind) Valid() bool { return k == Native || k == Aria2 }

func Kinds() []Kind { return []Kind{Native, Aria2} }

type DestinationKind string

const (
	DestinationStaging        DestinationKind = "staging"
	DestinationPlatformStream DestinationKind = "platform_stream"
)

type MediaShape string

const (
	MediaDirectFile       MediaShape = "direct_file"
	MediaDirectMedia      MediaShape = "direct_media"
	MediaAdaptivePlaylist MediaShape = "adaptive_playlist"
	MediaExternalTool     MediaShape = "external_tool"
)

type ResumeRequirement string

const (
	ResumeNone     ResumeRequirement = "none"
	ResumeOptional ResumeRequirement = "optional"
	ResumeRequired ResumeRequirement = "required"
)

type Requirements struct {
	Destination     DestinationKind           `json:"destination"`
	Proxy           transportpolicy.ProxyMode `json:"proxy"`
	Media           MediaShape                `json:"media_shape"`
	Resume          ResumeRequirement         `json:"resume"`
	SelectiveRepair bool                      `json:"selective_repair,omitempty"`
}

type Operation struct {
	Request      domainrequest.NetworkIntent `json:"request"`
	Requirements Requirements                `json:"requirements"`
}

func NewOperation(in Operation) (Operation, error) {
	req, err := domainrequest.NewNetworkIntent(in.Request)
	if err != nil {
		return Operation{}, fmt.Errorf("%w: %v", ErrInvalidOperation, err)
	}
	in.Request = req
	if in.Requirements.Destination == "" {
		in.Requirements.Destination = DestinationStaging
	}
	if in.Requirements.Proxy == "" {
		in.Requirements.Proxy = transportpolicy.ProxyDirect
	}
	if in.Requirements.Media == "" {
		in.Requirements.Media = MediaDirectFile
	}
	if in.Requirements.Resume == "" {
		in.Requirements.Resume = ResumeOptional
	}
	if in.Requirements.Destination != DestinationStaging && in.Requirements.Destination != DestinationPlatformStream {
		return Operation{}, fmt.Errorf("%w: destination=%s", ErrInvalidOperation, in.Requirements.Destination)
	}
	switch in.Requirements.Media {
	case MediaDirectFile, MediaDirectMedia, MediaAdaptivePlaylist, MediaExternalTool:
	default:
		return Operation{}, fmt.Errorf("%w: media_shape=%s", ErrInvalidOperation, in.Requirements.Media)
	}
	switch in.Requirements.Resume {
	case ResumeNone, ResumeOptional, ResumeRequired:
	default:
		return Operation{}, fmt.Errorf("%w: resume=%s", ErrInvalidOperation, in.Requirements.Resume)
	}
	switch in.Requirements.Proxy {
	case transportpolicy.ProxyDirect, transportpolicy.ProxyHTTP, transportpolicy.ProxySOCKS, transportpolicy.ProxySystem, transportpolicy.ProxyPACResolved:
	default:
		return Operation{}, fmt.Errorf("%w: proxy=%s", ErrInvalidOperation, in.Requirements.Proxy)
	}
	return in, nil
}

type CompatibilityReason string

const (
	ReasonProtocol        CompatibilityReason = "protocol"
	ReasonMethodBody      CompatibilityReason = "method_body"
	ReasonDestination     CompatibilityReason = "destination"
	ReasonCredentialMode  CompatibilityReason = "credential_mode"
	ReasonProxy           CompatibilityReason = "proxy"
	ReasonMediaShape      CompatibilityReason = "media_shape"
	ReasonResume          CompatibilityReason = "resume"
	ReasonMirrorSemantics CompatibilityReason = "mirror_semantics"
)

type CompatibilityIssue struct {
	Reason CompatibilityReason `json:"reason"`
	Detail string              `json:"detail"`
}

type PreferenceHint string

const (
	HintNativePOST        PreferenceHint = "native_post"
	HintNativeFTPS        PreferenceHint = "native_ftps"
	HintNativeCredential  PreferenceHint = "native_credentials"
	HintNativeDestination PreferenceHint = "native_platform_destination"
	HintNativeDirectMedia PreferenceHint = "native_direct_media"
	HintAria2Mirrors      PreferenceHint = "aria2_mirrors"
	HintAria2FTP          PreferenceHint = "aria2_ftp"
	HintAria2Large        PreferenceHint = "aria2_large_transfer"
)

type CompatibilityResult struct {
	Backend    Kind                 `json:"backend"`
	Compatible bool                 `json:"compatible"`
	Rejects    []CompatibilityIssue `json:"rejects,omitempty"`
	Hints      []PreferenceHint     `json:"hints,omitempty"`
}

func (r CompatibilityResult) FirstReason() CompatibilityReason {
	if len(r.Rejects) == 0 {
		return ""
	}
	return r.Rejects[0].Reason
}

type PreflightError struct {
	Backend Kind
	Issue   CompatibilityIssue
}

func (e *PreflightError) Error() string {
	return fmt.Sprintf("%v: backend=%s reason=%s detail=%s", ErrIncompatible, e.Backend, e.Issue.Reason, e.Issue.Detail)
}
func (e *PreflightError) Unwrap() error { return ErrIncompatible }

type Backend interface {
	Kind() Kind
	CanExecute(Operation) CompatibilityResult
	Preflight(Operation) error
}

func normalizedPreference(raw string) (Kind, bool, error) {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "", "automatic", "auto":
		return "", false, nil
	case string(Native):
		return Native, true, nil
	case string(Aria2):
		return Aria2, true, nil
	default:
		return "", false, fmt.Errorf("%w: backend_preference=%q", ErrInvalidOperation, raw)
	}
}

func sortIssues(in []CompatibilityIssue) {
	order := map[CompatibilityReason]int{
		ReasonProtocol: 0, ReasonMethodBody: 1, ReasonDestination: 2, ReasonCredentialMode: 3,
		ReasonProxy: 4, ReasonMediaShape: 5, ReasonResume: 6, ReasonMirrorSemantics: 7,
	}
	sort.SliceStable(in, func(i, j int) bool {
		if order[in[i].Reason] == order[in[j].Reason] {
			return in[i].Detail < in[j].Detail
		}
		return order[in[i].Reason] < order[in[j].Reason]
	})
}
