package media

import (
	"errors"
	"testing"
)

func TestFragmentLedgerDuplicateCommitStaleAndReconstruction(t *testing.T) {
	ident := FragmentIdentity{Protocol: FragmentProtocolHLS, TimelineKey: "hls:0:100", ResourceID: "res-a", Range: &ByteRange{Offset: 0, Length: 100}, EncryptionID: "aes-key"}
	ledger, err := NewFragmentLedger()
	if err != nil {
		t.Fatal(err)
	}
	pending, err := ledger.Upsert(FragmentRecord{Identity: ident, ExpectedLength: 100, AttemptGeneration: 2, State: FragmentPending})
	if err != nil {
		t.Fatal(err)
	}
	duplicate, err := ledger.Upsert(FragmentRecord{Identity: ident, ExpectedLength: 100, AttemptGeneration: 2, State: FragmentPending})
	if err != nil {
		t.Fatal(err)
	}
	if duplicate.ID != pending.ID || len(ledger.Snapshot()) != 1 {
		t.Fatalf("duplicate insert was not idempotent: %+v", ledger.Snapshot())
	}
	_, err = ledger.Commit(FragmentRecord{Identity: ident, ExpectedLength: 100, Hash: "sha256:abc", AttemptGeneration: 1, LocalArtifactRef: "seg.ts", DurableBytesWritten: true})
	if !errors.Is(err, ErrFragmentStale) {
		t.Fatalf("expected stale generation error, got %v", err)
	}
	_, err = ledger.Commit(FragmentRecord{Identity: ident, ExpectedLength: 100, Hash: "sha256:abc", AttemptGeneration: 2, LocalArtifactRef: "seg.ts"})
	if !errors.Is(err, ErrFragmentDurability) {
		t.Fatalf("expected durable-before-record error, got %v", err)
	}
	committed, err := ledger.Commit(FragmentRecord{Identity: ident, ExpectedLength: 100, Hash: "sha256:abc", AttemptGeneration: 2, LocalArtifactRef: "seg.ts", DurableBytesWritten: true})
	if err != nil {
		t.Fatal(err)
	}
	if committed.State != FragmentCommitted {
		t.Fatalf("expected committed state, got %+v", committed)
	}
	reconstructed, err := NewFragmentLedger(ledger.Snapshot()...)
	if err != nil {
		t.Fatal(err)
	}
	got, ok := reconstructed.Get(ident)
	if !ok || got.State != FragmentCommitted || got.Hash != "sha256:abc" {
		t.Fatalf("restart reconstruction lost committed fragment: %+v %v", got, ok)
	}
	corrupt, err := reconstructed.MarkCorrupt(ident, 3, "hash_mismatch")
	if err != nil {
		t.Fatal(err)
	}
	if corrupt.State != FragmentCorrupt || corrupt.RetryState != "hash_mismatch" {
		t.Fatalf("corruption/retry state not recorded: %+v", corrupt)
	}
}

func TestFragmentIdentityIncludesProtocolRangeAndEncryption(t *testing.T) {
	base := FragmentIdentity{Protocol: FragmentProtocolHLS, TimelineKey: "hls:0:1", ResourceID: "res"}
	ranged := base
	ranged.Range = &ByteRange{Offset: 10, Length: 20}
	encrypted := base
	encrypted.EncryptionID = "key-a"
	idBase, err := FragmentID(base)
	if err != nil {
		t.Fatal(err)
	}
	idRange, err := FragmentID(ranged)
	if err != nil {
		t.Fatal(err)
	}
	idEnc, err := FragmentID(encrypted)
	if err != nil {
		t.Fatal(err)
	}
	if idBase == idRange || idBase == idEnc || idRange == idEnc {
		t.Fatalf("fragment identity failed to include range/encryption: %s %s %s", idBase, idRange, idEnc)
	}
}
