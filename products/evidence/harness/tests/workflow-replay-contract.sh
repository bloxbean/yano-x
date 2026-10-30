#!/usr/bin/env bash
# Static contract for workflow-replay-e2e.sh and its evidence-e2e.yml job. It runs without Docker
# containers: source assertions, a numeric-injection probe and a fake-docker image-collision probe.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
REPO_ROOT="$(cd "$DEMO_DIR/../../.." && pwd -P)"
SOURCE="$SCRIPT_DIR/workflow-replay-e2e.sh"
ROLE_SOURCE="$SCRIPT_DIR/role-workflow-e2e.sh"
FAILOVER_SOURCE="$SCRIPT_DIR/effect-failover-e2e.sh"
WORKFLOW="$REPO_ROOT/.github/workflows/evidence-e2e.yml"
TAG_WORKFLOW="$REPO_ROOT/.github/workflows/tag-release.yml"
RELEASE_CONTRACTS="$SCRIPT_DIR/release-contracts.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/yano-workflow-replay-contract.XXXXXX")"
TMP="$(cd "$TMP" && pwd -P)"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

[ -x "$SOURCE" ] || fail 'workflow-replay E2E must be executable'
bash -n "$SOURCE"

python3 - "$SOURCE" "$ROLE_SOURCE" "$FAILOVER_SOURCE" "$WORKFLOW" "$RELEASE_CONTRACTS" \
  "$TAG_WORKFLOW" <<'PY'
from pathlib import Path
import re
import subprocess
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
role_source = Path(sys.argv[2]).read_text(encoding="utf-8")
failover_source = Path(sys.argv[3]).read_text(encoding="utf-8")
workflow = Path(sys.argv[4]).read_text(encoding="utf-8")
release_contracts = Path(sys.argv[5]).read_text(encoding="utf-8")
tag_workflow = Path(sys.argv[6]).read_text(encoding="utf-8")


def listeners(text, prefix, assignment):
    ports = dict(re.findall(assignment % prefix, text))
    result = []
    for name in ('HTTP_BASE', 'UI_PORT', 'SERVER_BASE', 'KAFKA_PORT', 'S3_PORT',
                 'IPFS_PORT', 'PROMETHEUS_PORT', 'GRAFANA_PORT'):
        base = int(ports[name])
        result.extend(range(base, base + (3 if name.endswith('_BASE') else 1)))
    return result


# Default listeners: distinct, below the default Linux client-port range, and
# disjoint from the two other devnet E2Es so all three can share one Docker host.
replay = listeners(source, 'WORKFLOW_REPLAY', r'(\w+)="\$\{YANO_%s_\w+:-([0-9]+)\}"')
role = listeners(role_source, 'ROLE_WORKFLOW', r'export DEMO_(\w+)="\$\{YANO_%s_\w+:-([0-9]+)\}"')
failover = listeners(failover_source, 'EFFECT_FAILOVER',
                     r'export DEMO_(\w+)="\$\{YANO_%s_\w+:-([0-9]+)\}"')
if len(set(replay)) != len(replay) or not all(1024 <= port < 32768 for port in replay):
    raise SystemExit('replay default listeners must be distinct and below the default Linux client-port range')
if set(replay) & set(role) or set(replay) & set(failover):
    raise SystemExit('replay default listeners collide with another devnet E2E')
subnets = {
    'replay': re.search(r'YANO_WORKFLOW_REPLAY_SUBNET:-([0-9./]+)\}', source).group(1),
    'role': re.search(r'YANO_ROLE_WORKFLOW_SUBNET:-([0-9./]+)\}', role_source).group(1),
    'failover': re.search(r'YANO_EFFECT_FAILOVER_SUBNET:-([0-9./]+)\}', failover_source).group(1),
}
if len(set(subnets.values())) != 3:
    raise SystemExit(f'devnet E2E connector subnets must be distinct: {subnets}')
replay_prefix = subnets['replay'].rsplit('.', 1)[0] + '.'
for name in ('S3_IP', 'KUBO_IP', 'KAFKA_IP'):
    address = re.search(rf'YANO_WORKFLOW_REPLAY_{name}:-([0-9.]+)\}}', source).group(1)
    if not address.startswith(replay_prefix):
        raise SystemExit(f'replay connector {name} is outside its subnet')
guard = 'reject_ephemeral_listener() {' + source.split(
    'reject_ephemeral_listener() {', 1)[1].split('\nport_free() {', 1)[0]
