package media

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	domainartifact "github.com/subhra74/xdm/engine/domain/artifact"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/publication"
)

const (
	FFmpegPlanVersion      = 1
	MaxToolDiagnosticBytes = 4096
)

var (
	ErrInvalidFFmpegPlan       = errors.New("invalid ffmpeg plan")
	ErrUnsafeToolRequest       = errors.New("unsafe external media tool request")
	ErrInvalidProbeReport      = errors.New("invalid ffprobe report")
	ErrMediaVerificationFailed = errors.New("media verification failed")
	ErrInvalidMediaToolState   = errors.New("invalid external media tool state")
	ErrMediaToolFailed         = errors.New("external media tool failed")
)

type MediaInputRole string

const (
	MediaInputVideo    MediaInputRole = "video"
	MediaInputAudio    MediaInputRole = "audio"
	MediaInputSubtitle MediaInputRole = "subtitle"
	MediaInputMuxed    MediaInputRole = "muxed"
	MediaInputMetadata MediaInputRole = "metadata"
)

type FFmpegPlanMode string

const (
	FFmpegModeRemux     FFmpegPlanMode = "remux"
	FFmpegModeMux       FFmpegPlanMode = "mux"
	FFmpegModeTranscode FFmpegPlanMode = "transcode"
	FFmpegModeCopy      FFmpegPlanMode = "copy"
)

type StreamKind string

const (
	StreamVideo    StreamKind = "video"
	StreamAudio    StreamKind = "audio"
	StreamSubtitle StreamKind = "subtitle"
)

type StreamAction string

const (
	StreamCopy      StreamAction = "copy"
	StreamTranscode StreamAction = "transcode"
	StreamDrop      StreamAction = "drop"
)

type MediaInputArtifact struct {
	Role        MediaInputRole `json:"role"`
	ArtifactRef string         `json:"artifact_ref"`
	Path        string         `json:"path"`
	Container   string         `json:"container,omitempty"`
	Duration    time.Duration  `json:"duration,omitempty"`
	Streams     []MediaStream  `json:"streams"`
}

type MediaStream struct {
	Kind     StreamKind `json:"kind"`
	Index    int        `json:"index"`
	Codec    string     `json:"codec,omitempty"`
	Language string     `json:"language,omitempty"`
}

type StreamMapping struct {
	InputRole   MediaInputRole `json:"input_role"`
	InputIndex  int            `json:"input_index"`
	StreamIndex int            `json:"stream_index"`
	OutputIndex int            `json:"output_index"`
	Kind        StreamKind     `json:"kind"`
	Action      StreamAction   `json:"action"`
	Codec       string         `json:"codec,omitempty"`
	Language    string         `json:"language,omitempty"`
}

type OutputExpectation struct {
	Path             string        `json:"path"`
	Container        string        `json:"container"`
	ExpectedDuration time.Duration `json:"expected_duration,omitempty"`
	RequiredStreams  []StreamKind  `json:"required_streams"`
}

type ExternalToolRequest struct {
	Binary       string        `json:"binary"`
	Args         []string      `json:"args"`
	Timeout      time.Duration `json:"timeout"`
	ExpectedKind string        `json:"expected_kind"`
}

type FFmpegPlan struct {
	Version                 int                  `json:"version"`
	PlanID                  string               `json:"plan_id"`
	Mode                    FFmpegPlanMode       `json:"mode"`
	Inputs                  []MediaInputArtifact `json:"inputs"`
	Mappings                []StreamMapping      `json:"mappings"`
	Output                  OutputExpectation    `json:"output"`
	Tool                    ExternalToolRequest  `json:"tool"`
	DownloadID              string               `json:"download_id,omitempty"`
	ArtifactGeneration      int64                `json:"artifact_generation,omitempty"`
	SourceAttemptGeneration int64                `json:"source_attempt_generation,omitempty"`
	PublicationID           string               `json:"publication_id,omitempty"`
	PublicationStagingID    string               `json:"publication_staging_id,omitempty"`
	Verification            VerificationPolicy   `json:"verification"`
	State                   PostProcessJobState  `json:"state"`
}

type VerificationPolicy struct {
	DurationTolerance time.Duration `json:"duration_tolerance,omitempty"`
}

