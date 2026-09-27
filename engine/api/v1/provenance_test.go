package v1

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

func TestDecodeCreateConfigRejectsCompatibilityMismatches(t *testing.T) {
	good := []byte(`{"protocol":{"major":1,"minor":0},"compatibility":{"engine_version":"0.1.0-dev","abi_major":1,"schema_version":1},"host_platform":"desktop"}`)
	cfg, err := DecodeCreateConfig(good)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Compatibility == nil || cfg.Compatibility.SchemaVersion != SchemaVersion {
		t.Fatalf("compatibility expectation missing: %+v", cfg)
	}

	wrongProtocol := []byte(`{"protocol":{"major":99,"minor":0},"compatibility":{"engine_version":"0.1.0-dev","abi_major":1,"schema_version":1}}`)
	if _, err := DecodeCreateConfig(wrongProtocol); !errors.Is(err, ErrProtocolMismatch) {
		t.Fatalf("wrong protocol err=%v", err)
	}

	wrongSchema := []byte(`{"protocol":{"major":1,"minor":0},"compatibility":{"engine_version":"0.1.0-dev","abi_major":1,"schema_version":999}}`)
	if _, err := DecodeCreateConfig(wrongSchema); !errors.Is(err, ErrCompatibilityMismatch) || !errors.Is(err, ErrSchemaMismatch) {
		t.Fatalf("wrong schema err=%v", err)
	}

	wrongABI := []byte(`{"protocol":{"major":1,"minor":0},"compatibility":{"engine_version":"0.1.0-dev","abi_major":99,"schema_version":1}}`)
	if _, err := DecodeCreateConfig(wrongABI); !errors.Is(err, ErrCompatibilityMismatch) || !errors.Is(err, ErrABIMismatch) {
		t.Fatalf("wrong ABI err=%v", err)
	}

	wrongEngine := []byte(`{"protocol":{"major":1,"minor":0},"compatibility":{"engine_version":"99.0.0","abi_major":1,"schema_version":1}}`)
	if _, err := DecodeCreateConfig(wrongEngine); !errors.Is(err, ErrCompatibilityMismatch) || !errors.Is(err, ErrEngineVersionMismatch) {
		t.Fatalf("wrong engine err=%v", err)
	}
}

func TestMetadataCarriesCompleteBuildProvenance(t *testing.T) {
	metadata := CurrentMetadata()
	if metadata.SchemaVersion != 1 {
		t.Fatalf("schema version=%d", metadata.SchemaVersion)
	}
	if !strings.HasPrefix(metadata.Provenance.GoVersion, "go") {
		t.Fatalf("go version=%q", metadata.Provenance.GoVersion)
	}
	if metadata.Provenance.GitRevision == "" || metadata.Provenance.TargetOS == "" || metadata.Provenance.TargetArch == "" || metadata.Provenance.BuildMode == "" {
		t.Fatalf("incomplete provenance: %+v", metadata.Provenance)
	}
	encoded, err := EncodeMetadata()
	if err != nil {
		t.Fatal(err)
	}
	var decoded Metadata
	if err := json.Unmarshal(encoded, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.Provenance != metadata.Provenance {
		t.Fatalf("metadata provenance changed after encoding: got=%+v want=%+v", decoded.Provenance, metadata.Provenance)
	}
}

func TestProvenanceDocumentValidation(t *testing.T) {
	build := BuildProvenance{
		GoVersion: "go1.25.1", GitRevision: "0123456789abcdef", GitModified: false,
		TargetOS: "android", TargetArch: "arm64", BuildMode: "c-shared",
	}
	document, err := NewProvenanceDocument(build)
	if err != nil {
		t.Fatal(err)
	}
	if document.EngineSchemaVersion != SchemaVersion || document.Build != build {
		t.Fatalf("unexpected provenance document: %+v", document)
	}
	document.EngineSchemaVersion++
	if err := document.Validate(); err == nil {
		t.Fatal("schema drift accepted")
	}
}
