#!/usr/bin/env bash
# Whole-stack Compose E2E for the evidence workflow: first run, immediate replay, retained stop/up
# restart, and post-restart replay of one evidence id on one isolated devnet cluster.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
SUBNET_TOOL="$DEMO_DIR/tools/effect_failover.py"
# shellcheck disable=SC1091
. "$DEMO_DIR/config/images.env"

[ "${YANO_RUN_WORKFLOW_REPLAY_E2E:-false}" = true ] || {
  printf '%s\n' \
    'SKIP: set YANO_RUN_WORKFLOW_REPLAY_E2E=true to run the destructive isolated E2E.'
  exit 0
}

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }
require() { command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"; }
mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then stat -c '%a' "$1"; else stat -f '%Lp' "$1"; fi
}

for command in docker curl jq python3 openssl lsof; do require "$command"; done
docker compose version >/dev/null 2>&1 || fail 'Docker Compose v2 is required'

INSTANCE="${YANO_WORKFLOW_REPLAY_INSTANCE:-workflow-replay}"
CHAIN_ID="${YANO_WORKFLOW_REPLAY_CHAIN_ID:-evidence-chain-workflow-replay}"
RUN_TOKEN="$(openssl rand -hex 6)"
EVIDENCE_ID="${YANO_WORKFLOW_REPLAY_EVIDENCE_ID:-replay-$RUN_TOKEN}"
MACHINE_MODE="${YANO_WORKFLOW_REPLAY_MACHINE:-composite}"
CONTINUATION_MODE="${YANO_WORKFLOW_REPLAY_CONTINUATION_MODE:-direct}"

[[ "$INSTANCE" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] || fail 'isolated instance id is malformed'
[[ "$CHAIN_ID" =~ ^[a-z][a-z0-9-]{0,62}$ ]] || fail 'isolated chain id is malformed'
[[ "$EVIDENCE_ID" =~ ^[a-z][a-z0-9-]{0,62}$ ]] || fail 'isolated evidence id is malformed'
case "$CONTINUATION_MODE" in
  explicit)
    EXPECTED_FINALITY_BUNDLES=2
    EXPECTED_CONTINUATION_CHECK=EXPLICIT_NOTIFY_CONTINUATION
    ;;
  direct)
    EXPECTED_FINALITY_BUNDLES=1
    EXPECTED_CONTINUATION_CHECK=DIRECT_RESULT_CONTINUATION
    ;;
  *) fail 'continuation mode must be exactly explicit or direct';;
esac
case "$MACHINE_MODE" in
  standalone)
    EXPECTED_STATE_MACHINE=evidence-registry
    EXPECTED_WORKFLOW_CHECK=DIRECT_EVIDENCE_SUBMISSION
    ;;
  composite)
    EXPECTED_STATE_MACHINE=composite
    EXPECTED_WORKFLOW_CHECK=COMPOSITE_EVIDENCE_RELEASE_WORKFLOW
    ;;
  role)
    EXPECTED_STATE_MACHINE=role-evidence
    EXPECTED_WORKFLOW_CHECK=ROLE_GATED_EVIDENCE_RELEASE_WORKFLOW
    ;;
  *) fail 'machine mode must be exactly standalone, composite, or role';;
esac

# Listeners stay below Linux's default automatic client-port range and apart from
# effect-failover-e2e.sh (18xxx/2xxxx, 172.30.114.0/24) and role-workflow-e2e.sh
# (30xxx/31xxx, 172.30.118.0/24), so the three scenarios can share one Docker host.
HTTP_BASE="${YANO_WORKFLOW_REPLAY_HTTP_BASE:-26070}"
UI_PORT="${YANO_WORKFLOW_REPLAY_UI_PORT:-26080}"
SERVER_BASE="${YANO_WORKFLOW_REPLAY_SERVER_BASE:-26337}"
KAFKA_PORT="${YANO_WORKFLOW_REPLAY_KAFKA_PORT:-27092}"
S3_PORT="${YANO_WORKFLOW_REPLAY_S3_PORT:-27000}"
IPFS_PORT="${YANO_WORKFLOW_REPLAY_IPFS_PORT:-27001}"
PROMETHEUS_PORT="${YANO_WORKFLOW_REPLAY_PROMETHEUS_PORT:-27090}"
GRAFANA_PORT="${YANO_WORKFLOW_REPLAY_GRAFANA_PORT:-27030}"
CONNECTOR_SUBNET="${YANO_WORKFLOW_REPLAY_SUBNET:-172.30.116.0/24}"
CONNECTOR_S3_IP="${YANO_WORKFLOW_REPLAY_S3_IP:-172.30.116.10}"
CONNECTOR_KUBO_IP="${YANO_WORKFLOW_REPLAY_KUBO_IP:-172.30.116.11}"
CONNECTOR_KAFKA_IP="${YANO_WORKFLOW_REPLAY_KAFKA_IP:-172.30.116.12}"
SCENARIO_TIMEOUT="${YANO_WORKFLOW_REPLAY_TIMEOUT_SECONDS:-600}"
# A low, steady L1 heartbeat lets slow CI followers recover without flooding
# them with the hundreds of empty blocks produced by the showcase cadence.
export DEMO_DEVNET_BLOCK_TIME_MILLIS=10000

# Validate every environment-controlled number before Bash arithmetic. Bash
# arithmetic recursively evaluates input and therefore must never see raw text.
require_decimal_range() {
  local name="$1" value="$2" minimum="$3" maximum="$4" number
  if ! [[ "$value" =~ ^(0|[1-9][0-9]*)$ ]] \
      || [ "${#value}" -gt "${#maximum}" ]; then
    fail "$name must be a canonical bounded decimal integer"
  fi
  number=$((10#$value))
  if [ "$number" -lt "$minimum" ] || [ "$number" -gt "$maximum" ]; then
    fail "$name is outside its supported range"
  fi
}

require_decimal_range HTTP_BASE "$HTTP_BASE" 1 65533
require_decimal_range SERVER_BASE "$SERVER_BASE" 1 65533
require_decimal_range UI_PORT "$UI_PORT" 1 65535
require_decimal_range KAFKA_PORT "$KAFKA_PORT" 1 65535
require_decimal_range S3_PORT "$S3_PORT" 1 65535
require_decimal_range IPFS_PORT "$IPFS_PORT" 1 65535
require_decimal_range PROMETHEUS_PORT "$PROMETHEUS_PORT" 1 65535
require_decimal_range GRAFANA_PORT "$GRAFANA_PORT" 1 65535
require_decimal_range SCENARIO_TIMEOUT "$SCENARIO_TIMEOUT" 10 3600

ROOT_PARENT="$(cd "${YANO_WORKFLOW_REPLAY_TMPDIR:-${TMPDIR:-/tmp}}" && pwd -P)"
ROOT="$(mktemp -d "$ROOT_PARENT/yano-workflow-replay-e2e.XXXXXX")"
ROOT="$(cd "$ROOT" && pwd -P)"
SENTINEL="$ROOT/.yano-workflow-replay-owned-v1"
(umask 077; printf 'yano-workflow-replay-e2e-v1\n' > "$SENTINEL")
chmod 600 "$SENTINEL"

remove_owned_root() {
  python3 - "$ROOT" "$SENTINEL" "$ROOT_PARENT" <<'PY'
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1])
sentinel = Path(sys.argv[2])
resolved = root.resolve(strict=True)
parent = Path(sys.argv[3]).resolve(strict=True)
if (root != resolved or root.is_symlink() or resolved.parent != parent
        or not resolved.name.startswith("yano-workflow-replay-e2e.")):
    raise SystemExit("refusing unexpected cleanup root")
if (sentinel.is_symlink()
        or sentinel.read_text(encoding="ascii") != "yano-workflow-replay-e2e-v1\n"):
    raise SystemExit("refusing cleanup without exact ownership sentinel")
shutil.rmtree(resolved)
PY
}

