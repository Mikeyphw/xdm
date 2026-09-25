package identity

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"strconv"
)

var (
	ErrInvalidCounter  = errors.New("generation/revision must be a positive integer")
	ErrCounterOverflow = errors.New("generation/revision overflow")
)

type AttemptGeneration int64
type ArtifactGeneration int64
type Revision int64

func newCounter(value int64) (int64, error) {
	if value < 1 {
		return 0, ErrInvalidCounter
	}
	return value, nil
}

func nextCounter(value int64) (int64, error) {
	if value < 1 {
		return 0, ErrInvalidCounter
	}
	if value == math.MaxInt64 {
		return 0, ErrCounterOverflow
	}
	return value + 1, nil
}

func parseCounter(text string) (int64, error) {
	value, err := strconv.ParseInt(text, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("%w: %v", ErrInvalidCounter, err)
	}
	return newCounter(value)
}

func marshalCounter(value int64) ([]byte, error) {
	if _, err := newCounter(value); err != nil {
		return nil, err
	}
	return json.Marshal(value)
}

func unmarshalCounter(data []byte) (int64, error) {
	var value int64
	if err := json.Unmarshal(data, &value); err != nil {
		return 0, err
	}
	return newCounter(value)
}

func NewAttemptGeneration(value int64) (AttemptGeneration, error) {
	v, err := newCounter(value)
	return AttemptGeneration(v), err
}
func ParseAttemptGeneration(text string) (AttemptGeneration, error) {
	v, err := parseCounter(text)
	return AttemptGeneration(v), err
}
func (v AttemptGeneration) Valid() bool    { return v >= 1 }
func (v AttemptGeneration) String() string { return strconv.FormatInt(int64(v), 10) }
func (v AttemptGeneration) Int64() int64   { return int64(v) }
func (v AttemptGeneration) Next() (AttemptGeneration, error) {
	n, err := nextCounter(int64(v))
	return AttemptGeneration(n), err
}
func (v AttemptGeneration) MarshalJSON() ([]byte, error) { return marshalCounter(int64(v)) }
func (v *AttemptGeneration) UnmarshalJSON(data []byte) error {
	n, err := unmarshalCounter(data)
	if err != nil {
		return err
	}
	*v = AttemptGeneration(n)
	return nil
}

func NewArtifactGeneration(value int64) (ArtifactGeneration, error) {
	v, err := newCounter(value)
	return ArtifactGeneration(v), err
}
func ParseArtifactGeneration(text string) (ArtifactGeneration, error) {
	v, err := parseCounter(text)
	return ArtifactGeneration(v), err
}
func (v ArtifactGeneration) Valid() bool    { return v >= 1 }
func (v ArtifactGeneration) String() string { return strconv.FormatInt(int64(v), 10) }
func (v ArtifactGeneration) Int64() int64   { return int64(v) }
func (v ArtifactGeneration) Next() (ArtifactGeneration, error) {
	n, err := nextCounter(int64(v))
	return ArtifactGeneration(n), err
}
func (v ArtifactGeneration) MarshalJSON() ([]byte, error) { return marshalCounter(int64(v)) }
func (v *ArtifactGeneration) UnmarshalJSON(data []byte) error {
	n, err := unmarshalCounter(data)
	if err != nil {
		return err
	}
	*v = ArtifactGeneration(n)
	return nil
}

func NewRevision(value int64) (Revision, error) { v, err := newCounter(value); return Revision(v), err }
func ParseRevision(text string) (Revision, error) {
	v, err := parseCounter(text)
	return Revision(v), err
}
func (v Revision) Valid() bool                  { return v >= 1 }
func (v Revision) String() string               { return strconv.FormatInt(int64(v), 10) }
func (v Revision) Int64() int64                 { return int64(v) }
func (v Revision) Next() (Revision, error)      { n, err := nextCounter(int64(v)); return Revision(n), err }
func (v Revision) MarshalJSON() ([]byte, error) { return marshalCounter(int64(v)) }
func (v *Revision) UnmarshalJSON(data []byte) error {
	n, err := unmarshalCounter(data)
	if err != nil {
		return err
	}
	*v = Revision(n)
	return nil
}
