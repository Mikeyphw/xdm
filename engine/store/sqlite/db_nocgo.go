//go:build !cgo

package sqlite

import "context"

const Available = false

type DB struct{}
type Tx struct{}
type Options struct{ BusyTimeoutMS int }

func DefaultOptions() Options                                       { return Options{BusyTimeoutMS: 5000} }
func LibraryVersion() string                                        { return "unavailable-without-cgo" }
func Open(string, Options) (*DB, error)                             { return nil, ErrUnavailable }
func (db *DB) Close() error                                         { return nil }
func (db *DB) ExecScript(context.Context, string) error             { return ErrUnavailable }
func (db *DB) Exec(context.Context, string, ...any) (int64, error)  { return 0, ErrUnavailable }
func (db *DB) Query(context.Context, string, ...any) ([]Row, error) { return nil, ErrUnavailable }
func (db *DB) BeginImmediate(context.Context) (*Tx, error)          { return nil, ErrUnavailable }
func (db *DB) IntegrityCheck(context.Context) error                 { return ErrUnavailable }
func (tx *Tx) Exec(context.Context, string, ...any) (int64, error)  { return 0, ErrUnavailable }
func (tx *Tx) ExecScript(context.Context, string) error             { return ErrUnavailable }
func (tx *Tx) Query(context.Context, string, ...any) ([]Row, error) { return nil, ErrUnavailable }
func (tx *Tx) Commit(context.Context) error                         { return ErrUnavailable }
func (tx *Tx) Rollback(context.Context) error                       { return ErrUnavailable }
