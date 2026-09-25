package idgen_test

import (
	"bytes"
	"errors"
	"testing"

	"github.com/subhra74/xdm/engine/foundation/idgen"
	"github.com/subhra74/xdm/engine/foundation/idgen/idgentest"
)

func TestCryptoUsesInjectedEntropyDeterministically(t *testing.T) {
	input := bytes.Repeat([]byte{0xab}, idgen.TokenBytes)
	generator := idgen.NewCrypto(bytes.NewReader(input))
	got, err := generator.Token()
	if err != nil {
		t.Fatal(err)
	}
	if want := "abababababababababababababababab"; got != want {
		t.Fatalf("token=%q want=%q", got, want)
	}
	if err := idgen.ValidateToken(got); err != nil {
		t.Fatalf("generated token rejected: %v", err)
	}
}

func TestCounterIsDeterministic(t *testing.T) {
	counter := idgentest.NewCounter(7)
	first, err := counter.Token()
	if err != nil {
		t.Fatal(err)
	}
	second, err := counter.Token()
	if err != nil {
		t.Fatal(err)
	}
	if first != "00000000000000000000000000000007" || second != "00000000000000000000000000000008" {
		t.Fatalf("unexpected sequence: %q %q", first, second)
	}
}

func TestValidateTokenRejectsNonCanonicalForms(t *testing.T) {
	for _, value := range []string{"", "ABCDEF00000000000000000000000000", "1234", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"} {
		if err := idgen.ValidateToken(value); !errors.Is(err, idgen.ErrInvalidToken) {
			t.Fatalf("value=%q err=%v", value, err)
		}
	}
}
