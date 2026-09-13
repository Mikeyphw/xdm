# XDM Desktop REM15 — Desktop notifications and Smart Transfer environment integration

Overlay: **REM15, overlay 15 of 18**  
Target: `xdm_modern`  
Artifact: `xdm_desktop_rem15_notifications_smart_transfer_environment_v1.tar.gz`

## Scope

REM15 closes the four frozen S13 findings assigned to the notification and Smart Transfer environment layer:

- `S13-06` — Windows desktop-toast prerequisites were incomplete.
- `S13-07` — desktop notification execution had no bounded deadline.
- `S13-08` — notification failures were treated as shown notifications.
- `S13-11` — Smart Transfer Auto environment detection lacked maintained-platform truth and Unknown state.

## Implementation summary

### Desktop notification delivery contract

- Reworked `IDesktopNotificationService.ShowAsync` to return `DesktopNotificationDeliveryResult` so callers can distinguish delivered, unsupported, failed, timed out, and suppressed outcomes.
- Added `DesktopNotificationCommandFactory` with explicit platform variants and a stable Windows AUMID: `com.subhra74.xdm.modern`.
- Added a Windows toast command path that attempts Start Menu shortcut/AUMID registration before showing the toast via `CreateToastNotifier($aumid)`.
- Added Linux delivery through `notify-send` with XDM app identity and expiry arguments.
- Preserved macOS delivery through `osascript`.
- Added `ProcessNotificationCommandExecutor` with bounded stdout/stderr capture and cancellation/timeout process-tree termination.
- Added an eight-second default delivery timeout so notification commands cannot indefinitely block awaited workflows.

### Notification center state accuracy

- `NotificationCenterService` now marks `DesktopNotificationShown=true` only after a real successful delivery result.
- Unsupported, failed, timed-out, and suppressed notifications remain visible in the in-app notification center without falsely claiming OS delivery.
- Delivery failure text is retained on `NotificationCenterEntry.DesktopNotificationFailure` for diagnostics and future REM16 support-bundle/health integration.

### Smart Transfer environment detection

- Replaced the environment-only probe with an injectable `ITransferEnvironmentDetector` boundary.
- Preserved explicit `XDM_NETWORK_METERED` and `XDM_ON_BATTERY` overrides, but labels them as overrides in snapshot source text.
- Added Linux metered detection through bounded `nmcli GENERAL.METERED` probing.
- Added Linux battery detection through `/sys/class/power_supply`.
- Added Windows metered detection through bounded WinRT `NetworkInformation.GetInternetConnectionProfile().GetConnectionCost()` PowerShell probing.
- Added Windows battery detection through bounded `Win32_Battery` CIM probing.
- Unknown or unavailable detection now remains `null` and is reported as `network cost unknown` / `power source unknown` instead of being coerced into false Auto confidence.

## Contract tests added/updated

- `DesktopNotificationServiceTests.WindowsCommandDeclaresAumidAndShortcutRegistration`
- `DesktopNotificationServiceTests.LinuxCommandUsesNotifySendWithAppIdentity`
- `DesktopNotificationServiceTests.DeliveryHasDeadlineAndReportsTimeout`
- `DesktopNotificationServiceTests.DeliveryFailureIsReportedInsteadOfPretendingShown`
- `DesktopNotificationServiceTests.UnsupportedPlatformReturnsExplicitUnsupportedState`
- `SystemTransferEnvironmentProbeTests.AutoDetectionKeepsUnknownMeteredStateWhenPlatformCannotResolveCost`
- `SystemTransferEnvironmentProbeTests.EnvironmentOverridesRemainExplicitAndDoNotMasqueradeAsAutoDetection`
- `SystemTransferEnvironmentProbeTests.CancellationIsPropagatedBeforeDetectorWork`
- `DesktopProductivityServiceTests.NotificationCenterMarksDesktopShownOnlyAfterSuccessfulDelivery`
- `DesktopProductivityServiceTests.NotificationCenterKeepsShownFalseWhenDesktopDeliveryFails`

## Files changed

```text
app/XDM/src/XDM.Core/Abstractions/IDesktopNotificationService.cs
app/XDM/src/XDM.Platform/DesktopNotificationService.cs
app/XDM/src/XDM.Platform/SystemTransferEnvironmentProbe.cs
app/XDM/src/XDM.App/Services/NotificationCenterService.cs
app/XDM/src/XDM.Core.Tests/DesktopNotificationServiceTests.cs
app/XDM/src/XDM.Core.Tests/SystemTransferEnvironmentProbeTests.cs
app/XDM/src/XDM.App.Tests/DesktopProductivityServiceTests.cs
```

## Validation performed in this environment

The local ChatGPT container still does not have the .NET SDK installed, so `dotnet test` could not be executed here. The overlay was validated with static and packaging checks:

- notification contract sentinels present
- Smart Transfer environment detection sentinels present
- manifest JSON parsed successfully
- tarball extraction and declared-file presence verified

## Devtool notes

This is an intermediate overlay. Apply with `--no-validate`; reserve full validation for REM18 unless a targeted REM15 validation pass is explicitly requested.

The overlay includes a schema-v2 `.devtool-artifact.json` commit policy:

```json
"commit": {
  "enabled": true,
  "strategy": "single",
  "message": "fix(desktop): implement REM15 notifications and smart transfer environment"
}
```
