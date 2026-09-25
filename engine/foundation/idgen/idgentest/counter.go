package idgentest

import (
	"errors"
	"fmt"
	"math"
	"sync"
)

var ErrExhausted = errors.New("deterministic identifier generator exhausted")

// Counter emits deterministic 128-bit-looking hexadecimal tokens. The high
// 64 bits remain zero; the low 64 bits monotonically increase.
type Counter struct {
	mu   sync.Mutex
	next uint64
}

func NewCounter(start uint64) *Counter {
	return &Counter{next: start}
}

func (c *Counter) Token() (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	value := c.next
	if value == math.MaxUint64 {
		return "", ErrExhausted
	}
	c.next++
	return fmt.Sprintf("%032x", value), nil
}
