# XDM Android XAR15 privacy/security/performance report

Overlay: XAR15 — merged roadmap overlay 6 of 8 / Android overlay 15 of 17.

This overlay closes 13 S15 cross-cutting roots while preserving the XFE01 invariant: the production Android Firefox extension remains the canonical implementation and is not modified.

Highlights:
- Guarded/bounded artwork network and decode path.
- One-use approval gate for legacy direct browser-capture MainActivity handoff.
- Async secure request-envelope expiry sweep.
- Bounded browser capture session registry.
- Nonblocking/bounded external ClipData parsing.
- Cancellation-safe transfer runtime.
- Throttled live-progress summary projection.
- Sensitive clipboard handling for settings snapshots.
- Scoped-storage release manifest lane with personal direct-storage lane retained.
