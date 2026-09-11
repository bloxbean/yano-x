#!/usr/bin/env bash
set -euo pipefail

archive="${1:?Usage: final-distribution-plugin-catalog-acceptance.sh YANO_ZIP}"
work="$(mktemp -d "${TMPDIR:-/tmp}/yano-plugin-catalog.XXXXXX")"

cleanup() {
  rm -rf "$work"
}
trap cleanup EXIT

unzip -q "$archive" -d "$work/distribution"
yano_home="$(find "$work/distribution" -mindepth 1 -maxdepth 1 -type d | head -1)"
manifest="$yano_home/yano-x-plugin-pack-v1.json"
catalog="$work/default-catalog.json"
cli="$yano_home/tools/yano-plugins/bin/yano-plugins"

"$cli" inspect --format json "$yano_home"/plugins/*.jar >"$catalog"

jq -e -n --slurpfile manifest "$manifest" --slurpfile catalog "$catalog" '
  ($manifest[0].bundles | map(select(.installMode == "default"))) as $expected
  | ($catalog[0].bundles | map(select(.selected and .source == "DIRECTORY"))) as $actual
  | ($expected | map(.bundleId) | sort) == ($actual | map(.id) | sort)
  and ($expected | map({key: .bundleId, value: (.contributions | sort)}) | from_entries)
      == ($actual | map({
          key: .id,
          value: (.contributions | map(.kind + "/" + .name) | sort)
        }) | from_entries)
  and ($expected | map({key: .bundleId, value: ("sha256:" + .sha256)}) | from_entries)
      == ($actual | map({key: .id, value: .digest}) | from_entries)
  and ($expected | length) == 17
  and ($manifest[0].bundles | map(select(.installMode == "optional")) | length) == 1
  and ([
      "org.yanoproject.x.stdlib",
      "org.yanoproject.x.kafka",
      "org.yanoproject.x.evidence-profile",
      "org.yanoproject.x.eutxo"
    ] - ($actual | map(.id)) | length) == 0
' >/dev/null

# The ZK eUTxO runtime is an alternative provider for eutxo-ledger. Validate
# its supported selection by replacing the standard runtime and its dependent
# Cardano bridge, without mutating the packaged default directory.
alternative=()
for jar in "$yano_home"/plugins/*.jar; do
  case "$jar" in
    *yano-x-eutxo-ledger-bundle*|*yano-x-eutxo-bridge-cardano-bundle*) ;;
    *) alternative+=("$jar") ;;
  esac
done
alternative+=("$yano_home"/optional-plugins/*yano-x-eutxo-zk-runtime-bundle*.jar)
"$cli" validate "${alternative[@]}" | grep -Fq 'bundles=16 selected=16'

echo "PASS: packaged/default directory catalog identity and optional eUTxO ZK selection"
