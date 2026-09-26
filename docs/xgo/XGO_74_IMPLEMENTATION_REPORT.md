# XGO-74 implementation report

## Merge decision

XGO-74 is intentionally standalone. XGO-75 is the dependent legacy-authority deletion/seal after the persistence cutover, while GATE-10 is a separate authoritative-Go qualification gate. Combining them would hide whether failures come from import/cutover, deletion, or final qualification.

## Delivered

Android now constructs one stable Room schema-25 import package using `AndroidLegacyRoomImporter`. Downloads, attempt summaries, terminal history, queues, schedules, recovery records, captures, and variants are mapped where supportable; the existing XGO-65 `AndroidRoomImportAdapter` validates and stages the package into canonical imported objects.

The JNI create configuration now carries an app-private `state_path`. The Go runtime opens `android-state-v1.json`, commits the first accepted legacy package atomically with mode 0600, and restores the committed download/media state on process restart. Import hashing is based on canonical JSON content rather than raw key order/whitespace, so identical retries are idempotent. Once committed, a different Room package is fenced as `room_changed_after_cutover` rather than silently replacing Go authority.

`XdmApplication` no longer starts download or media Room projection collectors. The old `mirrorIntoGo`, `syncLegacyDownloadProjection`, and `syncLegacyMediaProjection` entry points are removed. Go UI/media mutations persist their updated projection/selection into the Go authority snapshot. `MainViewModel` no longer dual-writes Go media selections into Room or `MediaResolverSelectionStore`.

## Deliberately retained for XGO-75

The Android compatibility execution broker and legacy transfer/media implementations still perform platform side effects requested by Go. They are no longer the source of the UI/media projection or durable selection authority, but deleting/reducing those implementation paths is XGO-75's explicit responsibility.

## Validation evidence

`xgo_android` now contains 11 validation nodes. The new `android_import_matrix` performs a live import into a temporary durable Go authority file, restarts the engine, verifies the Go projection restores without Room, and audits the Android importer, app startup, process authority, C ABI, state store, and no-live-mirror/no-selection-dual-write rules. The existing Android UI/media audits were rebased to require the one-shot importer rather than the removed mirror.
