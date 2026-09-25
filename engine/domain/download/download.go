package download

import (
	"errors"
	"fmt"

	"github.com/subhra74/xdm/engine/domain/artifact"
	"github.com/subhra74/xdm/engine/domain/identity"
)

var (
	ErrInvalidDownload   = errors.New("invalid download")
	ErrInvalidTransition = errors.New("invalid download lifetime transition")
	ErrStaleAttempt      = errors.New("attempt generation is not current")
	ErrArtifactMismatch  = errors.New("artifact does not belong to current download execution")
)

type State string

const (
	Open      State = "open"
	Completed State = "completed"
	Deleted   State = "deleted"
)

var transitions = map[State][]State{
	Open:      {Completed, Deleted},
	Completed: {Deleted},
	Deleted:   nil,
}

func States() []State { return []State{Open, Completed, Deleted} }
func AllowedTransitions() map[State][]State {
	out := make(map[State][]State, len(transitions))
	for state, values := range transitions {
		out[state] = append([]State(nil), values...)
	}
	return out
}
func (s State) Terminal() bool { return s == Deleted }

type Download struct {
	id              identity.DownloadID
	requestID       identity.RequestID
	currentAttempt  *identity.AttemptGeneration
	currentArtifact *identity.ArtifactGeneration
	state           State
}

func New(id identity.DownloadID, requestID identity.RequestID) (Download, error) {
	if id.IsZero() || requestID.IsZero() {
		return Download{}, ErrInvalidDownload
	}
	return Download{id: id, requestID: requestID, state: Open}, nil
}

func (d Download) ID() identity.DownloadID       { return d.id }
func (d Download) RequestID() identity.RequestID { return d.requestID }
func (d Download) State() State                  { return d.state }
func (d Download) CurrentAttempt() (identity.AttemptGeneration, bool) {
	if d.currentAttempt == nil {
		return 0, false
	}
	return *d.currentAttempt, true
}
func (d Download) CurrentArtifact() (identity.ArtifactGeneration, bool) {
	if d.currentArtifact == nil {
		return 0, false
	}
	return *d.currentArtifact, true
}

func (d Download) BeginAttempt(generation identity.AttemptGeneration) (Download, error) {
	if d.state != Open || !generation.Valid() {
		return Download{}, ErrInvalidTransition
	}
	if d.currentAttempt != nil && generation.Int64() <= d.currentAttempt.Int64() {
		return Download{}, ErrStaleAttempt
	}
	copyGen := generation
	d.currentAttempt = &copyGen
	return d, nil
}

func (d Download) AcceptArtifact(value artifact.Artifact) (Download, error) {
	if d.state != Open || value.DownloadID() != d.id {
		return Download{}, ErrArtifactMismatch
	}
	if d.currentAttempt == nil || value.SourceAttempt() != *d.currentAttempt {
		return Download{}, ErrStaleAttempt
	}
	if d.currentArtifact != nil && value.Generation().Int64() <= d.currentArtifact.Int64() {
		return Download{}, ErrArtifactMismatch
	}
	gen := value.Generation()
	d.currentArtifact = &gen
	return d, nil
}

func (d Download) Complete(generation identity.ArtifactGeneration) (Download, error) {
	if d.state != Open || d.currentArtifact == nil || generation != *d.currentArtifact {
		return Download{}, ErrArtifactMismatch
	}
	d.state = Completed
	return d, nil
}

func (d Download) Delete() (Download, error) {
	if d.state == Deleted {
		return Download{}, fmt.Errorf("%w: %s -> %s", ErrInvalidTransition, d.state, Deleted)
	}
	d.state = Deleted
	return d, nil
}
