package platform

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"
)

func TestRoundTripDuplicateAndUnknownReply(t *testing.T) {
	b := NewBroker(2)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		reply, err := b.Request(ctx, RuntimeConditions, Refs{}, json.RawMessage(`{"q":1}`))
		if err == nil && string(reply.Payload) != `{"ok":true}` {
			err = errors.New("bad payload")
		}
		done <- err
	}()
	req, err := b.NextRequest(ctx)
	if err != nil {
		t.Fatal(err)
	}
	reply := Reply{RequestID: req.ID, Session: req.Session, OK: true, Payload: json.RawMessage(`{"ok":true}`)}
	if err := b.Reply(reply); err != nil {
		t.Fatal(err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if err := b.Reply(reply); !errors.Is(err, ErrDuplicateReply) {
		t.Fatalf("duplicate: %v", err)
	}
	if err := b.Reply(Reply{RequestID: 999, Session: req.Session}); !errors.Is(err, ErrUnknownRequest) {
		t.Fatalf("unknown: %v", err)
	}
}

func TestTimeoutMakesReplyLate(t *testing.T) {
	b := NewBroker(1)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Millisecond)
	defer cancel()
	result := make(chan error, 1)
	go func() { _, err := b.Request(ctx, SecretLookup, Refs{}, nil); result <- err }()
	req, err := b.NextRequest(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if err := <-result; !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("request err: %v", err)
	}
	if err := b.Reply(Reply{RequestID: req.ID, Session: req.Session}); !errors.Is(err, ErrLateReply) {
		t.Fatalf("late reply: %v", err)
	}
}

func TestDisconnectAndReconnect(t *testing.T) {
	b := NewBroker(1)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := b.Request(ctx, RuntimeConditions, Refs{}, nil); done <- err }()
	if _, err := b.NextRequest(ctx); err != nil {
		t.Fatal(err)
	}
	b.Disconnect(errors.New("host restart"))
	if err := <-done; !errors.Is(err, ErrDisconnected) {
		t.Fatalf("disconnect: %v", err)
	}
	if _, err := b.Request(ctx, RuntimeConditions, Refs{}, nil); !errors.Is(err, ErrDisconnected) {
		t.Fatalf("while disconnected: %v", err)
	}
	if err := b.Connect(); err != nil {
		t.Fatal(err)
	}
	go func() {
		req, _ := b.NextRequest(ctx)
		_ = b.Reply(Reply{RequestID: req.ID, Session: req.Session, OK: true})
	}()
	if _, err := b.Request(ctx, RuntimeConditions, Refs{}, nil); err != nil {
		t.Fatal(err)
	}
}

func TestUnsupportedAndShutdown(t *testing.T) {
	b := NewBroker(1)
	if _, err := b.Request(context.Background(), Kind("unknown"), Refs{}, nil); !errors.Is(err, ErrUnsupportedOperation) {
		t.Fatal(err)
	}
	b.Shutdown()
	if _, err := b.Request(context.Background(), RuntimeConditions, Refs{}, nil); !errors.Is(err, ErrShutdown) {
		t.Fatal(err)
	}
}
