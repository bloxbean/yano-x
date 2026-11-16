#!/usr/bin/env bash
set -euo pipefail
scripts_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_directory="$(mktemp -d "${TMPDIR:-/tmp}/yano-input-tests.XXXXXX")"
commit=1111111111111111111111111111111111111111
version=0.0.0-111111111
fixture="$test_directory/fixture"
host_api_version=1.0.0-222222222
mkdir -p "$test_directory/bin" "$fixture/maven"
cp "$scripts_dir/tests/fake-gh.sh" "$test_directory/bin/gh"
chmod +x "$test_directory/bin/gh"
for artifact in yano-node-api yano-cardano-api yano-runtime yano-host-api; do
  artifact_version="$version"
  [[ "$artifact" != yano-host-api ]] || artifact_version="$host_api_version"
  path="$fixture/maven/org/yanoproject/$artifact/$artifact_version"
  mkdir -p "$path"
  printf '<project/>\n' > "$path/$artifact-$artifact_version.pom"
  printf 'fixture JAR bytes\n' > "$path/$artifact-$artifact_version.jar"
done
printf 'fixture ZIP bytes\n' > "$fixture/yano-$version.zip"
jq -n --arg commit "$commit" --arg version "$version" --arg hostApiVersion "$host_api_version" \
  '{schemaVersion:1,repository:"bloxbean/yano",commit:$commit,version:$version,
    hostApiVersion:$hostApiVersion,distribution:("yano-"+$version+".zip"),mavenDirectory:"maven"}' \
  > "$fixture/yano-inputs.json"
(
  cd "$fixture"
  find maven -type f -print0 | sort -z | xargs -0 sha256sum
  sha256sum "yano-$version.zip" yano-inputs.json
) > "$fixture/SHA256SUMS"

no_distribution="$test_directory/no-distribution-fixture"
cp -R "$fixture" "$no_distribution"
rm "$no_distribution/yano-$version.zip"
jq 'del(.distribution)' "$no_distribution/yano-inputs.json" > "$no_distribution/manifest.tmp"
mv "$no_distribution/manifest.tmp" "$no_distribution/yano-inputs.json"
(cd "$no_distribution"; find maven -type f -print0 | sort -z | xargs -0 sha256sum; sha256sum yano-inputs.json) \
  > "$no_distribution/SHA256SUMS"

run_case() {
  local name="$1" expected="$2"
  shift 2
  local workspace="$test_directory/$name" result=0
  mkdir -p "$workspace"
  printf 'yanoVersion=0.1.0-pre14\n' > "$workspace/gradle.properties"
  if [[ "$name" == committed-* || "$name" == explicit-default-override ]]; then
    mkdir -p "$workspace/.github"
    printf 'yanoVersion = %s\nnodeApiVersion = %s\nhostApiVersion = %s  \n' \
      "$version" "$version" "$host_api_version" > "$workspace/gradle.properties"
    jq -n --arg commit "$commit" \
      '{host:{runId:"123",commit:$commit}}' \
      > "$workspace/.github/ci-input-pins.json"
    if [[ "$name" == committed-drift-* ]]; then
      printf '%s = different-version\n' "${name#committed-drift-}" >> "$workspace/gradle.properties"
    elif [[ "$name" == committed-missing-* ]]; then
      grep -v "^${name#committed-missing-} " "$workspace/gradle.properties" > "$workspace/properties.tmp"
      mv "$workspace/properties.tmp" "$workspace/gradle.properties"
    elif [[ "$name" == explicit-default-override ]]; then
      printf 'yanoVersion=another-host\nnodeApiVersion=another-node\nhostApiVersion=another-api\n' \
        > "$workspace/gradle.properties"
    elif [[ "$name" == committed-no-properties ]]; then
      rm "$workspace/gradle.properties"
    fi
  fi
  if env PATH="$test_directory/bin:$PATH" GITHUB_WORKSPACE="$workspace" GITHUB_ENV="$workspace/env" \
      TEST_INPUT_FIXTURE="$fixture" TEST_INPUT_VERSION="$version" YANO_VERSION='' \
      ORG_GRADLE_PROJECT_nodeApiVersion='' ORG_GRADLE_PROJECT_hostApiVersion='' \
      YANO_INPUTS_RUN_ID=123 YANO_INPUTS_COMMIT="$commit" \
      YANO_JVM_DIST_URL='' YANO_MAVEN_REPOSITORY_URL='' "$@" \
      bash "$scripts_dir/prepare-yano-inputs.sh" > "$workspace/output.log" 2>&1; then
    result=0
  else
    result=$?
  fi
  if [[ "$expected" == pass && "$result" != 0 || "$expected" == fail && "$result" == 0 ]]; then
    echo "FAIL: $name (see $workspace/output.log)" >&2
    exit 1
  fi
  echo "PASS: $name"
}

run_case valid pass
run_case 'space path' pass
run_case committed-pin pass YANO_INPUTS_RUN_ID='' YANO_INPUTS_COMMIT=''
for property in yanoVersion nodeApiVersion hostApiVersion; do
  for failure in drift missing; do
    name="committed-$failure-$property"
    run_case "$name" fail YANO_INPUTS_RUN_ID='' YANO_INPUTS_COMMIT=''
    grep -F "Committed host pin differs from $property" "$test_directory/$name/output.log" >/dev/null
    [[ ! -e "$test_directory/$name/env" ]]
  done
