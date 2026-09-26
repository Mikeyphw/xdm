package ops

import (
	"context"
	"errors"
	"testing"
	"time"
)

func testSource(t *testing.T, kind ImportSourceKind, version int, raw string) LegacyImportSource {
	t.Helper()
	s, err := NewLegacyImportSource(kind, version, []byte(raw))
	if err != nil {
		t.Fatalf("source: %v", err)
	}
	return s
}

func TestLegacyImportSuccessAndDuplicate(t *testing.T) {
	store := NewLegacyImportStore(func() time.Time { return time.Unix(10, 0) })
	source := testSource(t, ImportSourceAndroidRoom, 1, `{"downloads":[{"id":"a","url":"https://example.test/a?token=secret","file_name":"a.mp4","state":"complete"},{"id":"b","url":"https://example.test/b","file_name":"b.mp4"}]}`)
	result, err := store.Import(context.Background(), source, AndroidRoomImportAdapter)
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != ImportStatusCommitted || result.ImportedCount != 2 || result.Duplicate {
		t.Fatalf("unexpected result: %+v", result)
	}
	if got := len(store.AuthoritativeObjects()); got != 2 {
		t.Fatalf("authoritative count = %d", got)
	}
	dupe, err := store.Import(context.Background(), source, AndroidRoomImportAdapter)
	if err != nil {
		t.Fatal(err)
	}
	if !dupe.Duplicate || dupe.ImportedCount != 2 || len(store.Journal()) != 1 || len(store.AuthoritativeObjects()) != 2 {
		t.Fatalf("duplicate import was not idempotent: %+v journal=%d objects=%d", dupe, len(store.Journal()), len(store.AuthoritativeObjects()))
	}
}

func TestLegacyImportCrashMidImportCommitsStagedOnRestart(t *testing.T) {
	store := NewLegacyImportStore(func() time.Time { return time.Unix(20, 0) })
	source := testSource(t, ImportSourceAndroidRoom, 1, `{"downloads":[{"id":"resume","url":"https://example.test/r","file_name":"r.mp4"}]}`)
	_, err := store.ImportWithOptions(context.Background(), source, AndroidRoomImportAdapter, ImportOptions{CrashAfterStage: true})
	if !errors.Is(err, ErrImportInterrupted) {
		t.Fatalf("expected interrupted import, got %v", err)
	}
	if len(store.AuthoritativeObjects()) != 0 {
		t.Fatalf("crash-after-stage mutated authoritative state")
	}
	record, ok := store.Record(source.IdempotencyKey())
	if !ok || record.Status != ImportStatusStaged || record.StagedCount != 1 {
		t.Fatalf("missing staged journal record: %+v ok=%v", record, ok)
	}
	result, err := store.Import(context.Background(), source, func(context.Context, LegacyImportSource) ([]ImportedObject, error) {
		t.Fatal("adapter should not be called when a staged import is recovered")
		return nil, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if !result.RecoveredStage || result.Status != ImportStatusCommitted || len(store.AuthoritativeObjects()) != 1 {
		t.Fatalf("staged recovery failed: %+v objects=%d", result, len(store.AuthoritativeObjects()))
	}
}

func TestLegacyImportMalformedPartialUnsupportedAndRetry(t *testing.T) {
	if _, err := NewLegacyImportSource(ImportSourceAndroidRoom, 1, []byte(`{"downloads":[`)); err == nil {
		t.Fatalf("malformed input accepted")
	}
	store := NewLegacyImportStore(func() time.Time { return time.Unix(30, 0) })
	partial := testSource(t, ImportSourceAndroidRoom, 1, `{"downloads":[{"id":"partial","file_name":"missing-url.mp4"}]}`)
	if _, err := store.Import(context.Background(), partial, AndroidRoomImportAdapter); err == nil {
		t.Fatalf("partial source accepted")
	}
	if len(store.AuthoritativeObjects()) != 0 {
		t.Fatalf("partial source mutated authoritative state")
	}
	if record, _ := store.Record(partial.IdempotencyKey()); record.Status != ImportStatusFailed {
		t.Fatalf("partial source was not journaled as failed: %+v", record)
	}
	newer := testSource(t, ImportSourceDesktopJSON, 2, `{"items":[{"id":"x","url":"https://example.test/x","file_name":"x.bin"}]}`)
	if _, err := store.Import(context.Background(), newer, DesktopJSONImportAdapter); !errors.Is(err, ErrUnsupportedSourceVersion) {
		t.Fatalf("unsupported version got %v", err)
	}
	if _, ok := store.Record(newer.IdempotencyKey()); ok {
		t.Fatalf("unsupported newer version created a journal record")
	}

	retry := testSource(t, ImportSourceDesktopJSON, 1, `{"items":[{"id":"d","url":"https://example.test/d","file_name":"d.bin"}]}`)
	failOnce := true
	adapter := func(ctx context.Context, source LegacyImportSource) ([]ImportedObject, error) {
		if failOnce {
			failOnce = false
			return nil, ErrImportFailed
		}
		return DesktopJSONImportAdapter(ctx, source)
	}
	if _, err := store.Import(context.Background(), retry, adapter); err == nil {
		t.Fatalf("first retry fixture import should fail")
	}
	result, err := store.Import(context.Background(), retry, adapter)
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != ImportStatusCommitted || result.ImportedCount != 1 {
		t.Fatalf("retry after failure failed: %+v", result)
	}
}
