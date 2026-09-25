package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"sync/atomic"
	"time"

	"github.com/subhra74/xdm/engine/domain/identity"
	store "github.com/subhra74/xdm/engine/store/sqlite"
)

type report struct {
	SchemaVersion int            `json:"schema_version"`
	Mode          string         `json:"mode"`
	Status        string         `json:"status"`
	Details       map[string]any `json:"details"`
}

func main() {
	mode := flag.String("mode", "platform", "platform|schema|cas")
	output := flag.String("output", "", "optional JSON report path")
	iterations := flag.Int("iterations", 64, "CAS stress rounds")
	flag.Parse()

	var details map[string]any
	var err error
	switch *mode {
	case "platform":
		details, err = auditPlatform()
	case "schema":
		details, err = auditSchema()
	case "cas":
		details, err = auditCAS(*iterations)
	default:
		err = fmt.Errorf("unknown mode %q", *mode)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	r := report{SchemaVersion: 1, Mode: *mode, Status: "pass", Details: details}
	data, _ := json.MarshalIndent(r, "", "  ")
	data = append(data, '\n')
	if *output != "" {
		if err := os.MkdirAll(filepath.Dir(*output), 0o755); err != nil {
			panic(err)
		}
		if err := os.WriteFile(*output, data, 0o644); err != nil {
			panic(err)
		}
	}
	os.Stdout.Write(data)
}

func tempDB() (*store.DB, string, func(), error) {
	dir, err := os.MkdirTemp("", "xgo-store-audit-")
	if err != nil {
		return nil, "", nil, err
	}
	path := filepath.Join(dir, "engine.sqlite")
	db, err := store.Open(path, store.DefaultOptions())
	if err != nil {
		os.RemoveAll(dir)
		return nil, "", nil, err
	}
	cleanup := func() { _ = db.Close(); _ = os.RemoveAll(dir) }
	return db, path, cleanup, nil
}

func auditPlatform() (map[string]any, error) {
	if !store.Available {
		return nil, store.ErrUnavailable
	}
	db, path, cleanup, err := tempDB()
	if err != nil {
		return nil, err
	}
	defer cleanup()
	ctx := context.Background()
	if err := store.Migrate(ctx, db, time.Now().UnixMilli()); err != nil {
		return nil, err
	}
	if err := db.IntegrityCheck(ctx); err != nil {
		return nil, err
	}
	pragmas := map[string]any{}
	for _, q := range []string{"PRAGMA journal_mode", "PRAGMA foreign_keys", "PRAGMA busy_timeout", "PRAGMA synchronous"} {
		rows, err := db.Query(ctx, q)
		if err != nil {
			return nil, err
		}
		if len(rows) != 1 || len(rows[0]) != 1 {
			return nil, fmt.Errorf("unexpected %s result", q)
		}
		v := rows[0][0]
		if v.Kind == store.Text {
			pragmas[q] = v.Text
		} else {
			pragmas[q] = v.I64
		}
	}
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	return map[string]any{"goos": runtime.GOOS, "goarch": runtime.GOARCH, "sqlite_version": store.LibraryVersion(), "database_bytes": info.Size(), "pragmas": pragmas}, nil
}

func auditSchema() (map[string]any, error) {
	db, _, cleanup, err := tempDB()
	if err != nil {
		return nil, err
	}
	defer cleanup()
	ctx := context.Background()
	if err := store.Migrate(ctx, db, 1234); err != nil {
		return nil, err
	}
	rows, err := db.Query(ctx, `SELECT name,type FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%' ORDER BY name`)
	if err != nil {
		return nil, err
	}
	tables := 0
	indexes := 0
	for _, r := range rows {
		if r[1].Text == "table" {
			tables++
		} else if r[1].Text == "index" {
			indexes++
		}
	}
	return map[string]any{"schema_version": store.CurrentSchemaVersion, "schema_checksum": store.SchemaChecksum(), "tables": tables, "indexes": indexes, "objects": len(rows)}, nil
}

func auditCAS(rounds int) (map[string]any, error) {
	if rounds < 1 {
		return nil, fmt.Errorf("iterations must be >= 1")
	}
	ctx := context.Background()
	totalWins := int64(0)
	totalStale := int64(0)
	for round := 0; round < rounds; round++ {
		db, path, cleanup, err := tempDB()
		if err != nil {
			return nil, err
		}
		if err := store.Migrate(ctx, db, 1); err != nil {
			cleanup()
			return nil, err
		}
		repo, _ := store.NewRepository(db)
		req, _ := identity.ParseRequestID("req_00000000000000000000000000000000")
		dl, _ := identity.ParseDownloadID("dl_00000000000000000000000000000000")
		rev, _ := identity.NewRevision(1)
		if err := repo.CreateRequest(ctx, store.RequestRecord{ID: req, Revision: rev, ResourceIdentity: "audit", Method: "GET", SafeSpecJSON: `{"audit":true}`, CreatedAtUnixMS: 1}); err != nil {
			cleanup()
			return nil, err
		}
		if err := repo.CreateDownload(ctx, store.DownloadRecord{ID: dl, RequestID: req, CurrentRequestRevision: rev, State: "open", Revision: rev, CreatedAtUnixMS: 1, UpdatedAtUnixMS: 1}); err != nil {
			cleanup()
			return nil, err
		}
		const writers = 12
		var wins atomic.Int64
		var stale atomic.Int64
		var unexpected atomic.Int64
		var wg sync.WaitGroup
		for i := 0; i < writers; i++ {
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				conn, e := store.Open(path, store.DefaultOptions())
				if e != nil {
					unexpected.Add(1)
					return
				}
				defer conn.Close()
				r, _ := store.NewRepository(conn)
				_, e = r.UpdateDownload(ctx, dl, rev, store.DownloadPatch{State: "winner", UpdatedAtUnixMS: int64(i + 2)})
				if e == nil {
					wins.Add(1)
				} else if errors.Is(e, store.ErrStaleWrite) {
					stale.Add(1)
				} else {
					unexpected.Add(1)
				}
			}(i)
		}
		wg.Wait()
		cleanup()
		if wins.Load() != 1 || stale.Load() != writers-1 || unexpected.Load() != 0 {
			return nil, fmt.Errorf("round %d: wins=%d stale=%d unexpected=%d", round, wins.Load(), stale.Load(), unexpected.Load())
		}
		totalWins += wins.Load()
		totalStale += stale.Load()
	}
	return map[string]any{"rounds": rounds, "writers_per_round": 12, "winning_writes": totalWins, "stale_writes": totalStale}, nil
}
