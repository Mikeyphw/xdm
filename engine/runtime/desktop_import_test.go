package runtime

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDesktopLegacyImportIsDurableAndIdempotent(t *testing.T) {
	statePath := filepath.Join(t.TempDir(), "desktop-state.json")
	e := New(Config{HostPlatform: "desktop", StatePath: statePath, EventBuffer: 64})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	payload := `{"client_request_id":"desktop-import-1","package":{"package_version":1,"downloads":[{"id":"legacy-1","file_name":"fixture.bin","source_url":"https://example.test/fixture.bin","destination_path":"/tmp/fixture.bin","downloaded_bytes":64,"total_bytes":128,"bytes_per_second":0,"state":"Paused","updated_at_epoch_ms":7,"queue_id":"default","queue_order":0,"connection_count":4,"priority":"Normal","backend":"Native","backend_preference":"Automatic","allow_backend_fallback":true,"archived":false}],"installations":[{"id":"desktop-v1","payload":{"source":"legacy-json"}}]}}`
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"d1", strings.Repeat("0", 30)+"d1", DesktopLegacyImportKind, payload)); err != nil {
		t.Fatal(err)
	}
	projection := nextDesktopFrame(t, e, "desktop.ui.projection")
	if !strings.Contains(string(projection), `"legacy-1"`) {
		t.Fatalf("import projection=%s", projection)
	}
	result := nextDesktopFrame(t, e, "desktop.import.result")
	if !strings.Contains(string(result), `"ok":true`) || !strings.Contains(string(result), `"duplicate":false`) {
		t.Fatalf("import result=%s", result)
	}
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"d2", strings.Repeat("0", 30)+"d2", DesktopLegacyImportKind, payload)); err != nil {
		t.Fatal(err)
	}
	_ = nextDesktopFrame(t, e, "desktop.ui.projection")
	result = nextDesktopFrame(t, e, "desktop.import.result")
	if !strings.Contains(string(result), `"duplicate":true`) {
		t.Fatalf("duplicate result=%s", result)
	}
	if err := e.Shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}

	reopened := New(Config{HostPlatform: "desktop", StatePath: statePath, EventBuffer: 64})
	if err := reopened.Start(); err != nil {
		t.Fatal(err)
	}
	defer reopened.Shutdown(context.Background())
	restored := nextDesktopFrame(t, reopened, "desktop.ui.projection")
	if !strings.Contains(string(restored), `"legacy-1"`) || !strings.Contains(string(restored), `"Paused"`) {
		t.Fatalf("restored=%s", restored)
	}
}

func TestDesktopLegacyImportCanRetryAfterAuthorityCommitInterruption(t *testing.T) {
	root := t.TempDir()
	blockedParent := filepath.Join(root, "blocked")
	statePath := filepath.Join(blockedParent, "desktop-state.json")
	e := New(Config{HostPlatform: "desktop", StatePath: statePath, EventBuffer: 64})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	defer e.Shutdown(context.Background())

	// Introduce an interruption only after the authority store has opened: the
	// next commit cannot create its parent because a regular file occupies it.
	if err := os.WriteFile(blockedParent, []byte("blocked"), 0o600); err != nil {
		t.Fatal(err)
	}
	payload := `{"client_request_id":"desktop-import-interrupted","package":{"package_version":1,"downloads":[],"installations":[{"id":"desktop-v1","payload":{"source":"interrupted"}}]}}`
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"d5", strings.Repeat("0", 30)+"d5", DesktopLegacyImportKind, payload)); err != nil {
		t.Fatal(err)
	}
	result := nextDesktopFrame(t, e, "desktop.import.result")
	if !strings.Contains(string(result), `"authority_commit_failed"`) {
		t.Fatalf("interrupted result=%s", result)
	}
	if e.desktopAuthority.imported() {
		t.Fatal("failed commit must not mark desktop authority imported")
	}

	if err := os.Remove(blockedParent); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(blockedParent, 0o700); err != nil {
		t.Fatal(err)
	}
	retry := `{"client_request_id":"desktop-import-retry","package":{"package_version":1,"downloads":[],"installations":[{"id":"desktop-v1","payload":{"source":"interrupted"}}]}}`
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"d6", strings.Repeat("0", 30)+"d6", DesktopLegacyImportKind, retry)); err != nil {
		t.Fatal(err)
	}
	_ = nextDesktopFrame(t, e, "desktop.ui.projection")
	result = nextDesktopFrame(t, e, "desktop.import.result")
	if !strings.Contains(string(result), `"ok":true`) || !strings.Contains(string(result), `"duplicate":false`) {
		t.Fatalf("retry result=%s", result)
	}
	if !e.desktopAuthority.imported() {
		t.Fatal("successful retry did not establish desktop authority")
	}
}

func TestDesktopLegacyImportRejectsChangedPackageAfterCutover(t *testing.T) {
	statePath := filepath.Join(t.TempDir(), "desktop-state.json")
	e := New(Config{HostPlatform: "desktop", StatePath: statePath, EventBuffer: 64})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	defer e.Shutdown(context.Background())
	first := `{"client_request_id":"desktop-import-a","package":{"package_version":1,"downloads":[],"installations":[{"id":"desktop-v1","payload":{"source":"fresh"}}]}}`
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"d3", strings.Repeat("0", 30)+"d3", DesktopLegacyImportKind, first)); err != nil {
		t.Fatal(err)
	}
	_ = nextDesktopFrame(t, e, "desktop.ui.projection")
	_ = nextDesktopFrame(t, e, "desktop.import.result")
	changed := `{"client_request_id":"desktop-import-b","package":{"package_version":1,"downloads":[],"installations":[{"id":"desktop-v1","payload":{"source":"changed"}}]}}`
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"d4", strings.Repeat("0", 30)+"d4", DesktopLegacyImportKind, changed)); err != nil {
		t.Fatal(err)
	}
	result := nextDesktopFrame(t, e, "desktop.import.result")
	if !strings.Contains(string(result), `"legacy_changed_after_cutover"`) {
		t.Fatalf("changed result=%s", result)
	}
}
