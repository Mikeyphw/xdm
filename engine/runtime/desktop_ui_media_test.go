package runtime

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/subhra74/xdm/engine/runtime/platform"
)

func TestDesktopDownloadProjectionAndCommandsStayGoAuthoritative(t *testing.T) {
	e := New(Config{EventBuffer: 64})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	defer e.Shutdown(context.Background())

	observation := `{"downloads":[{"id":"legacy-desktop-1","file_name":"fixture.bin","source_url":"https://example.test/fixture.bin","destination_path":"/tmp/fixture.bin","downloaded_bytes":128,"total_bytes":1024,"bytes_per_second":64,"state":"Downloading","updated_at_epoch_ms":7,"queue_id":"default","queue_order":0,"connection_count":4,"priority":"Normal","backend":"Native","backend_preference":"Automatic","allow_backend_fallback":true,"archived":false}]}`
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"a1", strings.Repeat("0", 30)+"a1", DesktopUIObservationKind, observation)); err != nil {
		t.Fatal(err)
	}
	projection := nextDesktopFrame(t, e, "desktop.ui.projection")
	if !strings.Contains(string(projection), `"legacy-desktop-1"`) || !strings.Contains(string(projection), `"Downloading"`) {
		t.Fatalf("unexpected projection: %s", projection)
	}

	command := `{"client_request_id":"ui-pause-1","action":"pause","download_id":"legacy-desktop-1"}`
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"a2", strings.Repeat("0", 30)+"a2", DesktopUICommandKind, command)); err != nil {
		t.Fatal(err)
	}
	requestPayload := nextDesktopFrame(t, e, "platform.request")
	got := answerDesktopRequest(t, e, requestPayload, true, map[string]any{"status": "paused", "download_id": "legacy-desktop-1"}, "")
	if got.Kind != platform.DesktopDownloadCommand {
		t.Fatalf("kind=%s", got.Kind)
	}
	projection = nextDesktopFrame(t, e, "desktop.ui.projection")
	if !strings.Contains(string(projection), `"Paused"`) {
		t.Fatalf("pause did not flow through Go projection: %s", projection)
	}
	result := nextDesktopFrame(t, e, "desktop.ui.command_result")
	if !strings.Contains(string(result), `"ui-pause-1"`) || !strings.Contains(string(result), `"ok":true`) {
		t.Fatalf("unexpected result: %s", result)
	}
}

func TestDesktopMediaCaptureGraphAndExecutionRouteThroughGo(t *testing.T) {
	e := New(Config{EventBuffer: 64})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	defer e.Shutdown(context.Background())

	capture := `{
      "client_request_id":"media-capture-1",
      "capture_record":{"id":"cap_00000000000000000000000000000079","source_url":"https://media.test/master.m3u8","page_url":"https://media.test/watch","title":"Fixture","status":"MetadataReady","kind":"HlsPlaylist","mime_type":"application/vnd.apple.mpegurl","container":"mpegts","codecs":"avc1","duration_ms":120000,"file_name":"fixture.mp4","selected_variant_id":"variant-1","manifest_is_live":false,"manifest_protected":false,"row_revision":1},
      "variants":[{"id":"variant-1","capture_id":"cap_00000000000000000000000000000079","url":"https://cdn.media.test/v1.m3u8","kind":"Video","mime_type":"application/vnd.apple.mpegurl","width":1920,"height":1080,"bitrate_bits_per_second":4000000,"codecs":"avc1","is_default":true}],
      "envelope":{"version":1,"request":{"url":"https://media.test/master.m3u8","method":"GET","headers":[{"name":"Accept","value":"application/vnd.apple.mpegurl"}]},"page":{"page_url":"https://media.test/watch","session_id":"desktop-session-1","document_generation":1},"response":{"status_code":200,"content_type":"application/vnd.apple.mpegurl","observed_at":"1970-01-01T00:00:01Z"},"media_hints":[{"kind":"manifest","value":"hls"}],"credential_scope":{"origin":"https://media.test","ref":"desktop:cap-79"}}
    }`
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"a3", strings.Repeat("0", 30)+"a3", DesktopMediaCaptureKind, capture)); err != nil {
		t.Fatal(err)
	}
	projection := nextDesktopFrame(t, e, "desktop.media.projection")
	if !strings.Contains(string(projection), "cap_00000000000000000000000000000079") {
		t.Fatalf("capture missing from Go graph projection: %s", projection)
	}
	_ = nextDesktopFrame(t, e, "desktop.media.command_result")

	execute := `{"client_request_id":"media-exec-1","capture_id":"cap_00000000000000000000000000000079","output_path":"/tmp/fixture.mp4","duration_ms":120000}`
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"a4", strings.Repeat("0", 30)+"a4", DesktopMediaExecuteKind, execute)); err != nil {
		t.Fatal(err)
	}
	requestPayload := nextDesktopFrame(t, e, "platform.request")
	var req platform.Request
	if err := json.Unmarshal(requestPayload, &req); err != nil {
		t.Fatal(err)
	}
	if req.Kind != platform.ExternalMediaTool {
		t.Fatalf("kind=%s", req.Kind)
	}
	if !strings.Contains(string(req.Payload), `"request_kind":"desktop_media_execute"`) || !strings.Contains(string(req.Payload), `"output_path":"/tmp/fixture.mp4"`) {
		t.Fatalf("desktop media request not Go-authored: %s", req.Payload)
	}
	answerDesktopRequest(t, e, requestPayload, true, map[string]any{"output_path": "/tmp/fixture.mp4", "exit_code": 0}, "")
	result := nextDesktopFrame(t, e, "desktop.media.command_result")
	if !strings.Contains(string(result), `"started_via_go"`) || !strings.Contains(string(result), `"ok":true`) {
		t.Fatalf("unexpected execute result: %s", result)
	}
}

