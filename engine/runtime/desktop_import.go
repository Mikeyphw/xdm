package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/subhra74/xdm/engine/ops"
	"github.com/subhra74/xdm/engine/runtime/command"
	"github.com/subhra74/xdm/engine/runtime/event"
)

const (
	DesktopLegacyImportKind     = "desktop.import_legacy_state"
	DesktopLegacyPackageVersion = 1
)

type desktopLegacyImportEnvelope struct {
	ClientRequestID string          `json:"client_request_id"`
	Package         json.RawMessage `json:"package"`
}

type desktopLegacyPackage struct {
	PackageVersion int                 `json:"package_version"`
	Downloads      []DesktopUIDownload `json:"downloads"`
}

type desktopImportResult struct {
	ClientRequestID string `json:"client_request_id,omitempty"`
	OK              bool   `json:"ok"`
	Status          string `json:"status"`
	ImportKey       string `json:"import_key,omitempty"`
	Duplicate       bool   `json:"duplicate"`
	ImportedCount   int    `json:"imported_count"`
	ErrorCode       string `json:"error_code,omitempty"`
	Message         string `json:"message,omitempty"`
}

func (e *Engine) desktopLegacyImportHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var request desktopLegacyImportEnvelope
	if err := json.Unmarshal(env.Payload, &request); err != nil || len(request.Package) == 0 {
		if err == nil {
			err = errors.New("desktop legacy import package is missing")
		}
		return emitDesktopImportFailure(inv, request.ClientRequestID, "invalid_package", err)
	}
	source, err := ops.NewLegacyImportSource(ops.ImportSourceDesktopJSON, DesktopLegacyPackageVersion, request.Package)
	if err != nil {
		return emitDesktopImportFailure(inv, request.ClientRequestID, "invalid_package", err)
	}
	var pkg desktopLegacyPackage
	if err := json.Unmarshal(source.Raw, &pkg); err != nil {
		return emitDesktopImportFailure(inv, request.ClientRequestID, "invalid_package", err)
	}
	if pkg.PackageVersion != DesktopLegacyPackageVersion {
		return emitDesktopImportFailure(inv, request.ClientRequestID, "unsupported_package_version", fmt.Errorf("desktop package version %d", pkg.PackageVersion))
	}
	ui := DesktopUIProjection{Revision: 1, Downloads: append([]DesktopUIDownload(nil), pkg.Downloads...)}
	for _, item := range ui.Downloads {
		if err := validateDesktopUIDownload(item, true); err != nil {
			return emitDesktopImportFailure(inv, request.ClientRequestID, "invalid_download_projection", err)
		}
	}

	framework := ops.NewLegacyImportStore(nil)
	imported, err := framework.Import(ctx, source, ops.DesktopJSONImportAdapter)
	if err != nil {
		return emitDesktopImportFailure(inv, request.ClientRequestID, "framework_import_failed", err)
	}
	duplicate, err := e.desktopAuthority.commitImport(source, framework.AuthoritativeObjects(), ui)
	if err != nil {
		code := "authority_commit_failed"
		if errors.Is(err, ErrDesktopAuthorityAlreadyImported) {
			code = "legacy_changed_after_cutover"
		}
		return emitDesktopImportFailure(inv, request.ClientRequestID, code, err)
	}
	snap := e.desktopAuthority.snapshot()
	if err := e.desktopUI.seed(snap.UI); err != nil {
		return err
	}
	if err := emitDesktopUIProjection(inv, e.desktopUI.snapshot()); err != nil {
		return err
	}
	result := desktopImportResult{
		ClientRequestID: request.ClientRequestID,
		OK:              true,
		Status:          "committed",
		ImportKey:       snap.ImportKey,
		Duplicate:       duplicate,
		ImportedCount:   imported.ImportedCount,
	}
	payload, _ := json.Marshal(result)
	_, err = inv.Emit(event.Durable, "desktop.import.result", payload, "")
	return err
}

func emitDesktopImportFailure(inv *Invocation, clientRequestID, code string, cause error) error {
	result := desktopImportResult{ClientRequestID: clientRequestID, OK: false, Status: "failed", ErrorCode: code, Message: cause.Error()}
	payload, _ := json.Marshal(result)
	if _, err := inv.Emit(event.Durable, "desktop.import.result", payload, ""); err != nil {
		return err
	}
	return cause
}
