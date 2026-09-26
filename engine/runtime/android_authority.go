package runtime

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"

	"github.com/subhra74/xdm/engine/ops"
)

const androidAuthoritySnapshotVersion = 1

var ErrAndroidAuthorityAlreadyImported = errors.New("android legacy Room authority already imported")

type androidMediaPersistentSnapshot struct {
	Sync       androidMediaSyncInput            `json:"sync"`
	Selections map[string]androidMediaSelection `json:"selections,omitempty"`
}

type androidAuthoritySnapshot struct {
	Version           int                            `json:"version"`
	ImportKey         string                         `json:"import_key,omitempty"`
	SourceHash        string                         `json:"source_hash,omitempty"`
	RoomSchemaVersion int                            `json:"room_schema_version,omitempty"`
	Objects           []ops.ImportedObject           `json:"objects,omitempty"`
	UI                AndroidUIProjection            `json:"ui"`
	Media             androidMediaPersistentSnapshot `json:"media"`
}

type androidAuthorityStore struct {
	mu    sync.Mutex
	path  string
	state androidAuthoritySnapshot
}

func newAndroidAuthorityStore(path string) (*androidAuthorityStore, error) {
	s := &androidAuthorityStore{path: path, state: androidAuthoritySnapshot{Version: androidAuthoritySnapshotVersion, UI: AndroidUIProjection{Downloads: []AndroidUIDownload{}}}}
	if path == "" {
		return s, nil
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read Android authority snapshot: %w", err)
	}
	var snap androidAuthoritySnapshot
	if err := json.Unmarshal(data, &snap); err != nil {
		return nil, fmt.Errorf("decode Android authority snapshot: %w", err)
	}
	if snap.Version != androidAuthoritySnapshotVersion {
		return nil, fmt.Errorf("unsupported Android authority snapshot version %d", snap.Version)
	}
	if err := validateAndroidUIProjection(snap.UI); err != nil {
		return nil, fmt.Errorf("invalid persisted Android UI projection: %w", err)
	}
	s.state = snap
	if s.state.UI.Downloads == nil {
		s.state.UI.Downloads = []AndroidUIDownload{}
	}
	return s, nil
}

func (s *androidAuthorityStore) imported() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state.ImportKey != ""
}

func (s *androidAuthorityStore) snapshot() androidAuthoritySnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	return cloneAndroidAuthoritySnapshot(s.state)
}

func (s *androidAuthorityStore) commitImport(source ops.LegacyImportSource, roomSchema int, objects []ops.ImportedObject, ui AndroidUIProjection, media androidMediaPersistentSnapshot) (duplicate bool, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := source.IdempotencyKey()
	if s.state.ImportKey != "" {
		if s.state.ImportKey == key {
			return true, nil
		}
		return false, fmt.Errorf("%w: existing=%s incoming=%s", ErrAndroidAuthorityAlreadyImported, s.state.ImportKey, key)
	}
	next := androidAuthoritySnapshot{
		Version:           androidAuthoritySnapshotVersion,
		ImportKey:         key,
		SourceHash:        source.Hash,
		RoomSchemaVersion: roomSchema,
		Objects:           append([]ops.ImportedObject(nil), objects...),
		UI:                ui,
		Media:             media,
	}
	if err := s.writeLocked(next); err != nil {
		return false, err
	}
	s.state = next
	return false, nil
}

func (s *androidAuthorityStore) saveUI(ui AndroidUIProjection) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.state.ImportKey == "" {
		return nil
	}
	next := cloneAndroidAuthoritySnapshot(s.state)
	next.UI = ui
	if err := s.writeLocked(next); err != nil {
		return err
	}
	s.state = next
	return nil
}

func (s *androidAuthorityStore) saveMedia(media androidMediaPersistentSnapshot) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.state.ImportKey == "" {
		return nil
	}
	next := cloneAndroidAuthoritySnapshot(s.state)
	next.Media = media
	if err := s.writeLocked(next); err != nil {
		return err
	}
	s.state = next
	return nil
}

func (s *androidAuthorityStore) writeLocked(next androidAuthoritySnapshot) error {
	if s.path == "" {
		return nil
	}
	data, err := json.Marshal(next)
	if err != nil {
		return fmt.Errorf("encode Android authority snapshot: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return fmt.Errorf("create Android authority directory: %w", err)
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return fmt.Errorf("write Android authority snapshot: %w", err)
	}
	if err := os.Rename(tmp, s.path); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("commit Android authority snapshot: %w", err)
	}
	return nil
}

func cloneAndroidAuthoritySnapshot(in androidAuthoritySnapshot) androidAuthoritySnapshot {
	data, _ := json.Marshal(in)
	var out androidAuthoritySnapshot
	_ = json.Unmarshal(data, &out)
	return out
}
