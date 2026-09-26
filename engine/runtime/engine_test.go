package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/runtime/command"
	"github.com/subhra74/xdm/engine/runtime/event"
	"github.com/subhra74/xdm/engine/runtime/platform"
)

const token1 = "00000000000000000000000000000001"
const token2 = "00000000000000000000000000000002"

func cmd(t *testing.T, idToken, opToken, kind string, payload string) command.Envelope {
	t.Helper()
	id, err := command.ParseID("cmd_" + idToken)
	if err != nil {
		t.Fatal(err)
	}
	op, err := identity.ParseOperationID("op_" + opToken)
	if err != nil {
		t.Fatal(err)
	}
	return command.Envelope{ID: id, OperationID: op, Kind: kind, Payload: json.RawMessage(payload)}
}

func TestPingSequenceAndShutdown(t *testing.T) {
	e := New(Config{EventBuffer: 8})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	c := cmd(t, token1, token1, "runtime.ping", `{"hello":"world"}`)
	if err := e.Submit(context.Background(), c); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	first, err := e.NextFrame(ctx)
	if err != nil {
		t.Fatal(err)
	}
	second, err := e.NextFrame(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if first.Sequence != 1 || second.Sequence != 2 {
		t.Fatalf("seq: %d %d", first.Sequence, second.Sequence)
	}
	if first.Kind != "runtime.pong" || second.Kind != "command.completed" {
		t.Fatalf("kinds: %s %s", first.Kind, second.Kind)
	}
	if err := e.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := e.NextFrame(ctx); !errors.Is(err, event.ErrStreamClosed) {
		t.Fatalf("stream: %v", err)
	}
}

func TestConcurrentSubmissionAndDuplicateCommand(t *testing.T) {
	e := New(Config{EventBuffer: 128})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	defer e.Shutdown(context.Background())
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			tok := []byte(token1)
			tok[len(tok)-1] = "0123456789abcdef"[(i+1)%16]
			tok[len(tok)-2] = "0123456789abcdef"[(i+1)/16]
			token := string(tok)
			c := cmd(t, token, token, "runtime.ping", `{}`)
			if err := e.Submit(context.Background(), c); err != nil {
				t.Errorf("submit: %v", err)
			}
		}(i)
	}
	wg.Wait()
	c := cmd(t, "000000000000000000000000000000fa", "000000000000000000000000000000fa", "runtime.ping", `{}`)
	if err := e.Submit(context.Background(), c); err != nil {
		t.Fatal(err)
	}
	if err := e.Submit(context.Background(), c); !errors.Is(err, ErrDuplicateCommand) {
		t.Fatalf("duplicate: %v", err)
	}
}

func TestCancellationIsIdempotent(t *testing.T) {
	started := make(chan struct{})
	handler := func(ctx context.Context, inv *Invocation, env command.Envelope) error {
		close(started)
		<-ctx.Done()
		return ctx.Err()
	}
	e := New(Config{Handlers: map[string]Handler{"test.block": handler}, EventBuffer: 8})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	defer e.Shutdown(context.Background())
	c := cmd(t, token1, token1, "test.block", `{}`)
	if err := e.Submit(context.Background(), c); err != nil {
		t.Fatal(err)
	}
	<-started
	cancel1 := cmd(t, token2, token1, command.CancelKind, `{}`)
	if err := e.Submit(context.Background(), cancel1); err != nil {
		t.Fatal(err)
	}
	token3 := "00000000000000000000000000000003"
	cancel2 := cmd(t, token3, token1, command.CancelKind, `{}`)
	if err := e.Submit(context.Background(), cancel2); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	foundCancelled := false
	for i := 0; i < 3; i++ {
		f, err := e.NextFrame(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if f.Kind == "command.cancelled" {
			foundCancelled = true
		}
	}
	if !foundCancelled {
		t.Fatal("missing command.cancelled")
	}
}

func TestPlatformRequestFlowsThroughEventStream(t *testing.T) {
	e := New(Config{EventBuffer: 8})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	defer e.Shutdown(context.Background())
	c := cmd(t, token1, token1, "runtime.platform_probe", `{}`)
	if err := e.Submit(context.Background(), c); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	f, err := e.NextFrame(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if f.Kind != "platform.request" {
		t.Fatalf("kind: %s", f.Kind)
	}
	var req platform.Request
	if err := json.Unmarshal(f.Payload, &req); err != nil {
		t.Fatal(err)
	}
	if err := e.PlatformReply(platform.Reply{RequestID: req.ID, Session: req.Session, OK: true, Payload: json.RawMessage(`{"online":true}`)}); err != nil {
		t.Fatal(err)
	}
	done, err := e.NextFrame(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if done.Kind != "command.completed" {
		t.Fatalf("completion: %s", done.Kind)
	}
}

func TestAndroidSchedulerWakeRequestsRuntimeConditionsBeforeDecision(t *testing.T) {
	e := New(Config{EventBuffer: 16, PlatformBuffer: 4})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	defer e.Shutdown(context.Background())

	payload := `{"event_id":"wm:fixture-71","download_id":"dl_00000000000000000000000000000071","reason":"work_manager","conditions":{"require_online":true,"require_wifi":true}}`
	wake := cmd(t, "00000000000000000000000000000071", "00000000000000000000000000000071", "android.scheduler_wake", payload)
	if err := e.Submit(context.Background(), wake); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	platformFrame, err := e.NextFrame(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if platformFrame.Kind != "platform.request" {
		t.Fatalf("first frame kind=%s", platformFrame.Kind)
	}
	var req platform.Request
	if err := json.Unmarshal(platformFrame.Payload, &req); err != nil {
		t.Fatal(err)
	}
	if req.Kind != platform.RuntimeConditions {
		t.Fatalf("platform kind=%s", req.Kind)
	}
	conditions := json.RawMessage(`{"observed_at":"2026-09-26T03:00:00Z","online":true,"metered":false,"wifi":true,"charging":true,"battery_percent":90,"storage_free_bytes":1073741824,"power_source":"ac"}`)
	if err := e.PlatformReply(platform.Reply{RequestID: req.ID, Session: req.Session, OK: true, Payload: conditions}); err != nil {
		t.Fatal(err)
	}

	decisionFrame, err := e.NextFrame(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if decisionFrame.Kind != "android.scheduler.decision" {
		t.Fatalf("decision kind=%s", decisionFrame.Kind)
	}
	var decision map[string]any
	if err := json.Unmarshal(decisionFrame.Payload, &decision); err != nil {
		t.Fatal(err)
	}
	if decision["engine_policy_authoritative"] != true || decision["wake"] != true {
		t.Fatalf("bad decision: %s", decisionFrame.Payload)
	}
	completed, err := e.NextFrame(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if completed.Kind != "command.completed" {
		t.Fatalf("completion kind=%s", completed.Kind)
	}
}
