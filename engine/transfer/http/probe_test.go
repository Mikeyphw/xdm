package httptransfer

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestProbeHeadSuccessAndRedirectEffectiveURL(t *testing.T) {
	final := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodHead {
			t.Fatalf("method=%s", r.Method)
		}
		w.Header().Set("Content-Length", "12")
		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("ETag", `"abc"`)
		w.Header().Set("Last-Modified", "Mon, 02 Jan 2006 15:04:05 GMT")
		w.Header().Set("Content-Disposition", `attachment; filename="file.bin"`)
		w.Header().Set("Content-Type", "application/octet-stream")
		w.WriteHeader(http.StatusOK)
	}))
	defer final.Close()
	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, final.URL+"/payload", http.StatusFound)
	}))
	defer redirect.Close()
	got, err := Probe(context.Background(), final.Client(), redirect.URL)
	if err != nil {
		t.Fatal(err)
	}
	if got.Length == nil || *got.Length != 12 || !got.AcceptRanges || got.ETag != `"abc"` || got.Filename != "file.bin" || got.EffectiveURL != final.URL+"/payload" {
		t.Fatalf("unexpected probe: %+v", got)
	}
}

func TestProbeFallsBackToRange(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodHead {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		if r.Header.Get("Range") != "bytes=0-0" || r.Header.Get("Accept-Encoding") != "identity" {
			t.Fatalf("headers=%v", r.Header)
		}
		w.Header().Set("Content-Range", "bytes 0-0/99")
		w.Header().Set("Content-Length", "1")
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write([]byte{'x'})
	}))
	defer srv.Close()
	got, err := Probe(context.Background(), srv.Client(), srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	if !got.UsedRangeFallback || got.Length == nil || *got.Length != 99 || !got.AcceptRanges {
		t.Fatalf("unexpected: %+v", got)
	}
}

func TestProbeRejectsMalformedRangeAndEncoding(t *testing.T) {
	t.Run("malformed range", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodHead {
				w.WriteHeader(http.StatusForbidden)
				return
			}
			w.Header().Set("Content-Range", "bytes nope")
			w.WriteHeader(http.StatusPartialContent)
		}))
		defer srv.Close()
		if _, err := Probe(context.Background(), srv.Client(), srv.URL); err == nil {
			t.Fatal("expected error")
		}
	})
	t.Run("encoded", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Encoding", "gzip")
			w.WriteHeader(http.StatusOK)
		}))
		defer srv.Close()
		if _, err := Probe(context.Background(), srv.Client(), srv.URL); err != ErrEncodedRepresentation {
			t.Fatalf("err=%v", err)
		}
	})
}

func TestProbeUnknownLength(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Transfer-Encoding", "chunked")
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()
	got, err := Probe(context.Background(), srv.Client(), srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	if got.Length != nil {
		t.Fatalf("length=%v", got.Length)
	}
}
