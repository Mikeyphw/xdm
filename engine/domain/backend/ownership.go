package backend

import "fmt"

type OwnershipState string

const (
	OwnershipClaimed   OwnershipState = "claimed"
	OwnershipTaskBound OwnershipState = "task_bound"
	OwnershipReady     OwnershipState = "ready"
	OwnershipActive    OwnershipState = "active"
	OwnershipRetired   OwnershipState = "retired"
	OwnershipAbandoned OwnershipState = "abandoned"
)

func (s OwnershipState) Valid() bool {
	switch s {
	case OwnershipClaimed, OwnershipTaskBound, OwnershipReady, OwnershipActive, OwnershipRetired, OwnershipAbandoned:
		return true
	default:
		return false
	}
}

func (s OwnershipState) Terminal() bool { return s == OwnershipRetired || s == OwnershipAbandoned }

func CanTransitionOwnership(from, to OwnershipState) bool {
	switch from {
	case OwnershipClaimed:
		return to == OwnershipTaskBound || to == OwnershipAbandoned
	case OwnershipTaskBound:
		return to == OwnershipReady || to == OwnershipAbandoned
	case OwnershipReady:
		return to == OwnershipActive || to == OwnershipAbandoned
	case OwnershipActive:
		return to == OwnershipRetired
	default:
		return false
	}
}

func ValidateOwnershipTransition(from, to OwnershipState) error {
	if !from.Valid() || !to.Valid() || !CanTransitionOwnership(from, to) {
		return fmt.Errorf("invalid backend ownership transition: %s -> %s", from, to)
	}
	return nil
}

type TaskState string

const (
	TaskPrepared TaskState = "prepared"
	TaskActive   TaskState = "active"
	TaskRetired  TaskState = "retired"
)

func (s TaskState) Valid() bool {
	switch s {
	case TaskPrepared, TaskActive, TaskRetired:
		return true
	default:
		return false
	}
}
