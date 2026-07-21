# Aria2 Runtime Alignment Gate

The Android aria2 backend uses an optional packaged ARM64 `aria2c` payload. The
current pinned upstream runtime is useful for developer builds, but Android Lint
can report `Aligned16KB` when that ELF is not built for 16 KB page-size devices.

Default development validation keeps aria2 optional and disables the lint check
for the known optional payload, so installing the runtime does not block unrelated
UI/resource overlays. Strict aria2 distribution builds must opt in with:

```bash
./gradlew -Pxdm.requireAria2Runtime=true :transfer-aria2:verifyAria2Runtime assembleDebug
```

That mode requires the payload and runs the repository verifier with
`--require-16kb-alignment`. If the bundled runtime is not 16 KB aligned, the
strict build must fail until the runtime is rebuilt or replaced with a trusted
16 KB aligned artifact.

Launcher icon resources intentionally use adaptive vector resources only. Because
`minSdk` is 26, density PNG launcher fallbacks are unnecessary and can create
extra icon lint noise.
