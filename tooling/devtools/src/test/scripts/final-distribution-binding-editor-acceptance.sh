#!/usr/bin/env bash
# ADR-031.2 M3 acceptance. A binding document exported by the Studio editor and its CLI-authored equivalent each run
# on a fresh three-node devnet generated from the final JVM archive, one after the other on the same ports with the
# same blueprint name and member keys. Both must pin the same IR, profile and plugin catalog. In each run all three
# nodes must finalize the command with identical receipt bytes, audit head and state root, and a restarted node must
# serve the same root. Across the runs the finalized height, the audit component's head and the decoded receipt must
# be equal apart from message ids: the node assigns every submission its own id, which the receipt (and therefore its
# bytes and the root) records.
#
# Prerequisites: bash, curl, jq, lsof, unzip and node. Uses ports 29870-29872 (HTTP) and 29470-29472 (node to node)
# unless YANO_EDITOR_ACCEPTANCE_HTTP_BASE/SERVER_BASE are set. It never uses an existing deployment or its ports.
set -euo pipefail

usage() {
  echo "Usage: final-distribution-binding-editor-acceptance.sh YANO_ZIP EDITOR_DOCUMENT CLI_DOCUMENT REPO" >&2
  exit 64
}
[ "$#" -eq 4 ] || usage
for input in "$1" "$2" "$3"; do [ -f "$input" ] || { echo "FAIL: $input is not a file" >&2; exit 64; }; done
[ -d "$4" ] || { echo "FAIL: $4 is not a directory" >&2; exit 64; }
archive="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
editor_document="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
cli_document="$(cd "$(dirname "$3")" && pwd)/$(basename "$3")"
for tool in curl jq lsof unzip node; do
  command -v "$tool" >/dev/null || { echo "FAIL: $tool is required" >&2; exit 69; }
done

http_base="${YANO_EDITOR_ACCEPTANCE_HTTP_BASE:-29870}"
server_base="${YANO_EDITOR_ACCEPTANCE_SERVER_BASE:-29470}"
ports=("$http_base" "$((http_base + 1))" "$((http_base + 2))"
  "$server_base" "$((server_base + 1))" "$((server_base + 2))")
for port in "${ports[@]}"; do
  # The retained local deployment (HTTP 7170-7172, node to node 14337-14339) is never touched.
  if { [ "$port" -ge 7170 ] && [ "$port" -le 7172 ]; } || { [ "$port" -ge 14337 ] && [ "$port" -le 14339 ]; }; then
    echo "FAIL: port $port belongs to the retained deployment; choose other port bases" >&2
    exit 64
  fi
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "FAIL: port $port is in use; set YANO_EDITOR_ACCEPTANCE_HTTP_BASE/SERVER_BASE" >&2
    exit 1
  fi
done

# Nothing from the caller's environment may redirect node data, ports or configuration.
while read -r name; do unset "$name"; done < <(env | sed -n 's/^\(\(YANO\|QUARKUS\)_[A-Za-z0-9_]*\)=.*/\1/p')

# Public test identities (the same fixed seeds as the other packaged acceptance scripts); never real keys.
seeds=()
for byte in 01 02 03; do
  seed=""
  for _ in $(seq 1 32); do seed="$seed$byte"; done
  seeds+=("$seed")
done
api_key='binding-editor-acceptance-operator'
chain=workflow
project=""
restarted_pid=""

work="$(mktemp -d "${TMPDIR:-/tmp}/yano-binding-editor.XXXXXX")"

