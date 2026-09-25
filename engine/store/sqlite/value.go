package sqlite

import "fmt"

type Kind uint8

const (
	Null Kind = iota
	Integer
	Float
	Text
	Blob
)

type Value struct {
	Kind Kind
	I64  int64
	F64  float64
	Text string
	Blob []byte
}

func (v Value) StringValue() (string, error) {
	if v.Kind != Text {
		return "", fmt.Errorf("value kind %d is not text", v.Kind)
	}
	return v.Text, nil
}
func (v Value) Int64Value() (int64, error) {
	if v.Kind != Integer {
		return 0, fmt.Errorf("value kind %d is not integer", v.Kind)
	}
	return v.I64, nil
}

type Row []Value
