package ftpbackend

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/textproto"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	ErrProtocol           = errors.New("ftp protocol error")
	ErrUnsupportedFeature = errors.New("ftp server feature unsupported")
	ErrAuthentication     = errors.New("ftp authentication failed")
	ErrTLS                = errors.New("ftps transport failed")
	ErrUnexpectedReply    = errors.New("unexpected ftp reply")
)

type UnsupportedFeatureError struct {
	Feature string
	Code    int
}

func (e *UnsupportedFeatureError) Error() string {
	return fmt.Sprintf("%v: %s (reply %d)", ErrUnsupportedFeature, e.Feature, e.Code)
}
func (e *UnsupportedFeatureError) Unwrap() error { return ErrUnsupportedFeature }

type ReplyError struct {
	Stage string
	Code  int
}

func (e *ReplyError) Error() string {
	return fmt.Sprintf("%v: %s reply %d", ErrUnexpectedReply, e.Stage, e.Code)
}
func (e *ReplyError) Unwrap() error { return ErrUnexpectedReply }

type Endpoint struct {
	Scheme string
	Host   string
	Port   string
}

func (e Endpoint) Address() string { return net.JoinHostPort(e.Host, e.Port) }
func (e Endpoint) TLS() bool       { return e.Scheme == "ftps" }

type Session interface {
	Login(context.Context, string, string) error
	Size(context.Context, string) (int64, error)
	Retrieve(context.Context, string, int64) (io.ReadCloser, error)
	Close() error
}

type Dialer interface {
	Dial(context.Context, Endpoint) (Session, error)
}

type NetDialer struct {
	Dialer    *net.Dialer
	TLSConfig *tls.Config
}

func (d NetDialer) Dial(ctx context.Context, ep Endpoint) (Session, error) {
	nd := d.Dialer
	if nd == nil {
		nd = &net.Dialer{Timeout: 15 * time.Second}
	}
	raw, err := nd.DialContext(ctx, "tcp", ep.Address())
	if err != nil {
		return nil, err
	}
	conn := raw
	secure := ep.TLS()
	var sessionTLSConfig *tls.Config
	if secure {
		cfg := d.TLSConfig
		if cfg == nil {
			cfg = &tls.Config{MinVersion: tls.VersionTLS12, ServerName: ep.Host}
		}
		if cfg.ServerName == "" {
			clone := cfg.Clone()
			clone.ServerName = ep.Host
			cfg = clone
		}
		tc := tls.Client(raw, cfg)
		if deadline, ok := ctx.Deadline(); ok {
			_ = tc.SetDeadline(deadline)
		}
		if err = tc.HandshakeContext(ctx); err != nil {
			_ = raw.Close()
			return nil, fmt.Errorf("%w", ErrTLS)
		}
		_ = tc.SetDeadline(time.Time{})
		conn = tc
		sessionTLSConfig = cfg.Clone()
	}
	s := &netSession{conn: conn, tp: textproto.NewConn(conn), endpoint: ep, secure: secure, tlsConfig: sessionTLSConfig}
	code, _, err := s.readResponse(ctx, 0)
	if err != nil {
		_ = s.Close()
		return nil, err
	}
	if code != 220 {
		_ = s.Close()
		return nil, &ReplyError{Stage: "greeting", Code: code}
	}
	return s, nil
}

type netSession struct {
	mu        sync.Mutex
	conn      net.Conn
	tp        *textproto.Conn
	endpoint  Endpoint
	secure    bool
	tlsConfig *tls.Config
	loggedIn  bool
	closed    bool
}

func deadlineFrom(ctx context.Context) time.Time {
	if ctx == nil {
		return time.Time{}
	}
	if d, ok := ctx.Deadline(); ok {
		return d
	}
	return time.Time{}
}
func (s *netSession) setDeadline(ctx context.Context) { _ = s.conn.SetDeadline(deadlineFrom(ctx)) }
func (s *netSession) clearDeadline()                  { _ = s.conn.SetDeadline(time.Time{}) }

func (s *netSession) readResponse(ctx context.Context, expect int) (int, string, error) {
	s.setDeadline(ctx)
	defer s.clearDeadline()
	code, msg, err := s.tp.ReadResponse(expect)
	if err != nil {
		return code, "", err
	}
	return code, msg, nil
}
func (s *netSession) command(ctx context.Context, expect int, format string, args ...any) (int, string, error) {
	s.setDeadline(ctx)
	id, err := s.tp.Cmd(format, args...)
	if err != nil {
		s.clearDeadline()
		return 0, "", err
	}
	s.tp.StartResponse(id)
	code, msg, readErr := s.tp.ReadResponse(expect)
	s.tp.EndResponse(id)
	s.clearDeadline()
	return code, msg, readErr
}

