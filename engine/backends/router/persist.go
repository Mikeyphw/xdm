package router

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

func selectionEventID(downloadID identity.DownloadID, generation identity.AttemptGeneration) string {
	return fmt.Sprintf("backend-selection:%s:%d", downloadID.String(), generation.Int64())
}

// PersistSelection writes a safe, attempt-scoped diagnostic only while the
// matching attempt is still reserved. It never mutates BackendKind and therefore
// cannot be used as an implicit backend migration mechanism.
func PersistSelection(ctx context.Context, repo *store.Repository, attempt store.AttemptRecord, decision Decision, nowMS int64) error {
	if repo == nil || attempt.DownloadID.IsZero() || !attempt.Generation.Valid() || !decision.CanStart || !decision.Selected.Valid() {
		return fmt.Errorf("%w: incomplete selection persistence input", ErrInvalidOperation)
	}
	if attempt.State != "reserved" || attempt.BackendKind != string(decision.Selected) {
		return ErrAttemptStarted
	}
	if nowMS <= 0 {
		nowMS = time.Now().UnixMilli()
	}
	raw, err := json.Marshal(decision)
	if err != nil {
		return err
	}
	return repo.RecordAttemptDiagnostic(ctx, store.AttemptDiagnosticRecord{
		EventID:         selectionEventID(attempt.DownloadID, attempt.Generation),
		Subsystem:       "backend",
		DownloadID:      attempt.DownloadID,
		Generation:      attempt.Generation,
		ExpectedState:   "reserved",
		EventType:       "backend_selection",
		Severity:        "info",
		SafePayload:     string(raw),
		CreatedAtUnixMS: nowMS,
	})
}
