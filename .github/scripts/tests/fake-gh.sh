#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == api ]]; then
  jq -n --arg commit "${TEST_RUN_COMMIT:-$YANO_INPUTS_COMMIT}" \
    --arg conclusion "${TEST_RUN_CONCLUSION:-success}" \
    --arg repository "${TEST_RUN_REPOSITORY:-bloxbean/yano}" \
    --arg path "${TEST_RUN_PATH:-.github/workflows/stage-consumer-inputs.yml}" \
    '{head_sha:$commit,status:"completed",conclusion:$conclusion,event:"workflow_dispatch",
      path:$path,repository:{full_name:$repository}}'
elif [[ "${1:-}" == run && "${2:-}" == download ]]; then
  destination=''
  while (($#)); do
    if [[ "$1" == --dir ]]; then destination="$2"; break; fi
    shift
  done
  [[ -n "$destination" ]]
  cp -R "$TEST_INPUT_FIXTURE/." "$destination/"
  if [[ -n "${TEST_DROP_PUBLICATION:-}" ]]; then
    rm -r "$destination/maven/org/yanoproject/$TEST_DROP_PUBLICATION"
    grep -v "/$TEST_DROP_PUBLICATION/" "$destination/SHA256SUMS" > "$destination/SHA256SUMS.tmp"
    mv "$destination/SHA256SUMS.tmp" "$destination/SHA256SUMS"
  fi
  if [[ -n "${TEST_UNLISTED_PUBLICATION:-}" ]]; then
    grep -v "/$TEST_UNLISTED_PUBLICATION/" "$destination/SHA256SUMS" > "$destination/SHA256SUMS.tmp"
    mv "$destination/SHA256SUMS.tmp" "$destination/SHA256SUMS"
  fi
  if [[ -n "${TEST_INVALID_VERSION_FIELD:-}" ]]; then
    jq --arg field "$TEST_INVALID_VERSION_FIELD" '.[$field]="1 -Dunexpected=value" | if $field == "version" and has("distribution") then .distribution=("yano-"+.version+".zip") else . end' \
      "$destination/yano-inputs.json" > "$destination/manifest.tmp"
    mv "$destination/manifest.tmp" "$destination/yano-inputs.json"
    grep -v '  yano-inputs.json$' "$destination/SHA256SUMS" > "$destination/SHA256SUMS.tmp"
    (cd "$destination" && sha256sum yano-inputs.json) >> "$destination/SHA256SUMS.tmp"
    mv "$destination/SHA256SUMS.tmp" "$destination/SHA256SUMS"
  fi
  if [[ "${TEST_TAMPER_ZIP:-0}" == 1 ]]; then
    printf 'tampered' >> "$destination/yano-$TEST_INPUT_VERSION.zip"
  fi
  if [[ "${TEST_UNSAFE_CHECKSUM:-0}" == 1 ]]; then
    printf '%064d  ../outside\n' 0 >> "$destination/SHA256SUMS"
  fi
else
  echo 'Unexpected fake gh invocation' >&2
  exit 1
fi