func TestDesktopAddResumeAndSnapshotReopenStayGoOwned(t *testing.T) {
	e := New(Config{EventBuffer: 64})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	defer e.Shutdown(context.Background())

	add := `{"client_request_id":"ui-add-1","action":"add","request_ref":"req-local-only","download":{"id":"","file_name":"added.bin","source_url":"https://example.test/added.bin","destination_path":"/tmp/added.bin","downloaded_bytes":0,"total_bytes":2048,"bytes_per_second":0,"state":"Queued","updated_at_epoch_ms":11,"queue_id":"default","queue_order":0,"connection_count":4,"priority":"Normal","backend":"Native","backend_preference":"Automatic","allow_backend_fallback":true,"archived":false}}`
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"b1", strings.Repeat("0", 30)+"b1", DesktopUICommandKind, add)); err != nil {
		t.Fatal(err)
	}
	requestPayload := nextDesktopFrame(t, e, "platform.request")
	answerDesktopRequest(t, e, requestPayload, true, map[string]any{"download_id": "go-created-1", "status": "created"}, "")
	projection := nextDesktopFrame(t, e, "desktop.ui.projection")
	if !strings.Contains(string(projection), `"go-created-1"`) || !strings.Contains(string(projection), `"added.bin"`) {
		t.Fatalf("add was not projected by Go: %s", projection)
	}
	_ = nextDesktopFrame(t, e, "desktop.ui.command_result")

	resume := `{"client_request_id":"ui-resume-1","action":"resume","download_id":"go-created-1"}`
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"b2", strings.Repeat("0", 30)+"b2", DesktopUICommandKind, resume)); err != nil {
		t.Fatal(err)
	}
	requestPayload = nextDesktopFrame(t, e, "platform.request")
	answerDesktopRequest(t, e, requestPayload, true, map[string]any{"status": "resumed"}, "")
	projection = nextDesktopFrame(t, e, "desktop.ui.projection")
	if !strings.Contains(string(projection), `"Queued"`) {
		t.Fatalf("resume was not reflected by Go: %s", projection)
	}
	_ = nextDesktopFrame(t, e, "desktop.ui.command_result")

	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"b3", strings.Repeat("0", 30)+"b3", DesktopUISnapshotKind, `{}`)); err != nil {
		t.Fatal(err)
	}
	reopened := nextDesktopFrame(t, e, "desktop.ui.projection")
	if !strings.Contains(string(reopened), `"go-created-1"`) {
		t.Fatalf("UI reopen snapshot lost Go projection: %s", reopened)
	}
}

func TestDesktopDashExecutionUsesTypedFFmpegRequest(t *testing.T) {
	e := New(Config{EventBuffer: 64})
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	defer e.Shutdown(context.Background())

	capture := `{
      "client_request_id":"media-dash-1",
      "capture_record":{"id":"cap_dash_79","source_url":"https://media.test/manifest.mpd","page_url":"https://media.test/watch","title":"DASH Fixture","kind":"DashManifest","mime_type":"application/dash+xml","file_name":"dash.mp4","manifest_is_live":false,"manifest_protected":false,"row_revision":1},
      "variants":[{"id":"dash-video","capture_id":"cap_dash_79","url":"https://cdn.media.test/video.m4s","kind":"Video","width":1280,"height":720,"bitrate_bits_per_second":2000000,"codecs":"avc1"}],
      "envelope":{"version":1,"request":{"url":"https://media.test/manifest.mpd","method":"GET"},"page":{"page_url":"https://media.test/watch","session_id":"desktop-dash","document_generation":2},"response":{"status_code":200,"content_type":"application/dash+xml","observed_at":"1970-01-01T00:00:02Z"},"media_hints":[{"kind":"manifest","value":"dash"}],"credential_scope":{"origin":"https://media.test","ref":"desktop:dash"}}
    }`
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"b4", strings.Repeat("0", 30)+"b4", DesktopMediaCaptureKind, capture)); err != nil {
		t.Fatal(err)
	}
	projection := nextDesktopFrame(t, e, "desktop.media.projection")
	if !strings.Contains(string(projection), `"video_variant_id":"dash-video"`) {
		t.Fatalf("Go did not choose the default DASH graph variant: %s", projection)
	}
	_ = nextDesktopFrame(t, e, "desktop.media.command_result")

	execute := `{"client_request_id":"media-dash-exec","capture_id":"cap_dash_79","output_path":"/tmp/dash.mp4"}`
	if err := e.Submit(context.Background(), cmd(t, strings.Repeat("0", 30)+"b5", strings.Repeat("0", 30)+"b5", DesktopMediaExecuteKind, execute)); err != nil {
		t.Fatal(err)
	}
	requestPayload := nextDesktopFrame(t, e, "platform.request")
	var req platform.Request
	if err := json.Unmarshal(requestPayload, &req); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(req.Payload), `"protocol":"dash"`) || !strings.Contains(string(req.Payload), `"operation":"finalize_adaptive"`) {
		t.Fatalf("unexpected DASH tool request: %s", req.Payload)
	}
	answerDesktopRequest(t, e, requestPayload, true, map[string]any{"output_path": "/tmp/dash.mp4", "exit_code": 0}, "")
	_ = nextDesktopFrame(t, e, "desktop.media.command_result")
}
