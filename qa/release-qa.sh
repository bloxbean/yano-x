#!/bin/bash
# App-chain release QA: runs the app-chain regression tests one at a time on isolated ports
# against the integrated Yano X jar, and writes results plus a report.
#
#   JAR=/absolute/path/yano.jar qa/release-qa.sh                    # every test
#   JAR=/absolute/path/yano.jar qa/release-qa.sh --only appchain-cluster
#   qa/release-qa.sh --list
#
# JAR is the yano.jar inside an extracted integrated Yano X JVM distribution (distribution/jvm);
# this script does not build it. Output: qa/results/<run-id>/{results.json,report.md,logs/,runs/}
# See qa/README.md.

REPO=$(cd "$(dirname "$0")/.." && pwd)
QA_DIR=$REPO/qa
# The harness ports are fixed for release QA (common.sh honours inherited values for manual runs).
unset HTTP_A N2N_A HTTP_B N2N_B HTTP_C N2N_C
export REPO

# id|title|timeout-minutes|command (run from $REPO; $OUT is expanded)
REGISTRY='appchain-cluster|Two-node cluster, proofs, L1 anchor, L1 rollbacks with the follower up and down|25|qa/harness/appchain-cluster.sh
appchain-extensions|Multi-chain, query, SSE, admin, rotation, snapshot|25|qa/harness/appchain-extensions.sh
appchain-rotation-governance|Rotating sequencer + governed membership|20|qa/harness/appchain-rotation-governance.sh
appchain-script-anchor|Script anchors + L1 deposit observations|25|qa/harness/appchain-script-anchor.sh
appchain-standalone|Standalone three-node app ledger: late join, restart, no Cardano state|15|qa/harness/appchain-standalone-cluster.sh "$OUT/runs/appchain-standalone"'
HARNESS_PORTS="7371 7372 13641 13642 9199 17771 17772 17773 23441 23442 23443"

usage() {
  cat <<'USAGE'
Usage: JAR=/absolute/path/yano.jar qa/release-qa.sh [options]

  --only IDS   run exactly these comma-separated test ids (see --list); default: all
  --out DIR    results directory (default: qa/results/<run-id>)
  --list       print the test registry and exit
  -h, --help   this message

Exit code: 0 when every test passed, 1 when a test did not pass, 2 on usage errors,
3 when pre-flight failed (missing tool, missing JAR or busy harness port).
USAGE
}

ONLY=""; OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --only) ONLY=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --list) printf '%s\n' "$REGISTRY" | awk -F'|' '{printf "%-30s %3s min  %s\n",$1,$3,$2}'; exit 0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

TIMEOUT=$(command -v gtimeout || command -v timeout) \
  || { echo "GNU timeout is required (brew install coreutils)" >&2; exit 2; }
contains() { case ",$1," in *",$2,"*) return 0 ;; esac; return 1; }
SELECTED=""
while IFS='|' read -r id _; do
  [ -z "$ONLY" ] || contains "$ONLY" "$id" || continue
  SELECTED="$SELECTED $id"
