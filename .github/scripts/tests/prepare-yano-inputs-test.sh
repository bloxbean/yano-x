#!/usr/bin/env bash
set -euo pipefail
scripts_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_directory="$(mktemp -d "${TMPDIR:-/tmp}/yano-input-tests.XXXXXX")"
commit=1111111111111111111111111111111111111111
version=0.0.0-111111111
fixture="$test_directory/fixture"
mkdir -p "$test_directory/bin" "$fixture/maven/com/bloxbean/cardano/yano-core-api/$version"
cp "$scripts_dir/tests/fake-gh.sh" "$test_directory/bin/gh"
chmod +x "$test_directory/bin/gh"
printf '<project/>\n' > "$fixture/maven/com/bloxbean/cardano/yano-core-api/$version/yano-core-api-$version.pom"
printf 'fixture ZIP bytes\n' > "$fixture/yano-$version.zip"
jq -n --arg commit "$commit" --arg version "$version" \
  '{schemaVersion:1,repository:"bloxbean/yano",commit:$commit,version:$version,
    distribution:("yano-"+$version+".zip"),mavenDirectory:"maven"}' > "$fixture/yano-inputs.json"
(
  cd "$fixture"
  sha256sum "maven/com/bloxbean/cardano/yano-core-api/$version/yano-core-api-$version.pom" \
    "yano-$version.zip" yano-inputs.json
) > "$fixture/SHA256SUMS"

run_case() {
  local name="$1" expected="$2"
  shift 2
  local workspace="$test_directory/$name" result=0
  mkdir -p "$workspace"
  printf 'yanoVersion=0.1.0-pre14\n' > "$workspace/gradle.properties"
  if env PATH="$test_directory/bin:$PATH" GITHUB_WORKSPACE="$workspace" GITHUB_ENV="$workspace/env" \
      TEST_INPUT_FIXTURE="$fixture" TEST_INPUT_VERSION="$version" YANO_VERSION='' \
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
run_case wrong-commit fail TEST_RUN_COMMIT=2222222222222222222222222222222222222222
run_case failed-run fail TEST_RUN_CONCLUSION=failure
run_case wrong-repository fail TEST_RUN_REPOSITORY=someone/else
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
echo "Input preparation fixtures retained at $test_directory"
