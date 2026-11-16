#!/bin/bash
# Shared helpers for the app-chain QA harness (see qa/README.md). This is X's bounded copy of the
# generic process and HTTP helpers of Yano's qa/harness/common.sh (ADR-064 D8).
# Isolation rules: developer nodes commonly hold 7070/13337, 7079/18337 and the retained Yano X
# devnet showcase holds 7170-7172/14337-14339. The harness uses its own ports below, never kills
# by port, and only kills PIDs it started (tracked in $PIDFILE).

REPO=${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
QA_HOME=${QA_HOME:-$REPO/qa/work}
SP=${SP:-$QA_HOME}                      # runs/ and pids.txt for the current run
case "${JAR:-}" in
  /*) [ -f "$JAR" ] || { echo "JAR does not exist: $JAR" >&2; exit 2; } ;;
  *) echo "Set JAR to the absolute path of the integrated Yano X yano.jar (built by distribution/jvm)" >&2
     exit 2 ;;
esac
# The nodes run from the jar's distribution root, whose config/ holds the devnet profile and key files.
DIST=$(cd "$(dirname "$JAR")" && pwd)
[ -d "$DIST/config" ] || { echo "No config/ next to $JAR; use the jar inside an extracted distribution" >&2; exit 2; }
if [ -z "${JAVA:-}" ]; then
  if [ -n "${JAVA_HOME:-}" ]; then JAVA=$JAVA_HOME/bin/java; else JAVA=java; fi
fi

# Overridable for a manual run next to nodes that hold these ports (release-qa uses the defaults).
HTTP_A=${HTTP_A:-7371}
N2N_A=${N2N_A:-13641}
HTTP_B=${HTTP_B:-7372}
N2N_B=${N2N_B:-13642}

# Log lines that mean a class, resource or service provider could not be loaded.
LOADING_ERRORS='NoClassDefFoundError|ClassNotFoundException|UnsatisfiedLinkError|ServiceConfigurationError|InvalidDefinitionException'

PIDFILE=${PIDFILE:-$SP/pids.txt}
mkdir -p "$SP"

log() { echo "[$(date +%H:%M:%S)] $*"; }

track_pid() { echo "$1 $2" >> "$PIDFILE"; }

kill_tracked() {
  [ -f "$PIDFILE" ] || return 0
  while read -r pid name; do
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null
      for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
      kill -9 "$pid" 2>/dev/null
      log "killed $name ($pid)"
    fi
  done < "$PIDFILE"
  rm -f "$PIDFILE"
}

assert_ports_free() {
  for p in "$@"; do
    if lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "PORT $p IS BUSY" >&2; lsof -nP -iTCP:"$p" -sTCP:LISTEN >&2; return 1
    fi
  done
}

# start_yano <run_dir> <http_port> <n2n_port> <logfile> [extra -D args...]
# Runs with cwd=$DIST (the devnet profile resolves key files relative to it) but every
# mutable path (chainstate, history, log file) is pointed into run_dir.
start_yano() {
  local run=$1 http=$2 n2n=$3 out=$4; shift 4
  mkdir -p "$run"
  local common=(-Dquarkus.http.port=$http -Dyano.server.port=$n2n
    -Dyano.storage.path=$run/chainstate -Dyano.history.dir=$run/history
    -Dquarkus.log.file.path=$run/yano-file.log)
  cd "$DIST" || return 1
  "$JAVA" "${common[@]}" "$@" -jar "$JAR" > "$out" 2>&1 &
  YANO_PID=$!
  cd - >/dev/null
  track_pid $YANO_PID "yano-$http"
  log "started yano pid=$YANO_PID http=$http n2n=$n2n"
}

wait_ready() { # <http_port> <timeout_s>
  local t=${2:-60}
  for i in $(seq 1 "$t"); do
    curl -sf "http://localhost:$1/q/health/ready" >/dev/null 2>&1 && { log "ready on $1 after ${i}s"; return 0; }
    sleep 1
  done
  log "NOT READY on $1 after ${t}s"; return 1
}

yano_tip() { curl -s "http://localhost:$1/api/v1/node/tip"; }
