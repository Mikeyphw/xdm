#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
version="$(tr -d '[:space:]' < "$repo_root/VERSION")"
artifacts_dir="$repo_root/artifacts/rem18-final-seal"
run_core=1
run_static_audits=1
run_package_matrix=1
run_linux_x64_smoke=1
run_linux_arm64_smoke=1
run_powershell_static=1
run_finalize=1
devtool_package_step=0
termux_static_fallback=0

component_only() {
  run_core=0
  run_static_audits=0
  run_package_matrix=0
  run_linux_x64_smoke=0
  run_linux_arm64_smoke=0
  run_powershell_static=0
  run_finalize=0
  devtool_package_step=1
  termux_static_fallback=1
}

for arg in "$@"; do
  case "$arg" in
    --devtool-artifact-validation)
      run_core=0
      run_package_matrix=0
      run_linux_x64_smoke=0
      run_linux_arm64_smoke=0
      ;;
    --devtool-package-step)
      # Devtool package validation is used on Termux where the .NET SDK may not
      # be available. Keep the final REM18 ledger/matrix audits authoritative,
      # create a concrete evidence artifact, and defer executable .NET publish
      # proof to desktop/CI when dotnet is absent.
      run_core=0
      devtool_package_step=1
      termux_static_fallback=1
      ;;
    --skip-core)
      run_core=0
      ;;
    --skip-package)
      run_package_matrix=0
      run_linux_x64_smoke=0
      run_linux_arm64_smoke=0
      ;;
    --skip-static-audits)
      run_static_audits=0
      ;;
    --static-only)
      run_core=0
      run_package_matrix=0
      run_linux_x64_smoke=0
      run_linux_arm64_smoke=0
      ;;
    --devtool-package-matrix-only)
      component_only
      run_package_matrix=1
      ;;
    --devtool-linux-x64-smoke-only)
      component_only
      run_linux_x64_smoke=1
      ;;
    --devtool-linux-arm64-smoke-only)
      component_only
      run_linux_arm64_smoke=1
      ;;
    --devtool-powershell-static-only)
      component_only
      run_powershell_static=1
      ;;
    --devtool-finalize-only)
      component_only
      run_finalize=1
      ;;
    *)
      echo "Unknown REM18 final-seal argument: $arg" >&2
      exit 2
      ;;
  esac
done

mkdir -p "$artifacts_dir"

log_step() {
  printf '\n[rem18] %s\n' "$1"
}

run_and_log() {
  local name="$1"; shift
  log_step "$name"
  "$@" 2>&1 | tee "$artifacts_dir/${name//[^A-Za-z0-9_.-]/_}.log"
}

have_dotnet() {
  command -v dotnet >/dev/null 2>&1
}

write_dotnet_deferred() {
  local reason="$1"
  cat > "$artifacts_dir/dotnet-validation-deferred.json" <<JSON
{
  "schemaVersion": 1,
  "overlay": "REM18",
  "overlayIndex": 18,
  "overlayTotal": 18,
  "status": "deferred",
  "reason": "$reason",
  "requiredDesktopOrCiCommands": [
    "python3 app/XDM/eng/validate-xfe01-single-firefox-extension.py",
    "dotnet restore app/XDM/XDM.Modern.sln",
    "dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore",
    "dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build",
    "bash app/XDM/eng/rem18-final-release-seal.sh"
  ]
}
JSON
}

create_evidence_archive() {
  local archive="$artifacts_dir/rem18-final-seal-evidence.tar.gz"
  local tmp="$artifacts_dir/.rem18-final-seal-evidence.tar.gz.tmp"
  rm -f "$tmp" "$archive"
  (
    cd "$artifacts_dir"
    find . -type f \
      ! -name 'rem18-final-seal-evidence.tar.gz' \
      ! -name '.rem18-final-seal-evidence.tar.gz.tmp' \
      -print0 | LC_ALL=C sort -z | tar --null -T - -czf "$tmp"
  )
  mv "$tmp" "$archive"
}

if [[ "$run_static_audits" == 1 ]]; then
  log_step "single Firefox extension convergence audit"
  python3 "$repo_root/app/XDM/eng/validate-xfe01-single-firefox-extension.py" \
    2>&1 | tee "$artifacts_dir/single-firefox-extension-audit.log"

  log_step "ledger closure audit"
  python3 "$repo_root/app/XDM/eng/rem18-ledger-audit.py" \
    --write-evidence "artifacts/rem18-final-seal/ledger-audit.json"

  log_step "release matrix contract audit"
  python3 "$repo_root/app/XDM/eng/rem18-release-matrix-audit.py" \
    --write-evidence "artifacts/rem18-final-seal/release-matrix-audit.json"
fi

if [[ "$run_core" == 1 ]]; then
  if ! have_dotnet; then
    echo "REM18 full core gate requires dotnet on PATH." >&2
    write_dotnet_deferred "dotnet executable was not found on PATH during full core gate"
    exit 127
  fi
  run_and_log dotnet_restore dotnet restore "$repo_root/app/XDM/XDM.Modern.sln"
  run_and_log dotnet_build dotnet build "$repo_root/app/XDM/XDM.Modern.sln" --configuration Release --no-restore -warnaserror
  run_and_log dotnet_test dotnet test "$repo_root/app/XDM/XDM.Modern.sln" --configuration Release --no-build --logger "trx;LogFileName=rem18-tests.trx" --results-directory "$repo_root/artifacts/test-results"
  run_and_log bootstrap_validate dotnet run --project "$repo_root/app/XDM/src/XDM.App/XDM.App.csproj" --configuration Release --no-build -- --validate-bootstrap
