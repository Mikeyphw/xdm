package identity_test

import (
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"testing"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/foundation/idgen/idgentest"
)

const token = "0000000000000000000000000000002a"

func TestTypedIDsParseGenerateAndJSONRoundTrip(t *testing.T) {
	source := idgentest.NewCounter(42)
	generated, err := identity.NewDownloadID(source)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := generated.String(), "dl_"+token; got != want {
		t.Fatalf("got=%q want=%q", got, want)
	}

	parsed, err := identity.ParseDownloadID(generated.String())
	if err != nil {
		t.Fatal(err)
	}
	payload, err := json.Marshal(parsed)
	if err != nil {
		t.Fatal(err)
	}
	var restored identity.DownloadID
	if err := json.Unmarshal(payload, &restored); err != nil {
		t.Fatal(err)
	}
	if restored != parsed {
		t.Fatalf("restored=%q parsed=%q", restored, parsed)
	}
}

func TestIDsRejectEmptyWrongPrefixAndNonCanonicalTokens(t *testing.T) {
	cases := []string{"", "req_" + token, "dl_ABCDEF00000000000000000000000000", "dl_short"}
	for _, value := range cases {
		if _, err := identity.ParseDownloadID(value); err == nil {
			t.Fatalf("expected %q to fail", value)
		}
	}
	var zero identity.DownloadID
	if _, err := json.Marshal(zero); !errors.Is(err, identity.ErrZeroID) {
		t.Fatalf("zero marshal err=%v", err)
	}
}

func TestUnrelatedIDTypesRemainDistinct(t *testing.T) {
	if reflect.TypeOf(identity.DownloadID("")) == reflect.TypeOf(identity.RequestID("")) {
		t.Fatal("DownloadID and RequestID must remain distinct named types")
	}
	if reflect.TypeOf(identity.MediaID("")) == reflect.TypeOf(identity.CaptureID("")) {
		t.Fatal("MediaID and CaptureID must remain distinct named types")
	}
}

func FuzzParseDownloadID(f *testing.F) {
	f.Add("dl_" + token)
	f.Add("")
	f.Add("dl_" + strings.Repeat("f", 32))
	f.Fuzz(func(t *testing.T, value string) {
		id, err := identity.ParseDownloadID(value)
		if err != nil {
			return
		}
		if id.IsZero() || id.String() != value {
			t.Fatalf("successful parse returned inconsistent id: %q", id)
		}
	})
}
