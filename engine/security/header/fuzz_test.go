package header

import (
	"strings"
	"testing"

	domainrequest "github.com/subhra74/xdm/engine/domain/request"
)

func FuzzHeaderAdmission(f *testing.F) {
	seeds := [][2]string{{"Accept", "video/mp4"}, {"User-Agent", "XGO"}, {"Authorization", "Bearer secret"}, {"X-Test", "ok\r\nInjected: yes"}, {"Sec-Fetch-Site", "same-origin"}}
	for _, seed := range seeds {
		f.Add(seed[0], seed[1], true)
	}
	f.Fuzz(func(t *testing.T, name, value string, external bool) {
		if len(name) > 1024 {
			name = name[:1024]
		}
		if len(value) > 16*1024 {
			value = value[:16*1024]
		}
		source := SourceTrustedEngine
		if external {
			source = SourceExternalHandoff
		}
		out, err := Admit([]domainrequest.Header{{Name: name, Value: value}}, source)
		if err != nil {
			return
		}
		for key, values := range out {
			if strings.ContainsAny(key, "\r\n") || SensitiveName(key) {
				t.Fatalf("unsafe admitted header name %q", key)
			}
			for _, admitted := range values {
				if strings.ContainsAny(admitted, "\r\n") {
					t.Fatalf("unsafe admitted header value for %q", key)
				}
			}
		}
	})
}
