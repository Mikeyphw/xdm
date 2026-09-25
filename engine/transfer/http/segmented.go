package httptransfer

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

var (
	ErrRangesUnsupported = errors.New("server does not honor byte ranges")
	ErrSegmentShort      = errors.New("segmented response ended before requested range completed")
)

type StagingFile interface {
	checkpoint.File
	Truncate(int64) error
}

type ByteRange struct {
	Start int64 `json:"start"`
	End   int64 `json:"end"` // inclusive
}

func (r ByteRange) Valid() bool { return r.Start >= 0 && r.End >= r.Start }
func (r ByteRange) Length() int64 {
	if !r.Valid() {
		return 0
	}
	return r.End - r.Start + 1
}

type Segment struct {
	Index           int64 `json:"index"`
	Start           int64 `json:"start"`
	End             int64 `json:"end"`
	FirstBlockIndex int64 `json:"first_block_index"`
}

func PlanSegments(total int64, requested int, blockSize int64) ([]Segment, error) {
	if total <= 0 || requested < 2 || blockSize <= 0 {
		return nil, ErrInvalidTransfer
	}
	blocks := (total + blockSize - 1) / blockSize
	count := int64(requested)
	if count > blocks {
		count = blocks
	}
	if count <= 1 {
		return nil, ErrRangesUnsupported
	}
	base := blocks / count
	extra := blocks % count
	out := make([]Segment, 0, count)
	blockCursor := int64(0)
	for i := int64(0); i < count; i++ {
		n := base
		if i < extra {
			n++
		}
		start := blockCursor * blockSize
		endExclusive := (blockCursor + n) * blockSize
		if endExclusive > total {
			endExclusive = total
		}
		out = append(out, Segment{Index: i, Start: start, End: endExclusive - 1, FirstBlockIndex: blockCursor})
		blockCursor += n
	}
	return out, nil
}

func Preallocate(file StagingFile, total int64) error {
	if file == nil || total < 0 {
		return ErrInvalidTransfer
	}
	if err := file.Truncate(total); err != nil {
		return err
	}
	return file.Sync()
}

type SegmentLedger interface {
	EnsureSegmentPlan(context.Context, identity.DownloadID, identity.AttemptGeneration, []store.SegmentRecord) ([]store.SegmentRecord, error)
	MutateSegment(context.Context, identity.DownloadID, identity.AttemptGeneration, int64, identity.Revision, string) (store.SegmentRecord, error)
	CompleteSegment(context.Context, identity.DownloadID, identity.AttemptGeneration, int64, identity.Revision) (store.SegmentRecord, error)
}

type SegmentedPlan struct {
	URL            string
	Representation Representation
	DownloadID     identity.DownloadID
	Generation     identity.AttemptGeneration
	SegmentCount   int
	BlockBytes     int64
	BufferBytes    int
	File           StagingFile
	Committer      BlockCommitter
	Ledger         SegmentLedger
	Lifecycle      Lifecycle
	Limiter        Limiter
	Progress       ProgressSink
}

type SegmentedResult struct {
	Segments int   `json:"segments"`
	Blocks   int64 `json:"blocks"`
	Bytes    int64 `json:"bytes"`
	Fallback bool  `json:"fallback"`
}

func transferRangeRequest(ctx context.Context, url string, rep Representation, br ByteRange) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept-Encoding", "identity")
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", br.Start, br.End))
	if strongETag(rep.ETag) {
		req.Header.Set("If-Range", rep.ETag)
	} else if rep.LastModified != "" {
		req.Header.Set("If-Range", rep.LastModified)
	}
	return req, nil
}

func validateExactRange(resp *http.Response, br ByteRange, total int64) error {
	if resp.StatusCode == http.StatusOK {
		return ErrRangesUnsupported
	}
	if resp.StatusCode != http.StatusPartialContent {
		return fmt.Errorf("%w: status=%d", ErrMalformedContentRange, resp.StatusCode)
	}
	cr, err := parseContentRange(resp.Header.Get("Content-Range"))
	if err != nil {
		return err
	}
	if cr.start != br.Start || cr.end != br.End || cr.total == nil || *cr.total != total {
		return fmt.Errorf("%w: got=%d-%d/%v want=%d-%d/%d", ErrContradictoryMetadata, cr.start, cr.end, cr.total, br.Start, br.End, total)
	}
	if resp.ContentLength >= 0 && resp.ContentLength != br.Length() {
		return fmt.Errorf("%w: content-length=%d want=%d", ErrContradictoryMetadata, resp.ContentLength, br.Length())
	}
	enc := resp.Header.Get("Content-Encoding")
	if resp.Uncompressed || (enc != "" && enc != "identity") {
		return ErrEncodedRepresentation
	}
	return nil
}

