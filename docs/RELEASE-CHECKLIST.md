# Modern XDM prerelease checklist

## Source

- [ ] Working tree is clean and commit history identifies the intended release
- [ ] `VERSION` matches package and release names
- [ ] `XDM.Modern.sln` restores, builds, and tests with zero warnings
- [ ] Bootstrap validation passes on Linux and Windows
- [ ] No active workflow restores or builds WPF, GTK, WinForms, or MSIX projects

## Functional smoke tests

- [ ] Add, pause, resume, cancel, retry, remove, and complete a download
- [ ] Resume against a range-capable server
- [ ] Verify safe restart when a server ignores Range or changes validators
- [ ] Run two queues simultaneously and verify queue-specific limits
- [ ] Verify an overnight schedule and missed-start behavior
- [ ] Capture a browser download and repair the native host
- [ ] Probe direct media, HLS, and DASH fixtures
- [ ] Verify tray restore, explicit exit, notification, and second-instance activation
- [ ] Export a diagnostic bundle and inspect it for secrets
- [ ] Recover from an interrupted finalization marker

## Packages

- [ ] Linux x64 self-contained package launches on a clean supported system
- [ ] Linux ARM64 self-contained package launches on a clean supported system
- [ ] Windows x64 self-contained package launches on a clean supported system
- [ ] `XDM.NativeHost` is beside the application executable
- [ ] Browser manifests point to the packaged native-host path
- [ ] Upgrade preserves settings and history
- [ ] Uninstall leaves user data unless explicitly requested
- [ ] Package hashes are published

## Performance

- [ ] Large-history regression tests pass
- [ ] Bootstrap benchmark results are archived
- [ ] UI remains responsive with 10,000 history items
- [ ] Logs and timelines remain bounded

## Cutover

- [ ] Create a backup branch/tag before deleting legacy projects
- [ ] Run `remove-legacy-ui.sh --check`
- [ ] Run `remove-legacy-ui.sh --apply`
- [ ] Re-run full validation and package qualification
