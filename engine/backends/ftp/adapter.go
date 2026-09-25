package ftpbackend

import (
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/security/credentials"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

var (
	ErrInvalidPlan           = errors.New("invalid ftp transfer plan")
	ErrUnsupportedTransport  = errors.New("ftp backend requires ftp or ftps transport")
	ErrUnsupportedMethod     = errors.New("ftp backend supports bodyless GET only")
	ErrCredentialUnavailable = errors.New("ftp credential material is unavailable")
	ErrAmbiguousCredentials  = errors.New("multiple ftp credential references matched destination")
	ErrSizeMismatch          = errors.New("ftp representation size mismatch")
)

type SecretProvider interface {
	ResolveSecret(context.Context, domainrequest.SecretReference) ([]byte, error)
}

type ResourceLimiter interface {
	WaitN(context.Context, int) error
	AcquireConnection(context.Context) (func(), error)
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

type Credentials struct {
	Username  string
	Password  string
	Anonymous bool
}

type Factory struct {
	intent   domainrequest.NetworkIntent
	secrets  SecretProvider
	endpoint Endpoint
	path     string
}

func NewFactory(in domainrequest.NetworkIntent, secrets SecretProvider) (*Factory, error) {
	normalized, err := domainrequest.NewNetworkIntent(in)
	if err != nil {
		return nil, err
	}
	if normalized.Method != "GET" || normalized.Body != nil {
		return nil, ErrUnsupportedMethod
	}
	ep, path, err := ParseEndpoint(normalized.TransportURL)
	if err != nil {
		return nil, ErrUnsupportedTransport
	}
	return &Factory{intent: normalized, secrets: secrets, endpoint: ep, path: path}, nil
}
func (f *Factory) Endpoint() Endpoint {
	if f == nil {
		return Endpoint{}
	}
	return f.endpoint
}
func (f *Factory) Path() string {
	if f == nil {
		return ""
	}
	return f.path
}
func (f *Factory) Replayable() bool         { return f != nil }
func (f *Factory) RangeResumeAllowed() bool { return f != nil }

func (f *Factory) credentials(ctx context.Context) (Credentials, error) {
	if f == nil {
		return Credentials{}, ErrInvalidPlan
	}
	refs, err := credentials.ForDestination(f.intent.Credentials, f.intent.TransportURL, f.intent.Resource)
	if err != nil {
		return Credentials{}, err
	}
	ftpRefs := make([]domainrequest.CredentialReference, 0, 1)
	for _, ref := range refs {
		if ref.Kind == domainrequest.CredentialFTPPassword {
			ftpRefs = append(ftpRefs, ref)
		}
	}
	if len(ftpRefs) == 0 {
		return Credentials{Username: "anonymous", Password: "anonymous@", Anonymous: true}, nil
	}
	if len(ftpRefs) != 1 {
		return Credentials{}, ErrAmbiguousCredentials
	}
	ref := ftpRefs[0]
	if strings.TrimSpace(ref.Principal) == "" || f.secrets == nil {
		return Credentials{}, ErrCredentialUnavailable
	}
	raw, err := f.secrets.ResolveSecret(ctx, ref.Ref)
	if err != nil || len(raw) == 0 {
		return Credentials{}, ErrCredentialUnavailable
	}
	password := string(raw)
	for i := range raw {
		raw[i] = 0
	}
	if strings.ContainsAny(password, "\r\n") {
		return Credentials{}, ErrCredentialUnavailable
	}
	return Credentials{Username: ref.Principal, Password: password}, nil
}

type ProbeResult struct {
	Size      int64 `json:"size"`
	Resumable bool  `json:"resumable"`
	Secure    bool  `json:"secure"`
}

func (f *Factory) Probe(ctx context.Context, dialer Dialer) (ProbeResult, error) {
	if f == nil || dialer == nil {
		return ProbeResult{}, ErrInvalidPlan
	}
	creds, err := f.credentials(ctx)
	if err != nil {
		return ProbeResult{}, typedError(failure.AuthenticationRequired, err)
	}
	session, err := dialer.Dial(ctx, f.endpoint)
	if err != nil {
		return ProbeResult{}, classify(err)
	}
	defer session.Close()
	if err = session.Login(ctx, creds.Username, creds.Password); err != nil {
		return ProbeResult{}, classify(err)
	}
	size, err := session.Size(ctx, f.path)
	if err != nil {
		return ProbeResult{}, classify(err)
	}
	return ProbeResult{Size: size, Resumable: true, Secure: f.endpoint.TLS()}, nil
}

type ExecutePlan struct {
	Factory         *Factory
	Dialer          Dialer
	DownloadID      identity.DownloadID
	Generation      identity.AttemptGeneration
	StartOffset     int64
	StartBlockIndex int64
	BufferBytes     int
	CheckpointBytes int
	File            checkpoint.File
	Committer       BlockCommitter
	Lifecycle       Lifecycle
	Resources       ResourceLimiter
	Progress        ProgressSink
	PauseRequested  func() bool
}

type ExecuteResult struct {
	BytesThisRun int64 `json:"bytes_this_run"`
	Committed    int64 `json:"committed"`
	Blocks       int64 `json:"blocks"`
	RemoteSize   int64 `json:"remote_size"`
}

func typedError(category failure.Category, cause error) failure.Failure {
	f, err := failure.NewDefault(category, cause)
	if err != nil {
		panic(err)
	}
	return f
}
func classify(err error) failure.Failure {
	category := failure.NetworkUnavailable
	switch {
	case err == nil:
		category = failure.InternalFailure
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		category = failure.Cancelled
	case errors.Is(err, ErrAuthentication), errors.Is(err, ErrCredentialUnavailable), errors.Is(err, ErrAmbiguousCredentials):
		category = failure.AuthenticationRequired
	case errors.Is(err, ErrTLS):
		category = failure.TLSFailure
	case errors.Is(err, ErrUnsupportedFeature):
		category = failure.BackendUnavailable
	case errors.Is(err, ErrSizeMismatch):
		category = failure.RepresentationChanged
	case errors.Is(err, ErrUnsupportedMethod), errors.Is(err, ErrUnsupportedTransport), errors.Is(err, ErrInvalidPlan):
		category = failure.InvalidRequest
	}
	return typedError(category, err)
}
func fail(ctx context.Context, lifecycle Lifecycle, err error) error {
	f := classify(err)
	if lifecycle != nil {
		_ = lifecycle.Fail(context.WithoutCancel(ctx), f)
	}
	return f
}

func Execute(ctx context.Context, plan ExecutePlan) (ExecuteResult, error) {
	if plan.Factory == nil || plan.Dialer == nil || plan.File == nil || plan.Committer == nil || plan.Lifecycle == nil || plan.DownloadID.IsZero() || !plan.Generation.Valid() || plan.StartOffset < 0 || plan.StartBlockIndex < 0 {
		return ExecuteResult{}, ErrInvalidPlan
	}
	bufferSize := plan.BufferBytes
	if bufferSize == 0 {
		bufferSize = 64 << 10
	}
	checkpointBytes := plan.CheckpointBytes
	if checkpointBytes == 0 {
		checkpointBytes = 1 << 20
	}
	if bufferSize < 4<<10 || bufferSize > 1<<20 || checkpointBytes < bufferSize || checkpointBytes > 16<<20 {
		return ExecuteResult{}, ErrInvalidPlan
	}
	if err := plan.Lifecycle.Start(ctx); err != nil {
		return ExecuteResult{}, err
	}
	creds, err := plan.Factory.credentials(ctx)
	if err != nil {
		return ExecuteResult{}, fail(ctx, plan.Lifecycle, err)
	}
	release := func() {}
	if plan.Resources != nil {
		release, err = plan.Resources.AcquireConnection(ctx)
		if err != nil {
			return ExecuteResult{}, fail(ctx, plan.Lifecycle, err)
		}
		if release == nil {
			return ExecuteResult{}, fail(ctx, plan.Lifecycle, ErrInvalidPlan)
		}
	}
	defer release()
	session, err := plan.Dialer.Dial(ctx, plan.Factory.endpoint)
	if err != nil {
		return ExecuteResult{}, fail(ctx, plan.Lifecycle, err)
	}
	defer session.Close()
	if err = session.Login(ctx, creds.Username, creds.Password); err != nil {
		return ExecuteResult{}, fail(ctx, plan.Lifecycle, err)
	}
	remoteSize, err := session.Size(ctx, plan.Factory.path)
	if err != nil {
		return ExecuteResult{}, fail(ctx, plan.Lifecycle, err)
	}
	if plan.Factory.intent.ExpectedLength != nil && remoteSize != *plan.Factory.intent.ExpectedLength {
		return ExecuteResult{}, fail(ctx, plan.Lifecycle, fmt.Errorf("%w: remote=%d expected=%d", ErrSizeMismatch, remoteSize, *plan.Factory.intent.ExpectedLength))
	}
	if plan.StartOffset > remoteSize {
		return ExecuteResult{}, fail(ctx, plan.Lifecycle, fmt.Errorf("%w: resume=%d remote=%d", ErrSizeMismatch, plan.StartOffset, remoteSize))
	}
	body, err := session.Retrieve(ctx, plan.Factory.path, plan.StartOffset)
	if err != nil {
		return ExecuteResult{}, fail(ctx, plan.Lifecycle, err)
	}
	defer body.Close()

	buf := make([]byte, bufferSize)
	pending := make([]byte, 0, checkpointBytes)
	committed := plan.StartOffset
	block := plan.StartBlockIndex
	bytesRun := int64(0)
	total := remoteSize
	commit := func() error {
		if len(pending) == 0 {
			return nil
		}
		if committed+int64(len(pending)) > total {
			return io.ErrUnexpectedEOF
		}
		rec, e := plan.Committer.CommitBlock(ctx, plan.File, checkpoint.CommitRequest{DownloadID: plan.DownloadID, Generation: plan.Generation, BlockIndex: block, StartByte: committed, Data: append([]byte(nil), pending...), NowUnixMS: time.Now().UnixMilli()})
		if e != nil {
			return e
		}
		delta := rec.CommittedLength
		committed += delta
		block++
		pending = pending[:0]
		if plan.Progress != nil {
			t := total
			plan.Progress.OnProgress(Progress{Committed: committed, Total: &t, Delta: delta})
		}
		return nil
	}
	for {
		if plan.PauseRequested != nil && plan.PauseRequested() {
			if err = commit(); err != nil {
				return ExecuteResult{}, fail(ctx, plan.Lifecycle, err)
			}
			if err = plan.Lifecycle.Pause(context.WithoutCancel(ctx)); err != nil {
				return ExecuteResult{}, err
			}
			return ExecuteResult{BytesThisRun: bytesRun, Committed: committed, Blocks: block - plan.StartBlockIndex, RemoteSize: remoteSize}, nil
		}
		n, readErr := body.Read(buf)
		if n > 0 {
			if plan.Resources != nil {
				if err = plan.Resources.WaitN(ctx, n); err != nil {
					return ExecuteResult{}, fail(ctx, plan.Lifecycle, err)
				}
			}
			pending = append(pending, buf[:n]...)
			bytesRun += int64(n)
			for len(pending) >= checkpointBytes {
				chunk := append([]byte(nil), pending[:checkpointBytes]...)
				pending = append([]byte(nil), pending[checkpointBytes:]...)
				rec, e := plan.Committer.CommitBlock(ctx, plan.File, checkpoint.CommitRequest{DownloadID: plan.DownloadID, Generation: plan.Generation, BlockIndex: block, StartByte: committed, Data: chunk, NowUnixMS: time.Now().UnixMilli()})
				if e != nil {
					return ExecuteResult{}, fail(ctx, plan.Lifecycle, e)
				}
				committed += rec.CommittedLength
				block++
				if plan.Progress != nil {
					t := total
					plan.Progress.OnProgress(Progress{Committed: committed, Total: &t, Delta: rec.CommittedLength})
				}
			}
		}
		if readErr != nil {
			if errors.Is(readErr, io.EOF) {
				break
			}
			return ExecuteResult{}, fail(ctx, plan.Lifecycle, readErr)
		}
	}
	if err = body.Close(); err != nil {
		return ExecuteResult{}, fail(ctx, plan.Lifecycle, err)
	}
	if err = commit(); err != nil {
		return ExecuteResult{}, fail(ctx, plan.Lifecycle, err)
	}
	if committed != remoteSize {
		return ExecuteResult{}, fail(ctx, plan.Lifecycle, fmt.Errorf("%w: committed=%d remote=%d", ErrSizeMismatch, committed, remoteSize))
	}
	if err = plan.Lifecycle.Complete(context.WithoutCancel(ctx)); err != nil {
		return ExecuteResult{}, err
	}
	return ExecuteResult{BytesThisRun: bytesRun, Committed: committed, Blocks: block - plan.StartBlockIndex, RemoteSize: remoteSize}, nil
}
