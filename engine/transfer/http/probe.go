package httptransfer

import (
	"context"
	"errors"
	"fmt"
	"mime"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

var (
	ErrProbeFailed           = errors.New("http representation probe failed")
	ErrMalformedContentRange = errors.New("malformed content-range")
	ErrContradictoryMetadata = errors.New("contradictory representation metadata")
	ErrEncodedRepresentation = errors.New("encoded representation cannot preserve byte offsets")
)

type Doer interface {
	Do(*http.Request) (*http.Response, error)
}

type ProbeResult struct {
	StatusCode         int    `json:"status_code"`
	Length             *int64 `json:"length,omitempty"`
	AcceptRanges       bool   `json:"accept_ranges"`
	ETag               string `json:"etag,omitempty"`
	WeakETag           bool   `json:"weak_etag"`
	LastModified       string `json:"last_modified,omitempty"`
	ContentDisposition string `json:"content_disposition,omitempty"`
	Filename           string `json:"filename,omitempty"`
	ContentType        string `json:"content_type,omitempty"`
	EffectiveURL       string `json:"effective_url"`
	ContentEncoding    string `json:"content_encoding,omitempty"`
	UsedRangeFallback  bool   `json:"used_range_fallback"`
}

type contentRange struct {
	start int64
	end   int64
	total *int64
}

func parseContentRange(v string) (contentRange, error) {
	v = strings.TrimSpace(v)
	if !strings.HasPrefix(strings.ToLower(v), "bytes ") {
		return contentRange{}, ErrMalformedContentRange
	}
	rest := strings.TrimSpace(v[len("bytes "):])
	parts := strings.Split(rest, "/")
	if len(parts) != 2 {
		return contentRange{}, ErrMalformedContentRange
	}
	span := strings.Split(parts[0], "-")
	if len(span) != 2 {
		return contentRange{}, ErrMalformedContentRange
	}
	start, err1 := strconv.ParseInt(span[0], 10, 64)
	end, err2 := strconv.ParseInt(span[1], 10, 64)
	if err1 != nil || err2 != nil || start < 0 || end < start {
		return contentRange{}, ErrMalformedContentRange
	}
	var total *int64
	if parts[1] != "*" {
		n, err := strconv.ParseInt(parts[1], 10, 64)
		if err != nil || n <= end {
			return contentRange{}, ErrMalformedContentRange
		}
		total = &n
	}
	return contentRange{start: start, end: end, total: total}, nil
}

func parseLength(resp *http.Response, ranged bool) (*int64, error) {
	var headerLength *int64
	if raw := strings.TrimSpace(resp.Header.Get("Content-Length")); raw != "" {
		n, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || n < 0 {
			return nil, fmt.Errorf("%w: content-length", ErrProbeFailed)
		}
		headerLength = &n
	} else if resp.ContentLength >= 0 {
		n := resp.ContentLength
		headerLength = &n
	}
	rawRange := strings.TrimSpace(resp.Header.Get("Content-Range"))
	if rawRange == "" {
		if ranged && resp.StatusCode == http.StatusPartialContent {
			return nil, ErrMalformedContentRange
		}
		return headerLength, nil
	}
	cr, err := parseContentRange(rawRange)
	if err != nil {
		return nil, err
	}
	if ranged {
		if cr.start != 0 || cr.end != 0 {
			return nil, fmt.Errorf("%w: fallback range must be bytes 0-0", ErrContradictoryMetadata)
		}
		if headerLength != nil && *headerLength != 1 {
			return nil, fmt.Errorf("%w: partial content-length=%d", ErrContradictoryMetadata, *headerLength)
		}
	}
	if cr.total != nil {
		return cr.total, nil
	}
	return nil, nil
}

func parseDisposition(raw string) (string, string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", "", nil
	}
	kind, params, err := mime.ParseMediaType(raw)
	if err != nil {
		return "", "", fmt.Errorf("%w: content-disposition", ErrProbeFailed)
	}
	return strings.ToLower(kind), params["filename"], nil
}

