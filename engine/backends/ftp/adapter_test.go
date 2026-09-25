package ftpbackend

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
	"github.com/subhra74/xdm/engine/transfer/checksum"
	"github.com/subhra74/xdm/engine/transfer/retry"
)

type fakeSecrets map[domainrequest.SecretReference][]byte

func (s fakeSecrets) ResolveSecret(_ context.Context, ref domainrequest.SecretReference) ([]byte, error) {
	raw, ok := s[ref]
	if !ok {
		return nil, errors.New("missing")
	}
	return append([]byte(nil), raw...), nil
}

type fakeSession struct {
	data           []byte
	size           int64
	loginUser      string
	loginPassword  string
	wantUser       string
	wantPassword   string
	retrieveOffset int64
	loginErr       error
	sizeErr        error
	retrieveErr    error
}

func (s *fakeSession) Login(_ context.Context, user, password string) error {
	s.loginUser, s.loginPassword = user, password
	if s.loginErr != nil {
		return s.loginErr
	}
	if s.wantUser != "" && (user != s.wantUser || password != s.wantPassword) {
		return ErrAuthentication
	}
	return nil
}
func (s *fakeSession) Size(context.Context, string) (int64, error) {
	if s.sizeErr != nil {
		return 0, s.sizeErr
	}
	if s.size != 0 || len(s.data) == 0 {
		return s.size, nil
	}
	return int64(len(s.data)), nil
}
func (s *fakeSession) Retrieve(_ context.Context, _ string, offset int64) (io.ReadCloser, error) {
	s.retrieveOffset = offset
	if s.retrieveErr != nil {
		return nil, s.retrieveErr
	}
	if offset < 0 || offset > int64(len(s.data)) {
		return nil, ErrProtocol
	}
	return io.NopCloser(bytes.NewReader(s.data[offset:])), nil
}
func (s *fakeSession) Close() error { return nil }

type fakeDialer struct {
	session  *fakeSession
	endpoint Endpoint
	err      error
}

func (d *fakeDialer) Dial(_ context.Context, ep Endpoint) (Session, error) {
	d.endpoint = ep
	if d.err != nil {
		return nil, d.err
	}
	return d.session, nil
}

type fakeLifecycle struct {
	state  string
	failed *failure.Failure
}

func (l *fakeLifecycle) Start(context.Context) error    { l.state = "running"; return nil }
func (l *fakeLifecycle) Complete(context.Context) error { l.state = "transport_complete"; return nil }
func (l *fakeLifecycle) Pause(context.Context) error    { l.state = "paused"; return nil }
func (l *fakeLifecycle) Fail(_ context.Context, f failure.Failure) error {
	l.state = "failed"
	l.failed = &f
	return nil
}

type fileCommitter struct{}

func (fileCommitter) CommitBlock(_ context.Context, f checkpoint.File, req checkpoint.CommitRequest) (store.CheckpointBlockRecord, error) {
	n, err := f.WriteAt(req.Data, req.StartByte)
	if err != nil {
		return store.CheckpointBlockRecord{}, err
	}
	if n != len(req.Data) {
		return store.CheckpointBlockRecord{}, io.ErrShortWrite
	}
	if err = f.Sync(); err != nil {
		return store.CheckpointBlockRecord{}, err
	}
	h := sha256.Sum256(req.Data)
	return store.CheckpointBlockRecord{DownloadID: req.DownloadID, Generation: req.Generation, BlockIndex: req.BlockIndex, StartByte: req.StartByte, CommittedLength: int64(len(req.Data)), HashAlgorithm: "sha256", HashHex: hex.EncodeToString(h[:]), State: "committed"}, nil
}

type countResources struct {
	waits        int
	acquisitions int
}

func (r *countResources) WaitN(_ context.Context, n int) error { r.waits += n; return nil }
func (r *countResources) AcquireConnection(context.Context) (func(), error) {
	r.acquisitions++
	return func() {}, nil
}

