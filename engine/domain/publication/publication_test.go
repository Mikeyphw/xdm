package publication_test

import (
	"errors"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/publication"
)

func TestPublicationStateMachineIsLinearAndTerminal(t *testing.T) {
	states := []publication.State{publication.Prepared, publication.PlatformCommitRequested, publication.PlatformCommitted, publication.EngineCommitted, publication.Cleaned}
	for i := 0; i < len(states)-1; i++ {
		if err := publication.ValidateTransition(states[i], states[i+1]); err != nil {
			t.Fatalf("%s -> %s: %v", states[i], states[i+1], err)
		}
	}
	if err := publication.ValidateTransition(publication.Cleaned, publication.Prepared); !errors.Is(err, publication.ErrInvalidTransition) {
		t.Fatalf("terminal state reopened: %v", err)
	}
	if !publication.Cleaned.Terminal() {
		t.Fatal("cleaned must be terminal")
	}
}

func TestCommitRequestAndReceiptCarryIdempotentIdentity(t *testing.T) {
	pub, _ := identity.ParsePublicationID("pub_00000000000000000000000000000001")
	dl, _ := identity.ParseDownloadID("dl_00000000000000000000000000000001")
	art, _ := identity.NewArtifactGeneration(1)
	req := publication.CommitRequest{PublicationID: pub, DownloadID: dl, Artifact: art, IdempotencyKey: "publish:1", StagingIdentity: "stage:1"}
	if err := req.Validate(); err != nil {
		t.Fatal(err)
	}
	receipt := publication.Receipt{PublicationID: pub, ReceiptID: "receipt:1", Location: "content://output/1"}
	if err := receipt.Validate(); err != nil {
		t.Fatal(err)
	}
	if publication.ActionFor(publication.PlatformCommitRequested) != publication.ActionInspectReceipt {
		t.Fatal("ambiguous request must reconcile receipt rather than blindly republish")
	}
}
