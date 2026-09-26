package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"

	"github.com/subhra74/xdm/engine/media"
	"github.com/subhra74/xdm/engine/runtime/command"
	"github.com/subhra74/xdm/engine/runtime/event"
	"github.com/subhra74/xdm/engine/runtime/platform"
)

const (
	AndroidMediaSyncKind    = "android.media.sync_legacy"
	AndroidMediaCaptureKind = "android.media.capture"
	AndroidMediaSelectKind  = "android.media.select"
	AndroidMediaExecuteKind = "android.media.execute"
)

type androidMediaHostCapture struct {
	ID                string  `json:"id"`
	SourceURL         string  `json:"source_url"`
	PageURL           *string `json:"page_url,omitempty"`
	Title             string  `json:"title"`
	Kind              string  `json:"kind"`
	MIMEType          *string `json:"mime_type,omitempty"`
	Container         *string `json:"container,omitempty"`
	Codecs            *string `json:"codecs,omitempty"`
	DurationMS        *int64  `json:"duration_ms,omitempty"`
	FileName          string  `json:"file_name"`
	SelectedVariantID *string `json:"selected_variant_id,omitempty"`
	ManifestIsLive    *bool   `json:"manifest_is_live,omitempty"`
	ManifestProtected bool    `json:"manifest_protected"`
	LogicalMediaID    *string `json:"logical_media_id,omitempty"`
	RowRevision       int64   `json:"row_revision"`
}

type androidMediaHostVariant struct {
	ID                   string  `json:"id"`
	CaptureID            string  `json:"capture_id"`
	URL                  string  `json:"url"`
	Kind                 string  `json:"kind"`
	MIMEType             *string `json:"mime_type,omitempty"`
	Width                *int    `json:"width,omitempty"`
	Height               *int    `json:"height,omitempty"`
	BitrateBitsPerSecond *int64  `json:"bitrate_bits_per_second,omitempty"`
	Codecs               *string `json:"codecs,omitempty"`
	Language             *string `json:"language,omitempty"`
	GroupID              *string `json:"group_id,omitempty"`
}

type androidMediaCaptureInput struct {
	ClientRequestID string                `json:"client_request_id,omitempty"`
	CaptureRecord   json.RawMessage       `json:"capture_record"`
	Variants        []json.RawMessage     `json:"variants"`
	Envelope        media.CaptureEnvelope `json:"envelope"`
}

type androidMediaSyncInput struct {
	Revision int64                      `json:"revision"`
	Captures []androidMediaCaptureInput `json:"captures"`
}

type androidMediaSelection struct {
	VideoVariantID    string `json:"video_variant_id,omitempty"`
	AudioVariantID    string `json:"audio_variant_id,omitempty"`
	SubtitleVariantID string `json:"subtitle_variant_id,omitempty"`
}

type androidMediaSelectInput struct {
	ClientRequestID string                `json:"client_request_id"`
	CaptureID       string                `json:"capture_id"`
	Selection       androidMediaSelection `json:"selection"`
}

type androidMediaExecuteInput struct {
	ClientRequestID string `json:"client_request_id"`
	CaptureID       string `json:"capture_id"`
}

type androidMediaCaptureState struct {
	rawCapture      json.RawMessage
	capture         androidMediaHostCapture
	rawVariants     []json.RawMessage
	variants        []androidMediaHostVariant
	envelope        media.CaptureEnvelope
	itemID          string
	graphVariantIDs map[string]string
}

type androidMediaState struct {
	mu             sync.RWMutex
	graph          *media.MediaGraph
	captures       map[string]*androidMediaCaptureState
	selections     map[string]androidMediaSelection
	revision       int64
	mirrorRevision int64
}

func newAndroidMediaState() *androidMediaState {
	return &androidMediaState{
		graph:      media.NewMediaGraph(media.MediaGraphLimits{}),
		captures:   map[string]*androidMediaCaptureState{},
		selections: map[string]androidMediaSelection{},
	}
}

