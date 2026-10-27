#!/usr/bin/env bash
# showcase.sh against a stand-in yano.sh (support/fake_yano.py): the light profile sets every node up with the
# app-chain setup commands, carries its options into the drafts and each node's config/local.yaml, retains its
# choices, and delegates the evidence and eutxo profiles.
set -euo pipefail
MODULE="$(cd "$(dirname "$0")/../../.." && pwd -P)"
bash "$MODULE/src/test/scripts/showcase-admission-contract.sh"
WORK="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/yano-showcase-contract.XXXXXX")" && pwd -P)"
trap 'rm -rf "$WORK"' EXIT INT TERM
# The showcase lives at examples/showcase inside a Yano X distribution root.
DIST="$WORK/yano-x-jvm"
ROOT="$DIST/examples/showcase"
EVIDENCE="$DIST/examples/evidence"
mkdir -p "$DIST/plugins" "$DIST/tools/yano-appchain/bin" "$ROOT/catalog" "$EVIDENCE/config" "$WORK/bin"
printf 'fake distribution jar\n' > "$DIST/yano.jar"
printf 'fake plugin\n' > "$DIST/plugins/yano-x-showcase-bundle-test.jar"
printf 'fake history plugin\n' > "$DIST/plugins/yano-x-cardano-history-bundle-test.jar"
printf 'fake ledger plugin\n' > "$DIST/plugins/yano-x-eutxo-ledger-bundle-test.jar"
cp -R "$MODULE/src/main/showcase/." "$ROOT/"
# Packaging puts the catalog beside the showcase's config/.
cp "$MODULE/src/main/showcase/config/showcase-catalog-v1.json" "$ROOT/catalog/"
cp "$MODULE/src/test/scripts/support/fake_yano.py" "$DIST/yano.sh"
cat > "$ROOT/tools/loadtest.sh" <<'SH'
#!/usr/bin/env bash
printf 'loadtest %s base=%s\n' "$*" "$YANO_SHOWCASE_HTTP_BASE" >> "${SHOWCASE_STUB_LOG:?}"
SH
cat > "$ROOT/tools/soaktest.sh" <<'SH'
#!/usr/bin/env bash
printf 'soaktest %s\n' "$*" >> "${SHOWCASE_STUB_LOG:?}"
SH
cat > "$EVIDENCE/demo.sh" <<'SH'
#!/usr/bin/env bash
printf 'evidence %s\n' "$*" >> "${SHOWCASE_STUB_LOG:?}"
SH
# Every node answers as a healthy, settled member of a four-member chain.
cat > "$WORK/bin/curl" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${SHOWCASE_CURL_LOG:?}"
case "$*" in
  *'/admin/members'*) printf '%s\n' '{"members":["a","b","c","d"],"threshold":3}'; exit 0;;
  *'/query/'*) printf '%s\n' '{"payloadHex":"00"}'; exit 0;;
esac
printf '{"messageId":"%064d","height":1,"tipHeight":1,"stateRoot":"%064d","membershipEpochActive":true,"membershipActiveMembers":4,"members":4,"membershipActiveThreshold":3,"threshold":3,"anchor":{"bootstrapped":true}}\n' 1 2
SH
# The showcase's eUTxO helper (ShowcaseEutxoTransactions) for the payments-chain heartbeat.
cat > "$WORK/bin/java" <<'SH'
#!/usr/bin/env bash
case "$*" in
  -version) printf '%s\n' 'openjdk version "25"' >&2;;
  *' identity') printf '%s\n' '{"address":"addr_test1demo","genesisOutpoint":"00#0"}';;
  *' payment '*) printf '%s\n' '{"transactionHex":"00","transactionId":"11"}';;
  *' receipt '*) printf '%s\n' 'ACCEPTED';;
