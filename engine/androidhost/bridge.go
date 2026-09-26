package androidhost

import (
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
)

var (
	ErrInvalidBridgeBytes     = errors.New("invalid bridge bytes")
	ErrProtocolVersion        = errors.New("bridge protocol version mismatch")
	ErrBufferAlreadyReleased  = errors.New("native buffer already released")
	ErrNarrowBridgeException  = errors.New("narrow bridge exception")
	ErrDuplicateEngine        = errors.New("duplicate engine instance")
	ErrEngineNotRunning       = errors.New("engine not running")
)

type BridgeMessage struct {
	Version string          `json:"version"`
	Kind    string          `json:"kind"`
	Payload json.RawMessage `json:"payload"`
}

type BridgeCodec struct{ Version string }

func NewBridgeCodec(version string) BridgeCodec {
	if version == "" { version = WireAPIVersion }
	return BridgeCodec{Version: version}
}

func (c BridgeCodec) Encode(kind string, payload any) ([]byte, error) {
	if kind == "" { return nil, fmt.Errorf("kind is required") }
	data, err := json.Marshal(payload)
	if err != nil { return nil, err }
	return json.Marshal(BridgeMessage{Version: c.Version, Kind: kind, Payload: data})
}

func (c BridgeCodec) Decode(data []byte) (BridgeMessage, error) {
	if len(data) == 0 { return BridgeMessage{}, ErrInvalidBridgeBytes }
	var msg BridgeMessage
	if err := json.Unmarshal(data, &msg); err != nil { return BridgeMessage{}, ErrInvalidBridgeBytes }
	if msg.Version != c.Version { return BridgeMessage{}, ErrProtocolVersion }
	if msg.Kind == "" { return BridgeMessage{}, ErrInvalidBridgeBytes }
	return msg, nil
}

type NativeStatus int

const (
	StatusOK NativeStatus = iota
	StatusInvalid
	StatusNotFound
	StatusProtocol
	StatusTimeout
	StatusStopped
	StatusInternal NativeStatus = 255
)

func BridgeError(status NativeStatus) error {
	switch status {
	case StatusOK:
		return nil
	case StatusInvalid, StatusNotFound, StatusProtocol, StatusTimeout, StatusStopped:
		return ErrNarrowBridgeException
	default:
		return ErrNarrowBridgeException
	}
}

type BufferLease struct {
	mu       sync.Mutex
	Token    uint64
	Bytes    []byte
	released bool
}

func NewBufferLease(token uint64, data []byte) *BufferLease {
	return &BufferLease{Token: token, Bytes: append([]byte(nil), data...)}
}

func (b *BufferLease) Release() error {
	b.mu.Lock(); defer b.mu.Unlock()
	if b.released { return ErrBufferAlreadyReleased }
	b.released = true
	b.Bytes = nil
	return nil
}

type EngineAuthority struct {
	mu       sync.Mutex
	nextID   uint64
	engineID string
	running  bool
	clients  int
	restarts int
}

func NewEngineAuthority() *EngineAuthority { return &EngineAuthority{} }

func (a *EngineAuthority) EnsureEngine(reason string) (string, error) {
	a.mu.Lock(); defer a.mu.Unlock()
	if a.running { return a.engineID, nil }
	id := atomic.AddUint64(&a.nextID, 1)
	a.engineID = fmt.Sprintf("android-engine-%d", id)
	a.running = true
	return a.engineID, nil
}

func (a *EngineAuthority) BindClient() (string, error) {
	id, err := a.EnsureEngine("bind")
	if err != nil { return "", err }
	a.mu.Lock(); a.clients++; a.mu.Unlock()
	return id, nil
}

func (a *EngineAuthority) UnbindClient() {
	a.mu.Lock(); if a.clients > 0 { a.clients-- }; a.mu.Unlock()
}

func (a *EngineAuthority) ActivityRecreated() (string, error) { return a.BindClient() }

func (a *EngineAuthority) DuplicateStartIntent() (string, error) { return a.EnsureEngine("duplicate-start") }

func (a *EngineAuthority) ProcessRestart() (string, error) {
	a.mu.Lock()
	a.running = false
	a.engineID = ""
	a.restarts++
	a.mu.Unlock()
	return a.EnsureEngine("process-restart-recovery")
}

func (a *EngineAuthority) Shutdown() error {
	a.mu.Lock(); defer a.mu.Unlock()
	if !a.running { return ErrEngineNotRunning }
	a.running = false
	a.engineID = ""
	a.clients = 0
	return nil
}

func (a *EngineAuthority) Snapshot() (engineID string, running bool, clients int, restarts int) {
	a.mu.Lock(); defer a.mu.Unlock()
	return a.engineID, a.running, a.clients, a.restarts
}
