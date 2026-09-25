package platform

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
)

var (
	ErrUnsupportedOperation = errors.New("unsupported platform operation")
	ErrUnknownRequest       = errors.New("unknown platform request")
	ErrDuplicateReply       = errors.New("duplicate platform reply")
	ErrLateReply            = errors.New("late platform reply")
	ErrDisconnected         = errors.New("platform host disconnected")
	ErrShutdown             = errors.New("platform broker shut down")
)

type Kind string

const (
	SecretLookup      Kind = "secret_lookup"
	SystemProxy       Kind = "system_proxy"
	NetworkPolicy     Kind = "network_policy"
	RuntimeConditions Kind = "runtime_conditions"
	Publication       Kind = "publication"
	ExternalMediaTool Kind = "external_media_tool"
)

func (k Kind) Valid() bool {
	switch k {
	case SecretLookup, SystemProxy, NetworkPolicy, RuntimeConditions, Publication, ExternalMediaTool:
		return true
	default:
		return false
	}
}

type RequestID uint64

type Refs struct {
	OperationID       identity.OperationID       `json:"operation_id,omitempty"`
	AttemptGeneration identity.AttemptGeneration `json:"attempt_generation,omitempty"`
	PublicationID     identity.PublicationID     `json:"publication_id,omitempty"`
}

type Request struct {
	ID         RequestID       `json:"request_id"`
	Session    uint64          `json:"session"`
	Kind       Kind            `json:"kind"`
	DeadlineMS int64           `json:"deadline_ms,omitempty"`
	Refs       Refs            `json:"refs,omitempty"`
	Payload    json.RawMessage `json:"payload,omitempty"`
}

type Reply struct {
	RequestID RequestID       `json:"request_id"`
	Session   uint64          `json:"session"`
	OK        bool            `json:"ok"`
	Payload   json.RawMessage `json:"payload,omitempty"`
	ErrorCode string          `json:"error_code,omitempty"`
}

type result struct {
	reply Reply
	err   error
}

type pending struct {
	session uint64
	ch      chan result
}

type tombstone struct {
	id     RequestID
	status string
}

type Broker struct {
	mu        sync.Mutex
	queue     chan Request
	pending   map[RequestID]pending
	tomb      map[RequestID]string
	tombOrder []RequestID
	tombCap   int
	nextID    RequestID
	session   uint64
	connected bool
	shutdown  bool
	done      chan struct{}
}

func NewBroker(capacity int) *Broker {
	if capacity < 1 {
		capacity = 1
	}
	return &Broker{
		queue: make(chan Request, capacity), pending: make(map[RequestID]pending), tomb: make(map[RequestID]string),
		tombCap: 256, nextID: 1, session: 1, connected: true, done: make(chan struct{}),
	}
}

func cloneRaw(in json.RawMessage) json.RawMessage {
	if in == nil {
		return nil
	}
	return append(json.RawMessage(nil), in...)
}

func (b *Broker) Request(ctx context.Context, kind Kind, refs Refs, payload json.RawMessage) (Reply, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if !kind.Valid() {
		return Reply{}, ErrUnsupportedOperation
	}
	if len(payload) > 0 && !json.Valid(payload) {
		return Reply{}, fmt.Errorf("invalid platform request payload")
	}

	b.mu.Lock()
	if b.shutdown {
		b.mu.Unlock()
		return Reply{}, ErrShutdown
	}
	if !b.connected {
		b.mu.Unlock()
		return Reply{}, ErrDisconnected
	}
	id := b.nextID
	b.nextID++
	session := b.session
	ch := make(chan result, 1)
	b.pending[id] = pending{session: session, ch: ch}
	b.mu.Unlock()

	req := Request{ID: id, Session: session, Kind: kind, Refs: refs, Payload: cloneRaw(payload)}
	if deadline, ok := ctx.Deadline(); ok {
		req.DeadlineMS = deadline.UnixMilli()
	}
	select {
	case b.queue <- req:
	case <-ctx.Done():
		b.cancelPending(id, "late")
		return Reply{}, ctx.Err()
	case <-b.done:
		b.cancelPending(id, "late")
		return Reply{}, ErrShutdown
	}

	select {
	case got := <-ch:
		return got.reply, got.err
	case <-ctx.Done():
		b.cancelPending(id, "late")
		return Reply{}, ctx.Err()
	case <-b.done:
		return Reply{}, ErrShutdown
	}
}

