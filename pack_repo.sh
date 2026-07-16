#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  pack_repo.sh [options] [subdir ...]

Options:
  -o, --output PATH   Write the tar.gz archive to PATH.
  -h, --help          Show this help text.

Behavior:
  - With no subdir arguments, pack the whole repository.
  - With one or more subdir arguments, pack only those subtrees.
  - Respect gitignore and exclude common build/cache directories.
EOF
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
default_output="/tmp/$(basename "$repo_root")-$(date +%Y%m%d-%H%M%S).tar.gz"
output_path="$default_output"
inputs=()

while (($#)); do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    -o|--output)
      if (($# < 2)); then
        echo "error: missing value for $1" >&2
        exit 1
      fi
      output_path="$2"
      shift 2
      ;;
    --output=*)
      output_path="${1#*=}"
      shift
      ;;
    --)
      shift
      while (($#)); do
        inputs+=("$1")
        shift
      done
      ;;
    *)
      inputs+=("$1")
      shift
      ;;
  esac
done

mkdir -p "$(dirname "$output_path")"

if ! command -v git >/dev/null 2>&1; then
  echo "error: git is required" >&2
  exit 1
fi

if ! command -v tar >/dev/null 2>&1; then
  echo "error: tar is required" >&2
  exit 1
fi

should_skip() {
  case "$1" in
    .git|.git/*|*/.git|*/.git/*)
      return 0
      ;;
    bin|bin/*|*/bin|*/bin/*)
      return 0
      ;;
    obj|obj/*|*/obj|*/obj/*)
      return 0
      ;;
    build|build/*|*/build|*/build/*|Build|Build/*|*/Build|*/Build/*)
      return 0
      ;;
    cache|cache/*|*/cache|*/cache/*|Cache|Cache/*|*/Cache|*/Cache/*|.cache|.cache/*|*/.cache|*/.cache/*)
      return 0
      ;;
    artifacts|artifacts/*|*/artifacts|*/artifacts/*)
      return 0
      ;;
    node_modules|node_modules/*|*/node_modules|*/node_modules/*)
      return 0
      ;;
    TestResults|TestResults/*|*/TestResults|*/TestResults/*)
      return 0
      ;;
    .vs|.vs/*|*/.vs|*/.vs/*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

{
  if ((${#inputs[@]})); then
    git -C "$repo_root" ls-files -z -co --exclude-standard -- "${inputs[@]}"
  else
    git -C "$repo_root" ls-files -z -co --exclude-standard
  fi
} | while IFS= read -r -d '' path; do
  if should_skip "$path"; then
    continue
  fi
  printf '%s\0' "$path"
done | tar -C "$repo_root" --null -T - --zstd -cf "$output_path"

echo "created $output_path"
