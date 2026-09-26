package androidhost

import (
	"errors"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/publication"
)

func androidPublicationRequest(t *testing.T, destination AndroidDestinationSpec) AndroidPublicationRequest {
	t.Helper()
	pub, err := identity.ParsePublicationID("pub_00000000000000000000000000000009")
	if err != nil {
		t.Fatal(err)
	}
	dl, err := identity.ParseDownloadID("dl_00000000000000000000000000000009")
	if err != nil {
		t.Fatal(err)
	}
	art, err := identity.NewArtifactGeneration(1)
	if err != nil {
		t.Fatal(err)
	}
	return AndroidPublicationRequest{
		Commit:      publication.CommitRequest{PublicationID: pub, DownloadID: dl, Artifact: art, IdempotencyKey: "android-pub:09", StagingIdentity: "stage:09"},
		Destination: destination,
		SizeBytes:   64,
		ContentHash: "sha256:fixture",
	}
}

func documentTreeDestination() AndroidDestinationSpec {
	return AndroidDestinationSpec{Kind: DestinationDocumentTree, TreeURI: "content://tree/downloads", RelativePath: "Videos", DisplayName: "movie.mp4", MIMEType: "video/mp4", PermissionID: "tree-downloads", Collision: CollisionFail}
}

func mediaStoreDestination() AndroidDestinationSpec {
	return AndroidDestinationSpec{Kind: DestinationMediaStore, CollectionURI: "content://media/external/video/media", RelativePath: "Movies/XDM", DisplayName: "clip.mp4", MIMEType: "video/mp4", Collision: CollisionRename}
}

func TestTranslateAndroidDestinationRequiresPersistableGrantForSAF(t *testing.T) {
	target, err := TranslateAndroidDestination(documentTreeDestination())
	if err != nil {
		t.Fatal(err)
	}
	if target.Scheme != "saf" || !target.RequiresGrant || target.PermissionID != "tree-downloads" {
		t.Fatalf("bad document tree target: %+v", target)
	}
	media, err := TranslateAndroidDestination(mediaStoreDestination())
	if err != nil {
		t.Fatal(err)
	}
	if media.Scheme != "mediastore" || media.RequiresGrant {
		t.Fatalf("bad mediastore target: %+v", media)
	}
}

func TestAndroidPublicationCommitIsDurableAndIdempotentAcrossRestart(t *testing.T) {
	broker := NewAndroidPublicationBroker(nil, NewAndroidPermissionGrants("tree-downloads"), 1024)
	req := androidPublicationRequest(t, documentTreeDestination())
	first, err := broker.Commit(req)
	if err != nil {
		t.Fatal(err)
	}
	restarted := NewAndroidPublicationBroker(broker.DurableStore(), NewAndroidPermissionGrants("tree-downloads"), 1024)
	second, err := restarted.Commit(req)
	if err != nil {
		t.Fatal(err)
	}
	if second.ReceiptID != first.ReceiptID || second.ProviderID != first.ProviderID {
		t.Fatalf("duplicate publication after restart: first=%+v second=%+v", first, second)
	}
	if got := len(restarted.DurableStore().ProviderByKey); got != 1 {
		t.Fatalf("provider commit duplicated: %d", got)
	}
}

func TestAndroidPublicationCrashAfterProviderCommitReconcilesReceipt(t *testing.T) {
	broker := NewAndroidPublicationBroker(nil, NewAndroidPermissionGrants("tree-downloads"), 1024)
	req := androidPublicationRequest(t, documentTreeDestination())
	req.Fault = FaultCrashBeforeReceipt
	if _, err := broker.Commit(req); !errors.Is(err, ErrProviderCommitAmbiguous) {
		t.Fatalf("expected ambiguous crash, got %v", err)
	}
	restarted := NewAndroidPublicationBroker(broker.DurableStore(), NewAndroidPermissionGrants("tree-downloads"), 1024)
	action, receipt, err := restarted.Reconcile(publication.InspectRequest{PublicationID: req.Commit.PublicationID, IdempotencyKey: req.Commit.IdempotencyKey})
	if err != nil {
		t.Fatal(err)
	}
	if action != publication.ActionCommitEngine || receipt.ReceiptID == "" {
		t.Fatalf("bad reconcile action=%s receipt=%+v", action, receipt)
	}
	if len(restarted.DurableStore().ProviderByKey) != 1 || len(restarted.DurableStore().ReceiptsByKey) != 1 {
		t.Fatalf("reconcile did not persist one provider/receipt: %+v", restarted.DurableStore())
	}
}

func TestAndroidPublicationPermissionCollisionAndStorageFailures(t *testing.T) {
	req := androidPublicationRequest(t, documentTreeDestination())
	denied := NewAndroidPublicationBroker(nil, NewAndroidPermissionGrants(), 1024)
	if _, err := denied.Commit(req); !errors.Is(err, ErrPermissionLost) {
		t.Fatalf("permission loss not mapped: %v", err)
	}
	tiny := NewAndroidPublicationBroker(nil, NewAndroidPermissionGrants("tree-downloads"), 10)
	if _, err := tiny.Commit(req); !errors.Is(err, ErrInsufficientSpace) {
		t.Fatalf("insufficient space not mapped: %v", err)
	}
	broker := NewAndroidPublicationBroker(nil, NewAndroidPermissionGrants("tree-downloads"), 1024)
	if _, err := broker.Commit(req); err != nil {
		t.Fatal(err)
	}
	second := req
	second.Commit.IdempotencyKey = "android-pub:10"
	if _, err := broker.Commit(second); !errors.Is(err, ErrPublicationCollision) {
		t.Fatalf("collision not mapped: %v", err)
	}
	second.Destination.Collision = CollisionRename
	receipt, err := broker.Commit(second)
	if err != nil {
		t.Fatal(err)
	}
	if receipt.Target.DisplayName == req.Destination.DisplayName {
		t.Fatalf("rename collision policy did not rename: %+v", receipt.Target)
	}
}
