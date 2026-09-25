//go:build cgo

package httptransfer

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/store/checkpoint"
	"github.com/subhra74/xdm/engine/transfer/arbitration"
)

func TestSegmentedUsesCentralArbiterForConnectionAndBandwidth(t *testing.T) {
	data := []byte(strings.Repeat("arbiter-", 16384))
	var mu sync.Mutex
	active, peak := 0, 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var start, end int64
		if _, err := fmt.Sscanf(r.Header.Get("Range"), "bytes=%d-%d", &start, &end); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if start == 0 && end == 0 {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes 0-0/%d", len(data)))
			w.Header().Set("Content-Length", "1")
			w.WriteHeader(http.StatusPartialContent)
			_, _ = w.Write(data[:1])
			return
		}
		mu.Lock()
		active++
		if active > peak {
			peak = active
		}
		mu.Unlock()
		defer func() { mu.Lock(); active--; mu.Unlock() }()
		time.Sleep(20 * time.Millisecond)
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, len(data)))
		w.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
		w.Header().Set("ETag", `"arb-v1"`)
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(data[start : end+1])
	}))
	defer srv.Close()

	fx := setupTransferFixture(t)
	rep := representationFor(t, srv.URL, int64(len(data)), `"arb-v1"`)
	arb, err := arbitration.New(arbitration.Limits{
		GlobalConnections:          2,
		DefaultHostConnections:     2,
		DefaultDownloadConnections: 2,
		GlobalBytesPerSecond:       8 << 20,
		DownloadBytesPerSecond:     map[string]int64{fx.dl.String(): 8 << 20},
	})
	if err != nil {
		t.Fatal(err)
	}
	handle, err := arb.Bind(arbitration.Key{DownloadID: fx.dl.String(), Host: "127.0.0.1"})
	if err != nil {
		t.Fatal(err)
	}
	got, err := ExecuteSegmented(context.Background(), srv.Client(), SegmentedPlan{
		URL: srv.URL, Representation: rep, DownloadID: fx.dl, Generation: fx.gen,
		SegmentCount: 8, BlockBytes: 4096, BufferBytes: 4096, File: fx.file,
		Committer: checkpoint.Committer{Repository: fx.repo}, Ledger: fx.repo,
		Lifecycle: &fakeLifecycle{}, Resources: handle,
	})
	if err != nil {
		t.Fatal(err)
	}
	if got.Bytes != int64(len(data)) {
		t.Fatalf("bytes=%d", got.Bytes)
	}
	mu.Lock()
	gotPeak := peak
	mu.Unlock()
	if gotPeak != 2 {
		t.Fatalf("HTTP peak connections=%d want=2", gotPeak)
	}
	metrics := arb.Metrics()
	if metrics.PeakConnections != 2 || metrics.ActiveConnections != 0 || metrics.BandwidthBytes < int64(len(data)) {
		t.Fatalf("arbiter metrics=%+v", metrics)
	}
}
