package resource

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"unicode/utf8"
)

const identityPrefix = "res_"

var (
	ErrInvalidIdentity = errors.New("invalid resource identity")
	ErrEmptyCanonical  = errors.New("resource identity requires non-empty namespace and canonical key")
)

// Identity is a stable opaque key derived from canonical resource material.
// It deliberately does not retain or expose the raw URL/string used to derive
// it, so bearer/query secrets cannot leak through identity formatting.
type Identity string

func DeriveIdentity(namespace, canonicalKey string) (Identity, error) {
	namespace = strings.TrimSpace(namespace)
	if namespace == "" || canonicalKey == "" || !utf8.ValidString(canonicalKey) {
		return "", ErrEmptyCanonical
	}
	digest := sha256.Sum256([]byte(namespace + "\x00" + canonicalKey))
	return Identity(identityPrefix + hex.EncodeToString(digest[:])), nil
}

func ParseIdentity(value string) (Identity, error) {
	if !strings.HasPrefix(value, identityPrefix) {
		return "", ErrInvalidIdentity
	}
	raw := strings.TrimPrefix(value, identityPrefix)
	if len(raw) != sha256.Size*2 || raw != strings.ToLower(raw) {
		return "", ErrInvalidIdentity
	}
	decoded, err := hex.DecodeString(raw)
	if err != nil || len(decoded) != sha256.Size {
		return "", ErrInvalidIdentity
	}
	return Identity(value), nil
}

func (id Identity) String() string { return string(id) }
func (id Identity) IsZero() bool   { return id == "" }
func (id Identity) MarshalJSON() ([]byte, error) {
	if _, err := ParseIdentity(string(id)); err != nil {
		return nil, err
	}
	return json.Marshal(string(id))
}
func (id *Identity) UnmarshalJSON(data []byte) error {
	var value string
	if err := json.Unmarshal(data, &value); err != nil {
		return err
	}
	parsed, err := ParseIdentity(value)
	if err != nil {
		return err
	}
	*id = parsed
	return nil
}

// SensitiveText makes accidental formatting/JSON serialization safe by
// default. Reveal is intentionally explicit and should only be used at the
// narrow transport/platform boundary that genuinely needs the secret value.
type SensitiveText struct {
	value string
}

func NewSensitiveText(value string) SensitiveText { return SensitiveText{value: value} }
func (s SensitiveText) IsZero() bool              { return s.value == "" }
func (s SensitiveText) String() string {
	if s.value == "" {
		return ""
	}
	return "[REDACTED]"
}
func (s SensitiveText) GoString() string             { return s.String() }
func (s SensitiveText) Reveal() string               { return s.value }
func (s SensitiveText) MarshalJSON() ([]byte, error) { return json.Marshal(s.String()) }
func (s SensitiveText) Format(state fmt.State, verb rune) {
	_, _ = fmt.Fprint(state, s.String())
}