func decodeAndroidMediaCapture(input androidMediaCaptureInput) (*androidMediaCaptureState, error) {
	if len(input.CaptureRecord) == 0 {
		return nil, errors.New("android media capture record is missing")
	}
	var capture androidMediaHostCapture
	if err := json.Unmarshal(input.CaptureRecord, &capture); err != nil {
		return nil, fmt.Errorf("decode android media capture record: %w", err)
	}
	capture.ID = strings.TrimSpace(capture.ID)
	capture.SourceURL = strings.TrimSpace(capture.SourceURL)
	if capture.ID == "" || capture.SourceURL == "" || strings.TrimSpace(capture.FileName) == "" {
		return nil, errors.New("android media capture record is incomplete")
	}
	env, err := media.NewCaptureEnvelope(input.Envelope)
	if err != nil {
		return nil, fmt.Errorf("android media capture envelope: %w", err)
	}
	if env.Request.URL != capture.SourceURL {
		return nil, errors.New("android media capture envelope URL does not match host capture")
	}
	variants := make([]androidMediaHostVariant, 0, len(input.Variants))
	raws := make([]json.RawMessage, 0, len(input.Variants))
	seen := map[string]bool{}
	for _, raw := range input.Variants {
		var variant androidMediaHostVariant
		if err := json.Unmarshal(raw, &variant); err != nil {
			return nil, fmt.Errorf("decode android media variant: %w", err)
		}
		variant.ID = strings.TrimSpace(variant.ID)
		variant.CaptureID = strings.TrimSpace(variant.CaptureID)
		variant.URL = strings.TrimSpace(variant.URL)
		if variant.ID == "" || variant.CaptureID != capture.ID || variant.URL == "" {
			return nil, errors.New("android media variant is incomplete or belongs to another capture")
		}
		if seen[variant.ID] {
			return nil, fmt.Errorf("duplicate android media variant %s", variant.ID)
		}
		seen[variant.ID] = true
		variants = append(variants, variant)
		raws = append(raws, append(json.RawMessage(nil), raw...))
	}
	return &androidMediaCaptureState{
		rawCapture:      append(json.RawMessage(nil), input.CaptureRecord...),
		capture:         capture,
		rawVariants:     raws,
		variants:        variants,
		envelope:        env,
		graphVariantIDs: map[string]string{},
	}, nil
}

func (s *androidMediaState) replaceLegacy(input androidMediaSyncInput) (androidMediaProjection, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if input.Revision <= s.mirrorRevision {
		return s.projectionLocked(), false, nil
	}
	next := make(map[string]*androidMediaCaptureState, len(input.Captures))
	for _, item := range input.Captures {
		state, err := decodeAndroidMediaCapture(item)
		if err != nil {
			return androidMediaProjection{}, false, err
		}
		if _, exists := next[state.capture.ID]; exists {
			return androidMediaProjection{}, false, fmt.Errorf("duplicate android media capture %s", state.capture.ID)
		}
		next[state.capture.ID] = state
	}
	oldCaptures := s.captures
	s.captures = next
	if err := s.rebuildGraphLocked(); err != nil {
		s.captures = oldCaptures
		_ = s.rebuildGraphLocked()
		return androidMediaProjection{}, false, err
	}
	for captureID := range s.selections {
		if s.captures[captureID] == nil {
			delete(s.selections, captureID)
		}
	}
	s.mirrorRevision = input.Revision
	s.revision++
	return s.projectionLocked(), true, nil
}

