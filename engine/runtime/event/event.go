package event

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/runtime/command"
)

var ErrInvalidFrame = errors.New("invalid event frame")

type Class string

const (
	Durable     Class = "durable"
	Operational Class = "operational"
	Telemetry   Class = "telemetry"
)

func (c Class) Valid() bool {
	switch c {
	case Durable, Operational, Telemetry:
		return true
	default:
		return false
	}
}

type Frame struct {
	Sequence    uint64               `json:"sequence"`
	Class       Class                `json:"class"`
	Kind        string               `json:"kind"`
	CommandID   command.ID           `json:"command_id,omitempty"`
	OperationID identity.OperationID `json:"operation_id,omitempty"`
	CoalesceKey string               `json:"coalesce_key,omitempty"`
	Payload     json.RawMessage      `json:"payload,omitempty"`
}

func (f Frame) Validate() error {
	if f.Sequence == 0 || !f.Class.Valid() || strings.TrimSpace(f.Kind) == "" {
		return ErrInvalidFrame
	}
	if len(f.Payload) > 0 && !json.Valid(f.Payload) {
		return ErrInvalidFrame
	}
	if f.Class != Telemetry && f.CoalesceKey != "" {
		return fmt.Errorf("%w: coalesce key is telemetry-only", ErrInvalidFrame)
	}
	return nil
}

func (f Frame) Clone() Frame {
	out := f
	if f.Payload != nil {
		out.Payload = append(json.RawMessage(nil), f.Payload...)
	}
	return out
}
