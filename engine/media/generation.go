package media

import (
	"fmt"
	"sort"
)

type GenerationStatus string

const (
	GenerationAccepted   GenerationStatus = "accepted"
	GenerationHistorical GenerationStatus = "historical"
	GenerationRejected   GenerationStatus = "rejected"
)

type Observation struct {
	SessionID          string
	DocumentGeneration int64
	FrameOrigin        string
	EvidenceKey        string
}

type GenerationDecision struct {
	Status             GenerationStatus
	MutatesActiveGraph bool
	HistoricalRetained bool
	Reason             string
}

type GenerationFence struct {
	sessionID  string
	active     int64
	retain     int
	historical []Observation
}

func NewGenerationFence(sessionID string, activeGeneration int64, retention int) (*GenerationFence, error) {
	if sessionID == "" || activeGeneration <= 0 {
		return nil, fmt.Errorf("%w: generation fence", ErrInvalidCaptureEnvelope)
	}
	if retention < 0 {
		retention = 0
	}
	return &GenerationFence{sessionID: sessionID, active: activeGeneration, retain: retention}, nil
}

func (f *GenerationFence) ActiveGeneration() int64 { return f.active }

func (f *GenerationFence) Navigate(newGeneration int64) error {
	if newGeneration <= f.active {
		return fmt.Errorf("%w: non-monotonic navigation generation", ErrInvalidCaptureEnvelope)
	}
	f.active = newGeneration
	f.trimHistorical()
	return nil
}

func (f *GenerationFence) Evaluate(obs Observation) GenerationDecision {
	if obs.SessionID != f.sessionID {
		return GenerationDecision{Status: GenerationRejected, Reason: "session_mismatch"}
	}
	if obs.DocumentGeneration == f.active {
		return GenerationDecision{Status: GenerationAccepted, MutatesActiveGraph: true, Reason: "active_generation"}
	}
	if obs.DocumentGeneration < f.active {
		retained := f.remember(obs)
		return GenerationDecision{Status: GenerationHistorical, HistoricalRetained: retained, Reason: "stale_generation"}
	}
	return GenerationDecision{Status: GenerationRejected, Reason: "future_generation"}
}

func (f *GenerationFence) Historical() []Observation {
	out := append([]Observation(nil), f.historical...)
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].DocumentGeneration == out[j].DocumentGeneration {
			return out[i].EvidenceKey < out[j].EvidenceKey
		}
		return out[i].DocumentGeneration < out[j].DocumentGeneration
	})
	return out
}

func (f *GenerationFence) remember(obs Observation) bool {
	if f.retain == 0 {
		return false
	}
	f.historical = append(f.historical, obs)
	f.trimHistorical()
	return true
}

func (f *GenerationFence) trimHistorical() {
	if f.retain == 0 {
		f.historical = nil
		return
	}
	if len(f.historical) <= f.retain {
		return
	}
	f.historical = append([]Observation(nil), f.historical[len(f.historical)-f.retain:]...)
}