func testResource(t *testing.T) resource.Identity {
	t.Helper()
	r, e := resource.DeriveIdentity("ftp-test", "resource")
	if e != nil {
		t.Fatal(e)
	}
	return r
}
func testIntent(t *testing.T, raw string, size *int64) domainrequest.NetworkIntent {
	t.Helper()
	in, e := domainrequest.NewNetworkIntent(domainrequest.NetworkIntent{TransportURL: raw, Resource: testResource(t), Method: "GET", ExpectedLength: size})
	if e != nil {
		t.Fatal(e)
	}
	return in
}
func testIDs(t *testing.T) (identity.DownloadID, identity.AttemptGeneration) {
	t.Helper()
	dl, e := identity.ParseDownloadID("dl_00000000000000000000000000000037")
	if e != nil {
		t.Fatal(e)
	}
	g, e := identity.NewAttemptGeneration(1)
	if e != nil {
		t.Fatal(e)
	}
	return dl, g
}
func tempFile(t *testing.T) *os.File {
	t.Helper()
	f, e := os.OpenFile(filepath.Join(t.TempDir(), "stage.bin"), os.O_CREATE|os.O_RDWR, 0o600)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { _ = f.Close() })
	return f
}

func executeFake(t *testing.T, factory *Factory, sess *fakeSession, offset int64) (ExecuteResult, *fakeLifecycle, *os.File, error) {
	t.Helper()
	dl, g := testIDs(t)
	f := tempFile(t)
	if offset > 0 {
		if _, e := f.WriteAt(sess.data[:offset], 0); e != nil {
			t.Fatal(e)
		}
	}
	lifecycle := &fakeLifecycle{}
	resources := &countResources{}
	d := &fakeDialer{session: sess}
	result, err := Execute(context.Background(), ExecutePlan{Factory: factory, Dialer: d, DownloadID: dl, Generation: g, StartOffset: offset, StartBlockIndex: offset / 4, BufferBytes: 4 << 10, CheckpointBytes: 4 << 10, File: f, Committer: fileCommitter{}, Lifecycle: lifecycle, Resources: resources})
	if resources.acquisitions != 1 && err == nil {
		t.Fatalf("connection acquisitions=%d", resources.acquisitions)
	}
	return result, lifecycle, f, err
}

func TestFactoryAnonymousAndAuthenticatedCredentials(t *testing.T) {
	size := int64(4)
	anon := testIntent(t, "ftp://example.test/file.bin", &size)
	f, e := NewFactory(anon, nil)
	if e != nil {
		t.Fatal(e)
	}
	creds, e := f.credentials(context.Background())
	if e != nil {
		t.Fatal(e)
	}
	if !creds.Anonymous || creds.Username != "anonymous" {
		t.Fatalf("anon=%+v", creds)
	}
	ref, e := domainrequest.NewSecretReference("secret/ftp/password")
	if e != nil {
		t.Fatal(e)
	}
	auth := anon
	auth.Credentials = []domainrequest.CredentialReference{{Kind: domainrequest.CredentialFTPPassword, Principal: "mikey", Ref: ref, Scope: domainrequest.CredentialScope{Origin: "ftp://example.test", Resource: auth.Resource}}}
	auth, e = domainrequest.NewNetworkIntent(auth)
	if e != nil {
		t.Fatal(e)
	}
	f, e = NewFactory(auth, fakeSecrets{ref: []byte("private")})
	if e != nil {
		t.Fatal(e)
	}
	creds, e = f.credentials(context.Background())
	if e != nil {
		t.Fatal(e)
	}
	if creds.Username != "mikey" || creds.Password != "private" || creds.Anonymous {
		t.Fatalf("auth=%+v", creds)
	}
}

func TestFTPSAndResume(t *testing.T) {
	data := []byte("abcdefgh")
	size := int64(len(data))
	in := testIntent(t, "ftps://secure.test/file.bin", &size)
	f, e := NewFactory(in, nil)
	if e != nil {
		t.Fatal(e)
	}
	if !f.Endpoint().TLS() || f.Endpoint().Port != "990" {
		t.Fatalf("endpoint=%+v", f.Endpoint())
	}
	sess := &fakeSession{data: data, size: size}
	result, lifecycle, file, err := executeFake(t, f, sess, 4)
	if err != nil {
		t.Fatal(err)
	}
	if sess.retrieveOffset != 4 || result.Committed != size || lifecycle.state != "transport_complete" {
		t.Fatalf("result=%+v state=%s off=%d", result, lifecycle.state, sess.retrieveOffset)
	}
	got := make([]byte, len(data))
	if _, e = file.ReadAt(got, 0); e != nil && !errors.Is(e, io.EOF) {
		t.Fatal(e)
	}
	if !bytes.Equal(got, data) {
		t.Fatalf("got=%q", got)
	}
}

