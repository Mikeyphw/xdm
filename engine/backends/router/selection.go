package router

import (
	"fmt"
	"sort"
	"strings"
)

type Health string

const (
	HealthHealthy     Health = "healthy"
	HealthDegraded    Health = "degraded"
	HealthUnavailable Health = "unavailable"
)

type MigrationCost string

const (
	MigrationNone    MigrationCost = "none"
	MigrationReuse   MigrationCost = "reuse"
	MigrationRestart MigrationCost = "restart"
	MigrationUnsafe  MigrationCost = "unsafe"
)

type BackendState struct {
	RuntimeAvailable bool          `json:"runtime_available"`
	Health           Health        `json:"health"`
	MigrationCost    MigrationCost `json:"migration_cost"`
}

type SelectionRejectReason string

const (
	RejectCompatibility   SelectionRejectReason = "compatibility"
	RejectRuntime         SelectionRejectReason = "runtime_unavailable"
	RejectHealth          SelectionRejectReason = "health_unavailable"
	RejectFallback        SelectionRejectReason = "fallback_disabled"
	RejectMigration       SelectionRejectReason = "migration_required"
	RejectMigrationUnsafe SelectionRejectReason = "migration_unsafe"
)

type SelectionRejection struct {
	Reason SelectionRejectReason `json:"reason"`
	Detail string                `json:"detail"`
}

type CandidateDecision struct {
	Backend       Kind                 `json:"backend"`
	Score         int                  `json:"score"`
	Compatibility CompatibilityResult  `json:"compatibility"`
	Rejections    []SelectionRejection `json:"rejections,omitempty"`
	Notes         []string             `json:"notes,omitempty"`
}

type SelectionReason string

const (
	SelectExistingAttempt SelectionReason = "existing_attempt"
	SelectUserPreference  SelectionReason = "user_preference"
	SelectFallback        SelectionReason = "fallback"
	SelectRequiredShape   SelectionReason = "required_shape"
	SelectMirrors         SelectionReason = "mirror_workload"
	SelectFTP             SelectionReason = "ftp_protocol"
	SelectLargeTransfer   SelectionReason = "large_transfer"
	SelectDirectMedia     SelectionReason = "direct_media"
	SelectHealthyDefault  SelectionReason = "healthy_default"
)

type SelectionInput struct {
	Operation      Operation             `json:"operation"`
	States         map[Kind]BackendState `json:"states"`
	CurrentBackend Kind                  `json:"current_backend,omitempty"`
	AttemptStarted bool                  `json:"attempt_started"`
}

type Decision struct {
	CanStart    bool                `json:"can_start"`
	Selected    Kind                `json:"selected,omitempty"`
	Reason      SelectionReason     `json:"reason,omitempty"`
	Explanation string              `json:"explanation"`
	Fallback    bool                `json:"fallback"`
	Candidates  []CandidateDecision `json:"candidates"`
}

func defaultState() BackendState {
	return BackendState{RuntimeAvailable: false, Health: HealthUnavailable, MigrationCost: MigrationNone}
}

func validateState(s BackendState) error {
	if s.Health == "" {
		s.Health = HealthHealthy
	}
	switch s.Health {
	case HealthHealthy, HealthDegraded, HealthUnavailable:
	default:
		return fmt.Errorf("invalid health %q", s.Health)
	}
	if s.MigrationCost == "" {
		s.MigrationCost = MigrationNone
	}
	switch s.MigrationCost {
	case MigrationNone, MigrationReuse, MigrationRestart, MigrationUnsafe:
	default:
		return fmt.Errorf("invalid migration cost %q", s.MigrationCost)
	}
	return nil
}

func backendFor(kind Kind) Backend {
	if kind == Aria2 {
		return Aria2Backend()
	}
	return NativeBackend()
}

func hasHint(r CompatibilityResult, h PreferenceHint) bool {
	for _, got := range r.Hints {
		if got == h {
			return true
		}
	}
	return false
}

func scoreCandidate(c *CandidateDecision, state BackendState, preferred Kind, explicit bool, current Kind) {
	if explicit && c.Backend == preferred {
		c.Score += 10000
	}
	if state.Health == HealthHealthy {
		c.Score += 200
	} else if state.Health == HealthDegraded {
		c.Score -= 200
		c.Notes = append(c.Notes, "backend health is degraded")
	}
	if c.Backend == Native {
		c.Score += 100
	}
	if hasHint(c.Compatibility, HintNativePOST) {
		c.Score += 900
	}
	if hasHint(c.Compatibility, HintNativeFTPS) {
		c.Score += 700
	}
	if hasHint(c.Compatibility, HintNativeCredential) {
		c.Score += 600
	}
	if hasHint(c.Compatibility, HintNativeDestination) {
		c.Score += 500
	}
	if hasHint(c.Compatibility, HintNativeDirectMedia) {
		c.Score += 400
	}
	if hasHint(c.Compatibility, HintAria2Mirrors) {
		c.Score += 800
	}
	if hasHint(c.Compatibility, HintAria2FTP) {
		c.Score += 600
	}
	if hasHint(c.Compatibility, HintAria2Large) {
		c.Score += 500
	}
	if current != "" && c.Backend != current {
		switch state.MigrationCost {
		case MigrationReuse:
			c.Score -= 50
			c.Notes = append(c.Notes, "migration reuses verified state")
		case MigrationRestart:
			c.Score -= 400
			c.Notes = append(c.Notes, "migration requires restart")
		}
	}
}

