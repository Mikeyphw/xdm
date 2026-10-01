# XGO Gate-12 RM08 source-runtime lineage v19

Direct AndroidJUnitRunner evidence after the v18 recipe change still reported the exact pre-v18 aria2 SHA-256 and an empty `DT_NEEDED` set. The temporary `registry_bypass` graph was found to refresh APKs without first invoking `xgo_rm08_aria2_source`, so Gradle correctly repackaged the stale prebuilt JNI runtime.

v19 adds `aria2_source_runtime -> android_pair_refresh -> package_attestation` to the opt-in bypass lane. The canonical Gate-12 validate workflow remains unchanged. The source target publishes both `libaria2c.so` and `aria2-runtime.lock.json`; the subsequent APK refresh packages that materialized runtime; RM08 attestation then compares packaged bytes to the refreshed lock.

This is an artifact-lineage repair. It does not alter the v18 native compiler/linker model.
