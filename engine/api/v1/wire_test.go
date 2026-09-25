package v1

import (
	"encoding/json"
	"errors"
	"testing"

	"github.com/subhra74/xdm/engine/runtime/event"
)

const token = "00000000000000000000000000000001"

func TestDecodeCommandAndVersionMismatch(t *testing.T) {
	good := []byte(`{"protocol":{"major":1,"minor":0},"command_id":"cmd_` + token + `","operation_id":"op_` + token + `","kind":"runtime.ping","payload":{"x":1}}`)
	env, err := DecodeCommand(good)
	if err != nil {
		t.Fatal(err)
	}
	if env.Kind != "runtime.ping" {
		t.Fatal(env.Kind)
	}
	bad := []byte(`{"protocol":{"major":2,"minor":0},"command_id":"cmd_` + token + `","operation_id":"op_` + token + `","kind":"runtime.ping"}`)
	if _, err := DecodeCommand(bad); !errors.Is(err, ErrProtocolMismatch) {
		t.Fatalf("version: %v", err)
	}
}

func TestMalformedAndUnknownFieldRejected(t *testing.T) {
	if _, err := DecodeCommand([]byte(`{"protocol":`)); !errors.Is(err, ErrMalformedMessage) {
		t.Fatal(err)
	}
	data := []byte(`{"protocol":{"major":1,"minor":0},"command_id":"cmd_` + token + `","operation_id":"op_` + token + `","kind":"runtime.ping","surprise":1}`)
	if _, err := DecodeCommand(data); !errors.Is(err, ErrMalformedMessage) {
		t.Fatal(err)
	}
}

func TestEncodeFrameAndMetadata(t *testing.T) {
	f := event.Frame{Sequence: 1, Class: event.Durable, Kind: "engine.ready", Payload: json.RawMessage(`{"ok":true}`)}
	data, err := EncodeFrame(f)
	if err != nil {
		t.Fatal(err)
	}
	if !json.Valid(data) {
		t.Fatal("invalid frame JSON")
	}
	md, err := EncodeMetadata()
	if err != nil || !json.Valid(md) {
		t.Fatalf("metadata: %v", err)
	}
}
