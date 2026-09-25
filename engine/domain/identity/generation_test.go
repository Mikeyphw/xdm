package identity_test

import (
	"encoding/json"
	"errors"
	"math"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
)

func TestGenerationAndRevisionAreIndependentPositiveCounters(t *testing.T) {
	attempt, err := identity.NewAttemptGeneration(7)
	if err != nil {
		t.Fatal(err)
	}
	artifact, err := identity.NewArtifactGeneration(7)
	if err != nil {
		t.Fatal(err)
	}
	revision, err := identity.NewRevision(7)
	if err != nil {
		t.Fatal(err)
	}
	if attempt.Int64() != 7 || artifact.Int64() != 7 || revision.Int64() != 7 {
		t.Fatal("counter construction changed values")
	}
	if _, err := identity.NewAttemptGeneration(0); !errors.Is(err, identity.ErrInvalidCounter) {
		t.Fatalf("zero attempt err=%v", err)
	}
}

func TestGenerationNextAndOverflow(t *testing.T) {
	value, _ := identity.NewAttemptGeneration(9)
	next, err := value.Next()
	if err != nil || next.Int64() != 10 {
		t.Fatalf("next=%v err=%v", next, err)
	}
	max, _ := identity.NewAttemptGeneration(math.MaxInt64)
	if _, err := max.Next(); !errors.Is(err, identity.ErrCounterOverflow) {
		t.Fatalf("overflow err=%v", err)
	}
}

func TestCounterJSONRejectsZero(t *testing.T) {
	revision, _ := identity.NewRevision(12)
	payload, err := json.Marshal(revision)
	if err != nil {
		t.Fatal(err)
	}
	var restored identity.Revision
	if err := json.Unmarshal(payload, &restored); err != nil {
		t.Fatal(err)
	}
	if restored != revision {
		t.Fatalf("restored=%v revision=%v", restored, revision)
	}
	if err := json.Unmarshal([]byte("0"), &restored); !errors.Is(err, identity.ErrInvalidCounter) {
		t.Fatalf("zero unmarshal err=%v", err)
	}
}
