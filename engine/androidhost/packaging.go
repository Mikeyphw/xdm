package androidhost

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"path/filepath"
	"sort"
	"strings"
)

const (
	LibraryName       = "libxdmcore.so"
	HeaderName        = "xdm_core.h"
	MetadataName      = "xdmcore-manifest.json"
	CurrentABIVersion = "xgo.android.abi.v1"
	WireAPIVersion    = "xgo.api.v1"
	SchemaVersion     = 1
)

type AndroidABI struct {
	Name        string `json:"name"`
	GOARCH      string `json:"goarch"`
	GOARM       string `json:"goarm,omitempty"`
	MinAPI      int    `json:"min_api"`
	CCTriple    string `json:"cc_triple"`
	Enabled     bool   `json:"enabled"`
	Description string `json:"description"`
}

func RequiredABIs() []AndroidABI {
	return []AndroidABI{{
		Name: "arm64-v8a", GOARCH: "arm64", MinAPI: 26,
		CCTriple: "aarch64-linux-android26-clang", Enabled: true,
		Description: "primary Termux/Android device ABI for XGO host reconnection",
	}}
}

func RequiredSymbols() []string {
	return []string{
		"xdm_buffer_free",
		"xdm_engine_command",
		"xdm_engine_create",
		"xdm_engine_metadata",
		"xdm_engine_next_frame",
		"xdm_engine_platform_reply",
		"xdm_engine_shutdown",
	}
}

type BuildArtifact struct {
	ABI          string   `json:"abi"`
	GOOS         string   `json:"goos"`
	GOARCH       string   `json:"goarch"`
	MinAPI       int      `json:"min_api"`
	CC           string   `json:"cc"`
	Output       string   `json:"output"`
	HeaderOutput string   `json:"header_output"`
	Command      []string `json:"command"`
}

type MetadataManifest struct {
	SchemaVersion   int      `json:"schema_version"`
	LibraryName     string   `json:"library_name"`
	ABIVersion      string   `json:"abi_version"`
	WireAPIVersion  string   `json:"wire_api_version"`
	GoVersion       string   `json:"go_version"`
	SourceRevision  string   `json:"source_revision"`
	SupportedABIs   []string `json:"supported_abis"`
	RequiredSymbols []string `json:"required_symbols"`
	MetadataSHA256  string   `json:"metadata_sha256"`
}

type SharedLibraryPlan struct {
	ABIs       []AndroidABI     `json:"abis"`
	Artifacts  []BuildArtifact  `json:"artifacts"`
	Manifest   MetadataManifest `json:"manifest"`
	NoDesktopC bool             `json:"no_desktop_libc_assumptions"`
}

func NewSharedLibraryPlan(sourceRevision, goVersion string) (SharedLibraryPlan, error) {
	if strings.TrimSpace(sourceRevision) == "" {
		return SharedLibraryPlan{}, fmt.Errorf("source revision is required")
	}
	if !strings.HasPrefix(goVersion, "go") {
		return SharedLibraryPlan{}, fmt.Errorf("go version must be recorded in Go's canonical form")
	}
	abis := RequiredABIs()
	artifacts := make([]BuildArtifact, 0, len(abis))
	abiNames := make([]string, 0, len(abis))
	for _, abi := range abis {
		if !abi.Enabled {
			continue
		}
		if abi.MinAPI < 26 {
			return SharedLibraryPlan{}, fmt.Errorf("ABI %s has unsupported min API %d", abi.Name, abi.MinAPI)
		}
		abiNames = append(abiNames, abi.Name)
		out := filepath.ToSlash(filepath.Join("app", "XDM.Android", "app", "src", "main", "jniLibs", abi.Name, LibraryName))
		head := filepath.ToSlash(filepath.Join("app", "XDM.Android", "app", "src", "main", "cpp", HeaderName))
		cmd := []string{"env", "GOOS=android", "GOARCH=" + abi.GOARCH, "CGO_ENABLED=1", "CC=" + abi.CCTriple, "go", "build", "-trimpath", "-buildmode=c-shared", "-o", out, "./engine/bridge/cabi"}
		artifacts = append(artifacts, BuildArtifact{ABI: abi.Name, GOOS: "android", GOARCH: abi.GOARCH, MinAPI: abi.MinAPI, CC: abi.CCTriple, Output: out, HeaderOutput: head, Command: cmd})
	}
	if len(artifacts) == 0 {
		return SharedLibraryPlan{}, fmt.Errorf("no Android ABIs enabled")
	}
	symbols := RequiredSymbols()
	manifest := MetadataManifest{SchemaVersion: SchemaVersion, LibraryName: LibraryName, ABIVersion: CurrentABIVersion, WireAPIVersion: WireAPIVersion, GoVersion: goVersion, SourceRevision: sourceRevision, SupportedABIs: abiNames, RequiredSymbols: symbols}
	data, _ := json.Marshal(manifest)
	sum := sha256.Sum256(data)
	manifest.MetadataSHA256 = hex.EncodeToString(sum[:])
	return SharedLibraryPlan{ABIs: abis, Artifacts: artifacts, Manifest: manifest, NoDesktopC: true}, nil
}