type PostProcessJobState struct {
	Status         MediaToolStatus `json:"status"`
	ToolJobID      string          `json:"tool_job_id,omitempty"`
	ProgressPermil int             `json:"progress_permil,omitempty"`
	Diagnostics    string          `json:"diagnostics,omitempty"`
	StartedAt      time.Time       `json:"started_at,omitempty"`
	FinishedAt     time.Time       `json:"finished_at,omitempty"`
	RestartCount   int             `json:"restart_count,omitempty"`
}

type MediaToolStatus string

const (
	MediaToolPlanned              MediaToolStatus = "planned"
	MediaToolStartRequested       MediaToolStatus = "start_requested"
	MediaToolRunning              MediaToolStatus = "running"
	MediaToolCanceled             MediaToolStatus = "canceled"
	MediaToolTimedOut             MediaToolStatus = "timed_out"
	MediaToolFailedStatus         MediaToolStatus = "failed"
	MediaToolSucceeded            MediaToolStatus = "succeeded"
	MediaToolVerified             MediaToolStatus = "verified"
	MediaToolPublicationRequested MediaToolStatus = "publication_requested"
	MediaToolPublished            MediaToolStatus = "published"
)

type FFmpegPlanInput struct {
	Mode                    FFmpegPlanMode
	Inputs                  []MediaInputArtifact
	Output                  OutputExpectation
	VideoCodec              string
	AudioCodec              string
	SubtitleCodec           string
	Timeout                 time.Duration
	DownloadID              string
	ArtifactGeneration      int64
	SourceAttemptGeneration int64
	PublicationID           string
	PublicationStagingID    string
}

func NewFFmpegPlan(input FFmpegPlanInput) (FFmpegPlan, error) {
	plan := FFmpegPlan{
		Version:                 FFmpegPlanVersion,
		Mode:                    normalizePlanMode(input.Mode),
		Inputs:                  cloneInputs(input.Inputs),
		Output:                  input.Output,
		DownloadID:              strings.TrimSpace(input.DownloadID),
		ArtifactGeneration:      input.ArtifactGeneration,
		SourceAttemptGeneration: input.SourceAttemptGeneration,
		PublicationID:           strings.TrimSpace(input.PublicationID),
		PublicationStagingID:    strings.TrimSpace(input.PublicationStagingID),
		Verification:            VerificationPolicy{DurationTolerance: 2 * time.Second},
		State:                   PostProcessJobState{Status: MediaToolPlanned},
	}
	if plan.Output.RequiredStreams == nil {
		plan.Output.RequiredStreams = requiredStreamsForInputs(plan.Inputs)
	}
	mappings, err := buildStreamMappings(plan.Mode, plan.Inputs, input.VideoCodec, input.AudioCodec, input.SubtitleCodec)
	if err != nil {
		return FFmpegPlan{}, err
	}
	plan.Mappings = mappings
	args, err := buildFFmpegArgs(plan.Mode, plan.Inputs, mappings, plan.Output)
	if err != nil {
		return FFmpegPlan{}, err
	}
	timeout := input.Timeout
	if timeout <= 0 {
		timeout = 30 * time.Minute
	}
	plan.Tool = ExternalToolRequest{Binary: "ffmpeg", Args: args, Timeout: timeout, ExpectedKind: "ffmpeg"}
	plan.PlanID = computePlanID(plan)
	if err := plan.Validate(); err != nil {
		return FFmpegPlan{}, err
	}
	return plan, nil
}

