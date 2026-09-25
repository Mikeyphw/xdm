//go:build cgo

package sqlite

/*
#cgo linux LDFLAGS: -lsqlite3
#cgo android LDFLAGS: -lsqlite3
#cgo darwin LDFLAGS: -lsqlite3
#cgo windows LDFLAGS: -lsqlite3
#include <stdlib.h>
#include <stdint.h>

typedef struct sqlite3 sqlite3;
typedef struct sqlite3_stmt sqlite3_stmt;

extern int sqlite3_open_v2(const char*, sqlite3**, int, const char*);
extern int sqlite3_close_v2(sqlite3*);
extern const char *sqlite3_errmsg(sqlite3*);
extern int sqlite3_extended_errcode(sqlite3*);
extern int sqlite3_extended_result_codes(sqlite3*, int);
extern int sqlite3_busy_timeout(sqlite3*, int);
extern int sqlite3_prepare_v2(sqlite3*, const char*, int, sqlite3_stmt**, const char**);
extern int sqlite3_finalize(sqlite3_stmt*);
extern int sqlite3_step(sqlite3_stmt*);
extern int sqlite3_reset(sqlite3_stmt*);
extern int sqlite3_clear_bindings(sqlite3_stmt*);
extern int sqlite3_bind_null(sqlite3_stmt*, int);
extern int sqlite3_bind_int64(sqlite3_stmt*, int, int64_t);
extern int sqlite3_bind_double(sqlite3_stmt*, int, double);
extern int sqlite3_bind_text(sqlite3_stmt*, int, const char*, int, void(*)(void*));
extern int sqlite3_bind_blob(sqlite3_stmt*, int, const void*, int, void(*)(void*));
extern int sqlite3_column_count(sqlite3_stmt*);
extern int sqlite3_column_type(sqlite3_stmt*, int);
extern int64_t sqlite3_column_int64(sqlite3_stmt*, int);
extern double sqlite3_column_double(sqlite3_stmt*, int);
extern const unsigned char *sqlite3_column_text(sqlite3_stmt*, int);
extern const void *sqlite3_column_blob(sqlite3_stmt*, int);
extern int sqlite3_column_bytes(sqlite3_stmt*, int);
extern int sqlite3_changes(sqlite3*);
extern int sqlite3_get_autocommit(sqlite3*);
extern const char *sqlite3_libversion(void);

#define XGO_SQLITE_TRANSIENT ((void(*)(void*))-1)
static int xgo_bind_text(sqlite3_stmt *s, int i, const char *p, int n) {
    return sqlite3_bind_text(s, i, p, n, XGO_SQLITE_TRANSIENT);
}
static int xgo_bind_blob(sqlite3_stmt *s, int i, const void *p, int n) {
    return sqlite3_bind_blob(s, i, p, n, XGO_SQLITE_TRANSIENT);
}

#define XGO_SQLITE_OK 0
#define XGO_SQLITE_ROW 100
#define XGO_SQLITE_DONE 101
#define XGO_SQLITE_INTEGER 1
#define XGO_SQLITE_FLOAT 2
#define XGO_SQLITE_TEXT 3
#define XGO_SQLITE_BLOB 4
#define XGO_SQLITE_NULL 5
#define XGO_SQLITE_OPEN_READWRITE 0x00000002
#define XGO_SQLITE_OPEN_CREATE 0x00000004
#define XGO_SQLITE_OPEN_URI 0x00000040
#define XGO_SQLITE_OPEN_FULLMUTEX 0x00010000
*/
import "C"

import (
	"context"
	"fmt"
	"sync"
	"unsafe"
)

const Available = true

type DB struct {
	mu     sync.Mutex
	ptr    *C.sqlite3
	path   string
	closed bool
}

type Tx struct {
	db   *DB
	done bool
}

type Options struct {
	BusyTimeoutMS int
}

func DefaultOptions() Options { return Options{BusyTimeoutMS: 5000} }

func LibraryVersion() string { return C.GoString(C.sqlite3_libversion()) }

