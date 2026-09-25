package event

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

func TestSequenceAndTelemetryCoalescing(t *testing.T) {
	q := NewQueue(2)
	ctx := context.Background()
	ok, err := q.Enqueue(ctx, Frame{Class: Telemetry, Kind: "progress", CoalesceKey: "dl", Payload: json.RawMessage(`1`)})
	if err != nil || !ok {
		t.Fatalf("enqueue: %v %v", ok, err)
	}
	ok, err = q.Enqueue(ctx, Frame{Class: Telemetry, Kind: "progress", CoalesceKey: "dl", Payload: json.RawMessage(`2`)})
	if err != nil || !ok {
		t.Fatalf("coalesce: %v %v", ok, err)
	}
	ok, err = q.Enqueue(ctx, Frame{Class: Durable, Kind: "done"})
	if err != nil || !ok {
		t.Fatalf("durable: %v %v", ok, err)
	}
	first, _ := q.Dequeue(ctx)
	second, _ := q.Dequeue(ctx)
	if first.Sequence != 1 || second.Sequence != 2 {
		t.Fatalf("sequence: %d %d", first.Sequence, second.Sequence)
	}
	if string(first.Payload) != "2" {
		t.Fatalf("coalesced payload: %s", first.Payload)
	}
}

func TestTelemetryDropsWhenFullButDurableBackpressures(t *testing.T) {
	q := NewQueue(1)
	ctx := context.Background()
	if ok, err := q.Enqueue(ctx, Frame{Class: Durable, Kind: "a"}); err != nil || !ok {
		t.Fatal(err)
	}
	if ok, err := q.Enqueue(ctx, Frame{Class: Telemetry, Kind: "p"}); err != nil || ok {
		t.Fatalf("telemetry should drop: %v %v", ok, err)
	}
	timed, cancel := context.WithTimeout(ctx, 15*time.Millisecond)
	defer cancel()
	if _, err := q.Enqueue(timed, Frame{Class: Durable, Kind: "b"}); err == nil {
		t.Fatal("durable enqueue should backpressure until context expires")
	}
}
