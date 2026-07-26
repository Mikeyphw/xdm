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

# Apply XDM Android Phase 38 repository-owned Firefox extension

Phase 38 depends on the landed Phase 37 browser scheme contract. It adds the canonical Firefox extension source and development validation module without generating an XPI or changing Android runtime behavior.

```bash
cd "$HOME/Code/xdm" && \
  devtool --copy --auto-hud --hud-mode desktop-window --yes \
  -r "$HOME/Code/xdm" \
  --target xdm_android \
  apply-overlay "$HOME/Downloads/xdm_android_phase38_repo_owned_firefox_extension_overlay.zip" \
  --validate
```

The schema-v2 artifact owns the commit message `Add repository-owned XDM Firefox extension`. Do not pass `--commit`.
