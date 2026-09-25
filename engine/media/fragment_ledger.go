package media

import (
	"errors"
	"fmt"
	"sort"
	"strings"
)

var (
	ErrInvalidFragment      = errors.New("invalid media fragment")
	ErrFragmentStale        = errors.New("stale fragment generation")
	ErrFragmentDurability   = errors.New("fragment bytes must be durable before commit")
	ErrFragmentHashMismatch = errors.New("fragment hash mismatch")
)

type FragmentProtocol string

const (
	FragmentProtocolHLS  FragmentProtocol = "hls"
	FragmentProtocolDASH FragmentProtocol = "dash"
)

type FragmentState string

const (
	FragmentPending    FragmentState = "pending"
	FragmentFetching   FragmentState = "fetching"
	FragmentCommitted  FragmentState = "committed"
	FragmentCorrupt    FragmentState = "corrupt"
	FragmentHistorical FragmentState = "historical"
)

type ByteRange struct {
	Offset int64 `json:"offset"`
	Length int64 `json:"length"`
}

type FragmentIdentity struct {
	Protocol     FragmentProtocol `json:"protocol"`
	TimelineKey  string           `json:"timeline_key"`
	ResourceID   string           `json:"resource_id"`
	Range        *ByteRange       `json:"range,omitempty"`
	EncryptionID string           `json:"encryption_id,omitempty"`
}

type FragmentRecord struct {
	ID                  string           `json:"id"`
	Identity            FragmentIdentity `json:"identity"`
	Role                string           `json:"role"`
	State               FragmentState    `json:"state"`
	ExpectedLength      int64            `json:"expected_length,omitempty"`
	Hash                string           `json:"hash,omitempty"`
	AttemptGeneration   int64            `json:"attempt_generation"`
	LocalArtifactRef    string           `json:"local_artifact_ref,omitempty"`
	RetryState          string           `json:"retry_state,omitempty"`
	DurableBytesWritten bool             `json:"durable_bytes_written"`
}

type FragmentLedger struct {
	records map[string]FragmentRecord
	order   []string
}

func NewFragmentLedger(records ...FragmentRecord) (*FragmentLedger, error) {
	l := &FragmentLedger{records: map[string]FragmentRecord{}}
	for _, r := range records {
		if _, err := l.Upsert(r); err != nil {
			return nil, err
		}
	}
	return l, nil
}

func (l *FragmentLedger) Upsert(record FragmentRecord) (FragmentRecord, error) {
	if l == nil {
		return FragmentRecord{}, fmt.Errorf("%w: nil ledger", ErrInvalidFragment)
	}
	if l.records == nil {
		l.records = map[string]FragmentRecord{}
	}
	id, err := FragmentID(record.Identity)
	if err != nil {
		return FragmentRecord{}, err
	}
	record.ID = id
	record.Role = normalizeFragmentRole(record.Role)
	record.State = normalizeFragmentState(record.State)
	if record.AttemptGeneration <= 0 {
		record.AttemptGeneration = 1
	}
	if existing, ok := l.records[id]; ok {
		if record.AttemptGeneration < existing.AttemptGeneration {
			return existing, fmt.Errorf("%w: %s", ErrFragmentStale, id)
		}
		merged := mergeFragmentRecord(existing, record)
		l.records[id] = merged
		return merged, nil
	}
	l.records[id] = record
	l.order = append(l.order, id)
	sort.Strings(l.order)
	return record, nil
}

func (l *FragmentLedger) BeginFetch(identity FragmentIdentity, generation int64) (FragmentRecord, error) {
	rec := FragmentRecord{Identity: identity, State: FragmentFetching, AttemptGeneration: generation}
	return l.Upsert(rec)
}

func (l *FragmentLedger) Commit(record FragmentRecord) (FragmentRecord, error) {
	if !record.DurableBytesWritten || strings.TrimSpace(record.LocalArtifactRef) == "" {
		return FragmentRecord{}, fmt.Errorf("%w: commit before durable artifact", ErrFragmentDurability)
	}
	if record.ExpectedLength < 0 {
		return FragmentRecord{}, fmt.Errorf("%w: expected length", ErrInvalidFragment)
	}
	if strings.TrimSpace(record.Hash) == "" {
		return FragmentRecord{}, fmt.Errorf("%w: hash required", ErrInvalidFragment)
	}
	record.State = FragmentCommitted
	return l.Upsert(record)
}

