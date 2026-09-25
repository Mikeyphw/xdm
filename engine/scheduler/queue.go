package scheduler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/sqlite"
)

var (
	ErrInvalidQueue      = errors.New("invalid queue record")
	ErrQueueHasMembers   = errors.New("queue has members")
	ErrDependencyCycle   = errors.New("dependency cycle")
	ErrInvalidDependency = errors.New("invalid dependency")
)

type DeleteQueuePolicy string

const (
	DeleteRejectWithMembers DeleteQueuePolicy = "reject_with_members"
	DeleteUnassignMembers   DeleteQueuePolicy = "unassign_members"
)

type DependencyRequirement string

const (
	CompletionRequired DependencyRequirement = "completion_required"
	SuccessRequired    DependencyRequirement = "success_required"
)

type DownloadOutcome string

const (
	OutcomeUnknown   DownloadOutcome = "unknown"
	OutcomeRunning   DownloadOutcome = "running"
	OutcomeSucceeded DownloadOutcome = "succeeded"
	OutcomeFailed    DownloadOutcome = "failed"
	OutcomeCanceled  DownloadOutcome = "canceled"
)

type QueuePolicy struct {
	Concurrency      int      `json:"concurrency"`
	BandwidthProfile string   `json:"bandwidth_profile,omitempty"`
	Conditions       []string `json:"conditions,omitempty"`
	ScheduleRef      string   `json:"schedule_ref,omitempty"`
	RetryPolicy      string   `json:"retry_policy,omitempty"`
}

type QueueRecord struct {
	ID              identity.QueueID
	Name            string
	Enabled         bool
	Priority        int
	Policy          QueuePolicy
	Revision        identity.Revision
	CreatedAtUnixMS int64
	UpdatedAtUnixMS int64
}

type MembershipRecord struct {
	QueueID    identity.QueueID
	DownloadID identity.DownloadID
	Position   int
	Revision   identity.Revision
}

type DependencyRecord struct {
	DownloadID           identity.DownloadID
	DependencyDownloadID identity.DownloadID
	Requirement          DependencyRequirement
	Revision             identity.Revision
	CreatedAtUnixMS      int64
	UpdatedAtUnixMS      int64
}

type BlockedReason struct {
	DependencyDownloadID identity.DownloadID   `json:"dependency_download_id"`
	Requirement          DependencyRequirement `json:"requirement"`
	Outcome              DownloadOutcome       `json:"outcome"`
	Reason               string                `json:"reason"`
}

type Eligibility struct {
	DownloadID identity.DownloadID `json:"download_id"`
	Eligible   bool                `json:"eligible"`
	Blocked    []BlockedReason     `json:"blocked,omitempty"`
}

type Store struct{ db *sqlite.DB }

func NewStore(db *sqlite.DB) (*Store, error) {
	if db == nil {
		return nil, sqlite.ErrClosed
	}
	return &Store{db: db}, nil
}

func (s *Store) CreateQueue(ctx context.Context, q QueueRecord) (QueueRecord, error) {
	if s == nil || s.db == nil {
		return QueueRecord{}, sqlite.ErrClosed
	}
	if err := validateQueue(q); err != nil {
		return QueueRecord{}, err
	}
	now := nowUnix(q.CreatedAtUnixMS)
	q.CreatedAtUnixMS = now
	if q.UpdatedAtUnixMS <= 0 {
		q.UpdatedAtUnixMS = now
	}
	if !q.Revision.Valid() {
		q.Revision, _ = identity.NewRevision(1)
	}
	policyJSON, err := encodePolicy(q.Policy)
	if err != nil {
		return QueueRecord{}, err
	}
	_, err = s.db.Exec(ctx, `INSERT INTO queues(queue_id,name,enabled,priority,policy_json,revision,created_at_unix_ms,updated_at_unix_ms) VALUES(?,?,?,?,?,?,?,?)`, q.ID.String(), q.Name, q.Enabled, q.Priority, policyJSON, q.Revision.Int64(), q.CreatedAtUnixMS, q.UpdatedAtUnixMS)
	if err != nil {
		return QueueRecord{}, err
	}
	return s.GetQueue(ctx, q.ID)
}

