#!/usr/bin/env bash
set -euo pipefail
: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"
: "${YANO_VERIFIED_INPUTS:?Prepare pinned foundation inputs first}"
: "${ORG_GRADLE_PROJECT_nodeApiVersion:?Missing node API pin}"
: "${ORG_GRADLE_PROJECT_hostApiVersion:?Missing Host API pin}"
cd "$GITHUB_WORKSPACE"
[[ -z "$(git status --porcelain)" ]] || { echo 'Framework inputs require committed clean sources' >&2; exit 1; }
commit="$(git rev-parse HEAD)"
[[ "$commit" == "${GITHUB_SHA:?GITHUB_SHA is required}" ]]
base_version="$(sed -n 's/^version[[:space:]]*=[[:space:]]*//p' gradle.properties)"
[[ "$base_version" =~ ^[0-9A-Za-z.-]+$ ]]
version="${base_version%-SNAPSHOT}-${commit:0:9}"
directory="$GITHUB_WORKSPACE/build/framework-inputs/$commit"
[[ ! -e "$directory" ]] || { echo 'Refusing to overwrite staged framework inputs' >&2; exit 1; }
mkdir -p "$directory/maven"
# Include only already verified foundation publications, never sibling sources.
cp -R "$YANO_VERIFIED_INPUTS/maven/." "$directory/maven/"
modules=()
while IFS= read -r module; do modules+=("$module"); done < <(jq -r '.artifacts[] | select(.modulePath | startswith(":framework:")
  or . == ":sdk:proof-contracts") | .modulePath' config/artifacts-v1.json)
((${#modules[@]} > 0))
tasks=()
for module in "${modules[@]}"; do tasks+=("$module:publishAllPublicationsToInternalRepository"); done
./gradlew frameworkCheck "${tasks[@]}" "-Pversion=$version" \
  "-PinternalRepository=$directory/maven" -PskipSigning=true --no-parallel
jq -n --arg commit "$commit" --arg version "$version" \
  --arg nodeApiVersion "$ORG_GRADLE_PROJECT_nodeApiVersion" \
  --arg hostApiVersion "$ORG_GRADLE_PROJECT_hostApiVersion" \
  --arg foundationCommit "$YANO_INPUTS_COMMIT" --arg foundationRun "$YANO_INPUTS_RUN_ID" \
  '{schemaVersion:1,repository:"bloxbean/yano-x",scope:"framework",commit:$commit,version:$version,
    nodeApiVersion:$nodeApiVersion,hostApiVersion:$hostApiVersion,mavenDirectory:"maven",
    foundation:{repository:"bloxbean/yano",commit:$foundationCommit,runId:$foundationRun}}' \
  > "$directory/framework-inputs.json"
(
  cd "$directory"
  find maven -type f -print0 | sort -z | xargs -0 sha256sum
  sha256sum framework-inputs.json
) > "$directory/SHA256SUMS"
printf 'directory=%s\nversion=%s\n' "$directory" "$version" >> "$GITHUB_OUTPUT"
