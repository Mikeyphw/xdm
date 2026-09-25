package httptransfer

import (
	"errors"
	"fmt"
	"strings"

	"github.com/subhra74/xdm/engine/domain/resource"
)

var ErrInvalidRepresentation = errors.New("invalid representation identity")

type IdentityStrength string

const (
	IdentityStrongETag         IdentityStrength = "strong_etag"
	IdentityLastModifiedLength IdentityStrength = "last_modified_length"
	IdentityLengthOnly         IdentityStrength = "length_only"
	IdentityUnknown            IdentityStrength = "unknown"
)

type Representation struct {
	Resource          resource.Identity `json:"resource"`
	EffectiveResource resource.Identity `json:"effective_resource"`
	EffectiveURL      string            `json:"effective_url"`
	Length            *int64            `json:"length,omitempty"`
	ETag              string            `json:"etag,omitempty"`
	LastModified      string            `json:"last_modified,omitempty"`
	WeakETag          bool              `json:"weak_etag"`
	Strength          IdentityStrength  `json:"strength"`
}

func strongETag(v string) bool {
	v = strings.TrimSpace(v)
	return v != "" && !strings.HasPrefix(strings.ToLower(v), "w/")
}

func RepresentationFromProbe(logical resource.Identity, p ProbeResult) (Representation, error) {
	if logical.IsZero() || p.EffectiveURL == "" {
		return Representation{}, ErrInvalidRepresentation
	}
	effective, err := resource.DeriveIdentity("http-effective-v1", p.EffectiveURL)
	if err != nil {
		return Representation{}, err
	}
	r := Representation{
		Resource: logical, EffectiveResource: effective, EffectiveURL: p.EffectiveURL,
		Length: p.Length, ETag: strings.TrimSpace(p.ETag), LastModified: strings.TrimSpace(p.LastModified),
		WeakETag: p.WeakETag || strings.HasPrefix(strings.ToLower(strings.TrimSpace(p.ETag)), "w/"), Strength: IdentityUnknown,
	}
	switch {
	case r.WeakETag:
		r.Strength = IdentityUnknown
	case strongETag(r.ETag):
		r.Strength = IdentityStrongETag
	case r.LastModified != "" && r.Length != nil:
		r.Strength = IdentityLastModifiedLength
	case r.Length != nil:
		r.Strength = IdentityLengthOnly
	}
	return r, nil
}

type ResumePolicy struct {
	AllowLastModifiedLength bool `json:"allow_last_modified_length"`
	AllowLengthOnly         bool `json:"allow_length_only"`
	AllowMirrorChange       bool `json:"allow_mirror_change"`
}

type ResumeReason string

const (
	ResumeStrongETagMatch      ResumeReason = "strong_etag_match"
	ResumeLastModifiedMatch    ResumeReason = "last_modified_length_match"
	ResumeLengthOnlyMatch      ResumeReason = "length_only_match"
	ResumeDifferentResource    ResumeReason = "effective_resource_changed"
	ResumeValidatorChanged     ResumeReason = "validator_changed"
	ResumeLengthChanged        ResumeReason = "length_changed"
	ResumeWeakValidator        ResumeReason = "weak_or_missing_validator"
	ResumeWeakerPolicyDisabled ResumeReason = "weaker_identity_policy_disabled"
)

type ResumeDecision struct {
	Allowed bool         `json:"allowed"`
	Reason  ResumeReason `json:"reason"`
}

func sameLength(a, b *int64) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return *a == *b
}

func CanResume(previous, current Representation, policy ResumePolicy) ResumeDecision {
	if previous.Resource.IsZero() || current.Resource.IsZero() || previous.Resource != current.Resource {
		return ResumeDecision{false, ResumeDifferentResource}
	}
	mirrorChanged := previous.EffectiveResource != current.EffectiveResource
	if mirrorChanged && !policy.AllowMirrorChange {
		return ResumeDecision{false, ResumeDifferentResource}
	}
	if previous.Strength == IdentityStrongETag || current.Strength == IdentityStrongETag {
		if previous.Strength != IdentityStrongETag || current.Strength != IdentityStrongETag || previous.ETag != current.ETag {
			return ResumeDecision{false, ResumeValidatorChanged}
		}
		if previous.Length != nil && current.Length != nil && !sameLength(previous.Length, current.Length) {
			return ResumeDecision{false, ResumeLengthChanged}
		}
		return ResumeDecision{true, ResumeStrongETagMatch}
	}
	if previous.Strength == IdentityLastModifiedLength && current.Strength == IdentityLastModifiedLength {
		if !policy.AllowLastModifiedLength {
			return ResumeDecision{false, ResumeWeakerPolicyDisabled}
		}
		if previous.LastModified != current.LastModified {
			return ResumeDecision{false, ResumeValidatorChanged}
		}
		if !sameLength(previous.Length, current.Length) {
			return ResumeDecision{false, ResumeLengthChanged}
		}
		return ResumeDecision{true, ResumeLastModifiedMatch}
	}
	if previous.Strength == IdentityLengthOnly && current.Strength == IdentityLengthOnly {
		if !policy.AllowLengthOnly {
			return ResumeDecision{false, ResumeWeakerPolicyDisabled}
		}
		if !sameLength(previous.Length, current.Length) {
			return ResumeDecision{false, ResumeLengthChanged}
		}
		return ResumeDecision{true, ResumeLengthOnlyMatch}
	}
	return ResumeDecision{false, ResumeWeakValidator}
}

func (r Representation) Validate() error {
	if r.Resource.IsZero() || r.EffectiveResource.IsZero() || r.EffectiveURL == "" {
		return ErrInvalidRepresentation
	}
	if r.Length != nil && *r.Length < 0 {
		return fmt.Errorf("%w: negative length", ErrInvalidRepresentation)
	}
	return nil
}
