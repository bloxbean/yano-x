#!/usr/bin/env bash
# Smoke-test one Yano X JVM release ZIP on this machine: archive layout, the
# packaged multi-node acceptance gates, and a live three-node showcase with a
# restart. Non-destructive: it works in a fresh directory on explicit ports,
# never touches retained deployments, and stops every node it starts.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: smoke_release_zip.sh <yano-x-jvm-<version>.zip> [options]

  --http-base N     first showcase HTTP port (default 29770); the acceptance
                    gates use N+100 and N+200
  --server-base N   first showcase node-to-node port (default 29370); the
                    acceptance gates use N+100 and N+200
  --work DIR        working directory (default: a new temporary directory)
  --skip-showcase   run only the archive and acceptance gates
  --skip-run-all    start and verify the showcase without `run all`
  --keep            keep the working directory after a pass
EOF
}

REPO="$(cd "$(dirname "$0")/../../../.." && pwd -P)"
ZIP="" HTTP_BASE=29770 SERVER_BASE=29370 WORK="" SHOWCASE_GATES=true RUN_ALL=true KEEP=false
while [ $# -gt 0 ]; do
  case "$1" in
    --http-base) HTTP_BASE="${2:?}"; shift 2;;
    --server-base) SERVER_BASE="${2:?}"; shift 2;;
    --work) WORK="${2:?}"; shift 2;;
    --skip-showcase) SHOWCASE_GATES=false; shift;;
    --skip-run-all) RUN_ALL=false; shift;;
    --keep) KEEP=true; shift;;
    -h|--help) usage; exit 0;;
    -*) usage >&2; exit 64;;
    *) [ -z "$ZIP" ] || { usage >&2; exit 64; }; ZIP="$1"; shift;;
  esac
done
[ -n "$ZIP" ] && [ -f "$ZIP" ] || { usage >&2; exit 64; }
ZIP="$(cd "$(dirname "$ZIP")" && pwd -P)/$(basename "$ZIP")"

die() { echo "ERROR: $*" >&2; exit 1; }

for tool in java curl jq python3 unzip lsof shasum; do
  command -v "$tool" >/dev/null || die "missing required tool: $tool"
done
java_version="$(java -XshowSettings:properties -version 2>&1 \
  | awk -F'= ' '/java.specification.version/ {print $2}')"
[ "$java_version" = 25 ] || die "Java 25 is required (found ${java_version:-unknown})"

# Test what the archive ships, not a local workaround.
unset _DEVNET_YANO_HISTORY_PROJECTION_ENABLED

for base in "$HTTP_BASE" "$SERVER_BASE" "$((HTTP_BASE + 100))" "$((SERVER_BASE + 100))" \
    "$((HTTP_BASE + 200))" "$((SERVER_BASE + 200))"; do
  for port in "$base" "$((base + 1))" "$((base + 2))"; do
    ! lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1 \
      || die "port $port is in use; pass other --http-base/--server-base values"
  done
done

[ -n "$WORK" ] || WORK="$(mktemp -d "${TMPDIR:-/tmp}/yano-x-smoke.XXXXXX")"
mkdir -p "$WORK/logs"
WORK="$(cd "$WORK" && pwd -P)"
LOGS="$WORK/logs" DATA="$WORK/showcase-data" INSTANCE=smoke
SHOWCASE_STARTED=false DIST="" SHOWCASE=""

cleanup() {
  local code=$?
  if [ "$SHOWCASE_STARTED" = true ]; then
    "$SHOWCASE" stop --instance "$INSTANCE" --data-dir "$DATA" >"$LOGS/showcase-stop.log" 2>&1 \
      || echo "WARN: showcase stop failed; see $LOGS/showcase-stop.log" >&2
  fi
  if [ "$code" -eq 0 ] && [ "$KEEP" = false ]; then
    rm -rf "$WORK"
  else
    echo "Working directory kept: $WORK" >&2
  fi
  exit "$code"
}
trap cleanup EXIT

# Bash ignores errexit inside a function run as an `if` condition, so every
# step function below returns its failures explicitly.
step() {
  local name="$1" started=$SECONDS
  shift
  printf '%-34s ' "$name"
  if "$@" >"$LOGS/$name.log" 2>&1; then
    printf 'PASS %5ss\n' "$((SECONDS - started))"
  else
    printf 'FAIL %5ss  (%s)\n' "$((SECONDS - started))" "$LOGS/$name.log"
    tail -40 "$LOGS/$name.log" >&2
    exit 1
  fi
}

extract_archive() {
  unzip -tq "$ZIP" && unzip -q "$ZIP" -d "$WORK/dist" || return 1
  DIST="$(find "$WORK/dist" -mindepth 1 -maxdepth 1 -type d -name 'yano-x-jvm-*' | head -1)"
  [ -n "$DIST" ]
}

