# XDM Go engine package rules

The Go engine is the portable XDM authority. These rules are intentionally
established before the first domain aggregates so later overlays cannot
smuggle frontend/platform ownership back into the engine.

## Dependency direction

- `foundation/*` contains platform-neutral primitives and MUST NOT import
  XDM domain/runtime/store/backend/media packages.
- `domain/*` may depend on `foundation/*`, but MUST NOT depend on runtime,
  persistence, backend implementations, Android, .NET, or UI code.
- `runtime/*` may orchestrate domain objects but platform operations cross
  the explicit platform protocol introduced later in the roadmap.
- `cmd/*` is composition/smoke tooling and may import public engine packages.
- Android/JVM/Room/Compose/Avalonia/.NET dependencies are forbidden below
  `engine/`.

## Identity and time rules

- Stable entity identifiers are typed values; unrelated identifiers are not
  represented by interchangeable strings.
- Attempt/artifact generations and mutable-row revisions are independent,
  positive monotonic counters.
- Wall time and monotonic elapsed time are obtained through an injected
  `clock.Source`; timeout/backoff code must not infer elapsed time from wall
  clock timestamps.
- Random identifier entropy is supplied through `idgen.Generator`; tests use
  deterministic generators instead of global randomness.
- Canonical resource identity is opaque and non-secret. Raw/sensitive values
  use explicit wrappers whose ordinary string/JSON representation is redacted.

## Toolchain

The minimum supported Go language/toolchain baseline for this workspace is
Go 1.23. Newer compatible Go releases are expected to work. Devtool's native
Go runner owns restore/build/test/vet execution for `xgo_foundation`.
