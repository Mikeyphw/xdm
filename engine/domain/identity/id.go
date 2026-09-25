package identity

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/subhra74/xdm/engine/foundation/idgen"
)

var (
	ErrInvalidID = errors.New("invalid canonical identifier")
	ErrZeroID    = errors.New("zero identifier is not a legal domain identity")
)

type TokenSource interface {
	Token() (string, error)
}

func parsePrefixed(value, prefix string) (string, error) {
	if value == "" {
		return "", ErrZeroID
	}
	if !strings.HasPrefix(value, prefix) {
		return "", fmt.Errorf("%w: expected %q prefix", ErrInvalidID, prefix)
	}
	token := strings.TrimPrefix(value, prefix)
	if err := idgen.ValidateToken(token); err != nil {
		return "", fmt.Errorf("%w: %v", ErrInvalidID, err)
	}
	return value, nil
}

func newPrefixed(source TokenSource, prefix string) (string, error) {
	if source == nil {
		return "", errors.New("nil identifier token source")
	}
	token, err := source.Token()
	if err != nil {
		return "", err
	}
	if err := idgen.ValidateToken(token); err != nil {
		return "", fmt.Errorf("token source returned non-canonical token: %w", err)
	}
	return prefix + token, nil
}

func marshalID(value string) ([]byte, error) {
	if value == "" {
		return nil, ErrZeroID
	}
	return json.Marshal(value)
}

func unmarshalID(data []byte, prefix string) (string, error) {
	var value string
	if err := json.Unmarshal(data, &value); err != nil {
		return "", err
	}
	return parsePrefixed(value, prefix)
}

type DownloadID string

func ParseDownloadID(value string) (DownloadID, error) {
	parsed, err := parsePrefixed(value, "dl_")
	return DownloadID(parsed), err
}

func NewDownloadID(source TokenSource) (DownloadID, error) {
	value, err := newPrefixed(source, "dl_")
	return DownloadID(value), err
}

func (id DownloadID) String() string { return string(id) }
func (id DownloadID) IsZero() bool   { return id == "" }
func (id DownloadID) MarshalText() ([]byte, error) {
	if id.IsZero() {
		return nil, ErrZeroID
	}
	if _, err := ParseDownloadID(string(id)); err != nil {
		return nil, err
	}
	return []byte(id), nil
}
func (id *DownloadID) UnmarshalText(data []byte) error {
	parsed, err := ParseDownloadID(string(data))
	if err != nil {
		return err
	}
	*id = parsed
	return nil
}
func (id DownloadID) MarshalJSON() ([]byte, error) {
	if _, err := ParseDownloadID(string(id)); err != nil {
		return nil, err
	}
	return marshalID(string(id))
}
func (id *DownloadID) UnmarshalJSON(data []byte) error {
	value, err := unmarshalID(data, "dl_")
	if err != nil {
		return err
	}
	*id = DownloadID(value)
	return nil
}

type RequestID string

func ParseRequestID(value string) (RequestID, error) {
	parsed, err := parsePrefixed(value, "req_")
	return RequestID(parsed), err
}

func NewRequestID(source TokenSource) (RequestID, error) {
	value, err := newPrefixed(source, "req_")
	return RequestID(value), err
}

func (id RequestID) String() string { return string(id) }
func (id RequestID) IsZero() bool   { return id == "" }
func (id RequestID) MarshalText() ([]byte, error) {
	if id.IsZero() {
		return nil, ErrZeroID
	}
	if _, err := ParseRequestID(string(id)); err != nil {
		return nil, err
	}
	return []byte(id), nil
}
func (id *RequestID) UnmarshalText(data []byte) error {
	parsed, err := ParseRequestID(string(data))
	if err != nil {
		return err
	}
	*id = parsed
	return nil
}
func (id RequestID) MarshalJSON() ([]byte, error) {
	if _, err := ParseRequestID(string(id)); err != nil {
		return nil, err
	}
	return marshalID(string(id))
}
func (id *RequestID) UnmarshalJSON(data []byte) error {
	value, err := unmarshalID(data, "req_")
	if err != nil {
		return err
	}
	*id = RequestID(value)
	return nil
}

type BackendTaskID string

func ParseBackendTaskID(value string) (BackendTaskID, error) {
	parsed, err := parsePrefixed(value, "bt_")
	return BackendTaskID(parsed), err
}