func (s *Store) GetQueue(ctx context.Context, id identity.QueueID) (QueueRecord, error) {
	if s == nil || s.db == nil {
		return QueueRecord{}, sqlite.ErrClosed
	}
	rows, err := s.db.Query(ctx, `SELECT queue_id,name,enabled,priority,policy_json,revision,created_at_unix_ms,updated_at_unix_ms FROM queues WHERE queue_id=?`, id.String())
	if err != nil {
		return QueueRecord{}, err
	}
	if len(rows) == 0 {
		return QueueRecord{}, sqlite.ErrNotFound
	}
	return decodeQueue(rows[0])
}

func (s *Store) UpdateQueue(ctx context.Context, id identity.QueueID, expected identity.Revision, patch QueueRecord) (QueueRecord, error) {
	if s == nil || s.db == nil {
		return QueueRecord{}, sqlite.ErrClosed
	}
	if id.IsZero() || !expected.Valid() || patch.Name == "" || patch.Policy.Concurrency < 0 {
		return QueueRecord{}, ErrInvalidQueue
	}
	next, err := expected.Next()
	if err != nil {
		return QueueRecord{}, err
	}
	updated := nowUnix(patch.UpdatedAtUnixMS)
	policyJSON, err := encodePolicy(patch.Policy)
	if err != nil {
		return QueueRecord{}, err
	}
	changes, err := s.db.Exec(ctx, `UPDATE queues SET name=?, enabled=?, priority=?, policy_json=?, revision=?, updated_at_unix_ms=? WHERE queue_id=? AND revision=?`, patch.Name, patch.Enabled, patch.Priority, policyJSON, next.Int64(), updated, id.String(), expected.Int64())
	if err != nil {
		return QueueRecord{}, err
	}
	if changes != 1 {
		if _, lookup := s.GetQueue(ctx, id); errors.Is(lookup, sqlite.ErrNotFound) {
			return QueueRecord{}, sqlite.ErrNotFound
		}
		return QueueRecord{}, sqlite.ErrStaleWrite
	}
	return s.GetQueue(ctx, id)
}

func (s *Store) DeleteQueue(ctx context.Context, id identity.QueueID, policy DeleteQueuePolicy) error {
	if s == nil || s.db == nil {
		return sqlite.ErrClosed
	}
	if id.IsZero() {
		return ErrInvalidQueue
	}
	tx, err := s.db.BeginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())
	rows, err := tx.Query(ctx, `SELECT COUNT(*) FROM queue_membership WHERE queue_id=?`, id.String())
	if err != nil {
		return err
	}
	count := int64(0)
	if len(rows) == 1 && len(rows[0]) == 1 {
		count = rows[0][0].I64
	}
	switch policy {
	case DeleteRejectWithMembers:
		if count > 0 {
			return ErrQueueHasMembers
		}
	case DeleteUnassignMembers:
		if _, err := tx.Exec(ctx, `DELETE FROM queue_membership WHERE queue_id=?`, id.String()); err != nil {
			return err
		}
	default:
		return fmt.Errorf("unknown queue delete policy %q", policy)
	}
	changes, err := tx.Exec(ctx, `DELETE FROM queues WHERE queue_id=?`, id.String())
	if err != nil {
		return err
	}
	if changes != 1 {
		return sqlite.ErrNotFound
	}
	return tx.Commit(ctx)
}

func (s *Store) Assign(ctx context.Context, queueID identity.QueueID, downloadID identity.DownloadID, position int) error {
	if s == nil || s.db == nil {
		return sqlite.ErrClosed
	}
	return s.moveOrAssign(ctx, queueID, downloadID, position)
}

func (s *Store) Move(ctx context.Context, queueID identity.QueueID, downloadID identity.DownloadID, position int) error {
	if s == nil || s.db == nil {
		return sqlite.ErrClosed
	}
	return s.moveOrAssign(ctx, queueID, downloadID, position)
}