done <<< "$REGISTRY"
SELECTED=${SELECTED# }
for id in $(echo "$ONLY" | tr ',' ' '); do
  contains "$(echo $SELECTED | tr ' ' ',')" "$id" || { echo "Unknown test id: $id (see --list)" >&2; exit 2; }
done

RUN_ID=$(date +%Y%m%d-%H%M%S)
OUT=${OUT:-$QA_DIR/results/$RUN_ID}
mkdir -p "$OUT/logs" "$OUT/results" "$OUT/runs" || exit 2
OUT=$(cd "$OUT" && pwd)   # harness nodes run with cwd = the distribution root, so paths must be absolute
export OUT
PROGRESS=$OUT/progress.log
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$PROGRESS"; }

# ------------------------------------------------------------------ preflight ----
say "run $RUN_ID: $SELECTED"
MISSING=""
for t in java jq python3 curl lsof shasum; do command -v "$t" >/dev/null 2>&1 || MISSING="$MISSING $t"; done
[ -n "$MISSING" ] && { say "PREFLIGHT FAIL: missing tools:$MISSING"; exit 3; }
case "${JAR:-}" in
  /*) [ -f "$JAR" ] || { say "PREFLIGHT FAIL: JAR does not exist: $JAR"; exit 3; } ;;
  *) say "PREFLIGHT FAIL: set JAR to the absolute path of the integrated Yano X yano.jar"; exit 3 ;;
esac
export JAR
BUSY=""
for p in $HARNESS_PORTS; do lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 && BUSY="$BUSY $p"; done
[ -n "$BUSY" ] && { say "PREFLIGHT FAIL: harness ports busy:$BUSY"; exit 3; }
JAR_SHA256=$(shasum -a 256 "$JAR" | cut -d' ' -f1)
say "jar: $JAR ($JAR_SHA256)"

# ---------------------------------------------------------------------- tests ----
cd "$REPO" || exit 2
FAILED=0
for id in $SELECTED; do
  IFS='|' read -r _ title mins cmd <<< "$(printf '%s\n' "$REGISTRY" | grep "^$id|")"
  logf=$OUT/logs/$id.log; started=$(date +%Y-%m-%dT%H:%M:%S%z)
  say "START $id"
  t0=$(date +%s)
  # Each test gets its own PID file so a timeout can clean up exactly what it started.
  SP=$OUT PIDFILE=$OUT/runs/$id.pids "$TIMEOUT" --kill-after=60 "${mins}m" \
    bash -c "cd \"\$REPO\" && $cmd" > "$logf" 2>&1
  rc=$?
  ( SP=$OUT PIDFILE=$OUT/runs/$id.pids; . "$QA_DIR/harness/common.sh"; kill_tracked ) >> "$logf" 2>&1
  dur=$(( $(date +%s) - t0 ))
  verdict=$(grep -E '^VERDICT:' "$logf" | tail -1)
  reason=$(printf '%s' "$verdict" | sed -E 's/^VERDICT: [A-Z]+ *//; s/^\((.*)\)$/\1/')
  if [ "$rc" = 124 ] || [ "$rc" = 137 ]; then status=TIMEOUT; reason="exceeded ${mins} min"
  elif grep -q 'IS BUSY' "$logf" && [ "$rc" = 2 ]; then status=BLOCKED; reason="harness port busy"
  elif [ "${verdict#VERDICT: PASS}" != "$verdict" ]; then status=PASS
  elif [ -n "$verdict" ]; then status=FAIL
  elif [ "$rc" = 0 ]; then status=PASS
  else status=FAIL; reason=${reason:-"exit code $rc, no verdict line"}; fi
  [ "$status" = PASS ] || FAILED=1
  python3 - "$OUT/results/$id.json" "$id" "$title" "$status" "$reason" "$rc" "$dur" "$started" <<'PY'
import json, sys
out, id_, title, status, reason, rc, dur, started = sys.argv[1:9]
json.dump({"id": id_, "title": title, "status": status, "reason": reason, "exit_code": int(rc),
           "duration_s": int(dur), "started": started, "log": f"logs/{id_}.log"}, open(out, "w"), indent=2)
PY
  say "END   $id $status $(( dur / 60 ))m$(( dur % 60 ))s${reason:+ ($reason)}"
done

# --------------------------------------------------------------------- report ----
python3 - "$OUT" "$RUN_ID" "$(git rev-parse HEAD 2>/dev/null)" "$JAR" "$JAR_SHA256" <<'PY'
import json, pathlib, sys
out, run_id, commit, jar, sha = sys.argv[1:6]
out = pathlib.Path(out)
results = [json.loads(p.read_text()) for p in sorted((out / "results").glob("*.json"))]
json.dump({"run_id": run_id, "commit": commit, "jar": jar, "jar_sha256": sha, "tests": results},
          open(out / "results.json", "w"), indent=2)
lines = [f"# App-chain release QA {run_id}", "", f"- Commit: `{commit}`", f"- Jar: `{jar}`",
         f"- Jar SHA-256: `{sha}`", "", "| Test | Status | Duration | Reason | Log |",
         "| --- | --- | --- | --- | --- |"]
for r in results:
    d = r["duration_s"]
    lines.append(f"| `{r['id']}` | {r['status']} | {d // 60}m{d % 60:02d}s | {r['reason']} | `{r['log']}` |")
for r in results:
    log = out / r["log"]
    checks = [line.rstrip() for line in log.read_text(errors="replace").splitlines()
              if line.startswith(("  PASS", "  FAIL", "VERDICT:"))] if log.exists() else []
    lines += ["", f"## {r['id']}", "", "```text", *checks, "```"]
(out / "report.md").write_text("\n".join(lines) + "\n")
PY
say "DONE  report: $OUT/report.md"
exit $FAILED
