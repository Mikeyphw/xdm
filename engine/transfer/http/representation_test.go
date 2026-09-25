package httptransfer

import (
	"github.com/subhra74/xdm/engine/domain/resource"
	"testing"
)

func rep(t *testing.T, effective, etag, last string, length int64) Representation {
	t.Helper()
	logical, _ := resource.DeriveIdentity("test", "logical")
	p := ProbeResult{EffectiveURL: effective, ETag: etag, LastModified: last}
	if length >= 0 {
		p.Length = &length
	}
	r, err := RepresentationFromProbe(logical, p)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func TestStrongRepresentationResumeMatrix(t *testing.T) {
	a := rep(t, "https://a.example/file", `"v1"`, "", 100)
	b := rep(t, "https://a.example/file", `"v1"`, "", 100)
	if d := CanResume(a, b, ResumePolicy{}); !d.Allowed || d.Reason != ResumeStrongETagMatch {
		t.Fatalf("decision=%+v", d)
	}
	c := rep(t, "https://a.example/file", `"v2"`, "", 100)
	if d := CanResume(a, c, ResumePolicy{}); d.Allowed || d.Reason != ResumeValidatorChanged {
		t.Fatalf("decision=%+v", d)
	}
	d := rep(t, "https://a.example/file", `"v1"`, "", 101)
	if got := CanResume(a, d, ResumePolicy{}); got.Allowed || got.Reason != ResumeLengthChanged {
		t.Fatalf("decision=%+v", got)
	}
}

func TestWeakerAndMirrorResumeRequireExplicitPolicy(t *testing.T) {
	a := rep(t, "https://a.example/file", "", "Mon, 02 Jan 2006 15:04:05 GMT", 100)
	b := rep(t, "https://a.example/file", "", "Mon, 02 Jan 2006 15:04:05 GMT", 100)
	if d := CanResume(a, b, ResumePolicy{}); d.Allowed || d.Reason != ResumeWeakerPolicyDisabled {
		t.Fatalf("decision=%+v", d)
	}
	if d := CanResume(a, b, ResumePolicy{AllowLastModifiedLength: true}); !d.Allowed {
		t.Fatalf("decision=%+v", d)
	}

	strongA := rep(t, "https://a.example/file", `"same"`, "", 100)
	strongMirror := rep(t, "https://mirror.example/file", `"same"`, "", 100)
	if d := CanResume(strongA, strongMirror, ResumePolicy{}); d.Allowed || d.Reason != ResumeDifferentResource {
		t.Fatalf("decision=%+v", d)
	}
	if d := CanResume(strongA, strongMirror, ResumePolicy{AllowMirrorChange: true}); !d.Allowed || d.Reason != ResumeStrongETagMatch {
		t.Fatalf("decision=%+v", d)
	}
}

func TestWeakETagNeverBecomesStrongResumeIdentity(t *testing.T) {
	a := rep(t, "https://a.example/file", `W/"v1"`, "", 100)
	b := rep(t, "https://a.example/file", `W/"v1"`, "", 100)
	if a.Strength != IdentityUnknown {
		t.Fatalf("strength=%s", a.Strength)
	}
	if d := CanResume(a, b, ResumePolicy{}); d.Allowed {
		t.Fatalf("decision=%+v", d)
	}
}

func TestLastModifiedChangeRejectsWeakerResume(t *testing.T) {
	a := rep(t, "https://a.example/file", "", "Mon, 02 Jan 2006 15:04:05 GMT", 100)
	b := rep(t, "https://a.example/file", "", "Tue, 03 Jan 2006 15:04:05 GMT", 100)
	d := CanResume(a, b, ResumePolicy{AllowLastModifiedLength: true})
	if d.Allowed || d.Reason != ResumeValidatorChanged {
		t.Fatalf("decision=%+v", d)
	}
}
