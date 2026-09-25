package media

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/publication"
)

func TestFFmpegPlanGoldens(t *testing.T) {
	cases := []struct {
		name        string
		input       FFmpegPlanInput
		wantMode    FFmpegPlanMode
		wantStreams int
	}{
		{"remux", baseFFmpegPlanInput(FFmpegModeRemux), FFmpegModeRemux, 2},
		{"av_mux", muxFFmpegPlanInput(), FFmpegModeMux, 2},
		{"transcode", func() FFmpegPlanInput {
			in := baseFFmpegPlanInput(FFmpegModeTranscode)
			in.VideoCodec = "libx264"
			in.AudioCodec = "aac"
			return in
		}(), FFmpegModeTranscode, 2},
		{"subtitle", subtitleFFmpegPlanInput(), FFmpegModeMux, 3},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			plan, err := NewFFmpegPlan(tc.input)
			if err != nil {
				t.Fatalf("plan: %v", err)
			}
			if plan.Mode != tc.wantMode {
				t.Fatalf("mode = %s", plan.Mode)
			}
			if len(plan.Mappings) != tc.wantStreams {
				t.Fatalf("mappings=%d", len(plan.Mappings))
			}
			if len(plan.Tool.Args) == 0 || strings.Contains(strings.Join(plan.Tool.Args, " "), "ffmpeg ") {
				t.Fatalf("expected argv-only tool request: %#v", plan.Tool.Args)
			}
			data, err := SerializeFFmpegPlan(plan)
			if err != nil {
				t.Fatalf("serialize: %v", err)
			}
			restarted, err := ParseFFmpegPlan(data)
			if err != nil {
				t.Fatalf("parse: %v", err)
			}
			if restarted.PlanID != plan.PlanID {
				t.Fatalf("plan identity changed")
			}
		})
	}
}

func TestFFmpegPlanInvalidCombinations(t *testing.T) {
	input := baseFFmpegPlanInput(FFmpegModeTranscode)
	if _, err := NewFFmpegPlan(input); !errors.Is(err, ErrInvalidFFmpegPlan) {
		t.Fatalf("expected missing codec error, got %v", err)
	}
	input = baseFFmpegPlanInput(FFmpegModeRemux)
	input.Output.RequiredStreams = []StreamKind{StreamVideo, "bogus"}
	plan, err := NewFFmpegPlan(input)
	if err != nil {
		t.Fatalf("plan setup: %v", err)
	}
	if _, err := VerifyFFProbe(plan, FFProbeReport{Container: "mp4", Size: 1, Streams: []FFProbeStream{{Kind: StreamVideo}, {Kind: StreamAudio}}}); !errors.Is(err, ErrMediaVerificationFailed) {
		t.Fatalf("expected verification failure for impossible required stream, got %v", err)
	}
	input = baseFFmpegPlanInput(FFmpegModeRemux)
	input.Inputs = nil
	if _, err := NewFFmpegPlan(input); !errors.Is(err, ErrInvalidFFmpegPlan) {
		t.Fatalf("expected empty input error, got %v", err)
	}
}

func TestFFmpegPlanHostileFilenameRemainsLiteralArg(t *testing.T) {
	input := baseFFmpegPlanInput(FFmpegModeRemux)
	input.Inputs[0].Path = "video; touch /tmp/pwned"
	plan, err := NewFFmpegPlan(input)
	if err != nil {
		t.Fatalf("hostile but literal path should be legal argv: %v", err)
	}
	found := false
	for _, arg := range plan.Tool.Args {
		if arg == "video; touch /tmp/pwned" {
			found = true
		}
	}
	if !found {
		t.Fatalf("literal hostile filename not preserved as a single argv element: %#v", plan.Tool.Args)
	}
	input.Inputs[0].Path = "bad\nname"
	if _, err := NewFFmpegPlan(input); !errors.Is(err, ErrUnsafeToolRequest) {
		t.Fatalf("expected control character rejection, got %v", err)
	}
}

