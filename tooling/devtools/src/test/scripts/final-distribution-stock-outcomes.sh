#!/usr/bin/env bash
# The stable stock recipes, set up from the final archive with `yano.sh appchain try` (three operators on
# this machine) and exercised through the nodes' APIs: an audit-log record with its message proof, an
# owned-registry value with its state proof, and an approval-workflow decision approved by two members.
set -euo pipefail

archive="${1:?Usage: final-distribution-stock-outcomes.sh YANO_ZIP}"
work="$(mktemp -d "${TMPDIR:-/tmp}/yano-stock-outcomes.XXXXXX")"

cleanup() {
  if [ -n "${yano_home:-}" ] && [ -d "$work/appchain-try" ]; then
    (cd "$work" && "$yano_home/yano.sh" appchain try --clean >/dev/null 2>&1) || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT
trap 'echo "FAIL: line $LINENO: $BASH_COMMAND" >&2' ERR

unzip -q "$archive" -d "$work/distribution"
yano_home="$(find "$work/distribution" -mindepth 1 -maxdepth 1 -type d | head -1)"
codec="$yano_home/examples/showcase/tools/showcase_codec.py"

(cd "$work" && "$yano_home/yano.sh" appchain try --chain orders=audit-log --chain registry=owned-registry \
  --chain approvals=approval-workflow) >"$work/try.log" 2>&1 || { tail -40 "$work/try.log" >&2; exit 1; }
api() { printf 'http://127.0.0.1:%s/api/v1/app-chain/chains' \
  "$(jq -r .httpPort "$work/appchain-try/$1/yano-node/node.json")"; }
submit() {
  curl -fsS -H 'content-type: application/json' \
    -d "$(jq -nc --arg topic "$3" --arg body "$4" '{topic:$topic,bodyHex:$body}')" "$(api "$1")/$2/messages" \
    | jq -er .messageId
}
final() {
  local node="$1" chain="$2" id="$3"
  for _ in $(seq 1 60); do
    curl -fsS "$(api "$node")/$chain/messages/$id" 2>/dev/null | jq -e '.height > 0' >/dev/null && return 0
    sleep 1
  done
  echo "message $id on $chain was not finalized" >&2
  return 1
}

# recipe-audit-log: threshold-finalized ordered record and message proof. `try` already finalized one
# message on the orders chain; this one goes through another member's node.
audit="$(curl -fsS -H 'content-type: application/json' \
  -d '{"topic":"acceptance","body":"packaged-audit-outcome"}' "$(api carol)/orders/messages")"
audit_id="$(jq -er '.messageId' <<<"$audit")"
final carol orders "$audit_id"
curl -fsS "$(api alice)/orders/state/proof/$audit_id" \
  | jq -e --arg id "$audit_id" '.key == $id and .proofWireHex != ""' >/dev/null

# recipe-owned-registry: first writer owns a committed value with a state proof.
put="$(submit bob registry kv.command.v1 "$(python3 "$codec" kv put supplier-42 active)")"
final bob registry "$put"
registry_key="$(printf 'supplier-42' | od -An -tx1 | tr -d ' \n')"
curl -fsS "$(api alice)/registry/state/proof/$registry_key" \
  | jq -e --arg key "$registry_key" '.key == $key and .valueHex != null and .proofWireHex != ""' >/dev/null

# recipe-approval-workflow: alice proposes, bob and carol approve from their own nodes, and the proposal's
# committed state reaches APPROVED.
proposal="$(submit alice approvals approvals.command.v1 \
  "$(python3 "$codec" approval propose release-42 '{"action":"release"}' --required 2)")"
final alice approvals "$proposal"
for member in bob carol; do
  final "$member" approvals "$(submit "$member" approvals approvals.command.v1 \
    "$(python3 "$codec" approval approve release-42)")"
done
state="$(curl -fsS "$(api alice)/approvals/state/proof/$(python3 "$codec" state-key approval release-42)")"
# The stored item is a CBOR array whose first element is its status; 1 is APPROVED.
jq -e '.valueHex != null and .proofWireHex != "" and (.valueHex | .[2:4]) == "01"' <<<"$state" >/dev/null

echo "PASS: final distribution stable recipe outcomes (audit, registry, approval)"
