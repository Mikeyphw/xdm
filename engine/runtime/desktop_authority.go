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

const desktopAuthoritySnapshotVersion = 1

var ErrDesktopAuthorityAlreadyImported = errors.New("desktop legacy authority already imported")

type desktopAuthoritySnapshot struct {
	Version    int                  `json:"version"`
	ImportKey  string               `json:"import_key,omitempty"`
	SourceHash string               `json:"source_hash,omitempty"`
	Objects    []ops.ImportedObject `json:"objects,omitempty"`
	UI         DesktopUIProjection  `json:"ui"`
}

type desktopAuthorityStore struct {
	mu    sync.Mutex
	path  string
	state desktopAuthoritySnapshot
}

func newDesktopAuthorityStore(path string) (*desktopAuthorityStore, error) {
	s := &desktopAuthorityStore{
		path: path,
		state: desktopAuthoritySnapshot{
			Version: desktopAuthoritySnapshotVersion,
			UI:      DesktopUIProjection{Downloads: []DesktopUIDownload{}},
		},
	}
	if path == "" {
		return s, nil
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read desktop authority snapshot: %w", err)
	}
	var snap desktopAuthoritySnapshot
	if err := json.Unmarshal(data, &snap); err != nil {
		return nil, fmt.Errorf("decode desktop authority snapshot: %w", err)
	}
	if snap.Version != desktopAuthoritySnapshotVersion {
		return nil, fmt.Errorf("unsupported desktop authority snapshot version %d", snap.Version)
	}
	for _, item := range snap.UI.Downloads {
		if err := validateDesktopUIDownload(item, true); err != nil {
			return nil, fmt.Errorf("invalid persisted desktop UI projection: %w", err)
		}
	}
	if snap.UI.Downloads == nil {
		snap.UI.Downloads = []DesktopUIDownload{}
	}
	s.state = snap
	return s, nil
}

func (s *desktopAuthorityStore) imported() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state.ImportKey != ""
}

func (s *desktopAuthorityStore) snapshot() desktopAuthoritySnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	return cloneDesktopAuthoritySnapshot(s.state)
}

func (s *desktopAuthorityStore) commitImport(source ops.LegacyImportSource, objects []ops.ImportedObject, ui DesktopUIProjection) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := source.IdempotencyKey()
	if s.state.ImportKey != "" {
		if s.state.ImportKey == key {
			return true, nil
		}
		return false, fmt.Errorf("%w: existing=%s incoming=%s", ErrDesktopAuthorityAlreadyImported, s.state.ImportKey, key)
	}
	next := desktopAuthoritySnapshot{
		Version:    desktopAuthoritySnapshotVersion,
		ImportKey:  key,
		SourceHash: source.Hash,
		Objects:    append([]ops.ImportedObject(nil), objects...),
		UI:         ui,
	}
	if err := s.writeLocked(next); err != nil {
		return false, err
	}
	s.state = next
	return false, nil
}

func (s *desktopAuthorityStore) saveUI(ui DesktopUIProjection) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.state.ImportKey == "" {
		return nil
	}
	next := cloneDesktopAuthoritySnapshot(s.state)
	next.UI = ui
	if err := s.writeLocked(next); err != nil {
		return err
	}
	s.state = next
	return nil
}

func (s *desktopAuthorityStore) writeLocked(next desktopAuthoritySnapshot) error {
	if s.path == "" {
		return nil
	}
	data, err := json.Marshal(next)
	if err != nil {
		return fmt.Errorf("encode desktop authority snapshot: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return fmt.Errorf("create desktop authority directory: %w", err)
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return fmt.Errorf("write desktop authority snapshot: %w", err)
	}
	if err := os.Rename(tmp, s.path); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("commit desktop authority snapshot: %w", err)
	}
	return nil
}

func cloneDesktopAuthoritySnapshot(in desktopAuthoritySnapshot) desktopAuthoritySnapshot {
	data, _ := json.Marshal(in)
	var out desktopAuthoritySnapshot
	_ = json.Unmarshal(data, &out)
	return out
}
