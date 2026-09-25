package media

import (
	"errors"
	"testing"
	"time"
)

func TestSelectMediaDeterministicResolutionBitrateAndDefaults(t *testing.T) {
	g, itemID := selectionGraph(t)
	result, err := SelectMedia(g, MediaSelectionConstraints{ItemID: itemID, MaxWidth: 1920, MaxHeight: 1080, PreferredWidth: 1920, PreferredHeight: 1080})
	if err != nil {
		t.Fatal(err)
	}
	if result.DefaultVideo == nil || result.SelectedVideo == nil {
		t.Fatalf("missing choices: %+v", result)
	}
	if result.DefaultVideo.Width != 3840 || result.DefaultVideo.Height != 2160 {
		t.Fatalf("default should expose highest available video, got %+v", result.DefaultVideo)
	}
	if result.SelectedVideo.Width != 1920 || result.SelectedVideo.Height != 1080 || result.SelectedVideo.Bitrate != 6000 {
		t.Fatalf("selected video should honor max/preferred constraints, got %+v", result.SelectedVideo)
	}
	if len(result.AvailableChoices) < 7 {
		t.Fatalf("available choices were not separated from default/selection: %+v", result.AvailableChoices)
	}
}

func TestSelectMediaUnavailablePreferredLanguageFallsBackDeterministically(t *testing.T) {
	g, itemID := selectionGraph(t)
	result, err := SelectMedia(g, MediaSelectionConstraints{ItemID: itemID, PreferredAudioLanguages: []string{"de", "it"}})
	if err != nil {
		t.Fatal(err)
	}
	if result.SelectedAudio == nil || result.SelectedAudio.Language != "en" {
		t.Fatalf("expected deterministic audio fallback to en, got %+v", result.SelectedAudio)
	}
	assertDecision(t, result.Rationale, "preferred_language_unavailable")
}

func TestSelectMediaEquivalentResolutionUsesCodecPreference(t *testing.T) {
	g, itemID := selectionGraph(t)
	result, err := SelectMedia(g, MediaSelectionConstraints{ItemID: itemID, MaxWidth: 1920, MaxHeight: 1080, PreferredWidth: 1920, PreferredHeight: 1080, PreferredCodecs: []string{"vp9", "avc1"}})
	if err != nil {
		t.Fatal(err)
	}
	if result.SelectedVideo == nil || result.SelectedVideo.Codec != "vp9" {
		t.Fatalf("equivalent resolution should use codec preference, got %+v", result.SelectedVideo)
	}
}

func TestSelectMediaSeparateAudioVideoAndOptionalSubtitle(t *testing.T) {
	g, itemID := selectionGraph(t)
	result, err := SelectMedia(g, MediaSelectionConstraints{ItemID: itemID, PreferredAudioLanguages: []string{"es"}, PreferredSubtitleLanguages: []string{"fr"}, SubtitlePolicy: SubtitleOptional})
	if err != nil {
		t.Fatal(err)
	}
	if result.SelectedVideo == nil || result.SelectedAudio == nil {
		t.Fatalf("expected separate audio/video selection: %+v", result)
	}
	if result.SelectedAudio.Language != "es" {
		t.Fatalf("expected Spanish audio, got %+v", result.SelectedAudio)
	}
	if result.SelectedSubtitle != nil {
		t.Fatalf("optional unavailable subtitle should not force fallback, got %+v", result.SelectedSubtitle)
	}
	assertDecision(t, result.Rationale, "subtitle_optional_not_selected")
}

func TestSelectMediaRequiredSubtitleFallbackAndFailure(t *testing.T) {
	g, itemID := selectionGraph(t)
	result, err := SelectMedia(g, MediaSelectionConstraints{ItemID: itemID, PreferredSubtitleLanguages: []string{"fr"}, SubtitlePolicy: SubtitleRequired})
	if err != nil {
		t.Fatal(err)
	}
	if result.SelectedSubtitle == nil || result.SelectedSubtitle.Language != "en" {
		t.Fatalf("required subtitle should fallback to deterministic available subtitle, got %+v", result.SelectedSubtitle)
	}
	assertDecision(t, result.Rationale, "preferred_language_unavailable")

	gNoSubs, noSubsItem := selectionGraphWithoutSubtitles(t)
	_, err = SelectMedia(gNoSubs, MediaSelectionConstraints{ItemID: noSubsItem, SubtitlePolicy: SubtitleRequired})
	if !errors.Is(err, ErrMediaSelectionSubtitle) {
		t.Fatalf("expected required subtitle error, got %v", err)
	}
}

func TestSelectMediaEstimatedSizeHardConstraint(t *testing.T) {
	g, itemID := selectionGraph(t)
	result, err := SelectMedia(g, MediaSelectionConstraints{ItemID: itemID, PreferredWidth: 3840, PreferredHeight: 2160, MaxEstimatedSizeBytes: 900_000_000})
	if err != nil {
		t.Fatal(err)
	}
	if result.SelectedVideo == nil || result.SelectedVideo.Width > 1920 {
		t.Fatalf("size constraint should reject 4k, got %+v", result.SelectedVideo)
	}
	assertDecision(t, result.Rationale, "rejected_size")
}