func NewBackendTaskID(source TokenSource) (BackendTaskID, error) {
	value, err := newPrefixed(source, "bt_")
	return BackendTaskID(value), err
}

func (id BackendTaskID) String() string { return string(id) }
func (id BackendTaskID) IsZero() bool   { return id == "" }
func (id BackendTaskID) MarshalText() ([]byte, error) {
	if id.IsZero() {
		return nil, ErrZeroID
	}
	if _, err := ParseBackendTaskID(string(id)); err != nil {
		return nil, err
	}
	return []byte(id), nil
}
func (id *BackendTaskID) UnmarshalText(data []byte) error {
	parsed, err := ParseBackendTaskID(string(data))
	if err != nil {
		return err
	}
	*id = parsed
	return nil
}
func (id BackendTaskID) MarshalJSON() ([]byte, error) {
	if _, err := ParseBackendTaskID(string(id)); err != nil {
		return nil, err
	}
	return marshalID(string(id))
}
func (id *BackendTaskID) UnmarshalJSON(data []byte) error {
	value, err := unmarshalID(data, "bt_")
	if err != nil {
		return err
	}
	*id = BackendTaskID(value)
	return nil
}

type QueueID string

func ParseQueueID(value string) (QueueID, error) {
	parsed, err := parsePrefixed(value, "queue_")
	return QueueID(parsed), err
}

func NewQueueID(source TokenSource) (QueueID, error) {
	value, err := newPrefixed(source, "queue_")
	return QueueID(value), err
}

func (id QueueID) String() string { return string(id) }
func (id QueueID) IsZero() bool   { return id == "" }
func (id QueueID) MarshalText() ([]byte, error) {
	if id.IsZero() {
		return nil, ErrZeroID
	}
	if _, err := ParseQueueID(string(id)); err != nil {
		return nil, err
	}
	return []byte(id), nil
}
func (id *QueueID) UnmarshalText(data []byte) error {
	parsed, err := ParseQueueID(string(data))
	if err != nil {
		return err
	}
	*id = parsed
	return nil
}
func (id QueueID) MarshalJSON() ([]byte, error) {
	if _, err := ParseQueueID(string(id)); err != nil {
		return nil, err
	}
	return marshalID(string(id))
}
func (id *QueueID) UnmarshalJSON(data []byte) error {
	value, err := unmarshalID(data, "queue_")
	if err != nil {
		return err
	}
	*id = QueueID(value)
	return nil
}

type MediaID string

func ParseMediaID(value string) (MediaID, error) {
	parsed, err := parsePrefixed(value, "media_")
	return MediaID(parsed), err
}

func NewMediaID(source TokenSource) (MediaID, error) {
	value, err := newPrefixed(source, "media_")
	return MediaID(value), err
}

func (id MediaID) String() string { return string(id) }
func (id MediaID) IsZero() bool   { return id == "" }
func (id MediaID) MarshalText() ([]byte, error) {
	if id.IsZero() {
		return nil, ErrZeroID
	}
	if _, err := ParseMediaID(string(id)); err != nil {
		return nil, err
	}
	return []byte(id), nil
}
func (id *MediaID) UnmarshalText(data []byte) error {
	parsed, err := ParseMediaID(string(data))
	if err != nil {
		return err
	}
	*id = parsed
	return nil
}
func (id MediaID) MarshalJSON() ([]byte, error) {
	if _, err := ParseMediaID(string(id)); err != nil {
		return nil, err
	}
	return marshalID(string(id))
}
func (id *MediaID) UnmarshalJSON(data []byte) error {
	value, err := unmarshalID(data, "media_")
	if err != nil {
		return err
	}
	*id = MediaID(value)
	return nil
}

type CaptureID string

func ParseCaptureID(value string) (CaptureID, error) {
	parsed, err := parsePrefixed(value, "cap_")
	return CaptureID(parsed), err
}

func NewCaptureID(source TokenSource) (CaptureID, error) {
	value, err := newPrefixed(source, "cap_")
	return CaptureID(value), err
}