for first, last, port, expected in ((32768, 60999, 48070, 1), (32768, 60999, 26070, 0),
                                    (20000, 60000, 26070, 1), (0, 0, 26070, 0)):
    result = subprocess.run(['bash', '-c', 'fail() { exit 1; };\n' + guard
                             + f'\nEPHEMERAL_FIRST={first}; EPHEMERAL_LAST={last}; '
                             + f'reject_ephemeral_listener {port}'], check=False)
    if result.returncode != expected:
        raise SystemExit('replay client-port overlap guard is incorrect')
if 'reject_ephemeral_listener "$port"' not in source:
    raise SystemExit('replay startup must apply its port-range guard')

for required in (
        '[ "${YANO_RUN_WORKFLOW_REPLAY_E2E:-false}" = true ]',
        'yano-workflow-replay-e2e-v1',
        'DATA_BASE="$ROOT/data"',
        'SECRET_BASE="$ROOT/secrets"',
        'RUNTIME_BASE="$ROOT/runtime"',
        'if len(set(resolved)) != 3:',
        'actual.parent != root',
        'info.st_uid != os.geteuid()',
        'stat.S_IMODE(info.st_mode) != 0o700',
        'require_decimal_range HTTP_BASE "$HTTP_BASE" 1 65533',
        'require_decimal_range SERVER_BASE "$SERVER_BASE" 1 65533',
        'require_decimal_range SCENARIO_TIMEOUT "$SCENARIO_TIMEOUT" 10 3600',
        'MACHINE_MODE="${YANO_WORKFLOW_REPLAY_MACHINE:-composite}"',
        'CONTINUATION_MODE="${YANO_WORKFLOW_REPLAY_CONTINUATION_MODE:-direct}"',
        'export DEMO_DEVNET_BLOCK_TIME_MILLIS=10000',
        'export DEMO_OBSERVABILITY=false',
        '--deployment compose',
        '--continuation "$CONTINUATION_MODE"',
        '--machine "$MACHINE_MODE"',
        'EXPECTED_STATE_MACHINE=evidence-registry',
        'EXPECTED_STATE_MACHINE=composite',
        'EXPECTED_STATE_MACHINE=role-evidence',
        'EXPECTED_WORKFLOW_CHECK=DIRECT_EVIDENCE_SUBMISSION',
        'EXPECTED_WORKFLOW_CHECK=COMPOSITE_EVIDENCE_RELEASE_WORKFLOW',
        'EXPECTED_WORKFLOW_CHECK=ROLE_GATED_EVIDENCE_RELEASE_WORKFLOW',
        'EXPECTED_CONTINUATION_CHECK=DIRECT_RESULT_CONTINUATION',
        'EXPECTED_CONTINUATION_CHECK=EXPLICIT_NOTIFY_CONTINUATION',
        "*) fail 'continuation mode must be exactly explicit or direct';;",
        "*) fail 'machine mode must be exactly standalone, composite, or role';;",
        'subnet_check=(python3 "$SUBNET_TOOL" subnet-check',
        'NODE_IMAGE="yano-adr013-replay-node:$RUN_TOKEN"',
        'RUNNER_IMAGE="yano-adr013-replay-runner:$RUN_TOKEN"',
        'export DEMO_YANO_IMAGE="$NODE_IMAGE"',
        'export DEMO_RUNNER_IMAGE="$RUNNER_IMAGE"',
        'printf \'header = "X-API-Key: %s"\\n\' "$api_key"',
        '--max-filesize "$max_bytes"',
        '/api/v1/plugin-operations/bundles?limit=100',
        'sample("yano_appchain_tip_height", f\'chain="{chain}"\')',
        'sample("yano_appchain_effects_open", f\'chain="{chain}"\')',
        'sample("yano_plugin_bundles", \'state="selected"\')',
        '.stats.executors == ["ipfs-pin", "kafka-publish", "objectstore-s3-object-put"]',
        'executor metric inventory is incorrect',
        'if not 21 <= len(samples) <= 48:',
        'and .attempts >= 1 and .successes >= 1 and .inFlight == 0',
        'and .chain.stateProofsVerified == 6 and .chain.effectProofsVerified == 3',
        'and .chain.finalityBundlesVerified == $finalityBundles',
        'and passed($continuationCheck)',
        'and passed($workflowCheck)',
        '.anchor.required == true and .anchor.portableLinkageVerified == true',
        '.anchor.memberObservedTransactionVisibleOnAllMembers == true',
        '.anchor.memberObservedDatumCommitmentVerified == true',
        'map(del(.tipHeight))',
        "changed consensus tip, state, membership, or governed profile",
        '/effects?fromHeight=0&limit=100',
        '== ["ipfs.pin", "kafka.publish", "object.put"]',
        'and ([.effects[].effectIdHashHex] | unique | length) == 3',
        'immediate replay changed the logical effect inventory',
        'retained restart/replay changed the logical effect inventory',
        "replay did not create exactly two attempt reports",
        "retained replay did not produce exactly three attempt reports",
        'immediate replay changed state or external receipts',
        'retained restart/replay changed state or external receipts',
        'retained restart/replay changed app-chain state or membership',
        'fresh isolated broker did not assign the first exact Kafka offset',
        'cmp -s "$ROOT/initial-artifacts.json" "$ROOT/restarted-artifacts.json"',
        'cmp -s "$ROOT/pre-restart-state.json" "$ROOT/restarted-state.json"',
        'max_file_bytes = 512 * 1024 * 1024',
        'while chunk := os.read(descriptor, 1024 * 1024):',
        'if consumed != before.st_size or identity(after) != identity(before):',
        'Bounded L1 sync/recovery diagnostics (no validation settings changed):',
        'Bounded script-anchor adoption diagnostics',
        'peerApplicationProgressAgeMillis,peerBodyFetchInProgress',
        '/api/v1/node/status',
        'logs --no-color --since 30m yano-0 yano-1 yano-2',
        'logs --no-color --tail 120 yano-0 yano-1 yano-2',
        'docker network ls --filter',
        'WARN: isolated test diagnostics retained after uncertain cleanup',
        'if [ "$uncertain" = true ] && [ "$status" -eq 0 ]; then status=1; fi',
):
    if required not in source:
        raise SystemExit(f"missing workflow-replay contract: {required}")
