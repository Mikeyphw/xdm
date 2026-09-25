package staging_test

import (
	"os"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/transfer/staging"
)

func TestFreezeBlocksNewWritesUntilThaw(t *testing.T) {
	raw, err := os.CreateTemp(t.TempDir(), "stage-*.bin")
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close()
	file := staging.Wrap(raw)
	thaw := file.Freeze()
	done := make(chan error, 1)
	go func() { _, e := file.WriteAt([]byte("x"), 0); done <- e }()
	select {
	case err := <-done:
		t.Fatalf("write escaped freeze: %v", err)
	case <-time.After(40 * time.Millisecond):
	}
	thaw()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("write did not resume after thaw")
	}
}
