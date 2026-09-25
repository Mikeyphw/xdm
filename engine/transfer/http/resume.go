package httptransfer

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

var ErrResumeRestartRequired = errors.New("resume response requires clean representation restart")

type ResumePlan struct {
	Reusable        []ByteRange `json:"reusable"`
	Missing         []ByteRange `json:"missing"`
	Invalidated     []int64     `json:"invalidated_block_indices,omitempty"`
	RestartRequired bool        `json:"restart_required"`
	Complete        bool        `json:"complete"`
}

func mergeRanges(in []ByteRange) []ByteRange {
	if len(in) == 0 {
		return nil
	}
	out := []ByteRange{in[0]}
	for _, r := range in[1:] {
		last := &out[len(out)-1]
		if r.Start <= last.End+1 {
			if r.End > last.End {
				last.End = r.End
			}
		} else {
			out = append(out, r)
		}
	}
	return out
}

func complement(total int64, used []ByteRange) []ByteRange {
	if total <= 0 {
		return nil
	}
	used = mergeRanges(used)
	out := []ByteRange{}
	cursor := int64(0)
	for _, r := range used {
		if r.Start > cursor {
			out = append(out, ByteRange{cursor, r.Start - 1})
		}
		if r.End+1 > cursor {
			cursor = r.End + 1
		}
	}
	if cursor < total {
		out = append(out, ByteRange{cursor, total - 1})
	}
	return out
}

// BuildResumePlan trusts only read-back verified committed checkpoints. Invalid
// evidence is durably revoked before the missing ranges are returned.
func BuildResumePlan(ctx context.Context, repo *store.Repository, file StagingFile, downloadID identity.DownloadID, generation identity.AttemptGeneration, previous, current Representation, policy ResumePolicy) (ResumePlan, error) {
	if repo == nil || file == nil || downloadID.IsZero() || !generation.Valid() || current.Length == nil || *current.Length < 0 {
		return ResumePlan{}, ErrInvalidTransfer
	}
	if err := repo.AssertAuthoritativeWriter(ctx, downloadID, generation); err != nil {
		return ResumePlan{}, err
	}
	blocks, err := repo.ListCheckpointBlocks(ctx, downloadID, generation)
	if err != nil {
		return ResumePlan{}, err
	}
	decision := CanResume(previous, current, policy)
	if !decision.Allowed {
		ids := make([]int64, 0, len(blocks))
		for _, b := range blocks {
			if b.State == "committed" {
				ids = append(ids, b.BlockIndex)
			}
		}
		if err = repo.InvalidateCheckpointBlocks(ctx, downloadID, generation, ids, time.Now().UnixMilli()); err != nil {
			return ResumePlan{}, err
		}
		missing := []ByteRange{}
		if *current.Length > 0 {
			missing = []ByteRange{{0, *current.Length - 1}}
		}
		return ResumePlan{Missing: missing, Invalidated: ids, RestartRequired: true, Complete: *current.Length == 0}, nil
	}
	inspected, err := checkpoint.Inspect(ctx, repo, file, downloadID, generation)
	if err != nil {
		return ResumePlan{}, err
	}
	valid := make([]ByteRange, 0, len(inspected))
	invalid := make([]int64, 0)
	for _, in := range inspected {
		if in.Block.State != "committed" {
			continue
		}
		end := in.Block.StartByte + in.Block.CommittedLength - 1
		if !in.Valid || end >= *current.Length {
			invalid = append(invalid, in.Block.BlockIndex)
			continue
		}
		valid = append(valid, ByteRange{Start: in.Block.StartByte, End: end})
	}
	if len(invalid) > 0 {
		if err = repo.InvalidateCheckpointBlocks(ctx, downloadID, generation, invalid, time.Now().UnixMilli()); err != nil {
			return ResumePlan{}, err
		}
	}
	reusable := mergeRanges(valid)
	missing := complement(*current.Length, reusable)
	return ResumePlan{Reusable: reusable, Missing: missing, Invalidated: invalid, Complete: len(missing) == 0}, nil
}

type ResumeExecutePlan struct {
	URL            string
	Representation Representation
	DownloadID     identity.DownloadID
	Generation     identity.AttemptGeneration
	Ranges         []ByteRange
	BlockBytes     int64
	BufferBytes    int
	File           StagingFile
	Committer      BlockCommitter
	Repository     *store.Repository
	Lifecycle      Lifecycle
	Limiter        Limiter // legacy byte-only hook
	Resources      ResourceLimiter
	Progress       ProgressSink
}

