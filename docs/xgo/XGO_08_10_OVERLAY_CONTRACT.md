# XGO-08 + XGO-09 + XGO-10 overlay contract

## Covered roadmap items

- XGO-08 — command runtime, event stream and lifecycle
- XGO-09 — asynchronous platform request/reply broker
- XGO-10 — versioned wire schema and tiny C ABI
- GATE-01 — runtime foundation

These three adjacent items are intentionally combined. They form one runtime
boundary: Go receives versioned commands, performs platform-neutral execution,
requests nonportable work through the platform broker, emits ordered frames,
and exposes the same byte protocol through a tiny C ABI. Splitting them would
leave temporary interfaces that are immediately replaced by the next item.

## Devtool ownership

Artifact target: `xgo_gate_foundation`.

`xgo_gate_foundation#validate` composes `xgo_foundation#validate`, whose new
specialized nodes are:

```text
runtime_stress
  -> platform_broker_matrix
  -> abi_harness
```

The normal Go runner remains authoritative for build/test/vet. On the native-Termux
Android/arm64 validation target, `runtime_stress` runs repeated concurrency/lifecycle
tests plus the runtime audit; Go's race detector is not supported on Android/arm64 and
is therefore not a Gate-1 requirement on that execution environment. A supported Linux
host may invoke the same stress tool with `--race` during later cross-host qualification.
`abi_harness` builds a real `c-shared` library,
compiles a native C host, checks symbol inventory, and executes ABI lifecycle
contracts.

The artifact requires validation, uses `failure_action = pause`, and does not
override the workflow with `validation.tasks`.

## Runtime contract

1. `Engine` has explicit start, command submission, next-frame, platform-reply,
   and shutdown lifecycle.
2. Command IDs and operation IDs are distinct. A duplicate accepted CommandID
   is rejected within one engine lifetime.
3. Event sequence numbers are assigned at queue admission and are monotonic in
   delivery order.
4. Durable/operational events apply bounded backpressure; telemetry may
   coalesce by key and may drop when no queue slot exists.
5. Cancellation addresses an OperationID, is safe to repeat, propagates by
   context, and does not require frontend-owned cancellation state.
6. Shutdown cancels active operations and terminates platform requests without
   synchronous Go-to-host callbacks.

## Platform broker contract

1. Supported request kinds are typed: secret lookup, system proxy, network
   policy, runtime conditions, publication, and external media tool execution.
2. Every request receives a monotonically allocated request ID and host-session
   identity.
3. Deadline/cancellation removes request ownership; a subsequent reply is
   classified as late.
4. A successful first reply tombstones the request; a repeated reply is
   classified as duplicate.
5. Disconnect fails outstanding requests. Reconnect advances session identity;
   stale queued/replied requests cannot silently attach to the new host session.
6. Requests that affect durable operations can carry operation, attempt, and
   publication references.

## Wire/ABI contract

Initial wire protocol is JSON v1 with `{major:1, minor:0}`. The JSON envelope is
an implementation-stable byte protocol for the first ABI; domain packages do
not depend on JSON transport types.

Exported ABI surface:

```text
xdm_engine_create
xdm_engine_command
xdm_engine_next_frame
xdm_engine_platform_reply
xdm_engine_metadata
xdm_engine_shutdown
xdm_buffer_free
```

ABI rules:

- engine references are opaque numeric handles, never Go pointers;
- caller input bytes are copied before Go retains them;
- output buffers are allocated in C memory;
- each output allocation has an opaque token and `xdm_buffer_free` is
  idempotent for that token;
- incompatible protocol major versions are rejected before command execution;
- no normal Go-to-managed callback API exists; hosts poll/await frames;
- malformed/unknown wire fields fail closed.

## New capability fixtures

- `XGO-CAP-RUNTIME-001..003`
- `XGO-CAP-PLATFORM-001..002`
- `XGO-CAP-ABI-001`

All are marked IMPLEMENTED and have one-to-one language-neutral fixtures.

## Intentional deferrals

- Engine commands currently include only foundation probes (`runtime.ping`,
  `runtime.platform_probe`, cancellation). Domain download commands arrive when
  persistence/execution ownership exists.
- Wire v1 uses strict JSON to freeze semantics without introducing a third-party
  serialization dependency. A later protocol revision may add protobuf only via
  explicit versioned compatibility work; the C ABI remains byte-oriented.
- Durable event persistence across process death is not claimed here; "durable"
  in XGO-08 means non-droppable within the current process. Database-backed
  durable state begins in Wave 2.
- ABI library packaging for Android/Desktop remains XGO-66/XGO-76.

## Exit criteria

- all native Go tests and vet pass;
- repeated runtime/API concurrency stress passes on native Termux;
- when executed on a supported Linux host with `--race`, the same runtime/API set passes the race detector;
- 32-command concurrent runtime audit observes monotonic event sequencing and
  all command completions;
- platform request/reply matrix has zero leaked pending requests;
- C shared library and generated header build;
- native C harness passes malformed/version/lifecycle/double-free/outstanding
  platform-request scenarios;
- all seven required symbols are exported;
- capability/fixture/secret/topology audits remain green;
- `xgo_gate_foundation` passes as the artifact validation target.
