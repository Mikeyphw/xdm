package command

import (
	"encoding/json"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
)

const token = "00000000000000000000000000000001"

func TestCommandEnvelopeValidation(t *testing.T) {
	id, err := ParseID("cmd_" + token)
	if err != nil {
		t.Fatal(err)
	}
	op, err := identity.ParseOperationID("op_" + token)
	if err != nil {
		t.Fatal(err)
	}
	env := Envelope{ID: id, OperationID: op, Kind: "runtime.ping", Payload: json.RawMessage(`{"a":1}`)}
	if err := env.Validate(); err != nil {
		t.Fatal(err)
	}
	env.Payload = json.RawMessage(`{`)
	if err := env.Validate(); err == nil {
		t.Fatal("expected invalid payload")
	}
}

func TestCommandIDRejectsWrongPrefix(t *testing.T) {
	if _, err := ParseID("op_" + token); err == nil {
		t.Fatal("expected prefix failure")
	}
}
