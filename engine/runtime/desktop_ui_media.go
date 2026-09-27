package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/subhra74/xdm/engine/runtime/command"
	"github.com/subhra74/xdm/engine/runtime/event"
	"github.com/subhra74/xdm/engine/runtime/platform"
)

const (
	DesktopUICommandKind     = "desktop.ui.download_command"
	DesktopUIObservationKind = "desktop.ui.execution_observation"
	DesktopUISnapshotKind    = "desktop.ui.snapshot"
	DesktopMediaCaptureKind  = "desktop.media.capture"
	DesktopMediaSelectKind   = "desktop.media.select"
	DesktopMediaExecuteKind  = "desktop.media.execute"
	DesktopMediaSnapshotKind = "desktop.media.snapshot"
)

type DesktopUIDownload struct {
	ID                   string  `json:"id"`
	FileName             string  `json:"file_name"`
	SourceURL            string  `json:"source_url"`
	DestinationPath      string  `json:"destination_path"`
	DownloadedBytes      int64   `json:"downloaded_bytes"`
	TotalBytes           *int64  `json:"total_bytes,omitempty"`
	BytesPerSecond       float64 `json:"bytes_per_second"`
	State                string  `json:"state"`
	UpdatedAtEpochMS     int64   `json:"updated_at_epoch_ms"`
	ErrorMessage         *string `json:"error_message,omitempty"`
	QueueID              string  `json:"queue_id,omitempty"`
	CategoryID           *string `json:"category_id,omitempty"`
	QueueOrder           int     `json:"queue_order"`
	ConnectionCount      int     `json:"connection_count"`
	Priority             string  `json:"priority"`
	Backend              string  `json:"backend"`
	BackendTaskID        string  `json:"backend_task_id,omitempty"`
	BackendPreference    string  `json:"backend_preference"`
	AllowBackendFallback bool    `json:"allow_backend_fallback"`
	Archived             bool    `json:"archived"`
}

type DesktopUIProjection struct {
	Revision  int64               `json:"revision"`
	Downloads []DesktopUIDownload `json:"downloads"`
}

type DesktopUICommand struct {
	ClientRequestID string             `json:"client_request_id"`
	Action          string             `json:"action"`
	DownloadID      string             `json:"download_id,omitempty"`
	Download        *DesktopUIDownload `json:"download,omitempty"`
	RequestRef      string             `json:"request_ref,omitempty"`
	Backend         string             `json:"backend,omitempty"`
	BackendTaskID   string             `json:"backend_task_id,omitempty"`
}

type desktopUIObservation struct {
	Downloads []DesktopUIDownload `json:"downloads"`
}

type desktopUIState struct {
	mu         sync.RWMutex
	projection DesktopUIProjection
}

func newDesktopUIState() *desktopUIState {
	return &desktopUIState{projection: DesktopUIProjection{Downloads: []DesktopUIDownload{}}}
}

func validateDesktopUIDownload(d DesktopUIDownload, requireID bool) error {
	if requireID && strings.TrimSpace(d.ID) == "" {
		return errors.New("desktop UI download id is blank")
	}
	if strings.TrimSpace(d.FileName) == "" || strings.TrimSpace(d.SourceURL) == "" || strings.TrimSpace(d.DestinationPath) == "" {
		return errors.New("desktop UI download is incomplete")
	}
	if strings.TrimSpace(d.State) == "" || strings.TrimSpace(d.Backend) == "" {
		return errors.New("desktop UI download state/backend is blank")
	}
	if d.DownloadedBytes < 0 || (d.TotalBytes != nil && *d.TotalBytes < 0) || d.BytesPerSecond < 0 {
		return errors.New("desktop UI download counters are invalid")
	}
	return nil
}

func validateDesktopUICommand(c DesktopUICommand) error {
	if strings.TrimSpace(c.ClientRequestID) == "" {
		return errors.New("desktop UI command request id is blank")
	}
	switch c.Action {
	case "add":
		if c.Download == nil || strings.TrimSpace(c.RequestRef) == "" {
			return errors.New("desktop add command requires download template and request reference")
		}
		if err := validateDesktopUIDownload(*c.Download, false); err != nil {
			return err
		}
	case "pause", "resume", "cancel", "retry":
		if strings.TrimSpace(c.DownloadID) == "" {
			return fmt.Errorf("%s command requires download id", c.Action)
		}
	default:
		return fmt.Errorf("unsupported desktop UI action %q", c.Action)
	}
	return nil
}