for forbidden in ('--deployment host', 'demo_host', 'activate_host', 'HOST_'):
    if forbidden in source:
        raise SystemExit(f"workflow replay must stay Compose-only: {forbidden}")

numeric_validation = source.index('require_decimal_range HTTP_BASE "$HTTP_BASE" 1 65533')
first_arithmetic = source.index('$((HTTP_BASE + 1))')
if numeric_validation >= first_arithmetic:
    raise SystemExit("numeric validation must precede Bash arithmetic")
if '-H "X-API-Key:' in source or '--header "X-API-Key:' in source:
    raise SystemExit("private API keys must never be placed in curl argv")
if source.count('[[ "$bytes" =~ ^[0-9]+$ ]]') != 1:
    raise SystemExit("bounded response validation must have one shared implementation")
sentinel = source.index('chmod 600 "$SENTINEL"')
early_trap = source.index('trap early_cleanup EXIT INT TERM', sentinel)
first_owned_failure = source.index('mkdir -m 700 "$DATA_BASE"', sentinel)
project = source.index('PROJECT_NAME="$(sed -n', first_owned_failure)
full_trap = source.index('trap cleanup EXIT INT TERM', project)
first_up = source.index('\ndemo up\n')
if not sentinel < early_trap < first_owned_failure < project < full_trap < first_up:
    raise SystemExit("ownership-safe cleanup must be armed before any owned preflight or startup can fail")
for required in (
        'remove_owned_images || uncertain=true',
        'WARN: isolated preflight diagnostics retained after uncertain cleanup',
        'NODE_IMAGE_CLAIMED=false',
        'NODE_IMAGE_CLAIMED=true',
        '[ "$claimed" = true ] || return 0',
        'actual_id="$(docker image inspect --format \'{{.Id}}\' -- "$image")"',
        'docker image rm "$image" >/dev/null 2>&1 || return 1',
        '! docker image inspect "$image" >/dev/null 2>&1'):
    if required not in source:
        raise SystemExit(f"preflight image cleanup is not fail-closed: {required}")
manifest = source[source.index("artifact_manifest() {"):source.index("verify_live_generation() {")]
if "read_bytes()" in manifest:
    raise SystemExit("artifact hashing regressed to an unbounded whole-file read")
if '{files: ., images: {node: $node, runner: $runner}}' not in manifest:
    raise SystemExit("the artifact manifest must bind the owned image identities")

