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
project="$work/project"
finish() {
  code=$?
  trap - EXIT
  for record in "$project"/run/node*.pid; do
    [ -f "$record" ] || continue
    # A failed boundary test must never leave a paused process immune to graceful shutdown.
    kill -CONT "$(cat "$record")" 2>/dev/null || true
  done
  if [ -x "$project/scripts/stop" ]; then "$project/scripts/stop" >/dev/null 2>&1 || true; fi
  for record in "$project"/run/node*.pid; do
    [ -f "$record" ] || continue
    if kill -0 "$(cat "$record")" 2>/dev/null; then
      echo "FAIL: acceptance process is still alive; retaining $work" >&2
      code=1
    fi
  done
  if [ "$code" -ne 0 ]; then
    echo "FAIL: topic acceptance; retained diagnostics in $work" >&2
    for log in "$project"/logs/*.log; do [ -f "$log" ] && tail -40 "$log" >&2; done
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
"$yano_home/yano.sh" appchain init --non-interactive --recipe declarative-composite \
  --network devnet --members 3 --membership governed --name topics-acceptance --chain-id topics-acceptance \
  --member-key 8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c \
  --member-key 8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394 \
  --member-key ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1 \
  --bindings "$work/bindings.yaml" --plugins-directory "$yano_home/plugins" \
  --http-port-base "$http_base" --server-port-base "$server_base" --output "$project" --format json >/dev/null
for node in 0 1 2; do
  byte="$(printf '%02d' "$((node + 1))")"
  seed=""
  for _ in $(seq 1 32); do seed="$seed$byte"; done
  printf '%s\n' "YANO_APPCHAIN_SIGNING_KEY=$seed" \
    "YANO_APPCHAIN_API_KEYS=$YANO_TOPICS_ACCEPTANCE_API_KEY" >"$project/secrets/node$node.env"
done
chmod 600 "$project"/secrets/node*.env
"$project/scripts/start" >/dev/null
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
echo 'PASS: final distribution topic CLI, framed commands, rules, policy activation, proofs, catch-up and restart'