func (s *desktopUIState) seed(projection DesktopUIProjection) error {
	seen := make(map[string]struct{}, len(projection.Downloads))
	for _, d := range projection.Downloads {
		if err := validateDesktopUIDownload(d, true); err != nil {
			return err
		}
		if _, exists := seen[d.ID]; exists {
			return fmt.Errorf("duplicate desktop UI download %s", d.ID)
		}
		seen[d.ID] = struct{}{}
	}
	if projection.Downloads == nil {
		projection.Downloads = []DesktopUIDownload{}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.projection = projection
	return nil
}

func (s *desktopUIState) snapshot() DesktopUIProjection {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := s.projection
	out.Downloads = append([]DesktopUIDownload(nil), s.projection.Downloads...)
	return out
}

func (s *desktopUIState) observe(downloads []DesktopUIDownload) (DesktopUIProjection, error) {
	seen := make(map[string]struct{}, len(downloads))
	normalized := make([]DesktopUIDownload, 0, len(downloads))
	for _, d := range downloads {
		if err := validateDesktopUIDownload(d, true); err != nil {
			return DesktopUIProjection{}, err
		}
		if _, exists := seen[d.ID]; exists {
			return DesktopUIProjection{}, fmt.Errorf("duplicate desktop execution observation %s", d.ID)
		}
		seen[d.ID] = struct{}{}
		normalized = append(normalized, d)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.projection.Revision++
	s.projection.Downloads = normalized
	return s.projectionLocked(), nil
}

func (s *desktopUIState) applyCommand(cmd DesktopUICommand, returnedID string) (DesktopUIProjection, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := s.projectionLocked()
	find := func(id string) int {
		for i := range next.Downloads {
			if next.Downloads[i].ID == id {
				return i
			}
		}
		return -1
	}
	changed := false
	switch cmd.Action {
	case "add":
		if cmd.Download != nil && returnedID != "" && find(returnedID) < 0 {
			added := *cmd.Download
			added.ID = returnedID
			added.BackendTaskID = cmd.BackendTaskID
			added.State = "Queued"
			next.Downloads = append([]DesktopUIDownload{added}, next.Downloads...)
			changed = true
		}
	case "pause":
		if i := find(cmd.DownloadID); i >= 0 {
			next.Downloads[i].State = "Paused"
			changed = true
		}
	case "resume", "retry":
		if i := find(cmd.DownloadID); i >= 0 {
			next.Downloads[i].State = "Queued"
			next.Downloads[i].ErrorMessage = nil
			changed = true
		}
	case "cancel":
		if i := find(cmd.DownloadID); i >= 0 {
			next.Downloads[i].State = "Cancelled"
			changed = true
		}
	}
	if changed {
		next.Revision++
		s.projection = next
	}
	return s.projectionLocked(), changed
}

func (s *desktopUIState) projectionLocked() DesktopUIProjection {
	out := s.projection
	out.Downloads = append([]DesktopUIDownload(nil), s.projection.Downloads...)
	return out
}

func emitDesktopUIProjection(inv *Invocation, projection DesktopUIProjection) error {
	payload, err := json.Marshal(projection)
	if err != nil {
		return err
	}
	_, err = inv.Emit(event.Telemetry, "desktop.ui.projection", payload, "desktop.ui.downloads")
	return err
}

func emitDesktopUIResult(inv *Invocation, requestID, action string, ok bool, extra map[string]any) error {
	result := map[string]any{"client_request_id": requestID, "action": action, "ok": ok}
	for k, v := range extra {
		result[k] = v
	}
	payload, _ := json.Marshal(result)
	_, err := inv.Emit(event.Durable, "desktop.ui.command_result", payload, "")
	return err
}

func (e *Engine) desktopUISnapshotHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	return emitDesktopUIProjection(inv, e.desktopUI.snapshot())
}

func (e *Engine) desktopUIObservationHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var observation desktopUIObservation
	if err := json.Unmarshal(env.Payload, &observation); err != nil {
		return fmt.Errorf("decode desktop execution observation: %w", err)
	}
	projection, err := e.desktopUI.observe(observation.Downloads)
	if err != nil {
		return err
	}
	if err := e.desktopAuthority.saveUI(projection); err != nil {
		return err
	}
	return emitDesktopUIProjection(inv, projection)
}

