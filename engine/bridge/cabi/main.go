package main

/*
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>

typedef struct {
    void* data;
    size_t len;
    uint64_t token;
} xdm_buffer_t;

enum {
    XDM_OK = 0,
    XDM_E_INVALID = 1,
    XDM_E_NOT_FOUND = 2,
    XDM_E_PROTOCOL = 3,
    XDM_E_TIMEOUT = 4,
    XDM_E_STOPPED = 5,
    XDM_E_INTERNAL = 255
};
*/
import "C"

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	v1 "github.com/subhra74/xdm/engine/api/v1"
	engineruntime "github.com/subhra74/xdm/engine/runtime"
)

var engineRegistry = struct {
	sync.Mutex
	next    uint64
	engines map[uint64]*engineruntime.Engine
}{next: 1, engines: make(map[uint64]*engineruntime.Engine)}

var allocationRegistry = struct {
	sync.Mutex
	allocations map[uint64]unsafe.Pointer
}{allocations: make(map[uint64]unsafe.Pointer)}

var nextAllocation uint64

func bytesFromC(ptr *C.uint8_t, n C.size_t) []byte {
	if ptr == nil || n == 0 {
		return nil
	}
	src := unsafe.Slice((*byte)(unsafe.Pointer(ptr)), int(n))
	return append([]byte(nil), src...)
}

func setBuffer(out *C.xdm_buffer_t, data []byte) C.int {
	if out == nil {
		return C.XDM_E_INVALID
	}
	out.data = nil
	out.len = 0
	out.token = 0
	if len(data) == 0 {
		return C.XDM_OK
	}
	ptr := C.malloc(C.size_t(len(data)))
	if ptr == nil {
		return C.XDM_E_INTERNAL
	}
	copy(unsafe.Slice((*byte)(ptr), len(data)), data)
	token := atomic.AddUint64(&nextAllocation, 1)
	allocationRegistry.Lock()
	allocationRegistry.allocations[token] = ptr
	allocationRegistry.Unlock()
	out.data = ptr
	out.len = C.size_t(len(data))
	out.token = C.uint64_t(token)
	return C.XDM_OK
}

func lookup(handle C.uint64_t) (*engineruntime.Engine, bool) {
	engineRegistry.Lock()
	defer engineRegistry.Unlock()
	e, ok := engineRegistry.engines[uint64(handle)]
	return e, ok
}

func status(err error) C.int {
	switch {
	case err == nil:
		return C.XDM_OK
	case errors.Is(err, v1.ErrProtocolMismatch):
		return C.XDM_E_PROTOCOL
	case errors.Is(err, context.DeadlineExceeded):
		return C.XDM_E_TIMEOUT
	case errors.Is(err, engineruntime.ErrStopped):
		return C.XDM_E_STOPPED
	default:
		return C.XDM_E_INVALID
	}
}

//export xdm_engine_create
func xdm_engine_create(config *C.uint8_t, configLen C.size_t, outHandle *C.uint64_t) C.int {
	if outHandle == nil {
		return C.XDM_E_INVALID
	}
	if _, err := v1.DecodeCreateConfig(bytesFromC(config, configLen)); err != nil {
		return status(err)
	}
	e := engineruntime.New(engineruntime.Config{})
	if err := e.Start(); err != nil {
		return status(err)
	}
	engineRegistry.Lock()
	h := engineRegistry.next
	engineRegistry.next++
	engineRegistry.engines[h] = e
	engineRegistry.Unlock()
	*outHandle = C.uint64_t(h)
	return C.XDM_OK
}

//export xdm_engine_command
func xdm_engine_command(handle C.uint64_t, data *C.uint8_t, dataLen C.size_t) C.int {
	e, ok := lookup(handle)
	if !ok {
		return C.XDM_E_NOT_FOUND
	}
	env, err := v1.DecodeCommand(bytesFromC(data, dataLen))
	if err != nil {
		return status(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return status(e.Submit(ctx, env))
}

//export xdm_engine_next_frame
func xdm_engine_next_frame(handle C.uint64_t, timeoutMS C.int, out *C.xdm_buffer_t) C.int {
	e, ok := lookup(handle)
	if !ok {
		return C.XDM_E_NOT_FOUND
	}
	ctx := context.Background()
	cancel := func() {}
	if timeoutMS >= 0 {
		ctx, cancel = context.WithTimeout(ctx, time.Duration(timeoutMS)*time.Millisecond)
	}
	defer cancel()
	frame, err := e.NextFrame(ctx)
	if err != nil {
		return status(err)
	}
	encoded, err := v1.EncodeFrame(frame)
	if err != nil {
		return C.XDM_E_INTERNAL
	}
	return setBuffer(out, encoded)
}

//export xdm_engine_platform_reply
func xdm_engine_platform_reply(handle C.uint64_t, data *C.uint8_t, dataLen C.size_t) C.int {
	e, ok := lookup(handle)
	if !ok {
		return C.XDM_E_NOT_FOUND
	}
	reply, err := v1.DecodePlatformReply(bytesFromC(data, dataLen))
	if err != nil {
		return status(err)
	}
	return status(e.PlatformReply(reply))
}

//export xdm_engine_metadata
func xdm_engine_metadata(handle C.uint64_t, out *C.xdm_buffer_t) C.int {
	if _, ok := lookup(handle); !ok {
		return C.XDM_E_NOT_FOUND
	}
	data, err := v1.EncodeMetadata()
	if err != nil {
		return C.XDM_E_INTERNAL
	}
	return setBuffer(out, data)
}

//export xdm_engine_shutdown
func xdm_engine_shutdown(handle C.uint64_t) C.int {
	e, ok := lookup(handle)
	if !ok {
		return C.XDM_E_NOT_FOUND
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := e.Shutdown(ctx); err != nil {
		return status(err)
	}
	engineRegistry.Lock()
	delete(engineRegistry.engines, uint64(handle))
	engineRegistry.Unlock()
	return C.XDM_OK
}

//export xdm_buffer_free
func xdm_buffer_free(buffer C.xdm_buffer_t) {
	token := uint64(buffer.token)
	if token == 0 {
		return
	}
	allocationRegistry.Lock()
	ptr, ok := allocationRegistry.allocations[token]
	if ok {
		delete(allocationRegistry.allocations, token)
	}
	allocationRegistry.Unlock()
	if ok && ptr != nil {
		C.free(ptr)
	}
}

func main() {}
