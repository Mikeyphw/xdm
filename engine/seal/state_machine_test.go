package seal

import (
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/subhra74/xdm/engine/domain/artifact"
	"github.com/subhra74/xdm/engine/domain/attempt"
	"github.com/subhra74/xdm/engine/domain/download"
	"github.com/subhra74/xdm/engine/domain/failure"
	"github.com/subhra74/xdm/engine/domain/identity"
)

type seedCorpus struct {
	SchemaVersion int      `json:"schema_version"`
	Operations    []string `json:"operations"`
	Invariants    []string `json:"invariants"`
	Seeds         []struct {
		ID         string      `json:"id"`
		Operations []Operation `json:"operations"`
	} `json:"seeds"`
}

type machine struct {
	dl                 download.Download
	current            attempt.Attempt
	hasAttempt         bool
	generation         int64
	artifactGeneration int64
	currentArtifact    artifact.Artifact
	hasArtifact        bool
	published          bool
	crashed            bool
	backend            string
	lastGeneration     int64
}

func newMachine(t *testing.T) *machine {
	t.Helper()
	did, err := identity.ParseDownloadID("dl_22222222222222222222222222222222")
	if err != nil {
		t.Fatal(err)
	}
	rid, err := identity.ParseRequestID("req_11111111111111111111111111111111")
	if err != nil {
		t.Fatal(err)
	}
	dl, err := download.New(did, rid)
	if err != nil {
		t.Fatal(err)
	}
	return &machine{dl: dl, backend: "native"}
}

func (m *machine) startAttempt(t *testing.T, backend string) {
	t.Helper()
	if m.dl.State() != download.Open {
		return
	}
	m.generation++
	g, err := identity.NewAttemptGeneration(m.generation)
	if err != nil {
		t.Fatal(err)
	}
	next, err := m.dl.BeginAttempt(g)
	if err != nil {
		t.Fatal(err)
	}
	a, err := attempt.New(next.ID(), g, backend)
	if err != nil {
		t.Fatal(err)
	}
	a, err = a.Transition(attempt.Prepared)
	if err != nil {
		t.Fatal(err)
	}
	a, err = a.Transition(attempt.Running)
	if err != nil {
		t.Fatal(err)
	}
	m.dl = next
	m.current = a
	m.hasAttempt = true
	m.backend = backend
	m.hasArtifact = false
	m.published = false
	m.crashed = false
}

func (m *machine) apply(t *testing.T, op Operation) {
	t.Helper()
	beforeState := m.dl.State()
	beforeGeneration := m.generation

	if beforeState == download.Deleted {
		// Deleted lifetime is immutable. Every later operation must be a no-op.
		m.assertInvariants(t)
		if m.dl.State() != download.Deleted || m.generation != beforeGeneration {
			t.Fatalf("INV-XGO-DELETE-001 violated by %s", op)
		}
		return
	}

	switch op {
	case Start:
		if !m.hasAttempt {
			m.startAttempt(t, m.backend)
		}
	case Pause:
		if m.hasAttempt && m.current.State() == attempt.Running {
			if next, err := m.current.Transition(attempt.Paused); err == nil {
				m.current = next
			}
		}
	case Resume:
		if m.hasAttempt && m.current.State() == attempt.Paused && !m.crashed {
			if next, err := m.current.Transition(attempt.Running); err == nil {
				m.current = next
			}
		}
	case Cancel:
		if m.hasAttempt && !m.current.State().Terminal() {
			if next, err := m.current.Cancel(); err == nil {
				m.current = next
			}
		}
	case Retry:
		if m.hasAttempt && (m.current.State() == attempt.Failed || m.current.State() == attempt.Cancelled) {
			m.startAttempt(t, m.backend)
		}
	case MigrateBackend:
		if m.hasAttempt && (m.current.State() == attempt.Running || m.current.State() == attempt.Paused) {
			oldGen := m.current.Generation()
			nextBackend := "aria2"
			if m.backend == "aria2" {
				nextBackend = "native"
			}
			m.startAttempt(t, nextBackend)
			staleArtifactGen, _ := identity.NewArtifactGeneration(m.artifactGeneration + 1)
			staleArtifact, _ := artifact.NewVerified(m.dl.ID(), staleArtifactGen, oldGen, 1)
			if _, err := m.dl.AcceptArtifact(staleArtifact); !errors.Is(err, download.ErrStaleAttempt) {
				t.Fatalf("INV-XGO-OWNERSHIP-001 stale generation accepted after migration: %v", err)
			}
		}
	case BackendLoss:
		if m.hasAttempt && !m.current.State().Terminal() {
			info, err := failure.NewDefault(failure.BackendUnavailable, errors.New("seal backend unavailable"))
			if err != nil {
				t.Fatal(err)
			}
			if next, err := m.current.Fail(info); err == nil {
				m.current = next
			}
		}
	case Crash:
		m.crashed = true
	case Recover:
		if m.crashed {
			m.crashed = false
			if m.hasAttempt && m.current.State() == attempt.Running {
				if next, err := m.current.Transition(attempt.Paused); err == nil {
					m.current = next
				}
			}
		}
	case Verify:
		if m.hasAttempt && !m.crashed && m.current.State() == attempt.Running {
			transport, err := m.current.Transition(attempt.TransportComplete)
			if err != nil {
				t.Fatal(err)
			}
			m.artifactGeneration++
			ag, _ := identity.NewArtifactGeneration(m.artifactGeneration)
			art, err := artifact.NewVerified(m.dl.ID(), ag, transport.Generation(), 1024)
			if err != nil {
				t.Fatal(err)
			}
			nextDL, err := m.dl.AcceptArtifact(art)
			if err != nil {
				t.Fatal(err)
			}
			produced, err := transport.Transition(attempt.ProducedArtifact)
			if err != nil {
				t.Fatal(err)
			}
			m.current = produced
			m.currentArtifact = art
			m.hasArtifact = true
			m.dl = nextDL
		}
	case Publish:
		if m.hasArtifact && !m.crashed && m.dl.State() == download.Open {
			publishing, err := m.currentArtifact.TransitionPublication(artifact.Publishing)
			if err != nil {
				t.Fatal(err)
			}
			published, err := publishing.TransitionPublication(artifact.Published)
			if err != nil {
				t.Fatal(err)
			}
			nextDL, err := m.dl.Complete(published.Generation())
			if err != nil {
				t.Fatal(err)
			}
			m.currentArtifact = published
			m.published = true
			m.dl = nextDL
		}
	case Delete:
		if next, err := m.dl.Delete(); err == nil {
			m.dl = next
		}
	default:
		t.Fatalf("unknown operation %q", op)
	}

	if m.generation < beforeGeneration {
		t.Fatalf("INV-XGO-GENERATION-001 generation regressed %d -> %d", beforeGeneration, m.generation)
	}
	m.assertInvariants(t)
}

