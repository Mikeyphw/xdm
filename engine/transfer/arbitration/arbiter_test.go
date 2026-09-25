package arbitration

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

type fakeClock struct {
	mu     sync.Mutex
	now    time.Time
	sleeps []time.Duration
}

func newFakeClock() *fakeClock { return &fakeClock{now: time.Unix(1000, 0)} }
func (f *fakeClock) Now() time.Time {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.now
}
func (f *fakeClock) SleepUntil(ctx context.Context, at time.Time) error {
	f.mu.Lock()
	if !at.After(f.now) {
		f.mu.Unlock()
		return nil
	}
	d := at.Sub(f.now)
	select {
	case <-ctx.Done():
		f.mu.Unlock()
		return ctx.Err()
	default:
		f.sleeps = append(f.sleeps, d)
		f.now = at
		f.mu.Unlock()
		return nil
	}
}
func (f *fakeClock) durations() []time.Duration {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]time.Duration(nil), f.sleeps...)
}

func mustArbiter(t *testing.T, l Limits) *Arbiter {
	t.Helper()
	a, err := New(l)
	if err != nil {
		t.Fatal(err)
	}
	return a
}

func key(download, host string) Key { return Key{DownloadID: download, Host: host} }

func TestConnectionCapsHostIsolationAndRelease(t *testing.T) {
	a := mustArbiter(t, Limits{GlobalConnections: 2, DefaultHostConnections: 1, DefaultDownloadConnections: 2})
	l1, err := a.Acquire(context.Background(), key("a", "one.test"))
	if err != nil {
		t.Fatal(err)
	}
	defer l1.Release()

	blockedCtx, cancel := context.WithCancel(context.Background())
	defer cancel()
	blocked := make(chan error, 1)
	go func() {
		l, e := a.Acquire(blockedCtx, key("a", "one.test"))
		if l != nil {
			l.Release()
		}
		blocked <- e
	}()
	time.Sleep(20 * time.Millisecond)

	l2, err := a.Acquire(context.Background(), key("b", "two.test"))
	if err != nil {
		t.Fatalf("independent host blocked: %v", err)
	}
	l2.Release()

	select {
	case err := <-blocked:
		t.Fatalf("same-host waiter unexpectedly completed: %v", err)
	default:
	}
	l1.Release()
	select {
	case err := <-blocked:
		if err != nil {
			t.Fatalf("waiter did not acquire after release: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("waiter was not woken after release")
	}
	if got := a.Metrics().ActiveConnections; got != 0 {
		t.Fatalf("active connections = %d", got)
	}
}

func TestPerDownloadSegmentCap(t *testing.T) {
	a := mustArbiter(t, Limits{GlobalConnections: 4, DefaultHostConnections: 4, DefaultDownloadConnections: 1})
	first, err := a.Acquire(context.Background(), key("same", "one.test"))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	_, err = a.Acquire(ctx, key("same", "two.test"))
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("per-download cap error = %v", err)
	}
	first.Release()
}

func TestLimitChangeWakesConnectionWaiter(t *testing.T) {
	limits := Limits{GlobalConnections: 1, DefaultHostConnections: 2, DefaultDownloadConnections: 2}
	a := mustArbiter(t, limits)
	first, err := a.Acquire(context.Background(), key("a", "one.test"))
	if err != nil {
		t.Fatal(err)
	}
	defer first.Release()

	done := make(chan error, 1)
	go func() {
		lease, e := a.Acquire(context.Background(), key("b", "two.test"))
		if lease != nil {
			lease.Release()
		}
		done <- e
	}()
	time.Sleep(20 * time.Millisecond)
	limits.GlobalConnections = 2
	if err := a.UpdateLimits(limits); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("limit update did not wake waiter")
	}
	if a.Metrics().LimitsRevision != 2 {
		t.Fatalf("limits revision = %d", a.Metrics().LimitsRevision)
	}
}