esac
exit 0
SH
printf 'DEMO_EVIDENCE_ID=showcase-contract\n' > "$EVIDENCE/config/common.env"
chmod +x "$DIST/yano.sh" "$ROOT/showcase.sh" "$ROOT/tools/"*.py "$ROOT/tools/"*.sh "$ROOT/demos/"*.sh \
  "$WORK/bin/curl" "$WORK/bin/java" "$EVIDENCE/demo.sh"
export SHOWCASE_STUB_LOG="$WORK/yano.log"
export SHOWCASE_CURL_LOG="$WORK/curl.log"
export PATH="$WORK/bin:$PATH"
fails() { # <expected message> <command...>: the command must fail with that message
  local expected="$1"; shift
  if "$@" >"$WORK/failure.log" 2>&1; then echo "accepted: $*" >&2; exit 1; fi
  grep -q -- "$expected" "$WORK/failure.log" || { cat "$WORK/failure.log" >&2; exit 1; }
}
data() { printf '%s/data/showcase/%s' "$ROOT" "$1"; }
settings() { grep -v '^#' "$1"; } # a node's config/local.yaml, as the JSON it is written in

[ "$(python3 "$ROOT/tools/showcase_codec.py" authmap-value event created 1)" = "82676372656174656401" ]
[ "$(python3 "$ROOT/tools/showcase_codec.py" authmap-value product sku-1 5 active)" = \
  "a363736b7565736b752d316673746174757366616374697665687175616e7469747905" ]
[ "$(python3 "$ROOT/tools/showcase_codec.py" authmap state-key products sku-1)" = \
  "0101000870726f647563747300000005736b752d31" ]
"$ROOT/demos/submit-message-curl.sh" http://127.0.0.1:7070 orders-chain orders.command.v1 text demo >/dev/null
! grep -q 'X-API-Key' "$WORK/curl.log"
YANO_APP_CHAIN_API_KEY=contract-key "$ROOT/demos/submit-message-curl.sh" \
  http://127.0.0.1:7070 orders-chain orders.command.v1 text demo >/dev/null
grep -q 'X-API-Key: contract-key' "$WORK/curl.log"

grep -q '^light' <<< "$("$ROOT/showcase.sh" profiles)"
grep -q 'member join' <<< "$("$ROOT/showcase.sh" help)"
! grep -q 'threshold set' <<< "$("$ROOT/showcase.sh" help)"

# A three-node instance: three applications, each set up with new -> request -> seal -> join.
"$ROOT/showcase.sh" prepare --instance three --nodes 3 --http-base 19770 --server-base 19370 >/dev/null
THREE="$(data three)"
jq -e '.network == "devnet" and .nodes == 3 and .threshold == 2 and .httpBase == 19770
  and .cardanoHistory == {enabled: true, profile: "params-only-v1"}' "$THREE/instance.json" >/dev/null
jq -e '.genesis.chains | length == 11' "$THREE/coordinator/showcase.chain" >/dev/null
jq -e '[.genesis.chains[].id] == ["cardano-history-chain"]' "$THREE/history/history.chain" >/dev/null
jq -e '[.genesis.chains[].id] == ["payment-chain-settlement"]' "$THREE/settlement/settlement.chain" >/dev/null
grep -q "^yano appchain new showcase --from $ROOT/config/showcase.template.yaml --network devnet --operators node0,node1,node2 --threshold 2 " \
  "$WORK/yano.log"
grep -q "^yano appchain new settlement --from $ROOT/config/settlement.template.yaml .* --operators node0,node1,node2 --threshold majority " \
  "$WORK/yano.log"
# Every chain's sealed anchor policy, and no option the instance did not ask for.
[ "$(jq '[.genesis.chains[].settings["anchor.mode"] | select(. == "script")] | length' \
  "$THREE/coordinator/showcase.chain")" = 11 ]
