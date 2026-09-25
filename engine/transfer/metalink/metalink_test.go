package metalink_test

import (
	"errors"
	"strings"
	"testing"

	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/transfer/metalink"
)

func template(t *testing.T) domainrequest.NetworkIntent {
	t.Helper()
	r, err := resource.DeriveIdentity("metalink-test", "document")
	if err != nil {
		t.Fatal(err)
	}
	in, err := domainrequest.NewNetworkIntent(domainrequest.NetworkIntent{
		TransportURL:         "https://metadata.example/file.meta4",
		Resource:             r,
		Method:               "GET",
		Headers:              []domainrequest.Header{{Name: "Accept", Value: "application/octet-stream"}},
		BackendPreference:    "native",
		AllowBackendFallback: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	return in
}

func TestParseAndExpandValidMetalink(t *testing.T) {
	xml := `<?xml version="1.0"?>
<metalink xmlns="urn:ietf:params:xml:ns:metalink">
  <file name="archive.tar.zst">
    <identity>release-42</identity><description>release payload</description><version>42</version><language>en</language><os>linux</os>
    <size>12</size>
    <hash type="SHA-256">aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</hash>
    <url priority="20" location="US">HTTPS://Mirror2.Example:443/archive.tar.zst</url>
    <url priority="10" location="GB">https://mirror1.example/archive.tar.zst</url>
    <url priority="10" location="GB">https://mirror1.example/archive.tar.zst</url>
  </file>
</metalink>`
	doc, err := metalink.Parse(strings.NewReader(xml))
	if err != nil {
		t.Fatal(err)
	}
	if len(doc.Files) != 1 || doc.Files[0].Metadata.Identity != "release-42" || len(doc.Files[0].Checksums) != 1 {
		t.Fatalf("doc=%+v", doc)
	}
	expanded, err := metalink.Expand(template(t), doc)
	if err != nil {
		t.Fatal(err)
	}
	if len(expanded) != 1 {
		t.Fatalf("expanded=%d", len(expanded))
	}
	got := expanded[0]
	if got.Intent.TransportURL != "https://mirror1.example/archive.tar.zst" {
		t.Fatalf("primary=%s", got.Intent.TransportURL)
	}
	if len(got.Intent.Mirrors) != 1 || got.Intent.Mirrors[0] != "https://mirror2.example/archive.tar.zst" {
		t.Fatalf("mirrors=%v", got.Intent.Mirrors)
	}
	if got.Intent.ExpectedLength == nil || *got.Intent.ExpectedLength != 12 {
		t.Fatalf("length=%v", got.Intent.ExpectedLength)
	}
	if len(got.Intent.Checksums) != 1 || got.Intent.Checksums[0].Algorithm != "sha256" {
		t.Fatalf("checksums=%v", got.Intent.Checksums)
	}
	if got.Intent.Method != "GET" || got.Intent.Body != nil || got.Metadata.SuggestedName != "archive.tar.zst" {
		t.Fatalf("got=%+v", got)
	}
}

func TestMalformedXML(t *testing.T) {
	if _, err := metalink.Parse(strings.NewReader(`<metalink><file name="x"><url>https://example.test/x</url></metalink>`)); !errors.Is(err, metalink.ErrMalformed) {
		t.Fatalf("err=%v", err)
	}
}

func TestUnsupportedHash(t *testing.T) {
	xml := `<metalink><file name="x"><hash type="sha3-256">aa</hash><url>https://example.test/x</url></file></metalink>`
	if _, err := metalink.Parse(strings.NewReader(xml)); !errors.Is(err, metalink.ErrUnsupportedHash) {
		t.Fatalf("err=%v", err)
	}
}

func TestDuplicateURLsNormalizeAfterPriority(t *testing.T) {
	xml := `<metalink><file name="x"><url priority="20">HTTPS://EXAMPLE.TEST:443/x</url><url priority="5">https://example.test/x</url><url priority="10">https://mirror.test/x</url></file></metalink>`
	doc, err := metalink.Parse(strings.NewReader(xml))
	if err != nil {
		t.Fatal(err)
	}
	expanded, err := metalink.Expand(template(t), doc)
	if err != nil {
		t.Fatal(err)
	}
	got := expanded[0].Intent
	if got.TransportURL != "https://example.test/x" || len(got.Mirrors) != 1 || got.Mirrors[0] != "https://mirror.test/x" {
		t.Fatalf("intent=%+v", got)
	}
}

func TestConflictingSizeAndHash(t *testing.T) {
	sizeXML := `<metalink><file name="x"><size>10</size><size>11</size><url>https://example.test/x</url></file></metalink>`
	if _, err := metalink.Parse(strings.NewReader(sizeXML)); !errors.Is(err, metalink.ErrConflict) {
		t.Fatalf("size err=%v", err)
	}
	hashXML := `<metalink><file name="x"><hash type="sha256">aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</hash><hash type="SHA-256">bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb</hash><url>https://example.test/x</url></file></metalink>`
	if _, err := metalink.Parse(strings.NewReader(hashXML)); !errors.Is(err, metalink.ErrConflict) {
		t.Fatalf("hash err=%v", err)
	}
}

func TestExpansionRejectsTemplateIntegrityConflict(t *testing.T) {
	base := template(t)
	length := int64(9)
	base.ExpectedLength = &length
	base.Checksums = []domainrequest.Checksum{{Algorithm: "sha256", Digest: strings.Repeat("b", 64)}}
	xml := `<metalink><file name="x"><size>10</size><hash type="sha256">aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</hash><url>https://example.test/x</url></file></metalink>`
	doc, err := metalink.Parse(strings.NewReader(xml))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := metalink.Expand(base, doc); !errors.Is(err, metalink.ErrConflict) {
		t.Fatalf("err=%v", err)
	}
}

func TestLegacyResourcesAndVerificationAreCanonicalized(t *testing.T) {
	xml := `<metalink><file name="legacy.iso"><size>4</size><verification><hash type="sha-1">aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</hash></verification><resources><url preference="90">ftp://EXAMPLE.TEST:21/legacy.iso</url><url preference="10">https://mirror.test/legacy.iso</url></resources></file></metalink>`
	doc, err := metalink.Parse(strings.NewReader(xml))
	if err != nil {
		t.Fatal(err)
	}
	expanded, err := metalink.Expand(template(t), doc)
	if err != nil {
		t.Fatal(err)
	}
	got := expanded[0].Intent
	if got.TransportURL != "ftp://example.test/legacy.iso" || got.Checksums[0].Algorithm != "sha1" {
		t.Fatalf("intent=%+v", got)
	}
}

func TestMultiFileExpansionGetsDistinctResources(t *testing.T) {
	xml := `<metalink><file name="a"><url>https://example.test/a</url></file><file name="b"><url>https://example.test/b</url></file></metalink>`
	doc, err := metalink.Parse(strings.NewReader(xml))
	if err != nil {
		t.Fatal(err)
	}
	expanded, err := metalink.Expand(template(t), doc)
	if err != nil {
		t.Fatal(err)
	}
	if len(expanded) != 2 || expanded[0].Intent.Resource == expanded[1].Intent.Resource {
		t.Fatalf("resources=%v %v", expanded[0].Intent.Resource, expanded[1].Intent.Resource)
	}
}

func TestDocumentLimit(t *testing.T) {
	body := `<metalink><file name="x"><url>https://example.test/x</url><description>` + strings.Repeat("a", int(metalink.MaxDocumentBytes)) + `</description></file></metalink>`
	if _, err := metalink.Parse(strings.NewReader(body)); !errors.Is(err, metalink.ErrDocumentTooLarge) {
		t.Fatalf("err=%v", err)
	}
}

func TestLegacyFilesWrapperAndDuplicateFileNames(t *testing.T) {
	legacy := `<metalink xmlns="http://www.metalinker.org/"><files><file name="legacy.bin"><resources><url>https://example.test/legacy.bin</url></resources></file></files></metalink>`
	doc, err := metalink.Parse(strings.NewReader(legacy))
	if err != nil || len(doc.Files) != 1 {
		t.Fatalf("doc=%+v err=%v", doc, err)
	}
	dupe := `<metalink><file name="same"><url>https://example.test/a</url></file><file name="same"><url>https://example.test/b</url></file></metalink>`
	if _, err := metalink.Parse(strings.NewReader(dupe)); !errors.Is(err, metalink.ErrConflict) {
		t.Fatalf("duplicate err=%v", err)
	}
}

func TestFileNameTraversalRejected(t *testing.T) {
	for _, name := range []string{"../escape", "a/../escape", "/absolute", `C:/windows`, `a\\b`, "dir/"} {
		xml := `<metalink><file name="` + name + `"><url>https://example.test/x</url></file></metalink>`
		if _, err := metalink.Parse(strings.NewReader(xml)); !errors.Is(err, metalink.ErrMalformed) {
			t.Fatalf("name=%q err=%v", name, err)
		}
	}
	valid := `<metalink><file name="dir/sub/file.bin"><language>en</language><language>pt-BR</language><os>linux</os><os>windows</os><url>https://example.test/x</url></file></metalink>`
	doc, err := metalink.Parse(strings.NewReader(valid))
	if err != nil {
		t.Fatal(err)
	}
	if len(doc.Files[0].Metadata.Languages) != 2 || len(doc.Files[0].Metadata.OperatingSystems) != 2 {
		t.Fatalf("metadata=%+v", doc.Files[0].Metadata)
	}
}
