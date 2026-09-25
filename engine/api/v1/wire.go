package v1

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/runtime/command"
	"github.com/subhra74/xdm/engine/runtime/event"
	"github.com/subhra74/xdm/engine/runtime/platform"
)

const (
	ProtocolMajor = 1
	ProtocolMinor = 0
	ABIMajor      = 1
	SchemaVersion = 0
	EngineVersion = "0.1.0-dev"
)

var (
	ErrMalformedMessage = errors.New("malformed wire message")
	ErrProtocolMismatch = errors.New("wire protocol major mismatch")
)

type Protocol struct {
	Major int `json:"major"`
	Minor int `json:"minor"`
}

func CurrentProtocol() Protocol { return Protocol{Major: ProtocolMajor, Minor: ProtocolMinor} }
func (p Protocol) Validate() error {
	if p.Major != ProtocolMajor {
		return fmt.Errorf("%w: host=%d engine=%d", ErrProtocolMismatch, p.Major, ProtocolMajor)
	}
	if p.Minor < 0 {
		return ErrMalformedMessage
	}
	return nil
}

type CreateConfig struct {
	Protocol Protocol `json:"protocol"`
}

type CommandEnvelope struct {
	Protocol    Protocol        `json:"protocol"`
	CommandID   string          `json:"command_id"`
	OperationID string          `json:"operation_id"`
	Kind        string          `json:"kind"`
	Payload     json.RawMessage `json:"payload,omitempty"`
}

type EventEnvelope struct {
	Protocol    Protocol        `json:"protocol"`
	Sequence    uint64          `json:"sequence"`
	Class       string          `json:"class"`
	Kind        string          `json:"kind"`
	CommandID   string          `json:"command_id,omitempty"`
	OperationID string          `json:"operation_id,omitempty"`
	CoalesceKey string          `json:"coalesce_key,omitempty"`
	Payload     json.RawMessage `json:"payload,omitempty"`
}

type PlatformReplyEnvelope struct {
	Protocol  Protocol        `json:"protocol"`
	RequestID uint64          `json:"request_id"`
	Session   uint64          `json:"session"`
	OK        bool            `json:"ok"`
	Payload   json.RawMessage `json:"payload,omitempty"`
	ErrorCode string          `json:"error_code,omitempty"`
}

type Metadata struct {
	EngineVersion string   `json:"engine_version"`
	Protocol      Protocol `json:"protocol"`
	ABIMajor      int      `json:"abi_major"`
	SchemaVersion int      `json:"schema_version"`
}

func CurrentMetadata() Metadata {
	return Metadata{EngineVersion: EngineVersion, Protocol: CurrentProtocol(), ABIMajor: ABIMajor, SchemaVersion: SchemaVersion}
}

func decodeStrict(data []byte, out any) error {
	if len(data) == 0 {
		return ErrMalformedMessage
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(out); err != nil {
		return fmt.Errorf("%w: %v", ErrMalformedMessage, err)
	}
	var trailing any
	if err := dec.Decode(&trailing); err != io.EOF {
		if err == nil {
			return ErrMalformedMessage
		}
		return fmt.Errorf("%w: trailing data: %v", ErrMalformedMessage, err)
	}
	return nil
}

func DecodeCreateConfig(data []byte) (CreateConfig, error) {
	if len(data) == 0 {
		return CreateConfig{Protocol: CurrentProtocol()}, nil
	}
	var cfg CreateConfig
	if err := decodeStrict(data, &cfg); err != nil {
		return CreateConfig{}, err
	}
	if err := cfg.Protocol.Validate(); err != nil {
		return CreateConfig{}, err
	}
	return cfg, nil
}

func DecodeCommand(data []byte) (command.Envelope, error) {
	var wire CommandEnvelope
	if err := decodeStrict(data, &wire); err != nil {
		return command.Envelope{}, err
	}
	if err := wire.Protocol.Validate(); err != nil {
		return command.Envelope{}, err
	}
	cid, err := command.ParseID(wire.CommandID)
	if err != nil {
		return command.Envelope{}, err
	}
	op, err := identity.ParseOperationID(wire.OperationID)
	if err != nil {
		return command.Envelope{}, err
	}
	env := command.Envelope{ID: cid, OperationID: op, Kind: wire.Kind, Payload: append(json.RawMessage(nil), wire.Payload...)}
	if err := env.Validate(); err != nil {
		return command.Envelope{}, err
	}
	return env, nil
}

func EncodeFrame(frame event.Frame) ([]byte, error) {
	if err := frame.Validate(); err != nil {
		return nil, err
	}
	wire := EventEnvelope{Protocol: CurrentProtocol(), Sequence: frame.Sequence, Class: string(frame.Class), Kind: frame.Kind, CommandID: frame.CommandID.String(), OperationID: frame.OperationID.String(), CoalesceKey: frame.CoalesceKey, Payload: append(json.RawMessage(nil), frame.Payload...)}
	return json.Marshal(wire)
}

func DecodePlatformReply(data []byte) (platform.Reply, error) {
	var wire PlatformReplyEnvelope
	if err := decodeStrict(data, &wire); err != nil {
		return platform.Reply{}, err
	}
	if err := wire.Protocol.Validate(); err != nil {
		return platform.Reply{}, err
	}
	if wire.RequestID == 0 || wire.Session == 0 {
		return platform.Reply{}, ErrMalformedMessage
	}
	if len(wire.Payload) > 0 && !json.Valid(wire.Payload) {
		return platform.Reply{}, ErrMalformedMessage
	}
	return platform.Reply{RequestID: platform.RequestID(wire.RequestID), Session: wire.Session, OK: wire.OK, Payload: append(json.RawMessage(nil), wire.Payload...), ErrorCode: wire.ErrorCode}, nil
}

func EncodeMetadata() ([]byte, error) { return json.Marshal(CurrentMetadata()) }