func (b *Broker) cancelPending(id RequestID, status string) {
	b.mu.Lock()
	if _, ok := b.pending[id]; ok {
		delete(b.pending, id)
		b.addTombLocked(id, status)
	}
	b.mu.Unlock()
}

func (b *Broker) addTombLocked(id RequestID, status string) {
	if _, exists := b.tomb[id]; !exists {
		b.tombOrder = append(b.tombOrder, id)
	}
	b.tomb[id] = status
	for len(b.tombOrder) > b.tombCap {
		old := b.tombOrder[0]
		b.tombOrder = b.tombOrder[1:]
		delete(b.tomb, old)
	}
}

func (b *Broker) NextRequest(ctx context.Context) (Request, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	for {
		select {
		case req := <-b.queue:
			b.mu.Lock()
			valid := !b.shutdown && b.connected && req.Session == b.session
			b.mu.Unlock()
			if valid {
				return req, nil
			}
		case <-ctx.Done():
			return Request{}, ctx.Err()
		case <-b.done:
			return Request{}, ErrShutdown
		}
	}
}

func (b *Broker) Reply(reply Reply) error {
	if len(reply.Payload) > 0 && !json.Valid(reply.Payload) {
		return fmt.Errorf("invalid platform reply payload")
	}
	b.mu.Lock()
	if b.shutdown {
		b.mu.Unlock()
		return ErrShutdown
	}
	p, ok := b.pending[reply.RequestID]
	if !ok {
		status, tombed := b.tomb[reply.RequestID]
		b.mu.Unlock()
		if tombed && status == "completed" {
			return ErrDuplicateReply
		}
		if tombed {
			return ErrLateReply
		}
		return ErrUnknownRequest
	}
	if p.session != reply.Session {
		b.mu.Unlock()
		return ErrLateReply
	}
	delete(b.pending, reply.RequestID)
	b.addTombLocked(reply.RequestID, "completed")
	b.mu.Unlock()
	reply.Payload = cloneRaw(reply.Payload)
	p.ch <- result{reply: reply}
	return nil
}

func (b *Broker) Disconnect(cause error) {
	if cause == nil {
		cause = ErrDisconnected
	}
	b.mu.Lock()
	if b.shutdown {
		b.mu.Unlock()
		return
	}
	b.connected = false
	b.session++
	pendingMap := b.pending
	b.pending = make(map[RequestID]pending)
	for id := range pendingMap {
		b.addTombLocked(id, "late")
	}
	b.mu.Unlock()
	for _, p := range pendingMap {
		p.ch <- result{err: fmt.Errorf("%w: %v", ErrDisconnected, cause)}
	}
}

func (b *Broker) Connect() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.shutdown {
		return ErrShutdown
	}
	b.session++
	b.connected = true
	return nil
}

func (b *Broker) Shutdown() {
	b.mu.Lock()
	if b.shutdown {
		b.mu.Unlock()
		return
	}
	b.shutdown = true
	pendingMap := b.pending
	b.pending = make(map[RequestID]pending)
	for id := range pendingMap {
		b.addTombLocked(id, "late")
	}
	close(b.done)
	b.mu.Unlock()
	for _, p := range pendingMap {
		p.ch <- result{err: ErrShutdown}
	}
}

func (b *Broker) Pending() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.pending)
}

func (b *Broker) Session() uint64 {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.session
}

func DeadlineFromMillis(ms int64) (time.Time, bool) {
	if ms <= 0 {
		return time.Time{}, false
	}
	return time.UnixMilli(ms), true
}
