package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"sync"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/runtime/command"
	"github.com/subhra74/xdm/engine/runtime/event"
	"github.com/subhra74/xdm/engine/runtime/platform"
)

var (
	ErrNotStarted       = errors.New("engine not started")
	ErrAlreadyStarted   = errors.New("engine already started")
	ErrStopped          = errors.New("engine stopped")
	ErrDuplicateCommand = errors.New("duplicate command id")
	ErrOperationActive  = errors.New("operation already active")
	ErrUnknownCommand   = errors.New("unknown command kind")
)

type Handler func(context.Context, *Invocation, command.Envelope) error

type Config struct {
	CommandBuffer  int
	EventBuffer    int
	PlatformBuffer int
	Handlers       map[string]Handler
}

type activeOperation struct {
	cancel context.CancelFunc
}

type queuedCommand struct {
	env command.Envelope
	ctx context.Context
	op  *activeOperation
}

type Engine struct {
	mu         sync.Mutex
	started    bool
	stopping   bool
	stopped    bool
	rootCtx    context.Context
	rootCancel context.CancelFunc
	commands   chan queuedCommand
	events     *event.Queue
	broker     *platform.Broker
	handlers   map[string]Handler
	seen       map[command.ID]struct{}
	active     map[identity.OperationID]*activeOperation
	workers    sync.WaitGroup
	loops      sync.WaitGroup
}

type Invocation struct {
	engine *Engine
	env    command.Envelope
	ctx    context.Context
}

func New(config Config) *Engine {
	commandBuffer := config.CommandBuffer
	if commandBuffer < 1 {
		commandBuffer = 32
	}
	eventBuffer := config.EventBuffer
	if eventBuffer < 1 {
		eventBuffer = 128
	}
	platformBuffer := config.PlatformBuffer
	if platformBuffer < 1 {
		platformBuffer = 32
	}
	rootCtx, cancel := context.WithCancel(context.Background())
	e := &Engine{
		rootCtx: rootCtx, rootCancel: cancel,
		commands: make(chan queuedCommand, commandBuffer),
		events:   event.NewQueue(eventBuffer), broker: platform.NewBroker(platformBuffer),
		handlers: make(map[string]Handler), seen: make(map[command.ID]struct{}), active: make(map[identity.OperationID]*activeOperation),
	}
	e.handlers["runtime.ping"] = pingHandler
	e.handlers["runtime.platform_probe"] = platformProbeHandler
	for kind, handler := range config.Handlers {
		if kind != "" && handler != nil {
			e.handlers[kind] = handler
		}
	}
	return e
}

func (e *Engine) Start() error {
	e.mu.Lock()
	if e.stopped || e.stopping {
		e.mu.Unlock()
		return ErrStopped
	}
	if e.started {
		e.mu.Unlock()
		return ErrAlreadyStarted
	}
	e.started = true
	e.loops.Add(2)
	e.mu.Unlock()
	go e.commandLoop()
	go e.platformLoop()
	return nil
}

func (e *Engine) Submit(ctx context.Context, env command.Envelope) error {
	if ctx == nil {
		ctx = context.Background()
	}
	if err := env.Validate(); err != nil {
		return err
	}

	e.mu.Lock()
	if !e.started {
		e.mu.Unlock()
		return ErrNotStarted
	}
	if e.stopping || e.stopped {
		e.mu.Unlock()
		return ErrStopped
	}
	if _, exists := e.seen[env.ID]; exists {
		e.mu.Unlock()
		return ErrDuplicateCommand
	}
	e.seen[env.ID] = struct{}{}

	if env.Kind == command.CancelKind {
		op, active := e.active[env.OperationID]
		e.mu.Unlock()
		if active {
			op.cancel()
		}
		payload, _ := json.Marshal(map[string]bool{"active_operation_found": active})
		_, emitErr := e.emit(ctx, event.Frame{Class: event.Operational, Kind: "runtime.cancel.ack", CommandID: env.ID, OperationID: env.OperationID, Payload: payload})
		return emitErr
	}

	handler := e.handlers[env.Kind]
	if handler == nil {
		e.mu.Unlock()
		return ErrUnknownCommand
	}
	if _, exists := e.active[env.OperationID]; exists {
		e.mu.Unlock()
		return ErrOperationActive
	}
	opCtx, cancel := context.WithCancel(e.rootCtx)
	op := &activeOperation{cancel: cancel}
	e.active[env.OperationID] = op
	e.mu.Unlock()

	q := queuedCommand{env: env.Clone(), ctx: opCtx, op: op}
	select {
	case e.commands <- q:
		return nil
	case <-ctx.Done():
		e.releaseOperation(env.OperationID, op)
		return ctx.Err()
	case <-e.rootCtx.Done():
		e.releaseOperation(env.OperationID, op)
		return ErrStopped
	}
}

