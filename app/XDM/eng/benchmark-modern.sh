#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
solution="$repo_root/app/XDM/XDM.Modern.sln"
project="$repo_root/app/XDM/src/XDM.App/XDM.App.csproj"
out_dir="$repo_root/artifacts/benchmarks"
mkdir -p "$out_dir"

dotnet build "$solution" -c Release --no-restore >/dev/null
dotnet test "$solution" -c Release --no-build \
  --filter FullyQualifiedName~LargeHistoryPerformanceTests \
  --logger "trx;LogFileName=large-history.trx" \
  --results-directory "$out_dir"

start_ns="$(date +%s%N)"
dotnet run --project "$project" -c Release --no-build -- --validate-bootstrap >/dev/null
end_ns="$(date +%s%N)"
elapsed_ms="$(( (end_ns - start_ns) / 1000000 ))"
printf '{\n  "bootstrapMilliseconds": %s,\n  "measuredAtUtc": "%s"\n}\n' \
  "$elapsed_ms" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$out_dir/bootstrap.json"
printf 'Bootstrap validation: %sms\n' "$elapsed_ms"
