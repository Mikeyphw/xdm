package command

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/foundation/idgen"
)

var (
	ErrInvalidID      = errors.New("invalid command id")
	ErrInvalidCommand = errors.New("invalid command envelope")
)

const CancelKind = "runtime.cancel"

type ID string

type TokenSource interface {
	Token() (string, error)
}

func ParseID(value string) (ID, error) {
	if !strings.HasPrefix(value, "cmd_") {
		return "", fmt.Errorf("%w: expected cmd_ prefix", ErrInvalidID)
	}
	token := strings.TrimPrefix(value, "cmd_")
	if err := idgen.ValidateToken(token); err != nil {
		return "", fmt.Errorf("%w: %v", ErrInvalidID, err)
	}
	return ID(value), nil
}

func NewID(source TokenSource) (ID, error) {
	if source == nil {
		return "", fmt.Errorf("%w: nil token source", ErrInvalidID)
	}
	token, err := source.Token()
	if err != nil {
		return "", err
	}
	if err := idgen.ValidateToken(token); err != nil {
		return "", fmt.Errorf("%w: %v", ErrInvalidID, err)
	}
	return ID("cmd_" + token), nil
}

func (id ID) String() string { return string(id) }
func (id ID) Valid() bool {
	_, err := ParseID(string(id))
	return err == nil
}
func (id ID) MarshalJSON() ([]byte, error) {
	if !id.Valid() {
		return nil, ErrInvalidID
	}
	return json.Marshal(string(id))
}
func (id *ID) UnmarshalJSON(data []byte) error {
	var value string
	if err := json.Unmarshal(data, &value); err != nil {
		return err
	}
	parsed, err := ParseID(value)
	if err != nil {
		return err
	}
	*id = parsed
	return nil
}

type Envelope struct {
	ID          ID                   `json:"command_id"`
	OperationID identity.OperationID `json:"operation_id"`
	Kind        string               `json:"kind"`
	Payload     json.RawMessage      `json:"payload,omitempty"`
}

func (e Envelope) Validate() error {
	if !e.ID.Valid() || e.OperationID.IsZero() || strings.TrimSpace(e.Kind) == "" {
		return ErrInvalidCommand
	}
	if len(e.Payload) > 0 && !json.Valid(e.Payload) {
		return ErrInvalidCommand
	}
	return nil
}

func (e Envelope) Clone() Envelope {
	out := e
	if e.Payload != nil {
		out.Payload = append(json.RawMessage(nil), e.Payload...)
	}
	return out
}
