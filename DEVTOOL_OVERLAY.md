# XDM Android master remediation Overlay 04+05 v2: request security + queue/runtime ownership

Target: `xdm_android`.

This corrected intermediate schema-v2 devtool artifact supersedes v1 and is built on the successfully applied Overlay 02+03 state / commit `9d1608ec` and combines the next two dependency-ordered remediation phases:

1. request security envelope
2. queue/runtime ownership

## Manifest-controlled defaults

- `target`: `xdm_android`
- retained final validation tasks:
  - `:app:compileDebugKotlin`
  - `:app:testDebugUnitTest`
  - `:app:lintDebug`
- `validation.allow_deferred`: `true`
- `apply.commit.enabled`: `true`
- `apply.commit.strategy`: `single`
- `apply.commit.message`: `Apply XDM Android request security and queue runtime ownership`

Validation is intentionally deferred for the remediation campaign. Apply this intermediate overlay with `--no-validate`; the declared task list remains the canonical final-validation contract.

## Phase 04 — request security envelope

- exact-target approval scopes cover primary URLs, mirrors, migration, media probes, and native redirect hops;
- sensitive/transport-owned headers and malformed URL authority are rejected before transfer;
- Android cleartext policy is honored and sensitive cleartext requests require exact approval;
- torrent/magnet/metalink are explicit `DownloadRequest` kinds, persisted inside the encrypted handoff so caller filename/MIME metadata cannot reclassify a reviewed request after restart;
- backend migration reconstructs exact encrypted request context, including request kind and mirrors, instead of using only redacted Room metadata;
- app-side media probing uses the same security boundary and bounded manual redirects;
- media variant selection cannot widen a capture approval to a different URL;
- sensitive legacy migration envelopes recognizable URL/header material before fail-closed redaction, uses `AtomicFile`, advances rewritten Download timestamps monotonically, and never inherits historical approval;
- unsupported plaintext legacy capture-media handoffs are rejected on the app side.

## Phase 05 — queue/runtime ownership

- startup and boot converge on one ownership-first recovery pipeline behind a durable startup admission hold;
- Room transactionally owns queue slots with `Connecting` as the durable claim and unified null/default queue capacity; failed launch release requires the exact queue-claim token and later runtime transitions preserve strictly newer same-generation timestamps;
- explicit Pause All is a committed admission gate; Resume All explicitly clears it;
- pause/resume/cancel reconcile durable backend ownership after process death instead of mutating Room alone;
- UIDT / foreground-service / WorkManager ownership is selected according to user visibility and platform support, with WorkManager as the legal deferrable/fallback owner; every scheduled owner reauthorizes the exact durable Connecting-row claim token and admission gate before execution; stale Android stop callbacks are serialized by that token and can pause only their own claim; backend attempt generation is bound to that exact queue token rather than download ID alone and remains a separate ownership proof created by the runtime;
- terminal notifications use persisted idempotency and durable collision-free IDs;
- retry generations use attempt/error identity and one-time deadline work;
- schedule, battery, destination-storage, Pause All, and starvation policy gaps fail closed or converge on durable state;
- notification Resume/Retry re-enter queue policy rather than directly starting an FGS.

See `app/XDM.Android/docs/remediation/OVERLAY_04_05_REQUEST_SECURITY_QUEUE_RUNTIME_OWNERSHIP.md` for the implementation boundary.

## Apply

```bash
devtool -r "$HOME/Code/xdm" --yes apply-overlay \
  "/sdcard/Download/xdm_android_request_security_queue_runtime_ownership_overlay_v2.zip" \
  --no-validate
```

Do not start Overlay 06 until this artifact applies cleanly. Campaign validation remains deferred until the final overlay.
