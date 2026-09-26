# XGO-74 Android legacy Room import and authoritative-state cutover contract

XGO-74 moves Android durable presentation/selection authority from the legacy Room mirror into Go without absorbing XGO-75's broader Kotlin execution cleanup or GATE-10.

## Authority boundary

- Android samples legacy Room once per process into a versioned package for the XGO-65 import framework.
- The import package represents downloads and supportable attempts, history, queues, schedules, recovery, captures, and variants. Sensitive runtime request material remains Android-local.
- Go canonicalizes the package, records a content-idempotent import key, and atomically commits an app-private authority snapshot outside Room.
- Repeating identical import content is a successful duplicate. A different Room package may not overwrite already committed Go authority.
- A restarted Go engine restores download/media projections from its own snapshot before any legacy import confirmation arrives.
- Continuous Room-to-Go download/media mirrors are forbidden after this overlay.
- Go media selections are persisted only in the Go authority snapshot; ViewModel selection commands do not write Room or the old resolver-selection preference store.

## Temporary transition surfaces

The narrow Android download execution broker and other legacy runtime side effects remain until XGO-75 because Android still has Kotlin transfer/media implementations that Go invokes through typed platform requests. Those surfaces are compatibility hosts, not the projection/persistence authority. XGO-75 removes/reduces the superseded Kotlin transfer/media/scheduler/persistence paths and runs the Android legacy-authority seal.

## Validation

`xgo_android#validate` adds `android_import_matrix` between `android_media_e2e` and `android_host_runtime_tests`. It must exercise a real import, durable restart restore, content-idempotency/changed-source fencing, and inspect the production Android/C-ABI wiring. A detached in-memory importer test is insufficient.
