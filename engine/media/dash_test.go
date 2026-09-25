package media

import (
	"context"
	"fmt"
	"testing"
)

func TestParseDASHMPDBaseURLTimelineAndRMinusOne(t *testing.T) {
	mpd := `<?xml version="1.0"?>
<MPD type="static" mediaPresentationDuration="PT12S">
  <BaseURL>dash/</BaseURL>
  <Period id="p0" duration="PT12S">
    <BaseURL>period/</BaseURL>
    <AdaptationSet id="v" contentType="video" mimeType="video/mp4">
      <Role value="main"/>
      <SegmentTemplate timescale="1" startNumber="5" initialization="init-$RepresentationID$.mp4" media="$RepresentationID$-$Number$-$Time$.m4s">
        <SegmentTimeline>
          <S t="0" d="2" r="1"/>
          <S d="2" r="-1"/>
          <S t="10" d="2"/>
        </SegmentTimeline>
      </SegmentTemplate>
      <Representation id="1080p" bandwidth="4000000" codecs="avc1.4d401f" width="1920" height="1080">
        <BaseURL>video/</BaseURL>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>`
	manifest, err := ParseDASHManifest("https://cdn.example/root/manifest.mpd", mpd)
	if err != nil {
		t.Fatal(err)
	}
	if manifest.Type != DASHStatic || len(manifest.Periods) != 1 || len(manifest.Periods[0].AdaptationSets) != 1 {
		t.Fatalf("unexpected manifest: %+v", manifest)
	}
	frags, err := ExpandDASHFragments(manifest)
	if err != nil {
		t.Fatal(err)
	}
	if len(frags) != 6 {
		t.Fatalf("r=-1/next t expansion failed; got %d %+v", len(frags), frags)
	}
	if frags[0].URL != "https://cdn.example/root/dash/period/video/1080p-5-0.m4s" || frags[0].InitializationURL != "https://cdn.example/root/dash/period/video/init-1080p.mp4" {
		t.Fatalf("nested BaseURL/token resolution failed: %+v", frags[0])
	}
	if frags[4].Time != 8 || frags[5].Time != 10 {
		t.Fatalf("timeline continuity failed: %+v", frags)
	}
	if frags[0].Identity.Protocol != FragmentProtocolDASH || frags[0].Role != "video" {
		t.Fatalf("fragment identity/role wrong: %+v", frags[0])
	}
}

func TestParseDASHSegmentListRolesProtectionAndMalformed(t *testing.T) {
	mpd := `<MPD type="static">
  <Period id="intro">
    <AdaptationSet id="audio" contentType="audio" lang="en" mimeType="audio/mp4">
      <Role value="main"/>
      <SegmentList timescale="1" duration="4">
        <Initialization sourceURL="audio/init.mp4"/>
        <SegmentURL media="audio/a1.m4s" mediaRange="0-9"/>
        <SegmentURL media="audio/a2.m4s"/>
      </SegmentList>
      <Representation id="a-en" bandwidth="96000" codecs="mp4a.40.2"/>
    </AdaptationSet>
    <AdaptationSet id="subs" contentType="text" lang="en" mimeType="text/vtt">
      <SegmentList timescale="1" duration="10"><SegmentURL media="subs/en.vtt"/></SegmentList>
      <Representation id="sub-en"/>
    </AdaptationSet>
  </Period>
  <Period id="drm"><AdaptationSet id="v" contentType="video"><ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/><SegmentList><SegmentURL media="drm/v.m4s"/></SegmentList><Representation id="drm-v"/></AdaptationSet></Period>
</MPD>`
	manifest, err := ParseDASHManifest("https://cdn.example/manifest.mpd", mpd)
	if err != nil {
		t.Fatal(err)
	}
	if len(manifest.Periods) != 2 {
		t.Fatalf("multiple periods lost: %+v", manifest)
	}
	frags, err := ExpandDASHFragments(manifest)
	if err != nil {
		t.Fatal(err)
	}
	if len(frags) != 4 {
		t.Fatalf("segment list expansion wrong: %+v", frags)
	}
	if frags[0].Role != "audio" || frags[0].Range == nil || frags[0].Range.Length != 10 {
		t.Fatalf("audio role/range lost: %+v", frags[0])
	}
	if frags[2].Role != "subtitle" {
		t.Fatalf("subtitle role lost: %+v", frags[2])
	}
	if frags[3].Protection != DASHProtectionWidevine {
		t.Fatalf("unsupported protection taxonomy lost: %+v", frags[3])
	}
	if _, err := ParseDASHManifest("https://cdn.example/bad.mpd", `<MPD><Period>`); err == nil {
		t.Fatalf("malformed XML accepted")
	}
}

type memoryDASHFetcher struct {
	objects map[string][]byte
	counts  map[string]int
	fail    map[string]error
}

