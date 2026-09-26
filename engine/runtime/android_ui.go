package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"

	"github.com/subhra74/xdm/engine/runtime/command"
	"github.com/subhra74/xdm/engine/runtime/event"
	"github.com/subhra74/xdm/engine/runtime/platform"
)

const (
	AndroidUISyncKind    = "android.ui.sync_downloads"
	AndroidUICommandKind = "android.ui.download_command"
)

type AndroidUIDownload struct {
	ID                          string  `json:"id"`
	FileName                    string  `json:"file_name"`
	SourceURL                   string  `json:"source_url"`
	DestinationURI              string  `json:"destination_uri"`
	State                       string  `json:"state"`
	Backend                     string  `json:"backend"`
	BytesReceived               int64   `json:"bytes_received"`
	TotalBytes                  *int64  `json:"total_bytes,omitempty"`
	SpeedBytesPerSecond         int64   `json:"speed_bytes_per_second"`
	QueueID                     *string `json:"queue_id,omitempty"`
	Priority                    int     `json:"priority"`
	CreatedAtEpochMS            int64   `json:"created_at_epoch_ms"`
	UpdatedAtEpochMS            int64   `json:"updated_at_epoch_ms"`
	ErrorMessage                *string `json:"error_message,omitempty"`
	UserLabel                   *string `json:"user_label,omitempty"`
	ConflictPolicy              string  `json:"conflict_policy"`
	MIMEType                    *string `json:"mime_type,omitempty"`
	RequestedBackend            string  `json:"requested_backend"`
	BackendSelectionReason      string  `json:"backend_selection_reason"`
	BackendSelectionExplanation string  `json:"backend_selection_explanation"`
	AllowBackendFallback        bool    `json:"allow_backend_fallback"`
	Archived                    bool    `json:"archived"`
	AttemptGeneration           int64   `json:"attempt_generation"`
	CompletedArtifactURI        *string `json:"completed_artifact_uri,omitempty"`
	CompletedArtifactGeneration *int64  `json:"completed_artifact_generation,omitempty"`
	CompletedArtifactBytes      *int64  `json:"completed_artifact_bytes,omitempty"`
	ObservedAttemptGeneration   int64   `json:"observed_attempt_generation"`
	RowRevision                 int64   `json:"row_revision"`
}

type AndroidUIProjection struct {
	Revision  int64               `json:"revision"`
	Downloads []AndroidUIDownload `json:"downloads"`
}

type AndroidUICommand struct {
	ClientRequestID string             `json:"client_request_id"`
	Action          string             `json:"action"`
	DownloadID      string             `json:"download_id,omitempty"`
	Download        *AndroidUIDownload `json:"download,omitempty"`
	DuplicateAction string             `json:"duplicate_action,omitempty"`
	Checksum        json.RawMessage    `json:"checksum,omitempty"`
	PolicyOverride  bool               `json:"policy_override,omitempty"`
}

type androidUIState struct {
	mu         sync.RWMutex
	projection AndroidUIProjection
}

func newAndroidUIState() *androidUIState {
	return &androidUIState{projection: AndroidUIProjection{Downloads: []AndroidUIDownload{}}}
}

func (s *androidUIState) replace(next AndroidUIProjection) (AndroidUIProjection, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if next.Revision <= s.projection.Revision {
		return s.projection, false
	}
	next.Downloads = append([]AndroidUIDownload(nil), next.Downloads...)
	s.projection = next
	return next, true
}

func (s *androidUIState) seed(projection AndroidUIProjection) error {
	if err := validateAndroidUIProjection(projection); err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	projection.Downloads = append([]AndroidUIDownload(nil), projection.Downloads...)
	s.projection = projection
	return nil
}

func (s *androidUIState) snapshot() AndroidUIProjection {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := s.projection
	out.Downloads = append([]AndroidUIDownload(nil), s.projection.Downloads...)
	return out
}