func effectiveURL(resp *http.Response, fallback string) string {
	if resp != nil && resp.Request != nil && resp.Request.URL != nil {
		return resp.Request.URL.String()
	}
	return fallback
}

func metadataFromResponse(resp *http.Response, requested string, ranged bool) (ProbeResult, error) {
	if resp == nil {
		return ProbeResult{}, ErrProbeFailed
	}
	encoding := strings.ToLower(strings.TrimSpace(resp.Header.Get("Content-Encoding")))
	if resp.Uncompressed || (encoding != "" && encoding != "identity") {
		return ProbeResult{}, ErrEncodedRepresentation
	}
	length, err := parseLength(resp, ranged)
	if err != nil {
		return ProbeResult{}, err
	}
	disposition, filename, err := parseDisposition(resp.Header.Get("Content-Disposition"))
	if err != nil {
		return ProbeResult{}, err
	}
	etag := strings.TrimSpace(resp.Header.Get("ETag"))
	weak := strings.HasPrefix(strings.ToLower(etag), "w/")
	result := ProbeResult{
		StatusCode:         resp.StatusCode,
		Length:             length,
		AcceptRanges:       strings.EqualFold(strings.TrimSpace(resp.Header.Get("Accept-Ranges")), "bytes") || ranged,
		ETag:               etag,
		WeakETag:           weak,
		LastModified:       strings.TrimSpace(resp.Header.Get("Last-Modified")),
		ContentDisposition: disposition,
		Filename:           filename,
		ContentType:        strings.TrimSpace(resp.Header.Get("Content-Type")),
		EffectiveURL:       effectiveURL(resp, requested),
		ContentEncoding:    encoding,
		UsedRangeFallback:  ranged,
	}
	if _, err := url.ParseRequestURI(result.EffectiveURL); err != nil {
		return ProbeResult{}, fmt.Errorf("%w: effective url", ErrProbeFailed)
	}
	return result, nil
}

func makeProbeRequest(ctx context.Context, method, raw string, ranged bool) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, method, raw, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept-Encoding", "identity")
	if ranged {
		req.Header.Set("Range", "bytes=0-0")
	}
	return req, nil
}

// Probe discovers representation metadata without trusting frontend guesses.
// The supplied Doer is expected to enforce the XGO security transport policy;
// Probe itself disables content decoding and performs the HEAD -> ranged GET
// fallback contract.
func Probe(ctx context.Context, client Doer, rawURL string) (ProbeResult, error) {
	if client == nil {
		return ProbeResult{}, ErrProbeFailed
	}
	req, err := makeProbeRequest(ctx, http.MethodHead, rawURL, false)
	if err != nil {
		return ProbeResult{}, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return ProbeResult{}, fmt.Errorf("%w: %v", ErrProbeFailed, err)
	}
	if resp.Body != nil {
		resp.Body.Close()
	}
	if resp.StatusCode != http.StatusMethodNotAllowed && resp.StatusCode != http.StatusForbidden {
		if resp.StatusCode < 200 || resp.StatusCode >= 400 {
			return ProbeResult{}, fmt.Errorf("%w: status %d", ErrProbeFailed, resp.StatusCode)
		}
		return metadataFromResponse(resp, rawURL, false)
	}

	req, err = makeProbeRequest(ctx, http.MethodGet, rawURL, true)
	if err != nil {
		return ProbeResult{}, err
	}
	resp, err = client.Do(req)
	if err != nil {
		return ProbeResult{}, fmt.Errorf("%w: %v", ErrProbeFailed, err)
	}
	if resp.Body != nil {
		defer resp.Body.Close()
	}
	if resp.StatusCode != http.StatusPartialContent && resp.StatusCode != http.StatusOK {
		return ProbeResult{}, fmt.Errorf("%w: fallback status %d", ErrProbeFailed, resp.StatusCode)
	}
	result, err := metadataFromResponse(resp, rawURL, resp.StatusCode == http.StatusPartialContent)
	if err != nil {
		return ProbeResult{}, err
	}
	result.UsedRangeFallback = true
	return result, nil
}
