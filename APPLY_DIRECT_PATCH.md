# Apply XDM Android Phase 37 browser scheme contract

Phase 37 depends on the current Phase 36 external-download-handoff baseline and adds the XDM-owned custom URI contract without changing routes, Room, or transfer engines.

```bash
cd "$HOME/Code/xdm" && \
  devtool --copy --auto-hud --hud-mode desktop-window --yes \
  -r "$HOME/Code/xdm" \
  --target xdm_android \
  apply-overlay "$HOME/Downloads/xdm_android_phase37_browser_scheme_contract_overlay.zip" \
  --validate
```

The artifact owns the commit message `Add XDM Android browser scheme contract`. Do not pass `--commit` on the command line. Validation is focused on the Phase 37 parser, manifest, routing, core idempotency, app contracts, and Android-test assembly.