func (e *Engine) desktopUICommandHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var cmd DesktopUICommand
	if err := json.Unmarshal(env.Payload, &cmd); err != nil {
		return fmt.Errorf("decode desktop UI command: %w", err)
	}
	if err := validateDesktopUICommand(cmd); err != nil {
		return err
	}
	canonicalID := cmd.DownloadID
	if cmd.Action == "add" {
		canonicalID = "desktop-" + string(env.OperationID)
		cmd.DownloadID = canonicalID
		if cmd.Download != nil {
			cmd.Download.ID = canonicalID
			cmd.Backend = cmd.Download.Backend
		}
	} else if current := e.desktopUI.snapshot(); canonicalID != "" {
		for _, item := range current.Downloads {
			if item.ID == canonicalID {
				cmd.Backend = item.Backend
				cmd.BackendTaskID = item.BackendTaskID
				break
			}
		}
	}
	request, _ := json.Marshal(cmd)
	reply, err := inv.PlatformRequest(ctx, platform.DesktopDownloadCommand, platform.Refs{OperationID: env.OperationID}, request)
	result := map[string]any{}
	if err != nil {
		result["error_code"] = "platform_unavailable"
		result["message"] = err.Error()
		_ = emitDesktopUIResult(inv, cmd.ClientRequestID, cmd.Action, false, result)
		return err
	}
	if len(reply.Payload) > 0 {
		var extra map[string]any
		if json.Unmarshal(reply.Payload, &extra) == nil {
			for k, v := range extra {
				result[k] = v
			}
		}
	}
	if reply.ErrorCode != "" {
		result["error_code"] = reply.ErrorCode
	}
	if !reply.OK {
		_ = emitDesktopUIResult(inv, cmd.ClientRequestID, cmd.Action, false, result)
		return fmt.Errorf("desktop download command rejected: %s", reply.ErrorCode)
	}
	returnedID := canonicalID
	if cmd.Action == "add" {
		// Go owns canonical download identity. The platform may return a backend
		// task reference, but it cannot replace the canonical download ID.
		result["download_id"] = canonicalID
	} else if platformID, ok := result["download_id"].(string); ok && platformID != "" {
		returnedID = platformID
	}
	if taskID, ok := result["backend_task_id"].(string); ok {
		cmd.BackendTaskID = taskID
	}
	if projection, changed := e.desktopUI.applyCommand(cmd, returnedID); changed {
		if err := e.desktopAuthority.saveUI(projection); err != nil {
			return err
		}
		if err := emitDesktopUIProjection(inv, projection); err != nil {
			return err
		}
	}
	return emitDesktopUIResult(inv, cmd.ClientRequestID, cmd.Action, true, result)
}

type desktopMediaExecuteInput struct {
	ClientRequestID string `json:"client_request_id"`
	CaptureID       string `json:"capture_id"`
	OutputPath      string `json:"output_path"`
	DurationMS      *int64 `json:"duration_ms,omitempty"`
	MaximumBytes    *int64 `json:"maximum_bytes,omitempty"`
}

func emitDesktopMediaProjection(inv *Invocation, projection androidMediaProjection) error {
	payload, err := json.Marshal(projection)
	if err != nil {
		return err
	}
	_, err = inv.Emit(event.Telemetry, "desktop.media.projection", payload, "desktop.media.graph")
	return err
}

func emitDesktopMediaResult(inv *Invocation, requestID, action string, ok bool, extra map[string]any) error {
	result := map[string]any{"client_request_id": requestID, "action": action, "ok": ok}
	for k, v := range extra {
		result[k] = v
	}
	payload, _ := json.Marshal(result)
	_, err := inv.Emit(event.Durable, "desktop.media.command_result", payload, "")
	return err
}

func (e *Engine) desktopMediaSnapshotHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	return emitDesktopMediaProjection(inv, e.androidMedia.projection())
}

