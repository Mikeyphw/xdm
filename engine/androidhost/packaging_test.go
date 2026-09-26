package androidhost

import "testing"

func TestSharedLibraryPlan(t *testing.T) {
	plan, err := NewSharedLibraryPlan("abc123", "go1.23.0")
	if err != nil {
		t.Fatal(err)
	}
	if err := plan.Validate(); err != nil {
		t.Fatal(err)
	}
	if len(plan.Artifacts) != 1 || plan.Artifacts[0].ABI != "arm64-v8a" {
		t.Fatalf("unexpected artifacts: %+v", plan.Artifacts)
	}
	if plan.Artifacts[0].Output != "app/XDM.Android/app/src/main/jniLibs/arm64-v8a/libxdmcore.so" {
		t.Fatalf("wrong output: %s", plan.Artifacts[0].Output)
	}
	if plan.Manifest.MetadataSHA256 == "" {
		t.Fatalf("metadata hash missing")
	}
}

func TestRequiredExportsAreExact(t *testing.T) {
	if err := ValidateExports(RequiredSymbols()); err != nil {
		t.Fatal(err)
	}
	bad := append(RequiredSymbols(), "xdm_domain_decision_in_kotlin")
	if err := ValidateExports(bad); err == nil {
		t.Fatalf("unexpected extra export accepted")
	}
}

func TestBridgeCodecAndBufferLease(t *testing.T) {
	codec := NewBridgeCodec(WireAPIVersion)
	encoded, err := codec.Encode("runtime.ping", map[string]string{"hello": "world"})
	if err != nil {
		t.Fatal(err)
	}
	msg, err := codec.Decode(encoded)
	if err != nil {
		t.Fatal(err)
	}
	if msg.Kind != "runtime.ping" {
		t.Fatalf("bad kind %s", msg.Kind)
	}
	if _, err := codec.Decode([]byte(`{"version":"future","kind":"x"}`)); err != ErrProtocolVersion {
		t.Fatalf("protocol mismatch err=%v", err)
	}
	lease := NewBufferLease(1, []byte("abc"))
	if err := lease.Release(); err != nil {
		t.Fatal(err)
	}
	if err := lease.Release(); err != ErrBufferAlreadyReleased {
		t.Fatalf("double release err=%v", err)
	}
}

func TestEngineAuthoritySingleInstance(t *testing.T) {
	a := NewEngineAuthority()
	first, err := a.BindClient()
	if err != nil {
		t.Fatal(err)
	}
	recreated, err := a.ActivityRecreated()
	if err != nil {
		t.Fatal(err)
	}
	if first != recreated {
		t.Fatalf("activity recreate created a second engine: %s vs %s", first, recreated)
	}
	dup, err := a.DuplicateStartIntent()
	if err != nil {
		t.Fatal(err)
	}
	if dup != first {
		t.Fatalf("duplicate start created %s want %s", dup, first)
	}
	restarted, err := a.ProcessRestart()
	if err != nil {
		t.Fatal(err)
	}
	if restarted == first {
		t.Fatalf("process restart did not create recovered engine identity")
	}
	if err := a.Shutdown(); err != nil {
		t.Fatal(err)
	}
}
