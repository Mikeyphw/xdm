package idgen

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"strings"
)

const (
	TokenBytes = 16
	TokenChars = TokenBytes * 2
)

var ErrInvalidToken = errors.New("identifier token must be 32 lowercase hexadecimal characters")

// Generator supplies cryptographically opaque identifier tokens. Domain ID
// constructors accept this interface so tests can inject deterministic data.
type Generator interface {
	Token() (string, error)
}

type Crypto struct {
	reader io.Reader
}

func NewCrypto(reader io.Reader) *Crypto {
	if reader == nil {
		reader = rand.Reader
	}
	return &Crypto{reader: reader}
}

func (g *Crypto) Token() (string, error) {
	if g == nil || g.reader == nil {
		return "", errors.New("nil crypto identifier generator")
	}
	var raw [TokenBytes]byte
	if _, err := io.ReadFull(g.reader, raw[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(raw[:]), nil
}

func ValidateToken(value string) error {
	if len(value) != TokenChars || value != strings.ToLower(value) {
		return ErrInvalidToken
	}
	decoded, err := hex.DecodeString(value)
	if err != nil || len(decoded) != TokenBytes {
		return ErrInvalidToken
	}
	return nil
}
