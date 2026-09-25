package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"time"

	ftpbackend "github.com/subhra74/xdm/engine/backends/ftp"
	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/store/checkpoint"
	store "github.com/subhra74/xdm/engine/store/sqlite"
	"github.com/subhra74/xdm/engine/transfer/checksum"
	"github.com/subhra74/xdm/engine/transfer/retry"
)

type ftpLabSession struct {
	data        []byte
	size        int64
	user        string
	password    string
	offset      int64
	loginErr    error
	sizeErr     error
	retrieveErr error
}

func (s *ftpLabSession) Login(_ context.Context, user, password string) error {
	s.user = user
	s.password = password
	return s.loginErr
}
func (s *ftpLabSession) Size(context.Context, string) (int64, error) {
	if s.sizeErr != nil {
		return 0, s.sizeErr
	}
	if s.size == 0 && len(s.data) > 0 {
		return int64(len(s.data)), nil
	}
	return s.size, nil
}
func (s *ftpLabSession) Retrieve(_ context.Context, _ string, offset int64) (io.ReadCloser, error) {
	s.offset = offset
	if s.retrieveErr != nil {
		return nil, s.retrieveErr
	}
	if offset < 0 || offset > int64(len(s.data)) {
		return nil, io.ErrUnexpectedEOF
	}
	return io.NopCloser(bytes.NewReader(s.data[offset:])), nil
}
func (s *ftpLabSession) Close() error { return nil }

type ftpLabDialer struct {
	session  *ftpLabSession
	endpoint ftpbackend.Endpoint
}

func (d *ftpLabDialer) Dial(_ context.Context, ep ftpbackend.Endpoint) (ftpbackend.Session, error) {
	d.endpoint = ep
	return d.session, nil
}

type ftpLabLifecycle struct {
	state  string
	failed *failure.Failure
}

func (l *ftpLabLifecycle) Start(context.Context) error    { l.state = "running"; return nil }
func (l *ftpLabLifecycle) Complete(context.Context) error { l.state = "transport_complete"; return nil }
func (l *ftpLabLifecycle) Pause(context.Context) error    { l.state = "paused"; return nil }
func (l *ftpLabLifecycle) Fail(_ context.Context, f failure.Failure) error {
	l.state = "failed"
	l.failed = &f
	return nil
}

type ftpLabCommitter struct{}