func Open(path string, options Options) (*DB, error) {
	if path == "" {
		return nil, fmt.Errorf("open sqlite: empty path")
	}
	if options.BusyTimeoutMS <= 0 {
		options.BusyTimeoutMS = DefaultOptions().BusyTimeoutMS
	}
	cpath := C.CString(path)
	defer C.free(unsafe.Pointer(cpath))
	var ptr *C.sqlite3
	flags := C.int(C.XGO_SQLITE_OPEN_READWRITE | C.XGO_SQLITE_OPEN_CREATE | C.XGO_SQLITE_OPEN_URI | C.XGO_SQLITE_OPEN_FULLMUTEX)
	rc := C.sqlite3_open_v2(cpath, &ptr, flags, nil)
	if rc != C.XGO_SQLITE_OK {
		err := sqliteError(ptr, "open", rc)
		if ptr != nil {
			C.sqlite3_close_v2(ptr)
		}
		return nil, err
	}
	C.sqlite3_extended_result_codes(ptr, 1)
	if rc = C.sqlite3_busy_timeout(ptr, C.int(options.BusyTimeoutMS)); rc != C.XGO_SQLITE_OK {
		err := sqliteError(ptr, "busy-timeout", rc)
		C.sqlite3_close_v2(ptr)
		return nil, err
	}
	db := &DB{ptr: ptr, path: path}
	if err := db.ExecScript(context.Background(), "PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;"); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

func sqliteError(ptr *C.sqlite3, op string, rc C.int) error {
	ext := int(rc)
	msg := ""
	if ptr != nil {
		ext = int(C.sqlite3_extended_errcode(ptr))
		msg = C.GoString(C.sqlite3_errmsg(ptr))
	}
	code := int(rc) & 0xff
	return &Error{Op: op, Code: code, Extended: ext, Message: msg}
}

func (db *DB) checkLocked() error {
	if db == nil || db.closed || db.ptr == nil {
		return ErrClosed
	}
	return nil
}

func (db *DB) Close() error {
	if db == nil {
		return nil
	}
	db.mu.Lock()
	defer db.mu.Unlock()
	if db.closed || db.ptr == nil {
		db.closed = true
		return nil
	}
	rc := C.sqlite3_close_v2(db.ptr)
	if rc != C.XGO_SQLITE_OK {
		return sqliteError(db.ptr, "close", rc)
	}
	db.ptr = nil
	db.closed = true
	return nil
}

func (db *DB) ExecScript(ctx context.Context, script string) error {
	db.mu.Lock()
	defer db.mu.Unlock()
	if err := db.checkLocked(); err != nil {
		return err
	}
	return execScriptLocked(ctx, db.ptr, script)
}

func execScriptLocked(ctx context.Context, ptr *C.sqlite3, script string) error {
	remaining := script
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		csql := C.CString(remaining)
		var stmt *C.sqlite3_stmt
		var tail *C.char
		rc := C.sqlite3_prepare_v2(ptr, csql, C.int(len(remaining)), &stmt, &tail)
		consumed := len(remaining)
		if tail != nil {
			consumed = int(uintptr(unsafe.Pointer(tail)) - uintptr(unsafe.Pointer(csql)))
		}
		C.free(unsafe.Pointer(csql))
		if rc != C.XGO_SQLITE_OK {
			return sqliteError(ptr, "prepare-script", rc)
		}
		if stmt != nil {
			rc = C.sqlite3_step(stmt)
			C.sqlite3_finalize(stmt)
			if rc != C.XGO_SQLITE_DONE && rc != C.XGO_SQLITE_ROW {
				return sqliteError(ptr, "exec-script", rc)
			}
		}
		if consumed <= 0 || consumed >= len(remaining) {
			return nil
		}
		remaining = remaining[consumed:]
		if len(remaining) == 0 {
			return nil
		}
	}
}

func (db *DB) Exec(ctx context.Context, query string, args ...any) (int64, error) {
	db.mu.Lock()
	defer db.mu.Unlock()
	if err := db.checkLocked(); err != nil {
		return 0, err
	}
	return execLocked(ctx, db.ptr, query, args...)
}

func execLocked(ctx context.Context, ptr *C.sqlite3, query string, args ...any) (int64, error) {
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	stmt, err := prepare(ptr, query, args...)
	if err != nil {
		return 0, err
	}
	defer C.sqlite3_finalize(stmt)
	rc := C.sqlite3_step(stmt)
	if rc != C.XGO_SQLITE_DONE && rc != C.XGO_SQLITE_ROW {
		return 0, sqliteError(ptr, "step", rc)
	}
	return int64(C.sqlite3_changes(ptr)), nil
}

func (db *DB) Query(ctx context.Context, query string, args ...any) ([]Row, error) {
	db.mu.Lock()
	defer db.mu.Unlock()
	if err := db.checkLocked(); err != nil {
		return nil, err
	}
	return queryLocked(ctx, db.ptr, query, args...)
}

