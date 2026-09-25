package clock

import "time"

// Source separates civil wall time from monotonic elapsed time. Scheduling,
// retry, timeout, and rate calculations should consume MonotonicNow when they
// need elapsed duration so wall-clock corrections cannot move them backward.
type Source interface {
	WallNow() time.Time
	MonotonicNow() time.Duration
}

// System is the production clock. Construct it with NewSystem so the
// monotonic origin is explicit and stable for the lifetime of the instance.
type System struct {
	origin time.Time
}

func NewSystem() *System {
	return &System{origin: time.Now()}
}

func (s *System) WallNow() time.Time {
	return time.Now().UTC()
}

func (s *System) MonotonicNow() time.Duration {
	if s == nil || s.origin.IsZero() {
		return 0
	}
	return time.Since(s.origin)
}
