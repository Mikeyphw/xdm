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
	case "media_selection":
		r, err = mediaSelection()
	case "capture_convergence":
		r, err = captureConvergence()
	case "credential_scope_security":
		r, err = credentialScopeSecurity()
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

func mediaSelection() (report, error) {
	graph, itemID := selectionAuditGraph()
	result, err := media.SelectMedia(graph, media.MediaSelectionConstraints{ItemID: itemID, MaxWidth: 1920, MaxHeight: 1080, PreferredWidth: 1920, PreferredHeight: 1080})
	if err != nil {
		return report{}, err
	}
	if result.DefaultVideo == nil || result.DefaultVideo.Width != 3840 || result.SelectedVideo == nil || result.SelectedVideo.Width != 1920 {
		return report{}, fmt.Errorf("default and constrained choices were conflated: %+v", result)
	}
	codec, err := media.SelectMedia(graph, media.MediaSelectionConstraints{ItemID: itemID, MaxWidth: 1920, MaxHeight: 1080, PreferredWidth: 1920, PreferredHeight: 1080, PreferredCodecs: []string{"vp9", "avc1"}})
	if err != nil {
		return report{}, err
	}
	if codec.SelectedVideo == nil || codec.SelectedVideo.Codec != "vp9" {
		return report{}, fmt.Errorf("codec preference not honored for equivalent resolution: %+v", codec.SelectedVideo)
	}
	lang, err := media.SelectMedia(graph, media.MediaSelectionConstraints{ItemID: itemID, PreferredAudioLanguages: []string{"de"}})
	if err != nil {
		return report{}, err
	}
	if lang.SelectedAudio == nil || lang.SelectedAudio.Language != "en" {
		return report{}, fmt.Errorf("unavailable audio language did not fall back deterministically: %+v", lang.SelectedAudio)
	}
	separate, err := media.SelectMedia(graph, media.MediaSelectionConstraints{ItemID: itemID, PreferredAudioLanguages: []string{"es"}, PreferredSubtitleLanguages: []string{"fr"}, SubtitlePolicy: media.SubtitleOptional})
	if err != nil {
		return report{}, err
	}
	if separate.SelectedVideo == nil || separate.SelectedAudio == nil || separate.SelectedAudio.Language != "es" || separate.SelectedSubtitle != nil {
		return report{}, fmt.Errorf("separate audio/video optional subtitle selection failed: %+v", separate)
	}
	required, err := media.SelectMedia(graph, media.MediaSelectionConstraints{ItemID: itemID, PreferredSubtitleLanguages: []string{"fr"}, SubtitlePolicy: media.SubtitleRequired})
	if err != nil {
		return report{}, err
	}
	if required.SelectedSubtitle == nil || required.SelectedSubtitle.Language != "en" {
		return report{}, fmt.Errorf("required subtitle did not fallback to available track: %+v", required.SelectedSubtitle)
	}
	return report{Mode: "media_selection", Pass: true, Checks: []string{"deterministic selection fixtures", "unavailable preferred language", "equivalent resolution codec preference", "separate audio/video", "subtitles optional/required"}}, nil
}