func newMemoryDASHFetcher(objects map[string][]byte) *memoryDASHFetcher {
	return &memoryDASHFetcher{objects: objects, counts: map[string]int{}, fail: map[string]error{}}
}
func (f *memoryDASHFetcher) FetchDASH(_ context.Context, req DASHFetchRequest) (DASHFetchResponse, error) {
	f.counts[string(req.Kind)+" "+req.URI]++
	if err := f.fail[req.URI]; err != nil {
		return DASHFetchResponse{}, err
	}
	b, ok := f.objects[req.URI]
	if !ok {
		return DASHFetchResponse{}, fmt.Errorf("missing %s", req.URI)
	}
	if req.Range != nil {
		start := req.Range.Offset
		end := req.Range.Offset + req.Range.Length
		if start < 0 || end > int64(len(b)) {
			return DASHFetchResponse{}, fmt.Errorf("bad range")
		}
		b = b[start:end]
	}
	return DASHFetchResponse{Bytes: append([]byte(nil), b...)}, nil
}

func TestDASHExecutionDynamicRestartAndMultiTrack(t *testing.T) {
	p1 := `<MPD type="dynamic" minimumUpdatePeriod="PT2S">
  <Period id="p0">
    <AdaptationSet id="v" contentType="video"><SegmentTemplate timescale="1" startNumber="1" media="v-$Number$.m4s"><SegmentTimeline><S t="0" d="2" r="1"/></SegmentTimeline></SegmentTemplate><Representation id="v1" bandwidth="1000"/></AdaptationSet>
    <AdaptationSet id="a" contentType="audio"><SegmentTemplate timescale="1" startNumber="1" media="a-$Number$.m4s"><SegmentTimeline><S t="0" d="2" r="1"/></SegmentTimeline></SegmentTemplate><Representation id="a1" bandwidth="96"/></AdaptationSet>
  </Period>
</MPD>`
	p2 := `<MPD type="dynamic" minimumUpdatePeriod="PT2S">
  <Period id="p0">
    <AdaptationSet id="v" contentType="video"><SegmentTemplate timescale="1" startNumber="1" media="v-$Number$.m4s"><SegmentTimeline><S t="0" d="2" r="2"/></SegmentTimeline></SegmentTemplate><Representation id="v1" bandwidth="1000"/></AdaptationSet>
    <AdaptationSet id="a" contentType="audio"><SegmentTemplate timescale="1" startNumber="1" media="a-$Number$.m4s"><SegmentTimeline><S t="0" d="2" r="2"/></SegmentTimeline></SegmentTemplate><Representation id="a1" bandwidth="96"/></AdaptationSet>
  </Period>
  <Period id="p1"><AdaptationSet id="sub" contentType="text"><SegmentList><SegmentURL media="sub-1.vtt"/></SegmentList><Representation id="s1"/></AdaptationSet></Period>
</MPD>`
	objects := map[string][]byte{}
	for _, n := range []string{"v-1.m4s", "v-2.m4s", "v-3.m4s", "a-1.m4s", "a-2.m4s", "a-3.m4s", "sub-1.vtt"} {
		objects["https://cdn.example/dash/"+n] = []byte(n)
	}
	fetcher := newMemoryDASHFetcher(objects)
	ledger, _ := NewFragmentLedger()
	exec, err := NewDASHExecutor(fetcher, ledger)
	if err != nil {
		t.Fatal(err)
	}
	res, err := exec.Execute(context.Background(), DASHExecutionPlan{BaseURL: "https://cdn.example/dash/manifest.mpd", Manifests: []string{p1, p2}, AttemptGeneration: 1})
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if res.Completed != 7 || res.Skipped != 4 || len(res.TrackArtifacts) != 3 {
		t.Fatalf("dynamic/multitrack execution failed: %+v", res)
	}
	second, err := exec.Execute(context.Background(), DASHExecutionPlan{BaseURL: "https://cdn.example/dash/manifest.mpd", Manifests: []string{p2}, AttemptGeneration: 2})
	if err != nil {
		t.Fatalf("restart: %v", err)
	}
	if second.Completed != 0 || second.Skipped != 7 {
		t.Fatalf("restart did not suppress duplicates: %+v", second)
	}
	fetcher.fail["https://cdn.example/dash/v-3.m4s"] = fmt.Errorf("boom")
	fresh, _ := NewDASHExecutor(fetcher, nil)
	failed, err := fresh.Execute(context.Background(), DASHExecutionPlan{BaseURL: "https://cdn.example/dash/manifest.mpd", Manifests: []string{p2}, AttemptGeneration: 1, RepresentationIDs: []string{"v1"}})
	if err != nil {
		t.Fatalf("typed fetch failure should not be fatal: %v", err)
	}
	if failed.FetchFailures != 1 {
		t.Fatalf("missing fragment not typed: %+v", failed)
	}
}