# Only the two randomly tagged images this run builds are owned. The pinned
# third-party images (Kafka, RustFS, Kubo, the JRE base) are a shared cache.
remove_owned_image() {
  local image="$1" claimed="$2" expected_id="$3" actual_id
  [ "$claimed" = true ] || return 0
  [ -n "$image" ] || return 1
  if docker image inspect "$image" >/dev/null 2>&1; then
    actual_id="$(docker image inspect --format '{{.Id}}' -- "$image")" || return 1
    if [ -n "$expected_id" ] && [ "$actual_id" != "$expected_id" ]; then
      return 1
    fi
    docker image rm "$image" >/dev/null 2>&1 || return 1
  fi
  ! docker image inspect "$image" >/dev/null 2>&1
}

remove_owned_images() {
  remove_owned_image "${NODE_IMAGE:-}" "${NODE_IMAGE_CLAIMED:-false}" \
    "${NODE_IMAGE_EXPECTED_ID:-}" || return 1
  remove_owned_image "${RUNNER_IMAGE:-}" "${RUNNER_IMAGE_CLAIMED:-false}" \
    "${RUNNER_IMAGE_EXPECTED_ID:-}" || return 1
}

early_cleanup() {
  local status="$?" uncertain=false cleaned=false
  trap - EXIT INT TERM
  set +e
  remove_owned_images || uncertain=true
  if [ "$uncertain" = false ] && [ -f "$SENTINEL" ]; then
    remove_owned_root >/dev/null 2>&1 && cleaned=true || uncertain=true
  fi
  if [ "$cleaned" != true ]; then
    printf 'WARN: isolated preflight diagnostics retained after uncertain cleanup: %s\n' \
      "$ROOT" >&2
  fi
  if [ "$uncertain" = true ] && [ "$status" -eq 0 ]; then status=1; fi
  exit "$status"
}
trap early_cleanup EXIT INT TERM

DATA_BASE="$ROOT/data"
SECRET_BASE="$ROOT/secrets"
RUNTIME_BASE="$ROOT/runtime"
mkdir -m 700 "$DATA_BASE" "$SECRET_BASE" "$RUNTIME_BASE"
python3 - "$ROOT" "$DATA_BASE" "$SECRET_BASE" "$RUNTIME_BASE" <<'PY' \
  || fail 'the three deployment roots are not canonical pairwise-disjoint owned directories'
import os
from pathlib import Path
import stat
import sys

root = Path(sys.argv[1]).resolve(strict=True)
paths = [Path(raw) for raw in sys.argv[2:]]
resolved = []
for path in paths:
    info = path.lstat()
    actual = path.resolve(strict=True)
    if (path.is_symlink() or not stat.S_ISDIR(info.st_mode)
            or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700
            or actual.parent != root):
        raise SystemExit(1)
    resolved.append(actual)
if len(set(resolved)) != 3:
    raise SystemExit(1)
for index, left in enumerate(resolved):
    for right in resolved[index + 1:]:
        if left in right.parents or right in left.parents:
            raise SystemExit(1)
PY

RUNTIME="$RUNTIME_BASE/networks/devnet/$INSTANCE/compose"
SECRETS="$SECRET_BASE/networks/devnet/$INSTANCE/compose"
DATA="$DATA_BASE/networks/devnet/instances/$INSTANCE/compose"
ENV_FILE="$RUNTIME/compose.env"
REPORTS="$DATA/reports"
API_KEY_FILE="$SECRETS/yano-api-key"
NODE_DIR="$SECRETS/nodes-compose"
PROJECT_NAME=""
PREPARED=false
CLEANED=false

NODE_IMAGE="yano-adr013-replay-node:$RUN_TOKEN"
RUNNER_IMAGE="yano-adr013-replay-runner:$RUN_TOKEN"
NODE_IMAGE_CLAIMED=false
RUNNER_IMAGE_CLAIMED=false
NODE_IMAGE_EXPECTED_ID=""
RUNNER_IMAGE_EXPECTED_ID=""
for image in "$NODE_IMAGE" "$RUNNER_IMAGE"; do
  docker image inspect "$image" >/dev/null 2>&1 \
    && fail "random isolated image tag already exists: $image"
done
NODE_IMAGE_CLAIMED=true
RUNNER_IMAGE_CLAIMED=true

EPHEMERAL_FIRST=0
EPHEMERAL_LAST=0
if [ -r /proc/sys/net/ipv4/ip_local_port_range ]; then
  read -r EPHEMERAL_FIRST EPHEMERAL_LAST < /proc/sys/net/ipv4/ip_local_port_range
  require_decimal_range EPHEMERAL_FIRST "$EPHEMERAL_FIRST" 1 65535
  require_decimal_range EPHEMERAL_LAST "$EPHEMERAL_LAST" 1 65535
  note "Host automatic client-port range: $EPHEMERAL_FIRST-$EPHEMERAL_LAST"
fi
reject_ephemeral_listener() {
  local port="$1"
  if [ "$EPHEMERAL_FIRST" -ne 0 ] && [ "$port" -ge "$EPHEMERAL_FIRST" ] \
      && [ "$port" -le "$EPHEMERAL_LAST" ]; then
    fail "test listener $port overlaps automatic client-port range; choose another YANO_WORKFLOW_REPLAY port"
  fi
}

