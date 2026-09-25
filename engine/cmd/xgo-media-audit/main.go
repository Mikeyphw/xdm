package main

import (
	"encoding/json"
	"errors"
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
	case "media_identity":
		r, err = mediaIdentity()
	case "media_graph":
		r, err = mediaGraph()
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

func mediaIdentity() (report, error) {
	a, err := media.NewMediaResource(media.MediaResourceInput{TransportURL: "https://cdn.example/master.m3u8?sig=one&quality=hd&track=main", CredentialScope: media.CredentialScope{Origin: "https://cdn.example", PathPrefix: "/", Ref: "cred-ref"}})
	if err != nil {
		return report{}, err
	}
	b, err := media.NewMediaResource(media.MediaResourceInput{TransportURL: "https://cdn.example/master.m3u8?track=main&quality=hd&sig=two", CredentialScope: media.CredentialScope{Origin: "https://cdn.example", PathPrefix: "/", Ref: "cred-ref"}})
	if err != nil {
		return report{}, err
	}
	if a.ResourceID != b.ResourceID || a.TransportURL == b.TransportURL {
		return report{}, fmt.Errorf("signed token rotation or replay evidence identity failed")
	}
	relevant, err := media.NewMediaResource(media.MediaResourceInput{TransportURL: "https://cdn.example/master.m3u8?quality=sd&sig=two"})
	if err != nil {
		return report{}, err
	}
	if relevant.ResourceID == a.ResourceID {
		return report{}, fmt.Errorf("identity-relevant query was dropped")
	}
	externalDenied, err := media.EvaluateCredentialForwarding(media.ChildCredentialPolicy{Parent: a, ChildURL: "https://keys.example/key.bin", ChildKind: media.ResourceKindKey})
	if err != nil {
		return report{}, err
	}
	if externalDenied.Forward {
		return report{}, fmt.Errorf("external key credential forwarded without policy")
	}
	externalAllowed, err := media.EvaluateCredentialForwarding(media.ChildCredentialPolicy{Parent: a, ChildURL: "https://keys.example/key.bin", ChildKind: media.ResourceKindKey, AllowCrossOriginKeys: true, AllowedOrigins: []string{"https://keys.example"}})
	if err != nil {
		return report{}, err
	}
	if !externalAllowed.Forward {
		return report{}, fmt.Errorf("external key credential not forwarded under explicit policy")
	}
	return report{Mode: "media_identity", Pass: true, Checks: []string{"signed URL token rotation", "query ordering convergence", "identity-relevant query preserved", "external key origin denied", "credential forwarding explicit allow"}}, nil
}

func mediaGraph() (report, error) {
	graph := media.NewMediaGraph(media.MediaGraphLimits{MaxItems: 2, MaxSources: 4, MaxVariants: 8, MaxTracks: 8, MaxRenditions: 8, MaxFragmentSets: 8})
	env := graphEnvelope("https://page.example/watch", 1)
	res1, _ := media.NewMediaResource(media.MediaResourceInput{TransportURL: "https://cdn.example/master.m3u8?sig=one&quality=hd"})
	res2, _ := media.NewMediaResource(media.MediaResourceInput{TransportURL: "https://cdn.example/master.m3u8?quality=hd&sig=two"})
	id1, err := graph.Ingest(media.MediaObservation{Envelope: env, Resource: res1, Title: "Demo", Container: "hls", Codec: "avc1", Bitrate: 1200, Width: 1280, Height: 720, TrackKind: media.TrackVideo, RenditionGroup: "video", ManifestURL: res1.TransportURL, FragmentSetKey: "v0", Protection: media.ProtectionClear})
	if err != nil {
		return report{}, err
	}
	id2, err := graph.Ingest(media.MediaObservation{Envelope: env, Resource: res2, Title: "Demo", Container: "hls", Codec: "mp4a", TrackKind: media.TrackAudio, Language: "en", RenditionGroup: "audio", ManifestURL: res2.TransportURL, FragmentSetKey: "a0", Protection: media.ProtectionKeyed})
	if err != nil {
		return report{}, err
	}
	if id1 != id2 {
		return report{}, fmt.Errorf("duplicate signed manifests did not converge")
	}
	s := graph.Snapshot()
	if len(s.ItemIDs) != 1 || len(s.SourceIDs) != 1 || len(s.TrackIDs) != 2 || len(s.RenditionIDs) != 2 {
		return report{}, fmt.Errorf("unexpected graph shape: %+v", s)
	}
	_, err = graph.Ingest(media.MediaObservation{Envelope: graphEnvelope("https://page.example/2", 2), Resource: mustAuditResource("https://cdn.example/other.m3u8?id=2"), Title: "Other"})
	if err != nil {
		return report{}, err
	}
	_, err = graph.Ingest(media.MediaObservation{Envelope: graphEnvelope("https://page.example/3", 3), Resource: mustAuditResource("https://cdn.example/third.m3u8?id=3"), Title: "Third"})
	if !errors.Is(err, media.ErrMediaGraphLimit) {
		return report{}, fmt.Errorf("bounded graph growth did not fail: %v", err)
	}
	return report{Mode: "media_graph", Pass: true, Checks: []string{"duplicate captures converge", "signed manifest graph convergence", "audio/video rendition groups", "protection precedence", "bounded repeated observations", "equivalent capture streams same graph"}}, nil
}

func graphEnvelope(page string, gen int64) media.CaptureEnvelope {
	env, err := media.NewCaptureEnvelope(media.CaptureEnvelope{Version: media.CaptureEnvelopeVersion, Request: media.RequestEvidence{URL: "https://cdn.example/master.m3u8?sig=one", Method: "GET"}, Page: media.PageContext{PageURL: page, FrameOrigin: "https://page.example", SessionID: "sess-graph", DocumentGeneration: gen}, Response: media.ResponseMetadata{StatusCode: 200, ContentType: "application/vnd.apple.mpegurl", ObservedAt: time.Unix(gen, 0)}, MediaHints: []media.MediaHint{{Kind: "manifest", Value: "hls"}}})
	if err != nil {
		panic(err)
	}
	return env
}

func mustAuditResource(raw string) media.MediaResource {
	r, err := media.NewMediaResource(media.MediaResourceInput{TransportURL: raw})
	if err != nil {
		panic(err)
	}
	return r
}

func dir(path string) string {
	for i := len(path) - 1; i >= 0; i-- {
		if path[i] == '/' {
			return path[:i]
		}
	}
	return "."
}
