package media

import (
	"errors"
	"testing"
	"time"
)

func TestMediaResourceIdentitySeparatesTransportSignedTokensAndUnknownQuery(t *testing.T) {
	a, err := NewMediaResource(MediaResourceInput{TransportURL: "https://cdn.example/video.m3u8?quality=hd&sig=aaa&b=2&a=1", CredentialScope: CredentialScope{Ref: "cred-ref", PathPrefix: "/video"}})
	if err != nil {
		t.Fatal(err)
	}
	b, err := NewMediaResource(MediaResourceInput{TransportURL: "https://cdn.example/video.m3u8?a=1&sig=bbb&b=2&quality=hd", CredentialScope: CredentialScope{Ref: "cred-ref", PathPrefix: "/video"}})
	if err != nil {
		t.Fatal(err)
	}
	if a.TransportURL == b.TransportURL {
		t.Fatalf("transport URLs should preserve replay evidence differences")
	}
	if a.CanonicalURL != b.CanonicalURL || a.ResourceID != b.ResourceID {
		t.Fatalf("signed-token rotation/query ordering should converge: %#v %#v", a, b)
	}
	identityRelevant, err := NewMediaResource(MediaResourceInput{TransportURL: "https://cdn.example/video.m3u8?quality=sd&sig=ccc", CredentialScope: CredentialScope{Ref: "cred-ref"}})
	if err != nil {
		t.Fatal(err)
	}
	if identityRelevant.ResourceID == a.ResourceID {
		t.Fatalf("identity-relevant unknown query was dropped blindly")
	}
	withPolicy, err := NewMediaResource(MediaResourceInput{TransportURL: "https://cdn.example/video.m3u8?quality=hd&sig=aaa", SignedURLPolicy: SignedURLTokensAreIdentity})
	if err != nil {
		t.Fatal(err)
	}
	withPolicyRotated, err := NewMediaResource(MediaResourceInput{TransportURL: "https://cdn.example/video.m3u8?quality=hd&sig=bbb", SignedURLPolicy: SignedURLTokensAreIdentity})
	if err != nil {
		t.Fatal(err)
	}
	if withPolicy.ResourceID == withPolicyRotated.ResourceID {
		t.Fatalf("signed token should be identity-relevant under explicit policy")
	}
}

func TestCredentialForwardingPolicyForChildResources(t *testing.T) {
	parent, err := NewMediaResource(MediaResourceInput{TransportURL: "https://media.example/path/master.m3u8?sig=1", CredentialScope: CredentialScope{Origin: "https://media.example", PathPrefix: "/path", Ref: "cred-ref"}})
	if err != nil {
		t.Fatal(err)
	}
	allowed, err := EvaluateCredentialForwarding(ChildCredentialPolicy{Parent: parent, ChildURL: "https://media.example/path/seg-1.ts", ChildKind: ResourceKindSegment})
	if err != nil {
		t.Fatal(err)
	}
	if !allowed.Forward || allowed.CredentialRef != "cred-ref" {
		t.Fatalf("same-origin child should inherit credential ref: %#v", allowed)
	}
	deniedPath, err := EvaluateCredentialForwarding(ChildCredentialPolicy{Parent: parent, ChildURL: "https://media.example/other/seg-1.ts", ChildKind: ResourceKindSegment})
	if err != nil {
		t.Fatal(err)
	}
	if deniedPath.Forward || deniedPath.Reason != "path_denied" {
		t.Fatalf("path denial expected: %#v", deniedPath)
	}
	deniedOrigin, err := EvaluateCredentialForwarding(ChildCredentialPolicy{Parent: parent, ChildURL: "https://keys.example/key.bin", ChildKind: ResourceKindKey})
	if err != nil {
		t.Fatal(err)
	}
	if deniedOrigin.Forward || deniedOrigin.Reason != "origin_denied" {
		t.Fatalf("external key should be denied without explicit policy: %#v", deniedOrigin)
	}
	allowedOrigin, err := EvaluateCredentialForwarding(ChildCredentialPolicy{Parent: parent, ChildURL: "https://keys.example/key.bin", ChildKind: ResourceKindKey, AllowCrossOriginKeys: true, AllowedOrigins: []string{"https://keys.example"}})
	if err != nil {
		t.Fatal(err)
	}
	if !allowedOrigin.Forward {
		t.Fatalf("external key should forward only under policy: %#v", allowedOrigin)
	}
}

