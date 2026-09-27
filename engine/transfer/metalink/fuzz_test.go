package metalink

import (
	"strings"
	"testing"
)

func FuzzMetalinkParser(f *testing.F) {
	for _, seed := range []string{
		`<metalink><file name="a.bin"><url>https://example.test/a.bin</url></file></metalink>`,
		`<metalink xmlns="urn:ietf:params:xml:ns:metalink"><file name="a.bin"><size>1</size><url priority="1">https://example.test/a</url></file></metalink>`,
		`<metalink><file name="../escape"><url>https://example.test/a</url></file></metalink>`,
		`<not-metalink/>`,
	} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, raw string) {
		if len(raw) > int(MaxDocumentBytes)+1 {
			raw = raw[:int(MaxDocumentBytes)+1]
		}
		doc, err := Parse(strings.NewReader(raw))
		if err != nil {
			return
		}
		if len(doc.Files) == 0 {
			t.Fatal("successful Metalink parse produced no files")
		}
		for _, file := range doc.Files {
			if strings.TrimSpace(file.Metadata.SuggestedName) == "" || len(file.Sources) == 0 {
				t.Fatalf("successful Metalink parse produced incomplete file: %+v", file)
			}
		}
	})
}