func (s *androidMediaState) upsert(input androidMediaCaptureInput) (androidMediaProjection, error) {
	next, err := decodeAndroidMediaCapture(input)
	if err != nil {
		return androidMediaProjection{}, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	previous := s.captures[next.capture.ID]
	s.captures[next.capture.ID] = next
	if err := s.rebuildGraphLocked(); err != nil {
		if previous == nil {
			delete(s.captures, next.capture.ID)
		} else {
			s.captures[next.capture.ID] = previous
		}
		_ = s.rebuildGraphLocked()
		return androidMediaProjection{}, err
	}
	s.revision++
	return s.projectionLocked(), nil
}

func (s *androidMediaState) selectCapture(captureID string, selection androidMediaSelection) (androidMediaProjection, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	state := s.captures[captureID]
	if state == nil {
		return androidMediaProjection{}, fmt.Errorf("android media capture %s not found", captureID)
	}
	for _, variantID := range []string{selection.VideoVariantID, selection.AudioVariantID, selection.SubtitleVariantID} {
		if variantID == "" {
			continue
		}
		graphID := state.graphVariantIDs[variantID]
		variant := s.graph.Variants[graphID]
		if graphID == "" || variant == nil || variant.ItemID != state.itemID {
			return androidMediaProjection{}, fmt.Errorf("android media variant %s is not in Go graph for %s", variantID, captureID)
		}
	}
	s.selections[captureID] = selection
	s.revision++
	return s.projectionLocked(), nil
}

func (s *androidMediaState) executionRequest(captureID string) (map[string]any, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	state := s.captures[captureID]
	if state == nil {
		return nil, fmt.Errorf("android media capture %s not found", captureID)
	}
	if state.capture.ManifestProtected {
		return nil, errors.New("protected Android media is diagnostic-only")
	}
	selection := s.selections[captureID]
	selectedID := selection.VideoVariantID
	if selectedID == "" && state.capture.SelectedVariantID != nil {
		selectedID = strings.TrimSpace(*state.capture.SelectedVariantID)
	}
	if selectedID == "" {
		for _, variant := range state.variants {
			if strings.EqualFold(variant.Kind, "Primary") || strings.EqualFold(variant.Kind, "Video") {
				selectedID = variant.ID
				break
			}
		}
	}
	if selectedID != "" {
		graphID := state.graphVariantIDs[selectedID]
		if graphID == "" || s.graph.Variants[graphID] == nil {
			return nil, fmt.Errorf("selected variant %s is not in Go media graph", selectedID)
		}
	}
	protocol := mediaProtocol(state.capture)
	live := state.capture.ManifestIsLive != nil && *state.capture.ManifestIsLive
	operation := "record_stream"
	if !live && (protocol == "hls" || protocol == "dash") {
		operation = "finalize_adaptive"
	}
	payload := map[string]any{
		"request_kind": "android_media_execute",
		"tool":         "ffmpeg",
		"operation":    operation,
		"protocol":     protocol,
		"capture_id":   captureID,
		"output_name":  state.capture.FileName,
	}
	if selectedID != "" {
		payload["variant_id"] = selectedID
	}
	if state.capture.DurationMS != nil && *state.capture.DurationMS > 0 {
		payload["expected_duration_ms"] = *state.capture.DurationMS
	}
	return payload, nil
}

func (s *androidMediaState) rebuildGraphLocked() error {
	graph := media.NewMediaGraph(media.MediaGraphLimits{})
	ids := make([]string, 0, len(s.captures))
	for id := range s.captures {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		state := s.captures[id]
		state.graphVariantIDs = map[string]string{}
		mainResource, err := media.NewMediaResource(media.MediaResourceInput{
			TransportURL:    state.capture.SourceURL,
			CredentialScope: state.envelope.CredentialScope,
			SignedURLPolicy: media.SignedURLTokensAreTransportOnly,
		})
		if err != nil {
			return err
		}
		manifestURL := ""
		kind := media.ResourceKindMedia
		if mediaProtocol(state.capture) == "hls" || mediaProtocol(state.capture) == "dash" {
			kind = media.ResourceKindManifest
			manifestURL = state.capture.SourceURL
		}
		itemID, err := graph.Ingest(media.MediaObservation{
			Envelope:    state.envelope,
			Resource:    mainResource,
			Kind:        kind,
			Title:       state.capture.Title,
			Container:   valueOrEmpty(state.capture.Container),
			Codec:       valueOrEmpty(state.capture.Codecs),
			ManifestURL: manifestURL,
			Protection:  captureProtection(state.capture),
		})
		if err != nil {
			return err
		}
		state.itemID = itemID
		for _, variant := range state.variants {
			resource, err := media.NewMediaResource(media.MediaResourceInput{
				TransportURL:    variant.URL,
				CredentialScope: state.envelope.CredentialScope,
				SignedURLPolicy: media.SignedURLTokensAreTransportOnly,
			})
			if err != nil {
				return err
			}
			obs := media.MediaObservation{
				Envelope:    state.envelope,
				Resource:    resource,
				Kind:        media.ResourceKindMedia,
				Title:       state.capture.Title,
				Container:   valueOrEmpty(state.capture.Container),
				Codec:       valueOrEmpty(variant.Codecs),
				ManifestURL: state.capture.SourceURL,
				Protection:  captureProtection(state.capture),
			}
			if variant.BitrateBitsPerSecond != nil {
				obs.Bitrate = int(*variant.BitrateBitsPerSecond)
			}
			if variant.Width != nil {
				obs.Width = *variant.Width
			}
			if variant.Height != nil {
				obs.Height = *variant.Height
			}
			if variant.Language != nil {
				obs.Language = *variant.Language
			}
			if variant.GroupID != nil {
				obs.RenditionGroup = *variant.GroupID
			}
			switch strings.ToLower(variant.Kind) {
			case "audio":
				obs.TrackKind = media.TrackAudio
			case "subtitle":
				obs.TrackKind = media.TrackSubtitle
			}
			before := graph.Snapshot().VariantIDs
			variantItemID, err := graph.Ingest(obs)
			if err != nil {
				return err
			}
			if variantItemID != itemID {
				return fmt.Errorf("variant %s escaped capture graph item", variant.ID)
			}
			after := graph.Snapshot().VariantIDs
			state.graphVariantIDs[variant.ID] = newlyAddedID(before, after, graph, itemID, resource.ResourceID)
			if state.graphVariantIDs[variant.ID] == "" {
				// Identical host variants may converge to one graph node. Resolve by source.
				for graphID, gv := range graph.Variants {
					if gv.ItemID == itemID && gv.SourceID == resource.ResourceID {
						state.graphVariantIDs[variant.ID] = graphID
						break
					}
				}
			}
		}
	}
	s.graph = graph
	return nil
}

func newlyAddedID(before, after []string, graph *media.MediaGraph, itemID, sourceID string) string {
	old := map[string]bool{}
	for _, id := range before {
		old[id] = true
	}
	for _, id := range after {
		if !old[id] {
			if v := graph.Variants[id]; v != nil && v.ItemID == itemID && v.SourceID == sourceID {
				return id
			}
		}
	}
	return ""
}

func captureProtection(c androidMediaHostCapture) media.ProtectionClass {
	if c.ManifestProtected {
		return media.ProtectionDRM
	}
	return media.ProtectionClear
}

func mediaProtocol(c androidMediaHostCapture) string {
	token := strings.ToLower(c.Kind + " " + valueOrEmpty(c.MIMEType) + " " + c.SourceURL)
	switch {
	case strings.Contains(token, "hlsplaylist"), strings.Contains(token, "mpegurl"), strings.Contains(token, ".m3u8"):
		return "hls"
	case strings.Contains(token, "dashmanifest"), strings.Contains(token, "dash+xml"), strings.Contains(token, ".mpd"):
		return "dash"
	default:
		return "direct"
	}
}

func valueOrEmpty(v *string) string {
	if v == nil {
		return ""
	}
	return *v
}

type androidMediaSelectionProjection struct {
	CaptureID string `json:"capture_id"`
	androidMediaSelection
}

type androidMediaProjection struct {
	Revision   int64                             `json:"revision"`
	Captures   []json.RawMessage                 `json:"captures"`
	Variants   []json.RawMessage                 `json:"variants"`
	Selections []androidMediaSelectionProjection `json:"selections"`
}

func (s *androidMediaState) projectionLocked() androidMediaProjection {
	p := androidMediaProjection{Revision: s.revision, Captures: []json.RawMessage{}, Variants: []json.RawMessage{}, Selections: []androidMediaSelectionProjection{}}
	ids := make([]string, 0, len(s.captures))
	for id := range s.captures {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		state := s.captures[id]
		p.Captures = append(p.Captures, append(json.RawMessage(nil), state.rawCapture...))
		p.Variants = append(p.Variants, state.rawVariants...)
		if selection, ok := s.selections[id]; ok {
			p.Selections = append(p.Selections, androidMediaSelectionProjection{CaptureID: id, androidMediaSelection: selection})
		}
	}
	return p
}

func emitAndroidMediaProjection(inv *Invocation, projection androidMediaProjection) error {
	payload, err := json.Marshal(projection)
	if err != nil {
		return err
	}
	_, err = inv.Emit(event.Telemetry, "android.media.projection", payload, "android.media.graph")
	return err
}

func emitAndroidMediaResult(inv *Invocation, requestID, action string, ok bool, extra map[string]any) error {
	result := map[string]any{"client_request_id": requestID, "action": action, "ok": ok}
	for k, v := range extra {
		result[k] = v
	}
	payload, _ := json.Marshal(result)
	_, err := inv.Emit(event.Durable, "android.media.command_result", payload, "")
	return err
}

func (e *Engine) androidMediaSyncHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var input androidMediaSyncInput
	if err := json.Unmarshal(env.Payload, &input); err != nil {
		return err
	}
	projection, changed, err := e.androidMedia.replaceLegacy(input)
	if err != nil {
		return err
	}
	if !changed {
		return nil
	}
	return emitAndroidMediaProjection(inv, projection)
}