func selectionGraph(t *testing.T) (*MediaGraph, string) {
	t.Helper()
	g := NewMediaGraph(MediaGraphLimits{})
	base := selectionEnvelope("https://page.example/watch", 1)
	manifest := "https://cdn.example/master.m3u8?sig=one"
	variants := []MediaObservation{
		{Envelope: base, Resource: mustTestResource(t, "https://cdn.example/v-720.mp4"), Title: "Movie", Container: "mp4", Codec: "avc1", Bitrate: 3000, Width: 1280, Height: 720, EstimatedSizeBytes: 500_000_000, TrackKind: TrackVideo, RenditionGroup: "video", ManifestURL: manifest, FragmentSetKey: "v720", Protection: ProtectionClear},
		{Envelope: base, Resource: mustTestResource(t, "https://cdn.example/v-1080-avc.mp4"), Title: "Movie", Container: "mp4", Codec: "avc1", Bitrate: 6000, Width: 1920, Height: 1080, EstimatedSizeBytes: 850_000_000, TrackKind: TrackVideo, RenditionGroup: "video", ManifestURL: manifest, FragmentSetKey: "v1080a", Protection: ProtectionClear},
		{Envelope: base, Resource: mustTestResource(t, "https://cdn.example/v-1080-vp9.webm"), Title: "Movie", Container: "webm", Codec: "vp9", Bitrate: 5000, Width: 1920, Height: 1080, EstimatedSizeBytes: 800_000_000, TrackKind: TrackVideo, RenditionGroup: "video", ManifestURL: manifest, FragmentSetKey: "v1080v", Protection: ProtectionClear},
		{Envelope: base, Resource: mustTestResource(t, "https://cdn.example/v-4k.mp4"), Title: "Movie", Container: "mp4", Codec: "hevc", Bitrate: 16000, Width: 3840, Height: 2160, EstimatedSizeBytes: 2_000_000_000, TrackKind: TrackVideo, RenditionGroup: "video", ManifestURL: manifest, FragmentSetKey: "v4k", Protection: ProtectionClear},
		{Envelope: base, Resource: mustTestResource(t, "https://cdn.example/a-en.m4a"), Title: "Movie", Container: "m4a", Codec: "mp4a", Language: "en", TrackKind: TrackAudio, RenditionGroup: "audio", ManifestURL: manifest, FragmentSetKey: "a-en", Protection: ProtectionClear},
		{Envelope: base, Resource: mustTestResource(t, "https://cdn.example/a-es.m4a"), Title: "Movie", Container: "m4a", Codec: "mp4a", Language: "es", TrackKind: TrackAudio, RenditionGroup: "audio", ManifestURL: manifest, FragmentSetKey: "a-es", Protection: ProtectionClear},
		{Envelope: base, Resource: mustTestResource(t, "https://cdn.example/s-en.vtt"), Title: "Movie", Container: "vtt", Codec: "webvtt", Language: "en", TrackKind: TrackSubtitle, RenditionGroup: "subs", ManifestURL: manifest, FragmentSetKey: "s-en", Protection: ProtectionClear},
	}
	var itemID string
	for _, obs := range variants {
		id, err := g.Ingest(obs)
		if err != nil {
			t.Fatal(err)
		}
		itemID = id
	}
	return g, itemID
}

func selectionGraphWithoutSubtitles(t *testing.T) (*MediaGraph, string) {
	t.Helper()
	g, itemID := selectionGraph(t)
	for id, tr := range g.Tracks {
		if tr.Kind == TrackSubtitle {
			delete(g.Tracks, id)
		}
	}
	return g, itemID
}

func selectionEnvelope(page string, gen int64) CaptureEnvelope {
	env, err := NewCaptureEnvelope(CaptureEnvelope{Version: CaptureEnvelopeVersion, Request: RequestEvidence{URL: "https://cdn.example/master.m3u8?sig=one", Method: "GET"}, Page: PageContext{PageURL: page, FrameOrigin: "https://page.example", SessionID: "sess-select", DocumentGeneration: gen}, Response: ResponseMetadata{StatusCode: 200, ContentType: "application/vnd.apple.mpegurl", ObservedAt: time.Unix(gen, 0)}, MediaHints: []MediaHint{{Kind: "manifest", Value: "hls"}}})
	if err != nil {
		panic(err)
	}
	return env
}

func mustTestResource(t *testing.T, raw string) MediaResource {
	t.Helper()
	r, err := NewMediaResource(MediaResourceInput{TransportURL: raw})
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func assertDecision(t *testing.T, decisions []SelectionDecision, code string) {
	t.Helper()
	for _, d := range decisions {
		if d.Code == code {
			return
		}
	}
	t.Fatalf("missing decision %q in %+v", code, decisions)
}
