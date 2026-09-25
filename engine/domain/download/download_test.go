package download_test

import (
	"errors"
	"testing"

	"github.com/subhra74/xdm/engine/domain/artifact"
	"github.com/subhra74/xdm/engine/domain/download"
	"github.com/subhra74/xdm/engine/domain/identity"
)

func ids() (identity.DownloadID, identity.RequestID) {
	d, _ := identity.ParseDownloadID("dl_00000000000000000000000000000001")
	r, _ := identity.ParseRequestID("req_00000000000000000000000000000001")
	return d, r
}

func TestNewAttemptLeavesPriorGenerationHistoricalAndFencesStaleArtifact(t *testing.T) {
	did, rid := ids()
	d, _ := download.New(did, rid)
	g1, _ := identity.NewAttemptGeneration(1)
	g2, _ := identity.NewAttemptGeneration(2)
	d, _ = d.BeginAttempt(g1)
	d, _ = d.BeginAttempt(g2)
	current, ok := d.CurrentAttempt()
	if !ok || current != g2 {
		t.Fatalf("current=%v ok=%v", current, ok)
	}

	artGen, _ := identity.NewArtifactGeneration(1)
	staleArtifact, _ := artifact.NewVerified(did, artGen, g1, 10)
	if _, err := d.AcceptArtifact(staleArtifact); !errors.Is(err, download.ErrStaleAttempt) {
		t.Fatalf("stale artifact accepted: %v", err)
	}
}

func TestDownloadCompletesOnlyThroughCurrentVerifiedArtifact(t *testing.T) {
	did, rid := ids()
	d, _ := download.New(did, rid)
	attemptGen, _ := identity.NewAttemptGeneration(4)
	artifactGen, _ := identity.NewArtifactGeneration(2)
	d, _ = d.BeginAttempt(attemptGen)
	if _, err := d.Complete(artifactGen); !errors.Is(err, download.ErrArtifactMismatch) {
		t.Fatalf("download completed without artifact: %v", err)
	}
	a, _ := artifact.NewVerified(did, artifactGen, attemptGen, 42)
	d, err := d.AcceptArtifact(a)
	if err != nil {
		t.Fatal(err)
	}
	d, err = d.Complete(artifactGen)
	if err != nil {
		t.Fatal(err)
	}
	if d.State() != download.Completed {
		t.Fatalf("state=%s", d.State())
	}
	if _, err := d.BeginAttempt(attemptGen); !errors.Is(err, download.ErrInvalidTransition) {
		t.Fatalf("completed download started attempt: %v", err)
	}
}
