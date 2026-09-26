package runtime

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/runtime/event"
	"github.com/subhra74/xdm/engine/runtime/platform"
)

func nextAndroidMediaFrame(t *testing.T, e *Engine, ctx context.Context, kind string) event.Frame {
	t.Helper()
	for {
		frame, err := e.NextFrame(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if frame.Kind == kind {
			return frame
		}
	}
}

func TestAndroidMediaCaptureSelectionAndFfmpegExecutionRoundTrip(t *testing.T) {
	e := New(Config{EventBuffer: 64, PlatformBuffer: 8})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = e.Shutdown(context.Background()) }()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	capture := `{
      "client_request_id":"media-cap-1",
      "capture_record":{"id":"cap-1","source_url":"https://media.test/master.m3u8","page_url":"https://media.test/watch","title":"Fixture","status":"MetadataReady","kind":"HlsPlaylist","mime_type":"application/vnd.apple.mpegurl","container":"mpegts","codecs":"avc1","duration_ms":120000,"thumbnail_url":null,"thumbnail_provenance":"Unknown","file_name":"fixture.mp4","variant_count":1,"download_id":null,"created_at_epoch_ms":1,"updated_at_epoch_ms":2,"selected_variant_id":"var-1","selected_variant_url":"https://cdn.media.test/v1.m3u8","manifest_expires_at_epoch_ms":null,"last_resolved_at_epoch_ms":2,"resolution_status":"Resolved","manifest_role":"HlsMaster","manifest_is_live":false,"manifest_protected":false,"manifest_protection_scheme":null,"logical_media_id":"logical-1","canonical_media_url":"https://media.test/master.m3u8","observation_count":1,"segment_count":0,"protection_kind":"None","native_capability":"NativeCandidate","logical_confidence":100,"row_revision":2},
      "variants":[{"id":"var-1","capture_id":"cap-1","url":"https://cdn.media.test/v1.m3u8","kind":"Video","mime_type":"application/vnd.apple.mpegurl","width":1920,"height":1080,"bitrate_bits_per_second":4000000,"codecs":"avc1","language":null,"position":0,"display_label":"1080p","expires_at_epoch_ms":null,"group_id":null,"audio_group_id":null,"subtitle_group_id":null,"is_default":true,"is_autoselect":true,"is_forced":false,"channels":null,"in_stream_id":null,"requires_network_fetch":true,"manifest_timeline_group_id":null,"manifest_execution_url_template":null,"manifest_initialization_url":null,"manifest_role_label":null}],
      "envelope":{"version":1,"request":{"url":"https://media.test/master.m3u8","method":"GET","headers":[{"name":"Accept","value":"application/vnd.apple.mpegurl"}]},"page":{"page_url":"https://media.test/watch","session_id":"browser-session-1","document_generation":2},"credential_scope":{"origin":"https://media.test","ref":"capture:cap-1"}}
    }`
	if err := e.Submit(context.Background(), cmd(t, "00000000000000000000000000000081", "00000000000000000000000000000081", AndroidMediaCaptureKind, capture)); err != nil {
		t.Fatal(err)
	}
	projection := nextAndroidMediaFrame(t, e, ctx, "android.media.projection")
	var projected androidMediaProjection
	if err := json.Unmarshal(projection.Payload, &projected); err != nil {
		t.Fatal(err)
	}
	if len(projected.Captures) != 1 || len(projected.Variants) != 1 {
		t.Fatalf("projection=%s", projection.Payload)
	}
	result := nextAndroidMediaFrame(t, e, ctx, "android.media.command_result")
	var captureResult map[string]any
	if err := json.Unmarshal(result.Payload, &captureResult); err != nil {
		t.Fatal(err)
	}
	if captureResult["ok"] != true || captureResult["status"] != "graph_updated" {
		t.Fatalf("capture result=%v", captureResult)
	}

	selection := `{"client_request_id":"media-select-1","capture_id":"cap-1","selection":{"video_variant_id":"var-1"}}`
	if err := e.Submit(context.Background(), cmd(t, "00000000000000000000000000000082", "00000000000000000000000000000082", AndroidMediaSelectKind, selection)); err != nil {
		t.Fatal(err)
	}
	projection = nextAndroidMediaFrame(t, e, ctx, "android.media.projection")
	if err := json.Unmarshal(projection.Payload, &projected); err != nil {
		t.Fatal(err)
	}
	if len(projected.Selections) != 1 || projected.Selections[0].VideoVariantID != "var-1" {
		t.Fatalf("selection projection=%s", projection.Payload)
	}
	_ = nextAndroidMediaFrame(t, e, ctx, "android.media.command_result")

	execute := `{"client_request_id":"media-exec-1","capture_id":"cap-1"}`
	if err := e.Submit(context.Background(), cmd(t, "00000000000000000000000000000083", "00000000000000000000000000000083", AndroidMediaExecuteKind, execute)); err != nil {
		t.Fatal(err)
	}
	requestFrame := nextAndroidMediaFrame(t, e, ctx, "platform.request")
	var req platform.Request
	if err := json.Unmarshal(requestFrame.Payload, &req); err != nil {
		t.Fatal(err)
	}
	if req.Kind != platform.ExternalMediaTool {
		t.Fatalf("platform kind=%s", req.Kind)
	}
	var tool map[string]any
	if err := json.Unmarshal(req.Payload, &tool); err != nil {
		t.Fatal(err)
	}
	if tool["tool"] != "ffmpeg" || tool["operation"] != "finalize_adaptive" || tool["protocol"] != "hls" || tool["capture_id"] != "cap-1" || tool["variant_id"] != "var-1" {
		t.Fatalf("tool request=%v", tool)
	}
	if _, leaked := tool["source_url"]; leaked {
		t.Fatalf("typed tool request leaked transport URL: %v", tool)
	}
	if err := e.PlatformReply(platform.Reply{RequestID: req.ID, Session: req.Session, OK: true, Payload: json.RawMessage(`{"output_path":"/data/user/0/app/files/xgo-media/cap-1/fixture.mp4","exit_code":0}`)}); err != nil {
		t.Fatal(err)
	}
	executionResult := nextAndroidMediaFrame(t, e, ctx, "android.media.command_result")
	var got map[string]any
	if err := json.Unmarshal(executionResult.Payload, &got); err != nil {
		t.Fatal(err)
	}
	if got["ok"] != true || got["status"] != "started_via_go" || got["capture_id"] != "cap-1" {
		t.Fatalf("execution result=%v", got)
	}
}
