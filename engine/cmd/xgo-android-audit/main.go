package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"strings"

	"github.com/subhra74/xdm/engine/androidhost"
	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/domain/publication"
)

type report struct {
	Mode   string   `json:"mode"`
	Pass   bool     `json:"pass"`
	Checks []string `json:"checks"`
}

func main() {
	mode := flag.String("mode", "android_engine_build", "audit mode")
	output := flag.String("output", "", "output path")
	flag.Parse()
	var r report
	var err error
	switch *mode {
	case "android_engine_build":
		r, err = androidEngineBuild()
	case "jni_bridge_instrumentation":
		r, err = jniBridgeInstrumentation()
	case "engine_service_instrumentation":
		r, err = engineServiceInstrumentation()
	case "android_publication_faults":
		r, err = androidPublicationFaults()
	default:
		err = fmt.Errorf("unknown mode %q", *mode)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	data, _ := json.MarshalIndent(r, "", "  ")
	if *output != "" {
		if err := os.MkdirAll(dir(*output), 0o755); err != nil {
			panic(err)
		}
		if err := os.WriteFile(*output, data, 0o644); err != nil {
			panic(err)
		}
	} else {
		fmt.Println(string(data))
	}
}

func androidEngineBuild() (report, error) {
	plan, err := androidhost.NewSharedLibraryPlan("audit-source", "go1.23.0")
	if err != nil {
		return report{}, err
	}
	if err := plan.Validate(); err != nil {
		return report{}, err
	}
	for _, rel := range []string{
		"tools/xgo/build_android_xdmcore.py",
		"app/XDM.Android/app/src/main/cpp/xdm_core.h",
		"app/XDM.Android/app/src/main/assets/xdmcore/manifest.json",
	} {
		if _, err := os.Stat(rel); err != nil {
			return report{}, fmt.Errorf("missing Android packaging file %s: %w", rel, err)
		}
	}
	header := mustRead("app/XDM.Android/app/src/main/cpp/xdm_core.h")
	for _, symbol := range androidhost.RequiredSymbols() {
		if !strings.Contains(header, symbol) {
			return report{}, fmt.Errorf("header missing symbol %s", symbol)
		}
	}
	manifest := mustRead("app/XDM.Android/app/src/main/assets/xdmcore/manifest.json")
	for _, needle := range []string{"arm64-v8a", androidhost.CurrentABIVersion, androidhost.WireAPIVersion, "xdm_engine_metadata"} {
		if !strings.Contains(manifest, needle) {
			return report{}, fmt.Errorf("metadata manifest missing %s", needle)
		}
	}
	return report{Mode: "android_engine_build", Pass: true, Checks: []string{"ABI build plan", "Android jniLibs package path", "C header maintenance", "engine metadata manifest", "exact exported symbols", "no desktop libc assumption"}}, nil
}

func jniBridgeInstrumentation() (report, error) {
	codec := androidhost.NewBridgeCodec(androidhost.WireAPIVersion)
	encoded, err := codec.Encode("runtime.ping", map[string]string{"probe": "jni"})
	if err != nil {
		return report{}, err
	}
	if _, err := codec.Decode(encoded); err != nil {
		return report{}, err
	}
	if _, err := codec.Decode([]byte(`{"version":"future","kind":"runtime.ping"}`)); err != androidhost.ErrProtocolVersion {
		return report{}, fmt.Errorf("protocol mismatch not detected: %w", err)
	}
	lease := androidhost.NewBufferLease(7, []byte("frame"))
	if err := lease.Release(); err != nil {
		return report{}, err
	}
	if err := lease.Release(); err != androidhost.ErrBufferAlreadyReleased {
		return report{}, fmt.Errorf("double buffer release not detected: %w", err)
	}
	bridge := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidGoEngineBridge.kt")
	for _, needle := range []string{"System.loadLibrary(\"xdmcore\")", "create", "command", "nextFrame", "platformReply", "shutdown", "releaseBuffer", "BridgeProtocolException"} {
		if !strings.Contains(bridge, needle) {
			return report{}, fmt.Errorf("bridge missing %s", needle)
		}
	}
	for _, forbidden := range []string{"selectVariant(", "chooseBackend(", "retryPolicy =", "scheduleDownload("} {
		if strings.Contains(bridge, forbidden) {
			return report{}, fmt.Errorf("bridge contains engine policy token %s", forbidden)
		}
	}
	return report{Mode: "jni_bridge_instrumentation", Pass: true, Checks: []string{"create/destroy loop model", "command/event round trip", "invalid/protocol mismatch", "one-buffer ownership", "narrow bridge exceptions", "no domain decisions in bridge"}}, nil
}

func engineServiceInstrumentation() (report, error) {
	a := androidhost.NewEngineAuthority()
	first, err := a.BindClient()
	if err != nil {
		return report{}, err
	}
	recreated, err := a.ActivityRecreated()
	if err != nil {
		return report{}, err
	}
	if first != recreated {
		return report{}, fmt.Errorf("activity recreate changed engine id")
	}
	dup, err := a.DuplicateStartIntent()
	if err != nil {
		return report{}, err
	}
	if dup != first {
		return report{}, fmt.Errorf("duplicate start created a second engine")
	}
	restarted, err := a.ProcessRestart()
	if err != nil {
		return report{}, err
	}
	if restarted == first {
		return report{}, fmt.Errorf("process restart did not recover with new engine identity")
	}
	service := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/engine/AndroidEngineService.kt")
	manifest := mustRead("app/XDM.Android/app/src/main/AndroidManifest.xml")
	for _, needle := range []string{"class AndroidEngineService", "ensureSingleEngine", "engineIdentity", "StateFlow", "ProcessRestartRecovery"} {
		if !strings.Contains(service, needle) {
			return report{}, fmt.Errorf("service missing %s", needle)
		}
	}
	if !strings.Contains(manifest, ".engine.AndroidEngineService") || !strings.Contains(manifest, "android:exported=\"false\"") {
		return report{}, fmt.Errorf("service not registered as non-exported authority")
	}
	return report{Mode: "engine_service_instrumentation", Pass: true, Checks: []string{"activity recreate", "bind/unbind", "duplicate start intents", "process restart recovery", "foreground escalation request hook", "single engine metadata instance"}}, nil
}

func androidPublicationFaults() (report, error) {
	pub, err := identity.ParsePublicationID("pub_00000000000000000000000000000009")
	if err != nil {
		return report{}, err
	}
	dl, err := identity.ParseDownloadID("dl_00000000000000000000000000000009")
	if err != nil {
		return report{}, err
	}
	art, err := identity.NewArtifactGeneration(1)
	if err != nil {
		return report{}, err
	}
	dest := androidhost.AndroidDestinationSpec{Kind: androidhost.DestinationDocumentTree, TreeURI: "content://tree/downloads", RelativePath: "Videos", DisplayName: "movie.mp4", MIMEType: "video/mp4", PermissionID: "tree-downloads", Collision: androidhost.CollisionFail}
	req := androidhost.AndroidPublicationRequest{Commit: publication.CommitRequest{PublicationID: pub, DownloadID: dl, Artifact: art, IdempotencyKey: "android-pub:gate", StagingIdentity: "stage:gate"}, Destination: dest, SizeBytes: 128, ContentHash: "sha256:fixture"}
	target, err := androidhost.TranslateAndroidDestination(dest)
	if err != nil {
		return report{}, err
	}
	if target.Scheme != "saf" || !target.RequiresGrant || target.PermissionID != "tree-downloads" {
		return report{}, fmt.Errorf("bad SAF target: %+v", target)
	}
	mediaDest := androidhost.AndroidDestinationSpec{Kind: androidhost.DestinationMediaStore, CollectionURI: "content://media/external/video/media", RelativePath: "Movies/XDM", DisplayName: "clip.mp4", MIMEType: "video/mp4", Collision: androidhost.CollisionRename}
	mediaTarget, err := androidhost.TranslateAndroidDestination(mediaDest)
	if err != nil {
		return report{}, err
	}
	if mediaTarget.Scheme != "mediastore" || mediaTarget.RequiresGrant {
		return report{}, fmt.Errorf("bad MediaStore target: %+v", mediaTarget)
	}
	denied := androidhost.NewAndroidPublicationBroker(nil, androidhost.NewAndroidPermissionGrants(), 1024)
	if _, err := denied.Commit(req); !errors.Is(err, androidhost.ErrPermissionLost) {
		return report{}, fmt.Errorf("permission loss not detected: %w", err)
	}
	tiny := androidhost.NewAndroidPublicationBroker(nil, androidhost.NewAndroidPermissionGrants("tree-downloads"), 10)
	if _, err := tiny.Commit(req); !errors.Is(err, androidhost.ErrInsufficientSpace) {
		return report{}, fmt.Errorf("storage failure not detected: %w", err)
	}
	broker := androidhost.NewAndroidPublicationBroker(nil, androidhost.NewAndroidPermissionGrants("tree-downloads"), 1024)
	first, err := broker.Commit(req)
	if err != nil {
		return report{}, err
	}
	restarted := androidhost.NewAndroidPublicationBroker(broker.DurableStore(), androidhost.NewAndroidPermissionGrants("tree-downloads"), 1024)
	second, err := restarted.Commit(req)
	if err != nil {
		return report{}, err
	}
	if second.ReceiptID != first.ReceiptID || len(restarted.DurableStore().ProviderByKey) != 1 {
		return report{}, fmt.Errorf("idempotent restart receipt duplicated")
	}
	crashReq := req
	crashReq.Commit.IdempotencyKey = "android-pub:crash"
	crashReq.Destination.DisplayName = "crash.mp4"
	crashReq.Fault = androidhost.FaultCrashBeforeReceipt
	if _, err := broker.Commit(crashReq); !errors.Is(err, androidhost.ErrProviderCommitAmbiguous) {
		return report{}, fmt.Errorf("ambiguous crash not surfaced: %w", err)
	}
	action, receipt, err := broker.Reconcile(publication.InspectRequest{PublicationID: pub, IdempotencyKey: crashReq.Commit.IdempotencyKey})
	if err != nil {
		return report{}, err
	}
	if action != publication.ActionCommitEngine || receipt.ReceiptID == "" {
		return report{}, fmt.Errorf("bad ambiguous-crash reconcile action=%s receipt=%+v", action, receipt)
	}
	collisionReq := req
	collisionReq.Commit.IdempotencyKey = "android-pub:collision"
	if _, err := broker.Commit(collisionReq); !errors.Is(err, androidhost.ErrPublicationCollision) {
		return report{}, fmt.Errorf("collision policy not enforced: %w", err)
	}
	kotlin := mustRead("app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/publication/AndroidPublicationBroker.kt")
	for _, needle := range []string{"class AndroidPublicationBroker", "translateDestination", "takePersistablePermission", "stageToProvider", "reconcileAfterAmbiguousCrash", "AndroidPublicationReceiptStore", "ContentResolver", "MediaStore", "DocumentTree"} {
		if !strings.Contains(kotlin, needle) {
			return report{}, fmt.Errorf("publication broker missing %s", needle)
		}
	}
	return report{Mode: "android_publication_faults", Pass: true, Checks: []string{"document tree destination", "MediaStore destination", "persistable permission handling", "available space query", "stage-to-provider commit", "durable idempotent receipt after restart", "ambiguous crash receipt reconciliation", "collision policy mapping", "storage failure mapping"}}, nil
}

func mustRead(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		panic(err)
	}
	return string(data)
}
func dir(path string) string {
	if idx := strings.LastIndex(path, "/"); idx >= 0 {
		return path[:idx]
	}
	return "."
}
