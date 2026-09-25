package artifact

import (
	"errors"
	"fmt"

	"github.com/subhra74/xdm/engine/domain/identity"
)

var (
	ErrInvalidArtifact   = errors.New("invalid verified artifact")
	ErrInvalidTransition = errors.New("invalid artifact publication transition")
)

type PublicationState string

const (
	Unpublished PublicationState = "unpublished"
	Publishing  PublicationState = "publishing"
	Published   PublicationState = "published"
)

var publicationTransitions = map[PublicationState][]PublicationState{
	Unpublished: {Publishing},
	Publishing:  {Published},
	Published:   nil,
}

func PublicationStates() []PublicationState {
	return []PublicationState{Unpublished, Publishing, Published}
}
func AllowedPublicationTransitions() map[PublicationState][]PublicationState {
	out := make(map[PublicationState][]PublicationState, len(publicationTransitions))
	for state, values := range publicationTransitions {
		out[state] = append([]PublicationState(nil), values...)
	}
	return out
}
func (s PublicationState) Terminal() bool { return s == Published }

type Artifact struct {
	downloadID    identity.DownloadID
	generation    identity.ArtifactGeneration
	sourceAttempt identity.AttemptGeneration
	size          int64
	publication   PublicationState
}

// NewVerified is intentionally named: an Artifact does not exist merely
// because transport bytes exist. XGO-17 later records the verification journal.
func NewVerified(downloadID identity.DownloadID, generation identity.ArtifactGeneration, sourceAttempt identity.AttemptGeneration, size int64) (Artifact, error) {
	if downloadID.IsZero() || !generation.Valid() || !sourceAttempt.Valid() || size < 0 {
		return Artifact{}, ErrInvalidArtifact
	}
	return Artifact{downloadID: downloadID, generation: generation, sourceAttempt: sourceAttempt, size: size, publication: Unpublished}, nil
}

func (a Artifact) DownloadID() identity.DownloadID           { return a.downloadID }
func (a Artifact) Generation() identity.ArtifactGeneration   { return a.generation }
func (a Artifact) SourceAttempt() identity.AttemptGeneration { return a.sourceAttempt }
func (a Artifact) Size() int64                               { return a.size }
func (a Artifact) PublicationState() PublicationState        { return a.publication }

func (a Artifact) TransitionPublication(to PublicationState) (Artifact, error) {
	for _, candidate := range publicationTransitions[a.publication] {
		if candidate == to {
			a.publication = to
			return a, nil
		}
	}
	return Artifact{}, fmt.Errorf("%w: %s -> %s", ErrInvalidTransition, a.publication, to)
}