func (p FFmpegPlan) Validate() error {
	if p.Version != FFmpegPlanVersion {
		return fmt.Errorf("%w: unsupported version", ErrInvalidFFmpegPlan)
	}
	if normalizePlanMode(p.Mode) == "" {
		return fmt.Errorf("%w: mode", ErrInvalidFFmpegPlan)
	}
	if len(p.Inputs) == 0 {
		return fmt.Errorf("%w: inputs", ErrInvalidFFmpegPlan)
	}
	seenPaths := map[string]bool{}
	for _, in := range p.Inputs {
		if normalizeInputRole(in.Role) == "" || strings.TrimSpace(in.ArtifactRef) == "" || strings.TrimSpace(in.Path) == "" || len(in.Streams) == 0 {
			return fmt.Errorf("%w: input", ErrInvalidFFmpegPlan)
		}
		if err := validateToolArg(in.Path); err != nil {
			return err
		}
		if seenPaths[in.Path] {
			return fmt.Errorf("%w: duplicate input path", ErrInvalidFFmpegPlan)
		}
		seenPaths[in.Path] = true
		for _, st := range in.Streams {
			if normalizeStreamKind(st.Kind) == "" || st.Index < 0 {
				return fmt.Errorf("%w: stream", ErrInvalidFFmpegPlan)
			}
		}
	}
	if strings.TrimSpace(p.Output.Path) == "" || strings.TrimSpace(p.Output.Container) == "" || len(p.Output.RequiredStreams) == 0 {
		return fmt.Errorf("%w: output", ErrInvalidFFmpegPlan)
	}
	if err := validateToolArg(p.Output.Path); err != nil {
		return err
	}
	if len(p.Mappings) == 0 {
		return fmt.Errorf("%w: mappings", ErrInvalidFFmpegPlan)
	}
	for _, m := range p.Mappings {
		if normalizeInputRole(m.InputRole) == "" || normalizeStreamKind(m.Kind) == "" || normalizeStreamAction(m.Action) == "" || m.InputIndex < 0 || m.StreamIndex < 0 || m.OutputIndex < 0 {
			return fmt.Errorf("%w: mapping", ErrInvalidFFmpegPlan)
		}
		if p.Mode == FFmpegModeTranscode && m.Action == StreamTranscode && strings.TrimSpace(m.Codec) == "" {
			return fmt.Errorf("%w: transcode codec", ErrInvalidFFmpegPlan)
		}
	}
	if err := p.Tool.Validate(); err != nil {
		return err
	}
	return nil
}

func (r ExternalToolRequest) Validate() error {
	binary := strings.TrimSpace(r.Binary)
	if binary == "" || strings.ContainsAny(binary, " \t\n\r;&|`$<>") || strings.Contains(binary, string(filepath.Separator)) {
		return fmt.Errorf("%w: binary", ErrUnsafeToolRequest)
	}
	if len(r.Args) == 0 {
		return fmt.Errorf("%w: args", ErrUnsafeToolRequest)
	}
	for _, arg := range r.Args {
		if err := validateToolArg(arg); err != nil {
			return err
		}
	}
	if r.Timeout < 0 {
		return fmt.Errorf("%w: timeout", ErrUnsafeToolRequest)
	}
	return nil
}

func validateToolArg(arg string) error {
	if !utf8.ValidString(arg) || strings.ContainsRune(arg, '\x00') || strings.ContainsAny(arg, "\n\r") {
		return fmt.Errorf("%w: argument", ErrUnsafeToolRequest)
	}
	return nil
}

func SerializeFFmpegPlan(plan FFmpegPlan) ([]byte, error) {
	if err := plan.Validate(); err != nil {
		return nil, err
	}
	return json.MarshalIndent(plan, "", "  ")
}

func ParseFFmpegPlan(data []byte) (FFmpegPlan, error) {
	var plan FFmpegPlan
	if err := json.Unmarshal(data, &plan); err != nil {
		return FFmpegPlan{}, err
	}
	if err := plan.Validate(); err != nil {
		return FFmpegPlan{}, err
	}
	if plan.PlanID == "" {
		plan.PlanID = computePlanID(plan)
	}
	return plan, nil
}

func (p FFmpegPlan) RestartSnapshot() (FFmpegPlan, error) {
	data, err := SerializeFFmpegPlan(p)
	if err != nil {
		return FFmpegPlan{}, err
	}
	next, err := ParseFFmpegPlan(data)
	if err != nil {
		return FFmpegPlan{}, err
	}
	next.State.RestartCount++
	switch next.State.Status {
	case MediaToolRunning, MediaToolStartRequested:
		next.State.Status = MediaToolStartRequested
	}
	return next, nil
}

