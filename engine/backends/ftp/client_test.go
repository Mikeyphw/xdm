package ftpbackend

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

type ftpServer struct {
	listener   net.Listener
	secure     bool
	tlsConfig  *tls.Config
	data       []byte
	user       string
	password   string
	rejectREST bool
	done       chan struct{}
}

func testTLSConfig(t *testing.T) (*tls.Config, *tls.Config) {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		t.Fatal(err)
	}
	tmpl := x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "127.0.0.1"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageKeyEncipherment | x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		t.Fatal(err)
	}
	server := &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{cert}}
	client := &tls.Config{MinVersion: tls.VersionTLS12, InsecureSkipVerify: true} // loopback test certificate only
	return server, client
}

func startFTPServer(t *testing.T, secure bool, data []byte, user, password string, rejectREST bool) (Endpoint, *tls.Config, func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var serverTLS, clientTLS *tls.Config
	if secure {
		serverTLS, clientTLS = testTLSConfig(t)
		ln = tls.NewListener(ln, serverTLS)
	}
	srv := &ftpServer{listener: ln, secure: secure, tlsConfig: serverTLS, data: data, user: user, password: password, rejectREST: rejectREST, done: make(chan struct{})}
	go srv.serve()
	host, port, _ := net.SplitHostPort(ln.Addr().String())
	scheme := "ftp"
	if secure {
		scheme = "ftps"
	}
	stop := func() {
		_ = ln.Close()
		select {
		case <-srv.done:
		case <-time.After(2 * time.Second):
		}
	}
	return Endpoint{Scheme: scheme, Host: host, Port: port}, clientTLS, stop
}

func (s *ftpServer) serve() {
	defer close(s.done)
	for {
		c, err := s.listener.Accept()
		if err != nil {
			return
		}
		s.handle(c)
	}
}
func writeReply(w *bufio.Writer, code int, msg string) {
	fmt.Fprintf(w, "%d %s\r\n", code, msg)
	_ = w.Flush()
}
func (s *ftpServer) handle(conn net.Conn) {
	defer conn.Close()
	r := bufio.NewReader(conn)
	w := bufio.NewWriter(conn)
	writeReply(w, 220, "ready")
	var dataLn net.Listener
	var rest int64
	defer func() {
		if dataLn != nil {
			_ = dataLn.Close()
		}
	}()
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return
		}
		line = strings.TrimSpace(line)
		fields := strings.SplitN(line, " ", 2)
		cmd := strings.ToUpper(fields[0])
		arg := ""
		if len(fields) > 1 {
			arg = fields[1]
		}
		switch cmd {
		case "USER":
			if s.user == "" && arg == "anonymous" {
				writeReply(w, 230, "logged in")
			} else if arg == s.user {
				writeReply(w, 331, "password")
			} else {
				writeReply(w, 530, "denied")
			}
		case "PASS":
			if arg == s.password {
				writeReply(w, 230, "logged in")
			} else {
				writeReply(w, 530, "denied")
			}
		case "TYPE":
			writeReply(w, 200, "binary")
		case "PBSZ":
			writeReply(w, 200, "ok")
		case "PROT":
			writeReply(w, 200, "private")
		case "SIZE":
			writeReply(w, 213, strconv.Itoa(len(s.data)))
		case "REST":
			if s.rejectREST {
				writeReply(w, 502, "unsupported")
				continue
			}
			n, e := strconv.ParseInt(arg, 10, 64)
			if e != nil || n < 0 || n > int64(len(s.data)) {
				writeReply(w, 501, "bad")
			} else {
				rest = n
				writeReply(w, 350, "restart")
			}
		case "EPSV":
			if dataLn != nil {
				_ = dataLn.Close()
			}
			dl, e := net.Listen("tcp", "127.0.0.1:0")
			if e != nil {
				writeReply(w, 425, "no data")
				continue
			}
			dataLn = dl
			_, p, _ := net.SplitHostPort(dl.Addr().String())
			writeReply(w, 229, "Entering Extended Passive Mode (|||"+p+"|)")
		case "PASV":
			writeReply(w, 502, "use EPSV")
		case "RETR":
			if dataLn == nil {
				writeReply(w, 425, "no passive")
				continue
			}
			writeReply(w, 150, "opening")
			dc, e := dataLn.Accept()
			_ = dataLn.Close()
			dataLn = nil
			if e != nil {
				writeReply(w, 425, "accept")
				continue
			}
			if s.secure {
				tc := tls.Server(dc, s.tlsConfig)
				if e = tc.Handshake(); e != nil {
					_ = dc.Close()
					return
				}
				dc = tc
			}
			_, _ = dc.Write(s.data[rest:])
			_ = dc.Close()
			rest = 0
			writeReply(w, 226, "done")
		case "QUIT":
			writeReply(w, 221, "bye")
			return
		default:
			writeReply(w, 502, "unsupported")
		}
	}
}

func TestNetDialerFTPAnonymousAndResume(t *testing.T) {
	payload := []byte("0123456789")
	ep, _, stop := startFTPServer(t, false, payload, "", "", false)
	defer stop()
	session, err := (NetDialer{}).Dial(context.Background(), ep)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if err = session.Login(context.Background(), "anonymous", "anonymous@"); err != nil {
		t.Fatal(err)
	}
	size, err := session.Size(context.Background(), "/file.bin")
	if err != nil || size != int64(len(payload)) {
		t.Fatalf("size=%d err=%v", size, err)
	}
	body, err := session.Retrieve(context.Background(), "/file.bin", 4)
	if err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(body)
	if err != nil {
		t.Fatal(err)
	}
	if err = body.Close(); err != nil {
		t.Fatal(err)
	}
	if string(got) != "456789" {
		t.Fatalf("got=%q", got)
	}
}

func TestNetDialerFTPSAuthenticatedDataProtection(t *testing.T) {
	payload := []byte("secure-payload")
	ep, clientTLS, stop := startFTPServer(t, true, payload, "user", "pass", false)
	defer stop()
	session, err := (NetDialer{TLSConfig: clientTLS}).Dial(context.Background(), ep)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if err = session.Login(context.Background(), "user", "pass"); err != nil {
		t.Fatal(err)
	}
	body, err := session.Retrieve(context.Background(), "/secure.bin", 0)
	if err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(body)
	if err != nil {
		t.Fatal(err)
	}
	if err = body.Close(); err != nil {
		t.Fatal(err)
	}
	if string(got) != string(payload) {
		t.Fatalf("got=%q", got)
	}
}

func TestNetDialerClassifiesUnsupportedREST(t *testing.T) {
	ep, _, stop := startFTPServer(t, false, []byte("abcdef"), "", "", true)
	defer stop()
	session, err := (NetDialer{}).Dial(context.Background(), ep)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if err = session.Login(context.Background(), "anonymous", "anonymous@"); err != nil {
		t.Fatal(err)
	}
	_, err = session.Retrieve(context.Background(), "/file", 2)
	if !errors.Is(err, ErrUnsupportedFeature) {
		t.Fatalf("err=%v", err)
	}
}
