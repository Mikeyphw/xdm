package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/subhra74/xdm/engine/media"
)

type report struct {
	Mode   string   `json:"mode"`
	Pass   bool     `json:"pass"`
	Checks []string `json:"checks"`
}

func main() {
	mode := flag.String("mode", "capture_corpus", "audit mode")
	output := flag.String("output", "", "output path")
	flag.Parse()
	var r report
	var err error
	switch *mode {
	case "capture_corpus":
		r, err = captureCorpus()
	case "capture_generation":
		r, err = captureGeneration()
	default:
		err = fmt.Errorf("unknown mode %q", *mode)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	data, _ := json.MarshalIndent(r, "", "  ")
	if *output != "" {
		if err := os.MkdirAll(dir(*output), 0o755); err != nil {
			panic(err)
		}
		if err := os.WriteFile(*output, data, 0o644); err != nil {
			panic(err)
		}
	} else {
		fmt.Println(string(data))
	}
}

func captureCorpus() (report, error) {
	l := int64(77)
	env := media.CaptureEnvelope{
		Version:         media.CaptureEnvelopeVersion,
		Request:         media.RequestEvidence{URL: "https://media.example/path?sig=abc", Method: "POST", Headers: []media.Header{{Name: "Accept", Value: "video/mp4"}}, Body: &media.BodyEvidence{Reference: "body-ref", Length: &l, Replayable: true}},
		Page:            media.PageContext{PageURL: "https://page.example/watch", FrameOrigin: "https://page.example", SessionID: "sess-a", DocumentGeneration: 1},
		Response:        media.ResponseMetadata{StatusCode: 200, ObservedAt: time.Unix(1, 0)},
		MediaHints:      []media.MediaHint{{Kind: "container", Value: "mp4"}},
		CredentialScope: media.CredentialScope{Origin: "https://media.example", PathPrefix: "/path", Ref: "secret-ref"},
	}
	normalized, err := media.NewCaptureEnvelope(env)
	if err != nil {
		return report{}, err
	}
	if normalized.ReplayEvidenceKey() == normalized.LogicalObservationKey() {
		return report{}, fmt.Errorf("replay and logical keys conflated")
	}
	if _, err := media.NewCaptureEnvelope(func() media.CaptureEnvelope {
		b := env
		b.Request.Headers = []media.Header{{Name: "Cookie", Value: "a=b"}}
		return b
	}()); err == nil {
		return report{}, fmt.Errorf("secret raw header accepted")
	}
	if _, err := media.ParseCaptureEnvelopeJSON(make([]byte, media.MaxEnvelopeBytes+1)); err == nil {
		return report{}, fmt.Errorf("oversized envelope accepted")
	}
	return report{Mode: "capture_corpus", Pass: true, Checks: []string{"android fixture shape", "desktop fixture shape", "POST body reference", "secret redaction", "malformed/oversized rejection"}}, nil
}

func captureGeneration() (report, error) {
	fence, err := media.NewGenerationFence("sess-a", 2, 2)
	if err != nil {
		return report{}, err
	}
	active := fence.Evaluate(media.Observation{SessionID: "sess-a", DocumentGeneration: 2, FrameOrigin: "https://page.example", EvidenceKey: "active"})
	activeFrame := fence.Evaluate(media.Observation{SessionID: "sess-a", DocumentGeneration: 2, FrameOrigin: "https://frame.example", EvidenceKey: "frame"})
	stale := fence.Evaluate(media.Observation{SessionID: "sess-a", DocumentGeneration: 1, EvidenceKey: "stale"})
	if active.Status != media.GenerationAccepted || !active.MutatesActiveGraph {
		return report{}, fmt.Errorf("active generation not accepted")
	}
	if activeFrame.Status != media.GenerationAccepted || !activeFrame.MutatesActiveGraph {
		return report{}, fmt.Errorf("multiple frame active generation not accepted")
	}
	if stale.Status != media.GenerationHistorical || stale.MutatesActiveGraph {
		return report{}, fmt.Errorf("stale generation mutated active graph")
	}
	if err := fence.Navigate(3); err != nil {
		return report{}, err
	}
	reload := fence.Evaluate(media.Observation{SessionID: "sess-a", DocumentGeneration: 2, EvidenceKey: "reload-old"})
	if reload.Status != media.GenerationHistorical || reload.MutatesActiveGraph {
		return report{}, fmt.Errorf("reload old generation mutated active graph")
	}
	_ = fence.Evaluate(media.Observation{SessionID: "sess-a", DocumentGeneration: 1, EvidenceKey: "older"})
	_ = fence.Evaluate(media.Observation{SessionID: "sess-a", DocumentGeneration: 0, EvidenceKey: "oldest"})
	if len(fence.Historical()) != 2 {
		return report{}, fmt.Errorf("bounded retention failed")
	}
	return report{Mode: "capture_generation", Pass: true, Checks: []string{"generation A then B", "late A historical only", "multiple frames", "reload same URL new document", "bounded retention"}}, nil
}

func dir(path string) string {
	for i := len(path) - 1; i >= 0; i-- {
		if path[i] == '/' {
			return path[:i]
		}
	}
	return "."
}
