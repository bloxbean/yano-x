#!/bin/bash
# ADR-059 M4 devnet drills on a cluster made by m4-setup.sh and m4-deposits.sh. One drill per call:
#   JAR=... GW=... RUN=... m4-drills.sh <drill>
# Drills (see the M4 exit criteria in adr/059-cardano-asset-gateway-and-multi-asset-payments.md §8):
#   settle          withdrawals of ADA and the token by co-signed Settle; reservation -> PAID latency
#   sub-minimum     a 0.5 ADA withdrawal is rejected BELOW_MINIMUM
#   supplement      a token withdrawal whose min-ADA credit (1 ADA) is below its payout's min-UTxO (base address,
#                   32-byte asset name): the owner's builder supplements it from its fee wallet
#   throughput N    N ADA withdrawals of 1.5 ADA from user3..user12 while transfers continue; p50/p95, claims/min
#   last-liability  every account withdraws its whole token balance; the vault keeps only its floor
#   exit-reserve    stop the owner (C), reserve the owner-stopped Exit claims (ADA, a queued token claim and an
#                   Exit-only token claim whose min-ADA credit is below the batching floor)
#   race-reserve    with the owner still stopped, reserve the claims for the Settle-vs-Exit race
#   exit-verify     after exit-delay-ms (6 h): the claims are PAID by B's relayed proof Exits, the owner still down
#   race-start      once the race claims are EXIT_ELIGIBLE: start the owner (its Settle) and B's relayer together
set -u
source "$(dirname "${BASH_SOURCE[0]}")/m4-lib.sh"
APPA=http://localhost:$HTTP_A/api/v1/app-chain/
claims_from() { GW_APP=$APPA gw claims | jq "[.[] | select(.sequence >= $1)]"; }
count() { GW_APP=$APPA gw claims | jq length; }

case "${1:-}" in
  settle)
    n=$(count)
    { gwn a withdraw user1 0 10000000 0; gwn b withdraw user1 1 1000000 1500000; } | tee -a "$RUN/settle.jsonl"
    GW_APP=$APPA gw measure "$n" 2 600
    for node in a b c; do gwn $node claims | jq -c ".[] | select(.sequence >= $n) | {sequence, status, resolvedTx}"; done
    ;;
  sub-minimum)
    id=$(gwn a withdraw user1 0 500000 0 | jq -r .intentId); sleep 8; gwn a receipt "$id"
    ;;
  supplement)
    n=$(count)
    gwn b withdraw user2 1 1000000 1000000 user2 base | tee -a "$RUN/supplement.jsonl"
    GW_APP=$APPA gw measure "$n" 1 600
    tx=$(claims_from "$n" | jq -r '.[0].resolvedTx')
    curl -s "$A/txs/$tx/utxos" | jq -c '{inputs: [.inputs[] | {address, amount}], outputs: [.outputs[] | {address, amount}]}'
    ;;
  throughput)
    N=${2:-48}; n=$(count)
    (for i in 1 2 3 4 5 6; do gwn b transfer user$((i + 2)) user$((i + 3)) 0 1000000 >> "$RUN/transfers.jsonl"; done) &
    gwn a withdraw-many 0 1500000 0 "$N" user3 user4 user5 user6 user7 user8 user9 user10 user11 user12 \
      > "$RUN/throughput-$N.jsonl"
    wait
    GW_APP=$APPA gw measure "$n" "$N" 1200
    ;;
  last-liability)
    n=$(count); c=0
    for u in $(seq 1 12); do
      b=$(gwn a balance user$u 1); amt=$(echo "$b" | jq -r .amount); vl=$(echo "$b" | jq -r .vaultLovelace)
      [ "$amt" = 0 ] && [ "$vl" = 0 ] && continue
      first=$(( vl > 2000000 ? 2000000 : vl ))   # payout-lovelace-cap
      gwn a withdraw user$u 1 "$amt" "$first" >> "$RUN/last-liability.jsonl"; c=$((c + 1))
      if [ $(( vl - first )) -gt 0 ]; then gwn a withdraw user$u 1 0 $(( vl - first )) >> "$RUN/last-liability.jsonl"; c=$((c + 1)); fi
    done
    GW_APP=$APPA gw measure "$n" "$c" 1800
    gw utxos vault:1 | jq -c '[.[] | {tx: .tx_hash, amount, datum: .inline_datum}]'
    for u in $(seq 1 12); do gwn a balance user$u 1; done
    ;;
  exit-reserve)
    stop_node c; date +%s%3N > "$RUN/exit-owner-stopped-ms"
    { gwn a withdraw user2 0 20000000 0; gwn b withdraw user3 0 15000000 0; gwn a withdraw user4 1 2000000 1500000
      gwn b withdraw user2 1 1000000 900000; gwn a transfer user5 user6 0 5000000; } | tee "$RUN/exit-intents.jsonl"
    ;;
  race-reserve)
    { gwn a withdraw user5 0 25000000 0; gwn b withdraw user6 1 500000 1500000; } | tee "$RUN/race-intents.jsonl"
    ;;
  exit-verify)
    GW_APP=$APPA gw claims | jq -c '.[] | {sequence, vault, value, vaultLovelace, status, resolvedTx, reservedAtMs}'
    grep -h 'exit relayer on' "$RUN/node-b.log" | grep -v 'no claim is EXIT_ELIGIBLE\|no reserved claims' | tail -5
    ;;
  race-start)
    # B restarts with its relayer on while the owner starts: the owner's pending Settle and B's proof Exit race for
    # the same claims on L1.
    stop_node b; start_node b app; start_node c app; wait_ready $HTTP_B 90; wait_ready $HTTP_C 90
    ;;
  *) sed -n '2,20p' "$0"; exit 2 ;;
esac