func (l *FragmentLedger) MarkCorrupt(identity FragmentIdentity, generation int64, reason string) (FragmentRecord, error) {
	rec := FragmentRecord{Identity: identity, State: FragmentCorrupt, AttemptGeneration: generation, RetryState: strings.TrimSpace(reason)}
	return l.Upsert(rec)
}

func (l *FragmentLedger) MarkHistorical(identity FragmentIdentity) (FragmentRecord, error) {
	id, err := FragmentID(identity)
	if err != nil {
		return FragmentRecord{}, err
	}
	rec, ok := l.records[id]
	if !ok {
		return FragmentRecord{}, fmt.Errorf("%w: historical missing", ErrInvalidFragment)
	}
	if rec.State == FragmentCommitted {
		rec.State = FragmentHistorical
		l.records[id] = rec
	}
	return rec, nil
}

func (l *FragmentLedger) Get(identity FragmentIdentity) (FragmentRecord, bool) {
	if l == nil {
		return FragmentRecord{}, false
	}
	id, err := FragmentID(identity)
	if err != nil {
		return FragmentRecord{}, false
	}
	rec, ok := l.records[id]
	return rec, ok
}

func (l *FragmentLedger) Snapshot() []FragmentRecord {
	if l == nil {
		return nil
	}
	ids := append([]string(nil), l.order...)
	if len(ids) == 0 && len(l.records) > 0 {
		for id := range l.records {
			ids = append(ids, id)
		}
		sort.Strings(ids)
	}
	out := make([]FragmentRecord, 0, len(ids))
	for _, id := range ids {
		if rec, ok := l.records[id]; ok {
			out = append(out, rec)
		}
	}
	return out
}

func FragmentID(identity FragmentIdentity) (string, error) {
	protocol := normalizeFragmentProtocol(identity.Protocol)
	timeline := strings.TrimSpace(identity.TimelineKey)
	resource := strings.TrimSpace(identity.ResourceID)
	if timeline == "" || resource == "" {
		return "", fmt.Errorf("%w: identity", ErrInvalidFragment)
	}
	rng := ""
	if identity.Range != nil {
		if identity.Range.Offset < 0 || identity.Range.Length <= 0 {
			return "", fmt.Errorf("%w: range", ErrInvalidFragment)
		}
		rng = fmt.Sprintf("%d:%d", identity.Range.Offset, identity.Range.Length)
	}
	material := strings.Join([]string{string(protocol), timeline, resource, rng, strings.TrimSpace(identity.EncryptionID)}, "\x00")
	return digestID("fragment", material), nil
}

func normalizeFragmentProtocol(p FragmentProtocol) FragmentProtocol {
	switch p {
	case FragmentProtocolDASH:
		return FragmentProtocolDASH
	default:
		return FragmentProtocolHLS
	}
}

func normalizeFragmentState(s FragmentState) FragmentState {
	switch s {
	case FragmentFetching, FragmentCommitted, FragmentCorrupt, FragmentHistorical:
		return s
	default:
		return FragmentPending
	}
}

func normalizeFragmentRole(role string) string {
	role = strings.ToLower(strings.TrimSpace(role))
	switch role {
	case "video", "audio", "subtitle", "init", "key":
		return role
	default:
		return "media"
	}
}

func mergeFragmentRecord(existing, next FragmentRecord) FragmentRecord {
	if next.AttemptGeneration >= existing.AttemptGeneration {
		existing.AttemptGeneration = next.AttemptGeneration
	}
	if next.Role != "" && next.Role != "media" {
		existing.Role = next.Role
	}
	if next.ExpectedLength > 0 {
		existing.ExpectedLength = next.ExpectedLength
	}
	if strings.TrimSpace(next.Hash) != "" {
		existing.Hash = strings.TrimSpace(next.Hash)
	}
	if strings.TrimSpace(next.LocalArtifactRef) != "" {
		existing.LocalArtifactRef = strings.TrimSpace(next.LocalArtifactRef)
	}
	if strings.TrimSpace(next.RetryState) != "" {
		existing.RetryState = strings.TrimSpace(next.RetryState)
	}
	if next.DurableBytesWritten {
		existing.DurableBytesWritten = true
	}
	state := normalizeFragmentState(next.State)
	if state != FragmentPending || existing.State == "" {
		existing.State = state
	}
	return existing
}