func TestFFmpegExecutionLifecycle(t *testing.T) {
	plan, err := NewFFmpegPlan(baseFFmpegPlanInput(FFmpegModeRemux))
	if err != nil {
		t.Fatal(err)
	}
	plan, err = StartMediaTool(plan, "host-job-1", time.Unix(1, 0))
	if err != nil {
		t.Fatal(err)
	}
	plan, err = ApplyMediaToolEvent(plan, MediaToolEvent{Kind: MediaToolEventProgress, ToolJobID: "host-job-1", ProgressPermil: 450, Diagnostics: strings.Repeat("p", MaxToolDiagnosticBytes+100)})
	if err != nil {
		t.Fatal(err)
	}
	if plan.State.ProgressPermil != 450 || len(plan.State.Diagnostics) != MaxToolDiagnosticBytes {
		t.Fatalf("progress/diagnostics not bounded: %+v", plan.State)
	}
	plan, err = ApplyMediaToolEvent(plan, MediaToolEvent{Kind: MediaToolEventSuccess, ToolJobID: "host-job-1"})
	if err != nil {
		t.Fatal(err)
	}
	verified, result, err := VerifyMediaToolResult(plan, goodProbe())
	if err != nil || !result.Pass {
		t.Fatalf("verify: %+v %v", result, err)
	}
	if verified.State.Status != MediaToolVerified {
		t.Fatalf("status=%s", verified.State.Status)
	}
}

func TestFFmpegExecutionFailures(t *testing.T) {
	for _, ev := range []MediaToolEvent{{Kind: MediaToolEventCancel}, {Kind: MediaToolEventNonzero, ExitCode: 2}, {Kind: MediaToolEventTimeout}, {Kind: MediaToolEventCrash}} {
		plan, err := NewFFmpegPlan(baseFFmpegPlanInput(FFmpegModeRemux))
		if err != nil {
			t.Fatal(err)
		}
		plan, err = StartMediaTool(plan, "host-job", time.Unix(1, 0))
		if err != nil {
			t.Fatal(err)
		}
		next, err := ApplyMediaToolEvent(plan, ev)
		if ev.Kind == MediaToolEventCancel {
			if err != nil || next.State.Status != MediaToolCanceled {
				t.Fatalf("cancel got %+v %v", next.State, err)
			}
			continue
		}
		if !errors.Is(err, ErrMediaToolFailed) {
			t.Fatalf("expected tool failure for %s, got %v", ev.Kind, err)
		}
		recovered, err := RecoverMediaToolJob(next, MediaToolFailedStatus)
		if err != nil {
			t.Fatal(err)
		}
		if recovered.State.Status != MediaToolStartRequested {
			t.Fatalf("recovered=%s", recovered.State.Status)
		}
	}
}

func TestFFprobeMalformedAndMissingStream(t *testing.T) {
	plan, err := NewFFmpegPlan(baseFFmpegPlanInput(FFmpegModeRemux))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := VerifyFFProbe(plan, FFProbeReport{Container: "", Size: -1}); !errors.Is(err, ErrInvalidProbeReport) {
		t.Fatalf("expected malformed probe, got %v", err)
	}
	if _, err := VerifyFFProbe(plan, FFProbeReport{Container: "mp4", Size: 1, Streams: []FFProbeStream{{Kind: StreamVideo}}}); !errors.Is(err, ErrMediaVerificationFailed) {
		t.Fatalf("expected missing audio stream, got %v", err)
	}
}

