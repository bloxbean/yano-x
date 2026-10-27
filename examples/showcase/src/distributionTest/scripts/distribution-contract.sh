#!/usr/bin/env bash
# The showcase as extracted from the final JVM distribution: its files, and a real `prepare` that runs the
# release's own setup commands (new, request, seal, join) for three nodes without starting them.
set -euo pipefail
ZIP="${1:?Yano X JVM distribution zip required}"
WORK="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/yano-showcase-dist.XXXXXX")" && pwd -P)"
trap 'rm -rf "$WORK"' EXIT INT TERM
unzip -q "$ZIP" -d "$WORK"
set -- "$WORK"/yano-x-jvm-*
[ "$#" -eq 1 ] && [ -d "$1" ]
DIST="$1"
ROOT="$DIST/examples/showcase"
[ "$(find "$WORK" -mindepth 1 -maxdepth 1 -type d -print | wc -l | tr -d ' ')" = 1 ]
[ -x "$ROOT/showcase.sh" ]
for demo in master-demo load-test soak-test submit-message-curl submit-authenticated-map; do
  [ -x "$ROOT/demos/$demo.sh" ]
done
for tool in showcase_codec.py showcase_local.py loadtest.sh soaktest.sh; do [ -x "$ROOT/tools/$tool" ]; done
for doc in MASTER_DEMO MASTER_DEMO_CURL MESSAGE_SUBMISSION LOAD_AND_SOAK ANCHORING_DEMO JAVA_CLI_ROADMAP \
    PRESENTERS GOVERNANCE_DEMO PREPROD_ANCHORING CARDANO_HISTORY; do
  [ -f "$ROOT/docs/$doc.md" ]
