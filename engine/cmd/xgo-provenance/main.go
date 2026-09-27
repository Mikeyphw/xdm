package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"

	v1 "github.com/subhra74/xdm/engine/api/v1"
)

func main() {
	var (
		output    = flag.String("output", "", "write provenance JSON to this path")
		validate  = flag.String("validate", "", "validate an existing provenance JSON document")
		revision  = flag.String("revision", "", "Git revision")
		goVersion = flag.String("go-version", "", "Go toolchain version")
		goos      = flag.String("goos", "", "target GOOS")
		goarch    = flag.String("goarch", "", "target GOARCH")
		buildMode = flag.String("build-mode", "", "Go build mode")
		modified  = flag.Bool("modified", false, "source tree was modified at build time")
	)
	flag.Parse()

	if *validate != "" {
		if err := validateFile(*validate, *goos, *goarch, *buildMode); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	if *output == "" {
		fmt.Fprintln(os.Stderr, "--output is required")
		os.Exit(2)
	}
	build := v1.BuildProvenance{
		GoVersion:   *goVersion,
		GitRevision: *revision,
		GitModified: *modified,
		TargetOS:    *goos,
		TargetArch:  *goarch,
		BuildMode:   *buildMode,
	}
	document, err := v1.NewProvenanceDocument(build)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if err := writeJSON(*output, document); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func validateFile(path, expectedOS, expectedArch, expectedMode string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var document v1.ProvenanceDocument
	if err := json.Unmarshal(data, &document); err != nil {
		return fmt.Errorf("decode provenance: %w", err)
	}
	if err := document.Validate(); err != nil {
		return err
	}
	if expectedOS != "" && document.Build.TargetOS != expectedOS {
		return fmt.Errorf("provenance target OS %q does not match expected %q", document.Build.TargetOS, expectedOS)
	}
	if expectedArch != "" && document.Build.TargetArch != expectedArch {
		return fmt.Errorf("provenance target arch %q does not match expected %q", document.Build.TargetArch, expectedArch)
	}
	if expectedMode != "" && document.Build.BuildMode != expectedMode {
		return fmt.Errorf("provenance build mode %q does not match expected %q", document.Build.BuildMode, expectedMode)
	}
	return nil
}

func writeJSON(path string, document v1.ProvenanceDocument) error {
	data, err := json.MarshalIndent(document, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