func buildStreamMappings(mode FFmpegPlanMode, inputs []MediaInputArtifact, videoCodec, audioCodec, subtitleCodec string) ([]StreamMapping, error) {
	mode = normalizePlanMode(mode)
	if mode == "" {
		return nil, fmt.Errorf("%w: mode", ErrInvalidFFmpegPlan)
	}
	out := []StreamMapping{}
	outputIndex := 0
	for i, in := range inputs {
		role := normalizeInputRole(in.Role)
		if role == "" {
			return nil, fmt.Errorf("%w: input role", ErrInvalidFFmpegPlan)
		}
		for _, stream := range in.Streams {
			kind := normalizeStreamKind(stream.Kind)
			if kind == "" {
				return nil, fmt.Errorf("%w: stream kind", ErrInvalidFFmpegPlan)
			}
			action := StreamCopy
			codec := strings.TrimSpace(stream.Codec)
			if mode == FFmpegModeTranscode {
				switch kind {
				case StreamVideo:
					codec = strings.TrimSpace(videoCodec)
				case StreamAudio:
					codec = strings.TrimSpace(audioCodec)
				case StreamSubtitle:
					codec = strings.TrimSpace(subtitleCodec)
				}
				if codec == "" {
					return nil, fmt.Errorf("%w: codec for %s", ErrInvalidFFmpegPlan, kind)
				}
				action = StreamTranscode
			}
			out = append(out, StreamMapping{InputRole: role, InputIndex: i, StreamIndex: stream.Index, OutputIndex: outputIndex, Kind: kind, Action: action, Codec: codec, Language: strings.TrimSpace(stream.Language)})
			outputIndex++
		}
	}
	return out, nil
}

func buildFFmpegArgs(mode FFmpegPlanMode, inputs []MediaInputArtifact, mappings []StreamMapping, output OutputExpectation) ([]string, error) {
	args := []string{"-hide_banner", "-nostdin", "-y"}
	for _, in := range inputs {
		args = append(args, "-i", in.Path)
	}
	for _, m := range mappings {
		args = append(args, "-map", fmt.Sprintf("%d:%d", m.InputIndex, m.StreamIndex))
	}
	for _, m := range mappings {
		codecFlag := codecFlagFor(m.Kind, m.OutputIndex)
		codec := "copy"
		if m.Action == StreamTranscode {
			codec = strings.TrimSpace(m.Codec)
		}
		args = append(args, codecFlag, codec)
	}
	if output.Container != "" {
		args = append(args, "-f", strings.ToLower(strings.TrimSpace(output.Container)))
	}
	args = append(args, output.Path)
	return args, nil
}

func codecFlagFor(kind StreamKind, index int) string {
	prefix := "-c"
	switch kind {
	case StreamVideo:
		prefix = "-c:v"
	case StreamAudio:
		prefix = "-c:a"
	case StreamSubtitle:
		prefix = "-c:s"
	}
	return fmt.Sprintf("%s:%d", prefix, index)
}

func requiredStreamsForInputs(inputs []MediaInputArtifact) []StreamKind {
	kinds := map[StreamKind]bool{}
	for _, in := range inputs {
		for _, st := range in.Streams {
			if k := normalizeStreamKind(st.Kind); k != "" {
				kinds[k] = true
			}
		}
	}
	out := make([]StreamKind, 0, len(kinds))
	for k := range kinds {
		out = append(out, k)
	}
	sort.Slice(out, func(i, j int) bool { return out[i] < out[j] })
	return out
}

func computePlanID(plan FFmpegPlan) string {
	copyPlan := plan
	copyPlan.PlanID = ""
	copyPlan.State = PostProcessJobState{}
	copyPlan.Tool.Timeout = 0
	data, _ := json.Marshal(copyPlan)
	sum := sha256.Sum256(data)
	return "ffplan_" + hex.EncodeToString(sum[:16])
}

func cloneInputs(in []MediaInputArtifact) []MediaInputArtifact {
	out := make([]MediaInputArtifact, len(in))
	copy(out, in)
	for i := range out {
		out[i].Role = normalizeInputRole(out[i].Role)
		out[i].Streams = append([]MediaStream(nil), in[i].Streams...)
		for j := range out[i].Streams {
			out[i].Streams[j].Kind = normalizeStreamKind(out[i].Streams[j].Kind)
		}
	}
	return out
}

func normalizePlanMode(mode FFmpegPlanMode) FFmpegPlanMode {
	switch FFmpegPlanMode(strings.ToLower(strings.TrimSpace(string(mode)))) {
	case FFmpegModeRemux, FFmpegModeMux, FFmpegModeTranscode, FFmpegModeCopy:
		return FFmpegPlanMode(strings.ToLower(strings.TrimSpace(string(mode))))
	default:
		return ""
	}
}

