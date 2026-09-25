package httptransfer

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

var (
	ErrPaused          = errors.New("transfer paused at durable checkpoint boundary")
	ErrInvalidTransfer = errors.New("invalid http transfer plan")
)

type Limiter interface {
	WaitN(context.Context, int) error
}

// ResourceLimiter is the bound view of the central transfer arbiter. The same
// handle owns both connection permits and byte-rate reservations for a transfer.
type ResourceLimiter interface {
	Limiter
	AcquireConnection(context.Context) (func(), error)
}

func acquireConnection(ctx context.Context, resources ResourceLimiter) (func(), error) {
	if resources == nil {
		return func() {}, nil
	}
	release, err := resources.AcquireConnection(ctx)
	if err != nil {
		return nil, err
	}
	if release == nil {
		return nil, ErrInvalidTransfer
	}
	return release, nil
}

func waitBytes(ctx context.Context, resources ResourceLimiter, legacy Limiter, n int) error {
	if resources != nil {
		return resources.WaitN(ctx, n)
	}
	if legacy != nil {
		return legacy.WaitN(ctx, n)
	}
	return nil
}

type Progress struct {
	Committed int64  `json:"committed"`
	Total     *int64 `json:"total,omitempty"`
	Delta     int64  `json:"delta"`
}

type ProgressSink interface{ OnProgress(Progress) }

type BlockCommitter interface {
	CommitBlock(context.Context, checkpoint.File, checkpoint.CommitRequest) (store.CheckpointBlockRecord, error)
}

type Lifecycle interface {
	Start(context.Context) error
	Complete(context.Context) error
	Pause(context.Context) error
	Fail(context.Context, failure.Failure) error
}

type SQLiteLifecycle struct {
	Repository *store.Repository
	DownloadID identity.DownloadID
	Generation identity.AttemptGeneration
	Revision   identity.Revision
	State      string
	mu         sync.Mutex
}

func (l *SQLiteLifecycle) mutateLocked(ctx context.Context, state string, f *failure.Failure) error {
	if l.Repository == nil || l.DownloadID.IsZero() || !l.Generation.Valid() || !l.Revision.Valid() {
		return ErrInvalidTransfer
	}
	category, payload := "", ""
	if f != nil {
		category = string(f.Category)
		raw, _ := json.Marshal(f)
		payload = string(raw)
	}
	rec, err := l.Repository.MutateAttempt(ctx, l.DownloadID, l.Generation, l.Revision, state, category, payload, time.Now().UnixMilli())
	if err != nil {
		return err
	}
	l.Revision = rec.Revision
	l.State = state
	return nil
}

func (l *SQLiteLifecycle) Start(ctx context.Context) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	switch l.State {
	case "", "reserved":
		if err := l.mutateLocked(ctx, "prepared", nil); err != nil {
			return err
		}
		return l.mutateLocked(ctx, "running", nil)
	case "prepared", "paused":
		return l.mutateLocked(ctx, "running", nil)
	case "running":
		return nil
	default:
		return fmt.Errorf("%w: cannot start from %s", ErrInvalidTransfer, l.State)
	}
}

func (l *SQLiteLifecycle) Complete(ctx context.Context) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.State != "running" {
		return fmt.Errorf("%w: complete from %s", ErrInvalidTransfer, l.State)
	}
	return l.mutateLocked(ctx, "produced_artifact", nil)
}

func (l *SQLiteLifecycle) Pause(ctx context.Context) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.State != "running" {
		return fmt.Errorf("%w: pause from %s", ErrInvalidTransfer, l.State)
	}
	return l.mutateLocked(ctx, "paused", nil)
}

func (l *SQLiteLifecycle) Fail(ctx context.Context, f failure.Failure) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.State == "produced_artifact" || l.State == "failed" || l.State == "cancelled" {
		return fmt.Errorf("%w: fail from %s", ErrInvalidTransfer, l.State)
	}
	return l.mutateLocked(ctx, "failed", &f)
}

type ExecutePlan struct {
	URL             string
	Representation  Representation
	DownloadID      identity.DownloadID
	Generation      identity.AttemptGeneration
	StartOffset     int64
	StartBlockIndex int64
	BufferBytes     int
	CheckpointBytes int
	File            checkpoint.File
	Committer       BlockCommitter
	Lifecycle       Lifecycle
	Limiter         Limiter // legacy byte-only hook; Resources is authoritative when set
	Resources       ResourceLimiter
	Progress        ProgressSink
	PauseRequested  func() bool
}

type ExecuteResult struct {
	BytesThisRun int64 `json:"bytes_this_run"`
	Committed    int64 `json:"committed"`
	Blocks       int64 `json:"blocks"`
}

