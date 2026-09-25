package media

import "testing"

func TestParseHLSMasterPlaylistVariantsRenditionsAndRelativeURIs(t *testing.T) {
	playlist := `#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio/en.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="subs/en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="aud",SUBTITLES="subs"
video/720/prog.m3u8
`
	p, err := ParseHLSPlaylist("https://cdn.example/master/index.m3u8", playlist)
	if err != nil {
		t.Fatal(err)
	}
	if p.Kind != HLSPlaylistMaster || len(p.Variants) != 1 || len(p.Renditions) != 2 {
		t.Fatalf("unexpected master parse: %+v", p)
	}
	if p.Variants[0].ResolvedURI != "https://cdn.example/master/video/720/prog.m3u8" {
		t.Fatalf("relative variant URI not resolved: %+v", p.Variants[0])
	}
	if p.Renditions[0].ResolvedURI != "https://cdn.example/master/audio/en.m3u8" || !p.Renditions[0].Default {
		t.Fatalf("rendition attrs not preserved: %+v", p.Renditions[0])
	}
}

func TestParseHLSMediaPlaylistTagsByteRangesMapsAESAndDiscontinuity(t *testing.T) {
	playlist := `#EXTM3U
#EXT-X-TARGETDURATION:6
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-MEDIA-SEQUENCE:100
#EXT-X-DISCONTINUITY-SEQUENCE:7
#EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
#EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin",IV=0x1
#EXTINF:6.0,first
#EXT-X-BYTERANGE:1000@720
seg-100.ts
#EXT-X-DISCONTINUITY
#EXT-X-GAP
#EXTINF:6.0,second
seg-101.ts
#EXT-X-KEY:METHOD=SAMPLE-AES,URI="drm.key"
#EXTINF:6.0,third
seg-102.ts
#EXT-X-UNKNOWN-SAFE:foo
#EXT-X-ENDLIST
`
	p, err := ParseHLSPlaylist("https://cdn.example/live/playlist.m3u8", playlist)
	if err != nil {
		t.Fatal(err)
	}
	if p.Kind != HLSPlaylistMedia || len(p.Segments) != 3 || !p.EndList || p.TargetDuration != 6 {
		t.Fatalf("unexpected media parse: %+v", p)
	}
	first := p.Segments[0]
	if first.MediaSequence != 100 || first.DiscontinuitySequence != 7 || first.Protection != HLSProtectionAES128 || first.Range == nil || first.Range.Offset != 720 || first.Map == nil || first.Map.Range.Length != 720 {
		t.Fatalf("first segment tags lost: %+v", first)
	}
	if p.Segments[1].DiscontinuitySequence != 8 || !p.Segments[1].Gap {
		t.Fatalf("discontinuity/gap not tracked: %+v", p.Segments[1])
	}
	if p.Segments[2].Protection != HLSProtectionSampleAES {
		t.Fatalf("sample-aes taxonomy lost: %+v", p.Segments[2])
	}
	if len(p.UnknownTags) == 0 {
		t.Fatalf("safe unknown tags not preserved")
	}
}

func TestParseHLSMalformedInput(t *testing.T) {
	if _, err := ParseHLSPlaylist("https://cdn.example/a.m3u8", "#EXT-X-TARGETDURATION:6\nseg.ts"); err == nil {
		t.Fatalf("missing EXTM3U accepted")
	}
	if _, err := ParseHLSPlaylist("https://cdn.example/a.m3u8", "#EXTM3U\n#EXT-X-BYTERANGE:nope\nseg.ts"); err == nil {
		t.Fatalf("malformed byterange accepted")
	}
}
