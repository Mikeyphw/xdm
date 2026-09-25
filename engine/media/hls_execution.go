package media

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
)

var (
	ErrHLSFetchFailed             = errors.New("hls fetch failed")
	ErrHLSKeyFetchFailed          = errors.New("hls key fetch failed")
	ErrHLSUnsupportedProtection   = errors.New("hls protection unsupported")
	ErrHLSDecryptionFailed        = errors.New("hls decrypt failed")
	ErrHLSExecutionStopped        = errors.New("hls execution stopped")
	ErrHLSExecutionInvalidRequest = errors.New("invalid hls execution request")
)

type HLSFetchKind string

const (
	HLSFetchManifest HLSFetchKind = "manifest"
	HLSFetchSegment  HLSFetchKind = "segment"
	HLSFetchKey      HLSFetchKind = "key"
	HLSFetchInitMap  HLSFetchKind = "init_map"
)

type HLSFetchRequest struct {
	URI           string       `json:"uri"`
	Kind          HLSFetchKind `json:"kind"`
	Range         *ByteRange   `json:"range,omitempty"`
	CredentialRef string       `json:"credential_ref,omitempty"`
}

type HLSFetchResponse struct {
	Bytes []byte `json:"-"`
}

type HLSFetcher interface {
	FetchHLS(ctx context.Context, request HLSFetchRequest) (HLSFetchResponse, error)
}

type HLSExecutionStatus string

const (
	HLSExecutionCompleted HLSExecutionStatus = "completed"
	HLSExecutionLiveWait  HLSExecutionStatus = "live_wait"
	HLSExecutionStopped   HLSExecutionStatus = "stopped"
	HLSExecutionFailed    HLSExecutionStatus = "failed"
)

type HLSFragmentStatus string

const (
	HLSFragmentCommitted   HLSFragmentStatus = "committed"
	HLSFragmentSkipped     HLSFragmentStatus = "skipped"
	HLSFragmentGap         HLSFragmentStatus = "gap"
	HLSFragmentUnsupported HLSFragmentStatus = "unsupported"
	HLSFragmentKeyFailed   HLSFragmentStatus = "key_failed"
	HLSFragmentFetchFailed HLSFragmentStatus = "fetch_failed"
	HLSFragmentDecryptFail HLSFragmentStatus = "decrypt_failed"
)

type HLSExecutionPlan struct {
	BaseURL               string
	Playlists             []string
	AttemptGeneration     int64
	MaxPolls              int
	StopAfterNewFragments int
	CredentialRef         string
}

type HLSFragmentOutcome struct {
	TimelineKey string            `json:"timeline_key"`
	URI         string            `json:"uri"`
	Status      HLSFragmentStatus `json:"status"`
	Reason      string            `json:"reason,omitempty"`
	ArtifactRef string            `json:"artifact_ref,omitempty"`
	Hash        string            `json:"hash,omitempty"`
	Bytes       int               `json:"bytes,omitempty"`
	InitMapURI  string            `json:"init_map_uri,omitempty"`
}

type HLSExecutionResult struct {
	Status           HLSExecutionStatus   `json:"status"`
	Kind             HLSStreamKind        `json:"kind"`
	Polls            int                  `json:"polls"`
	Completed        int                  `json:"completed"`
	Skipped          int                  `json:"skipped"`
	Gaps             int                  `json:"gaps"`
	Historical       int                  `json:"historical"`
	Unsupported      int                  `json:"unsupported"`
	KeyFailures      int                  `json:"key_failures"`
	FetchFailures    int                  `json:"fetch_failures"`
	DecryptFailures  int                  `json:"decrypt_failures"`
	InitMapsFetched  int                  `json:"init_maps_fetched"`
	LiveBoundReached bool                 `json:"live_bound_reached"`
	Outcomes         []HLSFragmentOutcome `json:"outcomes"`
}

type HLSExecutor struct {
	fetcher HLSFetcher
	ledger  *FragmentLedger
}

func NewHLSExecutor(fetcher HLSFetcher, ledger *FragmentLedger) (*HLSExecutor, error) {
	if fetcher == nil {
		return nil, fmt.Errorf("%w: nil fetcher", ErrHLSExecutionInvalidRequest)
	}
	if ledger == nil {
		var err error
		ledger, err = NewFragmentLedger()
		if err != nil {
			return nil, err
		}
	}
	return &HLSExecutor{fetcher: fetcher, ledger: ledger}, nil
}

func (e *HLSExecutor) Ledger() *FragmentLedger { return e.ledger }