func queryLocked(ctx context.Context, ptr *C.sqlite3, query string, args ...any) ([]Row, error) {
	stmt, err := prepare(ptr, query, args...)
	if err != nil {
		return nil, err
	}
	defer C.sqlite3_finalize(stmt)
	cols := int(C.sqlite3_column_count(stmt))
	rows := make([]Row, 0)
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		rc := C.sqlite3_step(stmt)
		if rc == C.XGO_SQLITE_DONE {
			return rows, nil
		}
		if rc != C.XGO_SQLITE_ROW {
			return nil, sqliteError(ptr, "query-step", rc)
		}
		row := make(Row, cols)
		for i := 0; i < cols; i++ {
			typ := C.sqlite3_column_type(stmt, C.int(i))
			switch typ {
			case C.XGO_SQLITE_INTEGER:
				row[i] = Value{Kind: Integer, I64: int64(C.sqlite3_column_int64(stmt, C.int(i)))}
			case C.XGO_SQLITE_FLOAT:
				row[i] = Value{Kind: Float, F64: float64(C.sqlite3_column_double(stmt, C.int(i)))}
			case C.XGO_SQLITE_TEXT:
				p := C.sqlite3_column_text(stmt, C.int(i))
				n := int(C.sqlite3_column_bytes(stmt, C.int(i)))
				row[i] = Value{Kind: Text, Text: C.GoStringN((*C.char)(unsafe.Pointer(p)), C.int(n))}
			case C.XGO_SQLITE_BLOB:
				p := C.sqlite3_column_blob(stmt, C.int(i))
				n := int(C.sqlite3_column_bytes(stmt, C.int(i)))
				var b []byte
				if n > 0 {
					b = C.GoBytes(p, C.int(n))
				}
				row[i] = Value{Kind: Blob, Blob: b}
			default:
				row[i] = Value{Kind: Null}
			}
		}
		rows = append(rows, row)
	}
}

func prepare(ptr *C.sqlite3, query string, args ...any) (*C.sqlite3_stmt, error) {
	csql := C.CString(query)
	defer C.free(unsafe.Pointer(csql))
	var stmt *C.sqlite3_stmt
	rc := C.sqlite3_prepare_v2(ptr, csql, C.int(len(query)), &stmt, nil)
	if rc != C.XGO_SQLITE_OK {
		return nil, sqliteError(ptr, "prepare", rc)
	}
	for i, arg := range args {
		if rc = bind(stmt, i+1, arg); rc != C.XGO_SQLITE_OK {
			C.sqlite3_finalize(stmt)
			return nil, sqliteError(ptr, "bind", rc)
		}
	}
	return stmt, nil
}

func bind(stmt *C.sqlite3_stmt, index int, arg any) C.int {
	i := C.int(index)
	switch v := arg.(type) {
	case nil:
		return C.sqlite3_bind_null(stmt, i)
	case int:
		return C.sqlite3_bind_int64(stmt, i, C.int64_t(v))
	case int64:
		return C.sqlite3_bind_int64(stmt, i, C.int64_t(v))
	case bool:
		if v {
			return C.sqlite3_bind_int64(stmt, i, 1)
		}
		return C.sqlite3_bind_int64(stmt, i, 0)
	case float64:
		return C.sqlite3_bind_double(stmt, i, C.double(v))
	case string:
		p := C.CString(v)
		defer C.free(unsafe.Pointer(p))
		return C.xgo_bind_text(stmt, i, p, C.int(len(v)))
	case []byte:
		if len(v) == 0 {
			return C.xgo_bind_blob(stmt, i, nil, 0)
		}
		return C.xgo_bind_blob(stmt, i, unsafe.Pointer(&v[0]), C.int(len(v)))
	default:
		return C.int(21) // SQLITE_MISUSE
	}
}

func (db *DB) BeginImmediate(ctx context.Context) (*Tx, error) {
	db.mu.Lock()
	if err := db.checkLocked(); err != nil {
		db.mu.Unlock()
		return nil, err
	}
	if _, err := execLocked(ctx, db.ptr, "BEGIN IMMEDIATE"); err != nil {
		db.mu.Unlock()
		return nil, err
	}
	return &Tx{db: db}, nil
}

func (tx *Tx) Exec(ctx context.Context, query string, args ...any) (int64, error) {
	if tx == nil || tx.done {
		return 0, ErrClosed
	}
	return execLocked(ctx, tx.db.ptr, query, args...)
}
func (tx *Tx) ExecScript(ctx context.Context, script string) error {
	if tx == nil || tx.done {
		return ErrClosed
	}
	return execScriptLocked(ctx, tx.db.ptr, script)
}
func (tx *Tx) Query(ctx context.Context, query string, args ...any) ([]Row, error) {
	if tx == nil || tx.done {
		return nil, ErrClosed
	}
	return queryLocked(ctx, tx.db.ptr, query, args...)
}
func (tx *Tx) Commit(ctx context.Context) error   { return tx.finish(ctx, "COMMIT") }
func (tx *Tx) Rollback(ctx context.Context) error { return tx.finish(ctx, "ROLLBACK") }
func (tx *Tx) finish(ctx context.Context, statement string) error {
	if tx == nil || tx.done {
		return ErrClosed
	}
	_, err := execLocked(ctx, tx.db.ptr, statement)
	tx.done = true
	tx.db.mu.Unlock()
	return err
}

func (db *DB) IntegrityCheck(ctx context.Context) error {
	rows, err := db.Query(ctx, "PRAGMA integrity_check")
	if err != nil {
		return err
	}
	if len(rows) != 1 || len(rows[0]) != 1 || rows[0][0].Kind != Text || rows[0][0].Text != "ok" {
		return fmt.Errorf("sqlite integrity check failed: %v", rows)
	}
	return nil
}