func (s *androidUIState) applyCommand(cmd AndroidUICommand, status string) (AndroidUIProjection, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := s.projection
	next.Downloads = append([]AndroidUIDownload(nil), s.projection.Downloads...)
	changed := false
	find := func(id string) int {
		for i := range next.Downloads {
			if next.Downloads[i].ID == id {
				return i
			}
		}
		return -1
	}
	switch cmd.Action {
	case "add":
		if status == "created" && cmd.Download != nil && find(cmd.Download.ID) < 0 {
			next.Downloads = append(next.Downloads, *cmd.Download)
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
			changed = true
		}
	case "cancel":
		if i := find(cmd.DownloadID); i >= 0 {
			next.Downloads[i].State = "Cancelled"
			changed = true
		}
	case "delete":
		if i := find(cmd.DownloadID); i >= 0 {
			next.Downloads = append(next.Downloads[:i], next.Downloads[i+1:]...)
			changed = true
		}
	case "pause_all":
		for i := range next.Downloads {
			switch next.Downloads[i].State {
			case "Completed", "Failed", "Cancelled", "RecoveryRequired":
				continue
			}
			next.Downloads[i].State = "Paused"
			changed = true
		}
	case "resume_all":
		for i := range next.Downloads {
			switch next.Downloads[i].State {
			case "Paused", "WaitingForNetwork", "WaitingForPower":
				next.Downloads[i].State = "Queued"
				changed = true
			}
		}
	}
	if changed {
		next.Revision++
		s.projection = next
	}
	return next, changed
}

func validateAndroidUIProjection(p AndroidUIProjection) error {
	seen := make(map[string]struct{}, len(p.Downloads))
	for _, d := range p.Downloads {
		if d.ID == "" || d.FileName == "" || d.State == "" || d.Backend == "" {
			return errors.New("android UI projection contains incomplete download")
		}
		if _, ok := seen[d.ID]; ok {
			return fmt.Errorf("duplicate projected download %s", d.ID)
		}
		seen[d.ID] = struct{}{}
	}
	return nil
}

func validateAndroidUICommand(c AndroidUICommand) error {
	if c.ClientRequestID == "" {
		return errors.New("android UI command request id is blank")
	}
	switch c.Action {
	case "add":
		if c.Download == nil || c.Download.ID == "" {
			return errors.New("add command requires download")
		}
	case "pause", "resume", "cancel", "retry", "delete":
		if c.DownloadID == "" {
			return fmt.Errorf("%s command requires download id", c.Action)
		}
	case "pause_all", "resume_all":
	default:
		return fmt.Errorf("unsupported android UI action %q", c.Action)
	}
	return nil
}

func (e *Engine) androidUISyncHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	if e.androidAuthority.imported() {
		return errors.New("legacy Android Room download mirror is disabled after authoritative import")
	}
	var projection AndroidUIProjection
	if err := json.Unmarshal(env.Payload, &projection); err != nil {
		return fmt.Errorf("decode android UI projection: %w", err)
	}
	if err := validateAndroidUIProjection(projection); err != nil {
		return err
	}
	var accepted bool
	projection, accepted = e.androidUI.replace(projection)
	if !accepted {
		return nil
	}
	payload, _ := json.Marshal(projection)
	_, err := inv.Emit(event.Telemetry, "android.ui.projection", payload, "android.ui.downloads")
	return err
}

func (e *Engine) androidUICommandHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var cmd AndroidUICommand
	if err := json.Unmarshal(env.Payload, &cmd); err != nil {
		return fmt.Errorf("decode android UI command: %w", err)
	}
	if err := validateAndroidUICommand(cmd); err != nil {
		return err
	}
	request, _ := json.Marshal(cmd)
	reply, err := inv.PlatformRequest(ctx, platform.AndroidDownloadCommand, platform.Refs{OperationID: env.OperationID}, request)
	result := map[string]any{"client_request_id": cmd.ClientRequestID, "action": cmd.Action, "ok": err == nil && reply.OK}
	if err != nil {
		result["error_code"] = "platform_unavailable"
		result["message"] = err.Error()
	}
	if reply.ErrorCode != "" {
		result["error_code"] = reply.ErrorCode
	}
	status := ""
	if len(reply.Payload) > 0 {
		var extra map[string]any
		if json.Unmarshal(reply.Payload, &extra) == nil {
			for k, v := range extra {
				result[k] = v
			}
			if value, ok := extra["status"].(string); ok {
				status = value
			}
		}
	}
	if err == nil && reply.OK {
		if projection, changed := e.androidUI.applyCommand(cmd, status); changed {
			if persistErr := e.androidAuthority.saveUI(projection); persistErr != nil {
				return persistErr
			}
			projected, _ := json.Marshal(projection)
			if _, persistErr := inv.Emit(event.Telemetry, "android.ui.projection", projected, "android.ui.downloads"); persistErr != nil {
				return persistErr
			}
		}
	}
	payload, _ := json.Marshal(result)
	_, emitErr := inv.Emit(event.Durable, "android.ui.command_result", payload, "")
	if err != nil {
		return err
	}
	if !reply.OK {
		return fmt.Errorf("android download command rejected: %s", reply.ErrorCode)
	}
	return emitErr
}
