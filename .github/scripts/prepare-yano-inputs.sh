#!/usr/bin/env bash
set -euo pipefail

: "${YANO_VERSION:?YANO_VERSION must identify a staged or released Yano build}"
: "${YANO_JVM_DIST_URL:?YANO_JVM_DIST_URL must point to the matching Yano JVM ZIP}"
: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
: "${GITHUB_ENV:?GITHUB_ENV is required}"

input_dir="$GITHUB_WORKSPACE/build/ci-inputs"
yano_zip="$input_dir/yano-$YANO_VERSION.zip"
mkdir -p "$input_dir"
curl --fail --location --retry 3 --output "$yano_zip" "$YANO_JVM_DIST_URL"

{
  printf 'ORG_GRADLE_PROJECT_yanoVersion=%s\n' "$YANO_VERSION"
  printf 'ORG_GRADLE_PROJECT_yanoJvmDist=%s\n' "$yano_zip"
  printf 'ORG_GRADLE_PROJECT_internalRepository=%s\n' \
    "$GITHUB_WORKSPACE/build/internal-maven"
  printf 'DEMO_PREBUILT_ARTIFACT_ROOT=%s\n' \
    "$GITHUB_WORKSPACE/distribution/jvm/build/prepared/evidence-demo-artifacts"
  printf 'DEMO_YANO_HOME=%s\n' \
    "$GITHUB_WORKSPACE/distribution/jvm/build/prepared/evidence-demo-artifacts/yano-context/yano"
} >> "$GITHUB_ENV"

if [[ -n "${YANO_MAVEN_REPOSITORY_URL:-}" ]]; then
  printf 'ORG_GRADLE_PROJECT_yanoRepository=%s\n' \
    "$YANO_MAVEN_REPOSITORY_URL" >> "$GITHUB_ENV"
fi
