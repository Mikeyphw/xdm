package checksum_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
	"testing"

	"github.com/subhra74/xdm/engine/transfer/checksum"
)

func TestNormalizeExpectedAliasesAndMalformedValues(t *testing.T) {
	sum := sha256.Sum256([]byte("payload"))
	want := hex.EncodeToString(sum[:])
	got, err := checksum.NormalizeExpected(checksum.SourceMetalink, "SHA-256", strings.ToUpper(want))
	if err != nil {
		t.Fatal(err)
	}
	if got.Algorithm != checksum.SHA256 || got.Hex != want || got.Source != checksum.SourceMetalink {
		t.Fatalf("got=%+v", got)
	}
	if _, err := checksum.NormalizeExpected(checksum.SourceUser, "sha256", "abc"); !errors.Is(err, checksum.ErrMalformedExpected) {
		t.Fatalf("malformed=%v", err)
	}
	if _, err := checksum.NormalizeExpected(checksum.SourceUser, "crc32", "00000000"); !errors.Is(err, checksum.ErrUnsupportedAlgorithm) {
		t.Fatalf("unsupported=%v", err)
	}
}

func TestVerifyCorrectWrongLargeAndCancelled(t *testing.T) {
	data := bytes.Repeat([]byte("0123456789abcdef"), 512*1024) // 8 MiB
	sum := sha256.Sum256(data)
	expected, _ := checksum.NormalizeExpected(checksum.SourceUser, "sha256", hex.EncodeToString(sum[:]))
	result, err := checksum.Verify(context.Background(), bytes.NewReader(data), &expected, 64<<10)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Matched || result.Bytes != int64(len(data)) || result.Version != checksum.VerifierVersion {
		t.Fatalf("result=%+v", result)
	}

	wrong := expected
	wrong.Hex = strings.Repeat("0", 64)
	mismatch, err := checksum.Verify(context.Background(), bytes.NewReader(data), &wrong, 128<<10)
	if !errors.Is(err, checksum.ErrMismatch) || mismatch.Matched {
		t.Fatalf("mismatch=%+v err=%v", mismatch, err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := checksum.Verify(ctx, bytes.NewReader(data), &expected, 64<<10); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel=%v", err)
	}
}

func TestInternalVerificationDefaultsToSHA256(t *testing.T) {
	result, err := checksum.Verify(context.Background(), strings.NewReader("abc"), nil, 0)
	if err != nil {
		t.Fatal(err)
	}
	if result.Algorithm != checksum.SHA256 || result.Source != checksum.SourceInternal || result.Expected != "" || !result.Matched {
		t.Fatalf("result=%+v", result)
	}
}