for call, count, reason in (
        ("verify_live_generation ", 2, "ownership/inventory/metrics must be proven before and after restart"),
        ("run_and_capture ", 3, "the workflow must run once, replay immediately, and replay after restart"),
        ("assert_ui_latest ", 2, "the UI must be checked before and after retained restart"),
        ("capture_effect_inventory ", 3, "each run/replay generation must prove its exact logical effects"),
        ("capture_cluster_state ", 5, "every first/replay/restart generation must prove exact cluster state"),
        ("assert_read_only_preserved_cluster_state ", 2,
         "every immediate/retained replay must preserve exact cluster state"),
        ("assert_executor_activity ", 1, "the first run must prove live executor status and bounded metrics"),
        ("artifact_manifest ", 2, "artifacts must be captured before and after restart")):
    if source.count(call) != count:
        raise SystemExit(reason)
stable_start = source.index("stable_receipt_signature() {")
stable_end = source.index("capture_effect_inventory() {", stable_start)
stable_signature = source[stable_start:stable_end]
for dynamic_field in ("committedHeight", "stateRoot", "portableTransactionHashes",
                      "memberObservedAnchoredHeight", "memberObservedTransactionHash"):
    if dynamic_field in stable_signature:
        raise SystemExit(f"replay receipt signature includes dynamic observation field: {dynamic_field}")

markers = (
    'demo prepare',
    "capture_owned_image_ids || fail 'prepared Compose image tags are missing'",
    'demo config > "$ROOT/compose-preflight.yaml"',
    'PROJECT_NAME="$(sed -n',
    'trap cleanup EXIT INT TERM',
    'demo up',
    'verify_live_generation initial',
    'artifact_manifest "$ROOT/initial-artifacts.json"',
    'run_and_capture first',
    'assert_executor_activity first',
    'run_and_capture replay',
    '[ "$(attempt_reports)" -eq 2 ]',
    'assert_ui_latest pre-restart',
    "note 'Restarting the exact Compose stack from retained data'",
    'demo stop',
    'demo up',
    'verify_live_generation restarted',
    'artifact_manifest "$ROOT/restarted-artifacts.json"',
    'run_and_capture post-restart-replay',
    'capture_cluster_state post-restart-replay',
    'assert_ui_latest post-restart',
    '[ "$(attempt_reports)" -eq 3 ]',
    'qualification_diagnostics',
    'demo stop',
    'remove_owned_images',
    'remove_owned_root',
)
position = source.index('note "Starting isolated workflow-replay E2E')
for marker in markers:
    try:
        next_position = source.index(marker, position + 1)
    except ValueError as error:
        raise SystemExit(f"missing or misordered live workflow marker: {marker}") from error
    position = next_position

cleanup_start = source.index("\ncleanup() {") + 1
cleanup_end = source.index("\nwait_json() {", cleanup_start)
cleanup = source[cleanup_start:cleanup_end]
diagnostics = cleanup.index("qualification_diagnostics")
compose_cleanup = cleanup.index("demo stop")
uncertain_guard = cleanup.index('if [ "$uncertain" = false ]')
root_cleanup = cleanup.index("remove_owned_root")
if not diagnostics < compose_cleanup < uncertain_guard < root_cleanup:
    raise SystemExit("failure cleanup must collect diagnostics, stop Compose, then conditionally remove state")


def job_block(name, text=workflow):
    lines = text.splitlines()
    marker = f"  {name}:"
    try:
        start = lines.index(marker)
    except ValueError as error:
        raise SystemExit(f"workflow has no {name} job") from error
    end = len(lines)
    for index in range(start + 1, len(lines)):
        if re.fullmatch(r"  [a-z0-9][a-z0-9-]*:", lines[index]):
            end = index
            break
    return "\n".join(lines[start:end])


replay_job = job_block("workflow-replay")
for required in (
        "runs-on: ubuntu-24.04",
        "timeout-minutes: 90",
        "actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5",
        "actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9",
        "run: bash .github/scripts/prepare-yano-inputs.sh",
        "run: ./gradlew publishAllPublicationsToInternalRepository",
        "run: ./gradlew :distribution:jvm:prepareEvidenceDemoArtifacts",
        "run: products/evidence/harness/tests/workflow-replay-contract.sh",
        "YANO_RUN_WORKFLOW_REPLAY_E2E: 'true'",
        "YANO_WORKFLOW_REPLAY_MACHINE: composite",
        "YANO_WORKFLOW_REPLAY_CONTINUATION_MODE: direct",
        "YANO_WORKFLOW_REPLAY_TIMEOUT_SECONDS: '900'",
        "DEMO_ANCHOR_VISIBILITY_TIMEOUT_SECONDS: '900'",
        "DEMO_ANCHOR_ADOPTION_TIMEOUT_SECONDS: '900'",
        "run: products/evidence/harness/tests/workflow-replay-e2e.sh"):
    if required not in replay_job:
        raise SystemExit(f"mandatory workflow-replay CI contract is missing: {required}")
