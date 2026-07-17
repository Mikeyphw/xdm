# Phase 6B — Embedded aria2 runtime foundation

Phase 6B establishes the Android-safe process, RPC, session, and diagnostics boundary for the optional embedded aria2 backend. It intentionally does not enable production task creation yet; durable GID mapping, polling, and crash reconciliation remain the next slice.

## Executable packaging

Android must install executable code from the APK's native-library area. XDM therefore looks only for an ARM64 PIE executable named `libaria2c.so` under `ApplicationInfo.nativeLibraryDir`.

XDM never copies aria2 into `filesDir`, `cacheDir`, or another writable application directory. Writable app-private storage contains only configuration, session data, task mappings, partial data, and sanitized logs.

The app and library use legacy JNI extraction so a packaged runtime is installed as a real read-only executable file. Builds without the binary remain valid; capability probing reports the backend as unavailable and native downloads continue to work.

## Managed process contract

`Aria2ProcessManager` owns exactly one local process. It:

1. validates device ABI, ELF architecture, executable permission, and private runtime storage;
2. allocates an ephemeral loopback port;
3. obtains a random per-installation RPC secret from app-private preferences;
4. writes a short-lived owner-only launch configuration;
5. starts aria2 without a shell;
6. authenticates `aria2.getVersion` over `127.0.0.1`;
7. deletes the launch configuration after readiness is established;
8. saves the session and requests graceful RPC shutdown;
9. force-stops only after a bounded shutdown timeout; and
10. observes unexpected process exits.

RPC is always configured with `rpc-listen-all=false`. Every JSON-RPC call places `token:<secret>` first in the parameter array. Secrets are redacted from object rendering and failure messages.

## Session and artifact layout

Writable state lives under:

```text
files/aria2/
├── xdm.session
├── staging/
├── tasks/<download-id>/
│   ├── <name>.xdm.aria2.part
│   ├── <name>.xdm.aria2.part.aria2
│   └── ownership.json
└── logs/aria2-runtime.log
```

The executable is not stored in this tree.

aria2 partials use a distinct `.xdm.aria2.part` identity so they can never be mistaken for native `.xdm.part` files. The backend exposes physical artifact identities to the Phase 6A ownership coordinator.

## Product integration

The existing eight-route topography is unchanged. Diagnostics displays the real runtime state and offers a bounded smoke probe that starts aria2, authenticates RPC, saves the session, and stops the process. No phase or placeholder language is shown in product UI.

## Deliberate gate

`EmbeddedAria2Backend.add()` remains disabled. Enabling download creation requires all of the following in Phase 6C:

- persistent Room-to-GID mapping;
- add/pause/resume/cancel/remove RPC operations;
- active/waiting/stopped polling;
- process-death reconciliation;
- source, destination, size, and ownership-generation validation; and
- tests proving native and aria2 cannot concurrently own or write one target.