! grep -q -- '--without\|finalized-message-index' "$WORK/yano.log"
for node in 0 1 2; do
  [ "$(cat "$THREE/node$node/secrets/member.key")" = "$(python3 -c "print('%02x' % ($node + 1) * 32)")" ]
  jq -e --arg node "node$node" '.name == $node' "$THREE/node$node/node.json" >/dev/null
  # Each node joins all three applications, keeping chain indexes for its local settings.
  jq -e 'length == 13' "$THREE/node$node/apps/chain-indexes.json" >/dev/null
  jq -e '.yano.history.projection.enabled == false' <(settings "$THREE/node$node/config/local.yaml") >/dev/null
done
[ -z "$(find "$THREE" -name '.member-seed.*')" ]
WORKFLOW="$(jq -r '."workflow-chain"' "$THREE/node0/apps/chain-indexes.json")"
SETTLEMENT="$(jq -r '."payment-chain-settlement"' "$THREE/node0/apps/chain-indexes.json")"
jq -e --arg w "chains[$WORKFLOW]" --arg s "chains[$SETTLEMENT]" --arg outbox "$THREE/outbox" \
  --arg scripts "$ROOT/config/settlement" '.yano["app-chain"] as $c
  | $c[$w].effects.executor.enabled == true and $c[$w].effects.executors["showcase-outbox"].enabled == true
  and $c[$w].effects.executors["showcase-outbox"].directory == $outbox
  and $c[$s].effects.executors["eutxo-settlement"].owner == true
  and $c[$s].effects.executors["eutxo-settlement"]["vault-script-file"] == ($scripts + "/settlement-vault.script")' \
  <(settings "$THREE/node0/config/local.yaml") >/dev/null
jq -e --arg w "chains[$WORKFLOW]" --arg s "chains[$SETTLEMENT]" '.yano["app-chain"] as $c
  | $c[$w].effects.executor.enabled == false and $c[$s].effects.executors["eutxo-settlement"].owner == false' \
  <(settings "$THREE/node1/config/local.yaml") >/dev/null
! grep -R 'signing-key\|api-key\|contract-api-key' "$THREE"/node*/config >/dev/null

# The instance's choices are retained; a different one fails closed.
fails 'requested nodes differs from the retained instance' \
  "$ROOT/showcase.sh" prepare --instance three --nodes 5
fails 'requested httpBase differs' "$ROOT/showcase.sh" up --instance three --http-base 19000
fails 'more than half' "$ROOT/showcase.sh" prepare --instance weak --nodes 3 --threshold 1

# up adopts the retained ports and starts the lead first, then waits for every application.
: > "$WORK/yano.log"
"$ROOT/showcase.sh" up --instance three >/dev/null
[ "$(grep '^yano appchain start' "$WORK/yano.log" | sed 's|.*/||')" = "$(printf 'node0\nnode1\nnode2')" ]
for app in showcase history settlement; do
  grep -q "^yano appchain status $app --node-dir $THREE/node0 --wait live" "$WORK/yano.log"
done
"$ROOT/showcase.sh" load-test orders --count 25 --concurrency 3 --payload-bytes 64 --spread --instance three
"$ROOT/showcase.sh" load-test registry --count 12 --concurrency 2 --payload-bytes 32 --node 1 --instance three
"$ROOT/showcase.sh" soak-test orders --duration 3 --rate 4 --concurrency 2 --payload-bytes 32 --sample 1 \
  --report-dir "$WORK/soak" --node 0 --instance three
grep -q '^loadtest orders-chain -n 25 -c 3 -s 64 -t orders.command.v1 --spread base=19770$' "$WORK/yano.log"
grep -q '^loadtest registry-chain -n 12 -c 2 -s 32 -t kv.command.v1 --kv --node 1 base=19770$' "$WORK/yano.log"
grep -q "^soaktest orders-chain --duration 3 --rate 4 --conc 2 -s 32 --sample 1 --out $WORK/soak --node 0$" \
  "$WORK/yano.log"

