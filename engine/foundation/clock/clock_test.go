package clock_test

import (
	"errors"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/foundation/clock"
	"github.com/subhra74/xdm/engine/foundation/clock/clocktest"
)

func TestFakeSeparatesWallAndMonotonicTime(t *testing.T) {
	start := time.Date(2026, 9, 25, 3, 0, 0, 0, time.UTC)
	fake := clocktest.New(start)
	var source clock.Source = fake

	if err := fake.Advance(30 * time.Second); err != nil {
		t.Fatal(err)
	}
	fake.ShiftWall(-2 * time.Hour)

	if got := source.MonotonicNow(); got != 30*time.Second {
		t.Fatalf("monotonic=%s want=30s", got)
	}
	wantWall := start.Add(30*time.Second - 2*time.Hour)
	if got := source.WallNow(); !got.Equal(wantWall) {
		t.Fatalf("wall=%s want=%s", got, wantWall)
	}
}

func TestFakeRejectsNegativeMonotonicAdvance(t *testing.T) {
	fake := clocktest.New(time.Unix(0, 0))
	if err := fake.Advance(-time.Nanosecond); !errors.Is(err, clocktest.ErrNegativeAdvance) {
		t.Fatalf("err=%v", err)
	}
}

func TestSystemImplementsSource(t *testing.T) {
	var source clock.Source = clock.NewSystem()
	if source.WallNow().Location() != time.UTC {
		t.Fatal("system wall time must be UTC")
	}
	if source.MonotonicNow() < 0 {
		t.Fatal("system monotonic time moved backward")
	}
}
