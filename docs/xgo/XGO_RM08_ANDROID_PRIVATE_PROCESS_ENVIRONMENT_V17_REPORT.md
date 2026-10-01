# XGO RM08 Android private process environment v17

## Trigger

Direct AndroidJUnitRunner execution proved that the packaged aria2 runtime exits with SIGSEGV / code 139 before authenticated RPC is ready. Immediately before the crash, SELinux denies the app-domain child access to `/data/local/tests` (`shell_test_data_file`). The failing JUnit assertion is therefore downstream of a native process-startup failure, not a TLS/session assertion.

## Remediation

- Keep the existing app-private aria2 working directory.
- Override child `HOME`, `PWD`, temporary-directory, and XDG path variables so the native executable cannot inherit adb/instrumentation shell-test paths.
- Remove inherited `OLDPWD`.
- Preserve the rest of Android's child environment; do not clear it wholesale.
- Classify an observed native process exit before any trailing RPC `ConnectException`, so exit 139 is reported as `ProcessExited` rather than the misleading `ConnectionRefused`.
- Add a unit regression contract for the private environment mapping.

Upstream aria2's Android guidance similarly establishes an application-owned `HOME` and changes into that directory before launching aria2.

## Validation boundary

The overlay uses non-Gradle static/contract validation. The authoritative runtime check remains the Gate-12 Android rebuild + instrumentation lane, which must prove that the installed packaged runtime starts, serves authenticated RPC, enforces TLS policy, and recovers its owned saved session.