func selectionReason(chosen CandidateDecision, preferred Kind, explicit, fallback bool) SelectionReason {
	if fallback {
		return SelectFallback
	}
	if explicit && chosen.Backend == preferred {
		return SelectUserPreference
	}
	for _, h := range chosen.Compatibility.Hints {
		switch h {
		case HintNativePOST, HintNativeFTPS, HintNativeCredential, HintNativeDestination:
			return SelectRequiredShape
		case HintAria2Mirrors:
			return SelectMirrors
		case HintAria2FTP:
			return SelectFTP
		case HintAria2Large:
			return SelectLargeTransfer
		case HintNativeDirectMedia:
			return SelectDirectMedia
		}
	}
	return SelectHealthyDefault
}

func Select(in SelectionInput) (Decision, error) {
	op, err := NewOperation(in.Operation)
	if err != nil {
		return Decision{}, err
	}
	preferred, explicit, err := normalizedPreference(op.Request.BackendPreference)
	if err != nil {
		return Decision{}, err
	}
	if in.AttemptStarted && !in.CurrentBackend.Valid() {
		return Decision{}, fmt.Errorf("%w: attempt started without current backend", ErrInvalidOperation)
	}

	decision := Decision{Candidates: make([]CandidateDecision, 0, 2)}
	for _, kind := range Kinds() {
		state, ok := in.States[kind]
		if !ok {
			state = defaultState()
		}
		if state.Health == "" {
			state.Health = HealthHealthy
		}
		if state.MigrationCost == "" {
			state.MigrationCost = MigrationNone
		}
		if err := validateState(state); err != nil {
			return Decision{}, err
		}
		c := CandidateDecision{Backend: kind, Compatibility: backendFor(kind).CanExecute(op)}
		if !c.Compatibility.Compatible {
			c.Rejections = append(c.Rejections, SelectionRejection{Reason: RejectCompatibility, Detail: string(c.Compatibility.FirstReason())})
		}
		if !state.RuntimeAvailable {
			c.Rejections = append(c.Rejections, SelectionRejection{Reason: RejectRuntime, Detail: "runtime is not available"})
		}
		if state.Health == HealthUnavailable {
			c.Rejections = append(c.Rejections, SelectionRejection{Reason: RejectHealth, Detail: "backend health is unavailable"})
		}
		if explicit && !op.Request.AllowBackendFallback && kind != preferred {
			c.Rejections = append(c.Rejections, SelectionRejection{Reason: RejectFallback, Detail: "explicit backend preference forbids fallback"})
		}
		if in.AttemptStarted && kind != in.CurrentBackend {
			c.Rejections = append(c.Rejections, SelectionRejection{Reason: RejectMigration, Detail: "started attempts require explicit backend migration"})
		}
		if in.CurrentBackend.Valid() && kind != in.CurrentBackend && state.MigrationCost == MigrationUnsafe {
			c.Rejections = append(c.Rejections, SelectionRejection{Reason: RejectMigrationUnsafe, Detail: "migration inspection marked target unsafe"})
		}
		scoreCandidate(&c, state, preferred, explicit, in.CurrentBackend)
		decision.Candidates = append(decision.Candidates, c)
	}

	// Candidate evidence is itself ranked output: executable candidates first,
	// then descending deterministic score, then backend kind as a stable tie-break.
	// This keeps rejection evidence explainable without a second frontend sort.
	sort.SliceStable(decision.Candidates, func(i, j int) bool {
		iViable := len(decision.Candidates[i].Rejections) == 0
		jViable := len(decision.Candidates[j].Rejections) == 0
		if iViable != jViable {
			return iViable
		}
		if decision.Candidates[i].Score == decision.Candidates[j].Score {
			return decision.Candidates[i].Backend < decision.Candidates[j].Backend
		}
		return decision.Candidates[i].Score > decision.Candidates[j].Score
	})
	viable := make([]CandidateDecision, 0, 2)
	for _, c := range decision.Candidates {
		if len(c.Rejections) == 0 {
			viable = append(viable, c)
		}
	}
	if len(viable) == 0 {
		decision.Explanation = "No compatible, available backend may start this operation."
		return decision, nil
	}
	chosen := viable[0]
	decision.CanStart = true
	decision.Selected = chosen.Backend
	fallback := explicit && chosen.Backend != preferred
	decision.Fallback = fallback
	if in.AttemptStarted {
		decision.Reason = SelectExistingAttempt
		decision.Explanation = "The started attempt remains bound to its existing backend; changing backend requires explicit migration."
		return decision, nil
	}
	decision.Reason = selectionReason(chosen, preferred, explicit, fallback)
	switch decision.Reason {
	case SelectUserPreference:
		decision.Explanation = fmt.Sprintf("%s was selected explicitly and is compatible and available.", chosen.Backend)
	case SelectFallback:
		decision.Explanation = fmt.Sprintf("%s cannot start; fallback selected %s before attempt execution.", preferred, chosen.Backend)
	case SelectMirrors:
		decision.Explanation = "aria2 was selected because the canonical request contains mirror semantics."
	case SelectFTP:
		decision.Explanation = "aria2 was selected for a compatible FTP transfer."
	case SelectLargeTransfer:
		decision.Explanation = "aria2 was selected for a large compatible transfer."
	case SelectDirectMedia:
		decision.Explanation = "native was selected for a direct media transfer."
	case SelectRequiredShape:
		decision.Explanation = "native was selected because the operation shape requires native execution semantics."
	default:
		decision.Explanation = fmt.Sprintf("%s was selected by the deterministic healthy-backend default.", chosen.Backend)
	}
	return decision, nil
}

func (d Decision) SafeSummary() string {
	parts := []string{fmt.Sprintf("selected=%s", d.Selected), fmt.Sprintf("reason=%s", d.Reason), fmt.Sprintf("fallback=%t", d.Fallback)}
	return strings.Join(parts, " ")
}
