package clocktest

import (
	"errors"
	"sync"
	"time"
)

var ErrNegativeAdvance = errors.New("monotonic clock cannot advance by a negative duration")

// Fake is a deterministic clock for engine tests. Wall time can be shifted
// independently from monotonic time to exercise clock-correction scenarios.
type Fake struct {
	mu        sync.RWMutex
	wall      time.Time
	monotonic time.Duration
}

func New(wall time.Time) *Fake {
	return &Fake{wall: wall.UTC()}
}

func (f *Fake) WallNow() time.Time {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return f.wall
}

func (f *Fake) MonotonicNow() time.Duration {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return f.monotonic
}

func (f *Fake) Advance(d time.Duration) error {
	if d < 0 {
		return ErrNegativeAdvance
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	f.monotonic += d
	f.wall = f.wall.Add(d)
	return nil
}

// ShiftWall simulates a civil-clock correction without altering elapsed
// monotonic time.
func (f *Fake) ShiftWall(d time.Duration) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.wall = f.wall.Add(d)
}