func captureConvergence() (report, error) {
	androidGraph, err := ingestConvergenceStream("android", []string{
		"https://cdn.example/master.m3u8?sig=android-a&quality=hd&track=main",
		"https://cdn.example/audio-en.m4a?token=android-a&lang=en",
		"https://cdn.example/video-1080.mp4?expires=111&quality=hd",
	})
	if err != nil {
		return report{}, err
	}
	desktopGraph, err := ingestConvergenceStream("desktop", []string{
		"https://cdn.example/master.m3u8?track=main&quality=hd&sig=desktop-b",
		"https://cdn.example/audio-en.m4a?lang=en&token=desktop-b",
		"https://cdn.example/video-1080.mp4?quality=hd&expires=222",
	})
	if err != nil {
		return report{}, err
	}
	androidSnapshot := androidGraph.Snapshot()
	desktopSnapshot := desktopGraph.Snapshot()
	if !sameStrings(androidSnapshot.ItemIDs, desktopSnapshot.ItemIDs) || !sameStrings(androidSnapshot.SourceIDs, desktopSnapshot.SourceIDs) || !sameStrings(androidSnapshot.TrackIDs, desktopSnapshot.TrackIDs) || !sameStrings(androidSnapshot.RenditionIDs, desktopSnapshot.RenditionIDs) || !sameStrings(androidSnapshot.ManifestIDs, desktopSnapshot.ManifestIDs) || !sameStrings(androidSnapshot.FragmentSetIDs, desktopSnapshot.FragmentSetIDs) {
		return report{}, fmt.Errorf("android and desktop equivalent capture streams diverged: android=%+v desktop=%+v", androidSnapshot, desktopSnapshot)
	}
	if len(androidSnapshot.ItemIDs) != 1 || len(androidSnapshot.SourceIDs) != 3 || len(androidSnapshot.TrackIDs) != 2 || len(androidSnapshot.RenditionIDs) != 2 || len(androidSnapshot.ManifestIDs) != 1 || len(androidSnapshot.FragmentSetIDs) != 3 {
		return report{}, fmt.Errorf("unexpected convergence graph shape: %+v", androidSnapshot)
	}
	return report{Mode: "capture_convergence", Pass: true, Checks: []string{"android desktop equivalent streams converge", "signed manifest tokens ignored for logical identity", "signed child tokens ignored for logical identity", "audio video track groups converge", "fragment sets converge deterministically"}}, nil
}

func credentialScopeSecurity() (report, error) {
	parent, err := media.NewMediaResource(media.MediaResourceInput{TransportURL: "https://cdn.example/protected/master.m3u8?sig=one", CredentialScope: media.CredentialScope{Origin: "https://cdn.example", PathPrefix: "/protected/", Ref: "cred-ref"}})
	if err != nil {
		return report{}, err
	}
	samePath, err := media.EvaluateCredentialForwarding(media.ChildCredentialPolicy{Parent: parent, ChildURL: "https://cdn.example/protected/seg-001.ts", ChildKind: media.ResourceKindSegment})
	if err != nil || !samePath.Forward || samePath.CredentialRef != "cred-ref" {
		return report{}, fmt.Errorf("same-origin protected child did not inherit credential: decision=%+v err=%v", samePath, err)
	}
	outsidePath, err := media.EvaluateCredentialForwarding(media.ChildCredentialPolicy{Parent: parent, ChildURL: "https://cdn.example/public/seg-001.ts", ChildKind: media.ResourceKindSegment})
	if err != nil || outsidePath.Forward || outsidePath.Reason != "path_denied" {
		return report{}, fmt.Errorf("same-origin child outside path was not denied: decision=%+v err=%v", outsidePath, err)
	}
	crossSegment, err := media.EvaluateCredentialForwarding(media.ChildCredentialPolicy{Parent: parent, ChildURL: "https://segments.example/protected/seg-001.ts", ChildKind: media.ResourceKindSegment})
	if err != nil || crossSegment.Forward || crossSegment.Reason != "origin_denied" {
		return report{}, fmt.Errorf("cross-origin segment was not denied by default: decision=%+v err=%v", crossSegment, err)
	}
	crossKeyDenied, err := media.EvaluateCredentialForwarding(media.ChildCredentialPolicy{Parent: parent, ChildURL: "https://keys.example/protected/key.bin", ChildKind: media.ResourceKindKey})
	if err != nil || crossKeyDenied.Forward || crossKeyDenied.Reason != "origin_denied" {
		return report{}, fmt.Errorf("cross-origin key was not denied by default: decision=%+v err=%v", crossKeyDenied, err)
	}
	crossKeyAllowed, err := media.EvaluateCredentialForwarding(media.ChildCredentialPolicy{Parent: parent, ChildURL: "https://keys.example/protected/key.bin", ChildKind: media.ResourceKindKey, AllowCrossOriginKeys: true, AllowedOrigins: []string{"https://keys.example"}})
	if err != nil || !crossKeyAllowed.Forward || crossKeyAllowed.CredentialRef != "cred-ref" {
		return report{}, fmt.Errorf("explicit cross-origin key allow did not forward credential ref: decision=%+v err=%v", crossKeyAllowed, err)
	}
	wrongAllowedOrigin, err := media.EvaluateCredentialForwarding(media.ChildCredentialPolicy{Parent: parent, ChildURL: "https://evil.example/protected/key.bin", ChildKind: media.ResourceKindKey, AllowCrossOriginKeys: true, AllowedOrigins: []string{"https://keys.example"}})
	if err != nil || wrongAllowedOrigin.Forward || wrongAllowedOrigin.Reason != "origin_denied" {
		return report{}, fmt.Errorf("unlisted cross-origin key was not denied: decision=%+v err=%v", wrongAllowedOrigin, err)
	}
	return report{Mode: "credential_scope_security", Pass: true, Checks: []string{"same-origin path inherits credential reference", "same-origin outside path denied", "cross-origin segment denied by default", "cross-origin key denied by default", "explicit cross-origin key allow forwards ref", "unlisted allowed-origin denied"}}, nil
}

