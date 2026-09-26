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
	AndroidLegacyRoomImportKind     = "android.import_legacy_room"
	AndroidLegacyRoomPackageVersion = 1
	AndroidLegacyRoomMaxSchema      = 25
)

type androidLegacyRoomImportEnvelope struct {
	ClientRequestID string          `json:"client_request_id"`
	Package         json.RawMessage `json:"package"`
}

type androidLegacyRoomPackage struct {
	PackageVersion    int                   `json:"package_version"`
	RoomSchemaVersion int                   `json:"room_schema_version"`
	Downloads         []AndroidUIDownload   `json:"downloads"`
	MediaSync         androidMediaSyncInput `json:"media_sync"`
}

type androidImportResult struct {
	ClientRequestID   string `json:"client_request_id,omitempty"`
	OK                bool   `json:"ok"`
	Status            string `json:"status"`
	ImportKey         string `json:"import_key,omitempty"`
	Duplicate         bool   `json:"duplicate"`
	ImportedCount     int    `json:"imported_count"`
	RoomSchemaVersion int    `json:"room_schema_version"`
	ErrorCode         string `json:"error_code,omitempty"`
	Message           string `json:"message,omitempty"`
}

func (e *Engine) androidLegacyRoomImportHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var request androidLegacyRoomImportEnvelope
	if err := json.Unmarshal(env.Payload, &request); err != nil || len(request.Package) == 0 {
		if err == nil {
			err = errors.New("Android Room import package is missing")
		}
		return emitAndroidImportFailure(inv, request.ClientRequestID, "invalid_package", err)
	}
	source, err := ops.NewLegacyImportSource(ops.ImportSourceAndroidRoom, AndroidLegacyRoomPackageVersion, request.Package)
	if err != nil {
		return emitAndroidImportFailure(inv, request.ClientRequestID, "invalid_package", err)
	}
	var pkg androidLegacyRoomPackage
	if err := json.Unmarshal(source.Raw, &pkg); err != nil {
		return emitAndroidImportFailure(inv, request.ClientRequestID, "invalid_package", err)
	}
	if pkg.PackageVersion != AndroidLegacyRoomPackageVersion {
		return emitAndroidImportFailure(inv, request.ClientRequestID, "unsupported_package_version", fmt.Errorf("Android Room package version %d", pkg.PackageVersion))
	}
	if pkg.RoomSchemaVersion < 1 || pkg.RoomSchemaVersion > AndroidLegacyRoomMaxSchema {
		return emitAndroidImportFailure(inv, request.ClientRequestID, "unsupported_room_schema", fmt.Errorf("Android Room schema %d", pkg.RoomSchemaVersion))
	}
	ui := AndroidUIProjection{Revision: 1, Downloads: append([]AndroidUIDownload(nil), pkg.Downloads...)}
	if err := validateAndroidUIProjection(ui); err != nil {
		return emitAndroidImportFailure(inv, request.ClientRequestID, "invalid_download_projection", err)
	}

	candidateMedia := newAndroidMediaState()
	mediaSnapshot := androidMediaPersistentSnapshot{Sync: androidMediaSyncInput{Revision: 1}, Selections: map[string]androidMediaSelection{}}
	if len(pkg.MediaSync.Captures) > 0 {
		if pkg.MediaSync.Revision <= 0 {
			pkg.MediaSync.Revision = 1
		}
		if _, _, err := candidateMedia.replaceLegacy(pkg.MediaSync); err != nil {
			return emitAndroidImportFailure(inv, request.ClientRequestID, "invalid_media_projection", err)
		}
		mediaSnapshot = candidateMedia.persistentSnapshot()
	}

	framework := ops.NewLegacyImportStore(nil)
	imported, err := framework.Import(ctx, source, ops.AndroidRoomImportAdapter)
	if err != nil {
		return emitAndroidImportFailure(inv, request.ClientRequestID, "framework_import_failed", err)
	}
	objects := framework.AuthoritativeObjects()
	duplicate, err := e.androidAuthority.commitImport(source, pkg.RoomSchemaVersion, objects, ui, mediaSnapshot)
	if err != nil {
		code := "authority_commit_failed"
		if errors.Is(err, ErrAndroidAuthorityAlreadyImported) {
			code = "room_changed_after_cutover"
		}
		return emitAndroidImportFailure(inv, request.ClientRequestID, code, err)
	}

	snap := e.androidAuthority.snapshot()
	if err := e.androidUI.seed(snap.UI); err != nil {
		return err
	}
	e.androidMedia = newAndroidMediaState()
	if err := e.androidMedia.restorePersistent(snap.Media); err != nil {
		return err
	}

	uiPayload, _ := json.Marshal(e.androidUI.snapshot())
	if _, err := inv.Emit(event.Telemetry, "android.ui.projection", uiPayload, "android.ui.downloads"); err != nil {
		return err
	}
	if err := emitAndroidMediaProjection(inv, e.androidMedia.projection()); err != nil {
		return err
	}
	result := androidImportResult{
		ClientRequestID: request.ClientRequestID, OK: true, Status: "committed", ImportKey: snap.ImportKey, Duplicate: duplicate,
		ImportedCount: imported.ImportedCount, RoomSchemaVersion: snap.RoomSchemaVersion,
	}
	payload, _ := json.Marshal(result)
	_, err = inv.Emit(event.Durable, "android.import.result", payload, "")
	return err
}

func emitAndroidImportFailure(inv *Invocation, clientRequestID, code string, cause error) error {
	result := androidImportResult{ClientRequestID: clientRequestID, OK: false, Status: "failed", ErrorCode: code, Message: cause.Error()}
	payload, _ := json.Marshal(result)
	if _, err := inv.Emit(event.Durable, "android.import.result", payload, ""); err != nil {
		return err
	}
	return cause
}
