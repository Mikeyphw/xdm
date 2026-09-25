package sqlite

import (
	"errors"
	"fmt"
)

var (
	ErrUnavailable  = errors.New("sqlite unavailable")
	ErrClosed       = errors.New("sqlite database is closed")
	ErrStaleWrite   = errors.New("stale revision write")
	ErrNotFound     = errors.New("record not found")
	ErrSchemaTooNew = errors.New("database schema is newer than this engine")
)

// Error preserves stable SQLite result codes without exposing SQL text or bound values.
type Error struct {
	Op       string
	Code     int
	Extended int
	Message  string
}

func (e *Error) Error() string {
	if e == nil {
		return "<nil>"
	}
	if e.Message == "" {
		return fmt.Sprintf("sqlite %s failed (code=%d extended=%d)", e.Op, e.Code, e.Extended)
	}
	return fmt.Sprintf("sqlite %s failed (code=%d extended=%d): %s", e.Op, e.Code, e.Extended, e.Message)
}

func (e *Error) Busy() bool       { return e != nil && e.Code == 5 }
func (e *Error) Locked() bool     { return e != nil && e.Code == 6 }
func (e *Error) Constraint() bool { return e != nil && e.Code == 19 }
func (e *Error) Corrupt() bool    { return e != nil && (e.Code == 11 || e.Code == 26) }