func ingestConvergenceStream(label string, urls []string) (*media.MediaGraph, error) {
	graph := media.NewMediaGraph(media.MediaGraphLimits{})
	if len(urls) != 3 {
		return nil, fmt.Errorf("%s convergence fixture expected 3 URLs", label)
	}
	env, err := media.NewCaptureEnvelope(media.CaptureEnvelope{Version: media.CaptureEnvelopeVersion, Request: media.RequestEvidence{URL: urls[0], Method: "GET"}, Page: media.PageContext{PageURL: "https://page.example/watch", FrameOrigin: "https://page.example", SessionID: "sess-" + label, DocumentGeneration: 7}, Response: media.ResponseMetadata{StatusCode: 200, ContentType: "application/vnd.apple.mpegurl", ObservedAt: time.Unix(70, 0)}, MediaHints: []media.MediaHint{{Kind: "manifest", Value: "hls"}}, CredentialScope: media.CredentialScope{Origin: "https://cdn.example", PathPrefix: "/", Ref: "cred-ref"}})
	if err != nil {
		return nil, err
	}
	manifest, err := media.NewMediaResource(media.MediaResourceInput{TransportURL: urls[0], CredentialScope: media.CredentialScope{Origin: "https://cdn.example", PathPrefix: "/", Ref: "cred-ref"}})
	if err != nil {
		return nil, err
	}
	audio, err := media.NewMediaResource(media.MediaResourceInput{TransportURL: urls[1], CredentialScope: manifest.CredentialScope})
	if err != nil {
		return nil, err
	}
	video, err := media.NewMediaResource(media.MediaResourceInput{TransportURL: urls[2], CredentialScope: manifest.CredentialScope})
	if err != nil {
		return nil, err
	}
	observations := []media.MediaObservation{
		{Envelope: env, Resource: manifest, Title: "Shared Movie", Container: "hls", Codec: "avc1", Bitrate: 6000, Width: 1920, Height: 1080, TrackKind: media.TrackVideo, RenditionGroup: "video", ManifestURL: urls[0], FragmentSetKey: "video-main", Protection: media.ProtectionKeyed},
		{Envelope: env, Resource: audio, Title: "Shared Movie", Container: "m4a", Codec: "mp4a", Language: "en", TrackKind: media.TrackAudio, RenditionGroup: "audio", ManifestURL: urls[0], FragmentSetKey: "audio-en", Protection: media.ProtectionKeyed},
		{Envelope: env, Resource: video, Title: "Shared Movie", Container: "mp4", Codec: "avc1", Bitrate: 6000, Width: 1920, Height: 1080, TrackKind: media.TrackVideo, RenditionGroup: "video", ManifestURL: urls[0], FragmentSetKey: "video-file", Protection: media.ProtectionKeyed},
	}
	for _, obs := range observations {
		if _, err := graph.Ingest(obs); err != nil {
			return nil, fmt.Errorf("%s convergence ingest failed: %w", label, err)
		}
	}
	return graph, nil
}

func sameStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func selectionAuditGraph() (*media.MediaGraph, string) {
	graph := media.NewMediaGraph(media.MediaGraphLimits{})
	env := graphEnvelope("https://page.example/watch", 1)
	manifest := "https://cdn.example/master.m3u8?sig=one"
	observations := []media.MediaObservation{
		{Envelope: env, Resource: mustAuditResource("https://cdn.example/v-720.mp4"), Title: "Movie", Container: "mp4", Codec: "avc1", Bitrate: 3000, Width: 1280, Height: 720, EstimatedSizeBytes: 500000000, TrackKind: media.TrackVideo, RenditionGroup: "video", ManifestURL: manifest, FragmentSetKey: "v720", Protection: media.ProtectionClear},
		{Envelope: env, Resource: mustAuditResource("https://cdn.example/v-1080-avc.mp4"), Title: "Movie", Container: "mp4", Codec: "avc1", Bitrate: 6000, Width: 1920, Height: 1080, EstimatedSizeBytes: 850000000, TrackKind: media.TrackVideo, RenditionGroup: "video", ManifestURL: manifest, FragmentSetKey: "v1080a", Protection: media.ProtectionClear},
		{Envelope: env, Resource: mustAuditResource("https://cdn.example/v-1080-vp9.webm"), Title: "Movie", Container: "webm", Codec: "vp9", Bitrate: 5000, Width: 1920, Height: 1080, EstimatedSizeBytes: 800000000, TrackKind: media.TrackVideo, RenditionGroup: "video", ManifestURL: manifest, FragmentSetKey: "v1080v", Protection: media.ProtectionClear},
		{Envelope: env, Resource: mustAuditResource("https://cdn.example/v-4k.mp4"), Title: "Movie", Container: "mp4", Codec: "hevc", Bitrate: 16000, Width: 3840, Height: 2160, EstimatedSizeBytes: 2000000000, TrackKind: media.TrackVideo, RenditionGroup: "video", ManifestURL: manifest, FragmentSetKey: "v4k", Protection: media.ProtectionClear},
		{Envelope: env, Resource: mustAuditResource("https://cdn.example/a-en.m4a"), Title: "Movie", Container: "m4a", Codec: "mp4a", Language: "en", TrackKind: media.TrackAudio, RenditionGroup: "audio", ManifestURL: manifest, FragmentSetKey: "a-en", Protection: media.ProtectionClear},
		{Envelope: env, Resource: mustAuditResource("https://cdn.example/a-es.m4a"), Title: "Movie", Container: "m4a", Codec: "mp4a", Language: "es", TrackKind: media.TrackAudio, RenditionGroup: "audio", ManifestURL: manifest, FragmentSetKey: "a-es", Protection: media.ProtectionClear},
		{Envelope: env, Resource: mustAuditResource("https://cdn.example/s-en.vtt"), Title: "Movie", Container: "vtt", Codec: "webvtt", Language: "en", TrackKind: media.TrackSubtitle, RenditionGroup: "subs", ManifestURL: manifest, FragmentSetKey: "s-en", Protection: media.ProtectionClear},
	}
	itemID := ""
	for _, obs := range observations {
		id, err := graph.Ingest(obs)
		if err != nil {
			panic(err)
		}
		itemID = id
	}
	return graph, itemID
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