port_free() { ! lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
PORTS="$HTTP_BASE $((HTTP_BASE + 1)) $((HTTP_BASE + 2)) \
$UI_PORT $SERVER_BASE $((SERVER_BASE + 1)) $((SERVER_BASE + 2)) \
$KAFKA_PORT $S3_PORT $IPFS_PORT $PROMETHEUS_PORT $GRAFANA_PORT"
python3 - $PORTS <<'PY' || fail 'isolated deployment ports overlap'
import sys
ports = [int(raw) for raw in sys.argv[1:]]
if len(ports) != len(set(ports)):
    raise SystemExit(1)
PY
for port in $PORTS; do
  reject_ephemeral_listener "$port"
  port_free "$port" || fail "isolated test port is already in use: $port"
done

EXISTING_DOCKER_SUBNETS="$(docker network inspect $(docker network ls -q) \
  --format '{{range .IPAM.Config}}{{.Subnet}}{{"\n"}}{{end}}' 2>/dev/null)" \
  || fail 'could not inspect existing Docker networks'
[ "${#EXISTING_DOCKER_SUBNETS}" -le 65536 ] \
  || fail 'Docker subnet inventory exceeds the bounded preflight input'
subnet_check=(python3 "$SUBNET_TOOL" subnet-check --candidate "$CONNECTOR_SUBNET")
while IFS= read -r existing; do
  [ -z "$existing" ] || subnet_check+=(--existing "$existing")
done <<< "$EXISTING_DOCKER_SUBNETS"
"${subnet_check[@]}" >/dev/null \
  || fail "isolated connector subnet overlaps an existing Docker network: $CONNECTOR_SUBNET"

export DEMO_DATA_ROOT="$DATA_BASE"
export DEMO_SECRET_ROOT="$SECRET_BASE"
export DEMO_RUNTIME_ROOT="$RUNTIME_BASE"
export DEMO_HTTP_BASE="$HTTP_BASE"
export DEMO_UI_PORT="$UI_PORT"
export DEMO_SERVER_BASE="$SERVER_BASE"
export DEMO_KAFKA_PORT="$KAFKA_PORT"
export DEMO_S3_PORT="$S3_PORT"
export DEMO_IPFS_PORT="$IPFS_PORT"
export DEMO_PROMETHEUS_PORT="$PROMETHEUS_PORT"
export DEMO_GRAFANA_PORT="$GRAFANA_PORT"
export DEMO_CONNECTOR_SUBNET="$CONNECTOR_SUBNET"
export DEMO_S3_IP="$CONNECTOR_S3_IP"
export DEMO_KUBO_IP="$CONNECTOR_KUBO_IP"
export DEMO_KAFKA_IP="$CONNECTOR_KAFKA_IP"
export DEMO_OBSERVABILITY=false
export DEMO_SCENARIO_TIMEOUT_SECONDS="$SCENARIO_TIMEOUT"
export DEMO_SCENARIO_POLL_INTERVAL_MILLIS=500
export DEMO_SKIP_BUILD=false
export DEMO_YANO_IMAGE="$NODE_IMAGE"
export DEMO_RUNNER_IMAGE="$RUNNER_IMAGE"

demo() {
  "$DEMO_DIR/demo.sh" "$1" --deployment compose --network devnet \
    --instance "$INSTANCE" --chain-id "$CHAIN_ID" \
    --evidence-id "$EVIDENCE_ID" --continuation "$CONTINUATION_MODE" \
    --machine "$MACHINE_MODE"
}

dc() {
  [ -n "$PROJECT_NAME" ] && [ -f "$ENV_FILE" ] || fail 'Compose project is not resolved'
  docker compose -p "$PROJECT_NAME" --env-file "$ENV_FILE" \
    -f "$DEMO_DIR/compose.yaml" "$@"
}

capture_owned_image_ids() {
  NODE_IMAGE_EXPECTED_ID="$(docker image inspect --format '{{.Id}}' -- "$NODE_IMAGE")" \
    || return 1
  RUNNER_IMAGE_EXPECTED_ID="$(docker image inspect --format '{{.Id}}' -- "$RUNNER_IMAGE")" \
    || return 1
  [[ "$NODE_IMAGE_EXPECTED_ID" =~ ^sha256:[0-9a-f]{64}$ ]] \
    && [[ "$RUNNER_IMAGE_EXPECTED_ID" =~ ^sha256:[0-9a-f]{64}$ ]]
}

bounded_get() {
  local url="$1" output="$2" max_bytes="$3" temporary
  temporary="$output.tmp"
  rm -f "$temporary"
  if ! (umask 077; ulimit -f 8192; curl --proto '=http' --connect-timeout 2 \
      --max-time 10 --max-filesize "$max_bytes" -fsS "$url" > "$temporary"); then
    rm -f "$temporary"
    return 1
  fi
  install_bounded_response "$temporary" "$output" "$max_bytes"
}

bounded_authenticated_get() {
  local url="$1" output="$2" max_bytes="$3"
  local temporary api_key
  [ -f "$API_KEY_FILE" ] && [ ! -L "$API_KEY_FILE" ] \
    && [ "$(mode "$API_KEY_FILE")" = 600 ] || return 1
  api_key="$(tr -d '\r\n' < "$API_KEY_FILE")"
  [[ "$api_key" =~ ^[0-9a-f]{64}$ ]] || { unset api_key; return 1; }
  temporary="$output.tmp"
  rm -f "$temporary"
  if ! (umask 077; ulimit -f 8192; \
      printf 'header = "X-API-Key: %s"\n' "$api_key" \
      | curl --proto '=http' --connect-timeout 2 --max-time 10 \
          --max-filesize "$max_bytes" -fsS --config - "$url" > "$temporary"); then
    unset api_key
    rm -f "$temporary"
    return 1
  fi
  unset api_key
  install_bounded_response "$temporary" "$output" "$max_bytes"
}

install_bounded_response() {
  local temporary="$1" output="$2" max_bytes="$3" bytes
  [ -f "$temporary" ] && [ ! -L "$temporary" ] \
    || { rm -f "$temporary"; return 1; }
  bytes="$(wc -c < "$temporary" | tr -d ' ')"
  [[ "$bytes" =~ ^[0-9]+$ ]] && [ "$bytes" -gt 0 ] && [ "$bytes" -le "$max_bytes" ] \
    || { rm -f "$temporary"; return 1; }
  mv "$temporary" "$output"
}

# Bounded node, L1 and log diagnostics. They run before the stack is stopped, on
# failure from the exit trap and on success before the explicit final stop.
qualification_diagnostics() {
  local node port status
  [ -n "$PROJECT_NAME" ] && [ -f "$ENV_FILE" ] || return 0
  note 'Bounded workflow-replay status diagnostics before cleanup (success or failure):' >&2
  for node in 0 1 2; do
    port=$((HTTP_BASE + node))
    status="$ROOT/diagnostics-node$node-status.json"
    if bounded_get "http://127.0.0.1:$port/api/v1/app-chain/chains/$CHAIN_ID/status" \
        "$status" 1048576 2>/dev/null; then
      jq -c --argjson node "$node" '{node:$node,chainId,running,tipHeight,stateRoot,memberKey,
        members,threshold,stateMachine,
        anchor: (.anchor // {} | {leader,mode,bootstrapped,lastAnchoredHeight})}' \
        "$status" >&2 || true
    else
      note "node $node app-chain status unavailable" >&2
    fi
    if bounded_get "http://127.0.0.1:$port/api/v1/node/status" \
        "$ROOT/diagnostics-node$node-l1.json" 1048576 2>/dev/null; then
      jq -c --argjson node "$node" '{node:$node,localTipSlot,localTipBlockNumber,
        remoteTipSlot,blocksProcessed,runtimeDegraded,peerState,peerRecoveryReason,
        peerApplicationProgressAgeMillis,peerBodyFetchInProgress,
        peerBodyFetchInProgressAgeMillis,peerKeepAliveAgeMillis,upstreamValidationLevel}' \
        "$ROOT/diagnostics-node$node-l1.json" >&2 || true
    fi
  done
  dc ps -a >&2 || true
  note 'Bounded script-anchor adoption diagnostics (including earlier co-sign rounds):' >&2
  dc logs --no-color --since 30m yano-0 yano-1 yano-2 2>&1 \
    | awk '/Script-anchor|script-anchor|anchor identity|anchoring configured/' \
    | tail -n 180 >&2 || true
  note 'Bounded L1 sync/recovery diagnostics (no validation settings changed):' >&2
  dc logs --no-color --since 30m yano-0 yano-1 yano-2 2>&1 \
    | awk '/WARN|ERROR|Peer session|Upstream peer|body fetch|Body fetch|Nonce state/' \
    | tail -n 120 >&2 || true
}

cleanup() {
  local status="$?" uncertain=false remaining=""
  trap - EXIT INT TERM
  set +e
  if [ "$status" -ne 0 ] && [ -n "$PROJECT_NAME" ] && [ -f "$ENV_FILE" ]; then
    qualification_diagnostics
    dc logs --no-color --tail 120 yano-0 yano-1 yano-2 2>&1 | tail -n 360 >&2 || true
  fi
  if [ "$PREPARED" = true ]; then
    demo stop >/dev/null 2>&1 || uncertain=true
  fi
  if [ -n "$PROJECT_NAME" ]; then
    remaining="$(docker ps -a --filter \
      "label=com.docker.compose.project=$PROJECT_NAME" -q 2>/dev/null)" \
      || uncertain=true
    [ -z "$remaining" ] || uncertain=true
    remaining="$(docker network ls --filter \
      "label=com.docker.compose.project=$PROJECT_NAME" -q 2>/dev/null)" \
      || uncertain=true
    [ -z "$remaining" ] || uncertain=true
  fi
  if [ "$uncertain" = false ] && [ -f "$SENTINEL" ]; then
    if remove_owned_images && remove_owned_root >/dev/null 2>&1; then
      CLEANED=true
    else
      uncertain=true
    fi
  fi
  if [ "$CLEANED" != true ]; then
    printf 'WARN: isolated test diagnostics retained after uncertain cleanup: %s\n' \
      "$ROOT" >&2
  fi
  if [ "$uncertain" = true ] && [ "$status" -eq 0 ]; then status=1; fi
  exit "$status"
}

wait_json() {
  local url="$1" expression="$2" output="$3" deadline=$((SECONDS + 180))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if bounded_get "$url" "$output" 1048576 2>/dev/null \
        && jq -e "$expression" "$output" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  printf 'Timed out waiting for %s at %s\n' "$expression" "$url" >&2
  [ -f "$output" ] && jq -c . "$output" >&2 2>/dev/null || true
  return 1
}

wait_authenticated_json() {
  local url="$1" expression="$2" output="$3" deadline=$((SECONDS + 180))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if bounded_authenticated_get "$url" "$output" 1048576 2>/dev/null \
        && jq -e "$expression" "$output" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

assert_effect_ownership() {
  local phase="$1" node port output
  grep -Fxq 'yano.app-chain.chains[0].effects.executor.enabled=true' \
    "$NODE_DIR/node0.properties" \
    && grep -Fxq \
      'yano.app-chain.chains[0].effects.executor.types=object.put,ipfs.pin,kafka.publish' \
      "$NODE_DIR/node0.properties" \
    || fail "$phase node 0 lacks the exact executor partition"
  for node in 1 2; do
    grep -Fxq 'yano.app-chain.chains[0].effects.executor.enabled=false' \
      "$NODE_DIR/node$node.properties" \
      || fail "$phase node $node is not executor-disabled"
  done
  output="$ROOT/$phase-node0-effects.json"
  wait_json "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$CHAIN_ID/effects/stats" \
    '.stats.enabled == true and (.stats | has("runtimeEnabled") | not)
      and (.stats.owner | test("^v1:fp1:[0-9a-f]{64}$"))
      and .stats.executors == ["ipfs-pin", "kafka-publish", "objectstore-s3-object-put"]' \
    "$output" || fail "$phase node 0 is not the sole exact effect owner"
  for node in 1 2; do
    port=$((HTTP_BASE + node))
    output="$ROOT/$phase-node$node-effects.json"
    wait_json "http://127.0.0.1:$port/api/v1/app-chain/chains/$CHAIN_ID/effects/stats" \
      '.stats.enabled == true and .stats.runtimeEnabled == false
        and .stats.metricsGeneration == "inactive"
        and (.stats | has("owner") | not) and (.stats | has("executors") | not)
        and (.stats | has("executorOperations") | not)' \
      "$output" || fail "$phase node $node unexpectedly owns effects"
  done
}

assert_executor_activity() {
  local phase="$1" node port output
  output="$ROOT/$phase-node0-executor-activity.json"
  wait_json "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$CHAIN_ID/effects/stats" \
    '.stats.executorOperations as $operations
      | ($operations | length) == 3
      and (($operations | sort_by(.id) | map(.id)) == [
        "ipfs-pin", "kafka-publish", "objectstore-s3-object-put"])
      and all($operations[];
        (keys == ["attempts", "bundleId", "failureCode", "id", "inFlight",
          "lastFailureAge", "lastSuccessAge", "readiness", "retryableFailures",
          "sampleState", "scheme", "successes", "terminalFailures", "types"])
        and .sampleState == "FRESH" and .readiness == "READY"
        and .attempts >= 1 and .successes >= 1 and .inFlight == 0
        and .retryableFailures == 0 and .terminalFailures == 0
        and .lastSuccessAge != "NEVER" and .failureCode == "NONE")' \
    "$output" || fail "$phase executor activity snapshot is incomplete or unsafe"

  for node in 0 1 2; do
    port=$((HTTP_BASE + node))
    output="$ROOT/$phase-node$node-executor-metrics.prom"
    bounded_get "http://127.0.0.1:$port/q/metrics" "$output" 4194304 \
      || fail "$phase node $node executor metrics are unavailable or oversized"
    python3 - "$output" "$CHAIN_ID" "$node" <<'PY' \
      || fail "$phase node $node executor metric inventory is incorrect"
import math
from pathlib import Path
import re
import sys

text = Path(sys.argv[1]).read_text(encoding="utf-8")
chain = sys.argv[2]
node = int(sys.argv[3])
prefix = "yano_appchain_effects_executor_"
expected = {"ipfs-pin", "kafka-publish", "objectstore-s3-object-put"}
samples = []
for line in text.splitlines():
    if not line.startswith(prefix) or line.startswith("#"):
        continue
    try:
        metric, raw_value = line.rsplit(None, 1)
        value = float(raw_value)
    except ValueError:
        raise SystemExit(1)
    if not math.isfinite(value) or value < 0:
        raise SystemExit(1)
    name, _, raw_labels = metric.partition("{")
    labels = dict(re.findall(r'([A-Za-z_][A-Za-z0-9_]*)="([^"\\]*)"', raw_labels))
    samples.append((name, labels, value))

def reject(reason):
    inventory = {}
    for name, labels, value in samples:
        inventory.setdefault(name, []).append({
            "executor": labels.get("executor"),
            "slot": labels.get("slot"),
            "state": labels.get("state"),
            "value": value,
        })
    print(f"executor metrics rejected: {reason}; inventory={inventory}", file=sys.stderr)
    raise SystemExit(1)

if node != 0:
    if samples:
        reject("executor-disabled member exported executor series")
    raise SystemExit(0)

# The exact exporter spelling of base units is not contractual. Cardinality is:
# three fixed executor slots, four readiness states, and a bounded fixed set of
# counters/gauges. Require every operationally essential series and reject any
# dynamic executor label, while leaving room for exporter base-unit variants.
if not 21 <= len(samples) <= 48:
    reject(f"unexpected bounded series count {len(samples)}")
if {labels.get("executor") for _, labels, _ in samples} != expected:
    reject("executor labels differ from the startup-fixed inventory")
if any(labels.get("chain") != chain for _, labels, _ in samples):
    reject("chain label mismatch")

for executor in expected:
    owned = [(name, labels, value) for name, labels, value in samples
             if labels.get("executor") == executor]
    slots = {labels.get("slot") for _, labels, _ in owned}
    if len(slots) != 1 or None in slots:
        reject(f"{executor} does not own one fixed slot")
    readiness = [(labels.get("state"), value) for name, labels, value in owned
                 if name == prefix + "readiness"]
    if sorted(state for state, _ in readiness) != [
            "degraded", "ready", "unavailable", "unknown"]:
        reject(f"{executor} readiness is not one fixed four-state vector")
    if dict(readiness) != {"ready": 1.0, "degraded": 0.0,
                           "unavailable": 0.0, "unknown": 0.0}:
        reject(f"{executor} is not one-hot ready")
    attempts = [value for name, _, value in owned if "attempts" in name]
    successes = [value for name, _, value in owned if "successes" in name]
    in_flight = [value for name, _, value in owned if "in_flight" in name]
    if len(attempts) != 1 or attempts[0] < 1:
        reject(f"{executor} has no positive attempts counter")
    if len(successes) != 1 or successes[0] < 1:
        reject(f"{executor} has no positive successes counter")
    if in_flight != [0.0]:
        reject(f"{executor} in-flight gauge is absent, duplicated, or nonzero")
PY
  done
}

assert_plugin_inventory() {
  local phase="$1" node port summary bundles fingerprint expected=""
  for node in 0 1 2; do
    port=$((HTTP_BASE + node))
    summary="$ROOT/$phase-node$node-plugin-summary.json"
    bundles="$ROOT/$phase-node$node-plugin-bundles.json"
    wait_authenticated_json "http://127.0.0.1:$port/api/v1/plugin-operations" \
      '.catalogFingerprint | test("^sha256:[0-9a-f]{64}$")' "$summary" \
      || fail "$phase node $node plugin operations are unavailable"
    wait_authenticated_json \
      "http://127.0.0.1:$port/api/v1/plugin-operations/bundles?limit=100" \
      '(.items | map(select(.selected)) | map(.id)) == [
          "org.yanoproject.x.composite",
          "org.yanoproject.x.evidence-profile",
          "org.yanoproject.x.evidence-registry",
          "org.yanoproject.x.ipfs",
          "org.yanoproject.x.kafka",
          "org.yanoproject.x.objectstore.s3",
          "org.yanoproject.x.role-workflow",
          "org.yanoproject.x.stdlib"]
        and (.items | map(select(.selected)) | all(
          .selectionStatus == "SELECTED"
          and (.lifecycle == "VALIDATED" or .lifecycle == "ACTIVE")
          and (.health == "UNKNOWN" or .health == "UP")
          and .failure.code == "NONE" and .metricsStale == false))
        and ([.items[] | select(.selected) | .contributionCount] | add) == 30
        and .nextAfter == null' "$bundles" \
      || fail "$phase node $node plugin inventory differs from the demo catalog"
    fingerprint="$(jq -r '.catalogFingerprint' "$summary")"
    if ! jq -e --arg fingerprint "$fingerprint" '
      .catalogFingerprint == $fingerprint and .pluginApiMajor == 3
      and (.pluginApiLevel | type == "number" and . >= 8) and .totals.selectedBundles == 8
      and .totals.failedBundles == 0 and .totals.degradedBundles == 0
      and .totals.staleSources == 0' "$summary" >/dev/null; then
      jq -c '{pluginApiMajor, pluginApiLevel, totals}' "$summary" >&2 || true
      fail "$phase node $node plugin summary is unhealthy"
    fi
    jq -e --arg fingerprint "$fingerprint" '.catalogFingerprint == $fingerprint' \
      "$bundles" >/dev/null || fail "$phase node $node plugin snapshots disagree"
    if [ -z "$expected" ]; then expected="$fingerprint"; else
      [ "$expected" = "$fingerprint" ] \
        || fail "$phase plugin fingerprints differ across members"
    fi
  done
}

assert_metrics() {
  local phase="$1" node port output
  for node in 0 1 2; do
    port=$((HTTP_BASE + node))
    output="$ROOT/$phase-node$node-metrics.prom"
    bounded_get "http://127.0.0.1:$port/q/metrics" "$output" 4194304 \
      || fail "$phase node $node metrics are unavailable or oversized"
    python3 - "$output" "$CHAIN_ID" <<'PY' \
      || fail "$phase node $node lacks app-chain/effect/plugin metrics"
import math
from pathlib import Path
import sys

text = Path(sys.argv[1]).read_text(encoding="utf-8")
chain = sys.argv[2]

def sample(name, label):
    matches = []
    for line in text.splitlines():
        if not line.startswith(name + "{") or label not in line:
            continue
        value = float(line.rsplit(None, 1)[1])
        if not math.isfinite(value) or value < 0:
            raise SystemExit(1)
        matches.append(value)
    if len(matches) != 1:
        raise SystemExit(1)
    return matches[0]

sample("yano_appchain_tip_height", f'chain="{chain}"')
sample("yano_appchain_effects_open", f'chain="{chain}"')
if sample("yano_plugin_bundles", 'state="selected"') != 8:
    raise SystemExit(1)
PY
  done
}

capture_cluster_state() {
  local phase="$1" output="$2" node valid port
  local deadline=$((SECONDS + 180))
  while [ "$SECONDS" -lt "$deadline" ]; do
    valid=true
    for node in 0 1 2; do
      port=$((HTTP_BASE + node))
      bounded_get "http://127.0.0.1:$port/api/v1/app-chain/chains/$CHAIN_ID/status" \
        "$ROOT/$phase-node$node-status.json" 1048576 2>/dev/null || valid=false
    done
    if [ "$valid" = true ] && jq -s -e --arg chain "$CHAIN_ID" \
        --arg machine "$EXPECTED_STATE_MACHINE" '
        length == 3 and all(.[];
          .chainId == $chain and .running == true and .tipHeight >= 1
          and (.stateRoot | test("^[0-9a-f]{64}$"))
          and (.memberKey | test("^[0-9a-f]{64}$"))
          and .members == 3 and .threshold == 2
          and .stateMachine == $machine
          and (($machine != "composite" and $machine != "role-evidence") or (
            .stateMachineStatus.mode == "governed"
            and .stateMachineStatus.currentEpoch == 0
            and (.stateMachineStatus.activeProfileDigest
              | test("^[0-9a-f]{64}$"))
            and (.stateMachineStatus as $machineStatus
              | ($machineStatus.catalogDigests
                | index($machineStatus.activeProfileDigest)) != null)
            and .stateMachineStatus.catalogReady == true
            and (.stateMachineStatus.currentMembershipDigest
              | test("^[0-9a-f]{64}$")))))
        and ([.[].tipHeight] | unique | length) == 1
        and ([.[].stateRoot] | unique | length) == 1
        and ([.[].memberKey] | unique | length) == 3' \
        "$ROOT/$phase-node0-status.json" "$ROOT/$phase-node1-status.json" \
        "$ROOT/$phase-node2-status.json" >/dev/null 2>&1; then
      jq -S -c -s '[.[] | {chainId, running, tipHeight, stateRoot, memberKey,
        members, threshold, stateMachine, stateMachineStatus}]' \
        "$ROOT/$phase-node0-status.json" \
        "$ROOT/$phase-node1-status.json" "$ROOT/$phase-node2-status.json" \
        > "$output.tmp"
      mv "$output.tmp" "$output"
      return 0
    fi
    sleep 1
  done
  printf 'Cluster agreement timeout (%s):\n' "$phase" >&2
  for node in 0 1 2; do
    if [ -f "$ROOT/$phase-node$node-status.json" ]; then
      jq -c --argjson node "$node" '{node:$node,chainId,running,tipHeight,stateRoot,
        memberKey,members,threshold,stateMachine}' \
        "$ROOT/$phase-node$node-status.json" >&2 || true
    else
      printf '{"node":%s,"status":"unavailable"}\n' "$node" >&2
    fi
  done
  return 1
}

