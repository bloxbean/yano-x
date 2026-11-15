#!/usr/bin/env bash
set -euo pipefail

template_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(cd -- "$template_dir/../.." && pwd)"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/yano-plugin-template-inputs.XXXXXX")"
trap 'rm -rf "$scratch"' EXIT

check_missing() {
    local name="$1"
    shift
    if "$repo_root/gradlew" --offline --no-daemon --no-watch-fs --no-parallel \
            -p "$template_dir" help "$@" >"$scratch/$name.log" 2>&1; then
        echo "FAIL: $name accepted missing template versions" >&2
        return 1
    fi
    grep -F 'Plugin template requires both -PyanoVersion=<published-host-version>' "$scratch/$name.log" >/dev/null
    grep -F -- '-PyanoXVersion=<published-framework-version>' "$scratch/$name.log" >/dev/null
    if grep -F 'Cannot query the value of this provider' "$scratch/$name.log" >/dev/null; then
        echo "FAIL: $name exposed the provider implementation error" >&2
        return 1
    fi
}

check_missing missing-both
check_missing missing-host -PyanoXVersion=0.1.0-test
check_missing missing-framework -PyanoVersion=0.1.0-test
check_missing blank-framework -PyanoVersion=0.1.0-test '-PyanoXVersion=   '
echo 'PASS: template missing/blank version inputs give actionable instructions'
