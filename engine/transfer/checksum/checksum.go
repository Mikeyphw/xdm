package checksum

import (
	"context"
	"crypto/md5"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"io"
	"strings"
)

var (
	ErrUnsupportedAlgorithm = errors.New("unsupported checksum algorithm")
	ErrMalformedExpected    = errors.New("malformed expected checksum")
	ErrMismatch             = errors.New("checksum mismatch")
)

const VerifierVersion = "xgo-checksum-v1"

type Algorithm string

const (
	MD5    Algorithm = "md5"
	SHA1   Algorithm = "sha1"
	SHA256 Algorithm = "sha256"
	SHA512 Algorithm = "sha512"
)

type Source string

const (
	SourceUser     Source = "user"
	SourceMetalink Source = "metalink"
	SourceMetadata Source = "metadata"
	SourceInternal Source = "internal"
)

type Expected struct {
	Algorithm Algorithm `json:"algorithm"`
	Hex       string    `json:"hex"`
	Source    Source    `json:"source"`
}

type Result struct {
	Algorithm Algorithm `json:"algorithm"`
	Expected  string    `json:"expected,omitempty"`
	Actual    string    `json:"actual"`
	Source    Source    `json:"source"`
	Bytes     int64     `json:"bytes"`
	Matched   bool      `json:"matched"`
	Version   string    `json:"version"`
}

func Supported() []Algorithm { return []Algorithm{MD5, SHA1, SHA256, SHA512} }

func normalizeAlgorithm(raw string) (Algorithm, error) {
	v := strings.ToLower(strings.TrimSpace(raw))
	v = strings.ReplaceAll(v, "-", "")
	v = strings.ReplaceAll(v, "_", "")
	switch v {
	case "md5":
		return MD5, nil
	case "sha1":
		return SHA1, nil
	case "sha256":
		return SHA256, nil
	case "sha512":
		return SHA512, nil
	default:
		return "", fmt.Errorf("%w: %q", ErrUnsupportedAlgorithm, raw)
	}
}

func validSource(source Source) bool {
	switch source {
	case SourceUser, SourceMetalink, SourceMetadata, SourceInternal:
		return true
	default:
		return false
	}
}

func hashFor(alg Algorithm) (hash.Hash, int, error) {
	switch alg {
	case MD5:
		return md5.New(), md5.Size * 2, nil
	case SHA1:
		return sha1.New(), sha1.Size * 2, nil
	case SHA256:
		return sha256.New(), sha256.Size * 2, nil
	case SHA512:
		return sha512.New(), sha512.Size * 2, nil
	default:
		return nil, 0, ErrUnsupportedAlgorithm
	}
}

func NormalizeExpected(source Source, algorithm, value string) (Expected, error) {
	if !validSource(source) || source == SourceInternal {
		return Expected{}, ErrMalformedExpected
	}
	alg, err := normalizeAlgorithm(algorithm)
	if err != nil {
		return Expected{}, err
	}
	raw := strings.ToLower(strings.TrimSpace(value))
	raw = strings.TrimPrefix(raw, "0x")
	_, want, _ := hashFor(alg)
	if len(raw) != want {
		return Expected{}, fmt.Errorf("%w: %s requires %d hex chars", ErrMalformedExpected, alg, want)
	}
	decoded, err := hex.DecodeString(raw)
	if err != nil || len(decoded)*2 != want {
		return Expected{}, fmt.Errorf("%w: invalid %s hex", ErrMalformedExpected, alg)
	}
	return Expected{Algorithm: alg, Hex: raw, Source: source}, nil
}

func Verify(ctx context.Context, reader io.Reader, expected *Expected, bufferBytes int) (Result, error) {
	if reader == nil {
		return Result{}, errors.New("nil checksum reader")
	}
	alg, source, expectedHex := SHA256, SourceInternal, ""
	if expected != nil {
		alg, source, expectedHex = expected.Algorithm, expected.Source, strings.ToLower(expected.Hex)
		normalized, err := NormalizeExpected(source, string(alg), expectedHex)
		if err != nil {
			return Result{}, err
		}
		alg, expectedHex = normalized.Algorithm, normalized.Hex
	}
	h, _, err := hashFor(alg)
	if err != nil {
		return Result{}, err
	}
	if bufferBytes == 0 {
		bufferBytes = 256 << 10
	}
	if bufferBytes < 4<<10 || bufferBytes > 4<<20 {
		return Result{}, fmt.Errorf("invalid checksum buffer size %d", bufferBytes)
	}
	buf := make([]byte, bufferBytes)
	var total int64
	for {
		if err := ctx.Err(); err != nil {
			return Result{}, err
		}
		n, readErr := reader.Read(buf)
		if n > 0 {
			if _, err = h.Write(buf[:n]); err != nil {
				return Result{}, err
			}
			total += int64(n)
		}
		if readErr != nil {
			if errors.Is(readErr, io.EOF) {
				break
			}
			return Result{}, readErr
		}
	}
	actual := hex.EncodeToString(h.Sum(nil))
	matched := expected == nil || actual == expectedHex
	result := Result{Algorithm: alg, Expected: expectedHex, Actual: actual, Source: source, Bytes: total, Matched: matched, Version: VerifierVersion}
	if !matched {
		return result, ErrMismatch
	}
	return result, nil
}
