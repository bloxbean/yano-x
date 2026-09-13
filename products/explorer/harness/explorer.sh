#!/usr/bin/env bash
# Verifiable Explorer launcher (ADR-050 §2.7): runs `yano-explorer serve` against a Yano node,
# by default the showcase instance that lives next to this distribution.
#
#   ./explorer.sh up [--url http://127.0.0.1:7070] [--api-key-file <file>] [--chains a,b] [--port 8490]
#   ./explorer.sh status | env | logs | stop | clean [--instance <name>]
#   ./explorer.sh traffic            (drives `showcase.sh run all` on the showcase instance)
#
# The API key comes from --api-key-file, YANO_API_KEY, or the showcase default. The index lives
# under EXPLORER_DATA_DIR (default ~/.yano-x/explorer/<instance>), owner-only.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTANCE="${EXPLORER_INSTANCE:-default}"
DATA_DIR="${EXPLORER_DATA_DIR:-$HOME/.yano-x/explorer}"
NODE_URL="${EXPLORER_NODE_URL:-http://127.0.0.1:7070}"
PORT="${EXPLORER_PORT:-8490}"
BIND="${EXPLORER_BIND:-127.0.0.1}"
POLL_MS="${EXPLORER_POLL_MS:-2000}"
CHAINS="${EXPLORER_CHAINS:-}"
API_KEY_FILE="${EXPLORER_API_KEY_FILE:-}"
MEMBERS_FILE="${EXPLORER_MEMBERS_FILE:-}"
SHOWCASE_INSTANCE="${EXPLORER_SHOWCASE_INSTANCE:-}"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing required tool: $1"; }

usage() {
  cat <<'USAGE'
usage: explorer.sh <command> [--instance <name>] [--url <node base url>] [--api-key-file <file>]
                             [--members <file>] [--chains <id,id>] [--port <n>] [--bind <host>]
                             [--showcase-instance <name>]

  up       start the service: index the node's chains continuously and serve the read API
  status   service and index status
  env      print the service URL and index path as shell exports
  logs     tail the service log
  stop     stop the service (the index is kept)
  clean    stop and delete the instance's index
  traffic  run every showcase scenario on the showcase instance (needs showcase.sh next to this
           distribution and --showcase-instance)

Environment: EXPLORER_CLI (yano-explorer launcher, auto-detected), EXPLORER_DATA_DIR
(default ~/.yano-x/explorer), YANO_API_KEY (when no --api-key-file is given).
USAGE
}

resolve_cli() {
  CLI="${EXPLORER_CLI:-}"
  if [ -z "$CLI" ]; then
    local candidate
    for candidate in "$SCRIPT_DIR/../../tools/yano-explorer/bin/yano-explorer" \
        "$SCRIPT_DIR/../cli/build/install/yano-explorer/bin/yano-explorer"; do
      if [ -x "$candidate" ]; then CLI="$(cd "$(dirname "$candidate")" && pwd -P)/$(basename "$candidate")"; break; fi
    done
  fi
  [ -n "$CLI" ] && [ -x "$CLI" ] \
    || die "yano-explorer not found; build it (./gradlew :products:explorer:cli:installDist) or set EXPLORER_CLI"
}

resolve_showcase() {
  SHOWCASE=""
  local candidate
  for candidate in "$SCRIPT_DIR/../showcase/showcase.sh" "$SCRIPT_DIR/../../showcase.sh" \
      "$SCRIPT_DIR"/../../../build/yano-x/yano-x-jvm-*/examples/showcase/showcase.sh; do
    if [ -x "$candidate" ]; then SHOWCASE="$candidate"; break; fi
  done
}

instance_root() { printf '%s/%s' "$DATA_DIR" "$INSTANCE"; }
db_file() { printf '%s/explorer.db' "$(instance_root)"; }
pid_file() { printf '%s/serve.pid' "$(instance_root)"; }
log_file() { printf '%s/serve.log' "$(instance_root)"; }
marker_file() { printf '%s/explorer.json' "$(instance_root)"; }
api_key_copy() { printf '%s/api-key' "$(instance_root)"; }

api_key() {
  if [ -n "$API_KEY_FILE" ]; then
    [ -r "$API_KEY_FILE" ] || die "cannot read --api-key-file $API_KEY_FILE"
    tr -d ' \r\n' < "$API_KEY_FILE"
  elif [ -n "${YANO_API_KEY:-}" ]; then
    printf '%s' "$YANO_API_KEY"
  elif [ -r "$(api_key_copy)" ]; then
    tr -d ' \r\n' < "$(api_key_copy)"
  else
    # The showcase's cluster launcher default; a real deployment sets its own key.
    printf '%s' "yano-local-cluster-full-key"
  fi
}

adopt() {
  [ -f "$(marker_file)" ] || die "no instance '$INSTANCE' (run up first)"
  NODE_URL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["nodeUrl"])' "$(marker_file)")"
  PORT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["port"])' "$(marker_file)")"
  BIND="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["bind"])' "$(marker_file)")"
}

running() {
  [ -f "$(pid_file)" ] && kill -0 "$(cat "$(pid_file)")" 2>/dev/null
}

