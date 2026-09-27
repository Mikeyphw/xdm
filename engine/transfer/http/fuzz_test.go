package httptransfer

import (
	"strings"
	"testing"
)

func FuzzContentRangeParser(f *testing.F) {
	for _, seed := range []string{"bytes 0-0/1", "bytes 1-9/10", "bytes 0-9/*", "bytes -1-3/4", "items 0-1/2", "bytes 4-2/10"} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, raw string) {
		if len(raw) > 8*1024 {
			raw = raw[:8*1024]
		}
		parsed, err := parseContentRange(raw)
		if err != nil {
			return
		}
		if parsed.start < 0 || parsed.end < parsed.start {
			t.Fatalf("invalid successful content range: %+v", parsed)
		}
		if parsed.total != nil && *parsed.total <= parsed.end {
			t.Fatalf("invalid successful content range total: %+v", parsed)
		}
	})
}

func FuzzContentDispositionParser(f *testing.F) {
	for _, seed := range []string{`attachment; filename="file.bin"`, `inline`, `attachment; filename*=UTF-8''hello.txt`, `attachment; filename="bad`, ""} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, raw string) {
		if len(raw) > 16*1024 {
			raw = raw[:16*1024]
		}
		kind, _, err := parseDisposition(raw)
		if err != nil {
			return
		}
		if strings.TrimSpace(raw) != "" && kind == "" {
			t.Fatalf("non-empty disposition parsed without a media type: %q", raw)
		}
	})
}
