# XDM Android master remediation Overlay 10: media resolver/execution

Target: `xdm_android`.

This intermediate schema-v2 artifact is based on the successfully applied Phase 08+09 v3 tree at commit `79df336c` and implements the next dependency-ordered remediation phase: media resolver/execution.

## Manifest-controlled defaults

- `target`: `xdm_android`
- retained final validation tasks:
  - `:app:compileDebugKotlin`
  - `:app:testDebugUnitTest`
  - `:app:lintDebug`
- `validation.allow_deferred`: `true`
- `apply.commit.enabled`: `true`
- `apply.commit.strategy`: `single`
- `apply.commit.message`: `Apply XDM Android media resolver execution remediation`

Campaign validation is intentionally deferred. Apply this intermediate overlay with `--no-validate`; the declared tasks remain the final-campaign validation contract.

## Overlay 10 promises

- M-031: restores exact selected-variant request headers, uses the configured destination instead of forcing Public Downloads, and enforces dispatch readiness before any queue/external-job mutation;
- app/media side of M-032: app-owned media creates an app `Download`, while yt-dlp/Termux media creates only its real durable external job plus an external media-output record; no synthetic queued `Download` is created for Termux ownership;
- M-047: Room schema 20 adds a one-capture-to-many `media_outputs` relation keyed by owner and attempt generation, with a 19→20 migration that backfills existing capture/download links; Library rows and removals are output/generation keyed rather than capture keyed, and production disables legacy-link resurrection after deletion;
- media side of M-055: media execution failure classification uses structured resolver strategy, capture refresh state, transfer state, and backend identity instead of parsing failure-message substrings.

## Execution ownership details

- app-owned enqueue commits the new `Download`, capture compatibility link, and `media_outputs` child in one Room transaction;
- Termux-owned enqueue commits the durable post-processing job and corresponding output-generation row in one Room transaction before execution launches;
- Termux retry creates a new durable external job attempt and a new output-generation row atomically, preserving prior generations and selected-track history;
- app-owned transfer state is authoritative for the matching Download attempt generation; each ownership-generation change synchronizes a distinct `media_outputs` row and fresh-redownload/restart-from-zero preserves media lineage; Termux-owned state is synchronized from the durable external job;
- captures remain reviewable for repeat output selections; encrypted capture/variant handoffs are retained until explicit capture removal/expiry so later generations keep the exact request context;
- verified completed-artifact identity is persisted by the Download CAS/upsert path and current app Library playback still goes through the validated Download artifact grant;
- completed external artifact URIs/generations are synchronized into output history before durable external job cleanup can make runtime state unavailable;
- removing an app Library row tombstones only the selected output generation so later Download synchronization cannot resurrect it; terminal/recovery Termux owner metadata and its output are removed transactionally, and synchronization revalidates the durable job in-transaction to prevent stale observer resurrection.

## Security boundary retained for Overlay 11

Overlay 10 does not put Cookie, Authorization, Proxy-Authorization, credential-bearing URLs, or other transient secrets on a Termux command line. Authenticated yt-dlp media is held at readiness with an explicit message until Overlay 11 supplies the planned secure transient-secret bridge. Non-sensitive typed yt-dlp arguments remain supported.

## Apply

```bash
devtool -r "$HOME/Code/xdm" --yes apply-overlay \
  "/sdcard/Download/xdm_android_media_resolver_execution_overlay_v2.zip" \
  --no-validate
```

Do not start Overlay 11 until this artifact applies cleanly. Campaign Gradle/unit/lint validation remains deferred until Overlay 13.
