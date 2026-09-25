# XGO-41 implementation report — Generic aria2 JSON-RPC client

## Scope

XGO-41 introduces the canonical Go aria2 JSON-RPC transport under
`engine/backends/aria2`. It intentionally stops before daemon/process ownership,
durable GID binding, reconciliation, or backend migration; those remain XGO-42
and XGO-43.

## Implemented RPC surface

- `aria2.addUri` with multiple sources and typed textual options;
- `aria2.tellStatus`, `tellActive`, `tellWaiting`, `tellStopped`;
- pause/force-pause, unpause, remove/force-remove;
- task and global option changes plus global-option read;
- session save;
- graceful and forced shutdown;
- version and health probing.

Every request uses JSON-RPC 2.0 and a monotonic string ID. Every response must
carry JSON-RPC 2.0 and the same ID before its result/error is accepted. The client
uses a bounded per-call timeout, rejects RPC endpoint redirects, caps response
size, and returns typed errors for HTTP status, timeout, malformed response,
correlation mismatch, and remote RPC error. Secret token material is redacted
from remote messages and malformed/HTTP bodies are never echoed.

Aria2 option encoding is restricted to strings/string arrays. That matches aria2
wire semantics while preventing host-specific bool/number JSON encoding from
becoming canonical state.

## Validation evidence

`aria2_rpc_lab` covers:

1. authenticated mock JSON-RPC request/reply;
2. request-ID correlation rejection;
3. malformed response rejection;
4. bounded timeout;
5. unknown GID typed remote failure and token redaction;
6. option encoding;
7. status plus active/waiting/stopped lists;
8. controls and global options/session save;
9. health and forced shutdown;
10. a real `aria2c` smoke probe when the binary is installed.

The existing backend contract audit also requires the detailed XGO-41 fixture,
capability-ledger closure, and the eight-node `xgo_backends#validate` topology.
The full Wave-5 regression labs remain cumulative.