func rangeSupport(ctx context.Context, client Doer, url string, rep Representation, total int64) (bool, error) {
	req, err := transferRangeRequest(ctx, url, rep, ByteRange{0, 0})
	if err != nil {
		return false, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		return false, nil
	}
	if err = validateExactRange(resp, ByteRange{0, 0}, total); err != nil {
		return false, err
	}
	return true, nil
}

func executeOneSegment(ctx context.Context, client Doer, p SegmentedPlan, seg Segment, rec store.SegmentRecord, total int64, progressMu *sync.Mutex) (int64, int64, error) {
	running, err := p.Ledger.MutateSegment(ctx, p.DownloadID, p.Generation, seg.Index, rec.Revision, "running")
	if err != nil {
		return 0, 0, err
	}
	req, err := transferRangeRequest(ctx, p.URL, p.Representation, ByteRange{seg.Start, seg.End})
	if err != nil {
		return 0, 0, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, 0, err
	}
	defer resp.Body.Close()
	if err = validateExactRange(resp, ByteRange{seg.Start, seg.End}, total); err != nil {
		return 0, 0, err
	}
	bufferSize := p.BufferBytes
	if bufferSize == 0 {
		bufferSize = 64 << 10
	}
	if bufferSize < 4<<10 || bufferSize > 1<<20 {
		return 0, 0, ErrInvalidTransfer
	}
	blockSize := p.BlockBytes
	remaining := seg.End - seg.Start + 1
	cursor := seg.Start
	blockIndex := seg.FirstBlockIndex
	buf := make([]byte, bufferSize)
	pending := make([]byte, 0, blockSize)
	var bytesWritten, blocks int64
	commit := func(data []byte) error {
		rec, err := p.Committer.CommitBlock(ctx, p.File, checkpoint.CommitRequest{DownloadID: p.DownloadID, Generation: p.Generation, BlockIndex: blockIndex, StartByte: cursor, Data: append([]byte(nil), data...), NowUnixMS: time.Now().UnixMilli()})
		if err != nil {
			return err
		}
		cursor += rec.CommittedLength
		bytesWritten += rec.CommittedLength
		blocks++
		blockIndex++
		if p.Progress != nil {
			progressMu.Lock()
			p.Progress.OnProgress(Progress{Committed: rec.StartByte + rec.CommittedLength, Total: &total, Delta: rec.CommittedLength})
			progressMu.Unlock()
		}
		return nil
	}
	for remaining > 0 {
		want := len(buf)
		if int64(want) > remaining {
			want = int(remaining)
		}
		n, readErr := resp.Body.Read(buf[:want])
		if n > 0 {
			if p.Limiter != nil {
				if err := p.Limiter.WaitN(ctx, n); err != nil {
					return bytesWritten, blocks, err
				}
			}
			pending = append(pending, buf[:n]...)
			remaining -= int64(n)
			for int64(len(pending)) >= blockSize {
				chunk := append([]byte(nil), pending[:blockSize]...)
				pending = append([]byte(nil), pending[blockSize:]...)
				if err := commit(chunk); err != nil {
					return bytesWritten, blocks, err
				}
			}
		}
		if readErr != nil {
			if errors.Is(readErr, io.EOF) {
				break
			}
			return bytesWritten, blocks, readErr
		}
	}
	if remaining != 0 {
		return bytesWritten, blocks, ErrSegmentShort
	}
	if len(pending) > 0 {
		if err := commit(pending); err != nil {
			return bytesWritten, blocks, err
		}
	}
	// Detect a lying response that sent extra bytes beyond the declared range.
	one := make([]byte, 1)
	if n, e := resp.Body.Read(one); n > 0 || (e != nil && !errors.Is(e, io.EOF)) {
		return bytesWritten, blocks, fmt.Errorf("%w: response exceeded requested range", ErrContradictoryMetadata)
	}
	if _, err = p.Ledger.CompleteSegment(ctx, p.DownloadID, p.Generation, seg.Index, running.Revision); err != nil {
		return bytesWritten, blocks, err
	}
	return bytesWritten, blocks, nil
}

