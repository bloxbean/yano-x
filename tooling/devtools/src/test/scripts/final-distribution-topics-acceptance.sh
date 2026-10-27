#!/usr/bin/env bash
# ADR-038 MX3/MX4: the shipped CLI, SDK and plugin bundles on a fresh three-member local devnet.
set -euo pipefail
archive="${1:?Usage: final-distribution-topics-acceptance.sh YANO_X_ZIP}"
script_dir="$(cd "$(dirname "$0")" && pwd)"
http_base="${YANO_TOPICS_ACCEPTANCE_HTTP_BASE:-28870}"
server_base="${YANO_TOPICS_ACCEPTANCE_SERVER_BASE:-28470}"
for tool in java curl lsof unzip python3 shasum; do command -v "$tool" >/dev/null; done
archive_sha="$(shasum -a 256 "$archive" | cut -d ' ' -f 1)"
echo "Topic acceptance archive SHA-256: $archive_sha"
for base in "$http_base" "$server_base"; do
  for offset in 0 1 2; do
    port=$((base + offset))
    if { [ "$port" -ge 7170 ] && [ "$port" -le 7172 ]; } \
        || { [ "$port" -ge 14337 ] && [ "$port" -le 14339 ]; } \
        || lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "FAIL: port $port is retained or occupied" >&2; exit 1
    fi
  done
done
# Test processes must not inherit another deployment's data, network or credentials.
while read -r name; do unset "$name"; done < <(env | sed -n 's/^\(\(YANO\|QUARKUS\)_[A-Za-z0-9_]*\)=.*/\1/p')
work="$(mktemp -d "${TMPDIR:-/tmp}/yano-topics-acceptance.XXXXXX")"
nodes="$work/nodes"
coordinator="$work/coordinator"
operators=(alice bob carol)
yano_home=""
finish() {
  code=$?
  trap - EXIT
  for node in "$nodes"/node*; do
    [ -f "$node/run/node.pid" ] || continue
    # A failed boundary test must never leave a paused process immune to graceful shutdown.
    kill -CONT "$(cut -d ' ' -f 1 "$node/run/node.pid")" 2>/dev/null || true
    [ -n "$yano_home" ] && "$yano_home/yano.sh" appchain stop --node-dir "$node" >/dev/null 2>&1 || true
    if [ -f "$node/run/node.pid" ] && kill -0 "$(cut -d ' ' -f 1 "$node/run/node.pid")" 2>/dev/null; then
      echo "FAIL: acceptance process is still alive; retaining $work" >&2
      code=1
    fi
  done
  if [ "$code" -ne 0 ]; then
    echo "FAIL: topic acceptance; retained diagnostics in $work" >&2
    for log in "$nodes"/node*/logs/*.log; do [ -f "$log" ] && tail -40 "$log" >&2; done
  elif [ "${KEEP_WORK:-false}" != true ]; then
    rm -rf "$work"
  else
    echo "Acceptance diagnostics: $work"
  fi
  exit "$code"
}
trap finish EXIT
unzip -q "$archive" -d "$work/distribution"
yano_home="$(find "$work/distribution" -mindepth 1 -maxdepth 1 -type d | head -1)"
export YANO_HOME="$yano_home"
export YANO_TOPICS_ACCEPTANCE_API_KEY=topics-local-acceptance-operator
driver="$script_dir/TopicPolicyAcceptance.java"
java -cp "$yano_home/tools/lib/*" "$driver" prepare "$work"
appchain() { "$yano_home/yano.sh" appchain "$@"; }

# The ADR-056 setup ceremony with the shipped CLI. The draft enables topic policies at genesis, with the
# acceptance owner as the only registrar and short limits; three operators request, seal, join and start.
mkdir -p "$coordinator" "$nodes"
appchain new topics-acceptance --network devnet --operators "$(IFS=,; echo "${operators[*]}")" \
  --chain "topics-acceptance=bindings:$work/bindings.yaml" \
  --topics "topics-acceptance=registrars:$(cat "$work/owner.pub")" --output "$coordinator" --yes >/dev/null
python3 - "$coordinator/topics-acceptance.draft.yaml" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
marker = "      unregistered: open"
if marker not in text:
    raise SystemExit("draft has no topics block")
limits = ("\n      limits:\n        max-command-ms: 3600000\n        max-entries: 1000\n"
          "        max-entries-per-namespace: 100\n        activation-lag: 2")
open(path, "w", encoding="utf-8").write(text.replace(marker, marker + limits, 1))
PY
for node in 0 1 2; do
  appchain request "$coordinator/topics-acceptance.draft.yaml" --name "${operators[$node]}" --host 127.0.0.1 \
    --node-port "$((server_base + node))" --http-port "$((http_base + node))" --node-dir "$nodes/node$node" \
    --key "$work/member-$node.key" --output "$coordinator" --yes >/dev/null
  # One known operator credential lets the driver read every node; each node still holds its own copy.
  printf '%s\n' "$YANO_TOPICS_ACCEPTANCE_API_KEY" >"$nodes/node$node/secrets/api.key"
  chmod 600 "$nodes/node$node/secrets/api.key"
done
appchain seal "$coordinator/topics-acceptance.draft.yaml" >/dev/null
# Operators confirm the fingerprint they compared with the coordinator.
fingerprint="$(appchain inspect "$coordinator/topics-acceptance.chain" --json \
  | python3 -c 'import json, sys; print(json.load(sys.stdin)["displayFingerprint"])')"
for node in 0 1 2; do
  appchain join "$coordinator/topics-acceptance.chain" --expect-fingerprint "$fingerprint" \
    --node-dir "$nodes/node$node" --yes >/dev/null
done
for node in 0 1 2; do appchain start --node-dir "$nodes/node$node" >/dev/null; done
java -cp "$yano_home/tools/lib/*" "$driver" run "$work" "$yano_home" "$http_base"
python3 - "$work/result.json" "$archive_sha" <<'PY'
import json, sys
path, sha = sys.argv[1:]
with open(path, encoding='utf-8') as stream:
    value = json.load(stream)
value['archiveSha256'] = sha
with open(path, 'w', encoding='utf-8') as stream:
    json.dump(value, stream, indent=2)
    stream.write('\n')
print(json.dumps(value, separators=(',', ':')))
PY
echo 'PASS: final distribution topic setup, CLI votes, framed commands, rules, activation, proofs, catch-up, restart'