func (e *HLSExecutor) Execute(ctx context.Context, plan HLSExecutionPlan) (HLSExecutionResult, error) {
	if e == nil || e.fetcher == nil || e.ledger == nil {
		return HLSExecutionResult{}, fmt.Errorf("%w: nil executor", ErrHLSExecutionInvalidRequest)
	}
	if strings.TrimSpace(plan.BaseURL) == "" || len(plan.Playlists) == 0 {
		return HLSExecutionResult{}, fmt.Errorf("%w: base url and playlists required", ErrHLSExecutionInvalidRequest)
	}
	generation := plan.AttemptGeneration
	if generation <= 0 {
		generation = 1
	}
	maxPolls := plan.MaxPolls
	if maxPolls <= 0 || maxPolls > len(plan.Playlists) {
		maxPolls = len(plan.Playlists)
	}
	result := HLSExecutionResult{Status: HLSExecutionLiveWait}
	newFragments := 0
	for poll := 0; poll < maxPolls; poll++ {
		if err := ctx.Err(); err != nil {
			result.Status = HLSExecutionStopped
			return result, err
		}
		playlist, err := ParseHLSPlaylist(plan.BaseURL, plan.Playlists[poll])
		if err != nil {
			result.Status = HLSExecutionFailed
			return result, err
		}
		result.Polls++
		result.Kind = playlist.KindFromExecution()
		reconciled, err := ReconcileHLSTimeline(e.ledger.Snapshot(), playlist)
		if err != nil {
			result.Status = HLSExecutionFailed
			return result, err
		}
		result.Historical += reconciled.Historical
		for _, entry := range reconciled.Entries {
			if entry.Historical {
				continue
			}
			if !entry.Redownload {
				result.Skipped++
				result.Outcomes = append(result.Outcomes, HLSFragmentOutcome{TimelineKey: entry.Identity.TimelineKey, URI: entry.Segment.ResolvedURI, Status: HLSFragmentSkipped, Reason: "already_committed"})
				continue
			}
			outcome := e.executeSegment(ctx, entry, generation, plan.CredentialRef)
			result.Outcomes = append(result.Outcomes, outcome)
			switch outcome.Status {
			case HLSFragmentCommitted:
				result.Completed++
				newFragments++
			case HLSFragmentGap:
				result.Gaps++
			case HLSFragmentUnsupported:
				result.Unsupported++
			case HLSFragmentKeyFailed:
				result.KeyFailures++
			case HLSFragmentFetchFailed:
				result.FetchFailures++
			case HLSFragmentDecryptFail:
				result.DecryptFailures++
			}
			if outcome.InitMapURI != "" {
				result.InitMapsFetched++
			}
			if plan.StopAfterNewFragments > 0 && newFragments >= plan.StopAfterNewFragments {
				result.Status = HLSExecutionStopped
				result.LiveBoundReached = true
				return result, ErrHLSExecutionStopped
			}
		}
		if playlist.EndList || playlist.PlaylistType == "vod" {
			result.Status = HLSExecutionCompleted
			return result, nil
		}
	}
	result.Status = HLSExecutionLiveWait
	result.LiveBoundReached = true
	return result, nil
}

func (p HLSPlaylist) KindFromExecution() HLSStreamKind { return classifyHLSStream(p) }

