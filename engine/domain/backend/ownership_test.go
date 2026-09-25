package backend

import "testing"

func TestOwnershipTransitionProtocol(t *testing.T) {
	allowed := [][2]OwnershipState{
		{OwnershipClaimed, OwnershipTaskBound},
		{OwnershipTaskBound, OwnershipReady},
		{OwnershipReady, OwnershipActive},
		{OwnershipActive, OwnershipRetired},
		{OwnershipClaimed, OwnershipAbandoned},
		{OwnershipTaskBound, OwnershipAbandoned},
		{OwnershipReady, OwnershipAbandoned},
	}
	for _, pair := range allowed {
		if err := ValidateOwnershipTransition(pair[0], pair[1]); err != nil {
			t.Fatalf("expected %s -> %s: %v", pair[0], pair[1], err)
		}
	}
	for _, pair := range [][2]OwnershipState{
		{OwnershipClaimed, OwnershipActive},
		{OwnershipTaskBound, OwnershipActive},
		{OwnershipReady, OwnershipRetired},
		{OwnershipRetired, OwnershipActive},
		{OwnershipAbandoned, OwnershipClaimed},
	} {
		if err := ValidateOwnershipTransition(pair[0], pair[1]); err == nil {
			t.Fatalf("unexpected allowed transition %s -> %s", pair[0], pair[1])
		}
	}
}