cluster_state_signature() {
  jq -S -c 'map(del(.tipHeight))' "$1"
}

assert_read_only_preserved_cluster_state() {
  local before="$1" after="$2" label="$3"
  if ! cmp -s "$before" "$after"; then
    jq -c '[.[] | {tipHeight, stateRoot}]' "$before" "$after" >&2 || true
    fail "$label changed consensus tip, state, membership, or governed profile"
  fi
}

assert_report() {
  local report="$1"
  [ -f "$report" ] && [ ! -L "$report" ] \
    && [ "$(wc -c < "$report" | tr -d ' ')" -le 1048576 ] \
    || fail 'scenario report is missing, unsafe, or oversized'
  if ! jq -e --arg evidence "$EVIDENCE_ID" --arg chain "$CHAIN_ID" \
    --arg continuationCheck "$EXPECTED_CONTINUATION_CHECK" \
    --arg workflowCheck "$EXPECTED_WORKFLOW_CHECK" \
    --argjson finalityBundles "$EXPECTED_FINALITY_BUNDLES" '
    def passed($name):
      ([.checks[] | select(.name == $name and .status == "PASS")] | length) == 1;
    def checked($name; $status):
      ([.checks[] | select(.name == $name and .status == $status)] | length) == 1;
    .schemaVersion == 1 and .evidenceId == $evidence
    and .outcome == "PASS" and .failureCode == null
    and .chain.chainId == $chain and .chain.businessStatus == "READY"
    and .chain.membersVerified == 3 and .chain.finalityThreshold == 2
    and .chain.stateProofsVerified == 6 and .chain.effectProofsVerified == 3
    and .chain.finalityBundlesVerified == $finalityBundles
    and .storage.objectStateVerified == true and .storage.ipfsPinVerified == true
    and (.storage.objectVersionFingerprint | test("^[0-9a-f]{64}$"))
    and (.storage.cid | startswith("baf"))
    and .kafka.topic == "evidence.available.v1" and .kafka.partition == 0
    and .kafka.offset >= 0 and .kafka.eventVerified == true
    and .anchor.required == true and .anchor.portableLinkageVerified == true
    and .anchor.portableTransactionsVisibleOnAllMembers == true
    and .anchor.portableDatumCommitmentsVerified == true
    and .anchor.memberObservationCovered == true
    and .anchor.memberObservedTransactionVisibleOnAllMembers == true
    and .anchor.memberObservedDatumCommitmentVerified == true
    and ([.checks[].name] | length) == ([.checks[].name] | unique | length)
    and ((
      passed("AUTHENTICATED_STORAGE_RESULTS")
      and passed($workflowCheck)
      and passed($continuationCheck)
      and passed("THREE_NODE_STATE_AGREEMENT")
      and passed("COMPOSED_EFFECT_PROOFS")
      and passed("KAFKA_ACKNOWLEDGEMENT_AND_EVENT")
      and passed("PORTABLE_ANCHOR_LINKAGE")
      and passed("PORTABLE_ANCHOR_TXS_VISIBLE_ON_ALL_MEMBERS")
      and passed("PORTABLE_ANCHOR_DATUM_COMMITMENTS_VERIFIED")
      and passed("APP_CHAIN_MEMBER_ANCHOR_OBSERVATION")
      and passed("MEMBER_OBSERVED_ANCHOR_TX_VISIBLE_ON_ALL_MEMBERS")
      and passed("MEMBER_OBSERVED_ANCHOR_DATUM_COMMITMENT_VERIFIED")
    ) or (
      .operation == "VERIFY"
      and passed("SERVICES_PROBED")
      and passed("READ_ONLY_EXTERNAL_STORAGE_VERIFIED")
      and passed("READ_ONLY_FINALITY_AND_STATE_PROOFS")
      and passed("READ_ONLY_EFFECT_AND_KAFKA_PROOFS")
      and checked("BUSINESS_CLAIM_NOT_EVALUATED"; "NOT_EVALUATED")
      and passed("READ_ONLY_VERIFICATION_COMPLETE")
    ))' "$report" >/dev/null; then
    jq -c '{schemaVersion,evidenceId,operation,outcome,failureCode,chain,storage,kafka,anchor,
      checks:[.checks[] | {name,status}]}' "$report" >&2 || true
    fail 'scenario report does not close the workflow acceptance contract'
  fi
}