done
run_case committed-no-properties fail YANO_INPUTS_RUN_ID='' YANO_INPUTS_COMMIT=''
grep -F 'Committed host pin requires gradle.properties' "$test_directory/committed-no-properties/output.log" >/dev/null
[[ ! -e "$test_directory/committed-no-properties/env" ]]
run_case explicit-default-override pass
run_case host-requires-distribution fail TEST_INPUT_FIXTURE="$no_distribution"
for field in version hostApiVersion; do
  run_case "invalid-$field" fail TEST_INVALID_VERSION_FIELD="$field"
  [[ ! -e "$test_directory/invalid-$field/env" ]]
done
grep -F 'Invalid staged version' "$test_directory/invalid-version/output.log" >/dev/null
grep -F 'Missing or invalid Host API version' "$test_directory/invalid-hostApiVersion/output.log" >/dev/null
run_case wrong-node-api-version fail ORG_GRADLE_PROJECT_nodeApiVersion=0.1.0-other
run_case wrong-host-api-version fail ORG_GRADLE_PROJECT_hostApiVersion=1.0.0-other
run_case matching-api-overrides pass ORG_GRADLE_PROJECT_nodeApiVersion="$version" \
  ORG_GRADLE_PROJECT_hostApiVersion="$host_api_version"
for artifact in yano-node-api yano-cardano-api yano-runtime yano-host-api; do
  run_case "missing-$artifact" fail TEST_DROP_PUBLICATION="$artifact"
done
run_case unlisted-node-api fail TEST_UNLISTED_PUBLICATION=yano-node-api
run_case missing-host-api-version fail TEST_MISSING_HOST_API_VERSION=1
run_case wrong-commit fail TEST_RUN_COMMIT=2222222222222222222222222222222222222222
run_case failed-run fail TEST_RUN_CONCLUSION=failure
run_case wrong-repository fail TEST_RUN_REPOSITORY=someone/else
run_case wrong-workflow fail TEST_RUN_PATH=.github/workflows/integration.yml
run_case wrong-version fail YANO_VERSION=0.1.0-other
run_case missing-pin fail YANO_INPUTS_COMMIT=''
run_case mixed-inputs fail YANO_JVM_DIST_URL=https://example.invalid/yano.zip
run_case tampered-zip fail TEST_TAMPER_ZIP=1
run_case unsafe-checksum fail TEST_UNSAFE_CHECKSUM=1
run_case ordinary-default pass YANO_INPUTS_RUN_ID='' YANO_INPUTS_COMMIT=''
grep -Fx 'ORG_GRADLE_PROJECT_yanoVersion=0.1.0-pre14' "$test_directory/ordinary-default/env" >/dev/null
if grep -Eq '^ORG_GRADLE_PROJECT_(yanoJvmDist|yanoRepository|useMavenLocal)=' \
    "$test_directory/ordinary-default/env"; then
  echo 'FAIL: released default must not enable staged or local inputs' >&2
  exit 1
fi
grep -F "ORG_GRADLE_PROJECT_yanoVersion=$version" "$test_directory/valid/env" >/dev/null
grep -F 'ORG_GRADLE_PROJECT_yanoRepository=file://' "$test_directory/valid/env" >/dev/null
grep -Fx "ORG_GRADLE_PROJECT_nodeApiVersion=$version" "$test_directory/valid/env" >/dev/null
grep -Fx "ORG_GRADLE_PROJECT_hostApiVersion=$host_api_version" "$test_directory/valid/env" >/dev/null
grep -F '%20' "$test_directory/space path/env" >/dev/null
echo "Input preparation fixtures retained at $test_directory"

# Exercise real Gradle precedence without configuring this repository or resolving dependencies.
precedence="$test_directory/gradle-precedence"
mkdir -p "$precedence"
printf "rootProject.name = 'input-precedence'\n" > "$precedence/settings.gradle"
keys=(yanoVersion nodeApiVersion hostApiVersion)
for key in "${keys[@]}"; do printf '%s=checked-in-default\n' "$key"; done > "$precedence/gradle.properties"
cat > "$precedence/build.gradle" <<'GRADLE'
tasks.register('verifyInputs') {
    doLast {
        ['yanoVersion', 'nodeApiVersion', 'hostApiVersion'].each { key ->
            assert providers.gradleProperty(key).get() == providers.gradleProperty("expected_${key}").get()
        }
    }
}
GRADLE
(
  # GITHUB_ENV is data, not shell code (paths may contain spaces).
  while IFS='=' read -r key value; do export "$key=$value"; done < "$test_directory/valid/env"
  prepared_env="$test_directory/valid/env"
  unset GRADLE_OPTS
  for mode in defaults prepared command-line; do
    arguments=()
    for key in "${keys[@]}"; do
      variable="ORG_GRADLE_PROJECT_$key"
      expected="$(sed -n "s/^$variable=//p" "$prepared_env")"
      if [[ "$mode" == defaults ]]; then
        expected=checked-in-default
        unset "$variable"
      else
        export "$variable=$expected"
      fi
      if [[ "$mode" == command-line ]]; then
        expected=explicit-override
        arguments+=("-P$key=$expected")
      fi
      arguments+=("-Pexpected_$key=$expected")
    done
    if ! "$scripts_dir/../../gradlew" -p "$precedence" --offline --no-daemon --no-watch-fs --no-parallel \
        --console=plain verifyInputs "${arguments[@]}" > "$precedence/$mode.log" 2>&1; then
      echo "FAIL: Gradle $mode precedence (see $precedence/$mode.log)" >&2
      exit 1
    fi
    echo "PASS: Gradle $mode precedence"
  done
)
