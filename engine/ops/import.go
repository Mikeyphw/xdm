package ops

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"
)

var (
	ErrInvalidImportSource      = errors.New("invalid legacy import source")
	ErrUnsupportedSourceVersion = errors.New("unsupported legacy source version")
	ErrImportFailed             = errors.New("legacy import failed")
	ErrImportInterrupted        = errors.New("legacy import interrupted")
)

type ImportSourceKind string

const (
	ImportSourceAndroidRoom ImportSourceKind = "android_room"
	ImportSourceDesktopJSON ImportSourceKind = "desktop_json"
)

type ImportStatus string

const (
	ImportStatusPending   ImportStatus = "pending"
	ImportStatusStaged    ImportStatus = "staged"
	ImportStatusCommitted ImportStatus = "committed"
	ImportStatusFailed    ImportStatus = "failed"
)

type LegacyImportSource struct {
	Kind    ImportSourceKind `json:"kind"`
	Version int              `json:"version"`
	Hash    string           `json:"hash"`
	Raw     json.RawMessage  `json:"-"`
}

func NewLegacyImportSource(kind ImportSourceKind, version int, raw []byte) (LegacyImportSource, error) {
	if !json.Valid(raw) {
		return LegacyImportSource{}, fmt.Errorf("%w: malformed json", ErrInvalidImportSource)
	}
	var canonical any
	if err := json.Unmarshal(raw, &canonical); err != nil {
		return LegacyImportSource{}, fmt.Errorf("%w: malformed json", ErrInvalidImportSource)
	}
	canonicalRaw, err := json.Marshal(canonical)
	if err != nil {
		return LegacyImportSource{}, fmt.Errorf("%w: canonical json", ErrInvalidImportSource)
	}
	sum := sha256.Sum256(canonicalRaw)
	source := LegacyImportSource{Kind: kind, Version: version, Hash: hex.EncodeToString(sum[:]), Raw: append([]byte(nil), canonicalRaw...)}
	if strings.TrimSpace(string(kind)) == "" || version <= 0 {
		return LegacyImportSource{}, fmt.Errorf("%w: kind/version", ErrInvalidImportSource)
	}
	return source, nil
}

func (s LegacyImportSource) MaxSupportedVersion() (int, bool) {
	switch s.Kind {
	case ImportSourceAndroidRoom, ImportSourceDesktopJSON:
		return 1, true
	default:
		return 0, false
	}
}

func (s LegacyImportSource) Validate() error {
	if strings.TrimSpace(string(s.Kind)) == "" || s.Version <= 0 || strings.TrimSpace(s.Hash) == "" || len(s.Raw) == 0 || !json.Valid(s.Raw) {
		return fmt.Errorf("%w: source descriptor", ErrInvalidImportSource)
	}
	max, ok := s.MaxSupportedVersion()
	if !ok {
		return fmt.Errorf("%w: kind", ErrInvalidImportSource)
	}
	if s.Version > max {
		return fmt.Errorf("%w: %s v%d", ErrUnsupportedSourceVersion, s.Kind, s.Version)
	}
	return nil
}

func (s LegacyImportSource) IdempotencyKey() string {
	return fmt.Sprintf("%s/v%d/%s", s.Kind, s.Version, s.Hash)
}

type ImportedObject struct {
	Kind        string         `json:"kind"`
	LegacyID    string         `json:"legacy_id"`
	CanonicalID string         `json:"canonical_id"`
	Payload     map[string]any `json:"payload,omitempty"`
}

func (o ImportedObject) Validate() error {
	if strings.TrimSpace(o.Kind) == "" || strings.TrimSpace(o.LegacyID) == "" || strings.TrimSpace(o.CanonicalID) == "" {
		return fmt.Errorf("%w: imported object", ErrInvalidImportSource)
	}
	return nil
}

type ImportAdapter func(context.Context, LegacyImportSource) ([]ImportedObject, error)

type ImportJobRecord struct {
	Key            string           `json:"key"`
	SourceKind     ImportSourceKind `json:"source_kind"`
	SourceVersion  int              `json:"source_version"`
	SourceHash     string           `json:"source_hash"`
	Status         ImportStatus     `json:"status"`
	StagedCount    int              `json:"staged_count"`
	CommittedCount int              `json:"committed_count"`
	Error          string           `json:"error,omitempty"`
	CreatedAt      time.Time        `json:"created_at"`
	UpdatedAt      time.Time        `json:"updated_at"`
}

type ImportResult struct {
	Key            string       `json:"key"`
	Status         ImportStatus `json:"status"`
	ImportedCount  int          `json:"imported_count"`
	Duplicate      bool         `json:"duplicate"`
	RecoveredStage bool         `json:"recovered_stage"`
}

