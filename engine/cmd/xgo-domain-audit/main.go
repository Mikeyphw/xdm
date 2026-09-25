package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"

	"github.com/subhra74/xdm/engine/domain/artifact"
	"github.com/subhra74/xdm/engine/domain/attempt"
	"github.com/subhra74/xdm/engine/domain/download"
	"github.com/subhra74/xdm/engine/domain/failure"
)

type report struct {
	SchemaVersion int            `json:"schema_version"`
	Mode          string         `json:"mode"`
	Status        string         `json:"status"`
	Counts        map[string]int `json:"counts"`
}

func failf(format string, args ...any) { fmt.Fprintf(os.Stderr, format+"\n", args...); os.Exit(1) }

func auditGraph(name string, states []string, terminal map[string]bool, graph map[string][]string) {
	seen := map[string]bool{}
	for _, state := range states {
		if seen[state] {
			failf("%s duplicate state %s", name, state)
		}
		seen[state] = true
	}
	for _, state := range states {
		values, ok := graph[state]
		if !ok {
			failf("%s state missing from graph: %s", name, state)
		}
		if terminal[state] && len(values) != 0 {
			failf("%s terminal state has outgoing transitions: %s", name, state)
		}
		for _, to := range values {
			if !seen[to] {
				failf("%s transition to unknown state: %s -> %s", name, state, to)
			}
		}
	}
}

func stateMachineReport() report {
	agraph := map[string][]string{}
	aterm := map[string]bool{}
	astates := []string{}
	for _, s := range attempt.States() {
		astates = append(astates, string(s))
		aterm[string(s)] = s.Terminal()
	}
	for from, tos := range attempt.AllowedTransitions() {
		for _, to := range tos {
			agraph[string(from)] = append(agraph[string(from)], string(to))
		}
		if _, ok := agraph[string(from)]; !ok {
			agraph[string(from)] = nil
		}
	}
	auditGraph("attempt", astates, aterm, agraph)

	dgraph := map[string][]string{}
	dterm := map[string]bool{}
	dstates := []string{}
	for _, s := range download.States() {
		dstates = append(dstates, string(s))
		dterm[string(s)] = s.Terminal()
	}
	for from, tos := range download.AllowedTransitions() {
		for _, to := range tos {
			dgraph[string(from)] = append(dgraph[string(from)], string(to))
		}
		if _, ok := dgraph[string(from)]; !ok {
			dgraph[string(from)] = nil
		}
	}
	auditGraph("download", dstates, dterm, dgraph)

	pgraph := map[string][]string{}
	pterm := map[string]bool{}
	pstates := []string{}
	for _, s := range artifact.PublicationStates() {
		pstates = append(pstates, string(s))
		pterm[string(s)] = s.Terminal()
	}
	for from, tos := range artifact.AllowedPublicationTransitions() {
		for _, to := range tos {
			pgraph[string(from)] = append(pgraph[string(from)], string(to))
		}
		if _, ok := pgraph[string(from)]; !ok {
			pgraph[string(from)] = nil
		}
	}
	auditGraph("artifact_publication", pstates, pterm, pgraph)

	return report{1, "state-machines", "pass", map[string]int{"attempt_states": len(astates), "download_states": len(dstates), "artifact_publication_states": len(pstates)}}
}

func failureReport() report {
	mappings := failure.DonorMappings()
	seen := map[string]bool{}
	for _, category := range failure.Categories() {
		if _, ok := failure.DefaultPolicy(category); !ok {
			failf("category missing policy: %s", category)
		}
	}
	for _, m := range mappings {
		key := m.Donor + ":" + m.MatchKey
		if seen[key] {
			failf("duplicate donor mapping: %s", key)
		}
		seen[key] = true
		if _, err := failure.New(m.Category, m.Retry, m.UserAction, nil); err != nil {
			failf("invalid donor mapping %s: %v", key, err)
		}
	}
	keys := make([]string, 0, len(seen))
	for key := range seen {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return report{1, "failure-mapping", "pass", map[string]int{"categories": len(failure.Categories()), "donor_mappings": len(mappings)}}
}

func main() {
	mode := flag.String("mode", "", "state-machines or failure-mapping")
	output := flag.String("output", "", "optional JSON report path")
	flag.Parse()
	var r report
	switch *mode {
	case "state-machines":
		r = stateMachineReport()
	case "failure-mapping":
		r = failureReport()
	default:
		failf("unknown --mode %q", *mode)
	}
	data, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		failf("marshal report: %v", err)
	}
	data = append(data, '\n')
	if *output != "" {
		if err := os.MkdirAll(filepath.Dir(*output), 0o755); err != nil {
			failf("mkdir output: %v", err)
		}
		if err := os.WriteFile(*output, data, 0o644); err != nil {
			failf("write output: %v", err)
		}
	}
	_, _ = os.Stdout.Write(data)
}
