package attempt

import (
	"errors"
	"fmt"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
)

var (
	ErrInvalidAttempt    = errors.New("invalid attempt")
	ErrInvalidTransition = errors.New("invalid attempt state transition")
	ErrFailureRequired   = errors.New("failed attempt requires typed failure metadata")
)

type State string

const (
	Reserved         State = "reserved"
	Prepared         State = "prepared"
	Running          State = "running"
	Paused           State = "paused"
	ProducedArtifact State = "produced_artifact"
	Failed           State = "failed"
	Cancelled        State = "cancelled"
)

var transitions = map[State][]State{
	Reserved:         {Prepared, Failed, Cancelled},
	Prepared:         {Running, Failed, Cancelled},
	Running:          {Paused, ProducedArtifact, Failed, Cancelled},
	Paused:           {Running, Failed, Cancelled},
	ProducedArtifact: nil,
	Failed:           nil,
	Cancelled:        nil,
}

func States() []State {
	return []State{Reserved, Prepared, Running, Paused, ProducedArtifact, Failed, Cancelled}
}
func AllowedTransitions() map[State][]State {
	out := make(map[State][]State, len(transitions))
	for state, values := range transitions {
		out[state] = append([]State(nil), values...)
	}
	return out
}
func (s State) Terminal() bool { return s == ProducedArtifact || s == Failed || s == Cancelled }
func (s State) Valid() bool    { _, ok := transitions[s]; return ok }

type Attempt struct {
	downloadID identity.DownloadID
	generation identity.AttemptGeneration
	backend    string
	state      State
	failure    *failure.Failure
}

func New(downloadID identity.DownloadID, generation identity.AttemptGeneration, backend string) (Attempt, error) {
	if downloadID.IsZero() || !generation.Valid() || backend == "" {
		return Attempt{}, ErrInvalidAttempt
	}
	return Attempt{downloadID: downloadID, generation: generation, backend: backend, state: Reserved}, nil
}

func (a Attempt) DownloadID() identity.DownloadID        { return a.downloadID }
func (a Attempt) Generation() identity.AttemptGeneration { return a.generation }
func (a Attempt) Backend() string                        { return a.backend }
func (a Attempt) State() State                           { return a.state }
func (a Attempt) Failure() (failure.Failure, bool) {
	if a.failure == nil {
		return failure.Failure{}, false
	}
	return *a.failure, true
}

func canTransition(from, to State) bool {
	for _, candidate := range transitions[from] {
		if candidate == to {
			return true
		}
	}
	return false
}

func (a Attempt) Transition(to State) (Attempt, error) {
	if !a.state.Valid() || !to.Valid() || !canTransition(a.state, to) {
		return Attempt{}, fmt.Errorf("%w: %s -> %s", ErrInvalidTransition, a.state, to)
	}
	if to == Failed {
		return Attempt{}, ErrFailureRequired
	}
	a.state = to
	a.failure = nil
	return a, nil
}

func (a Attempt) Fail(info failure.Failure) (Attempt, error) {
	if err := info.Validate(); err != nil {
		return Attempt{}, err
	}
	if !canTransition(a.state, Failed) {
		return Attempt{}, fmt.Errorf("%w: %s -> %s", ErrInvalidTransition, a.state, Failed)
	}
	a.state = Failed
	a.failure = &info
	return a, nil
}

func (a Attempt) Cancel() (Attempt, error) { return a.Transition(Cancelled) }