func (ftpLabCommitter) CommitBlock(_ context.Context, f checkpoint.File, req checkpoint.CommitRequest) (store.CheckpointBlockRecord, error) {
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

type ftpLabResources struct {
	bytes       int
	connections int
}

func (r *ftpLabResources) WaitN(_ context.Context, n int) error { r.bytes += n; return nil }
func (r *ftpLabResources) AcquireConnection(context.Context) (func(), error) {
	r.connections++
	return func() {}, nil
}

func ftpLabResource(name string) resource.Identity {
	r, err := resource.DeriveIdentity("xgo37-lab", name)
	if err != nil {
		panic(err)
	}
	return r
}
func ftpLabIntent(raw string, size *int64) domainrequest.NetworkIntent {
	in, err := domainrequest.NewNetworkIntent(domainrequest.NetworkIntent{TransportURL: raw, Resource: ftpLabResource(raw), Method: "GET", ExpectedLength: size})
	if err != nil {
		panic(err)
	}
	return in
}
func ftpLabIDs() (identity.DownloadID, identity.AttemptGeneration) {
	dl, _ := identity.ParseDownloadID("dl_00000000000000000000000000000037")
	g, _ := identity.NewAttemptGeneration(1)
	return dl, g
}

func ftpLabExecute(factory *ftpbackend.Factory, sess *ftpLabSession, offset int64) (ftpbackend.ExecuteResult, *ftpLabLifecycle, *os.File, error) {
	dir, err := os.MkdirTemp("", "xgo37-ftp-lab-")
	if err != nil {
		return ftpbackend.ExecuteResult{}, nil, nil, err
	}
	file, err := os.OpenFile(filepath.Join(dir, "stage.bin"), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return ftpbackend.ExecuteResult{}, nil, nil, err
	}
	if offset > 0 {
		if _, err = file.WriteAt(sess.data[:offset], 0); err != nil {
			_ = file.Close()
			return ftpbackend.ExecuteResult{}, nil, nil, err
		}
	}
	dl, g := ftpLabIDs()
	life := &ftpLabLifecycle{}
	resources := &ftpLabResources{}
	result, execErr := ftpbackend.Execute(context.Background(), ftpbackend.ExecutePlan{Factory: factory, Dialer: &ftpLabDialer{session: sess}, DownloadID: dl, Generation: g, StartOffset: offset, BufferBytes: 4 << 10, CheckpointBytes: 4 << 10, File: file, Committer: ftpLabCommitter{}, Lifecycle: life, Resources: resources})
	if execErr == nil && (resources.connections != 1 || resources.bytes != len(sess.data)-int(offset)) {
		execErr = errors.New("common resource limiter was not used")
	}
	return result, life, file, execErr
}

func ftpAnonymousCase() error {
	data := []byte("anonymous")
	n := int64(len(data))
	factory, err := ftpbackend.NewFactory(ftpLabIntent("ftp://example.test/file", &n), nil)
	if err != nil {
		return err
	}
	sess := &ftpLabSession{data: data, size: n}
	_, _, f, err := ftpLabExecute(factory, sess, 0)
	if f != nil {
		defer os.RemoveAll(filepath.Dir(f.Name()))
		defer f.Close()
	}
	if err != nil {
		return err
	}
	if sess.user != "anonymous" || sess.password != "anonymous@" {
		return errors.New("anonymous login mismatch")
	}
	return nil
}
func ftpAuthenticatedCase() error {
	data := []byte("auth")
	n := int64(len(data))
	in := ftpLabIntent("ftp://example.test/file", &n)
	ref := mustSecretRef("secret/xgo37/ftp")
	in.Credentials = []domainrequest.CredentialReference{{Kind: domainrequest.CredentialFTPPassword, Principal: "user37", Ref: ref, Scope: domainrequest.CredentialScope{Origin: "ftp://example.test", Resource: in.Resource}}}
	var err error
	in, err = domainrequest.NewNetworkIntent(in)
	if err != nil {
		return err
	}
	mat := &material{secrets: map[domainrequest.SecretReference][]byte{ref: []byte("pass37")}}
	factory, err := ftpbackend.NewFactory(in, mat)
	if err != nil {
		return err
	}
	sess := &ftpLabSession{data: data, size: n}
	_, _, f, err := ftpLabExecute(factory, sess, 0)
	if f != nil {
		defer os.RemoveAll(filepath.Dir(f.Name()))
		defer f.Close()
	}
	if err != nil {
		return err
	}
	if sess.user != "user37" || sess.password != "pass37" {
		return errors.New("credentialed login mismatch")
	}
	return nil
}
func ftpFTPSCase() error {
	n := int64(1)
	factory, err := ftpbackend.NewFactory(ftpLabIntent("ftps://secure.test/file", &n), nil)
	if err != nil {
		return err
	}
	if !factory.Endpoint().TLS() || factory.Endpoint().Port != "990" {
		return errors.New("ftps endpoint not secure")
	}
	return nil
}
func ftpResumeCase() error {
	data := []byte("abcdefgh")
	n := int64(len(data))
	factory, _ := ftpbackend.NewFactory(ftpLabIntent("ftp://example.test/file", &n), nil)
	sess := &ftpLabSession{data: data, size: n}
	result, life, f, err := ftpLabExecute(factory, sess, 4)
	if f != nil {
		defer os.RemoveAll(filepath.Dir(f.Name()))
		defer f.Close()
	}
	if err != nil {
		return err
	}
	if sess.offset != 4 || result.Committed != n || life.state != "transport_complete" {
		return errors.New("resume did not preserve canonical offset/lifecycle")
	}
	return nil
}
func ftpWrongSizeCase() error {
	expected := int64(8)
	factory, _ := ftpbackend.NewFactory(ftpLabIntent("ftp://example.test/file", &expected), nil)
	sess := &ftpLabSession{data: []byte("short"), size: 5}
	_, life, f, err := ftpLabExecute(factory, sess, 0)
	if f != nil {
		defer os.RemoveAll(filepath.Dir(f.Name()))
		defer f.Close()
	}
	if err == nil || life.failed == nil || life.failed.Category != failure.RepresentationChanged {
		return errors.New("wrong size not typed as representation change")
	}
	return nil
}
func ftpDisconnectRetryCase() error {
	n := int64(8)
	factory, _ := ftpbackend.NewFactory(ftpLabIntent("ftp://example.test/file", &n), nil)
	sess := &ftpLabSession{size: n, retrieveErr: io.ErrUnexpectedEOF}
	_, life, f, err := ftpLabExecute(factory, sess, 0)
	if f != nil {
		defer os.RemoveAll(filepath.Dir(f.Name()))
		defer f.Close()
	}
	if err == nil || life.failed == nil || life.failed.Category != failure.NetworkUnavailable {
		return errors.New("disconnect not typed as network failure")
	}
	decision, e := (retry.Engine{Clock: fixedClock{time.Unix(100, 0)}}).Decide(retry.Input{Failure: *life.failed, AttemptCount: 1, Queue: retry.QueuePolicy{Enabled: true, MaxAttempts: 3, BaseDelay: time.Second}, NetworkAvailable: true, Replayable: true})
	if e != nil {
		return e
	}
	if decision.Kind != retry.RetryAt {
		return errors.New("retry taxonomy did not schedule backoff")
	}
	return nil
}
func ftpChecksumCase() error {
	data := []byte("ftp-checksum")
	n := int64(len(data))
	factory, _ := ftpbackend.NewFactory(ftpLabIntent("ftp://example.test/file", &n), nil)
	sess := &ftpLabSession{data: data, size: n}
	_, life, f, err := ftpLabExecute(factory, sess, 0)
	if f == nil {
		return errors.New("missing staging file")
	}
	defer os.RemoveAll(filepath.Dir(f.Name()))
	defer f.Close()
	if err != nil {
		return err
	}
	if life.state != "transport_complete" {
		return errors.New("transport did not complete")
	}
	if _, err = f.Seek(0, io.SeekStart); err != nil {
		return err
	}
	sum := sha256.Sum256(data)
	expected, err := checksum.NormalizeExpected(checksum.SourceUser, "sha256", hex.EncodeToString(sum[:]))
	if err != nil {
		return err
	}
	verified, err := checksum.Verify(context.Background(), f, &expected, 0)
	if err != nil {
		return err
	}
	if !verified.Matched {
		return errors.New("checksum did not verify")
	}
	return nil
}

func runFTPLab() report {
	cases := []caseResult{
		runCase("anonymous_ftp", ftpAnonymousCase),
		runCase("authenticated_ftp", ftpAuthenticatedCase),
		runCase("ftps_secure_mode", ftpFTPSCase),
		runCase("resume", ftpResumeCase),
		runCase("wrong_size", ftpWrongSizeCase),
		runCase("disconnect_retry", ftpDisconnectRetryCase),
		runCase("checksum_after_ftp", ftpChecksumCase),
	}
	r := report{SchemaVersion: 1, Mode: "ftp", Status: "pass", Total: len(cases), Cases: cases}
	for _, c := range cases {
		if c.Passed {
			r.Passed++
		}
	}
	if r.Passed != r.Total {
		r.Status = "fail"
	}
	return r
}