LAST_REPORT=""
run_and_capture() {
  local label="$1" output scenario report
  output="$ROOT/$label-run.out"
  if ! demo run >"$output" 2>&1; then
    sed -n '1,200p' "$output" >&2
    if [ -f "$REPORTS/latest.json" ] && [ ! -L "$REPORTS/latest.json" ] \
        && [ "$(wc -c < "$REPORTS/latest.json" | tr -d ' ')" -le 1048576 ]; then
      jq -c '{outcome, failureCode, checks}' "$REPORTS/latest.json" >&2 || true
    fi
    fail "$label scenario command failed"
  fi
  [ "$(wc -c < "$output" | tr -d ' ')" -le 1048576 ] \
    || fail "$label scenario output exceeds its bound"
  [ "$(grep -c '^PASS command=run scenario=' "$output")" -eq 1 ] \
    || { sed -n '1,200p' "$output" >&2; fail "$label scenario did not return one PASS"; }
  scenario="$(sed -n 's/^PASS command=run scenario=//p' "$output")"
  [[ "$scenario" =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] \
    || fail "$label scenario id is malformed"
  report="$REPORTS/report-$scenario.json"
  assert_report "$report"
  [ -f "$REPORTS/latest.json" ] && [ ! -L "$REPORTS/latest.json" ] \
    && cmp -s "$report" "$REPORTS/latest.json" \
    || fail "$label latest report does not identify the completed attempt"
  LAST_REPORT="$report"
}

attempt_reports() {
  find "$REPORTS" -maxdepth 1 -type f -name 'report-*.json' | wc -l | tr -d ' '
}

# Fields a replay must reproduce exactly. Observation fields that legitimately
# move with the chain (heights, roots, anchor transaction hashes) are excluded.
stable_receipt_signature() {
  jq -S -c '{
    evidenceId,
    chain: (.chain | {chainId, businessStatus}),
    storage: (.storage | {sha256, size, objectVersionFingerprint, cid,
      objectStateVerified, ipfsPinVerified}),
    kafka,
    anchor: (.anchor | {required, portableLinkageVerified,
      portableTransactionsVisibleOnAllMembers, portableDatumCommitmentsVerified,
      memberObservationCovered,
      memberObservedTransactionVisibleOnAllMembers,
      memberObservedDatumCommitmentVerified})
  }' "$1"
}