func ExecuteSegmented(ctx context.Context, client Doer, p SegmentedPlan) (SegmentedResult, error) {
	if client == nil || p.File == nil || p.Committer == nil || p.Ledger == nil || p.Lifecycle == nil || p.DownloadID.IsZero() || !p.Generation.Valid() || p.URL == "" || p.Representation.Length == nil || *p.Representation.Length <= 0 {
		return SegmentedResult{}, ErrInvalidTransfer
	}
	if err := p.Representation.Validate(); err != nil {
		return SegmentedResult{}, err
	}
	if p.BlockBytes == 0 {
		p.BlockBytes = checkpoint.DefaultBlockSize
	}
	if err := checkpoint.ValidateBlockSize(p.BlockBytes); err != nil {
		return SegmentedResult{}, err
	}
	segments, err := PlanSegments(*p.Representation.Length, p.SegmentCount, p.BlockBytes)
	if err != nil {
		return SegmentedResult{}, err
	}
	supported, err := rangeSupport(ctx, client, p.URL, p.Representation, *p.Representation.Length)
	if err != nil {
		return SegmentedResult{}, failLifecycle(ctx, p.Lifecycle, failure.RangeContradiction, err)
	}
	if !supported {
		return SegmentedResult{Fallback: true}, ErrRangesUnsupported
	}
	if err = p.Lifecycle.Start(ctx); err != nil {
		return SegmentedResult{}, err
	}
	if err = Preallocate(p.File, *p.Representation.Length); err != nil {
		return SegmentedResult{}, failLifecycle(ctx, p.Lifecycle, failure.StorageUnavailable, err)
	}
	specs := make([]store.SegmentRecord, len(segments))
	for i, s := range segments {
		specs[i] = store.SegmentRecord{Index: s.Index, StartByte: s.Start, EndByte: s.End}
	}
	persisted, err := p.Ledger.EnsureSegmentPlan(ctx, p.DownloadID, p.Generation, specs)
	if err != nil {
		return SegmentedResult{}, failLifecycle(ctx, p.Lifecycle, failure.StorageUnavailable, err)
	}
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	type result struct {
		bytes, blocks int64
		err           error
	}
	ch := make(chan result, len(segments))
	var progressMu sync.Mutex
	for i := range segments {
		seg, rec := segments[i], persisted[i]
		go func() {
			b, bl, e := executeOneSegment(runCtx, client, p, seg, rec, *p.Representation.Length, &progressMu)
			if e != nil {
				cancel()
			}
			ch <- result{b, bl, e}
		}()
	}
	var totalBytes, totalBlocks int64
	var firstErr error
	for range segments {
		r := <-ch
		totalBytes += r.bytes
		totalBlocks += r.blocks
		if firstErr == nil && r.err != nil {
			firstErr = r.err
		}
	}
	if firstErr != nil {
		return SegmentedResult{Segments: len(segments), Blocks: totalBlocks, Bytes: totalBytes}, failLifecycle(ctx, p.Lifecycle, failure.RangeContradiction, firstErr)
	}
	if totalBytes != *p.Representation.Length {
		return SegmentedResult{Segments: len(segments), Blocks: totalBlocks, Bytes: totalBytes}, failLifecycle(ctx, p.Lifecycle, failure.RangeContradiction, io.ErrUnexpectedEOF)
	}
	if err = p.Lifecycle.Complete(ctx); err != nil {
		return SegmentedResult{}, err
	}
	return SegmentedResult{Segments: len(segments), Blocks: totalBlocks, Bytes: totalBytes}, nil
}

// ExecuteAdaptive uses a range preflight to choose segmented execution or a
// clean single-stream fallback before any segmented checkpoint is committed.
func ExecuteAdaptive(ctx context.Context, client Doer, seg SegmentedPlan, single ExecutePlan) (SegmentedResult, error) {
	if seg.Representation.Length == nil {
		return SegmentedResult{}, ErrInvalidTransfer
	}
	supported, err := rangeSupport(ctx, client, seg.URL, seg.Representation, *seg.Representation.Length)
	if err != nil {
		return SegmentedResult{}, err
	}
	if !supported {
		res, err := Execute(ctx, client, single)
		return SegmentedResult{Segments: 1, Blocks: res.Blocks, Bytes: res.Committed, Fallback: true}, err
	}
	// Avoid a second preflight by executing segmented directly through a client
	// wrapper that replays the successful preflight response semantics only as
	// policy. The segmented function intentionally rechecks support because the
	// server may change between requests; safety wins over one extra request.
	return ExecuteSegmented(ctx, client, seg)
}

func rangeHeader(br ByteRange) string {
	return "bytes=" + strconv.FormatInt(br.Start, 10) + "-" + strconv.FormatInt(br.End, 10)
}
