package checkpoint

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"

	"github.com/subhra74/xdm/engine/domain/identity"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

const DefaultBlockSize int64 = 4 << 20

var ErrInvalidBlock = errors.New("invalid checkpoint block")

type Stage string

const (
	AfterWrite   Stage = "after_write"
	AfterSync    Stage = "after_sync"
	AfterHash    Stage = "after_hash"
	AfterPersist Stage = "after_persist"
)

type FaultHook func(Stage) error

type File interface {
	WriteAt([]byte, int64) (int, error)
	ReadAt([]byte, int64) (int, error)
	Sync() error
	Stat() (os.FileInfo, error)
}

type Committer struct {
	Repository *store.Repository
	Hook       FaultHook
}

type CommitRequest struct {
	DownloadID identity.DownloadID
	Generation identity.AttemptGeneration
	BlockIndex int64
	StartByte  int64
	Data       []byte
	NowUnixMS  int64
}

type Inspection struct {
	Block  store.CheckpointBlockRecord
	Valid  bool
	Reason string
}

func (c Committer) hook(stage Stage) error {
	if c.Hook == nil {
		return nil
	}
	return c.Hook(stage)
}

// CommitBlock owns the durable ordering contract: bytes -> Sync -> read-back
// integrity evidence -> authoritative checkpoint row. A hook failure before
// AfterPersist deliberately leaves no checkpoint row even if bytes reached disk.
func (c Committer) CommitBlock(ctx context.Context, file File, req CommitRequest) (store.CheckpointBlockRecord, error) {
	if c.Repository == nil || file == nil || req.DownloadID.IsZero() || !req.Generation.Valid() || req.BlockIndex < 0 || req.StartByte < 0 || len(req.Data) == 0 {
		return store.CheckpointBlockRecord{}, ErrInvalidBlock
	}
	if err := c.Repository.AssertAuthoritativeWriter(ctx, req.DownloadID, req.Generation); err != nil {
		return store.CheckpointBlockRecord{}, err
	}
	n, err := file.WriteAt(req.Data, req.StartByte)
	if err != nil {
		return store.CheckpointBlockRecord{}, err
	}
	if n != len(req.Data) {
		return store.CheckpointBlockRecord{}, io.ErrShortWrite
	}
	if err = c.hook(AfterWrite); err != nil {
		return store.CheckpointBlockRecord{}, err
	}
	if err = file.Sync(); err != nil {
		return store.CheckpointBlockRecord{}, err
	}
	if err = c.hook(AfterSync); err != nil {
		return store.CheckpointBlockRecord{}, err
	}
	readback := make([]byte, len(req.Data))
	n, err = file.ReadAt(readback, req.StartByte)
	if err != nil && !errors.Is(err, io.EOF) {
		return store.CheckpointBlockRecord{}, err
	}
	if n != len(readback) {
		return store.CheckpointBlockRecord{}, io.ErrUnexpectedEOF
	}
	hash := sha256.Sum256(readback)
	if err = c.hook(AfterHash); err != nil {
		return store.CheckpointBlockRecord{}, err
	}
	rec, err := c.Repository.CommitCheckpointBlock(ctx, store.CheckpointBlockRecord{
		DownloadID:      req.DownloadID,
		Generation:      req.Generation,
		BlockIndex:      req.BlockIndex,
		StartByte:       req.StartByte,
		CommittedLength: int64(len(readback)),
		HashAlgorithm:   "sha256",
		HashHex:         hex.EncodeToString(hash[:]),
		CommittedAtMS:   req.NowUnixMS,
	})
	if err != nil {
		return store.CheckpointBlockRecord{}, err
	}
	if err = c.hook(AfterPersist); err != nil {
		return rec, err
	}
	return rec, nil
}

func Inspect(ctx context.Context, repo *store.Repository, file File, downloadID identity.DownloadID, generation identity.AttemptGeneration) ([]Inspection, error) {
	if repo == nil || file == nil {
		return nil, ErrInvalidBlock
	}
	blocks, err := repo.ListCheckpointBlocks(ctx, downloadID, generation)
	if err != nil {
		return nil, err
	}
	info, err := file.Stat()
	if err != nil {
		return nil, err
	}
	out := make([]Inspection, 0, len(blocks))
	for _, block := range blocks {
		inspection := Inspection{Block: block, Valid: true}
		end := block.StartByte + block.CommittedLength
		if block.StartByte < 0 || block.CommittedLength <= 0 || end > info.Size() {
			inspection.Valid = false
			inspection.Reason = "staging_truncated"
			out = append(out, inspection)
			continue
		}
		buf := make([]byte, int(block.CommittedLength))
		n, readErr := file.ReadAt(buf, block.StartByte)
		if readErr != nil && !errors.Is(readErr, io.EOF) {
			return nil, readErr
		}
		if int64(n) != block.CommittedLength {
			inspection.Valid = false
			inspection.Reason = "staging_truncated"
			out = append(out, inspection)
			continue
		}
		if block.HashAlgorithm != "sha256" {
			inspection.Valid = false
			inspection.Reason = "unsupported_hash"
			out = append(out, inspection)
			continue
		}
		hash := sha256.Sum256(buf)
		if hex.EncodeToString(hash[:]) != block.HashHex {
			inspection.Valid = false
			inspection.Reason = "hash_mismatch"
		}
		out = append(out, inspection)
	}
	return out, nil
}

func ValidateBlockSize(size int64) error {
	if size <= 0 {
		return fmt.Errorf("%w: block size must be positive", ErrInvalidBlock)
	}
	return nil
}