check_layout() {
  local path launcher count=0
  for path in yano.jar yano.sh config/application-devnet.yml \
      examples/showcase/showcase.sh examples/showcase/yano/config/application-devnet.yml \
      appchain-cluster/cluster.sh tools/lib yano-x-plugin-pack-v1.json; do
    [ -e "$DIST/$path" ] || { echo "missing $path"; return 1; }
  done
  ls "$DIST"/plugins/*.jar >/dev/null || return 1
  for launcher in "$DIST"/tools/*/bin/*; do
    case "$launcher" in *.bat) continue;; esac
    # Yano X tools share tools/lib; Yano's own tools (yano-plugins) keep theirs.
    grep -q 'APP_HOME/\.\./lib/' "$launcher" || continue
    "$launcher" --help >/dev/null || { echo "launcher failed: $launcher"; return 1; }
    count=$((count + 1))
  done
  [ "$count" -gt 0 ] || { echo "no Yano X tool launchers found"; return 1; }
  echo "$count Yano X tool launchers answered --help"
}

runtime_acceptance() {
  YANO_ACCEPTANCE_HTTP_BASE="$((HTTP_BASE + 100))" \
    YANO_ACCEPTANCE_SERVER_BASE="$((SERVER_BASE + 100))" \
    bash "$REPO/tooling/devtools/src/test/scripts/final-distribution-runtime-acceptance.sh" "$ZIP"
}

stock_outcomes() {
  YANO_STOCK_ACCEPTANCE_HTTP_BASE="$((HTTP_BASE + 200))" \
    YANO_STOCK_ACCEPTANCE_SERVER_BASE="$((SERVER_BASE + 200))" \
    bash "$REPO/tooling/devtools/src/test/scripts/final-distribution-stock-outcomes.sh" \
    "$ZIP" "$REPO/config/application-appchain.yml"
}

showcase() {
  "$SHOWCASE" "$@" --instance "$INSTANCE" --data-dir "$DATA"
}

quickstart() {
  SHOWCASE_STARTED=true
  "$SHOWCASE" quickstart --profile light --nodes 3 --instance "$INSTANCE" --data-dir "$DATA" \
    --http-base "$HTTP_BASE" --server-base "$SERVER_BASE"
}

chain_status() {
  curl -fsS --max-time 10 "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$1/status"
}

record_tips() {
  local chain tip root chains
  chains="$(jq -er '.chainIds[]' "$DATA/$INSTANCE/showcase-identity.json")" || return 1
  : >"$WORK/tips-before-restart.txt"
  for chain in $chains; do
    tip="$(chain_status "$chain" | jq -er '.tipHeight')" || return 1
    [ "$tip" -gt 0 ] || continue
    root="$(curl -fsS --max-time 10 \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$chain/blocks/$tip" \
      | jq -er '.stateRoot')" || return 1
    printf '%s %s %s\n' "$chain" "$tip" "$root" >>"$WORK/tips-before-restart.txt"
  done
  [ -s "$WORK/tips-before-restart.txt" ] || { echo "no chain has produced a block"; return 1; }
  cat "$WORK/tips-before-restart.txt"
}

check_persisted_tips() {
  local chain tip root now
  while read -r chain tip root; do
    now="$(chain_status "$chain" | jq -er '.tipHeight')" || return 1
    [ "$now" -ge "$tip" ] || { echo "$chain went back from $tip to $now"; return 1; }
    [ "$(curl -fsS --max-time 10 \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$chain/blocks/$tip" \
      | jq -er '.stateRoot')" = "$root" ] || { echo "$chain block $tip changed root"; return 1; }
    echo "$chain tip $tip -> $now, root at $tip unchanged"
  done <"$WORK/tips-before-restart.txt"
}

check_projection_off() {
  local node
  for node in 0 1 2; do
    grep -q 'projection history is disabled' "$DATA/$INSTANCE/cluster/node$node/node.log" \
      || { echo "node$node did not start with the history projection disabled"; return 1; }
  done
}

step archive extract_archive
step layout check_layout
step runtime-acceptance runtime_acceptance
step stock-outcomes stock_outcomes

if [ "$SHOWCASE_GATES" = true ]; then
  SHOWCASE="$DIST/examples/showcase/showcase.sh"
  config_digest="$(shasum -a 256 "$DIST/config/application-appchain.yml")"
  step showcase-doctor "$SHOWCASE" doctor --profile light \
    --http-base "$HTTP_BASE" --server-base "$SERVER_BASE"
  step showcase-quickstart quickstart
  step showcase-projection-off check_projection_off
  if [ "$RUN_ALL" = true ]; then
    step showcase-run-all showcase run all
  fi
  step showcase-verify showcase verify
  step showcase-record-tips record_tips
  step showcase-restart showcase restart
  step showcase-verify-after-restart showcase verify
  step showcase-persisted-tips check_persisted_tips
  step distribution-config-unchanged \
    test "$config_digest" = "$(shasum -a 256 "$DIST/config/application-appchain.yml")"
fi

echo "PASS: $(basename "$ZIP") smoke gates"