# Stops the current project; returns non-zero, keeping the work directory, if any node process survives.
stop_project() {
  [ -n "$project" ] && [ -x "$project/scripts/stop" ] || return 0
  "$project/scripts/stop" >/dev/null 2>&1 || true
  survivors=""
  for record in "$project"/run/node*.pid; do
    [ -f "$record" ] || continue
    pid="$(cat "$record")"
    kill -0 "$pid" 2>/dev/null && survivors="$survivors $pid"
  done
  if [ -n "$restarted_pid" ] && kill -0 "$restarted_pid" 2>/dev/null; then survivors="$survivors $restarted_pid"; fi
  if [ -n "$survivors" ]; then
    echo "FAIL: node processes still running:$survivors; keeping $work for inspection" >&2
    return 1
  fi
}
cleanup() {
  stop_project || return 1
  if [ "${KEEP_WORK:-false}" != true ]; then rm -rf "$work"; fi
}
failure() {
  code=$?
  trap - EXIT
  echo "FAIL: line ${BASH_LINENO[0]}" >&2
  if [ -n "$project" ]; then
    for log in "$project"/logs/*.log; do
      [ -f "$log" ] && { echo "--- $log" >&2; tail -60 "$log" >&2; }
    done
  fi
  cleanup || true
  exit "$code"
}
trap failure EXIT

http() { curl -fsS --connect-timeout 2 --max-time 15 "$@"; }
authed() { http -H "X-API-Key: $api_key" "$@"; }
api() { printf 'http://127.0.0.1:%s/api/v1/app-chain/chains/%s' "$((http_base + $1))" "$chain"; }

unzip -q "$archive" -d "$work/distribution"
yano_home="$(find "$work/distribution" -mindepth 1 -maxdepth 1 -type d | head -1)"
export YANO_HOME="$yano_home"

# Both projects use the same name, members and ports so that they describe the same chain genesis.
init() {
  "$yano_home/yano.sh" appchain init --non-interactive --recipe declarative-composite \
    --network devnet --members 3 --name binding-editor-acceptance --chain-id "$chain" \
    --member-key 8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c \
    --member-key 8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394 \
    --member-key ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1 \
    --bindings "$1" --plugins-directory "$yano_home/plugins" \
    --http-port-base "$http_base" --server-port-base "$server_base" \
    --output "$2" --format json >/dev/null
  for node in 0 1 2; do
    printf '%s\n' "YANO_APPCHAIN_SIGNING_KEY=${seeds[$node]}" "YANO_APPCHAIN_API_KEYS=$api_key" \
      >"$2/secrets/node$node.env"
  done
  chmod 600 "$2"/secrets/node*.env
}
init "$editor_document" "$work/editor-project"
init "$cli_document" "$work/cli-project"

# Rendering pins the canonical IR digest, the composite profile digest and the plugin catalog; all must agree.
pins() { jq -S '.catalogDigests | with_entries(select(.key | startswith("binding.")))' "$1/appchain.lock"; }
[ "$(pins "$work/editor-project" | jq 'length')" = 3 ]
[ "$(pins "$work/editor-project")" = "$(pins "$work/cli-project")" ]
echo "PASS: editor-exported and CLI-authored documents pin the same IR, composite profile and plugin catalog"

# Waits for one node's readiness; with a pid, that process must stay alive while it becomes ready.
wait_ready() {
  for _ in $(seq 1 120); do
    if [ -n "${2:-}" ] && ! kill -0 "$2" 2>/dev/null; then
      echo "FAIL: node $1 process $2 exited before becoming ready" >&2
      return 1
    fi
    http "http://127.0.0.1:$((http_base + $1))/q/health/ready" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "FAIL: node $1 did not become ready" >&2
  return 1
}
root_of() { authed "$(api "$1")/blocks/$height" 2>/dev/null | jq -er '.stateRoot' 2>/dev/null || true; }

# Checks the running project for leaked secrets, stops it and requires every node process and pid file to be gone.
finish_project() {
  assert_no_secrets
  stop_project
  if ls "$project"/run/node*.pid >/dev/null 2>&1; then
    echo "FAIL: stop left pid files in $project/run" >&2
    exit 1
  fi
}

# Fails if the API key or any seed appears in logs or in a running node's command line.
assert_no_secrets() {
  patterns=(-e "$api_key" -e "${seeds[0]}" -e "${seeds[1]}" -e "${seeds[2]}")
  if grep -R -F -q "${patterns[@]}" "$project/logs"; then
    echo "FAIL: a secret appears in $project/logs" >&2
    exit 1
  fi
  for record in "$project"/run/node*.pid; do
    [ -f "$record" ] || continue
    if ps -p "$(cat "$record")" -o command= 2>/dev/null | grep -F -q "${patterns[@]}"; then
      echo "FAIL: a secret appears in a node process command line" >&2
      exit 1
    fi
  done
}

# Starts a project, finalizes one kv-registry put(0x0102, 0x0304) and records its id, height, root, receipt, the
# audit head of entity "0102" and the decoded outcome.
run_project() {
  project="$1"
  restarted_pid=""
  "$project/scripts/start" >/dev/null
  for node in 0 1 2; do wait_ready "$node"; done
  YANO_APPCHAIN_API_KEYS="$api_key" "$yano_home/yano.sh" appchain drift "$project" \
    --peer "http://127.0.0.1:${http_base}/api/v1/" \
    --peer "http://127.0.0.1:$((http_base + 1))/api/v1/" \
    --peer "http://127.0.0.1:$((http_base + 2))/api/v1/" \
    --api-key-env YANO_APPCHAIN_API_KEYS --format json \
    | jq -e '.status == "DRIFT_OK" and .peerCount == 3' >/dev/null
  submitted="$(authed -H 'content-type: application/json' \
    -d '{"topic":"records.command.v1","bodyHex":"8300420102420304"}' "$(api 0)/messages")"
  message_id="$(jq -er '.messageId' <<<"$submitted")"
  height=0
  for _ in $(seq 1 90); do
    height="$(authed "$(api 0)/messages/$message_id" 2>/dev/null | jq -r '.height // 0' 2>/dev/null || echo 0)"
    [ "${height:-0}" -gt 0 ] && break
    sleep 1
  done
  [ "${height:-0}" -gt 0 ] || { echo "FAIL: the message did not finalize" >&2; exit 1; }
  # Three valid, identical roots at the finalized height; missing roots never count as agreement.
  agreed=false
  for _ in $(seq 1 60); do
    roots=("$(root_of 0)" "$(root_of 1)" "$(root_of 2)")
    if [ -n "${roots[0]}" ] && [ "${roots[0]}" = "${roots[1]}" ] && [ "${roots[1]}" = "${roots[2]}" ]; then
      agreed=true
      break
    fi
    sleep 1
  done
  [ "$agreed" = true ] || { echo "FAIL: nodes did not agree on the root at height $height" >&2; exit 1; }
  root="${roots[0]}"
  authed "$(api 0)/blocks/$height" | jq -e '.certSignatures >= 2' >/dev/null
  receipts=()
  for node in 0 1 2; do
    receipts+=("$(authed -H 'content-type: application/json' -d '{"paramsHex":""}' \
      "$(api "$node")/query/composite/binding-receipt-v1/$message_id" | jq -er '.payloadHex')")
  done
  if [ -z "${receipts[0]}" ] || [ "${receipts[0]}" != "${receipts[1]}" ] \
      || [ "${receipts[1]}" != "${receipts[2]}" ]; then
    echo "FAIL: nodes returned different or missing receipts" >&2
    exit 1
  fi
  receipt="${receipts[0]}"
  # The derived append's finalized business state: the audit trail head for entity hex(0x0102) = "0102".
  heads=()
  for node in 0 1 2; do
    heads+=("$(authed -H 'content-type: application/json' -d '{"paramsHex":"30313032"}' \
      "$(api "$node")/query/components/audit/head" | jq -er '.payloadHex')")
  done
  if [ -z "${heads[0]}" ] || [ "${heads[0]}" != "${heads[1]}" ] || [ "${heads[1]}" != "${heads[2]}" ]; then
    echo "FAIL: nodes returned different or missing audit heads" >&2
    exit 1
  fi
  audit_head="${heads[0]}"
  # Decoded with the strict receipt decoder shipped in the archive's Studio: accepted, audit append recorded. The
  # printed outcome omits only the per-submission message ids.
  outcome="$(node --input-type=module -e "
import {decodeBindingReceipt, hexToBytes} from '$yano_home/studio/binding-receipt.mjs';
const receipt = decodeBindingReceipt(hexToBytes(process.argv[1]));
const derived = receipt.steps.find(step => step.bindingId === 'audit-record');
const text = value => JSON.stringify(value, (key, item) => typeof item === 'bigint' ? item.toString() : item);
if (receipt.status !== 'ACCEPTED' || !derived || derived.targetComponentId !== 'audit'
    || derived.status !== 'PLANNED') {
  console.error(text(receipt));
  process.exit(1);
}
console.log(text({...receipt, sourceMessageIdHex: undefined,
  steps: receipt.steps.map(step => ({...step, messageIdHex: undefined}))}));" "$receipt")"
  assert_no_secrets
}

run_project "$work/editor-project"
editor=("$height" "$audit_head" "$outcome")
echo "PASS: three nodes finalized the editor-authored cascade with identical roots, receipt bytes and audit heads"

# Restart node 2: the old process must exit, and the replacement process must serve the same root.
old_pid="$(cat "$project/run/node2.pid")"
kill "$old_pid"
for _ in $(seq 1 60); do kill -0 "$old_pid" 2>/dev/null || break; sleep 1; done
if kill -0 "$old_pid" 2>/dev/null; then
  echo "FAIL: node 2 (pid $old_pid) did not stop" >&2
  exit 1
fi
rm -f "$project/run/node2.pid"
"$project/scripts/start-node" 2 >"$project/logs/node2-restart.log" 2>&1 &
restarted_pid=$!
printf '%s\n' "$restarted_pid" >"$project/run/node2.pid"
wait_ready 2 "$restarted_pid"
restarted=""
for _ in $(seq 1 90); do
  kill -0 "$restarted_pid" 2>/dev/null || { echo "FAIL: restarted node 2 exited" >&2; exit 1; }
  restarted="$(root_of 2)"
  [ "$restarted" = "$root" ] && break
  sleep 1
done
[ "$restarted" = "$root" ] || { echo "FAIL: restarted node 2 does not serve root $root" >&2; exit 1; }
echo "PASS: a restarted node serves the same finalized root"
finish_project

# The CLI-authored equivalent, on the same ports and genesis, must reach the same outcome.
run_project "$work/cli-project"
cli=("$height" "$audit_head" "$outcome")
finish_project
labels=("finalized height" "audit head" "decoded receipt (apart from message ids)")
for index in 0 1 2; do
  if [ "${editor[$index]}" != "${cli[$index]}" ]; then
    echo "FAIL: ${labels[$index]} differs between the editor-exported and CLI-authored runs" >&2
    exit 1
  fi
done
echo "PASS: the CLI-authored equivalent finalizes at the same height with the same audit head and decoded receipt"

trap - EXIT
if [ "${KEEP_WORK:-false}" != true ]; then rm -rf "$work"; fi
echo "PASS: final distribution binding editor acceptance"
