package media

import "testing"

func TestReconcileHLSTimelineSlidingWindowRetainsCompletedAndAppends(t *testing.T) {
	old, err := ParseHLSPlaylist("https://cdn.example/live/list.m3u8", `#EXTM3U
#EXT-X-MEDIA-SEQUENCE:100
#EXTINF:4,
seg100.ts
#EXTINF:4,
seg101.ts
#EXTINF:4,
seg102.ts
#EXTINF:4,
seg103.ts
`)
	if err != nil {
		t.Fatal(err)
	}
	ledger, _ := NewFragmentLedger()
	for _, seg := range old.Segments {
		ident, err := HLSTimelineIdentity(seg)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := ledger.Commit(FragmentRecord{Identity: ident, AttemptGeneration: 1, ExpectedLength: 4, Hash: "sha256:ok", LocalArtifactRef: ident.TimelineKey + ".ts", DurableBytesWritten: true}); err != nil {
			t.Fatal(err)
		}
	}
	next, err := ParseHLSPlaylist("https://cdn.example/live/list.m3u8", `#EXTM3U
#EXT-X-MEDIA-SEQUENCE:102
#EXTINF:4,
seg102.ts
#EXTINF:4,
seg103.ts
#EXTINF:4,
seg104.ts
#EXTINF:4,
seg105.ts
`)
	if err != nil {
		t.Fatal(err)
	}
	result, err := ReconcileHLSTimeline(ledger.Snapshot(), next)
	if err != nil {
		t.Fatal(err)
	}
	if result.Retained != 2 || result.Appended != 2 || result.Historical != 2 || result.Kind != HLSLive {
		t.Fatalf("unexpected sliding reconcile: %+v", result)
	}
	for _, e := range result.Entries {
		if e.Segment.MediaSequence == 102 && e.Redownload {
			t.Fatalf("known completed fragment would redownload: %+v", e)
		}
	}
}

func TestHLSTimelineIdentityUsesDiscontinuityURIRangeAndEncryption(t *testing.T) {
	p, err := ParseHLSPlaylist("https://cdn.example/vod/list.m3u8", `#EXTM3U
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-MEDIA-SEQUENCE:1
#EXT-X-KEY:METHOD=AES-128,URI="key-a.bin"
#EXT-X-BYTERANGE:100@0
#EXTINF:4,
seg.ts
#EXT-X-DISCONTINUITY
#EXT-X-KEY:METHOD=AES-128,URI="key-b.bin"
#EXT-X-BYTERANGE:100@100
#EXTINF:4,
seg.ts
#EXT-X-ENDLIST
`)
	if err != nil {
		t.Fatal(err)
	}
	a, err := HLSTimelineIdentity(p.Segments[0])
	if err != nil {
		t.Fatal(err)
	}
	b, err := HLSTimelineIdentity(p.Segments[1])
	if err != nil {
		t.Fatal(err)
	}
	ida, _ := FragmentID(a)
	idb, _ := FragmentID(b)
	if ida == idb {
		t.Fatalf("timeline identity did not include discontinuity/range/encryption: %+v %+v", a, b)
	}
	res, err := ReconcileHLSTimeline(nil, p)
	if err != nil {
		t.Fatal(err)
	}
	if res.Kind != HLSVOD || !res.Ended || res.Appended != 2 {
		t.Fatalf("vod/endlist classification failed: %+v", res)
	}
}

func TestHLSTimelineIdentitySameSequenceChangedURIRange(t *testing.T) {
	base := HLSSegment{ResolvedURI: "https://cdn.example/seg.ts", MediaSequence: 42, DiscontinuitySequence: 0, Range: &ByteRange{Offset: 0, Length: 100}}
	changedURI := base
	changedURI.ResolvedURI = "https://cdn.example/seg-v2.ts"
	changedRange := base
	changedRange.Range = &ByteRange{Offset: 100, Length: 100}
	a, err := HLSTimelineIdentity(base)
	if err != nil {
		t.Fatal(err)
	}
	b, err := HLSTimelineIdentity(changedURI)
	if err != nil {
		t.Fatal(err)
	}
	c, err := HLSTimelineIdentity(changedRange)
	if err != nil {
		t.Fatal(err)
	}
	ida, _ := FragmentID(a)
	idb, _ := FragmentID(b)
	idc, _ := FragmentID(c)
	if ida == idb || ida == idc || idb == idc {
		t.Fatalf("same sequence URI/range changes were not identity-relevant: %s %s %s", ida, idb, idc)
	}
}

func TestReconcileHLSTimelineDuplicateRefreshSuppressesRedownload(t *testing.T) {
	p, err := ParseHLSPlaylist("https://cdn.example/event/list.m3u8", `#EXTM3U
#EXT-X-PLAYLIST-TYPE:EVENT
#EXT-X-MEDIA-SEQUENCE:10
#EXTINF:4,
seg10.ts
`)
	if err != nil {
		t.Fatal(err)
	}
	ident, err := HLSTimelineIdentity(p.Segments[0])
	if err != nil {
		t.Fatal(err)
	}
	ledger, _ := NewFragmentLedger()
	if _, err := ledger.Commit(FragmentRecord{Identity: ident, AttemptGeneration: 1, ExpectedLength: 4, Hash: "sha256:ok", LocalArtifactRef: "seg10.ts", DurableBytesWritten: true}); err != nil {
		t.Fatal(err)
	}
	res, err := ReconcileHLSTimeline(ledger.Snapshot(), p)
	if err != nil {
		t.Fatal(err)
	}
	if res.Kind != HLSEvent || len(res.Entries) != 1 || res.Retained != 1 || res.Entries[0].Redownload {
		t.Fatalf("duplicate refresh not suppressed: %+v", res)
	}
}
