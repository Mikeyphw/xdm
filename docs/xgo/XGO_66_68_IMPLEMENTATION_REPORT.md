# XGO-66..68 Android host reconnection foundation

This overlay reconnects the Android host to the Go engine through one narrow ABI/lifecycle boundary.

## Delivered

- `libxdmcore.so` build plan for Android `arm64-v8a` using `GOOS=android`, c-shared mode, and the existing Go C ABI.
- Maintained C header at `app/XDM.Android/app/src/main/cpp/xdm_core.h`.
- Engine metadata manifest under Android assets with ABI, wire protocol and symbol contract.
- Kotlin bridge that owns only native handles, buffers and ABI exceptions.
- Single `AndroidEngineService` authority registered as non-exported service.
- Engine lifecycle model covering bind, Activity recreation, duplicate starts, process restart and host foreground escalation request.

## Validation

The `xgo_android#validate` DAG now includes:

1. `bootstrap_contract`
2. `android_engine_build`
3. `jni_bridge_instrumentation`
4. `engine_service_instrumentation`

The overlay intentionally does not move transfer, media, scheduler or publication policy into Kotlin.
