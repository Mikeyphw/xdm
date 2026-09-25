package dial

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"

	"github.com/subhra74/xdm/engine/security/route"
	"github.com/subhra74/xdm/engine/security/urlpolicy"
)

var (
	ErrResolutionFailed = errors.New("dns resolution failed")
	ErrNoApprovedRoute  = errors.New("no approved dial route")
	ErrDialFailed       = errors.New("all approved dial candidates failed")
)

type Resolution struct {
	Host       string       `json:"host"`
	Candidates []netip.Addr `json:"candidates"`
	Source     string       `json:"source,omitempty"`
}

type Resolver interface {
	Resolve(context.Context, string) (Resolution, error)
}

type DialContextFunc func(context.Context, string, string) (net.Conn, error)

type Binding struct {
	OriginalHost  string                  `json:"original_host"`
	HTTPHost      string                  `json:"http_host"`
	TLSServerName string                  `json:"tls_server_name"`
	Port          uint16                  `json:"port"`
	Resolution    Resolution              `json:"resolution"`
	Approved      []route.AddressDecision `json:"approved"`
	Attempts      []netip.Addr            `json:"attempts"`
	Connected     netip.Addr              `json:"connected,omitempty"`
}

type BoundDialer struct {
	Resolver    Resolver
	DialContext DialContextFunc
}

func endpoint(raw string) (host string, port uint16, httpHost string, err error) {
	t, err := urlpolicy.ParseTransport(raw)
	if err != nil {
		return "", 0, "", err
	}
	u, err := url.Parse(t.String())
	if err != nil || u.Hostname() == "" {
		return "", 0, "", err
	}
	host = strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
	p := u.Port()
	if p == "" {
		switch strings.ToLower(u.Scheme) {
		case "https":
			p = "443"
		case "http":
			p = "80"
		case "ftps":
			p = "990"
		case "ftp":
			p = "21"
		default:
			return "", 0, "", fmt.Errorf("unsupported scheme")
		}
	}
	n, e := strconv.Atoi(p)
	if e != nil || n < 1 || n > 65535 {
		return "", 0, "", fmt.Errorf("invalid port")
	}
	return host, uint16(n), u.Host, nil
}

func (d BoundDialer) Dial(ctx context.Context, target route.Target, approvals []route.Approval) (net.Conn, Binding, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if d.DialContext == nil {
		return nil, Binding{}, fmt.Errorf("%w: missing dial function", ErrDialFailed)
	}
	host, port, httpHost, err := endpoint(target.URL)
	if err != nil {
		return nil, Binding{}, err
	}

	var res Resolution
	if literal, e := netip.ParseAddr(host); e == nil {
		res = Resolution{Host: host, Candidates: []netip.Addr{literal.Unmap()}, Source: "literal"}
	} else {
		if d.Resolver == nil {
			return nil, Binding{}, fmt.Errorf("%w: missing resolver", ErrResolutionFailed)
		}
		res, err = d.Resolver.Resolve(ctx, host)
		if err != nil {
			return nil, Binding{}, fmt.Errorf("%w: %v", ErrResolutionFailed, err)
		}
		if !strings.EqualFold(strings.TrimSuffix(res.Host, "."), host) {
			return nil, Binding{}, fmt.Errorf("%w: resolver host mismatch", ErrResolutionFailed)
		}
	}
	target.Addresses = append([]netip.Addr(nil), res.Candidates...)
	decision, err := route.Evaluate(target, approvals)
	if err != nil {
		return nil, Binding{}, fmt.Errorf("%w: %v", ErrNoApprovedRoute, err)
	}
	b := Binding{OriginalHost: host, HTTPHost: httpHost, TLSServerName: host, Port: port, Resolution: res, Approved: decision.Addresses}
	var errs []error
	for _, candidate := range decision.Addresses {
		addr := candidate.Address.Unmap()
		b.Attempts = append(b.Attempts, addr)
		conn, e := d.DialContext(ctx, "tcp", net.JoinHostPort(addr.String(), strconv.Itoa(int(port))))
		if e == nil && conn != nil {
			b.Connected = addr
			return conn, b, nil
		}
		if e == nil {
			e = errors.New("dial returned nil connection")
		}
		errs = append(errs, e)
		if ctx.Err() != nil {
			break
		}
	}
	if len(errs) == 0 {
		return nil, b, ErrNoApprovedRoute
	}
	return nil, b, fmt.Errorf("%w: %v", ErrDialFailed, errors.Join(errs...))
}
