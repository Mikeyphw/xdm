# XDM Overlay — Secure release channels and rollback-safe updates

This overlay applies on top of commit `417e5c6`.

## Release channels

- persists **Stable**, **Beta**, and **Nightly** selection in settings schema 6;
- routes each channel through its own HTTPS manifest;
- supports automatic startup checks for the selected channel;
- identifies mandatory updates through `minimumSupportedVersion`;
- accepts legacy schema-1 manifests only on Stable and schema-2 manifests on all channels.

## Verified staging and application

- validates trusted HTTPS hosts, package metadata, declared size, SHA-256, and optional SHA-512;
- writes atomic verification receipts and durable update transactions;
- packages a self-contained `XDM.Updater` helper beside XDM and the browser native host;
- copies the helper outside the running installation before replacement;
- rejects unsafe ZIP paths, symlinks, excessive entry counts, and excessive expanded size;
- preserves the old installation as a sibling rollback directory;
- restores the previous installation when swapping or launching the new version fails;
- marks an update healthy only after services initialize and the main window is created;
- terminates and rolls back a new version that exits early or misses the health deadline;
- restores executable permissions for XDM, the native host, and the updater on Unix.

## Release automation

- qualifies Release builds on Ubuntu and Windows;
- publishes self-contained `linux-x64`, `linux-arm64`, `win-x64`, and `win-arm64` artifacts;
- creates deterministic ZIP archives and deterministic Linux tarballs;
- creates Debian packages for amd64 and arm64 on Ubuntu runners;
- optionally Authenticode-signs and verifies all Windows executables when signing secrets are configured;
- creates SPDX JSON SBOMs;
- creates GitHub artifact attestations for packages, SBOMs, checksums, and update manifests;
- publishes SHA-256 and SHA-512 checksum manifests;
- supports immutable stable releases and moving beta/nightly channel tags.

## Validation

Devtool must restore, build, and test only `app/XDM/XDM.Modern.sln`. The build must have zero warnings and zero errors. The parity feature manifest is unchanged.
