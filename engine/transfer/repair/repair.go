package repair

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
	"github.com/subhra74/xdm/engine/transfer/checksum"
	httptransfer "github.com/subhra74/xdm/engine/transfer/http"
)

var (
	ErrNoRepairEvidence      = errors.New("no selective repair evidence")
	ErrRepresentationChanged = errors.New("representation changed during selective repair")
	ErrRepairIncomplete      = errors.New("selective repair did not restore artifact integrity")
)

type Plan struct {
	DamagedBlocks []int64                  `json:"damaged_blocks"`
	Ranges        []httptransfer.ByteRange `json:"ranges"`
	Bytes         int64                    `json:"bytes"`
}

func BuildPlan(ctx context.Context, repo *store.Repository, file httptransfer.StagingFile, downloadID identity.DownloadID, generation identity.AttemptGeneration, previous, current httptransfer.Representation, policy httptransfer.ResumePolicy) (Plan, error) {
	if repo == nil || file == nil || current.Length == nil {
		return Plan{}, ErrNoRepairEvidence
	}
	if d := httptransfer.CanResume(previous, current, policy); !d.Allowed {
		return Plan{}, fmt.Errorf("%w: %s", ErrRepresentationChanged, d.Reason)
	}
	inspections, err := checkpoint.Inspect(ctx, repo, file, downloadID, generation)
	if err != nil {
		return Plan{}, err
	}
	plan := Plan{}
	for _, in := range inspections {
		if in.Block.State != "committed" || in.Valid {
			continue
		}
		end := in.Block.StartByte + in.Block.CommittedLength - 1
		if end >= *current.Length {
			end = *current.Length - 1
		}
		if end < in.Block.StartByte {
			continue
		}
		plan.DamagedBlocks = append(plan.DamagedBlocks, in.Block.BlockIndex)
		plan.Ranges = append(plan.Ranges, httptransfer.ByteRange{Start: in.Block.StartByte, End: end})
		plan.Bytes += end - in.Block.StartByte + 1
	}
	if len(plan.DamagedBlocks) == 0 {
		return Plan{}, ErrNoRepairEvidence
	}
	return plan, nil
}

type ExecuteRequest struct {
	URL         string
	Previous    httptransfer.Representation
	Current     httptransfer.Representation
	Policy      httptransfer.ResumePolicy
	DownloadID  identity.DownloadID
	Generation  identity.AttemptGeneration
	BlockBytes  int64
	BufferBytes int
	File        httptransfer.StagingFile
	Committer   httptransfer.BlockCommitter
	Repository  *store.Repository
	Lifecycle   httptransfer.Lifecycle
	Resources   httptransfer.ResourceLimiter
	Expected    checksum.Expected
}

type Result struct {
	Plan         Plan            `json:"plan"`
	FetchedBytes int64           `json:"fetched_bytes"`
	Verification checksum.Result `json:"verification"`
}

func Execute(ctx context.Context, client httptransfer.Doer, req ExecuteRequest) (Result, error) {
	plan, err := BuildPlan(ctx, req.Repository, req.File, req.DownloadID, req.Generation, req.Previous, req.Current, req.Policy)
	if err != nil {
		return Result{}, err
	}
	// Representation compatibility is established before durable invalidation.
	if err = req.Repository.InvalidateCheckpointBlocks(ctx, req.DownloadID, req.Generation, plan.DamagedBlocks, time.Now().UnixMilli()); err != nil {
		return Result{}, err
	}
	transfer, err := httptransfer.ExecuteResume(ctx, client, httptransfer.ResumeExecutePlan{
		URL: req.URL, Representation: req.Current, DownloadID: req.DownloadID, Generation: req.Generation,
		Ranges: plan.Ranges, BlockBytes: req.BlockBytes, BufferBytes: req.BufferBytes, File: req.File,
		Committer: req.Committer, Repository: req.Repository, Lifecycle: req.Lifecycle, Resources: req.Resources,
	})
	if err != nil {
		return Result{Plan: plan, FetchedBytes: transfer.BytesThisRun}, err
	}
	if transfer.BytesThisRun != plan.Bytes {
		return Result{Plan: plan, FetchedBytes: transfer.BytesThisRun}, fmt.Errorf("%w: fetched=%d wanted=%d", ErrRepairIncomplete, transfer.BytesThisRun, plan.Bytes)
	}
	seeker, ok := req.File.(interface {
		Seek(int64, int) (int64, error)
		Read([]byte) (int, error)
	})
	if !ok {
		return Result{Plan: plan, FetchedBytes: transfer.BytesThisRun}, ErrRepairIncomplete
	}
	if _, err = seeker.Seek(0, 0); err != nil {
		return Result{}, err
	}
	verified, err := checksum.Verify(ctx, seeker, &req.Expected, 0)
	if err != nil {
		return Result{Plan: plan, FetchedBytes: transfer.BytesThisRun, Verification: verified}, fmt.Errorf("%w: %v", ErrRepairIncomplete, err)
	}
	return Result{Plan: plan, FetchedBytes: transfer.BytesThisRun, Verification: verified}, nil
}