func TestWrongSizeIsRepresentationFailure(t *testing.T) {
	expected := int64(9)
	in := testIntent(t, "ftp://example.test/file.bin", &expected)
	f, _ := NewFactory(in, nil)
	sess := &fakeSession{data: []byte("1234"), size: 4}
	_, l, _, err := executeFake(t, f, sess, 0)
	if err == nil {
		t.Fatal("expected error")
	}
	if l.failed == nil || l.failed.Category != failure.RepresentationChanged {
		t.Fatalf("failure=%+v err=%v", l.failed, err)
	}
}

func TestDisconnectUsesRetryTaxonomy(t *testing.T) {
	size := int64(4)
	in := testIntent(t, "ftp://example.test/file.bin", &size)
	f, _ := NewFactory(in, nil)
	sess := &fakeSession{size: size, retrieveErr: io.ErrUnexpectedEOF}
	_, l, _, err := executeFake(t, f, sess, 0)
	if err == nil {
		t.Fatal("expected error")
	}
	if l.failed == nil || l.failed.Category != failure.NetworkUnavailable {
		t.Fatalf("failure=%+v", l.failed)
	}
	decision, e := (retry.Engine{}).Decide(retry.Input{Failure: *l.failed, AttemptCount: 1, Queue: retry.QueuePolicy{Enabled: true, MaxAttempts: 3, BaseDelay: time.Millisecond}, NetworkAvailable: true, Replayable: true})
	if e != nil {
		t.Fatal(e)
	}
	if decision.Kind != retry.RetryAt {
		t.Fatalf("decision=%+v", decision)
	}
}

func TestChecksumAfterFTPTransfer(t *testing.T) {
	data := []byte("ftp-checksum-payload")
	size := int64(len(data))
	in := testIntent(t, "ftp://example.test/file.bin", &size)
	f, _ := NewFactory(in, nil)
	sess := &fakeSession{data: data, size: size}
	_, l, file, err := executeFake(t, f, sess, 0)
	if err != nil {
		t.Fatal(err)
	}
	if l.state != "transport_complete" {
		t.Fatalf("state=%s", l.state)
	}
	if _, e := file.Seek(0, io.SeekStart); e != nil {
		t.Fatal(e)
	}
	sum := sha256.Sum256(data)
	expected, e := checksum.NormalizeExpected(checksum.SourceUser, "sha256", hex.EncodeToString(sum[:]))
	if e != nil {
		t.Fatal(e)
	}
	result, e := checksum.Verify(context.Background(), file, &expected, 0)
	if e != nil {
		t.Fatal(e)
	}
	if !result.Matched || result.Bytes != size {
		t.Fatalf("verify=%+v", result)
	}
}

func TestUnsupportedRESTIsExplicitBackendFailure(t *testing.T) {
	f := classify(&UnsupportedFeatureError{Feature: "REST", Code: 502})
	if f.Category != failure.BackendUnavailable {
		t.Fatalf("failure=%+v", f)
	}
}

func TestProbeUsesCanonicalCredentialsAndClassifiesUnsupportedSize(t *testing.T) {
	data := []byte("probe")
	n := int64(len(data))
	in := testIntent(t, "ftp://example.test/file.bin", &n)
	factory, err := NewFactory(in, nil)
	if err != nil {
		t.Fatal(err)
	}
	sess := &fakeSession{data: data, size: n}
	dialer := &fakeDialer{session: sess}
	probe, err := factory.Probe(context.Background(), dialer)
	if err != nil {
		t.Fatal(err)
	}
	if probe.Size != n || !probe.Resumable || probe.Secure || sess.loginUser != "anonymous" {
		t.Fatalf("probe=%+v user=%q", probe, sess.loginUser)
	}

	sess = &fakeSession{sizeErr: &UnsupportedFeatureError{Feature: "SIZE", Code: 502}}
	dialer = &fakeDialer{session: sess}
	_, err = factory.Probe(context.Background(), dialer)
	var typed failure.Failure
	if !errors.As(err, &typed) || typed.Category != failure.BackendUnavailable {
		t.Fatalf("err=%v typed=%+v", err, typed)
	}
}

func TestExecuteClassifiesUnsupportedResumeFeature(t *testing.T) {
	data := []byte("abcdef")
	n := int64(len(data))
	factory, _ := NewFactory(testIntent(t, "ftp://example.test/file.bin", &n), nil)
	sess := &fakeSession{data: data, size: n, retrieveErr: &UnsupportedFeatureError{Feature: "REST", Code: 502}}
	_, lifecycle, _, err := executeFake(t, factory, sess, 2)
	if err == nil || lifecycle.failed == nil || lifecycle.failed.Category != failure.BackendUnavailable {
		t.Fatalf("err=%v failure=%+v", err, lifecycle.failed)
	}
}