func (id CaptureID) String() string { return string(id) }
func (id CaptureID) IsZero() bool   { return id == "" }
func (id CaptureID) MarshalText() ([]byte, error) {
	if id.IsZero() {
		return nil, ErrZeroID
	}
	if _, err := ParseCaptureID(string(id)); err != nil {
		return nil, err
	}
	return []byte(id), nil
}
func (id *CaptureID) UnmarshalText(data []byte) error {
	parsed, err := ParseCaptureID(string(data))
	if err != nil {
		return err
	}
	*id = parsed
	return nil
}
func (id CaptureID) MarshalJSON() ([]byte, error) {
	if _, err := ParseCaptureID(string(id)); err != nil {
		return nil, err
	}
	return marshalID(string(id))
}
func (id *CaptureID) UnmarshalJSON(data []byte) error {
	value, err := unmarshalID(data, "cap_")
	if err != nil {
		return err
	}
	*id = CaptureID(value)
	return nil
}

type VerificationID string

func ParseVerificationID(value string) (VerificationID, error) {
	parsed, err := parsePrefixed(value, "ver_")
	return VerificationID(parsed), err
}

func NewVerificationID(source TokenSource) (VerificationID, error) {
	value, err := newPrefixed(source, "ver_")
	return VerificationID(value), err
}

func (id VerificationID) String() string { return string(id) }
func (id VerificationID) IsZero() bool   { return id == "" }
func (id VerificationID) MarshalText() ([]byte, error) {
	if id.IsZero() {
		return nil, ErrZeroID
	}
	if _, err := ParseVerificationID(string(id)); err != nil {
		return nil, err
	}
	return []byte(id), nil
}
func (id *VerificationID) UnmarshalText(data []byte) error {
	parsed, err := ParseVerificationID(string(data))
	if err != nil {
		return err
	}
	*id = parsed
	return nil
}
func (id VerificationID) MarshalJSON() ([]byte, error) {
	if _, err := ParseVerificationID(string(id)); err != nil {
		return nil, err
	}
	return marshalID(string(id))
}
func (id *VerificationID) UnmarshalJSON(data []byte) error {
	value, err := unmarshalID(data, "ver_")
	if err != nil {
		return err
	}
	*id = VerificationID(value)
	return nil
}

type PublicationID string

func ParsePublicationID(value string) (PublicationID, error) {
	parsed, err := parsePrefixed(value, "pub_")
	return PublicationID(parsed), err
}

func NewPublicationID(source TokenSource) (PublicationID, error) {
	value, err := newPrefixed(source, "pub_")
	return PublicationID(value), err
}

func (id PublicationID) String() string { return string(id) }
func (id PublicationID) IsZero() bool   { return id == "" }
func (id PublicationID) MarshalText() ([]byte, error) {
	if id.IsZero() {
		return nil, ErrZeroID
	}
	if _, err := ParsePublicationID(string(id)); err != nil {
		return nil, err
	}
	return []byte(id), nil
}
func (id *PublicationID) UnmarshalText(data []byte) error {
	parsed, err := ParsePublicationID(string(data))
	if err != nil {
		return err
	}
	*id = parsed
	return nil
}
func (id PublicationID) MarshalJSON() ([]byte, error) {
	if _, err := ParsePublicationID(string(id)); err != nil {
		return nil, err
	}
	return marshalID(string(id))
}
func (id *PublicationID) UnmarshalJSON(data []byte) error {
	value, err := unmarshalID(data, "pub_")
	if err != nil {
		return err
	}
	*id = PublicationID(value)
	return nil
}

type OperationID string

func ParseOperationID(value string) (OperationID, error) {
	parsed, err := parsePrefixed(value, "op_")
	return OperationID(parsed), err
}

func NewOperationID(source TokenSource) (OperationID, error) {
	value, err := newPrefixed(source, "op_")
	return OperationID(value), err
}

func (id OperationID) String() string { return string(id) }
func (id OperationID) IsZero() bool   { return id == "" }
func (id OperationID) MarshalText() ([]byte, error) {
	if id.IsZero() {
		return nil, ErrZeroID
	}
	if _, err := ParseOperationID(string(id)); err != nil {
		return nil, err
	}
	return []byte(id), nil
}
func (id *OperationID) UnmarshalText(data []byte) error {
	parsed, err := ParseOperationID(string(data))
	if err != nil {
		return err
	}
	*id = parsed
	return nil
}
func (id OperationID) MarshalJSON() ([]byte, error) {
	if _, err := ParseOperationID(string(id)); err != nil {
		return nil, err
	}
	return marshalID(string(id))
}
func (id *OperationID) UnmarshalJSON(data []byte) error {
	value, err := unmarshalID(data, "op_")
	if err != nil {
		return err
	}
	*id = OperationID(value)
	return nil
}
