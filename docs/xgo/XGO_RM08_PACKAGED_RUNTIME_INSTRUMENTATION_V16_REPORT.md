# XGO RM08 packaged-runtime instrumentation redesign v16

This remediation replaces `Aria2Rm08PackagedRuntimeInstrumentedTest` as a test implementation rather than weakening or bypassing its contract.

The single JUnit entry point is retained so Gate-12 test inventory and failure identity remain stable. Internally it now runs two isolated phases:

1. packaged-runtime attestation, authenticated RPC startup, untrusted TLS rejection, and trusted TLS success;
2. a fresh owned-session fixture that creates a paused task, writes ownership metadata, proves the saved session contains both its GID and URI, performs a clean daemon stop, starts a new manager against the same durable store, waits for the owned task to reappear as paused/waiting, removes it, saves the session again, and proves the record disappears.

The redesign removes immediate state assumptions from the old test. Task states and session-file mutations are observed with bounded polling. TLS and recovery no longer share one long manager lifetime. Cleanup remains best-effort in `finally`, while the intended stop/restart boundaries retain strict assertions.

No production aria2 implementation is changed by this overlay. The Gate-12 artifact-registry bypass, exact APK pair reuse, signer qualification, and canonical validation workflow are unchanged.
