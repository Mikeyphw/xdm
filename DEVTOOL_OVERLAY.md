# XDM segmented acceleration overlay

Requires: `xdm_functional_parity_audit_overlay.zip`  
Target: `xdm_modern`  
Framework: `.NET 10`

## Scope

- Adds concurrent bounded HTTP range transfers with four connections by default.
- Adds range capability probing and safe single-stream fallback.
- Adds per-segment durable checkpoints and independent retry/resume.
- Validates ranges, validators, lengths, gaps and overlaps.
- Merges segments deterministically into the existing crash-safe finalization path.
- Preserves request headers, cookies, referer, user agent and Basic authentication.
- Applies the existing per-download/queue/global bandwidth limit across segment workers.
- Marks `download.segmented-transfer` complete in the executable parity ledger.

## Validation

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore
dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build
```

## Regression compatibility fix

The existing truncated-response checkpoint test explicitly requests `ConnectionCount: 1`. That test validates the single-stream retry/checkpoint path; allowing the new range probe to consume its intentionally stateful first response changes the fixture rather than the behavior under test. Segmented retry behavior remains covered independently by `SegmentedDownloadTests`.