func TestMediaGraphConvergesDuplicateCapturesAndBoundsGrowth(t *testing.T) {
	env := validGraphEnvelope("https://page.example/watch", 1)
	first, err := NewMediaResource(MediaResourceInput{TransportURL: "https://cdn.example/master.m3u8?sig=one&quality=hd", CredentialScope: CredentialScope{Origin: "https://cdn.example", Ref: "cred-ref"}})
	if err != nil {
		t.Fatal(err)
	}
	rotated, err := NewMediaResource(MediaResourceInput{TransportURL: "https://cdn.example/master.m3u8?quality=hd&sig=two", CredentialScope: CredentialScope{Origin: "https://cdn.example", Ref: "cred-ref"}})
	if err != nil {
		t.Fatal(err)
	}
	if first.ResourceID != rotated.ResourceID {
		t.Fatalf("same manifest under signed URL rotation should have one source")
	}
	g := NewMediaGraph(MediaGraphLimits{MaxItems: 2, MaxSources: 4, MaxVariants: 8, MaxTracks: 8, MaxRenditions: 8, MaxFragmentSets: 8})
	id1, err := g.Ingest(MediaObservation{Envelope: env, Resource: first, Kind: ResourceKindManifest, Title: "Demo", Container: "hls", Codec: "avc1", Bitrate: 1200, Width: 1280, Height: 720, TrackKind: TrackVideo, RenditionGroup: "v", ManifestURL: first.TransportURL, FragmentSetKey: "v0", Protection: ProtectionClear})
	if err != nil {
		t.Fatal(err)
	}
	id2, err := g.Ingest(MediaObservation{Envelope: env, Resource: rotated, Kind: ResourceKindManifest, Title: "Demo", Container: "hls", Codec: "avc1", Bitrate: 1200, Width: 1280, Height: 720, TrackKind: TrackAudio, Language: "en", RenditionGroup: "audio", ManifestURL: rotated.TransportURL, FragmentSetKey: "a0", Protection: ProtectionKeyed})
	if err != nil {
		t.Fatal(err)
	}
	if id1 != id2 {
		t.Fatalf("duplicate captures did not converge into one item: %s %s", id1, id2)
	}
	if len(g.Items) != 1 || len(g.Sources) != 1 || len(g.Tracks) != 2 || len(g.Renditions) != 2 || len(g.FragmentSets) != 2 {
		t.Fatalf("unexpected graph shape: %+v", g.Snapshot())
	}
	if got := g.Items[id1].Protection; got != ProtectionKeyed {
		t.Fatalf("protection merge precedence failed: %s", got)
	}
	_, err = g.Ingest(MediaObservation{Envelope: validGraphEnvelope("https://page.example/2", 2), Resource: mustResource(t, "https://cdn.example/other.m3u8?id=2"), Title: "Other"})
	if err != nil {
		t.Fatal(err)
	}
	_, err = g.Ingest(MediaObservation{Envelope: validGraphEnvelope("https://page.example/3", 3), Resource: mustResource(t, "https://cdn.example/third.m3u8?id=3"), Title: "Third"})
	if !errors.Is(err, ErrMediaGraphLimit) {
		t.Fatalf("expected bounded graph growth error, got %v", err)
	}
}

func validGraphEnvelope(page string, gen int64) CaptureEnvelope {
	env, err := NewCaptureEnvelope(CaptureEnvelope{Version: CaptureEnvelopeVersion, Request: RequestEvidence{URL: "https://cdn.example/master.m3u8?sig=one", Method: "GET"}, Page: PageContext{PageURL: page, FrameOrigin: "https://page.example", SessionID: "sess-graph", DocumentGeneration: gen}, Response: ResponseMetadata{StatusCode: 200, ContentType: "application/vnd.apple.mpegurl", ObservedAt: time.Unix(gen, 0)}, MediaHints: []MediaHint{{Kind: "manifest", Value: "hls"}}})
	if err != nil {
		panic(err)
	}
	return env
}

func mustResource(t *testing.T, raw string) MediaResource {
	t.Helper()
	r, err := NewMediaResource(MediaResourceInput{TransportURL: raw})
	if err != nil {
		t.Fatal(err)
	}
	return r
}