func (s *netSession) Login(ctx context.Context, user, password string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return net.ErrClosed
	}
	if strings.ContainsAny(user, "\r\n") || strings.ContainsAny(password, "\r\n") {
		return ErrAuthentication
	}
	code, _, err := s.command(ctx, 0, "USER %s", user)
	if err != nil {
		return err
	}
	switch code {
	case 230:
	case 331:
		code, _, err = s.command(ctx, 0, "PASS %s", password)
		if err != nil {
			return err
		}
		if code != 230 {
			return fmt.Errorf("%w", ErrAuthentication)
		}
	case 530:
		return fmt.Errorf("%w", ErrAuthentication)
	default:
		return &ReplyError{Stage: "USER", Code: code}
	}
	if code, _, err = s.command(ctx, 0, "TYPE I"); err != nil {
		return err
	} else if code/100 != 2 {
		return &ReplyError{Stage: "TYPE", Code: code}
	}
	if s.secure {
		if code, _, err = s.command(ctx, 0, "PBSZ 0"); err != nil {
			return err
		} else if code/100 != 2 {
			return &ReplyError{Stage: "PBSZ", Code: code}
		}
		if code, _, err = s.command(ctx, 0, "PROT P"); err != nil {
			return err
		} else if code/100 != 2 {
			return &UnsupportedFeatureError{Feature: "PROT P", Code: code}
		}
	}
	s.loggedIn = true
	return nil
}

func (s *netSession) Size(ctx context.Context, path string) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.loggedIn || s.closed {
		return 0, ErrProtocol
	}
	code, msg, err := s.command(ctx, 0, "SIZE %s", path)
	if err != nil {
		return 0, err
	}
	if code == 500 || code == 502 || code == 504 {
		return 0, &UnsupportedFeatureError{Feature: "SIZE", Code: code}
	}
	if code == 550 {
		return 0, &ReplyError{Stage: "SIZE", Code: code}
	}
	if code != 213 {
		return 0, &ReplyError{Stage: "SIZE", Code: code}
	}
	n, parseErr := strconv.ParseInt(strings.TrimSpace(msg), 10, 64)
	if parseErr != nil || n < 0 {
		return 0, ErrProtocol
	}
	return n, nil
}

func parseEPSV(msg string) (string, error) {
	start, end := strings.LastIndex(msg, "("), strings.LastIndex(msg, ")")
	if start < 0 || end <= start+4 {
		return "", ErrProtocol
	}
	body := msg[start+1 : end]
	delim := body[0]
	parts := strings.Split(body, string(delim))
	if len(parts) < 5 {
		return "", ErrProtocol
	}
	port := strings.TrimSpace(parts[len(parts)-2])
	n, err := strconv.Atoi(port)
	if err != nil || n < 1 || n > 65535 {
		return "", ErrProtocol
	}
	return port, nil
}
func parsePASV(msg string) (string, error) {
	start, end := strings.LastIndex(msg, "("), strings.LastIndex(msg, ")")
	if start < 0 || end <= start {
		return "", ErrProtocol
	}
	fields := strings.Split(msg[start+1:end], ",")
	if len(fields) != 6 {
		return "", ErrProtocol
	}
	p1, e1 := strconv.Atoi(strings.TrimSpace(fields[4]))
	p2, e2 := strconv.Atoi(strings.TrimSpace(fields[5]))
	if e1 != nil || e2 != nil || p1 < 0 || p1 > 255 || p2 < 0 || p2 > 255 {
		return "", ErrProtocol
	}
	return strconv.Itoa(p1*256 + p2), nil
}

func (s *netSession) passiveLocked(ctx context.Context) (net.Conn, error) {
	code, msg, err := s.command(ctx, 0, "EPSV")
	var port string
	if err == nil && code == 229 {
		port, err = parseEPSV(msg)
	} else {
		code, msg, err = s.command(ctx, 0, "PASV")
		if err != nil {
			return nil, err
		}
		if code != 227 {
			if code == 500 || code == 502 || code == 504 {
				return nil, &UnsupportedFeatureError{Feature: "passive_data", Code: code}
			}
			return nil, &ReplyError{Stage: "PASV", Code: code}
		}
		port, err = parsePASV(msg)
	}
	if err != nil {
		return nil, err
	}
	host := s.endpoint.Host // ignore PASV-advertised host to prevent data-channel host injection
	nd := &net.Dialer{Timeout: 15 * time.Second}
	raw, err := nd.DialContext(ctx, "tcp", net.JoinHostPort(host, port))
	if err != nil {
		return nil, err
	}
	// For FTPS the data-channel TLS handshake is intentionally deferred until
	// RETR receives its preliminary 125/150 reply. Some servers do not accept
	// or wrap the passive data socket before the transfer command arrives.
	return raw, nil
}