func normalizeInputRole(role MediaInputRole) MediaInputRole {
	switch MediaInputRole(strings.ToLower(strings.TrimSpace(string(role)))) {
	case MediaInputVideo, MediaInputAudio, MediaInputSubtitle, MediaInputMuxed, MediaInputMetadata:
		return MediaInputRole(strings.ToLower(strings.TrimSpace(string(role))))
	default:
		return ""
	}
}

func normalizeStreamKind(kind StreamKind) StreamKind {
	switch StreamKind(strings.ToLower(strings.TrimSpace(string(kind)))) {
	case StreamVideo, StreamAudio, StreamSubtitle:
		return StreamKind(strings.ToLower(strings.TrimSpace(string(kind))))
	default:
		return ""
	}
}

func normalizeStreamAction(action StreamAction) StreamAction {
	switch StreamAction(strings.ToLower(strings.TrimSpace(string(action)))) {
	case StreamCopy, StreamTranscode, StreamDrop:
		return StreamAction(strings.ToLower(strings.TrimSpace(string(action))))
	default:
		return ""
	}
}

type FFProbeReport struct {
	Container string          `json:"container"`
	Duration  time.Duration   `json:"duration,omitempty"`
	Size      int64           `json:"size"`
	Streams   []FFProbeStream `json:"streams"`
}

type FFProbeStream struct {
	Kind     StreamKind    `json:"kind"`
	Codec    string        `json:"codec,omitempty"`
	Language string        `json:"language,omitempty"`
	Duration time.Duration `json:"duration,omitempty"`
}

type VerificationResult struct {
	Pass     bool     `json:"pass"`
	Reasons  []string `json:"reasons,omitempty"`
	Artifact string   `json:"artifact,omitempty"`
	Size     int64    `json:"size,omitempty"`
}

func VerifyFFProbe(plan FFmpegPlan, probe FFProbeReport) (VerificationResult, error) {
	if err := plan.Validate(); err != nil {
		return VerificationResult{}, err
	}
	if strings.TrimSpace(probe.Container) == "" || probe.Size < 0 || len(probe.Streams) == 0 {
		return VerificationResult{Pass: false, Reasons: []string{"malformed_probe"}}, ErrInvalidProbeReport
	}
	reasons := []string{}
	if !strings.EqualFold(strings.TrimSpace(probe.Container), strings.TrimSpace(plan.Output.Container)) {
		reasons = append(reasons, "container_mismatch")
	}
	required := map[StreamKind]int{}
	for _, k := range plan.Output.RequiredStreams {
		required[normalizeStreamKind(k)]++
	}
	observed := map[StreamKind]int{}
	for _, st := range probe.Streams {
		if k := normalizeStreamKind(st.Kind); k != "" {
			observed[k]++
		}
	}
	for kind, count := range required {
		if kind == "" || observed[kind] < count {
			reasons = append(reasons, "missing_stream:"+string(kind))
		}
	}
	if plan.Output.ExpectedDuration > 0 && probe.Duration > 0 {
		tolerance := plan.Verification.DurationTolerance
		if tolerance <= 0 {
			tolerance = 2 * time.Second
		}
		delta := probe.Duration - plan.Output.ExpectedDuration
		if delta < 0 {
			delta = -delta
		}
		if delta > tolerance {
			reasons = append(reasons, "duration_mismatch")
		}
	}
	if len(reasons) > 0 {
		return VerificationResult{Pass: false, Reasons: reasons}, ErrMediaVerificationFailed
	}
	return VerificationResult{Pass: true, Artifact: plan.Output.Path, Size: probe.Size}, nil
}

type MediaToolEventKind string

const (
	MediaToolEventStarted  MediaToolEventKind = "started"
	MediaToolEventProgress MediaToolEventKind = "progress"
	MediaToolEventSuccess  MediaToolEventKind = "success"
	MediaToolEventCancel   MediaToolEventKind = "cancel"
	MediaToolEventNonzero  MediaToolEventKind = "nonzero_exit"
	MediaToolEventTimeout  MediaToolEventKind = "timeout"
	MediaToolEventCrash    MediaToolEventKind = "crash"
)