if replay_job.index("workflow-replay-contract.sh") > replay_job.index("workflow-replay-e2e.sh"):
    raise SystemExit("the static contract must gate the live workflow-replay run")
if workflow.count("run: products/evidence/harness/tests/workflow-replay-e2e.sh") != 1:
    raise SystemExit("mandatory workflow replay must have exactly one workflow invocation")
if "three\n# parallel jobs" not in workflow and "three parallel jobs" not in workflow:
    raise SystemExit("the evidence E2E header must describe its three parallel jobs")
if "actions/workflows/evidence-e2e.yml/runs" not in job_block("tag", tag_workflow):
    raise SystemExit("tagging a release no longer requires the workflow containing workflow replay")
if release_contracts.count("workflow-replay-contract.sh") != 1 \
        or 'bash "$SCRIPT_DIR/$test"' not in release_contracts:
    raise SystemExit("release-contracts.sh no longer invokes this static contract")
PY

ARITHMETIC_MARKER="$TMP/arithmetic-injection-executed"
MALICIOUS_HTTP_BASE='1+$(touch '"$ARITHMETIC_MARKER"')'
if YANO_RUN_WORKFLOW_REPLAY_E2E=true \
    YANO_WORKFLOW_REPLAY_HTTP_BASE="$MALICIOUS_HTTP_BASE" \
    "$SOURCE" >"$TMP/arithmetic-injection.out" 2>&1; then
  fail 'command-substitution-shaped numeric input unexpectedly passed'
fi
[ ! -e "$ARITHMETIC_MARKER" ] || fail 'shell arithmetic executed attacker-controlled input'
grep -Fq 'HTTP_BASE must be a canonical bounded decimal integer' \
  "$TMP/arithmetic-injection.out" \
  || fail 'numeric injection rejection diagnostic is missing'

for selection in 'MACHINE=host' 'CONTINUATION_MODE=implicit'; do
  if env YANO_RUN_WORKFLOW_REPLAY_E2E=true "YANO_WORKFLOW_REPLAY_$selection" \
      "$SOURCE" >"$TMP/selection.out" 2>&1; then
    fail "unsupported workflow-replay selection unexpectedly passed: $selection"
  fi
  grep -Eq 'FAIL: (machine|continuation) mode must be exactly' "$TMP/selection.out" \
    || fail "unsupported selection diagnostic is missing: $selection"
done

# Exercise early collision cleanup without touching the real Docker daemon.
FAKE_BIN="$TMP/fake-bin"
mkdir -m 700 "$FAKE_BIN"
cat > "$FAKE_BIN/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
last="${!#}"
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
case "${1:-}:${2:-}" in
  compose:version) exit 0 ;;
  image:inspect)
    if [[ "$last" == yano-adr013-replay-node:* ]]; then
      printf '%s\n' "$FAKE_IMAGE_ID"
      exit 0
    fi
    exit 1
    ;;
  image:rm) exit 1 ;;
  network:ls) exit 0 ;;
  network:inspect) exit 1 ;;
  *) printf 'unexpected fake docker invocation: %s\n' "$*" >&2; exit 2 ;;
esac
EOF
chmod 700 "$FAKE_BIN/docker"

COLLISION_PARENT="$TMP/collision-parent"
COLLISION_LOG="$TMP/collision-docker.log"
mkdir -m 700 "$COLLISION_PARENT"
: > "$COLLISION_LOG"
if PATH="$FAKE_BIN:$PATH" \
    FAKE_IMAGE_ID=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    FAKE_DOCKER_LOG="$COLLISION_LOG" YANO_RUN_WORKFLOW_REPLAY_E2E=true \
    YANO_WORKFLOW_REPLAY_TMPDIR="$COLLISION_PARENT" \
    "$SOURCE" >"$TMP/collision.out" 2>&1; then
  fail 'pre-existing random image-tag collision unexpectedly passed'
fi
grep -Fq 'random isolated image tag already exists:' "$TMP/collision.out" \
  || fail 'pre-existing random image-tag collision diagnostic is missing'
if grep -Eq '^image rm ' "$COLLISION_LOG"; then
  fail 'collision cleanup attempted to remove an unclaimed pre-existing image tag'
fi
[ -z "$(find "$COLLISION_PARENT" -mindepth 1 -maxdepth 1 -print -quit)" ] \
  || fail 'collision preflight retained a root despite making no owned image change'

printf '%s\n' 'PASS: isolated Compose workflow-replay E2E contract'
