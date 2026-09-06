#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == api ]]; then
  jq -n --arg commit "${TEST_RUN_COMMIT:-$YANO_INPUTS_COMMIT}" \
    --arg conclusion "${TEST_RUN_CONCLUSION:-success}" \
    --arg repository "${TEST_RUN_REPOSITORY:-bloxbean/yano}" \
    '{head_sha:$commit,status:"completed",conclusion:$conclusion,event:"workflow_dispatch",
      path:".github/workflows/integration.yml",repository:{full_name:$repository}}'
elif [[ "${1:-}" == run && "${2:-}" == download ]]; then
  destination=''
  while (($#)); do
    if [[ "$1" == --dir ]]; then destination="$2"; break; fi
    shift
  done
  [[ -n "$destination" ]]
  cp -R "$TEST_INPUT_FIXTURE/." "$destination/"
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
