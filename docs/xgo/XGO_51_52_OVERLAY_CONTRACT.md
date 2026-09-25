# XGO-51+52 overlay contract

- Target: `xgo_media`
- Covered IDs: `XGO-51`, `XGO-52`
- Covered capabilities: `XGO-CAP-MEDIA-001`, `XGO-CAP-MEDIA-002`, `XGO-CAP-MEDIA-004`
- Validation required: yes
- Failure action: pause
- `validation.tasks`: absent
- Devtool setup changes: forbidden

## Exit criteria

The overlay is complete only when `xgo_media#validate` includes capture, generation, media identity and media graph jobs and when the exact packaged ZIP replays into the baseline with matching manifest hashes.
