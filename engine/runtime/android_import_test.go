package runtime

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func androidImportPayload(fileName string) string {
	pkg := map[string]any{
		"package_version":     1,
		"room_schema_version": 25,
		"downloads": []map[string]any{{
			"id": "download-1", "file_name": fileName, "source_url": "https://example.test/file.bin",
			"destination_uri": "content://downloads/file.bin", "state": "Queued", "backend": "Native",
			"bytes_received": 0, "total_bytes": 100, "speed_bytes_per_second": 0, "priority": 0,
			"created_at_epoch_ms": 1, "updated_at_epoch_ms": 2, "conflict_policy": "Rename",
			"requested_backend": "Automatic", "backend_selection_reason": "DefaultNative",
			"backend_selection_explanation": "", "allow_backend_fallback": true, "archived": false,
			"attempt_generation": 1, "observed_attempt_generation": 1, "row_revision": 2,
		}},
		"attempts":   []map[string]any{{"id": "download-1:1", "payload": map[string]any{"download_id": "download-1", "attempt_generation": 1}}},
		"media_sync": map[string]any{"revision": 1, "captures": []any{}, "variants": []any{}},
	}
	raw, _ := json.Marshal(pkg)
	wrapper, _ := json.Marshal(map[string]any{"client_request_id": "import-1", "package": json.RawMessage(raw)})
	return string(wrapper)
}

func waitImportResult(t *testing.T, e *Engine) androidImportResult {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	for {
		frame, err := e.NextFrame(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if frame.Kind != "android.import.result" {
			continue
		}
		var result androidImportResult
		if err := json.Unmarshal(frame.Payload, &result); err != nil {
			t.Fatal(err)
		}
		return result
	}
}

func TestAndroidLegacyRoomImportIsDurableIdempotentAndRejectsChangedRoom(t *testing.T) {
	statePath := filepath.Join(t.TempDir(), "xgo", "android-state-v1.json")
	e := New(Config{StatePath: statePath, EventBuffer: 64})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	payload := androidImportPayload("file.bin")
	if err := e.Submit(context.Background(), cmd(t, "00000000000000000000000000000091", "00000000000000000000000000000091", AndroidLegacyRoomImportKind, payload)); err != nil {
		t.Fatal(err)
	}
	first := waitImportResult(t, e)
	if !first.OK || first.Duplicate || first.ImportedCount < 2 || first.RoomSchemaVersion != 25 {
		t.Fatalf("first=%+v", first)
	}
	if _, err := os.Stat(statePath); err != nil {
		t.Fatalf("durable Go authority snapshot missing: %v", err)
	}

	if err := e.Submit(context.Background(), cmd(t, "00000000000000000000000000000092", "00000000000000000000000000000092", AndroidLegacyRoomImportKind, payload)); err != nil {
		t.Fatal(err)
	}
	duplicate := waitImportResult(t, e)
	if !duplicate.OK || !duplicate.Duplicate || duplicate.ImportKey != first.ImportKey {
		t.Fatalf("duplicate=%+v", duplicate)
	}

	changed := androidImportPayload("changed.bin")
	if err := e.Submit(context.Background(), cmd(t, "00000000000000000000000000000093", "00000000000000000000000000000093", AndroidLegacyRoomImportKind, changed)); err != nil {
		t.Fatal(err)
	}
	rejected := waitImportResult(t, e)
	if rejected.OK || rejected.ErrorCode != "room_changed_after_cutover" {
		t.Fatalf("changed=%+v", rejected)
	}
	_ = e.Shutdown(context.Background())

	restored := New(Config{StatePath: statePath, EventBuffer: 64})
	if err := restored.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = restored.Shutdown(context.Background()) }()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	for {
		frame, err := restored.NextFrame(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if frame.Kind != "android.ui.projection" {
			continue
		}
		var projection AndroidUIProjection
		if err := json.Unmarshal(frame.Payload, &projection); err != nil {
			t.Fatal(err)
		}
		if len(projection.Downloads) != 1 || projection.Downloads[0].FileName != "file.bin" {
			t.Fatalf("restored=%+v", projection)
		}
		break
	}
}
