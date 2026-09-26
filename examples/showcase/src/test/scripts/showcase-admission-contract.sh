#!/usr/bin/env bash
# Exercise the real rejection helper without a running node or signing material.
set -euo pipefail
MODULE="$(cd "$(dirname "$0")/../../.." && pwd -P)"
# Load only this repository-owned function, without the launcher's top-level CLI dispatch.
eval "$(sed -n '/^expect_authenticated_map_admission_rejected() {/,/^}/p' \
  "$MODULE/src/main/showcase/showcase.sh")"
HTTP_BASE=7070
NODE=1
die() { printf '%s\n' "$*" >&2; exit 1; }
note() { :; }
curl() {
  case "$*" in
    *'-X POST'*)
      [ "$MOCK_STATUS" != transport-failure ] || return 7
      printf '%s\n%s' "$MOCK_RESPONSE" "$MOCK_STATUS";;
    *'/entries/products/'*) printf '{"proofKey":"0102"}';;
    *) die "unexpected request: $*";;
  esac
}
proof_key() {
  [ "$1" = authenticated-map-chain ] && [ "$2" = 0102 ] || die 'wrong proof coordinates'
  printf '%s' "$MOCK_PROOF"
}
MOCK_STATUS=400
MOCK_RESPONSE='{"code":"APPLICATION_REJECTED"}'
MOCK_PROOF='{"presence":"ABSENT","valueHex":null,"proofWireHex":"00"}'
expect_authenticated_map_admission_rejected 00 products invalid-sku 'product schema violation'

# An accepted submission, wrong error, leaked message id, transport failure, or
# state mutation must fail the contract rather than count as expected rejection.
for scenario in accepted wrong-code leaked-id malformed transport changed-state; do
  if (
    case "$scenario" in
      accepted) MOCK_STATUS=202; MOCK_RESPONSE='{"messageId":"01"}';;
      wrong-code) MOCK_RESPONSE='{"code":"UNKNOWN_CHAIN"}';;
      leaked-id) MOCK_RESPONSE='{"code":"APPLICATION_REJECTED","messageId":"01"}';;
      malformed) MOCK_RESPONSE='not-json';;
      transport) MOCK_STATUS=transport-failure;;
      changed-state) MOCK_PROOF='{"presence":"PRESENT","valueHex":"01","proofWireHex":"00"}';;
    esac
    expect_authenticated_map_admission_rejected 00 products invalid-sku "$scenario"
  ) >/dev/null 2>&1; then
    die "admission helper incorrectly accepted $scenario"
  fi
done
printf '%s\n' 'Showcase admission rejection contract passed'
