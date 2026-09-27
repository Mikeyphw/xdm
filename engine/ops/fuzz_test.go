package ops

import (
	"context"
	"testing"
)

func FuzzLegacyImportParser(f *testing.F) {
	for _, seed := range []string{
		`{"downloads":[{"id":"1","url":"https://example.test/a","file_name":"a"}]}`,
		`{"history":[{"id":"1","payload":{"state":"complete"}}]}`,
		`{"downloads":[`,
		`{}`,
	} {
		f.Add(seed, true, uint8(1))
	}
	f.Fuzz(func(t *testing.T, raw string, android bool, version uint8) {
		if len(raw) > 256*1024 {
			raw = raw[:256*1024]
		}
		kind := ImportSourceDesktopJSON
		adapter := DesktopJSONImportAdapter
		if android {
			kind = ImportSourceAndroidRoom
			adapter = AndroidRoomImportAdapter
		}
		source, err := NewLegacyImportSource(kind, int(version)+1, []byte(raw))
		if err != nil {
			return
		}
		if err := source.Validate(); err != nil {
			return
		}
		objects, err := adapter(context.Background(), source)
		if err != nil {
			return
		}
		for _, object := range objects {
			if err := object.Validate(); err != nil {
				t.Fatalf("adapter emitted invalid object: %v", err)
			}
		}
	})
}
