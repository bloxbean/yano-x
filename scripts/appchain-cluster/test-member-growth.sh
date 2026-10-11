#!/usr/bin/env bash
# Governed member growth must leave application records alone. Starts the stock
# three-member cluster, writes a registry key and a pending approval under the
# name the launcher once used to advance idle chains, joins a fourth member
# (which raises every threshold 2 -> 3 and advances each idle chain to the
# activation height), and checks that both records are byte-for-byte unchanged.
# Extra arguments go to "cluster.sh start", e.g. --http-base 36770 --server-base 36370.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLUSTER="$SCRIPT_DIR/cluster.sh"
WORK="$(mktemp -d /tmp/yano-cluster-growth-test.XXXXXX)"
DATA="$WORK/cluster"
NAME=cluster.epoch-advance

die() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

cleanup() {
  "$CLUSTER" stop --data-dir "$DATA" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

hex() { printf '%s' "$1" | od -An -v -tx1 | tr -d ' \n'; }

# "<presence> <valueHex>" of one state key, read through node 0.
proof() {
  curl -fsS "http://localhost:$HTTP/api/v1/app-chain/chains/$1/state/proof/$(hex "$2")" \
    | jq -r '"\(.presence) \(.valueHex // "")"'
}

wait_present() {
  local cid="$1" key="$2" state="" attempt
  for attempt in $(seq 1 60); do
    state="$(proof "$cid" "$key" 2>/dev/null)" || state=""
    case "$state" in PRESENT\ ?*) printf '%s' "$state"; return 0;; esac
    sleep 1
  done
  die "$cid key $key did not become PRESENT (last: ${state:-no response})"
}

CLUSTER_WARMUP=0 "$CLUSTER" start 3 --data-dir "$DATA" "$@" >"$WORK/start.log" 2>&1 \
  || { cat "$WORK/start.log"; die "three-member start failed"; }
HTTP="$(sed -n 's/^HTTP_BASE=//p' "$DATA/cluster.env")"
[ -n "$HTTP" ] || die "cluster.env does not record HTTP_BASE"

# A registry key owned by node 0, the member the launcher submits through.
"$CLUSTER" kv registry-chain set "$NAME" customer-record --node 0 --data-dir "$DATA" \
  >"$WORK/kv.log" 2>&1 || { cat "$WORK/kv.log"; die "registry write failed"; }
# A pending two-approval item: [PROPOSE, itemId, payload, required, deadline].
payload=customer-approval
propose="8500$(printf '%02x' $((0x60 + ${#NAME})))$(hex "$NAME")"
propose+="$(printf '%02x' $((0x40 + ${#payload})))$(hex "$payload")0200"
curl -fsS -X POST "http://localhost:$HTTP/api/v1/app-chain/chains/effects-chain/messages" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg h "$propose" '{topic:"approvals.command.v1",bodyHex:$h}')" >/dev/null \
  || die "approval proposal was not accepted"

registry_before="$(wait_present registry-chain "$NAME")"
item_before="$(wait_present effects-chain "i/$NAME")"

"$CLUSTER" node join 3 --data-dir "$DATA" >"$WORK/join.log" 2>&1 \
  || { cat "$WORK/join.log"; die "governed node join failed"; }
for cid in orders-chain registry-chain effects-chain; do
  grep -q "raising '$cid' threshold 2 -> 3 first" "$WORK/join.log" \
    || die "join did not raise the $cid threshold, so it did not advance the chain"
done
grep -q 'node 3 joined and caught up with member key' "$WORK/join.log" \
  || die "governed join did not report the joined identity"

[ "$(proof registry-chain "$NAME")" = "$registry_before" ] \
  || die "member growth changed the registry record: $(proof registry-chain "$NAME")"
[ "$(proof effects-chain "i/$NAME")" = "$item_before" ] \
  || die "member growth changed the pending approval: $(proof effects-chain "i/$NAME")"

"$CLUSTER" status --data-dir "$DATA" >"$WORK/status.log" 2>&1 || die "joined-cluster status failed"
grep -q 'node 3  \[ready\]' "$WORK/status.log" || die "joined node is not ready"
[ "$(grep -c 'AGREED' "$WORK/status.log")" -eq 3 ] \
  || die "the four members did not agree on all three chain roots"

printf 'PASS: member growth kept %s registry and approval records unchanged\n' "$NAME"