func TestFFmpegPublicationHandoffAndCrashRecovery(t *testing.T) {
	plan, err := NewFFmpegPlan(baseFFmpegPlanInput(FFmpegModeRemux))
	if err != nil {
		t.Fatal(err)
	}
	plan, err = StartMediaTool(plan, "host-job", time.Unix(1, 0))
	if err != nil {
		t.Fatal(err)
	}
	plan, err = ApplyMediaToolEvent(plan, MediaToolEvent{Kind: MediaToolEventSuccess, ToolJobID: "host-job"})
	if err != nil {
		t.Fatal(err)
	}
	verified, handoff, err := CreatePublicationHandoff(plan, goodProbe())
	if err != nil {
		t.Fatalf("handoff: %v", err)
	}
	if verified.State.Status != MediaToolPublicationRequested {
		t.Fatalf("status=%s", verified.State.Status)
	}
	if handoff.Artifact.Size() != 42 || handoff.CommitRequest.StagingIdentity == "" {
		t.Fatalf("bad handoff: %+v", handoff.CommitRequest)
	}
	recovered, err := RecoverMediaToolJob(verified, MediaToolRunning)
	if err != nil {
		t.Fatal(err)
	}
	if recovered.State.Status != MediaToolPublicationRequested {
		t.Fatalf("publication crash recovery regressed: %s", recovered.State.Status)
	}
	pubID, _ := identity.ParsePublicationID(verified.PublicationID)
	committed, err := MarkMediaPublicationCommitted(recovered, publication.Receipt{PublicationID: pubID, ReceiptID: "receipt", Location: "/downloads/out.mp4"})
	if err != nil {
		t.Fatal(err)
	}
	if committed.State.Status != MediaToolPublished {
		t.Fatalf("published status=%s", committed.State.Status)
	}
}

func baseFFmpegPlanInput(mode FFmpegPlanMode) FFmpegPlanInput {
	return FFmpegPlanInput{
		Mode:                    mode,
		Inputs:                  []MediaInputArtifact{{Role: MediaInputMuxed, ArtifactRef: "fragment-track-video-audio", Path: "/tmp/in.ts", Container: "mpegts", Duration: time.Minute, Streams: []MediaStream{{Kind: StreamVideo, Index: 0, Codec: "h264"}, {Kind: StreamAudio, Index: 1, Codec: "aac", Language: "en"}}}},
		Output:                  OutputExpectation{Path: "/tmp/out.mp4", Container: "mp4", ExpectedDuration: time.Minute, RequiredStreams: []StreamKind{StreamVideo, StreamAudio}},
		DownloadID:              "dl_00000000000000000000000000000001",
		ArtifactGeneration:      1,
		SourceAttemptGeneration: 1,
		PublicationID:           "pub_00000000000000000000000000000002",
		PublicationStagingID:    "stage:/tmp/out.mp4",
	}
}

func muxFFmpegPlanInput() FFmpegPlanInput {
	return FFmpegPlanInput{
		Mode: FFmpegModeMux,
		Inputs: []MediaInputArtifact{
			{Role: MediaInputVideo, ArtifactRef: "video-track", Path: "/tmp/video.mp4", Streams: []MediaStream{{Kind: StreamVideo, Index: 0, Codec: "h264"}}},
			{Role: MediaInputAudio, ArtifactRef: "audio-track", Path: "/tmp/audio.m4a", Streams: []MediaStream{{Kind: StreamAudio, Index: 0, Codec: "aac", Language: "en"}}},
		},
		Output:     OutputExpectation{Path: "/tmp/muxed.mp4", Container: "mp4", ExpectedDuration: time.Minute, RequiredStreams: []StreamKind{StreamVideo, StreamAudio}},
		DownloadID: "dl_00000000000000000000000000000001", ArtifactGeneration: 1, SourceAttemptGeneration: 1, PublicationID: "pub_00000000000000000000000000000002",
	}
}

func subtitleFFmpegPlanInput() FFmpegPlanInput {
	in := muxFFmpegPlanInput()
	in.Inputs = append(in.Inputs, MediaInputArtifact{Role: MediaInputSubtitle, ArtifactRef: "subtitle-track", Path: "/tmp/subs.vtt", Streams: []MediaStream{{Kind: StreamSubtitle, Index: 0, Codec: "webvtt", Language: "en"}}})
	in.Output.RequiredStreams = []StreamKind{StreamVideo, StreamAudio, StreamSubtitle}
	return in
}

func goodProbe() FFProbeReport {
	return FFProbeReport{Container: "mp4", Duration: time.Minute, Size: 42, Streams: []FFProbeStream{{Kind: StreamVideo, Codec: "h264"}, {Kind: StreamAudio, Codec: "aac", Language: "en"}}}
}