func typed(category failure.Category, cause error) failure.Failure {
	f, err := failure.NewDefault(category, cause)
	if err != nil {
		panic(err)
	}
	return f
}

func failLifecycle(ctx context.Context, l Lifecycle, category failure.Category, cause error) error {
	f := typed(category, cause)
	if l != nil {
		_ = l.Fail(context.WithoutCancel(ctx), f)
	}
	return f
}

func contentRangeForResume(resp *http.Response, start int64, total *int64) error {
	if start == 0 {
		return nil
	}
	if resp.StatusCode != http.StatusPartialContent {
		return ErrMalformedContentRange
	}
	cr, err := parseContentRange(resp.Header.Get("Content-Range"))
	if err != nil {
		return err
	}
	if cr.start != start {
		return fmt.Errorf("%w: start=%d want=%d", ErrContradictoryMetadata, cr.start, start)
	}
	if total != nil && (cr.total == nil || *cr.total != *total) {
		return fmt.Errorf("%w: total changed", ErrContradictoryMetadata)
	}
	return nil
}

func requestForTransfer(ctx context.Context, p ExecutePlan) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, p.URL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept-Encoding", "identity")
	if p.StartOffset > 0 {
		req.Header.Set("Range", "bytes="+strconv.FormatInt(p.StartOffset, 10)+"-")
		if strongETag(p.Representation.ETag) {
			req.Header.Set("If-Range", p.Representation.ETag)
		} else if p.Representation.LastModified != "" {
			req.Header.Set("If-Range", p.Representation.LastModified)
		}
	}
	return req, nil
}

