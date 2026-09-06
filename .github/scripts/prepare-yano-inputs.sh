#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
: "${GITHUB_ENV:?GITHUB_ENV is required}"

fail() { printf '%s\n' "$1" >&2; exit 1; }

input_dir="$GITHUB_WORKSPACE/build/ci-inputs"
mkdir -p "$input_dir"
if [[ -n "${YANO_INPUTS_RUN_ID:-}${YANO_INPUTS_COMMIT:-}" ]]; then
  [[ "${YANO_INPUTS_RUN_ID:-}" =~ ^[0-9]+$ ]] || fail 'Invalid staging run ID'
  [[ "${YANO_INPUTS_COMMIT:-}" =~ ^[0-9a-f]{40}$ ]] || fail 'A full host commit pin is required'
  if [[ -n "${YANO_JVM_DIST_URL:-}${YANO_MAVEN_REPOSITORY_URL:-}" ]]; then
    echo 'Do not mix a pinned input artifact with independent ZIP/repository overrides' >&2
    exit 1
  fi
  run_metadata="$(gh api "repos/bloxbean/yano/actions/runs/$YANO_INPUTS_RUN_ID")"
  jq -e --arg commit "$YANO_INPUTS_COMMIT" '
    .head_sha == $commit and .status == "completed" and .conclusion == "success"
    and .event == "workflow_dispatch" and .path == ".github/workflows/integration.yml"
    and .repository.full_name == "bloxbean/yano"
  ' <<< "$run_metadata" >/dev/null || fail 'Staging run provenance does not match the pin'
  staged_inputs="$(mktemp -d "$input_dir/staged.XXXXXX")"
  gh run download "$YANO_INPUTS_RUN_ID" --repo bloxbean/yano \
    --name "yano-inputs-$YANO_INPUTS_COMMIT" --dir "$staged_inputs"
  jq -e --arg commit "$YANO_INPUTS_COMMIT" '
    .schemaVersion == 1 and .repository == "bloxbean/yano" and .commit == $commit
    and .mavenDirectory == "maven" and .distribution == ("yano-" + .version + ".zip")
  ' "$staged_inputs/yano-inputs.json" >/dev/null || fail 'Invalid staged input manifest'
  staged_version="$(jq -r .version "$staged_inputs/yano-inputs.json")"
  [[ "$staged_version" =~ ^[0-9A-Za-z.-]+$ ]] || fail 'Invalid staged version'
  [[ "$staged_version" == *-"${YANO_INPUTS_COMMIT:0:9}" ]] || fail 'Version does not match the host commit'
  [[ -z "${YANO_VERSION:-}" || "$YANO_VERSION" == "$staged_version" ]] || fail 'Conflicting Yano version override'
  while IFS= read -r checksum; do
    [[ "$checksum" =~ ^[0-9a-f]{64}[[:space:]][[:space:]][A-Za-z0-9_./+-]+$ ]] || fail 'Invalid checksum inventory'
    checksum_path="${checksum:66}"
    [[ "$checksum_path" != /* && "/$checksum_path/" != *"/../"* ]] || fail 'Unsafe checksum path'
  done < "$staged_inputs/SHA256SUMS"
  (cd "$staged_inputs" && sha256sum --check --strict SHA256SUMS) || fail 'Staged input checksum mismatch'
  [[ -f "$staged_inputs/maven/com/bloxbean/cardano/yano-core-api/$staged_version/yano-core-api-$staged_version.pom" ]] \
    || fail 'Missing core API publication'
  [[ -f "$staged_inputs/yano-$staged_version.zip" ]] || fail 'Missing JVM distribution'
  YANO_VERSION="$staged_version"
  printf 'ORG_GRADLE_PROJECT_yanoJvmDist=%s\n' \
    "$staged_inputs/yano-$staged_version.zip" >> "$GITHUB_ENV"
  printf 'ORG_GRADLE_PROJECT_yanoRepository=file://%s\n' \
    "$staged_inputs/maven" >> "$GITHUB_ENV"
fi

if [[ -z "${YANO_VERSION:-}" ]]; then
  YANO_VERSION="$(sed -n 's/^yanoVersion=//p' "$GITHUB_WORKSPACE/gradle.properties")"
fi
: "${YANO_VERSION:?YANO_VERSION must identify a staged or released Yano build}"

{
  printf 'ORG_GRADLE_PROJECT_yanoVersion=%s\n' "$YANO_VERSION"
  printf 'ORG_GRADLE_PROJECT_internalRepository=%s\n' \
    "$GITHUB_WORKSPACE/build/internal-maven"
  printf 'DEMO_PREBUILT_ARTIFACT_ROOT=%s\n' \
    "$GITHUB_WORKSPACE/distribution/jvm/build/prepared/evidence-demo-artifacts"
  printf 'DEMO_YANO_HOME=%s\n' \
    "$GITHUB_WORKSPACE/distribution/jvm/build/prepared/evidence-demo-artifacts/yano-context/yano"
} >> "$GITHUB_ENV"

if [[ -n "${YANO_JVM_DIST_URL:-}" ]]; then
  yano_zip="$input_dir/yano-$YANO_VERSION.zip"
  curl --fail --location --retry 3 --output "$yano_zip" "$YANO_JVM_DIST_URL"
  printf 'ORG_GRADLE_PROJECT_yanoJvmDist=%s\n' "$yano_zip" >> "$GITHUB_ENV"
fi

if [[ -n "${YANO_MAVEN_REPOSITORY_URL:-}" ]]; then
  printf 'ORG_GRADLE_PROJECT_yanoRepository=%s\n' \
    "$YANO_MAVEN_REPOSITORY_URL" >> "$GITHUB_ENV"
fi