func (s *Store) moveOrAssign(ctx context.Context, queueID identity.QueueID, downloadID identity.DownloadID, position int) error {
	if queueID.IsZero() || downloadID.IsZero() || position < 0 {
		return ErrInvalidQueue
	}
	tx, err := s.db.BeginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())
	if _, err := tx.Exec(ctx, `DELETE FROM queue_membership WHERE download_id=?`, downloadID.String()); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE queue_membership SET position=position+1, revision=revision+1 WHERE queue_id=? AND position>=?`, queueID.String(), int64(position)); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO queue_membership(queue_id,download_id,position,revision) VALUES(?,?,?,1)`, queueID.String(), downloadID.String(), int64(position)); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (s *Store) Reorder(ctx context.Context, queueID identity.QueueID, ordered []identity.DownloadID) error {
	if s == nil || s.db == nil {
		return sqlite.ErrClosed
	}
	if queueID.IsZero() {
		return ErrInvalidQueue
	}
	seen := map[string]bool{}
	tx, err := s.db.BeginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())
	for pos, id := range ordered {
		if id.IsZero() || seen[id.String()] {
			return ErrInvalidQueue
		}
		seen[id.String()] = true
		changes, err := tx.Exec(ctx, `UPDATE queue_membership SET position=?, revision=revision+1 WHERE queue_id=? AND download_id=?`, int64(pos), queueID.String(), id.String())
		if err != nil {
			return err
		}
		if changes != 1 {
			return sqlite.ErrNotFound
		}
	}
	rows, err := tx.Query(ctx, `SELECT COUNT(*) FROM queue_membership WHERE queue_id=?`, queueID.String())
	if err != nil {
		return err
	}
	if len(rows) == 1 && rows[0][0].I64 != int64(len(ordered)) {
		return fmt.Errorf("reorder omitted queue members")
	}
	return tx.Commit(ctx)
}

func (s *Store) Members(ctx context.Context, queueID identity.QueueID) ([]MembershipRecord, error) {
	if s == nil || s.db == nil {
		return nil, sqlite.ErrClosed
	}
	rows, err := s.db.Query(ctx, `SELECT queue_id,download_id,position,revision FROM queue_membership WHERE queue_id=? ORDER BY position ASC, download_id ASC`, queueID.String())
	if err != nil {
		return nil, err
	}
	out := make([]MembershipRecord, 0, len(rows))
	for _, row := range rows {
		rec, err := decodeMembership(row)
		if err != nil {
			return nil, err
		}
		out = append(out, rec)
	}
	return out, nil
}

func (s *Store) QueueForDownload(ctx context.Context, downloadID identity.DownloadID) (MembershipRecord, error) {
	if s == nil || s.db == nil {
		return MembershipRecord{}, sqlite.ErrClosed
	}
	rows, err := s.db.Query(ctx, `SELECT queue_id,download_id,position,revision FROM queue_membership WHERE download_id=?`, downloadID.String())
	if err != nil {
		return MembershipRecord{}, err
	}
	if len(rows) == 0 {
		return MembershipRecord{}, sqlite.ErrNotFound
	}
	return decodeMembership(rows[0])
}

func validateQueue(q QueueRecord) error {
	if q.ID.IsZero() || q.Name == "" || q.Policy.Concurrency < 0 {
		return ErrInvalidQueue
	}
	return nil
}

func encodePolicy(policy QueuePolicy) (string, error) {
	conditions := append([]string(nil), policy.Conditions...)
	sort.Strings(conditions)
	policy.Conditions = conditions
	b, err := json.Marshal(policy)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

func decodeQueue(row sqlite.Row) (QueueRecord, error) {
	if len(row) != 8 {
		return QueueRecord{}, fmt.Errorf("unexpected queue column count %d", len(row))
	}
	id, err := identity.ParseQueueID(row[0].Text)
	if err != nil {
		return QueueRecord{}, err
	}
	rev, err := identity.NewRevision(row[5].I64)
	if err != nil {
		return QueueRecord{}, err
	}
	var policy QueuePolicy
	if err := json.Unmarshal([]byte(row[4].Text), &policy); err != nil {
		return QueueRecord{}, err
	}
	return QueueRecord{ID: id, Name: row[1].Text, Enabled: row[2].I64 == 1, Priority: int(row[3].I64), Policy: policy, Revision: rev, CreatedAtUnixMS: row[6].I64, UpdatedAtUnixMS: row[7].I64}, nil
}

func decodeMembership(row sqlite.Row) (MembershipRecord, error) {
	if len(row) != 4 {
		return MembershipRecord{}, fmt.Errorf("unexpected membership column count %d", len(row))
	}
	qid, err := identity.ParseQueueID(row[0].Text)
	if err != nil {
		return MembershipRecord{}, err
	}
	did, err := identity.ParseDownloadID(row[1].Text)
	if err != nil {
		return MembershipRecord{}, err
	}
	rev, err := identity.NewRevision(row[3].I64)
	if err != nil {
		return MembershipRecord{}, err
	}
	return MembershipRecord{QueueID: qid, DownloadID: did, Position: int(row[2].I64), Revision: rev}, nil
}

func nowUnix(v int64) int64 {
	if v > 0 {
		return v
	}
	return time.Now().UnixMilli()
}
