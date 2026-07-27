# Apply XDM Android Phase 40 shared theme and themed FAB

Phase 40 depends on the landed Phase 39 XPI export pipeline. It adds the shared XDM theme contract, Follow app export behavior, and compact Shadow DOM FAB without expanding the browser deep-link payload or changing routes, Room, transfer engines, or app version.

```bash
cd "$HOME/Code/xdm" && \
  devtool --copy --auto-hud --hud-mode desktop-window --yes \
  -r "$HOME/Code/xdm" \
  --target xdm_android \
  apply-overlay "$HOME/Downloads/xdm_android_phase40_extension_theme_fab_overlay.zip" \
  --validate
```

The schema-v2 artifact owns the commit message `Add shared XDM extension theme and FAB`. Do not pass `--commit`.

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

# Apply XDM Android Phase 39 deterministic XPI generation and SAF export

Phase 39 depends on the landed Phase 38 repository-owned Firefox extension. It adds the shared deterministic XPI packager, Android SAF export transaction, persisted export settings, and focused validation without changing routes, Room, transfer engines, or the app version.

```bash
cd "$HOME/Code/xdm" && \
  devtool --copy --auto-hud --hud-mode desktop-window --yes \
  -r "$HOME/Code/xdm" \
  --target xdm_android \
  apply-overlay "$HOME/Downloads/xdm_android_phase39_xpi_generation_saf_export_overlay.zip" \
  --validate
```

The schema-v2 artifact owns the commit message `Add deterministic XDM Firefox XPI export`. Do not pass `--commit`.


## Phase 41 browser bridge integration

Apply `xdm_android_phase41_browser_bridge_integration_overlay.zip` after Phase 40. The artifact owns the commit message `Add browser bridge settings diagnostics and recovery` and runs the focused Phase 37–41 validators plus browser-extension and Android unit tests.