func (e *HLSExecutor) executeSegment(ctx context.Context, entry HLSTimelineEntry, generation int64, credentialRef string) HLSFragmentOutcome {
	seg := entry.Segment
	out := HLSFragmentOutcome{TimelineKey: entry.Identity.TimelineKey, URI: seg.ResolvedURI}
	if seg.Gap {
		out.Status = HLSFragmentGap
		out.Reason = "gap"
		_, _ = e.ledger.Upsert(FragmentRecord{Identity: entry.Identity, State: FragmentHistorical, AttemptGeneration: generation, RetryState: "gap"})
		return out
	}
	if seg.Protection == HLSProtectionSampleAES || seg.Protection == HLSProtectionDRM || seg.Protection == HLSProtectionUnknown {
		out.Status = HLSFragmentUnsupported
		out.Reason = string(seg.Protection)
		_, _ = e.ledger.MarkCorrupt(entry.Identity, generation, ErrHLSUnsupportedProtection.Error()+":"+string(seg.Protection))
		return out
	}
	if seg.Map != nil {
		_, err := e.fetcher.FetchHLS(ctx, HLSFetchRequest{URI: seg.Map.ResolvedURI, Kind: HLSFetchInitMap, Range: cloneByteRange(seg.Map.Range), CredentialRef: credentialRef})
		if err != nil {
			out.Status = HLSFragmentFetchFailed
			out.Reason = "init_map"
			_, _ = e.ledger.MarkCorrupt(entry.Identity, generation, "init_map_fetch_failed")
			return out
		}
		out.InitMapURI = seg.Map.ResolvedURI
	}
	body, err := e.fetcher.FetchHLS(ctx, HLSFetchRequest{URI: seg.ResolvedURI, Kind: HLSFetchSegment, Range: cloneByteRange(seg.Range), CredentialRef: credentialRef})
	if err != nil {
		out.Status = HLSFragmentFetchFailed
		out.Reason = "segment"
		_, _ = e.ledger.MarkCorrupt(entry.Identity, generation, ErrHLSFetchFailed.Error())
		return out
	}
	payload := body.Bytes
	if seg.Key != nil && seg.Key.Method == HLSProtectionAES128 {
		key, err := e.fetcher.FetchHLS(ctx, HLSFetchRequest{URI: seg.Key.ResolvedURI, Kind: HLSFetchKey, CredentialRef: credentialRef})
		if err != nil {
			out.Status = HLSFragmentKeyFailed
			out.Reason = "key"
			_, _ = e.ledger.MarkCorrupt(entry.Identity, generation, ErrHLSKeyFetchFailed.Error())
			return out
		}
		payload, err = DecryptHLSAES128(payload, key.Bytes, seg.Key.IV, seg.MediaSequence)
		if err != nil {
			out.Status = HLSFragmentDecryptFail
			out.Reason = err.Error()
			_, _ = e.ledger.MarkCorrupt(entry.Identity, generation, ErrHLSDecryptionFailed.Error())
			return out
		}
	}
	sum := sha256.Sum256(payload)
	hash := "sha256:" + hex.EncodeToString(sum[:])
	artifact := artifactRefForFragment(entry.Identity)
	rec := FragmentRecord{Identity: entry.Identity, Role: "media", State: FragmentCommitted, ExpectedLength: int64(len(payload)), Hash: hash, AttemptGeneration: generation, LocalArtifactRef: artifact, DurableBytesWritten: true}
	if _, err := e.ledger.Commit(rec); err != nil {
		out.Status = HLSFragmentFetchFailed
		out.Reason = err.Error()
		return out
	}
	out.Status = HLSFragmentCommitted
	out.Hash = hash
	out.ArtifactRef = artifact
	out.Bytes = len(payload)
	return out
}

func DecryptHLSAES128(ciphertext, key []byte, ivText string, mediaSequence int64) ([]byte, error) {
	if len(key) != aes.BlockSize {
		return nil, fmt.Errorf("%w: key length", ErrHLSDecryptionFailed)
	}
	if len(ciphertext) == 0 || len(ciphertext)%aes.BlockSize != 0 {
		return nil, fmt.Errorf("%w: ciphertext length", ErrHLSDecryptionFailed)
	}
	iv, err := parseHLSIV(ivText, mediaSequence)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("%w: cipher", ErrHLSDecryptionFailed)
	}
	plain := append([]byte(nil), ciphertext...)
	cipher.NewCBCDecrypter(block, iv).CryptBlocks(plain, plain)
	return pkcs7Unpad(plain, aes.BlockSize)
}

func parseHLSIV(raw string, mediaSequence int64) ([]byte, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		iv := make([]byte, aes.BlockSize)
		for i := 15; i >= 0 && mediaSequence > 0; i-- {
			iv[i] = byte(mediaSequence)
			mediaSequence >>= 8
		}
		return iv, nil
	}
	raw = strings.TrimPrefix(strings.TrimPrefix(raw, "0x"), "0X")
	decoded, err := hex.DecodeString(raw)
	if err != nil || len(decoded) != aes.BlockSize {
		return nil, fmt.Errorf("%w: iv", ErrHLSDecryptionFailed)
	}
	return decoded, nil
}

func pkcs7Unpad(data []byte, blockSize int) ([]byte, error) {
	if len(data) == 0 || len(data)%blockSize != 0 {
		return nil, fmt.Errorf("%w: padding", ErrHLSDecryptionFailed)
	}
	pad := int(data[len(data)-1])
	if pad == 0 || pad > blockSize || pad > len(data) {
		return nil, fmt.Errorf("%w: padding", ErrHLSDecryptionFailed)
	}
	for _, b := range data[len(data)-pad:] {
		if int(b) != pad {
			return nil, fmt.Errorf("%w: padding", ErrHLSDecryptionFailed)
		}
	}
	return data[:len(data)-pad], nil
}

func artifactRefForFragment(identity FragmentIdentity) string {
	id, err := FragmentID(identity)
	if err != nil {
		return "hls/invalid.fragment"
	}
	return "hls/" + id + ".fragment"
}