func (p SharedLibraryPlan) Validate() error {
	if p.Manifest.SchemaVersion != SchemaVersion || p.Manifest.ABIVersion != CurrentABIVersion || p.Manifest.WireAPIVersion != WireAPIVersion {
		return fmt.Errorf("manifest versions are not canonical")
	}
	if len(p.Artifacts) == 0 {
		return fmt.Errorf("missing artifacts")
	}
	seen := map[string]bool{}
	for _, artifact := range p.Artifacts {
		if artifact.GOOS != "android" || !strings.HasPrefix(artifact.Output, "app/XDM.Android/app/src/main/jniLibs/") || !strings.HasSuffix(artifact.Output, "/"+LibraryName) {
			return fmt.Errorf("artifact %s is not packaged in Android jniLibs", artifact.Output)
		}
		if artifact.HeaderOutput != "app/XDM.Android/app/src/main/cpp/"+HeaderName {
			return fmt.Errorf("unexpected header path %s", artifact.HeaderOutput)
		}
		joined := strings.Join(artifact.Command, " ")
		for _, forbidden := range []string{"GOOS=linux", "glibc", "musl", "-static"} {
			if strings.Contains(joined, forbidden) {
				return fmt.Errorf("desktop libc/toolchain assumption %q in build command", forbidden)
			}
		}
		seen[artifact.ABI] = true
	}
	for _, abi := range p.Manifest.SupportedABIs {
		if !seen[abi] {
			return fmt.Errorf("manifest ABI %s missing artifact", abi)
		}
	}
	return ValidateExports(RequiredSymbols())
}

func ValidateExports(actual []string) error {
	req := RequiredSymbols()
	expected := append([]string(nil), req...)
	got := append([]string(nil), actual...)
	sort.Strings(expected)
	sort.Strings(got)
	if len(expected) != len(got) {
		return fmt.Errorf("export count mismatch: got %d want %d", len(got), len(expected))
	}
	for i := range expected {
		if expected[i] != got[i] {
			return fmt.Errorf("export[%d]=%s want %s", i, got[i], expected[i])
		}
	}
	return nil
}

func HeaderText() string {
	return `#pragma once
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    void* data;
    size_t len;
    uint64_t token;
} xdm_buffer_t;

int xdm_engine_create(const uint8_t* config, size_t config_len, uint64_t* out_handle);
int xdm_engine_command(uint64_t handle, const uint8_t* data, size_t data_len);
int xdm_engine_next_frame(uint64_t handle, int timeout_ms, xdm_buffer_t* out);
int xdm_engine_platform_reply(uint64_t handle, const uint8_t* data, size_t data_len);
int xdm_engine_metadata(uint64_t handle, xdm_buffer_t* out);
int xdm_engine_shutdown(uint64_t handle);
void xdm_buffer_free(xdm_buffer_t buffer);

#ifdef __cplusplus
}
#endif
`
}
