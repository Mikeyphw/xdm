package arbitration

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
)

var (
	ErrInvalidKey    = errors.New("invalid arbitration key")
	ErrInvalidLimits = errors.New("invalid arbitration limits")
)

type Key struct {
	DownloadID string `json:"download_id"`
	Host       string `json:"host"`
	Queue      string `json:"queue,omitempty"`
	Profile    string `json:"profile,omitempty"`
}

func (k Key) normalized() (Key, error) {
	k.DownloadID = strings.TrimSpace(k.DownloadID)
	k.Host = strings.ToLower(strings.TrimSpace(k.Host))
	k.Queue = strings.TrimSpace(k.Queue)
	k.Profile = strings.TrimSpace(k.Profile)
	if k.DownloadID == "" || k.Host == "" {
		return Key{}, ErrInvalidKey
	}
	return k, nil
}

type Limits struct {
	GlobalConnections          int              `json:"global_connections"`
	DefaultHostConnections     int              `json:"default_host_connections"`
	DefaultDownloadConnections int              `json:"default_download_connections"`
	HostConnections            map[string]int   `json:"host_connections,omitempty"`
	DownloadConnections        map[string]int   `json:"download_connections,omitempty"`
	GlobalBytesPerSecond       int64            `json:"global_bytes_per_second"`
	QueueBytesPerSecond        map[string]int64 `json:"queue_bytes_per_second,omitempty"`
	ProfileBytesPerSecond      map[string]int64 `json:"profile_bytes_per_second,omitempty"`
	DownloadBytesPerSecond     map[string]int64 `json:"download_bytes_per_second,omitempty"`
}

func cloneLimits(in Limits) Limits {
	out := in
	out.HostConnections = cloneIntMap(in.HostConnections)
	out.DownloadConnections = cloneIntMap(in.DownloadConnections)
	out.QueueBytesPerSecond = cloneInt64Map(in.QueueBytesPerSecond)
	out.ProfileBytesPerSecond = cloneInt64Map(in.ProfileBytesPerSecond)
	out.DownloadBytesPerSecond = cloneInt64Map(in.DownloadBytesPerSecond)
	return out
}

func cloneIntMap(in map[string]int) map[string]int {
	if len(in) == 0 {
		return nil
	}
	out := make(map[string]int, len(in))
	for k, v := range in {
		out[k] = v
	}
	return out
}

func cloneInt64Map(in map[string]int64) map[string]int64 {
	if len(in) == 0 {
		return nil
	}
	out := make(map[string]int64, len(in))
	for k, v := range in {
		out[k] = v
	}
	return out
}

func (l Limits) Validate() error {
	if l.GlobalConnections < 0 || l.DefaultHostConnections < 0 || l.DefaultDownloadConnections < 0 || l.GlobalBytesPerSecond < 0 {
		return ErrInvalidLimits
	}
	for _, v := range l.HostConnections {
		if v < 0 {
			return ErrInvalidLimits
		}
	}
	for _, v := range l.DownloadConnections {
		if v < 0 {
			return ErrInvalidLimits
		}
	}
	for _, m := range []map[string]int64{l.QueueBytesPerSecond, l.ProfileBytesPerSecond, l.DownloadBytesPerSecond} {
		for _, v := range m {
			if v < 0 {
				return ErrInvalidLimits
			}
		}
	}
	return nil
}

type Clock interface {
	Now() time.Time
	SleepUntil(context.Context, time.Time) error
}

type RealClock struct{}

