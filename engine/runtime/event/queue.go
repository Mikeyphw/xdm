package event

import (
	"context"
	"errors"
	"sync"
)

var ErrStreamClosed = errors.New("event stream closed")

type Queue struct {
	mu       sync.Mutex
	capacity int
	items    []Frame
	nextSeq  uint64
	changed  chan struct{}
	closed   bool
}

func NewQueue(capacity int) *Queue {
	if capacity < 1 {
		capacity = 1
	}
	return &Queue{capacity: capacity, nextSeq: 1, changed: make(chan struct{})}
}

func (q *Queue) signalLocked() {
	close(q.changed)
	q.changed = make(chan struct{})
}

func (q *Queue) Enqueue(ctx context.Context, frame Frame) (bool, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	for {
		q.mu.Lock()
		if q.closed {
			q.mu.Unlock()
			return false, ErrStreamClosed
		}
		if frame.Class == Telemetry && frame.CoalesceKey != "" {
			for i := range q.items {
				if q.items[i].Class == Telemetry && q.items[i].CoalesceKey == frame.CoalesceKey {
					q.items[i].Payload = append(q.items[i].Payload[:0], frame.Payload...)
					q.items[i].CommandID = frame.CommandID
					q.items[i].OperationID = frame.OperationID
					q.signalLocked()
					q.mu.Unlock()
					return true, nil
				}
			}
		}
		if len(q.items) < q.capacity {
			frame.Sequence = q.nextSeq
			q.nextSeq++
			q.items = append(q.items, frame.Clone())
			q.signalLocked()
			q.mu.Unlock()
			return true, nil
		}
		if frame.Class == Telemetry {
			q.mu.Unlock()
			return false, nil
		}
		wait := q.changed
		q.mu.Unlock()
		select {
		case <-ctx.Done():
			return false, ctx.Err()
		case <-wait:
		}
	}
}

func (q *Queue) Dequeue(ctx context.Context) (Frame, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	for {
		q.mu.Lock()
		if len(q.items) > 0 {
			frame := q.items[0]
			copy(q.items, q.items[1:])
			q.items = q.items[:len(q.items)-1]
			q.signalLocked()
			q.mu.Unlock()
			return frame.Clone(), nil
		}
		if q.closed {
			q.mu.Unlock()
			return Frame{}, ErrStreamClosed
		}
		wait := q.changed
		q.mu.Unlock()
		select {
		case <-ctx.Done():
			return Frame{}, ctx.Err()
		case <-wait:
		}
	}
}

func (q *Queue) Close() {
	q.mu.Lock()
	if !q.closed {
		q.closed = true
		q.signalLocked()
	}
	q.mu.Unlock()
}

func (q *Queue) Len() int {
	q.mu.Lock()
	defer q.mu.Unlock()
	return len(q.items)
}
