# XGO RM08 Android Bionic linkage remediation v18

Direct AndroidJUnitRunner execution proved the packaged aria2 process still exits with SIGSEGV/139 before authenticated RPC startup after v17 process-environment isolation. The runtime attestation reported an empty DT_NEEDED set.

v18 moves the source build back onto the maintained Android/NDK link model: third-party dependencies and libc++ remain static, while Android/Bionic remains dynamically linked through the platform linker. The build now uses the Android compiler driver's `-static-libstdc++` policy, runs a pre-build C++/getaddrinfo ELF probe, and requires `libc.so` in DT_NEEDED while rejecting `libc++_shared.so`.

The final source-built ELF and release verifier enforce the same condition. A zero-DT_NEEDED aria2 binary can no longer pass RM08 source-build or APK qualification. The native recipe marker schema is bumped so stale cached aria2 output is not reused.

This overlay does not weaken the RM08 instrumentation test and does not change the Gate-12 bypass workflow. Authoritative validation remains a fresh Devtool source/package build followed by exact-pair instrumentation.
