#!/usr/bin/env bash
set -euo pipefail

TARGET="${1:?target is required}"
INSTANCE="${2:?instance is required}"
HTTP_BASE="${3:?HTTP base is required}"
INSTANCE_ROOT="$TARGET/data/showcase/$INSTANCE"
# A deployment made by an earlier showcase keeps its identity marker and cluster/ stores; a current one has
# instance.json, sealed applications, and one node directory per node.
MARKER="$INSTANCE_ROOT/showcase-identity.json"
CATALOG="$TARGET/catalog/showcase-catalog-v1.json"

for tool in curl jq python3; do
  command -v "$tool" >/dev/null || {
    echo "Missing required tool: $tool" >&2
    exit 69
  }
done

if [ -f "$MARKER" ]; then
  CHAINS="$(jq -r '.chainIds[]' "$MARKER")"
  store_root() { printf '%s/cluster/node%d' "$INSTANCE_ROOT" "$1"; }
elif [ -f "$INSTANCE_ROOT/instance.json" ]; then
  CHAINS="$(python3 - "$CATALOG" "$INSTANCE_ROOT" <<'PY'
import json, pathlib, sys
catalog = [row["chainId"] for row in json.loads(pathlib.Path(sys.argv[1]).read_text())["chains"]]
root = pathlib.Path(sys.argv[2])
present = set()
for sealed in ("coordinator/showcase.chain", "history/history.chain", "settlement/settlement.chain"):
    path = root / sealed
    if path.exists():
        present.update(chain["id"] for chain in json.loads(path.read_text())["genesis"]["chains"])
print("\n".join(chain for chain in catalog if chain in present))
PY
)"
  store_root() { printf '%s/node%d/data' "$INSTANCE_ROOT" "$1"; }
else
  echo "Missing retained identity: $MARKER or $INSTANCE_ROOT/instance.json" >&2
  exit 66
fi
[ -f "$CATALOG" ] || { echo "Missing showcase catalog: $CATALOG" >&2; exit 66; }
[ -f "$TARGET/tools/showcase_catalog.py" ] || {
  echo "Missing packaged catalog tool" >&2
  exit 66
}

diff -u <(python3 "$TARGET/tools/showcase_catalog.py" "$CATALOG" list) <(printf '%s\n' "$CHAINS")

for NODE in 0 1 2; do
  NODE_ROOT="$(store_root "$NODE")"
  for STORE in chainstate appchain-chainstate appchain-indexers; do
    [ -d "$NODE_ROOT/$STORE" ] || {
      echo "Missing node$NODE $STORE root" >&2
      exit 66
    }
  done
  [ ! -e "$NODE_ROOT/chainstate/appchains" ] || {
    echo "Legacy app-chain index data remains inside node$NODE chainstate" >&2
    exit 65
  }
done

for PORT in "$HTTP_BASE" "$((HTTP_BASE + 1))" "$((HTTP_BASE + 2))"; do
  curl -fsS "http://127.0.0.1:$PORT/q/health/ready" \
    | jq -e '.status == "UP"' >/dev/null
done

while IFS= read -r CHAIN; do
  LEADER="$(curl -fsS \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$CHAIN/status")"
  printf '%s' "$LEADER" | jq -e \
    '.anchor.enabled == true and .anchor.mode == "script" and
     .anchor.bootstrapped == true and
     (.capabilityManifest.manifestDigest | test("^[0-9a-f]{64}$")) and
     (.anchor.lastAnchorTx | test("^[0-9a-f]{64}$"))' >/dev/null

  while IFS= read -r CAPABILITY; do
    printf '%s' "$LEADER" | jq -e --arg capability "$CAPABILITY" \
      '(.capabilityManifest.components | any(.id == $capability)) or
       (.capabilityManifest.crossCutting |
        any(.capabilityId == $capability and .enabled == true))' >/dev/null
  done < <(jq -r --arg chain "$CHAIN" \
    '.chains[] | select(.chainId == $chain) | .expectedCapabilities[]' "$CATALOG")

  REFERENCE=""
  for PORT in "$HTTP_BASE" "$((HTTP_BASE + 1))" "$((HTTP_BASE + 2))"; do
    STATUS="$(curl -fsS \
      "http://127.0.0.1:$PORT/api/v1/app-chain/chains/$CHAIN/status")"
    printf '%s' "$STATUS" | jq -e \
      '.running == true and .members == 3 and .threshold == 2 and
       (.stateRoot | test("^[0-9a-f]{64}$")) and
       (.stateCommitment.genesisId | test("^[0-9a-f]{64}$"))' >/dev/null
    CURRENT="$(printf '%s' "$STATUS" | jq -r \
      '[.tipHeight,.stateRoot,.stateCommitment.profile,
        .stateCommitment.genesisId,.capabilityManifest.manifestDigest] | @tsv')"
    [ -n "$REFERENCE" ] || REFERENCE="$CURRENT"
    [ "$REFERENCE" = "$CURRENT" ] || {
      echo "State disagreement for $CHAIN on HTTP $PORT" >&2
      exit 1
    }
  done

  printf '%-32s %s\n' "$CHAIN" \
    "$(printf '%s' "$LEADER" | jq -r \
      '"profile=" + .stateCommitment.profile +
       " tip=" + (.tipHeight|tostring) +
       " anchored=" + (.anchor.lastAnchoredHeight|tostring)')"
done < <(printf '%s\n' "$CHAINS")

for CHAIN in payments-chain payment-chain-settlement; do
  if grep -Fxq "$CHAIN" <<< "$CHAINS"; then
    INDEX_STATUS="$(curl -fsS \
      "http://127.0.0.1:$HTTP_BASE/api/v1/plugins/org.yanoproject.x.eutxo.indexer/index/v1/status?chain=$CHAIN")"
    printf '%s' "$INDEX_STATUS" | jq -e \
      '.projection.status != "FAILED" and
       .projection.indexedHeight <= .projection.finalizedHeight' >/dev/null
  fi
done

echo "PASS: all configured chains agree across three nodes and have bootstrapped leader anchors"
