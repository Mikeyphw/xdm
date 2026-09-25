package main

import (
	"errors"
	"fmt"
	"strings"

	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/transfer/metalink"
)

func metalinkTemplate() domainrequest.NetworkIntent {
	res, _ := resource.DeriveIdentity("xgo38-corpus", "metalink-document")
	intent, err := domainrequest.NewNetworkIntent(domainrequest.NetworkIntent{
		TransportURL:         "https://metadata.example/release.meta4",
		Resource:             res,
		Method:               "GET",
		Headers:              []domainrequest.Header{{Name: "Accept", Value: "application/octet-stream"}},
		BackendPreference:    "native",
		AllowBackendFallback: true,
	})
	if err != nil {
		panic(err)
	}
	return intent
}

func metalinkValidCase() error {
	xml := `<metalink xmlns="urn:ietf:params:xml:ns:metalink"><file name="release.bin"><identity>release-1</identity><description>stable payload</description><size>8</size><hash type="sha-256">aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</hash><url priority="20">https://mirror-b.example/release.bin</url><url priority="10">https://mirror-a.example/release.bin</url></file></metalink>`
	doc, err := metalink.Parse(strings.NewReader(xml))
	if err != nil {
		return err
	}
	expanded, err := metalink.Expand(metalinkTemplate(), doc)
	if err != nil {
		return err
	}
	if len(expanded) != 1 || expanded[0].Intent.TransportURL != "https://mirror-a.example/release.bin" || expanded[0].Intent.ExpectedLength == nil || *expanded[0].Intent.ExpectedLength != 8 || len(expanded[0].Intent.Checksums) != 1 || expanded[0].Metadata.Identity != "release-1" {
		return fmt.Errorf("unexpected expansion: %+v", expanded)
	}
	return nil
}

func metalinkMalformedCase() error {
	_, err := metalink.Parse(strings.NewReader(`<metalink><file name="x"><url>https://example.test/x</url></metalink>`))
	if !errors.Is(err, metalink.ErrMalformed) {
		return fmt.Errorf("err=%v", err)
	}
	return nil
}

func metalinkUnsupportedHashCase() error {
	_, err := metalink.Parse(strings.NewReader(`<metalink><file name="x"><hash type="sha3-256">00</hash><url>https://example.test/x</url></file></metalink>`))
	if !errors.Is(err, metalink.ErrUnsupportedHash) {
		return fmt.Errorf("err=%v", err)
	}
	return nil
}

func metalinkDuplicateURLCase() error {
	xml := `<metalink><file name="x"><url priority="50">HTTPS://EXAMPLE.TEST:443/x</url><url priority="1">https://example.test/x</url><url priority="2">https://mirror.test/x</url></file></metalink>`
	doc, err := metalink.Parse(strings.NewReader(xml))
	if err != nil {
		return err
	}
	expanded, err := metalink.Expand(metalinkTemplate(), doc)
	if err != nil {
		return err
	}
	got := expanded[0].Intent
	if got.TransportURL != "https://example.test/x" || len(got.Mirrors) != 1 || got.Mirrors[0] != "https://mirror.test/x" {
		return fmt.Errorf("intent=%+v", got)
	}
	return nil
}

func metalinkConflictCase() error {
	_, err := metalink.Parse(strings.NewReader(`<metalink><file name="x"><size>4</size><size>5</size><url>https://example.test/x</url></file></metalink>`))
	if !errors.Is(err, metalink.ErrConflict) {
		return fmt.Errorf("size err=%v", err)
	}
	_, err = metalink.Parse(strings.NewReader(`<metalink><file name="x"><hash type="sha256">aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</hash><hash type="sha256">bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb</hash><url>https://example.test/x</url></file></metalink>`))
	if !errors.Is(err, metalink.ErrConflict) {
		return fmt.Errorf("hash err=%v", err)
	}
	return nil
}

func metalinkGeneratedIntentCase() error {
	xml := `<metalink><file name="fixture.iso"><version>2</version><language>en</language><os>linux</os><size>4096</size><hash type="sha512">aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</hash><url preference="90">ftp://download.example/fixture.iso</url><url preference="10">https://mirror.example/fixture.iso</url></file></metalink>`
	doc, err := metalink.Parse(strings.NewReader(xml))
	if err != nil {
		return err
	}
	expanded, err := metalink.Expand(metalinkTemplate(), doc)
	if err != nil {
		return err
	}
	got := expanded[0]
	if got.Intent.Method != "GET" || got.Intent.Body != nil || got.Intent.TransportURL != "ftp://download.example/fixture.iso" || len(got.Intent.Mirrors) != 1 || got.Intent.ExpectedLength == nil || *got.Intent.ExpectedLength != 4096 || len(got.Intent.Checksums) != 1 || got.Intent.Checksums[0].Algorithm != "sha512" || got.Metadata.SuggestedName != "fixture.iso" || got.Metadata.Version != "2" || len(got.Metadata.Languages) != 1 || got.Metadata.Languages[0] != "en" || len(got.Metadata.OperatingSystems) != 1 || got.Metadata.OperatingSystems[0] != "linux" {
		return fmt.Errorf("fixture expansion=%+v", got)
	}
	return nil
}

func runMetalinkCorpus() report {
	cases := []caseResult{
		runCase("valid_metalink", metalinkValidCase),
		runCase("malformed_xml", metalinkMalformedCase),
		runCase("unsupported_hash", metalinkUnsupportedHashCase),
		runCase("duplicate_urls", metalinkDuplicateURLCase),
		runCase("conflicting_size_hash", metalinkConflictCase),
		runCase("generated_intent_fixture", metalinkGeneratedIntentCase),
	}
	r := report{SchemaVersion: 1, Mode: "metalink", Status: "pass", Total: len(cases), Cases: cases}
	for _, c := range cases {
		if c.Passed {
			r.Passed++
		}
	}
	if r.Passed != r.Total {
		r.Status = "fail"
	}
	return r
}
