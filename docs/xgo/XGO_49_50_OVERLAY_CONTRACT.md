# XGO-49+50 overlay contract

Target: `xgo_media`

Covered capabilities:

- `XGO-CAP-CAPTURE-001`
- `XGO-CAP-CAPTURE-002`

Forbidden shortcuts:

- Do not treat request replay evidence as logical media identity.
- Do not serialize raw body bytes or raw secret header values in capture evidence or diagnostics.
- Do not let stale document/session observations mutate active media graph state.
- Do not start XGO-51 media resource identity in this overlay.

The overlay validates through the `xgo_media#validate` DAG with no `validation.tasks` override.
