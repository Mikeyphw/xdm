package artifact_test

import (
	"errors"
	"testing"

	"github.com/subhra74/xdm/engine/domain/artifact"
	"github.com/subhra74/xdm/engine/domain/identity"
)

func sample(t *testing.T) artifact.Artifact {
	t.Helper()
	id, _ := identity.ParseDownloadID("dl_00000000000000000000000000000001")
	ag, _ := identity.NewArtifactGeneration(1)
	attempt, _ := identity.NewAttemptGeneration(2)
	value, err := artifact.NewVerified(id, ag, attempt, 1024)
	if err != nil {
		t.Fatal(err)
	}
	return value
}

func TestArtifactStartsVerifiedButUnpublishedAndHasExplicitPublicationState(t *testing.T) {
	value := sample(t)
	if value.PublicationState() != artifact.Unpublished {
		t.Fatalf("state=%s", value.PublicationState())
	}
	value, err := value.TransitionPublication(artifact.Publishing)
	if err != nil {
		t.Fatal(err)
	}
	value, err = value.TransitionPublication(artifact.Published)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := value.TransitionPublication(artifact.Unpublished); !errors.Is(err, artifact.ErrInvalidTransition) {
		t.Fatalf("published artifact reopened: %v", err)
	}
}

func TestArtifactCannotBeCreatedWithoutVerifiedSourceIdentity(t *testing.T) {
	id, _ := identity.ParseDownloadID("dl_00000000000000000000000000000001")
	ag, _ := identity.NewArtifactGeneration(1)
	if _, err := artifact.NewVerified(id, ag, 0, 1); !errors.Is(err, artifact.ErrInvalidArtifact) {
		t.Fatalf("zero source attempt accepted: %v", err)
	}
}
