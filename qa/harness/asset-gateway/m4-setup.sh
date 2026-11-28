#!/bin/bash
# ADR-059 M4 devnet drills, setup: a fresh three-node devnet with script anchoring v2, the asset gateway and the
# asset-payments ledger, an ADA vault and a token vault (32-byte asset name, 32-byte chain id), their reference
# scripts, the 2 x 16 shard threads, the exit-mode thread, floor top-ups and 12 registered accounts.
#
#   JAR=<dist>/yano.jar GW=<GatewayDevnet launcher> RUN=<empty dir> [SHARD_BACKEND=julc|aiken] m4-setup.sh
#
# The nodes keep running; stop them with: source m4-lib.sh; kill_tracked.
set -u
source "$(dirname "${BASH_SOURCE[0]}")/m4-lib.sh"
CHAIN=${CHAIN:-asset-payments-m4-devnet-drill-1}
BACKEND=${SHARD_BACKEND:-julc}
# The effective state genesis id (configured state.genesis-id with the framework's application profiles mixed in),
# which the anchor datum carries and the gateway scripts pin. Checked against /app-chain/state/identity below.
CHAIN_GENESIS=${CHAIN_GENESIS:-0a04d7419f326ef4da40938bfe817404f4525fbbdd2611c01f78e99b5e4182a8}
assert_ports_free $HTTP_A $N2N_A $HTTP_B $N2N_B $HTTP_C $N2N_C || exit 2

T=$(( $(date +%s) * 1000 )); echo $T > "$RUN/genesis-time-ms"; make_genesis $T
log "phase 0: L1 only on A (genesis $T)"
start_node a l1only; wait_ready $HTTP_A 90 || exit 1
HUB=$(gw address hub)
O=$(fund $A "$HUB" 20000 | jq -r '"\(.tx_hash)#\(.index)"')
gw distribute "$O" || exit 1
GW_CHAIN_GENESIS=$CHAIN_GENESIS gw derive "$CHAIN" $T "$BACKEND" || exit 1
gw publish-refs || exit 1
# A brand-new deployment has submitted nothing: a TTL file of 0 ends the builders' startup grace at once (the
# default without a file is a 12-minute wait).
for w in c:ownerfee b:relayfee; do
  kh=$(gw wallets | awk -v w="${w#*:}" '$1 == w { print substr($3, 9, 8) }')
  echo 0 > "$GW_DIR/node-${w%%:*}-pending-ttl.$CHAIN.$kh"
done
gw mint-token 1000000000000 || exit 1
gw send-token 100000000 user1 user2 user3 user4 user5 user6 || exit 1
gw send-token 100000000 user7 user8 user9 user10 user11 user12 || exit 1
stop_node a

log "phase 1: app chain on A, B, C"
start_node a app; wait_ready $HTTP_A 90 || exit 1
sleep 2; start_node b app; wait_ready $HTTP_B 90 || exit 1
start_node c app; wait_ready $HTTP_C 90 || exit 1
wait_l1_sync_b
GEN=$(curl -s $A/app-chain/state/identity | jq -r .genesisId)
check "effective state genesis id is the pinned one ($GEN)" $([ "$GEN" = "$CHAIN_GENESIS" ]; echo $?)
BS=$(curl -s -H "X-API-Key: $APIKEY" -X POST $A/app-chain/admin/anchor/bootstrap); echo "  bootstrap: $BS"
for i in $(seq 1 30); do grep -q 'Script-anchor bootstrap CONFIRMED on L1' "$RUN/node-a.log" && break; sleep 2; done
POL=$(echo "$BS" | jq -r .threadPolicyId)
check "anchor thread policy is the derived one" $(grep -q "^anchor.policy-id=$POL" "$GW_DIR/deploy.properties"; echo $?)
for c in "boot-shards 0" "boot-shards 1" "boot-exit" "floor-topup 0 2000000" "floor-topup 1 2000000"; do
  gw $c || exit 1
done
for u in $(seq 1 12); do gwn a register user$u >> "$RUN/intents.jsonl"; done
sleep 8
for id in $(jq -r .intentId "$RUN/intents.jsonl"); do gwn a receipt "$id"; done | jq -c '{action,outcome,height}' | sort | uniq -c
curl -s $A/app-chain/status | jq -c '{anchor: .anchor | {anchorVersion, lastAnchoredHeight, anchoredCount}}'
[ ${#FAILS[@]} -eq 0 ] && echo "SETUP: PASS" || { echo "SETUP: FAIL (${FAILS[*]})"; exit 1; }