type ImportOptions struct {
	CrashAfterStage bool
}

type LegacyImportStore struct {
	now           func() time.Time
	journal       map[string]ImportJobRecord
	staged        map[string][]ImportedObject
	authoritative map[string]ImportedObject
}

func NewLegacyImportStore(now func() time.Time) *LegacyImportStore {
	if now == nil {
		now = time.Now
	}
	return &LegacyImportStore{now: now, journal: map[string]ImportJobRecord{}, staged: map[string][]ImportedObject{}, authoritative: map[string]ImportedObject{}}
}

func (s *LegacyImportStore) Journal() []ImportJobRecord {
	out := make([]ImportJobRecord, 0, len(s.journal))
	for _, record := range s.journal {
		out = append(out, record)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key < out[j].Key })
	return out
}

func (s *LegacyImportStore) Record(key string) (ImportJobRecord, bool) {
	r, ok := s.journal[key]
	return r, ok
}

func (s *LegacyImportStore) AuthoritativeObjects() []ImportedObject {
	out := make([]ImportedObject, 0, len(s.authoritative))
	for _, obj := range s.authoritative {
		out = append(out, obj)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CanonicalID < out[j].CanonicalID })
	return out
}

func (s *LegacyImportStore) Import(ctx context.Context, source LegacyImportSource, adapter ImportAdapter) (ImportResult, error) {
	return s.ImportWithOptions(ctx, source, adapter, ImportOptions{})
}

func (s *LegacyImportStore) ImportWithOptions(ctx context.Context, source LegacyImportSource, adapter ImportAdapter, options ImportOptions) (ImportResult, error) {
	if err := source.Validate(); err != nil {
		return ImportResult{}, err
	}
	key := source.IdempotencyKey()
	if record, ok := s.journal[key]; ok {
		switch record.Status {
		case ImportStatusCommitted:
			return ImportResult{Key: key, Status: ImportStatusCommitted, ImportedCount: record.CommittedCount, Duplicate: true}, nil
		case ImportStatusStaged:
			return s.commitStaged(key, true)
		case ImportStatusFailed:
			delete(s.staged, key)
		}
	}
	if adapter == nil {
		return ImportResult{}, fmt.Errorf("%w: missing adapter", ErrInvalidImportSource)
	}
	now := s.now()
	s.journal[key] = ImportJobRecord{Key: key, SourceKind: source.Kind, SourceVersion: source.Version, SourceHash: source.Hash, Status: ImportStatusPending, CreatedAt: now, UpdatedAt: now}
	objects, err := adapter(ctx, source)
	if err != nil {
		s.markFailed(key, err)
		return ImportResult{Key: key, Status: ImportStatusFailed}, err
	}
	if len(objects) == 0 {
		err := fmt.Errorf("%w: empty import", ErrImportFailed)
		s.markFailed(key, err)
		return ImportResult{Key: key, Status: ImportStatusFailed}, err
	}
	seen := map[string]bool{}
	for _, obj := range objects {
		if err := obj.Validate(); err != nil {
			s.markFailed(key, err)
			return ImportResult{Key: key, Status: ImportStatusFailed}, err
		}
		if seen[obj.CanonicalID] {
			err := fmt.Errorf("%w: duplicate canonical id %s", ErrImportFailed, obj.CanonicalID)
			s.markFailed(key, err)
			return ImportResult{Key: key, Status: ImportStatusFailed}, err
		}
		seen[obj.CanonicalID] = true
	}
	s.staged[key] = append([]ImportedObject(nil), objects...)
	record := s.journal[key]
	record.Status = ImportStatusStaged
	record.StagedCount = len(objects)
	record.Error = ""
	record.UpdatedAt = s.now()
	s.journal[key] = record
	if options.CrashAfterStage {
		return ImportResult{Key: key, Status: ImportStatusStaged, ImportedCount: len(objects)}, ErrImportInterrupted
	}
	return s.commitStaged(key, false)
}

func (s *LegacyImportStore) commitStaged(key string, recovered bool) (ImportResult, error) {
	objects := s.staged[key]
	if len(objects) == 0 {
		err := fmt.Errorf("%w: staged objects missing", ErrImportFailed)
		s.markFailed(key, err)
		return ImportResult{Key: key, Status: ImportStatusFailed}, err
	}
	for _, obj := range objects {
		s.authoritative[obj.CanonicalID] = obj
	}
	delete(s.staged, key)
	record := s.journal[key]
	record.Status = ImportStatusCommitted
	record.CommittedCount = len(objects)
	record.Error = ""
	record.UpdatedAt = s.now()
	s.journal[key] = record
	return ImportResult{Key: key, Status: ImportStatusCommitted, ImportedCount: len(objects), RecoveredStage: recovered}, nil
}

