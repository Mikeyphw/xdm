# GATE-11 — Cross-host gate

GATE-11 closes the Android/Desktop host-migration boundary without recursively replaying all previous XGO subsystem milestones.

## Required evidence

1. **Cross-host metadata parity** — both host bridges consume `xdm_engine_metadata` and validate the canonical Go metadata tuple: engine version, protocol major/minor, ABI major, and engine schema version.
2. **Android remains green** — the focused `xgo_android#validate` authority/integration workflow passes. GATE-10 already owns the expensive Android Gradle full seal, so GATE-11 does not rebuild the whole Android app merely to repeat that milestone.
3. **Desktop focused seal** — `xgo_desktop#validate` proves the P/Invoke host, publication broker, platform services, UI/media reconnection, durable legacy import, and legacy-authority exclusion.
4. **Desktop full seal** — `xdm_modern#validate` runs the real .NET restore/build/test runner. This is the first authoritative compilation/test qualification of the post-XGO-76..80 desktop host.

## Failure policy

A metadata mismatch, Android focused regression, desktop focused regression, .NET compile failure, or .NET test failure blocks GATE-11. The gate intentionally avoids the full GATE-10 Android build because no Android packaging milestone is being reopened here.