capture_effect_inventory() {
  local phase="$1" output="$2" raw
  raw="$output.raw"
  bounded_get \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$CHAIN_ID/effects?fromHeight=0&limit=100" \
    "$raw" 1048576 || fail "$phase effect inventory is unavailable or oversized"
  jq -S -c --arg chain "$CHAIN_ID" '
    if (.chainId == $chain
        and (.effects | length) == 3
        and ([.effects[].type] | sort)
          == ["ipfs.pin", "kafka.publish", "object.put"]
        and ([.effects[].effectIdHashHex] | unique | length) == 3
        and all(.effects[];
          .chainId == $chain
          and ((.height | type) == "number" and .height >= 1)
          and ((.ordinal | type) == "number" and .ordinal >= 0)
          and (.effectHashHex | test("^[0-9a-f]{64}$"))
          and (.effectIdHashHex | test("^[0-9a-f]{64}$"))))
    then [.effects[] | {
      chainId, height, ordinal, type, scope, gate, resultPolicy, expiryHeight,
      effectHashHex, effectIdHashHex
    }] | sort_by(.height, .ordinal)
    else error("unexpected logical effect inventory")
    end
  ' "$raw" > "$output.tmp" \
    || fail "$phase does not contain exactly one logical effect of each required type"
  mv "$output.tmp" "$output"
}

