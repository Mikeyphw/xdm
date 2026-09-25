package attempt_test

import (
	"errors"
	"testing"

	"github.com/subhra74/xdm/engine/domain/attempt"
	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
)

func sample(t *testing.T) attempt.Attempt {
	t.Helper()
	id, _ := identity.ParseDownloadID("dl_00000000000000000000000000000001")
	gen, _ := identity.NewAttemptGeneration(1)
	a, err := attempt.New(id, gen, "native")
	if err != nil {
		t.Fatal(err)
	}
	return a
}

func TestAttemptTransitionTableIsExhaustive(t *testing.T) {
	allowed := attempt.AllowedTransitions()
	states := attempt.States()
	for _, from := range states {
		for _, to := range states {
			a := sample(t)
			// Reach the requested source state through known minimal paths.
			var err error
			switch from {
			case attempt.Prepared:
				a, err = a.Transition(attempt.Prepared)
			case attempt.Running:
				a, err = a.Transition(attempt.Prepared)
				if err == nil {
					a, err = a.Transition(attempt.Running)
				}
			case attempt.Paused:
				a, err = a.Transition(attempt.Prepared)
				if err == nil {
					a, err = a.Transition(attempt.Running)
				}
				if err == nil {
					a, err = a.Transition(attempt.Paused)
				}
			case attempt.ProducedArtifact:
				a, err = a.Transition(attempt.Prepared)
				if err == nil {
					a, err = a.Transition(attempt.Running)
				}
				if err == nil {
					a, err = a.Transition(attempt.ProducedArtifact)
				}
			case attempt.Failed:
				info, _ := failure.NewDefault(failure.NetworkUnavailable, nil)
				a, err = a.Fail(info)
			case attempt.Cancelled:
				a, err = a.Cancel()
			}
			if err != nil {
				t.Fatalf("reach %s: %v", from, err)
			}
			expected := false
			for _, candidate := range allowed[from] {
				if candidate == to {
					expected = true
				}
			}
			var gotErr error
			if to == attempt.Failed {
				info, _ := failure.NewDefault(failure.NetworkUnavailable, nil)
				_, gotErr = a.Fail(info)
			} else {
				_, gotErr = a.Transition(to)
			}
			if expected == (gotErr != nil) {
				t.Fatalf("transition %s -> %s expectedAllowed=%v err=%v", from, to, expected, gotErr)
			}
		}
	}
}

func TestAttemptOwnsFailureCancellationAndTerminalImmutability(t *testing.T) {
	a := sample(t)
	a, _ = a.Transition(attempt.Prepared)
	a, _ = a.Transition(attempt.Running)
	info, _ := failure.NewDefault(failure.RateLimited, errors.New("Retry-After: secret-ish-cause"))
	failed, err := a.Fail(info)
	if err != nil {
		t.Fatal(err)
	}
	stored, ok := failed.Failure()
	if !ok || stored.Category != failure.RateLimited {
		t.Fatal("typed failure was not retained")
	}
	if _, err := failed.Cancel(); !errors.Is(err, attempt.ErrInvalidTransition) {
		t.Fatalf("terminal attempt mutated: %v", err)
	}
}