func (e *Engine) androidMediaCaptureHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var input androidMediaCaptureInput
	if err := json.Unmarshal(env.Payload, &input); err != nil {
		return err
	}
	projection, err := e.androidMedia.upsert(input)
	if err != nil {
		_ = emitAndroidMediaResult(inv, input.ClientRequestID, "capture", false, map[string]any{"error_code": "invalid_capture"})
		return err
	}
	if err := emitAndroidMediaProjection(inv, projection); err != nil {
		return err
	}
	return emitAndroidMediaResult(inv, input.ClientRequestID, "capture", true, map[string]any{"status": "graph_updated"})
}

func (e *Engine) androidMediaSelectHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var input androidMediaSelectInput
	if err := json.Unmarshal(env.Payload, &input); err != nil {
		return err
	}
	if strings.TrimSpace(input.ClientRequestID) == "" || strings.TrimSpace(input.CaptureID) == "" {
		return errors.New("android media selection request is incomplete")
	}
	projection, err := e.androidMedia.selectCapture(input.CaptureID, input.Selection)
	if err != nil {
		_ = emitAndroidMediaResult(inv, input.ClientRequestID, "select", false, map[string]any{"error_code": "invalid_selection"})
		return err
	}
	if err := emitAndroidMediaProjection(inv, projection); err != nil {
		return err
	}
	return emitAndroidMediaResult(inv, input.ClientRequestID, "select", true, map[string]any{"status": "selected"})
}

