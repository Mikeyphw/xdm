package v1

import (
	"errors"
	"fmt"
	"runtime"
	"runtime/debug"
	"strconv"
	"strings"
)

var (
	// BuildRevision, BuildModified, and BuildMode are populated by release/native
	// build entrypoints with -ldflags -X. Development/test builds fall back to
	// Go's embedded VCS/build settings when available.
	BuildRevision = ""
	BuildModified = ""
	BuildMode     = ""
)

type BuildProvenance struct {
	GoVersion   string `json:"go_version"`
	GitRevision string `json:"git_revision"`
	GitModified bool   `json:"git_modified"`
	TargetOS    string `json:"target_os"`
	TargetArch  string `json:"target_arch"`
	BuildMode   string `json:"build_mode"`
}

type ProvenanceDocument struct {
	SchemaVersion       int             `json:"schema_version"`
	EngineVersion       string          `json:"engine_version"`
	Protocol            Protocol        `json:"protocol"`
	ABIMajor            int             `json:"abi_major"`
	EngineSchemaVersion int             `json:"engine_schema_version"`
	Build               BuildProvenance `json:"build"`
}

func CurrentBuildProvenance() BuildProvenance {
	settings := map[string]string{}
	if info, ok := debug.ReadBuildInfo(); ok {
		for _, setting := range info.Settings {
			settings[setting.Key] = setting.Value
		}
	}

	revision := strings.TrimSpace(BuildRevision)
	if revision == "" {
		revision = strings.TrimSpace(settings["vcs.revision"])
	}
	if revision == "" {
		revision = "unknown"
	}

	modified := parseBuildBool(BuildModified)
	if strings.TrimSpace(BuildModified) == "" {
		modified = parseBuildBool(settings["vcs.modified"])
	}

	mode := strings.TrimSpace(BuildMode)
	if mode == "" {
		mode = strings.TrimSpace(settings["-buildmode"])
	}
	if mode == "" {
		mode = "default"
	}

	return BuildProvenance{
		GoVersion:   runtime.Version(),
		GitRevision: revision,
		GitModified: modified,
		TargetOS:    runtime.GOOS,
		TargetArch:  runtime.GOARCH,
		BuildMode:   mode,
	}
}

func NewProvenanceDocument(build BuildProvenance) (ProvenanceDocument, error) {
	document := ProvenanceDocument{
		SchemaVersion:       1,
		EngineVersion:       EngineVersion,
		Protocol:            CurrentProtocol(),
		ABIMajor:            ABIMajor,
		EngineSchemaVersion: SchemaVersion,
		Build:               build,
	}
	if err := document.Validate(); err != nil {
		return ProvenanceDocument{}, err
	}
	return document, nil
}

func (p ProvenanceDocument) Validate() error {
	if p.SchemaVersion != 1 {
		return fmt.Errorf("unsupported provenance document schema %d", p.SchemaVersion)
	}
	if p.EngineVersion != EngineVersion || p.Protocol != CurrentProtocol() || p.ABIMajor != ABIMajor || p.EngineSchemaVersion != SchemaVersion {
		return errors.New("provenance compatibility tuple does not match canonical engine metadata")
	}
	if !validSemanticVersion(p.EngineVersion) {
		return fmt.Errorf("engine version %q is not semantic", p.EngineVersion)
	}
	if !strings.HasPrefix(strings.TrimSpace(p.Build.GoVersion), "go") {
		return errors.New("provenance Go toolchain is missing or non-canonical")
	}
	if strings.TrimSpace(p.Build.GitRevision) == "" {
		return errors.New("provenance Git revision is missing")
	}
	if strings.TrimSpace(p.Build.TargetOS) == "" || strings.TrimSpace(p.Build.TargetArch) == "" {
		return errors.New("provenance target OS/arch is missing")
	}
	if strings.TrimSpace(p.Build.BuildMode) == "" {
		return errors.New("provenance build mode is missing")
	}
	return nil
}

func validSemanticVersion(value string) bool {
	parts := strings.SplitN(value, "-", 2)
	core := strings.Split(parts[0], ".")
	if len(core) != 3 {
		return false
	}
	for _, part := range core {
		if part == "" {
			return false
		}
		if _, err := strconv.Atoi(part); err != nil {
			return false
		}
	}
	return true
}

func parseBuildBool(value string) bool {
	parsed, err := strconv.ParseBool(strings.TrimSpace(value))
	return err == nil && parsed
}