assert_ui_latest() {
  local phase="$1" latest="$2" output
  output="$ROOT/$phase-ui-latest.json"
  bounded_get "http://127.0.0.1:$UI_PORT/api/v1/reports/latest" "$output" 1048576 \
    || fail "$phase evidence UI is unavailable or oversized"
  cmp -s "$latest" "$output" || fail "$phase evidence UI does not serve retained latest.json"
}

# Staged binaries and the two owned image identities. A retained restart must
# reuse exactly these bytes and images.
artifact_manifest() {
  local output="$1"
  python3 - "$RUNTIME" "$output.files" <<'PY' || return 1
import hashlib
import json
import os
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve(strict=True)
max_file_bytes = 512 * 1024 * 1024
max_total_bytes = 1024 * 1024 * 1024
expected = [
    "plugins/yano-x-ipfs-bundle.jar",
    "plugins/yano-x-kafka-bundle.jar",
    "plugins/yano-x-objectstore-s3-bundle.jar",
    "plugins/yano-x-composite-bundle.jar",
    "plugins/yano-x-stdlib-bundle.jar",
    "plugins/yano-x-role-workflow-bundle.jar",
    "plugins/yano-x-evidence-registry-bundle.jar",
    "plugins/yano-x-evidence-profile-bundle.jar",
    "runner.jar",
]
actual_plugins = sorted(entry.name for entry in (root / "plugins").iterdir())
if actual_plugins != sorted(Path(name).name for name in expected if name.startswith("plugins/")):
    raise SystemExit(1)
result = {}
total = 0
for relative in expected:
    path = root / relative
    info = path.lstat()
    resolved = path.resolve(strict=True)
    if (path.is_symlink() or resolved != path or not path.is_file()
            or info.st_uid != os.geteuid() or info.st_nlink != 1
            or not 1 <= info.st_size <= max_file_bytes):
        raise SystemExit(1)
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
                         | getattr(os, "O_NOFOLLOW", 0))
    try:
        before = os.fstat(descriptor)
        identity = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns)
        if identity(before) != identity(info):
            raise SystemExit(1)
        total += before.st_size
        if total > max_total_bytes:
            raise SystemExit(1)
        digest = hashlib.sha256()
        consumed = 0
        while chunk := os.read(descriptor, 1024 * 1024):
            consumed += len(chunk)
            if consumed > before.st_size:
                raise SystemExit(1)
            digest.update(chunk)
        after = os.fstat(descriptor)
        if consumed != before.st_size or identity(after) != identity(before):
            raise SystemExit(1)
    finally:
        os.close(descriptor)
    result[relative] = {"bytes": before.st_size, "sha256": digest.hexdigest()}
