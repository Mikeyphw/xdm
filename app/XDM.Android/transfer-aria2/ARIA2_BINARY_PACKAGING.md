# ARM64 aria2 runtime slot

Place the Android ARM64 aria2 executable here as `libaria2c.so` before producing a build that enables the embedded backend.

The filename intentionally uses the native-library convention so Android installs it under the read-only executable `nativeLibraryDir`. The file must remain a PIE ARM64 ELF executable; it is launched as a process and is not loaded with `System.loadLibrary`.

The Phase 6B runtime detects a missing binary and reports aria2 as unavailable. It never copies executable code into writable app storage.