func (e *Engine) androidMediaExecuteHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var input androidMediaExecuteInput
	if err := json.Unmarshal(env.Payload, &input); err != nil {
		return err
	}
	if strings.TrimSpace(input.ClientRequestID) == "" || strings.TrimSpace(input.CaptureID) == "" {
		return errors.New("android media execute request is incomplete")
	}
	request, err := e.androidMedia.executionRequest(input.CaptureID)
	if err != nil {
		_ = emitAndroidMediaResult(inv, input.ClientRequestID, "execute", false, map[string]any{"error_code": "media_not_executable", "message": err.Error()})
		return err
	}
	requestPayload, _ := json.Marshal(request)
	reply, err := inv.PlatformRequest(ctx, platform.ExternalMediaTool, platform.Refs{OperationID: env.OperationID}, requestPayload)
	result := map[string]any{"capture_id": input.CaptureID}
	if err != nil {
		result["error_code"] = "platform_unavailable"
		result["message"] = err.Error()
		_ = emitAndroidMediaResult(inv, input.ClientRequestID, "execute", false, result)
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
		_ = emitAndroidMediaResult(inv, input.ClientRequestID, "execute", false, result)
		return fmt.Errorf("android media tool rejected: %s", reply.ErrorCode)
	}
	result["status"] = "started_via_go"
	return emitAndroidMediaResult(inv, input.ClientRequestID, "execute", true, result)
}
