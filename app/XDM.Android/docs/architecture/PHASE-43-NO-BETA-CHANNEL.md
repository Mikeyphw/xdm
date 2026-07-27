# Phase 43 — Pre-release Channel Removal Contract

XDM Android now keeps only two installable Android variants: release and debug. The removed retired pre-release channel no longer has a Gradle build type, package suffix, version suffix, browser scheme, CI task, release-gate task, or generated Firefox extension channel.

## Remaining variants

| Variant | Package id | Browser scheme |
|---|---|---|
| Release | `com.mikeyphw.xdm.android` | `xdmdownload` |
| Debug | `com.mikeyphw.xdm.android.debug` | `xdmdownload-debug` |

## Validation

`tools/validate-no-pre-release-channel.py` scans active Android, Firefox extension, release-gate, CI, and desktop update-channel source for retired pre-release-channel tokens. Historical Devtool transaction records are intentionally excluded because they describe past artifacts rather than active product behavior.