func (s *netSession) Retrieve(ctx context.Context, path string, offset int64) (io.ReadCloser, error) {
	s.mu.Lock()
	if !s.loggedIn || s.closed || offset < 0 {
		s.mu.Unlock()
		return nil, ErrProtocol
	}
	if offset > 0 {
		code, _, err := s.command(ctx, 0, "REST %d", offset)
		if err != nil {
			s.mu.Unlock()
			return nil, err
		}
		if code != 350 {
			s.mu.Unlock()
			if code == 500 || code == 501 || code == 502 || code == 504 {
				return nil, &UnsupportedFeatureError{Feature: "REST", Code: code}
			}
			return nil, &ReplyError{Stage: "REST", Code: code}
		}
	}
	data, err := s.passiveLocked(ctx)
	if err != nil {
		s.mu.Unlock()
		return nil, err
	}
	s.setDeadline(ctx)
	id, err := s.tp.Cmd("RETR %s", path)
	if err != nil {
		s.clearDeadline()
		_ = data.Close()
		s.mu.Unlock()
		return nil, err
	}
	s.tp.StartResponse(id)
	code, _, err := s.tp.ReadResponse(0)
	s.tp.EndResponse(id)
	s.clearDeadline()
	if err != nil {
		_ = data.Close()
		s.mu.Unlock()
		return nil, err
	}
	if code != 125 && code != 150 {
		_ = data.Close()
		s.mu.Unlock()
		return nil, &ReplyError{Stage: "RETR", Code: code}
	}
	if s.secure {
		cfg := s.tlsConfig
		if cfg == nil {
			cfg = &tls.Config{MinVersion: tls.VersionTLS12, ServerName: s.endpoint.Host}
		} else {
			cfg = cfg.Clone()
			if cfg.ServerName == "" {
				cfg.ServerName = s.endpoint.Host
			}
		}
		tc := tls.Client(data, cfg)
		if deadline, ok := ctx.Deadline(); ok {
			_ = tc.SetDeadline(deadline)
		}
		if err = tc.HandshakeContext(ctx); err != nil {
			_ = data.Close()
			s.mu.Unlock()
			return nil, fmt.Errorf("%w", ErrTLS)
		}
		_ = tc.SetDeadline(time.Time{})
		data = tc
	}
	s.mu.Unlock()
	return &dataReader{session: s, conn: data, ctx: ctx}, nil
}

type dataReader struct {
	session   *netSession
	conn      net.Conn
	ctx       context.Context
	once      sync.Once
	finishErr error
}

func (r *dataReader) finish() error {
	r.once.Do(func() {
		_ = r.conn.Close()
		r.session.mu.Lock()
		defer r.session.mu.Unlock()
		code, _, err := r.session.readResponse(r.ctx, 0)
		if err != nil {
			r.finishErr = err
			return
		}
		if code != 226 && code != 250 {
			r.finishErr = &ReplyError{Stage: "RETR completion", Code: code}
		}
	})
	return r.finishErr
}
func (r *dataReader) Read(p []byte) (int, error) {
	n, err := r.conn.Read(p)
	if errors.Is(err, io.EOF) {
		if ferr := r.finish(); ferr != nil {
			return n, ferr
		}
	}
	return n, err
}
func (r *dataReader) Close() error { return r.finish() }

func (s *netSession) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil
	}
	s.closed = true
	if s.tp != nil {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		_, _, _ = s.command(ctx, 0, "QUIT")
		cancel()
		return s.tp.Close()
	}
	if s.conn != nil {
		return s.conn.Close()
	}
	return nil
}

func ParseEndpoint(raw string) (Endpoint, string, error) {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" || (u.Scheme != "ftp" && u.Scheme != "ftps") || u.User != nil || u.Fragment != "" {
		return Endpoint{}, "", ErrProtocol
	}
	host := strings.ToLower(u.Hostname())
	if host == "" {
		return Endpoint{}, "", ErrProtocol
	}
	port := u.Port()
	if port == "" {
		if u.Scheme == "ftps" {
			port = "990"
		} else {
			port = "21"
		}
	}
	path, err := url.PathUnescape(u.EscapedPath())
	if err != nil || path == "" {
		return Endpoint{}, "", ErrProtocol
	}
	return Endpoint{Scheme: strings.ToLower(u.Scheme), Host: host, Port: port}, path, nil
}