func Execute(ctx context.Context, client Doer, plan ExecutePlan) (ExecuteResult, error) {
	if client == nil || plan.File == nil || plan.Committer == nil || plan.Lifecycle == nil || plan.DownloadID.IsZero() || !plan.Generation.Valid() || plan.URL == "" || plan.StartOffset < 0 || plan.StartBlockIndex < 0 {
		return ExecuteResult{}, ErrInvalidTransfer
	}
	if err := plan.Representation.Validate(); err != nil {
		return ExecuteResult{}, err
	}
	bufferSize := plan.BufferBytes
	if bufferSize == 0 {
		bufferSize = 64 << 10
	}
	if bufferSize < 4<<10 || bufferSize > 1<<20 {
		return ExecuteResult{}, ErrInvalidTransfer
	}
	checkpointBytes := plan.CheckpointBytes
	if checkpointBytes == 0 {
		checkpointBytes = 1 << 20
	}
	if checkpointBytes < bufferSize || checkpointBytes > 16<<20 {
		return ExecuteResult{}, ErrInvalidTransfer
	}
	if err := plan.Lifecycle.Start(ctx); err != nil {
		return ExecuteResult{}, err
	}

	req, err := requestForTransfer(ctx, plan)
	if err != nil {
		return ExecuteResult{}, failLifecycle(ctx, plan.Lifecycle, failure.InvalidRequest, err)
	}
	releaseConnection, err := acquireConnection(ctx, plan.Resources)
	if err != nil {
		category := failure.InternalFailure
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			category = failure.Cancelled
		}
		return ExecuteResult{}, failLifecycle(ctx, plan.Lifecycle, category, err)
	}
	defer releaseConnection()
	resp, err := client.Do(req)
	if err != nil {
		category := failure.NetworkUnavailable
		if ctx.Err() != nil || errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			category = failure.Cancelled
		}
		return ExecuteResult{}, failLifecycle(ctx, plan.Lifecycle, category, err)
	}
	defer resp.Body.Close()
	enc := strings.ToLower(strings.TrimSpace(resp.Header.Get("Content-Encoding")))
	if resp.Uncompressed || (enc != "" && enc != "identity") {
		return ExecuteResult{}, failLifecycle(ctx, plan.Lifecycle, failure.RangeContradiction, ErrEncodedRepresentation)
	}
	if plan.StartOffset == 0 {
		if resp.StatusCode != http.StatusOK {
			category := failure.NetworkUnavailable
			if resp.StatusCode == http.StatusPartialContent {
				category = failure.RangeContradiction
			}
			return ExecuteResult{}, failLifecycle(ctx, plan.Lifecycle, category, fmt.Errorf("unexpected status %d", resp.StatusCode))
		}
	} else if err := contentRangeForResume(resp, plan.StartOffset, plan.Representation.Length); err != nil {
		return ExecuteResult{}, failLifecycle(ctx, plan.Lifecycle, failure.RepresentationChanged, err)
	}

	buf := make([]byte, bufferSize)
	pending := make([]byte, 0, checkpointBytes)
	committed := plan.StartOffset
	bytesRun := int64(0)
	block := plan.StartBlockIndex
	total := plan.Representation.Length

	commitPending := func() error {
		if len(pending) == 0 {
			return nil
		}
		if total != nil && committed+int64(len(pending)) > *total {
			return io.ErrUnexpectedEOF
		}
		rec, err := plan.Committer.CommitBlock(ctx, plan.File, checkpoint.CommitRequest{
			DownloadID: plan.DownloadID, Generation: plan.Generation, BlockIndex: block,
			StartByte: committed, Data: append([]byte(nil), pending...), NowUnixMS: time.Now().UnixMilli(),
		})
		if err != nil {
			return err
		}
		committed += rec.CommittedLength
		block++
		if plan.Progress != nil {
			plan.Progress.OnProgress(Progress{Committed: committed, Total: total, Delta: rec.CommittedLength})
		}
		pending = pending[:0]
		return nil
	}

	for {
		if err := ctx.Err(); err != nil {
			return ExecuteResult{BytesThisRun: bytesRun, Committed: committed, Blocks: block - plan.StartBlockIndex}, failLifecycle(ctx, plan.Lifecycle, failure.Cancelled, err)
		}
		n, readErr := resp.Body.Read(buf)
		if n > 0 {
			if err := waitBytes(ctx, plan.Resources, plan.Limiter, n); err != nil {
				category := failure.NetworkUnavailable
				if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
					category = failure.Cancelled
				}
				return ExecuteResult{BytesThisRun: bytesRun, Committed: committed, Blocks: block - plan.StartBlockIndex}, failLifecycle(ctx, plan.Lifecycle, category, err)
			}
			if total != nil && plan.StartOffset+bytesRun+int64(n) > *total {
				return ExecuteResult{BytesThisRun: bytesRun, Committed: committed, Blocks: block - plan.StartBlockIndex}, failLifecycle(ctx, plan.Lifecycle, failure.RangeContradiction, errors.New("response exceeds expected representation length"))
			}
			pending = append(pending, buf[:n]...)
			bytesRun += int64(n)
			for len(pending) >= checkpointBytes {
				chunk := append([]byte(nil), pending[:checkpointBytes]...)
				rest := append([]byte(nil), pending[checkpointBytes:]...)
				pending = chunk
				if err := commitPending(); err != nil {
					return ExecuteResult{BytesThisRun: bytesRun, Committed: committed, Blocks: block - plan.StartBlockIndex}, failLifecycle(ctx, plan.Lifecycle, failure.StorageUnavailable, err)
				}
				pending = append(pending, rest...)
				if plan.PauseRequested != nil && plan.PauseRequested() {
					if err := plan.Lifecycle.Pause(context.WithoutCancel(ctx)); err != nil {
						return ExecuteResult{}, err
					}
					return ExecuteResult{BytesThisRun: bytesRun, Committed: committed, Blocks: block - plan.StartBlockIndex}, ErrPaused
				}
			}
		}
		if readErr != nil {
			if errors.Is(readErr, io.EOF) {
				break
			}
			return ExecuteResult{BytesThisRun: bytesRun, Committed: committed, Blocks: block - plan.StartBlockIndex}, failLifecycle(ctx, plan.Lifecycle, failure.NetworkUnavailable, readErr)
		}
	}
	if err := commitPending(); err != nil {
		return ExecuteResult{BytesThisRun: bytesRun, Committed: committed, Blocks: block - plan.StartBlockIndex}, failLifecycle(ctx, plan.Lifecycle, failure.StorageUnavailable, err)
	}
	if total != nil && committed != *total {
		return ExecuteResult{BytesThisRun: bytesRun, Committed: committed, Blocks: block - plan.StartBlockIndex}, failLifecycle(ctx, plan.Lifecycle, failure.RangeContradiction, fmt.Errorf("early eof: committed=%d expected=%d", committed, *total))
	}
	if err := plan.File.Sync(); err != nil {
		return ExecuteResult{BytesThisRun: bytesRun, Committed: committed, Blocks: block - plan.StartBlockIndex}, failLifecycle(ctx, plan.Lifecycle, failure.StorageUnavailable, err)
	}
	if err := plan.Lifecycle.Complete(context.WithoutCancel(ctx)); err != nil {
		return ExecuteResult{}, err
	}
	return ExecuteResult{BytesThisRun: bytesRun, Committed: committed, Blocks: block - plan.StartBlockIndex}, nil
}