func executeResumeRange(ctx context.Context, client Doer, p ResumeExecutePlan, br ByteRange) (int64, int64, error) {
	req, err := transferRangeRequest(ctx, p.URL, p.Representation, br)
	if err != nil {
		return 0, 0, err
	}
	releaseConnection, err := acquireConnection(ctx, p.Resources)
	if err != nil {
		return 0, 0, err
	}
	defer releaseConnection()
	resp, err := client.Do(req)
	if err != nil {
		return 0, 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		return 0, 0, ErrResumeRestartRequired
	}
	total := int64(0)
	if p.Representation.Length != nil {
		total = *p.Representation.Length
	}
	if err = validateExactRange(resp, br, total); err != nil {
		return 0, 0, err
	}
	buffer := p.BufferBytes
	if buffer == 0 {
		buffer = 64 << 10
	}
	if buffer < 4<<10 || buffer > 1<<20 {
		return 0, 0, ErrInvalidTransfer
	}
	blockSize := p.BlockBytes
	if blockSize == 0 {
		blockSize = checkpoint.DefaultBlockSize
	}
	if blockSize <= 0 {
		return 0, 0, ErrInvalidTransfer
	}
	remaining := br.Length()
	cursor := br.Start
	blockIndex := cursor / blockSize
	pending := make([]byte, 0, blockSize)
	buf := make([]byte, buffer)
	var bytes, blocks int64
	commit := func(data []byte) error {
		rec, e := p.Committer.CommitBlock(ctx, p.File, checkpoint.CommitRequest{DownloadID: p.DownloadID, Generation: p.Generation, BlockIndex: blockIndex, StartByte: cursor, Data: append([]byte(nil), data...), NowUnixMS: time.Now().UnixMilli()})
		if e != nil {
			return e
		}
		cursor += rec.CommittedLength
		blockIndex++
		bytes += rec.CommittedLength
		blocks++
		if p.Progress != nil {
			p.Progress.OnProgress(Progress{Committed: rec.StartByte + rec.CommittedLength, Total: p.Representation.Length, Delta: rec.CommittedLength})
		}
		return nil
	}
	for remaining > 0 {
		want := len(buf)
		if int64(want) > remaining {
			want = int(remaining)
		}
		n, re := resp.Body.Read(buf[:want])
		if n > 0 {
			if e := waitBytes(ctx, p.Resources, p.Limiter, n); e != nil {
				return bytes, blocks, e
			}
			pending = append(pending, buf[:n]...)
			remaining -= int64(n)
			for int64(len(pending)) >= blockSize {
				chunk := append([]byte(nil), pending[:blockSize]...)
				pending = append([]byte(nil), pending[blockSize:]...)
				if e := commit(chunk); e != nil {
					return bytes, blocks, e
				}
			}
		}
		if re != nil {
			if errors.Is(re, io.EOF) {
				break
			}
			return bytes, blocks, re
		}
	}
	if remaining != 0 {
		return bytes, blocks, ErrSegmentShort
	}
	if len(pending) > 0 {
		if err = commit(pending); err != nil {
			return bytes, blocks, err
		}
	}
	return bytes, blocks, nil
}

// ExecuteResume fetches sparse missing ranges only. A 200 response to any
// conditional range invalidates all committed evidence and returns a restart
// signal before response bytes are written.
func ExecuteResume(ctx context.Context, client Doer, p ResumeExecutePlan) (ExecuteResult, error) {
	if client == nil || p.Repository == nil || p.File == nil || p.Committer == nil || p.Lifecycle == nil || p.DownloadID.IsZero() || !p.Generation.Valid() || p.Representation.Length == nil {
		return ExecuteResult{}, ErrInvalidTransfer
	}
	if err := p.Repository.AssertAuthoritativeWriter(ctx, p.DownloadID, p.Generation); err != nil {
		return ExecuteResult{}, err
	}
	if len(p.Ranges) == 0 {
		return ExecuteResult{Committed: *p.Representation.Length}, nil
	}
	if err := p.Lifecycle.Start(ctx); err != nil {
		return ExecuteResult{}, err
	}
	var bytes, blocks int64
	for _, br := range p.Ranges {
		if !br.Valid() || br.End >= *p.Representation.Length {
			return ExecuteResult{}, ErrInvalidTransfer
		}
		b, bl, err := executeResumeRange(ctx, client, p, br)
		if errors.Is(err, ErrResumeRestartRequired) {
			existing, _ := p.Repository.ListCheckpointBlocks(context.WithoutCancel(ctx), p.DownloadID, p.Generation)
			ids := make([]int64, 0, len(existing))
			for _, r := range existing {
				if r.State == "committed" {
					ids = append(ids, r.BlockIndex)
				}
			}
			_ = p.Repository.InvalidateCheckpointBlocks(context.WithoutCancel(ctx), p.DownloadID, p.Generation, ids, time.Now().UnixMilli())
			return ExecuteResult{BytesThisRun: bytes, Blocks: blocks}, failLifecycle(ctx, p.Lifecycle, failure.RepresentationChanged, err)
		}
		if err != nil {
			return ExecuteResult{BytesThisRun: bytes, Blocks: blocks}, failLifecycle(ctx, p.Lifecycle, failure.RangeContradiction, err)
		}
		bytes += b
		blocks += bl
	}
	plan, err := BuildResumePlan(ctx, p.Repository, p.File, p.DownloadID, p.Generation, p.Representation, p.Representation, ResumePolicy{AllowMirrorChange: true, AllowLastModifiedLength: true, AllowLengthOnly: true})
	if err != nil {
		return ExecuteResult{BytesThisRun: bytes, Blocks: blocks}, err
	}
	if !plan.Complete {
		return ExecuteResult{BytesThisRun: bytes, Blocks: blocks}, fmt.Errorf("%w: missing ranges remain", ErrSegmentShort)
	}
	if err = p.Lifecycle.Complete(ctx); err != nil {
		return ExecuteResult{}, err
	}
	return ExecuteResult{BytesThisRun: bytes, Committed: *p.Representation.Length, Blocks: blocks}, nil
}