func (e *Engine) desktopMediaCaptureHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var input androidMediaCaptureInput
	if err := json.Unmarshal(env.Payload, &input); err != nil {
		return err
	}
	projection, err := e.androidMedia.upsert(input)
	if err != nil {
		_ = emitDesktopMediaResult(inv, input.ClientRequestID, "capture", false, map[string]any{"error_code": "invalid_capture"})
		return err
	}
	var capture androidMediaHostCapture
	if json.Unmarshal(input.CaptureRecord, &capture) == nil {
		selection := defaultDesktopMediaSelection(input.Variants)
		if selection.VideoVariantID != "" || selection.AudioVariantID != "" || selection.SubtitleVariantID != "" {
			if selected, selectErr := e.androidMedia.selectCapture(capture.ID, selection); selectErr == nil {
				projection = selected
			}
		}
	}
	if err := emitDesktopMediaProjection(inv, projection); err != nil {
		return err
	}
	return emitDesktopMediaResult(inv, input.ClientRequestID, "capture", true, map[string]any{"status": "graph_updated"})
}

func defaultDesktopMediaSelection(rawVariants []json.RawMessage) androidMediaSelection {
	selection := androidMediaSelection{}
	for _, raw := range rawVariants {
		var variant androidMediaHostVariant
		if json.Unmarshal(raw, &variant) != nil {
			continue
		}
		switch strings.ToLower(variant.Kind) {
		case "primary", "video":
			if selection.VideoVariantID == "" || strings.EqualFold(variant.Kind, "primary") {
				selection.VideoVariantID = variant.ID
			}
		case "audio":
			if selection.AudioVariantID == "" {
				selection.AudioVariantID = variant.ID
			}
		case "subtitle":
			if selection.SubtitleVariantID == "" {
				selection.SubtitleVariantID = variant.ID
			}
		}
	}
	return selection
}

func (e *Engine) desktopMediaSelectHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var input androidMediaSelectInput
	if err := json.Unmarshal(env.Payload, &input); err != nil {
		return err
	}
	if strings.TrimSpace(input.ClientRequestID) == "" || strings.TrimSpace(input.CaptureID) == "" {
		return errors.New("desktop media selection request is incomplete")
	}
	projection, err := e.androidMedia.selectCapture(input.CaptureID, input.Selection)
	if err != nil {
		_ = emitDesktopMediaResult(inv, input.ClientRequestID, "select", false, map[string]any{"error_code": "invalid_selection"})
		return err
	}
	if err := emitDesktopMediaProjection(inv, projection); err != nil {
		return err
	}
	return emitDesktopMediaResult(inv, input.ClientRequestID, "select", true, map[string]any{"status": "selected"})
}

func (e *Engine) desktopMediaExecuteHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var input desktopMediaExecuteInput
	if err := json.Unmarshal(env.Payload, &input); err != nil {
		return err
	}
	if strings.TrimSpace(input.ClientRequestID) == "" || strings.TrimSpace(input.CaptureID) == "" || strings.TrimSpace(input.OutputPath) == "" {
		return errors.New("desktop media execute request is incomplete")
	}
	request, err := e.androidMedia.executionRequest(input.CaptureID)
	if err != nil {
		_ = emitDesktopMediaResult(inv, input.ClientRequestID, "execute", false, map[string]any{"error_code": "media_not_executable", "message": err.Error()})
		return err
	}
	request["request_kind"] = "desktop_media_execute"
	request["output_path"] = input.OutputPath
	if input.DurationMS != nil && *input.DurationMS > 0 {
		request["requested_duration_ms"] = *input.DurationMS
	}
	if input.MaximumBytes != nil && *input.MaximumBytes > 0 {
		request["maximum_bytes"] = *input.MaximumBytes
	}
	requestPayload, _ := json.Marshal(request)
	reply, err := inv.PlatformRequest(ctx, platform.ExternalMediaTool, platform.Refs{OperationID: env.OperationID}, requestPayload)
	result := map[string]any{"capture_id": input.CaptureID}
	if err != nil {
		result["error_code"] = "platform_unavailable"
		result["message"] = err.Error()
		_ = emitDesktopMediaResult(inv, input.ClientRequestID, "execute", false, result)
		return err
	}
	if len(reply.Payload) > 0 {
		var extra map[string]any
		if json.Unmarshal(reply.Payload, &extra) == nil {
			for k, v := range extra {
				result[k] = v
			}
		}
	}
	if reply.ErrorCode != "" {
		result["error_code"] = reply.ErrorCode
	}
	if !reply.OK {
		_ = emitDesktopMediaResult(inv, input.ClientRequestID, "execute", false, result)
		return fmt.Errorf("desktop media tool rejected: %s", reply.ErrorCode)
	}
	result["status"] = "started_via_go"
	return emitDesktopMediaResult(inv, input.ClientRequestID, "execute", true, result)
}
