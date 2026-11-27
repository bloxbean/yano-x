#!/bin/bash
# ADR-059 M3 deferred devnet criterion and M4 step 2: real deposits of ADA and the token are observed, credited once
# on every member, and accepted (co-signed by the members) into spendable vault custody.
#   JAR=... GW=... RUN=<the m4-setup.sh run> m4-deposits.sh
set -u
source "$(dirname "${BASH_SOURCE[0]}")/m4-lib.sh"
: > "$RUN/deposits.jsonl"
for u in $(seq 1 12); do
  gw deposit user$u 0 100000000 | grep '^{' >> "$RUN/deposits.jsonl"
  gw deposit user$u 1 5000000 3000000 | grep '^{' >> "$RUN/deposits.jsonl"
done
check "24 deposits submitted" $([ "$(wc -l < "$RUN/deposits.jsonl")" -eq 24 ]; echo $?)
sleep 10
n=0; for line in $(jq -c . "$RUN/deposits.jsonl"); do
  v=$(( n % 2 )); tx=$(echo "$line" | jq -r .tx)
  for node in a b c; do gwn $node deposit-record $v "$tx" 0 | jq -c --arg n $node '{node: $n, status, height}'; done
  n=$(( n + 1 ))
done > "$RUN/deposit-records.jsonl"
check "every deposit CREDITED on every member" $(jq -se 'length == 72 and all(.status == "CREDITED")' \
  "$RUN/deposit-records.jsonl" >/dev/null; echo $?)
for node in a b c; do echo "  $node user1: $(gwn $node balance user1 0 | jq -c .) $(gwn $node balance user1 1 | jq -c .)"; done
log "waiting for Accept (owner C, co-signed)"
for i in $(seq 1 60); do
  left=$(for v in 0 1; do gw utxos vault:$v | jq '[.[] | select(.inline_datum | startswith("d879"))] | length'; done | paste -sd+ | bc)
  [ "$left" = 0 ] && break; sleep 5
done
check "no deposit-shaped output left at either vault (all accepted)" $([ "$left" = 0 ]; echo $?)
for v in 0 1; do echo "  vault $v: $(gw utxos vault:$v | jq -c '[.[] | {tx: .tx_hash[0:16], lovelace: .amount[0].quantity, datum: .inline_datum[0:8]}]')"; done
grep -h 'acceptor on' "$RUN/node-c.log" "$RUN/node-b.log" 2>/dev/null | grep -o 'accepted ([a-z-]*) [0-9]* deposits in [0-9a-f]*' | sort | uniq
[ ${#FAILS[@]} -eq 0 ] && echo "DEPOSITS: PASS" || echo "DEPOSITS: FAIL (${FAILS[*]})"