func (RealClock) Now() time.Time { return time.Now() }
func (RealClock) SleepUntil(ctx context.Context, at time.Time) error {
	d := time.Until(at)
	if d <= 0 {
		return nil
	}
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

type Metrics struct {
	LimitsRevision          uint64           `json:"limits_revision"`
	ActiveConnections       int              `json:"active_connections"`
	PeakConnections         int              `json:"peak_connections"`
	ActiveByHost            map[string]int   `json:"active_by_host"`
	ActiveByDownload        map[string]int   `json:"active_by_download"`
	ConnectionAcquisitions  uint64           `json:"connection_acquisitions"`
	ConnectionWaits         uint64           `json:"connection_waits"`
	ConnectionCancellations uint64           `json:"connection_cancellations"`
	BandwidthRequests       uint64           `json:"bandwidth_requests"`
	BandwidthBytes          int64            `json:"bandwidth_bytes"`
	BandwidthWaitNanos      int64            `json:"bandwidth_wait_nanos"`
	BytesByDownload         map[string]int64 `json:"bytes_by_download"`
}

type connWaiter struct {
	id  uint64
	key Key
}

type bandWaiter struct {
	id  uint64
	key Key
	n   int
}

type Arbiter struct {
	mu sync.Mutex

	limits   Limits
	revision uint64
	clock    Clock

	activeTotal     int
	activeHost      map[string]int
	activeDownload  map[string]int
	peakConnections int
	waiters         []*connWaiter
	nextWaiterID    uint64
	notify          chan struct{}

	bandNext    map[string]time.Time
	bandWaiters []*bandWaiter
	bandActive  map[string]bool
	nextBandID  uint64
	metrics     Metrics
}

func New(l Limits) (*Arbiter, error) { return NewWithClock(l, RealClock{}) }

func NewWithClock(l Limits, clock Clock) (*Arbiter, error) {
	if err := l.Validate(); err != nil || clock == nil {
		if err != nil {
			return nil, err
		}
		return nil, ErrInvalidLimits
	}
	a := &Arbiter{
		limits:         cloneLimits(l),
		revision:       1,
		clock:          clock,
		activeHost:     map[string]int{},
		activeDownload: map[string]int{},
		notify:         make(chan struct{}),
		bandNext:       map[string]time.Time{},
		bandActive:     map[string]bool{},
	}
	a.metrics.LimitsRevision = 1
	return a, nil
}

func (a *Arbiter) signalLocked() {
	close(a.notify)
	a.notify = make(chan struct{})
}

func (a *Arbiter) hostLimitLocked(host string) int {
	if v, ok := a.limits.HostConnections[host]; ok {
		return v
	}
	return a.limits.DefaultHostConnections
}

func (a *Arbiter) downloadLimitLocked(download string) int {
	if v, ok := a.limits.DownloadConnections[download]; ok {
		return v
	}
	return a.limits.DefaultDownloadConnections
}

func under(current, limit int) bool { return limit == 0 || current < limit }

func (a *Arbiter) admissibleLocked(k Key) bool {
	return under(a.activeTotal, a.limits.GlobalConnections) &&
		under(a.activeHost[k.Host], a.hostLimitLocked(k.Host)) &&
		under(a.activeDownload[k.DownloadID], a.downloadLimitLocked(k.DownloadID))
}

func (a *Arbiter) firstAdmissibleLocked() int {
	for i, w := range a.waiters {
		if a.admissibleLocked(w.key) {
			return i
		}
	}
	return -1
}

func removeWaiter(in []*connWaiter, id uint64) ([]*connWaiter, bool) {
	for i, w := range in {
		if w.id == id {
			copy(in[i:], in[i+1:])
			return in[:len(in)-1], true
		}
	}
	return in, false
}

func (a *Arbiter) Acquire(ctx context.Context, key Key) (*Lease, error) {
	key, err := key.normalized()
	if err != nil {
		return nil, err
	}
	if ctx == nil {
		ctx = context.Background()
	}
	a.mu.Lock()
	a.nextWaiterID++
	w := &connWaiter{id: a.nextWaiterID, key: key}
	a.waiters = append(a.waiters, w)
	waited := false
	for {
		idx := a.firstAdmissibleLocked()
		mine := -1
		for i, candidate := range a.waiters {
			if candidate.id == w.id {
				mine = i
				break
			}
		}
		if idx >= 0 && idx == mine {
			a.waiters = append(a.waiters[:mine], a.waiters[mine+1:]...)
			a.activeTotal++
			a.activeHost[key.Host]++
			a.activeDownload[key.DownloadID]++
			if a.activeTotal > a.peakConnections {
				a.peakConnections = a.activeTotal
			}
			a.metrics.ConnectionAcquisitions++
			if waited {
				a.metrics.ConnectionWaits++
			}
			a.metrics.ActiveConnections = a.activeTotal
			a.metrics.PeakConnections = a.peakConnections
			a.signalLocked()
			a.mu.Unlock()
			return &Lease{arbiter: a, key: key}, nil
		}
		notify := a.notify
		waited = true
		a.mu.Unlock()
		select {
		case <-ctx.Done():
			a.mu.Lock()
			var removed bool
			a.waiters, removed = removeWaiter(a.waiters, w.id)
			if removed {
				a.metrics.ConnectionCancellations++
				a.signalLocked()
			}
			a.mu.Unlock()
			return nil, ctx.Err()
		case <-notify:
			a.mu.Lock()
		}
	}
}

type Lease struct {
	arbiter *Arbiter
	key     Key
	once    sync.Once
}

func (l *Lease) Release() {
	if l == nil || l.arbiter == nil {
		return
	}
	l.once.Do(func() {
		a := l.arbiter
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.activeTotal > 0 {
			a.activeTotal--
		}
		if a.activeHost[l.key.Host] > 1 {
			a.activeHost[l.key.Host]--
		} else {
			delete(a.activeHost, l.key.Host)
		}
		if a.activeDownload[l.key.DownloadID] > 1 {
			a.activeDownload[l.key.DownloadID]--
		} else {
			delete(a.activeDownload, l.key.DownloadID)
		}
		a.metrics.ActiveConnections = a.activeTotal
		a.signalLocked()
	})
}

func (a *Arbiter) Bind(key Key) (*Handle, error) {
	k, err := key.normalized()
	if err != nil {
		return nil, err
	}
	return &Handle{arbiter: a, key: k}, nil
}

type Handle struct {
	arbiter *Arbiter
	key     Key
}

func (h *Handle) AcquireConnection(ctx context.Context) (func(), error) {
	if h == nil || h.arbiter == nil {
		return nil, ErrInvalidKey
	}
	lease, err := h.arbiter.Acquire(ctx, h.key)
	if err != nil {
		return nil, err
	}
	return lease.Release, nil
}

func (h *Handle) WaitN(ctx context.Context, n int) error {
	if h == nil || h.arbiter == nil {
		return ErrInvalidKey
	}
	return h.arbiter.WaitN(ctx, h.key, n)
}

func durationForBytes(n int, rate int64) time.Duration {
	if n <= 0 || rate <= 0 {
		return 0
	}
	ns := (int64(n)*int64(time.Second) + rate - 1) / rate
	if ns < 1 {
		ns = 1
	}
	return time.Duration(ns)
}

type rateScope struct {
	id   string
	rate int64
}

func (a *Arbiter) rateScopesLocked(k Key) []rateScope {
	out := make([]rateScope, 0, 4)
	if a.limits.GlobalBytesPerSecond > 0 {
		out = append(out, rateScope{"global", a.limits.GlobalBytesPerSecond})
	}
	if k.Queue != "" {
		if rate := a.limits.QueueBytesPerSecond[k.Queue]; rate > 0 {
			out = append(out, rateScope{"queue:" + k.Queue, rate})
		}
	}
	if k.Profile != "" {
		if rate := a.limits.ProfileBytesPerSecond[k.Profile]; rate > 0 {
			out = append(out, rateScope{"profile:" + k.Profile, rate})
		}
	}
	if rate := a.limits.DownloadBytesPerSecond[k.DownloadID]; rate > 0 {
		out = append(out, rateScope{"download:" + k.DownloadID, rate})
	}
	return out
}

func removeBandWaiter(in []*bandWaiter, id uint64) ([]*bandWaiter, bool) {
	for i, w := range in {
		if w.id == id {
			copy(in[i:], in[i+1:])
			return in[:len(in)-1], true
		}
	}
	return in, false
}

func (a *Arbiter) firstBandwidthAdmissibleLocked() int {
	for i, w := range a.bandWaiters {
		if !a.bandActive[w.key.DownloadID] {
			return i
		}
	}
	return -1
}

func (a *Arbiter) WaitN(ctx context.Context, key Key, n int) error {
	if n < 0 {
		return ErrInvalidKey
	}
	if n == 0 {
		return nil
	}
	key, err := key.normalized()
	if err != nil {
		return err
	}
	if ctx == nil {
		ctx = context.Background()
	}

	a.mu.Lock()
	a.nextBandID++
	w := &bandWaiter{id: a.nextBandID, key: key, n: n}
	a.bandWaiters = append(a.bandWaiters, w)
	for {
		idx := a.firstBandwidthAdmissibleLocked()
		mine := -1
		for i, candidate := range a.bandWaiters {
			if candidate.id == w.id {
				mine = i
				break
			}
		}
		if idx >= 0 && idx == mine {
			a.bandWaiters = append(a.bandWaiters[:mine], a.bandWaiters[mine+1:]...)
			a.bandActive[key.DownloadID] = true
			now := a.clock.Now()
			ready := now
			scopes := a.rateScopesLocked(key)
			previous := make(map[string]time.Time, len(scopes))
			reserved := make(map[string]time.Time, len(scopes))
			for _, scope := range scopes {
				previous[scope.id] = a.bandNext[scope.id]
				if next := a.bandNext[scope.id]; next.After(ready) {
					ready = next
				}
			}
			for _, scope := range scopes {
				next := ready.Add(durationForBytes(n, scope.rate))
				a.bandNext[scope.id] = next
				reserved[scope.id] = next
			}
			a.metrics.BandwidthRequests++
			a.metrics.BandwidthBytes += int64(n)
			if a.metrics.BytesByDownload == nil {
				a.metrics.BytesByDownload = map[string]int64{}
			}
			a.metrics.BytesByDownload[key.DownloadID] += int64(n)
			if ready.After(now) {
				a.metrics.BandwidthWaitNanos += ready.Sub(now).Nanoseconds()
			}
			a.signalLocked()
			a.mu.Unlock()

			var sleepErr error
			if ready.After(now) {
				sleepErr = a.clock.SleepUntil(ctx, ready)
			}

			a.mu.Lock()
			delete(a.bandActive, key.DownloadID)
			if sleepErr != nil {
				// Reclaim this reservation when no later request has extended the
				// same scope. If a later reservation exists, preserving the gap is
				// safer than moving already-promised grant times backwards.
				for scope, next := range reserved {
					if a.bandNext[scope].Equal(next) {
						a.bandNext[scope] = previous[scope]
					}
				}
			}
			a.signalLocked()
			a.mu.Unlock()
			return sleepErr
		}
		notify := a.notify
		a.mu.Unlock()
		select {
		case <-ctx.Done():
			a.mu.Lock()
			var removed bool
			a.bandWaiters, removed = removeBandWaiter(a.bandWaiters, w.id)
			if removed {
				a.signalLocked()
			}
			a.mu.Unlock()
			return ctx.Err()
		case <-notify:
			a.mu.Lock()
		}
	}
}

func (a *Arbiter) UpdateLimits(l Limits) error {
	if err := l.Validate(); err != nil {
		return err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.limits = cloneLimits(l)
	a.revision++
	a.metrics.LimitsRevision = a.revision
	a.signalLocked()
	return nil
}

func (a *Arbiter) Limits() Limits {
	a.mu.Lock()
	defer a.mu.Unlock()
	return cloneLimits(a.limits)
}

func (a *Arbiter) Metrics() Metrics {
	a.mu.Lock()
	defer a.mu.Unlock()
	out := a.metrics
	out.ActiveConnections = a.activeTotal
	out.PeakConnections = a.peakConnections
	out.ActiveByHost = cloneIntMap(a.activeHost)
	out.ActiveByDownload = cloneIntMap(a.activeDownload)
	out.BytesByDownload = cloneInt64Map(a.metrics.BytesByDownload)
	return out
}

func (a *Arbiter) String() string {
	m := a.Metrics()
	return fmt.Sprintf("connections=%d peak=%d bytes=%d revision=%d", m.ActiveConnections, m.PeakConnections, m.BandwidthBytes, m.LimitsRevision)
}
