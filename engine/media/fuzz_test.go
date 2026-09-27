package media

import "testing"

func FuzzHLSParser(f *testing.F) {
	for _, seed := range []string{"#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts\n#EXT-X-ENDLIST\n", "#EXTM3U\n#EXT-X-BYTERANGE:nope\nseg.ts\n", "not-hls"} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, body string) {
		if len(body) > MaxEnvelopeBytes {
			body = body[:MaxEnvelopeBytes]
		}
		playlist, err := ParseHLSPlaylist("https://cdn.example.test/base/list.m3u8", body)
		if err != nil {
			return
		}
		if playlist.BaseURL == "" {
			t.Fatal("successful HLS parse lost base URL")
		}
	})
}

func FuzzDASHParser(f *testing.F) {
	for _, seed := range []string{`<MPD type="static"><Period><AdaptationSet><Representation id="v" bandwidth="1"/></AdaptationSet></Period></MPD>`, `<MPD><Period>`, `<not-mpd/>`} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, body string) {
		if len(body) > MaxEnvelopeBytes {
			body = body[:MaxEnvelopeBytes]
		}
		manifest, err := ParseDASHManifest("https://cdn.example.test/base/manifest.mpd", body)
		if err != nil {
			return
		}
		if manifest.BaseURL == "" {
			t.Fatal("successful DASH parse lost base URL")
		}
		_, _ = ExpandDASHFragments(manifest)
	})
}

func FuzzCaptureEnvelopeParser(f *testing.F) {
	for _, seed := range []string{
		`{"version":1,"request":{"url":"https://cdn.example.test/a","method":"GET"},"page":{"page_url":"https://page.example.test/","session_id":"s","document_generation":1},"response":{"observed_at":"2026-01-01T00:00:00Z"}}`,
		`{"version":1,"request":{"url":"https://cdn.example.test/a","method":"GET","headers":[{"name":"Authorization","value":"Bearer secret"}]}}`,
		`not-json`,
	} {
		f.Add([]byte(seed))
	}
	f.Fuzz(func(t *testing.T, data []byte) {
		if len(data) > MaxEnvelopeBytes+1 {
			data = data[:MaxEnvelopeBytes+1]
		}
		env, err := ParseCaptureEnvelopeJSON(data)
		if err != nil {
			return
		}
		diagnostic := env.SafeDiagnostic()
		if diagnostic.RequestURL == "" || diagnostic.SessionID == "" {
			t.Fatalf("successful capture parse produced incomplete diagnostic: %+v", diagnostic)
		}
	})
}
