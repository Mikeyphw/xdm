package request

import (
	"errors"
	"fmt"
	"strings"
	"unicode"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/resource"
)

var (
	ErrInvalidRequest = errors.New("invalid request")
	ErrInvalidRef     = errors.New("invalid opaque reference")
	ErrRevisionStep   = errors.New("request revision must advance exactly once")
)

// SecretReference is an opaque handle to secret material. It is not the secret
// itself; resolution belongs to the platform/secret broker introduced later.
type SecretReference string

// BodyReference is an opaque handle to replayable request-body material.
type BodyReference string

func parseReference(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" || len(value) > 256 {
		return "", ErrInvalidRef
	}
	for _, r := range value {
		if unicode.IsControl(r) || unicode.IsSpace(r) {
			return "", ErrInvalidRef
		}
	}
	return value, nil
}

func NewSecretReference(value string) (SecretReference, error) {
	parsed, err := parseReference(value)
	return SecretReference(parsed), err
}

func NewBodyReference(value string) (BodyReference, error) {
	parsed, err := parseReference(value)
	return BodyReference(parsed), err
}

// Spec is deliberately smaller than the future canonical network intent. It
// establishes aggregate ownership without prematurely freezing XGO-20 fields.
type Spec struct {
	Resource   resource.Identity
	Method     string
	SecretRefs []SecretReference
	BodyRef    *BodyReference
}

type Request struct {
	id       identity.RequestID
	revision identity.Revision
	spec     Spec
}

func normalizeSpec(spec Spec) (Spec, error) {
	if spec.Resource.IsZero() {
		return Spec{}, fmt.Errorf("%w: zero resource identity", ErrInvalidRequest)
	}
	method := strings.ToUpper(strings.TrimSpace(spec.Method))
	if method == "" {
		method = "GET"
	}
	for _, r := range method {
		if !(r >= 'A' && r <= 'Z') && r != '-' {
			return Spec{}, fmt.Errorf("%w: invalid method", ErrInvalidRequest)
		}
	}
	refs := make([]SecretReference, len(spec.SecretRefs))
	copy(refs, spec.SecretRefs)
	for _, ref := range refs {
		if _, err := parseReference(string(ref)); err != nil {
			return Spec{}, fmt.Errorf("%w: secret reference", err)
		}
	}
	var body *BodyReference
	if spec.BodyRef != nil {
		if _, err := parseReference(string(*spec.BodyRef)); err != nil {
			return Spec{}, fmt.Errorf("%w: body reference", err)
		}
		copyRef := *spec.BodyRef
		body = &copyRef
	}
	return Spec{Resource: spec.Resource, Method: method, SecretRefs: refs, BodyRef: body}, nil
}

func New(id identity.RequestID, revision identity.Revision, spec Spec) (Request, error) {
	if id.IsZero() || !revision.Valid() {
		return Request{}, ErrInvalidRequest
	}
	normalized, err := normalizeSpec(spec)
	if err != nil {
		return Request{}, err
	}
	return Request{id: id, revision: revision, spec: normalized}, nil
}

func (r Request) ID() identity.RequestID      { return r.id }
func (r Request) Revision() identity.Revision { return r.revision }
func (r Request) Spec() Spec {
	spec := r.spec
	spec.SecretRefs = append([]SecretReference(nil), r.spec.SecretRefs...)
	if r.spec.BodyRef != nil {
		v := *r.spec.BodyRef
		spec.BodyRef = &v
	}
	return spec
}

// Revise creates a new immutable request value and leaves the receiver intact.
func (r Request) Revise(next identity.Revision, spec Spec) (Request, error) {
	expected, err := r.revision.Next()
	if err != nil {
		return Request{}, err
	}
	if next != expected {
		return Request{}, ErrRevisionStep
	}
	return New(r.id, next, spec)
}