# ADR-UTXO-009: settlement info reads the sealed settlement chain and the executor's settings.
SETTLEMENT_INFO="$("$ROOT/showcase.sh" settlement info --instance three)"
grep -q 'machine profile       : yano-eutxo-v3-bridge-settlement-devnet' <<< "$SETTLEMENT_INFO"
grep -q 'vault (script)        : addr_test1wpwhmf5cd5pm9gsg5y8xnkyk3xue2u35ral098gd8u49g3gjpdqgr' <<< "$SETTLEMENT_INFO"
grep -q 'nullifier shards      : addr_test1wqkrdxsvwfrzwm60qf6fk06w6f3me8ssfwururl4mt3j94sjytqyh' <<< "$SETTLEMENT_INFO"
grep -q 'No single key is custody' <<< "$SETTLEMENT_INFO"

# Membership growth: node 3 requests with the sealed file, every node approves it, then it joins and starts.
: > "$WORK/yano.log"
"$ROOT/showcase.sh" member join 3 --instance three >/dev/null
grep -q "^yano appchain request $THREE/coordinator/showcase.chain --name node3 " "$WORK/yano.log"
for node in 0 1 2; do
  grep -q "^yano appchain approve $THREE/coordinator/node3.member showcase --node-dir $THREE/node$node --yes" \
    "$WORK/yano.log"
done
grep -q "^yano appchain join $THREE/coordinator/showcase.chain .* --node-dir $THREE/node3 --yes" "$WORK/yano.log"
grep -q "^yano appchain start --node-dir $THREE/node3" "$WORK/yano.log"
[ "$(cat "$THREE/joined-nodes")" = 3 ]
# The L1-driven applications keep their founding members.
jq -e 'has("payment-chain-settlement") | not' "$THREE/node3/apps/chain-indexes.json" >/dev/null
: > "$WORK/yano.log"
"$ROOT/showcase.sh" member join 3 --instance three >/dev/null
! grep -q 'appchain approve' "$WORK/yano.log"
: > "$WORK/yano.log"
"$ROOT/showcase.sh" restart --instance three >/dev/null
[ "$(grep -c '^yano appchain stop' "$WORK/yano.log")" = 4 ]
[ "$(grep -c '^yano appchain start' "$WORK/yano.log")" = 4 ]

# Anchoring runs the setup tool's anchor command on the lead, per chain and per application.
: > "$WORK/yano.log"
"$ROOT/showcase.sh" anchor registry-chain --instance three >/dev/null
grep -q "^yano appchain anchor showcase --chain registry-chain --node-dir $THREE/node0 --yes$" "$WORK/yano.log"
"$ROOT/showcase.sh" anchor all --instance three >/dev/null
[ "$(grep -c '^yano appchain anchor ' "$WORK/yano.log")" = 14 ]
grep -q '^yano appchain anchor history --chain cardano-history-chain ' "$WORK/yano.log"
grep -q '^yano appchain anchor settlement --chain payment-chain-settlement ' "$WORK/yano.log"
"$ROOT/showcase.sh" config export "$WORK/export.chain" --instance three >/dev/null
cmp -s "$WORK/export.chain" "$THREE/coordinator/showcase.chain"

# ADR-033 finalized-message indexes are sealed draft settings; their scope is retained and validated.
"$ROOT/showcase.sh" prepare --instance indexed --http-base 19570 --server-base 19170 \
  --enable-finalized-message-index=documents-chain,workflow-chain >/dev/null
INDEXED="$(data indexed)"
jq -e '.finalizedMessageIndex == ["documents-chain", "workflow-chain"]' "$INDEXED/instance.json" >/dev/null
jq -e '[.genesis.chains[] | select(.settings["machines.finalized-message-index.enabled"] == "true") | .id]
  == ["documents-chain", "workflow-chain"]' "$INDEXED/coordinator/showcase.chain" >/dev/null
fails 'finalized-message index scope differs' \
  "$ROOT/showcase.sh" prepare --instance indexed --enable-finalized-message-index=workflow-chain
