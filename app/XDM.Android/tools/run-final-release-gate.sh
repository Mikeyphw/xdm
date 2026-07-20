#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

for validator in   tools/validate-foundation.py   tools/validate-phase-2-3.py   tools/validate-phase-4.py   tools/validate-phase-5.py   tools/validate-ownership-hardening.py   tools/validate-phase-6b.py   tools/validate-phase-6.py   tools/validate-phase-7.py   tools/validate-phase-8.py   tools/validate-phase-9.py   tools/validate-phase-10.py   tools/validate-phase-11.py   tools/validate-phase-12.py   tools/validate-phase-13.py   tools/validate-phase-14.py   tools/validate-phase-15.py   tools/validate-phase-16.py   tools/validate-phase-17.py; do
  python3 "$validator"
done

if [[ "${1:-}" == "--ci" ]]; then
  echo "CI final static gate passed"
  exit 0
fi

cat <<'EOF'
Final static gate passed.

For the actual public release gate, run the full devtool overlay validation from the repository root:

devtool --copy --auto-hud --hud-mode desktop-window --yes -r "$HOME/Code/xdm" apply-overlay "$HOME/Downloads/xdm_android_phase17_final_public_release_gate_overlay.zip" --validate
EOF