func (s *LegacyImportStore) markFailed(key string, err error) {
	record := s.journal[key]
	record.Status = ImportStatusFailed
	record.Error = err.Error()
	record.UpdatedAt = s.now()
	s.journal[key] = record
}

type legacyDownloadRecord struct {
	ID        string `json:"id"`
	URL       string `json:"url,omitempty"`
	SourceURL string `json:"source_url,omitempty"`
	FileName  string `json:"file_name"`
	State     string `json:"state,omitempty"`
}

type legacyGenericRecord struct {
	ID      string         `json:"id"`
	Payload map[string]any `json:"payload,omitempty"`
}

func AndroidRoomImportAdapter(_ context.Context, source LegacyImportSource) ([]ImportedObject, error) {
	if source.Kind != ImportSourceAndroidRoom {
		return nil, fmt.Errorf("%w: adapter kind", ErrInvalidImportSource)
	}
	var doc struct {
		Downloads   []legacyDownloadRecord `json:"downloads"`
		Attempts    []legacyGenericRecord  `json:"attempts"`
		Checkpoints []legacyGenericRecord  `json:"checkpoints"`
		History     []legacyGenericRecord  `json:"history"`
		Queues      []legacyGenericRecord  `json:"queues"`
		Schedules   []legacyGenericRecord  `json:"schedules"`
		Recovery    []legacyGenericRecord  `json:"recovery"`
		Media       []legacyGenericRecord  `json:"media"`
	}
	if err := json.Unmarshal(source.Raw, &doc); err != nil {
		return nil, fmt.Errorf("%w: android json", ErrInvalidImportSource)
	}
	out, err := importDownloadRecords(source.Kind, doc.Downloads)
	if err != nil && len(doc.Downloads) > 0 {
		return nil, err
	}
	appendGeneric := func(kind string, records []legacyGenericRecord) error {
		for _, record := range records {
			id := strings.TrimSpace(record.ID)
			if id == "" {
				return fmt.Errorf("%w: %s record id", ErrImportFailed, kind)
			}
			canonical := fmt.Sprintf("%s:%x", kind, sha256.Sum256([]byte(string(source.Kind)+"\x00"+kind+"\x00"+id)))
			out = append(out, ImportedObject{Kind: kind, LegacyID: id, CanonicalID: canonical, Payload: record.Payload})
		}
		return nil
	}
	for _, item := range []struct {
		kind    string
		records []legacyGenericRecord
	}{
		{"attempt", doc.Attempts}, {"checkpoint", doc.Checkpoints}, {"history", doc.History},
		{"queue", doc.Queues}, {"schedule", doc.Schedules}, {"recovery", doc.Recovery}, {"media", doc.Media},
	} {
		if err := appendGeneric(item.kind, item.records); err != nil {
			return nil, err
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("%w: no supported Android Room records", ErrImportFailed)
	}
	return out, nil
}

func DesktopJSONImportAdapter(_ context.Context, source LegacyImportSource) ([]ImportedObject, error) {
	if source.Kind != ImportSourceDesktopJSON {
		return nil, fmt.Errorf("%w: adapter kind", ErrInvalidImportSource)
	}
	var doc struct {
		Items []legacyDownloadRecord `json:"items"`
	}
	if err := json.Unmarshal(source.Raw, &doc); err != nil {
		return nil, fmt.Errorf("%w: desktop json", ErrInvalidImportSource)
	}
	return importDownloadRecords(source.Kind, doc.Items)
}

func importDownloadRecords(kind ImportSourceKind, records []legacyDownloadRecord) ([]ImportedObject, error) {
	if len(records) == 0 {
		return nil, fmt.Errorf("%w: no download records", ErrImportFailed)
	}
	out := make([]ImportedObject, 0, len(records))
	for _, r := range records {
		url := strings.TrimSpace(r.URL)
		if url == "" {
			url = strings.TrimSpace(r.SourceURL)
		}
		if strings.TrimSpace(r.ID) == "" || url == "" || strings.TrimSpace(r.FileName) == "" {
			return nil, fmt.Errorf("%w: partial source record", ErrImportFailed)
		}
		canonical := fmt.Sprintf("download:%x", sha256.Sum256([]byte(string(kind)+"\x00"+r.ID)))
		out = append(out, ImportedObject{Kind: "download", LegacyID: r.ID, CanonicalID: canonical, Payload: map[string]any{"url": SafeURL(url), "file_name": r.FileName, "state": r.State}})
	}
	return out, nil
}
