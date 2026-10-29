#!/usr/bin/env bash
# ADR-056 runtime gate on an extracted Yano X JVM archive: a two-operator application is drafted, carded,
# sealed, joined and started with the packaged `yano.sh appchain` verbs; a message finalizes with a proof and
# evidence; a member restarts and catches up; a third operator joins later through `approve`; the lead
# anchors on devnet; and no private key reaches a log or a process listing.
set -euo pipefail

archive="${1:?Usage: final-distribution-runtime-acceptance.sh YANO_ZIP}"
http_base="${YANO_ACCEPTANCE_HTTP_BASE:-18080}"
server_base="${YANO_ACCEPTANCE_SERVER_BASE:-23337}"
work="$(mktemp -d "${TMPDIR:-/tmp}/yano-appchain-dx.XXXXXX")"
coordinator="$work/coordinator"
operators=(alice bob carol)

node_dir() { printf '%s/%s' "$work" "$1"; }
cli() { "$YANO_HOME/yano.sh" appchain "$@"; }

cleanup() {
  for operator in "${operators[@]}"; do
    [ -f "$(node_dir "$operator")/node.json" ] && cli stop --node-dir "$(node_dir "$operator")" >/dev/null 2>&1 || true
  done
  rm -rf "$work"
}

failure() {
  code=$?
  trap - EXIT
  for log in "$work"/*/logs/node.log; do
    [ -f "$log" ] && { echo "--- $log" >&2; tail -80 "$log" >&2; }
  done
  cleanup
  exit "$code"
}
trap failure EXIT

unzip -q "$archive" -d "$work/distribution"
YANO_HOME="$(find "$work/distribution" -mindepth 1 -maxdepth 1 -type d | head -1)"
export YANO_HOME
mkdir -p "$coordinator"

cli new acceptance --network devnet --operators alice,bob --chain acceptance-chain=audit-log \
  --output "$coordinator" --yes >/dev/null
index=0
for operator in alice bob carol; do
  source="$coordinator/acceptance.draft.yaml"
  [ "$operator" = carol ] && source="$coordinator/acceptance.chain"
  if [ "$operator" = carol ]; then
    cli seal "$coordinator/acceptance.draft.yaml" >/dev/null
  fi
  cli request "$source" --name "$operator" --host 127.0.0.1 \
    --node-port "$((server_base + index))" --http-port "$((http_base + index))" \
    --node-dir "$(node_dir "$operator")" --output "$coordinator" --yes >/dev/null
  index=$((index + 1))
done
fingerprint="$(cli inspect "$coordinator/acceptance.chain" --json | jq -er '.displayFingerprint')"
for operator in alice bob; do
  cli join "$coordinator/acceptance.chain" --expect-fingerprint "$fingerprint" \
    --node-dir "$(node_dir "$operator")" >/dev/null
done
# A file that does not match the compared fingerprint is refused.
! cli join "$coordinator/acceptance.chain" --expect-fingerprint "0000-0000-0000-0000-0000-0000-0000-0000" \
  --node-dir "$(node_dir alice)" >/dev/null 2>&1

cli start --node-dir "$(node_dir alice)" >/dev/null
cli start --node-dir "$(node_dir bob)" >/dev/null
cli status --node-dir "$(node_dir bob)" --wait live --timeout 180 --json \
  | jq -e '.stage == "Live" and (.members | length) == 2 and all(.chains[]; .agree)' >/dev/null

api0="http://127.0.0.1:${http_base}/api/v1/app-chain/chains/acceptance-chain"
api1="http://127.0.0.1:$((http_base + 1))/api/v1/app-chain/chains/acceptance-chain"
key0="$(cat "$(node_dir alice)/secrets/api.key")"
curl -fsS -H "X-API-Key: $key0" "$api0/identity" \
  | jq -e '.schemaVersion == "v1" and (.consensusProfileDigest | length) == 64
      and (.pluginCatalogFingerprint | startswith("sha256:"))' >/dev/null
submitted="$(curl -fsS -H "X-API-Key: $key0" -H 'content-type: application/json' \
  -d '{"topic":"acceptance","body":"packaged-runtime"}' "$api0/messages")"
message_id="$(jq -er '.messageId' <<<"$submitted")"

for _ in $(seq 1 60); do
  tip0="$(curl -fsS "$api0/tip")"
  tip1="$(curl -fsS "$api1/tip")"
  height0="$(jq -r '.height' <<<"$tip0")"
  height1="$(jq -r '.height' <<<"$tip1")"
  root0="$(jq -r '.stateRoot' <<<"$tip0")"
  root1="$(jq -r '.stateRoot' <<<"$tip1")"
  [ "$height0" -gt 0 ] && [ "$height0" = "$height1" ] \
    && [ "$root0" = "$root1" ] && break
  sleep 1
done
[ "$height0" -gt 0 ] && [ "$height0" = "$height1" ] && [ "$root0" = "$root1" ]
curl -fsS "$api0/blocks/$height0" \
  | jq -e --arg id "$message_id" --arg root "$root0" \
      '.stateRoot == $root and .certSignatures == 2 and .messages[0].messageId == $id' >/dev/null
curl -fsS "$api0/messages/$message_id" \
  | jq -e --arg id "$message_id" '.messageId == $id and .height > 0' >/dev/null
curl -fsS "$api0/state/proof/$message_id" \
  | jq -e --arg id "$message_id" --arg root "$root0" \
      '.key == $id and .stateRoot == $root and .proofWireHex != ""' >/dev/null
curl -fsS "$api0/evidence/$message_id" \
  | jq -e --arg id "$message_id" \
      '.messageId == $id and (.blocksCbor | length) > 0 and .threshold == 2' >/dev/null

# A member restarts and catches up with the same tip and root.
cli stop --node-dir "$(node_dir bob)" >/dev/null
cli start --node-dir "$(node_dir bob)" >/dev/null
for _ in $(seq 1 60); do
  restarted="$(curl -fsS "$api1/tip")"
  [ "$(jq -r '.height' <<<"$restarted")" = "$height0" ] \
    && [ "$(jq -r '.stateRoot' <<<"$restarted")" = "$root0" ] && break
  sleep 1
done
[ "$(jq -r '.height' <<<"$restarted")" = "$height0" ]
[ "$(jq -r '.stateRoot' <<<"$restarted")" = "$root0" ]

# A third operator joins later: carol joins and starts, the founders approve her card.
cli join "$coordinator/acceptance.chain" --expect-fingerprint "$fingerprint" --node-dir "$(node_dir carol)" >/dev/null
cli start --node-dir "$(node_dir carol)" >/dev/null
cli approve "$coordinator/carol.member" --node-dir "$(node_dir alice)" --heartbeat --timeout 300 >/dev/null &
approving=$!
cli approve "$coordinator/carol.member" --node-dir "$(node_dir bob)" --heartbeat --timeout 300 >/dev/null
wait "$approving"
cli status --node-dir "$(node_dir carol)" --wait live --timeout 180 --json \
  | jq -e '.stage == "Live" and (.members | length) == 3 and .chains[0].members == 3' >/dev/null

# The lead anchors on devnet with one command; the first anchor follows the next blocks.
cli anchor --node-dir "$(node_dir alice)" --yes --timeout 240 >/dev/null
for i in $(seq 1 12); do
  curl -fsS -H "X-API-Key: $key0" -H 'content-type: application/json' \
    -d "{\"topic\":\"acceptance\",\"body\":\"anchor-traffic-$i\"}" "$api0/messages" >/dev/null
  sleep 1
done
for _ in $(seq 1 60); do
  anchored="$(curl -fsS "$api0/status" | jq -r '.anchor.anchoredCount // 0')"
  [ "$anchored" -gt 0 ] && break
  sleep 2
done
[ "$anchored" -gt 0 ]

# No private key or API key reaches a log or a process listing.
secrets=()
for operator in "${operators[@]}"; do
  for file in "$(node_dir "$operator")"/secrets/*.key; do secrets+=("$(cat "$file")"); done
done
pattern="$(IFS='|'; printf '%s' "${secrets[*]}")"
! grep -ERq "$pattern" "$work"/*/logs
for operator in "${operators[@]}"; do
  pid="$(cat "$(node_dir "$operator")/run/node.pid")"
  ! ps -p "$pid" -o command= | grep -Eq "$pattern"
done

for operator in "${operators[@]}"; do cli stop --node-dir "$(node_dir "$operator")" >/dev/null; done
for operator in "${operators[@]}"; do [ ! -e "$(node_dir "$operator")/run/node.pid" ]; done
trap - EXIT
cleanup
echo "PASS: final distribution setup ceremony, finality, proof, restart, late member, anchoring and secret gates"