type MediaToolEvent struct {
	Kind           MediaToolEventKind `json:"kind"`
	ToolJobID      string             `json:"tool_job_id,omitempty"`
	ProgressPermil int                `json:"progress_permil,omitempty"`
	ExitCode       int                `json:"exit_code,omitempty"`
	Diagnostics    string             `json:"diagnostics,omitempty"`
	At             time.Time          `json:"at,omitempty"`
}

type MediaPublicationHandoff struct {
	Artifact      domainartifact.Artifact   `json:"-"`
	CommitRequest publication.CommitRequest `json:"commit_request"`
}

func StartMediaTool(plan FFmpegPlan, toolJobID string, now time.Time) (FFmpegPlan, error) {
	if err := plan.Validate(); err != nil {
		return FFmpegPlan{}, err
	}
	if strings.TrimSpace(toolJobID) == "" {
		return FFmpegPlan{}, fmt.Errorf("%w: job id", ErrInvalidMediaToolState)
	}
	plan.State.Status = MediaToolRunning
	plan.State.ToolJobID = strings.TrimSpace(toolJobID)
	plan.State.StartedAt = now
	return plan, nil
}

func ApplyMediaToolEvent(plan FFmpegPlan, event MediaToolEvent) (FFmpegPlan, error) {
	if err := plan.Validate(); err != nil {
		return FFmpegPlan{}, err
	}
	if event.ToolJobID != "" && plan.State.ToolJobID != "" && event.ToolJobID != plan.State.ToolJobID {
		return FFmpegPlan{}, fmt.Errorf("%w: mismatched job id", ErrInvalidMediaToolState)
	}
	at := event.At
	if at.IsZero() {
		at = time.Now().UTC()
	}
	switch event.Kind {
	case MediaToolEventStarted:
		plan.State.Status = MediaToolRunning
		if plan.State.ToolJobID == "" {
			plan.State.ToolJobID = strings.TrimSpace(event.ToolJobID)
		}
		if plan.State.StartedAt.IsZero() {
			plan.State.StartedAt = at
		}
	case MediaToolEventProgress:
		if plan.State.Status != MediaToolRunning {
			return FFmpegPlan{}, fmt.Errorf("%w: progress while %s", ErrInvalidMediaToolState, plan.State.Status)
		}
		if event.ProgressPermil < 0 || event.ProgressPermil > 1000 {
			return FFmpegPlan{}, fmt.Errorf("%w: progress", ErrInvalidMediaToolState)
		}
		if event.ProgressPermil >= plan.State.ProgressPermil {
			plan.State.ProgressPermil = event.ProgressPermil
		}
	case MediaToolEventSuccess:
		if plan.State.Status != MediaToolRunning {
			return FFmpegPlan{}, fmt.Errorf("%w: success while %s", ErrInvalidMediaToolState, plan.State.Status)
		}
		plan.State.Status = MediaToolSucceeded
		plan.State.ProgressPermil = 1000
		plan.State.FinishedAt = at
	case MediaToolEventCancel:
		plan.State.Status = MediaToolCanceled
		plan.State.FinishedAt = at
	case MediaToolEventNonzero:
		plan.State.Status = MediaToolFailedStatus
		plan.State.FinishedAt = at
	case MediaToolEventTimeout:
		plan.State.Status = MediaToolTimedOut
		plan.State.FinishedAt = at
	case MediaToolEventCrash:
		plan.State.Status = MediaToolFailedStatus
		plan.State.FinishedAt = at
	default:
		return FFmpegPlan{}, fmt.Errorf("%w: event", ErrInvalidMediaToolState)
	}
	plan.State.Diagnostics = boundedDiagnostics(plan.State.Diagnostics, event.Diagnostics)
	if plan.State.Status == MediaToolFailedStatus || plan.State.Status == MediaToolTimedOut {
		return plan, ErrMediaToolFailed
	}
	return plan, nil
}

func VerifyMediaToolResult(plan FFmpegPlan, probe FFProbeReport) (FFmpegPlan, VerificationResult, error) {
	if plan.State.Status != MediaToolSucceeded {
		return FFmpegPlan{}, VerificationResult{}, fmt.Errorf("%w: verify while %s", ErrInvalidMediaToolState, plan.State.Status)
	}
	result, err := VerifyFFProbe(plan, probe)
	if err != nil {
		return plan, result, err
	}
	plan.State.Status = MediaToolVerified
	return plan, result, nil
}

