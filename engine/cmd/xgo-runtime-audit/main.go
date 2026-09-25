package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	engineruntime "github.com/subhra74/xdm/engine/runtime"
	"github.com/subhra74/xdm/engine/runtime/command"
	"github.com/subhra74/xdm/engine/runtime/event"
	"github.com/subhra74/xdm/engine/runtime/platform"
)

const base = "00000000000000000000000000000000"

func ids(n int) (command.ID, identity.OperationID) {
	s := []byte(base)
	hex := fmt.Sprintf("%02x", n%256)
	copy(s[len(s)-2:], hex)
	cid, _ := command.ParseID("cmd_" + string(s))
	op, _ := identity.ParseOperationID("op_" + string(s))
	return cid, op
}

func runtimeStress() (map[string]any, error) {
	e := engineruntime.New(engineruntime.Config{CommandBuffer: 64, EventBuffer: 256})
	if err := e.Start(); err != nil {
		return nil, err
	}
	defer e.Shutdown(context.Background())
	var wg sync.WaitGroup
	for i := 1; i <= 32; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			cid, op := ids(i)
			_ = e.Submit(context.Background(), command.Envelope{ID: cid, OperationID: op, Kind: "runtime.ping", Payload: json.RawMessage(`{"audit":true}`)})
		}(i)
	}
	wg.Wait()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	last := uint64(0)
	completions := 0
	for completions < 32 {
		f, err := e.NextFrame(ctx)
		if err != nil {
			return nil, err
		}
		if f.Sequence <= last {
			return nil, fmt.Errorf("non-monotonic sequence")
		}
		last = f.Sequence
		if f.Kind == "command.completed" {
			completions++
		}
	}
	return map[string]any{"status": "pass", "commands": 32, "last_sequence": last, "completed": completions}, nil
}

func platformMatrix() (map[string]any, error) {
	b := platform.NewBroker(4)
	defer b.Shutdown()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		r, err := b.Request(ctx, platform.RuntimeConditions, platform.Refs{}, json.RawMessage(`{}`))
		if err == nil && !r.OK {
			err = fmt.Errorf("reply not OK")
		}
		done <- err
	}()
	req, err := b.NextRequest(ctx)
	if err != nil {
		return nil, err
	}
	if err := b.Reply(platform.Reply{RequestID: req.ID, Session: req.Session, OK: true}); err != nil {
		return nil, err
	}
	if err := <-done; err != nil {
		return nil, err
	}
	return map[string]any{"status": "pass", "session": req.Session, "request_id": req.ID, "pending": b.Pending()}, nil
}

func main() {
	mode := flag.String("mode", "runtime", "runtime|platform")
	output := flag.String("output", "", "report path")
	flag.Parse()
	var report map[string]any
	var err error
	switch *mode {
	case "runtime":
		report, err = runtimeStress()
	case "platform":
		report, err = platformMatrix()
	default:
		err = fmt.Errorf("unknown mode %q", *mode)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	report["schema_version"] = 1
	data, _ := json.MarshalIndent(report, "", "  ")
	data = append(data, '\n')
	if *output != "" {
		if err := os.MkdirAll(filepath.Dir(*output), 0o755); err != nil {
			panic(err)
		}
		if err := os.WriteFile(*output, data, 0o644); err != nil {
			panic(err)
		}
	}
	fmt.Print(string(data))
	_ = event.Durable
}