Path(sys.argv[2]).write_text(json.dumps(result, sort_keys=True, separators=(",", ":")) + "\n",
                             encoding="utf-8")
PY
  capture_owned_image_ids || return 1
  jq -S -c --arg node "$NODE_IMAGE_EXPECTED_ID" --arg runner "$RUNNER_IMAGE_EXPECTED_ID" \
    '{files: ., images: {node: $node, runner: $runner}}' "$output.files" > "$output" \
    || return 1
  rm -f "$output.files"
}

verify_live_generation() {
  local phase="$1"
  assert_effect_ownership "$phase"
  assert_plugin_inventory "$phase"
  assert_metrics "$phase"
}

note "Starting isolated workflow-replay E2E ($MACHINE_MODE machine, $CONTINUATION_MODE continuation) in $ROOT"
PREPARED=true
demo prepare
capture_owned_image_ids || fail 'prepared Compose image tags are missing'
demo config > "$ROOT/compose-preflight.yaml"
python3 - "$ENV_FILE" <<'PY' \
  || fail 'Compose environment is not a private regular owner file'
import os
from pathlib import Path
import stat
import sys
path = Path(sys.argv[1])
info = path.lstat()
if (path.is_symlink() or not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
        or info.st_nlink != 1 or stat.S_IMODE(info.st_mode) != 0o600
        or not 1 <= info.st_size <= 65_536):
    raise SystemExit(1)
PY
[ "$(grep -c '^DEMO_PROJECT_NAME=' "$ENV_FILE")" -eq 1 ] \
  || fail 'Compose environment has no unique project name'
PROJECT_NAME="$(sed -n 's/^DEMO_PROJECT_NAME=//p' "$ENV_FILE")"
[[ "$PROJECT_NAME" =~ ^yano-effects-devnet-${INSTANCE}-[0-9a-f]{8}$ ]] \
  || fail "unexpected isolated Compose project: $PROJECT_NAME"
[ -z "$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT_NAME" -q)" ] \
  || fail 'random isolated Compose project already owns containers'
# Only an exit trap armed after the project is resolved may stop it.
trap cleanup EXIT INT TERM

demo up
demo probe
verify_live_generation initial
artifact_manifest "$ROOT/initial-artifacts.json" \
  || fail 'staged artifacts or owned images are missing or unsafe'

run_and_capture first
FIRST="$LAST_REPORT"
assert_executor_activity first
capture_effect_inventory first "$ROOT/first-effects.json"
capture_cluster_state first "$ROOT/first-state.json" \
  || fail 'members did not agree after the first scenario'
[ "$(jq -r '.kafka.offset' "$FIRST")" -eq 0 ] \
  || fail 'fresh isolated broker did not assign the first exact Kafka offset'

run_and_capture replay
REPLAY="$LAST_REPORT"
capture_effect_inventory replay "$ROOT/replay-effects.json"
cmp -s "$ROOT/first-effects.json" "$ROOT/replay-effects.json" \
  || fail 'immediate replay changed the logical effect inventory'
capture_cluster_state replay "$ROOT/replay-state.json" \
  || fail 'members did not agree after immediate replay'
assert_read_only_preserved_cluster_state "$ROOT/first-state.json" \
  "$ROOT/replay-state.json" 'immediate replay'
[ "$(attempt_reports)" -eq 2 ] || fail 'replay did not create exactly two attempt reports'
[ "$(stable_receipt_signature "$FIRST")" = "$(stable_receipt_signature "$REPLAY")" ] \
  || fail 'immediate replay changed state or external receipts'
assert_ui_latest pre-restart "$REPLAY"
capture_cluster_state pre-restart "$ROOT/pre-restart-state.json" \
  || fail 'members did not agree before retained restart'

note 'Restarting the exact Compose stack from retained data'
demo stop
[ -z "$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT_NAME" -q)" ] \
  || fail 'stop retained project containers'
demo up
demo probe
verify_live_generation restarted
artifact_manifest "$ROOT/restarted-artifacts.json" \
  || fail 'restarted artifacts or owned images are missing or unsafe'
cmp -s "$ROOT/initial-artifacts.json" "$ROOT/restarted-artifacts.json" \
  || fail 'retained restart changed its staged binary artifacts or images'
capture_cluster_state restarted "$ROOT/restarted-state.json" \
  || fail 'members did not recover retained agreement'
cmp -s "$ROOT/pre-restart-state.json" "$ROOT/restarted-state.json" \
  || fail 'app-chain state or membership changed across retained restart'

run_and_capture post-restart-replay
POST_RESTART="$LAST_REPORT"
capture_effect_inventory post-restart "$ROOT/post-restart-effects.json"
cmp -s "$ROOT/first-effects.json" "$ROOT/post-restart-effects.json" \
  || fail 'retained restart/replay changed the logical effect inventory'
capture_cluster_state post-restart-replay "$ROOT/post-restart-replay-state.json" \
  || fail 'members did not agree after retained restart/replay'
assert_read_only_preserved_cluster_state "$ROOT/restarted-state.json" \
  "$ROOT/post-restart-replay-state.json" 'retained restart/replay'
[ "$(cluster_state_signature "$ROOT/first-state.json")" \
    = "$(cluster_state_signature "$ROOT/post-restart-replay-state.json")" ] \
  || fail 'retained restart/replay changed app-chain state or membership'
[ "$(stable_receipt_signature "$FIRST")" \
    = "$(stable_receipt_signature "$POST_RESTART")" ] \
  || fail 'retained restart/replay changed state or external receipts'
assert_ui_latest post-restart "$POST_RESTART"
[ "$(attempt_reports)" -eq 3 ] \
  || fail 'retained replay did not produce exactly three attempt reports'

qualification_diagnostics
note 'Stopping the exact isolated Compose project'
demo stop
PREPARED=false
[ -z "$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT_NAME" -q)" ] \
  || fail 'final stop retained project containers'
[ -z "$(docker network ls --filter "label=com.docker.compose.project=$PROJECT_NAME" -q)" ] \
  || fail 'final stop retained project networks'
remove_owned_images || fail 'owned images could not be removed'
remove_owned_root
CLEANED=true
trap - EXIT INT TERM
note "PASS: workflow replay ($MACHINE_MODE machine, $CONTINUATION_MODE continuation) keeps effects," \
  "state, receipts, and artifacts across replay and retained restart."