fails 'duplicate finalized-message index chain' \
  "$ROOT/showcase.sh" prepare --instance invalid-index --enable-finalized-message-index=documents-chain,documents-chain

# ADR-028/035: Cardano History presets and snapshots go into the history application; archives stay per node.
"$ROOT/showcase.sh" prepare --instance snapshots --http-base 19470 --server-base 19070 \
  --cardano-history-profile full --enable-authenticated-snapshots=cardano-history-chain \
  --authenticated-snapshot-profile jmt-blake2b256-v1 >/dev/null
SNAPSHOTS="$(data snapshots)"
jq -e '.authenticatedSnapshots == {chainIds: ["cardano-history-chain"], profile: "jmt-blake2b256-v1",
  mpfPruningChainIds: []}' "$SNAPSHOTS/instance.json" >/dev/null
jq -e '.genesis.chains[0].settings as $s | $s["capabilities.authenticated-snapshots.enabled"] == "true"
  and $s["machines.epoch-stake.snapshot-profile"] == "jmt-blake2b256-v1"
  and $s["observers.epoch-governance.type"] == "l1-epoch-governance-v1"
  and $s["machines.cardano-history.preset"] == "full-v1"' "$SNAPSHOTS/history/history.chain" >/dev/null
HISTORY="$(jq -r '."cardano-history-chain"' "$SNAPSHOTS/node1/apps/chain-indexes.json")"
jq -e --arg h "chains[$HISTORY]" --arg dir "$SNAPSHOTS/node1/data/appchain-snapshot-archives" \
  '.yano["app-chain"][$h].capabilities["authenticated-snapshots"]["archive-directory"] == $dir' \
  <(settings "$SNAPSHOTS/node1/config/local.yaml") >/dev/null
"$ROOT/showcase.sh" prepare --instance snapshot-pruning --http-base 19670 --server-base 19270 \
  --cardano-history-profile full --enable-authenticated-snapshots=cardano-history-chain \
  --enable-authenticated-snapshot-mpf-pruning=cardano-history-chain >/dev/null
grep -q '"mpf-pruning-enabled": true' "$(data snapshot-pruning)/node0/config/local.yaml"
fails 'MPF pruning requires mpf-blake2b256-v1' "$ROOT/showcase.sh" prepare --instance snapshot-pruning-jmt \
  --cardano-history-profile full --enable-authenticated-snapshots=cardano-history-chain \
  --authenticated-snapshot-profile jmt-blake2b256-v1 --enable-authenticated-snapshot-mpf-pruning=cardano-history-chain
fails 'does not declare authenticated snapshot series' \
  "$ROOT/showcase.sh" prepare --instance invalid-snapshots --enable-authenticated-snapshots=orders-chain
fails 'require a Cardano History stake or governance profile' \
  "$ROOT/showcase.sh" prepare --instance history-params-snapshot --enable-authenticated-snapshots=cardano-history-chain
"$ROOT/showcase.sh" prepare --instance history-stake --http-base 19870 --server-base 19470 \
  --cardano-history-profile params-stake --l1-source-snapshot-retention-epochs 2 >/dev/null
jq -e '.genesis.chains[0].settings as $s | $s["observers.epoch-stake.type"] == "l1-epoch-stake-v1"
  and ($s | has("observers.epoch-governance.type") | not)' "$(data history-stake)/history/history.chain" >/dev/null
jq -e '.yano["account-state"]["snapshot-retention-epochs"] == 2' \
  <(settings "$(data history-stake)/node0/config/local.yaml") >/dev/null
fails 'L1 source snapshot retention differs' "$ROOT/showcase.sh" prepare --instance history-stake \
  --l1-source-snapshot-retention-epochs 3
"$ROOT/showcase.sh" prepare --instance history-disabled --disable-cardano-history \
  --http-base 18070 --server-base 18337 >/dev/null
