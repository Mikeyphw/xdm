package scheduler

import (
	"context"
	"fmt"
	"sort"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

func (s *Store) AddDependency(ctx context.Context, rec DependencyRecord) error {
	if s == nil || s.db == nil {
		return sqlite.ErrClosed
	}
	if err := validateDependency(rec); err != nil {
		return err
	}
	tx, err := s.db.BeginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())
	edges, err := loadDependenciesTx(ctx, tx)
	if err != nil {
		return err
	}
	from := rec.DownloadID.String()
	to := rec.DependencyDownloadID.String()
	edges[from] = append(edges[from], to)
	if reaches(edges, to, from, map[string]bool{}) {
		return ErrDependencyCycle
	}
	now := nowUnix(rec.CreatedAtUnixMS)
	if rec.UpdatedAtUnixMS <= 0 {
		rec.UpdatedAtUnixMS = now
	}
	if _, err := tx.Exec(ctx, `INSERT INTO download_dependencies(download_id,dependency_download_id,requirement,revision,created_at_unix_ms,updated_at_unix_ms) VALUES(?,?,?,?,?,?)`, from, to, string(rec.Requirement), int64(1), now, rec.UpdatedAtUnixMS); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (s *Store) Dependencies(ctx context.Context, downloadID identity.DownloadID) ([]DependencyRecord, error) {
	if s == nil || s.db == nil {
		return nil, sqlite.ErrClosed
	}
	rows, err := s.db.Query(ctx, `SELECT download_id,dependency_download_id,requirement,revision,created_at_unix_ms,updated_at_unix_ms FROM download_dependencies WHERE download_id=? ORDER BY dependency_download_id`, downloadID.String())
	if err != nil {
		return nil, err
	}
	return decodeDependencies(rows)
}

func (s *Store) Eligibility(ctx context.Context, downloadID identity.DownloadID, outcomes map[identity.DownloadID]DownloadOutcome) (Eligibility, error) {
	deps, err := s.Dependencies(ctx, downloadID)
	if err != nil {
		return Eligibility{}, err
	}
	result := Eligibility{DownloadID: downloadID, Eligible: true}
	for _, dep := range deps {
		outcome := outcomes[dep.DependencyDownloadID]
		if outcome == "" {
			outcome = OutcomeUnknown
		}
		if reason := blockedReason(dep.Requirement, outcome); reason != "" {
			result.Eligible = false
			result.Blocked = append(result.Blocked, BlockedReason{DependencyDownloadID: dep.DependencyDownloadID, Requirement: dep.Requirement, Outcome: outcome, Reason: reason})
		}
	}
	sort.Slice(result.Blocked, func(i, j int) bool {
		return result.Blocked[i].DependencyDownloadID.String() < result.Blocked[j].DependencyDownloadID.String()
	})
	return result, nil
}

func blockedReason(req DependencyRequirement, outcome DownloadOutcome) string {
	switch req {
	case CompletionRequired:
		if outcome == OutcomeSucceeded || outcome == OutcomeFailed || outcome == OutcomeCanceled {
			return ""
		}
		return "dependency_not_complete"
	case SuccessRequired:
		switch outcome {
		case OutcomeSucceeded:
			return ""
		case OutcomeFailed, OutcomeCanceled:
			return "dependency_failed"
		default:
			return "dependency_not_successful"
		}
	default:
		return "unknown_dependency_requirement"
	}
}

func validateDependency(rec DependencyRecord) error {
	if rec.DownloadID.IsZero() || rec.DependencyDownloadID.IsZero() || rec.DownloadID == rec.DependencyDownloadID {
		return ErrInvalidDependency
	}
	if rec.Requirement != CompletionRequired && rec.Requirement != SuccessRequired {
		return ErrInvalidDependency
	}
	return nil
}

func loadDependenciesTx(ctx context.Context, tx *sqlite.Tx) (map[string][]string, error) {
	rows, err := tx.Query(ctx, `SELECT download_id,dependency_download_id FROM download_dependencies`)
	if err != nil {
		return nil, err
	}
	edges := map[string][]string{}
	for _, row := range rows {
		if len(row) != 2 {
			return nil, fmt.Errorf("unexpected dependency edge width %d", len(row))
		}
		edges[row[0].Text] = append(edges[row[0].Text], row[1].Text)
	}
	return edges, nil
}

func reaches(edges map[string][]string, from, target string, seen map[string]bool) bool {
	if from == target {
		return true
	}
	if seen[from] {
		return false
	}
	seen[from] = true
	for _, next := range edges[from] {
		if reaches(edges, next, target, seen) {
			return true
		}
	}
	return false
}

func decodeDependencies(rows []sqlite.Row) ([]DependencyRecord, error) {
	out := make([]DependencyRecord, 0, len(rows))
	for _, row := range rows {
		if len(row) != 6 {
			return nil, fmt.Errorf("unexpected dependency column count %d", len(row))
		}
		dl, err := identity.ParseDownloadID(row[0].Text)
		if err != nil {
			return nil, err
		}
		dep, err := identity.ParseDownloadID(row[1].Text)
		if err != nil {
			return nil, err
		}
		rev, err := identity.NewRevision(row[3].I64)
		if err != nil {
			return nil, err
		}
		out = append(out, DependencyRecord{DownloadID: dl, DependencyDownloadID: dep, Requirement: DependencyRequirement(row[2].Text), Revision: rev, CreatedAtUnixMS: row[4].I64, UpdatedAtUnixMS: row[5].I64})
	}
	return out, nil
}