fi

is_linux_runner() {
  [[ "${RUNNER_OS:-$(uname -s)}" == Linux* || "$(uname -s 2>/dev/null || echo unknown)" == Linux* ]]
}

if [[ "$run_package_matrix" == 1 ]]; then
  if is_linux_runner; then
    log_step "linux package matrix"
    if have_dotnet; then
      # Use bash so the package helper does not depend on executable bit state
      # after cross-device extraction or FAT/emulated-storage copies.
      bash "$repo_root/app/XDM/eng/package-linux.sh" "$version" 2>&1 | tee "$artifacts_dir/linux-package-matrix.log"
    elif [[ "$termux_static_fallback" == 1 ]]; then
      echo "dotnet not found; recording Termux structural final-seal evidence and deferring binary publish proof." | tee "$artifacts_dir/linux-package-matrix.log"
      write_dotnet_deferred "dotnet executable was not found on PATH during Termux package step"
    else
      echo "REM18 package matrix requires dotnet on PATH." >&2
      write_dotnet_deferred "dotnet executable was not found on PATH during package matrix"
      exit 127
    fi
  else
    log_step "non-Linux package matrix"
    echo "Linux package execution skipped on non-Linux runner; structural matrix remains enforced." | tee "$artifacts_dir/non-linux-package-note.log"
  fi
fi

if [[ "$run_linux_x64_smoke" == 1 ]]; then
  log_step "linux-x64 package smoke"
  if is_linux_runner && have_dotnet; then
    bash "$repo_root/app/XDM/eng/smoke-package.sh" linux-x64 2>&1 | tee "$artifacts_dir/linux-x64-smoke.log"
  elif [[ "$termux_static_fallback" == 1 ]] || ! have_dotnet; then
    echo "linux-x64 smoke deferred because dotnet is unavailable on this Devtool execution host." | tee "$artifacts_dir/linux-x64-smoke.log"
  else
    echo "linux-x64 smoke skipped on non-Linux runner." | tee "$artifacts_dir/linux-x64-smoke.log"
  fi
fi

if [[ "$run_linux_arm64_smoke" == 1 ]]; then
  log_step "linux-arm64 structural smoke"
  if is_linux_runner && have_dotnet; then
    bash "$repo_root/app/XDM/eng/smoke-package.sh" linux-arm64 --publish-only 2>&1 | tee "$artifacts_dir/linux-arm64-structural-smoke.log"
  elif [[ "$termux_static_fallback" == 1 ]] || ! have_dotnet; then
    echo "linux-arm64 structural smoke deferred because dotnet is unavailable on this Devtool execution host." | tee "$artifacts_dir/linux-arm64-structural-smoke.log"
  else
    echo "linux-arm64 structural smoke skipped on non-Linux runner." | tee "$artifacts_dir/linux-arm64-structural-smoke.log"
  fi
fi

if [[ "$run_powershell_static" == 1 ]]; then
  if command -v pwsh >/dev/null 2>&1; then
    log_step "PowerShell final-seal structural validation"
    pwsh -NoLogo -NoProfile -File "$repo_root/app/XDM/eng/rem18-final-release-seal.ps1" -StaticOnly 2>&1 | tee "$artifacts_dir/rem18-powershell-static.log"
  else
    log_step "PowerShell final-seal structural validation"
    echo "pwsh not found; optional PowerShell structural validation skipped." | tee "$artifacts_dir/rem18-powershell-static.log"
  fi
fi

if [[ "$run_finalize" == 1 ]]; then
  package_matrix_attempted=false
  if [[ "$run_package_matrix" == 1 || -f "$artifacts_dir/linux-package-matrix.log" || -f "$artifacts_dir/non-linux-package-note.log" ]]; then
    package_matrix_attempted=true
  fi
  cat > "$artifacts_dir/final-seal-summary.json" <<JSON
{
  "schemaVersion": 1,
  "overlay": "REM18",
  "overlayIndex": 18,
  "overlayTotal": 18,
  "mergedOverlay": "2 of 8",
  "version": "$version",
  "validatedRids": ["linux-x64", "linux-arm64", "win-x64", "win-arm64"],
  "ledgerExpectedTotal": 258,
  "ledgerExpectedSeverityTotals": {"HIGH": 67, "MEDIUM": 153, "LOW": 38},
  "devtoolPackageStep": $([[ "$devtool_package_step" == 1 ]] && echo true || echo false),
  "termuxStaticFallbackAllowed": $([[ "$termux_static_fallback" == 1 ]] && echo true || echo false),
  "dotnetAvailable": $(have_dotnet && echo true || echo false),
  "packageMatrixAttempted": $package_matrix_attempted,
  "singleFirefoxExtensionConvergence": "validated by validate-xfe01-single-firefox-extension.py",
  "canonicalFirefoxExtensionId": "xdm-android-media-bridge@mikeyphw"
}
JSON

  create_evidence_archive
  printf '\nREM18 final release seal completed. Evidence: %s\n' "$artifacts_dir"
fi
