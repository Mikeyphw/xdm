package media

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"fmt"
	"strings"
	"testing"
)

type memoryHLSFetcher struct {
	objects map[string][]byte
	counts  map[string]int
	fail    map[string]error
}

func newMemoryHLSFetcher(objects map[string][]byte) *memoryHLSFetcher {
	return &memoryHLSFetcher{objects: objects, counts: map[string]int{}, fail: map[string]error{}}
}

func (f *memoryHLSFetcher) FetchHLS(_ context.Context, req HLSFetchRequest) (HLSFetchResponse, error) {
	f.counts[string(req.Kind)+" "+req.URI]++
	if err := f.fail[req.URI]; err != nil {
		return HLSFetchResponse{}, err
	}
	body, ok := f.objects[req.URI]
	if !ok {
		return HLSFetchResponse{}, fmt.Errorf("missing %s", req.URI)
	}
	if req.Range != nil {
		start := req.Range.Offset
		end := req.Range.Offset + req.Range.Length
		if start < 0 || end > int64(len(body)) || start > end {
			return HLSFetchResponse{}, fmt.Errorf("bad range")
		}
		body = body[start:end]
	}
	return HLSFetchResponse{Bytes: append([]byte(nil), body...)}, nil
}

func TestHLSExecutionVODAES128InitMapByteRangeRestart(t *testing.T) {
	key := []byte("0123456789abcdef")
	objects := map[string][]byte{
		"https://cdn.example/vod/init.mp4":      []byte("init-0123456789"),
		"https://cdn.example/vod/key.bin":       key,
		"https://cdn.example/vod/all-seg.ts":    []byte("0123456789" + string(encryptHLSForTest(t, []byte("first-clear-fragment"), key, 1)) + string(encryptHLSForTest(t, []byte("second-clear-fragment"), key, 2))),
		"https://cdn.example/live/seg100.ts":    []byte("live100"),
		"https://cdn.example/live/seg101.ts":    []byte("live101"),
		"https://cdn.example/live/seg102.ts":    []byte("live102"),
		"https://cdn.example/live/seg103.ts":    []byte("live103"),
		"https://cdn.example/live/seg104.ts":    []byte("live104"),
		"https://cdn.example/protected/key.bin": key,
	}
	seg1 := encryptHLSForTest(t, []byte("first-clear-fragment"), key, 1)
	seg2 := encryptHLSForTest(t, []byte("second-clear-fragment"), key, 2)
	objects["https://cdn.example/vod/all-seg.ts"] = append(append([]byte("0123456789"), seg1...), seg2...)
	fetcher := newMemoryHLSFetcher(objects)
	ledger, _ := NewFragmentLedger()
	exec, err := NewHLSExecutor(fetcher, ledger)
	if err != nil {
		t.Fatal(err)
	}
	playlist := `#EXTM3U
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-MEDIA-SEQUENCE:1
#EXT-X-MAP:URI="init.mp4",BYTERANGE="4@5"
#EXT-X-KEY:METHOD=AES-128,URI="key.bin"
#EXT-X-BYTERANGE:` + fmt.Sprintf("%d@10", len(seg1)) + `
#EXTINF:4,
all-seg.ts
#EXT-X-BYTERANGE:` + fmt.Sprintf("%d@%d", len(seg2), 10+len(seg1)) + `
#EXTINF:4,
all-seg.ts
#EXT-X-ENDLIST
`
	result, err := exec.Execute(context.Background(), HLSExecutionPlan{BaseURL: "https://cdn.example/vod/list.m3u8", Playlists: []string{playlist}, AttemptGeneration: 1})
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if result.Status != HLSExecutionCompleted || result.Completed != 2 || result.InitMapsFetched != 2 || len(ledger.Snapshot()) != 2 {
		t.Fatalf("unexpected result=%+v ledger=%+v", result, ledger.Snapshot())
	}
	second, err := exec.Execute(context.Background(), HLSExecutionPlan{BaseURL: "https://cdn.example/vod/list.m3u8", Playlists: []string{playlist}, AttemptGeneration: 2})
	if err != nil {
		t.Fatalf("restart execute: %v", err)
	}
	if second.Completed != 0 || second.Skipped != 2 {
		t.Fatalf("restart redownloaded committed fragments: %+v", second)
	}
	if fetcher.counts["segment https://cdn.example/vod/all-seg.ts"] != 2 {
		t.Fatalf("segment fetched after restart; counts=%+v", fetcher.counts)
	}
}

func TestHLSExecutionLiveSlidingWindowStopAndUnsupported(t *testing.T) {
	objects := map[string][]byte{
		"https://cdn.example/live/seg100.ts": []byte("live100"),
		"https://cdn.example/live/seg101.ts": []byte("live101"),
		"https://cdn.example/live/seg102.ts": []byte("live102"),
		"https://cdn.example/live/seg103.ts": []byte("live103"),
		"https://cdn.example/live/seg104.ts": []byte("live104"),
	}
	fetcher := newMemoryHLSFetcher(objects)
	ledger, _ := NewFragmentLedger()
	exec, _ := NewHLSExecutor(fetcher, ledger)
	p1 := `#EXTM3U
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:100
#EXTINF:4,
seg100.ts
#EXTINF:4,
seg101.ts
#EXTINF:4,
seg102.ts
`
	p2 := `#EXTM3U
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:102
#EXTINF:4,
seg102.ts
#EXTINF:4,
seg103.ts
#EXTINF:4,
seg104.ts
`
	result, err := exec.Execute(context.Background(), HLSExecutionPlan{BaseURL: "https://cdn.example/live/list.m3u8", Playlists: []string{p1, p2}, AttemptGeneration: 1, StopAfterNewFragments: 4})
	if err == nil || !strings.Contains(err.Error(), ErrHLSExecutionStopped.Error()) {
		t.Fatalf("expected bounded stop, got result=%+v err=%v", result, err)
	}
	if result.Status != HLSExecutionStopped || result.Completed != 4 || result.Skipped < 1 || !result.LiveBoundReached {
		t.Fatalf("live stop/duplicate suppression failed: %+v", result)
	}
	if fetcher.counts["segment https://cdn.example/live/seg102.ts"] != 1 {
		t.Fatalf("duplicate live segment fetched more than once: %+v", fetcher.counts)
	}
	unsupported := `#EXTM3U
#EXT-X-MEDIA-SEQUENCE:1
#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://fairplay"
#EXTINF:4,
seg100.ts
#EXT-X-ENDLIST
`
	res2, err := exec.Execute(context.Background(), HLSExecutionPlan{BaseURL: "https://cdn.example/live/list.m3u8", Playlists: []string{unsupported}, AttemptGeneration: 2})
	if err != nil {
		t.Fatalf("unsupported protection should be typed outcome, not fatal: %v", err)
	}
	if res2.Unsupported != 1 || res2.Outcomes[0].Status != HLSFragmentUnsupported {
		t.Fatalf("unsupported protection not typed: %+v", res2)
	}
}

func encryptHLSForTest(t *testing.T, plain, key []byte, mediaSequence int64) []byte {
	t.Helper()
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	pad := aes.BlockSize - (len(plain) % aes.BlockSize)
	padded := append([]byte(nil), plain...)
	for i := 0; i < pad; i++ {
		padded = append(padded, byte(pad))
	}
	iv, err := parseHLSIV("", mediaSequence)
	if err != nil {
		t.Fatal(err)
	}
	out := make([]byte, len(padded))
	cipher.NewCBCEncrypter(block, iv).CryptBlocks(out, padded)
	return out
}
