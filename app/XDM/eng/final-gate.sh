#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

if ! command -v dotnet >/dev/null 2>&1; then
  echo "XDM final gate requires dotnet on PATH. Use rem18-final-release-seal.sh --devtool-package-step for Termux structural evidence only." >&2
  exit 127
fi

python3 "$ROOT/app/XDM/eng/validate-xfe01-single-firefox-extension.py"
python3 "$ROOT/app/XDM/eng/rem18-ledger-audit.py"
python3 "$ROOT/app/XDM/eng/rem18-release-matrix-audit.py"

bash "$ROOT/app/XDM/eng/validate-modern.sh"
bash "$ROOT/app/XDM/eng/remove-legacy-ui.sh" --check
bash "$ROOT/app/XDM/eng/smoke-package.sh" linux-x64

printf 'XDM final gate passed on Linux.\n'