func (e *Engine) releaseOperation(id identity.OperationID, op *activeOperation) {
	e.mu.Lock()
	if current, ok := e.active[id]; ok && current == op {
		delete(e.active, id)
	}
	e.mu.Unlock()
	if op != nil && op.cancel != nil {
		op.cancel()
	}
}

func (e *Engine) commandLoop() {
	defer e.loops.Done()
	for {
		select {
		case <-e.rootCtx.Done():
			return
		case q := <-e.commands:
			e.workers.Add(1)
			go e.runCommand(q)
		}
	}
}

func (e *Engine) runCommand(q queuedCommand) {
	defer e.workers.Done()
	defer e.releaseOperation(q.env.OperationID, q.op)
	e.mu.Lock()
	handler := e.handlers[q.env.Kind]
	e.mu.Unlock()
	inv := &Invocation{engine: e, env: q.env, ctx: q.ctx}
	err := handler(q.ctx, inv, q.env)
	kind := "command.completed"
	payload := json.RawMessage(`{"status":"ok"}`)
	if err != nil {
		if errors.Is(err, context.Canceled) {
			kind = "command.cancelled"
			payload = json.RawMessage(`{"status":"cancelled"}`)
		} else {
			kind = "command.failed"
			f, ferr := failure.NewDefault(failure.InternalFailure, err)
			if ferr == nil {
				payload, _ = json.Marshal(f)
			} else {
				payload = json.RawMessage(`{"status":"failed"}`)
			}
		}
	}
	_, _ = e.emit(e.rootCtx, event.Frame{Class: event.Durable, Kind: kind, CommandID: q.env.ID, OperationID: q.env.OperationID, Payload: payload})
}

func (e *Engine) platformLoop() {
	defer e.loops.Done()
	for {
		req, err := e.broker.NextRequest(e.rootCtx)
		if err != nil {
			return
		}
		payload, err := json.Marshal(req)
		if err != nil {
			continue
		}
		if _, err := e.emit(e.rootCtx, event.Frame{Class: event.Durable, Kind: "platform.request", OperationID: req.Refs.OperationID, Payload: payload}); err != nil {
			return
		}
	}
}

func (e *Engine) emit(ctx context.Context, frame event.Frame) (bool, error) {
	return e.events.Enqueue(ctx, frame)
}

func (i *Invocation) Emit(class event.Class, kind string, payload json.RawMessage, coalesceKey string) (bool, error) {
	if i == nil || i.engine == nil {
		return false, ErrStopped
	}
	ctx := i.ctx
	if ctx == nil {
		ctx = context.Background()
	}
	return i.engine.emit(ctx, event.Frame{Class: class, Kind: kind, CommandID: i.env.ID, OperationID: i.env.OperationID, CoalesceKey: coalesceKey, Payload: payload})
}

func (i *Invocation) PlatformRequest(ctx context.Context, kind platform.Kind, refs platform.Refs, payload json.RawMessage) (platform.Reply, error) {
	if i == nil || i.engine == nil {
		return platform.Reply{}, ErrStopped
	}
	if refs.OperationID.IsZero() {
		refs.OperationID = i.env.OperationID
	}
	return i.engine.broker.Request(ctx, kind, refs, payload)
}

func (e *Engine) NextFrame(ctx context.Context) (event.Frame, error) { return e.events.Dequeue(ctx) }
func (e *Engine) PlatformReply(reply platform.Reply) error           { return e.broker.Reply(reply) }
func (e *Engine) PlatformBroker() *platform.Broker                   { return e.broker }

func (e *Engine) Shutdown(ctx context.Context) error {
	if ctx == nil {
		ctx = context.Background()
	}
	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		return nil
	}
	if !e.started {
		e.stopped = true
		e.stopping = false
		e.rootCancel()
		e.broker.Shutdown()
		e.events.Close()
		e.mu.Unlock()
		return nil
	}
	if !e.stopping {
		e.stopping = true
		e.rootCancel()
		e.broker.Shutdown()
	}
	e.mu.Unlock()

	done := make(chan struct{})
	go func() { e.loops.Wait(); e.workers.Wait(); close(done) }()
	select {
	case <-done:
		e.mu.Lock()
		e.stopped = true
		e.stopping = false
		e.mu.Unlock()
		e.events.Close()
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func pingHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
	}
	payload := env.Payload
	if len(payload) == 0 {
		payload = json.RawMessage(`{}`)
	}
	_, err := inv.Emit(event.Operational, "runtime.pong", payload, "")
	return err
}

func platformProbeHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	_, err := inv.PlatformRequest(ctx, platform.RuntimeConditions, platform.Refs{}, env.Payload)
	return err
}