func (m *machine) assertInvariants(t *testing.T) {
	t.Helper()
	if m.generation < m.lastGeneration {
		t.Fatalf("INV-XGO-GENERATION-001 generation regressed: last=%d now=%d", m.lastGeneration, m.generation)
	}
	m.lastGeneration = m.generation

	if m.published && !m.hasArtifact {
		t.Fatal("INV-XGO-PUBLISH-001 publication exists without verified artifact")
	}
	if m.published && m.currentArtifact.PublicationState() != artifact.Published {
		t.Fatalf("INV-XGO-PUBLISH-001 published flag with artifact state %s", m.currentArtifact.PublicationState())
	}
	if m.dl.State() == download.Completed && !m.published {
		t.Fatal("INV-XGO-VERIFY-001 completed download without published verified artifact")
	}
	if m.crashed && m.published && m.dl.State() != download.Completed && m.dl.State() != download.Deleted {
		t.Fatal("INV-XGO-RECOVERY-001 crash changed committed publication truth")
	}
	if m.hasAttempt && m.current.State().Terminal() {
		if _, err := m.current.Transition(attempt.Running); err == nil {
			t.Fatalf("INV-XGO-TERMINAL-001 terminal attempt %s allowed mutation", m.current.State())
		}
	}
}

func loadSeeds(t *testing.T) seedCorpus {
	t.Helper()
	_, thisFile, _, _ := runtime.Caller(0)
	path := filepath.Join(filepath.Dir(thisFile), "..", "testdata", "seal", "state-machine-seeds.json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var corpus seedCorpus
	if err := json.Unmarshal(data, &corpus); err != nil {
		t.Fatal(err)
	}
	if corpus.SchemaVersion != 1 || len(corpus.Seeds) == 0 {
		t.Fatalf("invalid seed corpus: %+v", corpus)
	}
	return corpus
}

func TestStateMachinePermanentRegressionSeeds(t *testing.T) {
	corpus := loadSeeds(t)
	for _, seed := range corpus.Seeds {
		seed := seed
		t.Run(seed.ID, func(t *testing.T) {
			m := newMachine(t)
			for _, op := range seed.Operations {
				m.apply(t, op)
			}
		})
	}
}

func TestStateMachineDeterministicPropertyCampaign(t *testing.T) {
	const cases = 256
	const steps = 96
	for seed := int64(1); seed <= cases; seed++ {
		seed := seed
		t.Run(fmt.Sprintf("seed-%03d", seed), func(t *testing.T) {
			rng := rand.New(rand.NewSource(seed))
			m := newMachine(t)
			for step := 0; step < steps; step++ {
				op := Operations[rng.Intn(len(Operations))]
				m.apply(t, op)
			}
		})
	}
}
