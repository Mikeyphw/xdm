package ops

import (
	"encoding/json"
	"errors"
	"testing"
)

func TestSettingsDefaultsAndMigration(t *testing.T) {
	a, err := CanonicalSettingsJSON(DefaultEngineSettings())
	if err != nil {
		t.Fatal(err)
	}
	b, err := CanonicalSettingsJSON(DefaultEngineSettings())
	if err != nil {
		t.Fatal(err)
	}
	if string(a) != string(b) {
		t.Fatal("defaults not deterministic")
	}
	legacy := []byte(`{"maxRetries":5,"concurrent":3,"perHost":2,"backendOrder":["aria2","native-http"],"bandwidthLimit":2048,"retentionDays":7,"preferredFormat":"mkv"}`)
	result, err := ParseEngineSettingsJSON(legacy)
	if err != nil {
		t.Fatal(err)
	}
	if result.Settings.Version != EngineSettingsVersion || result.Settings.Retry.MaxAttempts != 5 || result.Settings.QueuePolicy.MaxActive != 3 || result.Settings.MediaPreferences.PreferredContainer != "mkv" {
		t.Fatalf("bad migration: %+v", result.Settings)
	}
}

func TestSettingsFutureAndImpossible(t *testing.T) {
	_, err := ParseEngineSettingsJSON([]byte(`{"version":99,"newField":true}`))
	if !errors.Is(err, ErrFutureSettingsVersion) {
		t.Fatalf("expected future version, got %v", err)
	}
	bad := DefaultEngineSettings()
	bad.Connections.MaxConcurrentPerHost = 99
	data, _ := json.Marshal(bad)
	_, err = ParseEngineSettingsJSON(data)
	if !errors.Is(err, ErrInvalidSettings) {
		t.Fatalf("expected invalid settings, got %v", err)
	}
}

func TestLegacySettingsOwnershipClassification(t *testing.T) {
	seen := map[Owner]bool{}
	for _, item := range LegacySettingsInventory() {
		seen[item.Owner] = true
	}
	for _, owner := range []Owner{OwnerEngine, OwnerAndroidHost, OwnerDesktopHost, OwnerPresentationOnly} {
		if !seen[owner] {
			t.Fatalf("missing owner %s", owner)
		}
	}
}