cmd_up() {
  need java; need python3
  resolve_cli
  if running; then die "instance '$INSTANCE' is already running (pid $(cat "$(pid_file)"))"; fi
  mkdir -p "$(instance_root)"; chmod 700 "$(instance_root)"
  (umask 077; api_key > "$(api_key_copy)")
  python3 - "$(marker_file)" "$INSTANCE" "$NODE_URL" "$PORT" "$BIND" "$CHAINS" <<'PY'
import json, os, sys
path, instance, url, port, bind, chains = sys.argv[1:]
doc = {"schemaVersion": 1, "instance": instance, "nodeUrl": url, "port": int(port), "bind": bind,
       "chains": [c for c in chains.split(",") if c]}
fd = os.open(path, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
with os.fdopen(fd, "w") as handle:
    json.dump(doc, handle, indent=2)
    handle.write("\n")
PY
  local -a args=(serve --url "$NODE_URL" --db "$(db_file)" --port "$PORT" --bind "$BIND" --poll-ms "$POLL_MS"
    --api-key-file "$(api_key_copy)")
  [ -z "$CHAINS" ] || args+=(--chain "$CHAINS")
  [ -z "$MEMBERS_FILE" ] || args+=(--members "$MEMBERS_FILE")
  export JAVA_OPTS="${JAVA_OPTS:-} --enable-native-access=ALL-UNNAMED"
  nohup "$CLI" "${args[@]}" > "$(log_file)" 2>&1 &
  echo $! > "$(pid_file)"
  local i
  for i in $(seq 1 30); do
    if curl -fsS "http://$BIND:$PORT/healthz" >/dev/null 2>&1; then break; fi
    sleep 1
  done
  curl -fsS "http://$BIND:$PORT/healthz" >/dev/null 2>&1 || { tail -20 "$(log_file)" >&2; die "the service did not come up; see $(log_file)"; }
  note "yano-explorer serving http://$BIND:$PORT from node $NODE_URL"
  note "  index : $(db_file)"
  note "  log   : $(log_file)"
  note "  console: open product-ui/explorer/index.html (or npm run dev) and enter http://$BIND:$PORT"
}

cmd_status() {
  adopt
  if running; then note "service: running (pid $(cat "$(pid_file)")) at http://$BIND:$PORT"; else note "service: stopped"; fi
  local chains
  chains="$(curl -fsS "http://$BIND:$PORT/chains" 2>/dev/null)" || { note "  (service not answering)"; return 0; }
  printf '%s' "$chains" | python3 -c '
import json, sys
for chain in json.load(sys.stdin):
    print("  %s: checkpoint %s tip %s levels %s %s" % (chain.get("chainId"), chain.get("checkpointHeight", 0),
          chain.get("tipHeight"), chain.get("levels", {}), chain.get("diagnostic", "")))'
}

cmd_env() {
  adopt
  printf 'export YANO_EXPLORER_URL=%q\n' "$NODE_URL"
  printf 'export EXPLORER_SERVICE_URL=%q\n' "http://$BIND:$PORT"
  printf 'export EXPLORER_DB=%q\n' "$(db_file)"
}

cmd_logs() { adopt; tail -n 50 "$(log_file)"; }

cmd_stop() {
  adopt
  if running; then kill "$(cat "$(pid_file)")" && note "stopped pid $(cat "$(pid_file)")"; else note "not running"; fi
  rm -f "$(pid_file)"
}

cmd_clean() {
  if [ -f "$(marker_file)" ]; then cmd_stop; fi
  rm -rf -- "$(instance_root)"
  note "removed $(instance_root)"
}

cmd_traffic() {
  resolve_showcase
  [ -n "$SHOWCASE" ] || die "showcase.sh not found next to this distribution"
  [ -n "$SHOWCASE_INSTANCE" ] || die "--showcase-instance <name> is required"
  "$SHOWCASE" run all --instance "$SHOWCASE_INSTANCE"
}

COMMAND="${1:-}"; [ $# -eq 0 ] || shift
while [ $# -gt 0 ]; do
  case "$1" in
    --instance) INSTANCE="${2:-}"; shift 2;;
    --url) NODE_URL="${2:-}"; shift 2;;
    --api-key-file) API_KEY_FILE="${2:-}"; shift 2;;
    --members) MEMBERS_FILE="${2:-}"; shift 2;;
    --chains) CHAINS="${2:-}"; shift 2;;
    --port) PORT="${2:-}"; shift 2;;
    --bind) BIND="${2:-}"; shift 2;;
    --poll-ms) POLL_MS="${2:-}"; shift 2;;
    --showcase-instance) SHOWCASE_INSTANCE="${2:-}"; shift 2;;
    -h|--help) usage; exit 0;;
    *) die "unknown option: $1";;
  esac
done
[[ "$INSTANCE" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die "invalid instance name"

case "$COMMAND" in
  up) cmd_up;;
  status) cmd_status;;
  env) cmd_env;;
  logs) cmd_logs;;
  stop) cmd_stop;;
  clean) cmd_clean;;
  traffic) cmd_traffic;;
  ""|-h|--help) usage;;
  *) die "unknown command: $COMMAND";;
esac
