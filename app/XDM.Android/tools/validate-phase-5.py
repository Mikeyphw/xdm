#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
checks = {
    "destination abstraction": ("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/DestinationProvider.kt", "interface DestinationWriter"),
    "MediaStore writer": ("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt", "MediaStore.Downloads.EXTERNAL_CONTENT_URI"),
    "SAF tree writer": ("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt", "DocumentsContract.createDocument"),
    "persisted URI permission": ("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt", "takePersistableUriPermission"),
    "flush before commit": ("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt", "fileDescriptor.sync"),
    "conflict policies": ("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "enum class FilenameConflictPolicy"),
    "destination health": ("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "DestinationHealthStatus"),
    "native storage integration": ("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt", "destinationWriter.prepare"),
    "schema migration": ("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt", "Migration3To4"),
    "folder picker": ("app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt", "OpenDocumentTree"),
    "public destination default": ("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt", "DestinationUris.PUBLIC_DOWNLOADS"),
}
errors=[]
for name,(relative,needle) in checks.items():
    path=root/relative
    if not path.is_file(): errors.append(f"missing {name}: {relative}"); continue
    text=path.read_text(encoding='utf-8')
    if needle not in text: errors.append(f"missing {name} marker {needle!r} in {relative}")
    if "TODO(" in text or "TODO:" in text: errors.append(f"unfinished TODO in {relative}")
for relative in [
    "storage/src/test/kotlin/com/mikeyphw/xdm/android/storage/FileDestinationWriterTest.kt",
    "persistence/src/androidTest/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabaseMigrationTest.kt",
]:
    if not (root/relative).is_file(): errors.append(f"missing tests: {relative}")
if errors:
    print("Phase 5 validation failed:")
    for error in errors: print(f"- {error}")
    sys.exit(1)
print("Phase 5 validation passed: MediaStore, SAF, SD-card-capable trees, staging, promotion, conflicts, permissions, and native-engine integration are present")
