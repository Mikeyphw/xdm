# Update rollback and release trust

REM17 changes the desktop updater from a plain staged-package downloader into a
transaction-owned portable updater with explicit release-trust metadata.

## Portable self-update model

Portable installations may stage a package from the signed release manifest and
apply it through `XDM.Updater`, which is copied into the update staging tree
before the application shuts down. The external runner waits for the old process
with a bounded deadline, extracts the verified portable ZIP into a candidate
directory, records an Applying transaction state, moves the current install into
a rollback directory, and then moves the candidate into place.

If the machine loses power after the install root was moved but before the new
candidate is in place, the next updater invocation treats the transaction as
interrupted, restores the rollback directory to the install root, marks the
transaction Failed, and refuses to keep applying without a fresh staging pass.

## Observed health window

A restarted version is not marked Healthy immediately. The transaction carries a
`healthyAfterUtc` timestamp and the application marks the transaction healthy
only after that observation window has elapsed and the same transaction is still
`AppliedPendingHealth`. Rollback directories and recovery markers are retained
until that point.

## Package-manager model

`.deb` and `.rpm` artifacts remain package-manager owned. The in-app update UI
can report their availability, but package-managed installations do not mutate
`/opt/xdm`, `/usr/lib/xdm`, or `/usr/share/xdm` with the portable self-updater.
Those installations must be updated through the OS package manager.

## Release metadata trust

Stable release metadata is generated only with a full commit SHA and the exact
`v<version>` tag. The manifest carries the release commit, tag, SHA-256, SHA-512,
SBOM links, provenance links, and install model for each portable package. Stable
Windows packaging must Authenticode-sign and verify executables before the
portable ZIP is republished for upload.