[ ! -e "$(data history-disabled)/history" ]
jq -e '.cardanoHistory.enabled == false' "$(data history-disabled)/instance.json" >/dev/null

# Five nodes: every node joins the showcase; the settlement federation stays the three founding members.
"$ROOT/showcase.sh" prepare --instance five --nodes 5 --http-base 19970 --server-base 19570 >/dev/null
[ "$(find "$(data five)" -maxdepth 2 -name node.json | wc -l | tr -d ' ')" = 5 ]
jq -e '.genesis.members == ["node0", "node1", "node2"]' "$(data five)/settlement/settlement.chain" >/dev/null
jq -e '.genesis.members | length == 5' "$(data five)/coordinator/showcase.chain" >/dev/null

# A public network: no devnet settlement application, and anchoring needs a key and the network confirmation.
printf '%064d\n' 1 > "$WORK/anchor.seed"
chmod 600 "$WORK/anchor.seed"
"$ROOT/showcase.sh" prepare --network preprod --instance light-preprod --http-base 17070 \
  --server-base 17337 >/dev/null
[ ! -e "$(data light-preprod)/settlement" ]
grep -q 'anchor.every-blocks=30' "$WORK/yano.log"
fails 'confirm-public-anchor preprod' "$ROOT/showcase.sh" anchor workflow-chain --instance light-preprod
: > "$WORK/yano.log"
"$ROOT/showcase.sh" anchor workflow-chain --instance light-preprod --anchor-key-file "$WORK/anchor.seed" \
  --confirm-public-anchor preprod >/dev/null
grep -q "^yano appchain anchor showcase --chain workflow-chain .* --key $WORK/anchor.seed$" "$WORK/yano.log"

# An instance made by the earlier launcher is refused rather than reinterpreted.
mkdir -p "$(data legacy)"
printf '{}\n' > "$(data legacy)/showcase-identity.json"
fails 'made by an earlier showcase' "$ROOT/showcase.sh" prepare --instance legacy

# The other profiles delegate.
"$ROOT/showcase.sh" prepare --profile eutxo --variant ledger --instance eutxo --nodes 5 \
  --http-base 19970 --server-base 19570
"$ROOT/showcase.sh" run --profile eutxo --variant ledger --instance eutxo --nodes 5 \
  --http-base 19970 --server-base 19570
grep -q '^yano appchain eutxo demo setup --scenario ledger .* --members 5 --http-port-base 19970 --server-port-base 19570$' \
  "$WORK/yano.log"
grep -q '^yano appchain eutxo demo round-trip --scenario ledger .* --members 5 --http-port-base 19970 --server-port-base 19570$' \
  "$WORK/yano.log"
fails 'EUTxO showcase variants are devnet-only' "$ROOT/showcase.sh" prepare --profile eutxo --variant ledger \
  --network preprod --instance eutxo-public
"$ROOT/showcase.sh" quickstart --profile evidence --variant role --instance evidence-role
"$ROOT/showcase.sh" verify --profile evidence --variant composite --instance evidence-composite
grep -q '^evidence role-lifecycle --deployment compose --machine role ' "$WORK/yano.log"
grep -q '^evidence verify --deployment compose --machine composite .* --evidence-id showcase-contract$' \
  "$WORK/yano.log"
fails 'evidence profile uses exactly 3 nodes with threshold 2' \
  "$ROOT/showcase.sh" prepare --profile evidence --nodes 5 --instance evidence-five
"$ROOT/showcase.sh" prepare --profile evidence --variant composite --network preprod \
  --instance evidence-preprod --anchor-key-file "$WORK/anchor.seed" --confirm-public-anchor preprod
grep -q "^evidence prepare .* --network preprod .* --anchor-key-file $WORK/anchor.seed --confirm-public-anchor preprod$" \
  "$WORK/yano.log"

echo "PASS: showcase sets every node up with the app-chain setup commands and keeps its option, node-role, join, and delegation contracts"