done
for template in showcase history settlement; do [ -f "$ROOT/config/$template.template.yaml" ]; done
[ -f "$ROOT/config/settlement/executor.json" ]
[ -f "$ROOT/config/settlement/settlement-vault.script" ]
# The showcase carries no runtime or Yano configuration of its own: yano.jar, plugins, tools, and the
# evidence harness come from the enclosing distribution, and nothing ships a launcher of its own.
[ ! -e "$ROOT/yano" ] && [ ! -e "$DIST/appchain-cluster" ]
[ -x "$DIST/examples/evidence/demo.sh" ]
[ -f "$DIST/examples/evidence/runner.jar" ]
[ -x "$DIST/tools/yano-cardano-history/bin/yano-cardano-history" ]
! grep -R '/Users/satya/work/bloxbean/yano' "$ROOT" --include='*.sh' --include='*.md' >/dev/null
! find "$ROOT" -type f \( -name '*.skey' -o -name '*.seed' -o -name 'operator.seed' \) -print -quit | grep -q .
DIST_CONFIG_SHA="$(cd "$DIST/config" && find . -type f -exec shasum -a 256 {} + | sort | shasum -a 256)"
PROFILES="$("$ROOT/showcase.sh" profiles)"
HELP="$("$ROOT/showcase.sh" help)"
ROLE_DIGEST="$(java -cp "$DIST/yano.jar:$DIST/plugins/*" \
  org.yanoproject.x.evidence.profile.RoleEvidenceProfileCli \
  --chain evidence-chain-contract \
  --members 8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c,8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394,ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1 \
  --threshold 2 --storage-gate app-final --continuation explicit \
  --evidence-capacity 8)"
[[ "$ROLE_DIGEST" =~ ^[0-9a-f]{64}$ ]]
HISTORY_HELP="$("$DIST/tools/yano-cardano-history/bin/yano-cardano-history" --help)"
grep -q '^light' <<< "$PROFILES"
grep -q 'config show|paths|export' <<< "$HELP"
grep -q 'verify' <<< "$HISTORY_HELP"
grep -q 'member join 3' "$ROOT/docs/MASTER_DEMO.md"
grep -q 'POST.*messages' "$ROOT/docs/MASTER_DEMO_CURL.md"
grep -q 'load-test' "$ROOT/docs/LOAD_AND_SOAK.md"
grep -q 'showcase.sh anchor' "$ROOT/docs/ANCHORING_DEMO.md"
grep -q 'Python 3 standard library only' "$ROOT/README.md"
jq -e '.schemaVersion == 1 and .profileId == "light-v1" and (.chains | length) == 13' \
  "$ROOT/catalog/showcase-catalog-v1.json" >/dev/null
CARDANO_HISTORY_BUNDLE="$(find "$DIST/plugins" -maxdepth 1 -name 'yano-x-cardano-history-bundle-*.jar' -print -quit)"
unzip -p "$CARDANO_HISTORY_BUNDLE" META-INF/yano/plugins/org.yanoproject.x.cardano-history.json \
  | jq -e '
      .id as $id |
      $id == "org.yanoproject.x.cardano-history" and
      any(.contributions[]; .kind == "app-state-machine" and .name == "cardano-history") and
      any(.contributions[]; .kind == "domain-api" and .name == $id) and
      all(.contributions[]; .kind != "ui-extension")
    ' >/dev/null
! unzip -Z1 "$CARDANO_HISTORY_BUNDLE" | grep -q '^META-INF/yano/ui/'
! unzip -Z1 "$CARDANO_HISTORY_BUNDLE" \
  | grep -Eq '^(org/yanoproject/api/|org/yanoproject/x/(composite|stdlib)/|org/slf4j/)'
grep -q 'document-review-chain' "$ROOT/docs/CAPABILITY_CATALOG.md"

# The release's setup commands seal all thirteen showcase chains, in three applications, for three nodes.
"$ROOT/showcase.sh" prepare --instance distribution-contract --nodes 3 \
  --http-base 29770 --server-base 29370 >/dev/null
DATA="$ROOT/data/showcase/distribution-contract"
python3 - "$ROOT/catalog/showcase-catalog-v1.json" "$DATA" <<'PY'
import json, pathlib, sys
catalog = [row["chainId"] for row in json.loads(pathlib.Path(sys.argv[1]).read_text())["chains"]]
data = pathlib.Path(sys.argv[2])
sealed = {}
for app, folder in (("showcase", "coordinator"), ("history", "history"), ("settlement", "settlement")):
    sealed[app] = [chain["id"] for chain in
                   json.loads((data / folder / f"{app}.chain").read_text())["genesis"]["chains"]]
assert sealed["history"] == ["cardano-history-chain"], sealed
assert sealed["settlement"] == ["payment-chain-settlement"], sealed
assert sorted(sum(sealed.values(), [])) == sorted(catalog), sealed
for node in range(3):
    indexes = json.loads((data / f"node{node}" / "apps" / "chain-indexes.json").read_text())
    assert sorted(indexes) == sorted(catalog), indexes
PY
for node in 0 1 2; do
  [ "$(cat "$DATA/node$node/secrets/member.key")" = "$(python3 -c "print('%02x' % ($node + 1) * 32)")" ]
done
SETTLEMENT="$DATA/settlement/settlement.chain"
jq -e '.genesis.chains[0].settings as $s
  | $s["machines.eutxo.bridge.vault-address"] == "addr_test1wpwhmf5cd5pm9gsg5y8xnkyk3xue2u35ral098gd8u49g3gjpdqgr"
  and $s["machines.eutxo.bridge.withdrawal-address"] == "addr_test1vrf4896s3htkc8pzytgvvm07c2e489rtcg42f23zk5r2mjs8ge5ef"' \
  "$SETTLEMENT" >/dev/null
# A plugin validator is used only from an allow-listed bundle; the seal lists every bundle the node has.
jq -e '.genesis.settings["yano.plugins.allow-list"] | split(",")
  | index("org.yanoproject.x.authenticated-map-validators") != null' "$DATA/coordinator/showcase.chain" >/dev/null

# Both governed maps sealed with the GTIN plugin validator and the CDDL product schema.
for chain in authenticated-map-chain:mpf-blake2b256-v1 authenticated-map-jmt-chain:jmt-blake2b256-v1; do
  id="${chain%%:*}"
  jq -r --arg id "$id" '.genesis.chains[] | select(.id == $id)
    | .settings["machines.authenticated-map.genesis-cbor-hex"]' "$DATA/coordinator/showcase.chain" \
    > "$WORK/$id.hex"
  "$DIST/yano.sh" appchain state validators --genesis-file "$WORK/$id.hex" \
    | jq -e --arg id "$id" --arg profile "${chain#*:}" '
      .chainId == $id and .profile == $profile and .validatorCount == 2 and
      ([.collections[].id] | sort ==
        ["attachments", "canonical-events", "governed-catalog", "gtins", "products", "released-products"]) and
      any(.validators[]; .id == "product-v1" and .kind == "schema") and
      any(.validators[]; .id == "gtin-v1" and .kind == "plugin" and .providerId == "gs1-gtin-v1" and
        (.artifactClosureSha256 | test("^[0-9a-f]{64}$")))' >/dev/null
done

# Preparing an instance writes only under the instance; the distribution's configuration is untouched.
[ "$(cd "$DIST/config" && find . -type f -exec shasum -a 256 {} + | sort | shasum -a 256)" = "$DIST_CONFIG_SHA" ]
echo "PASS: the showcase in the JVM distribution seals its applications with the release's setup commands"
