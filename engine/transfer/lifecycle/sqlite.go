package lifecycle

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

var ErrInvalidLifecycle = errors.New("invalid transfer lifecycle")

type Lifecycle interface {
	Start(context.Context) error
	Complete(context.Context) error
	Pause(context.Context) error
	Fail(context.Context, failure.Failure) error
}

type SQLiteLifecycle struct {
	Repository *store.Repository
	DownloadID identity.DownloadID
	Generation identity.AttemptGeneration
	Revision   identity.Revision
	State      string
	mu         sync.Mutex
}

func (l *SQLiteLifecycle) mutateLocked(ctx context.Context, state string, f *failure.Failure) error {
	if l.Repository == nil || l.DownloadID.IsZero() || !l.Generation.Valid() || !l.Revision.Valid() {
		return ErrInvalidLifecycle
	}
	category, payload := "", ""
	if f != nil {
		category = string(f.Category)
		raw, _ := json.Marshal(f)
		payload = string(raw)
	}
	rec, err := l.Repository.MutateAttempt(ctx, l.DownloadID, l.Generation, l.Revision, state, category, payload, time.Now().UnixMilli())
	if err != nil {
		return err
	}
	l.Revision = rec.Revision
	l.State = state
	return nil
}
func (l *SQLiteLifecycle) Start(ctx context.Context) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	switch l.State {
	case "", "reserved":
		if err := l.mutateLocked(ctx, "prepared", nil); err != nil {
			return err
		}
		return l.mutateLocked(ctx, "running", nil)
	case "prepared", "paused", "transport_complete":
		return l.mutateLocked(ctx, "running", nil)
	case "running":
		return nil
	default:
		return fmt.Errorf("%w: cannot start from %s", ErrInvalidLifecycle, l.State)
	}
}
func (l *SQLiteLifecycle) Complete(ctx context.Context) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.State != "running" {
		return fmt.Errorf("%w: complete from %s", ErrInvalidLifecycle, l.State)
	}
	return l.mutateLocked(ctx, "transport_complete", nil)
}
func (l *SQLiteLifecycle) Pause(ctx context.Context) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.State != "running" {
		return fmt.Errorf("%w: pause from %s", ErrInvalidLifecycle, l.State)
	}
	return l.mutateLocked(ctx, "paused", nil)
}
func (l *SQLiteLifecycle) Fail(ctx context.Context, f failure.Failure) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.State == "produced_artifact" || l.State == "failed" || l.State == "cancelled" {
		return fmt.Errorf("%w: fail from %s", ErrInvalidLifecycle, l.State)
	}
	return l.mutateLocked(ctx, "failed", &f)
}