func CreatePublicationHandoff(plan FFmpegPlan, probe FFProbeReport) (FFmpegPlan, MediaPublicationHandoff, error) {
	verified, result, err := VerifyMediaToolResult(plan, probe)
	if err != nil {
		return FFmpegPlan{}, MediaPublicationHandoff{}, err
	}
	dl, err := identity.ParseDownloadID(strings.TrimSpace(verified.DownloadID))
	if err != nil {
		return FFmpegPlan{}, MediaPublicationHandoff{}, err
	}
	artifactGen, err := identity.NewArtifactGeneration(verified.ArtifactGeneration)
	if err != nil {
		return FFmpegPlan{}, MediaPublicationHandoff{}, err
	}
	attemptGen, err := identity.NewAttemptGeneration(verified.SourceAttemptGeneration)
	if err != nil {
		return FFmpegPlan{}, MediaPublicationHandoff{}, err
	}
	art, err := domainartifact.NewVerified(dl, artifactGen, attemptGen, result.Size)
	if err != nil {
		return FFmpegPlan{}, MediaPublicationHandoff{}, err
	}
	pubID, err := identity.ParsePublicationID(strings.TrimSpace(verified.PublicationID))
	if err != nil {
		return FFmpegPlan{}, MediaPublicationHandoff{}, err
	}
	idempotency := digestID("media-publication", verified.PlanID+"\x00"+verified.Output.Path)
	staging := strings.TrimSpace(verified.PublicationStagingID)
	if staging == "" {
		staging = verified.Output.Path
	}
	req := publication.CommitRequest{PublicationID: pubID, DownloadID: dl, Artifact: artifactGen, IdempotencyKey: idempotency, StagingIdentity: staging}
	if err := req.Validate(); err != nil {
		return FFmpegPlan{}, MediaPublicationHandoff{}, err
	}
	verified.State.Status = MediaToolPublicationRequested
	return verified, MediaPublicationHandoff{Artifact: art, CommitRequest: req}, nil
}

func MarkMediaPublicationCommitted(plan FFmpegPlan, receipt publication.Receipt) (FFmpegPlan, error) {
	if plan.State.Status != MediaToolPublicationRequested {
		return FFmpegPlan{}, fmt.Errorf("%w: publication while %s", ErrInvalidMediaToolState, plan.State.Status)
	}
	if err := receipt.Validate(); err != nil {
		return FFmpegPlan{}, err
	}
	plan.State.Status = MediaToolPublished
	return plan, nil
}

func RecoverMediaToolJob(plan FFmpegPlan, hostStatus MediaToolStatus) (FFmpegPlan, error) {
	if err := plan.Validate(); err != nil {
		return FFmpegPlan{}, err
	}
	next := plan
	next.State.RestartCount++
	switch plan.State.Status {
	case MediaToolSucceeded:
		return next, nil
	case MediaToolVerified, MediaToolPublicationRequested, MediaToolPublished:
		return next, nil
	case MediaToolRunning, MediaToolStartRequested:
		switch hostStatus {
		case MediaToolSucceeded:
			next.State.Status = MediaToolSucceeded
		case MediaToolRunning:
			next.State.Status = MediaToolRunning
		default:
			next.State.Status = MediaToolStartRequested
			next.State.ToolJobID = ""
		}
	case MediaToolFailedStatus, MediaToolTimedOut:
		next.State.Status = MediaToolStartRequested
		next.State.ToolJobID = ""
	}
	return next, nil
}

func boundedDiagnostics(existing, next string) string {
	combined := strings.TrimSpace(existing)
	if strings.TrimSpace(next) != "" {
		if combined != "" {
			combined += "\n"
		}
		combined += strings.TrimSpace(next)
	}
	if len(combined) <= MaxToolDiagnosticBytes {
		return combined
	}
	return combined[len(combined)-MaxToolDiagnosticBytes:]
}

func PlanContextDone(ctx context.Context) error {
	if ctx == nil {
		return nil
	}
	return ctx.Err()
}