func TestCancelledWaiterDoesNotLeakPermit(t *testing.T) {
	a := mustArbiter(t, Limits{GlobalConnections: 1})
	first, err := a.Acquire(context.Background(), key("a", "one.test"))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()
	if _, err = a.Acquire(ctx, key("b", "two.test")); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("cancelled acquire = %v", err)
	}
	first.Release()
	next, err := a.Acquire(context.Background(), key("b", "two.test"))
	if err != nil {
		t.Fatal(err)
	}
	next.Release()
	m := a.Metrics()
	if m.ConnectionCancellations != 1 || m.ActiveConnections != 0 {
		t.Fatalf("metrics after cancellation: %+v", m)
	}
}

func TestBandwidthScopesMonotonicAccountingAndLiveUpdate(t *testing.T) {
	clock := newFakeClock()
	limits := Limits{
		GlobalBytesPerSecond:   100,
		DownloadBytesPerSecond: map[string]int64{"a": 50},
		QueueBytesPerSecond:    map[string]int64{"q": 75},
		ProfileBytesPerSecond:  map[string]int64{"p": 80},
	}
	a, err := NewWithClock(limits, clock)
	if err != nil {
		t.Fatal(err)
	}
	h, err := a.Bind(Key{DownloadID: "a", Host: "one.test", Queue: "q", Profile: "p"})
	if err != nil {
		t.Fatal(err)
	}
	if err = h.WaitN(context.Background(), 50); err != nil {
		t.Fatal(err)
	}
	if err = h.WaitN(context.Background(), 50); err != nil {
		t.Fatal(err)
	}
	sleeps := clock.durations()
	if len(sleeps) != 1 || sleeps[0] != time.Second {
		t.Fatalf("initial throttling sleeps = %v", sleeps)
	}

	limits.GlobalBytesPerSecond = 200
	limits.DownloadBytesPerSecond["a"] = 100
	limits.QueueBytesPerSecond["q"] = 150
	limits.ProfileBytesPerSecond["p"] = 160
	if err = a.UpdateLimits(limits); err != nil {
		t.Fatal(err)
	}
	if err = h.WaitN(context.Background(), 50); err != nil {
		t.Fatal(err)
	}
	if err = h.WaitN(context.Background(), 50); err != nil {
		t.Fatal(err)
	}
	sleeps = clock.durations()
	if len(sleeps) != 3 || sleeps[1] != time.Second || sleeps[2] != 500*time.Millisecond {
		t.Fatalf("live update sleeps = %v", sleeps)
	}
	m := a.Metrics()
	if m.BandwidthBytes != 200 || m.BandwidthRequests != 4 || m.BytesByDownload["a"] != 200 {
		t.Fatalf("bandwidth metrics: %+v", m)
	}
}

func TestMetricsSnapshotIsDetached(t *testing.T) {
	a := mustArbiter(t, Limits{})
	h, _ := a.Bind(key("a", "one.test"))
	if err := h.WaitN(context.Background(), 12); err != nil {
		t.Fatal(err)
	}
	m := a.Metrics()
	m.BytesByDownload["a"] = 999
	if got := a.Metrics().BytesByDownload["a"]; got != 12 {
		t.Fatalf("metrics alias leaked: %d", got)
	}
}

func TestInvalidLimitsAndKey(t *testing.T) {
	if _, err := New(Limits{GlobalConnections: -1}); !errors.Is(err, ErrInvalidLimits) {
		t.Fatalf("invalid limits = %v", err)
	}
	a := mustArbiter(t, Limits{})
	if _, err := a.Bind(Key{}); !errors.Is(err, ErrInvalidKey) {
		t.Fatalf("invalid key = %v", err)
	}
	if err := a.WaitN(context.Background(), key("a", "one.test"), -1); !errors.Is(err, ErrInvalidKey) {
		t.Fatalf("negative wait = %v", err)
	}
}
