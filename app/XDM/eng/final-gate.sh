#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

"$repo_root/app/XDM/eng/validate-modern.sh"
"$repo_root/app/XDM/eng/remove-legacy-ui.sh" --check
"$repo_root/app/XDM/eng/smoke-package.sh" linux-x64

printf 'XDM final gate passed on Linux.\n'
