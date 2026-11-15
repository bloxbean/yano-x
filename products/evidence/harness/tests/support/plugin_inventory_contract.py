#!/usr/bin/env python3
"""Exercise the evidence E2Es' real plugin guards without nodes or Docker."""

import copy
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


REQUIRED_BUNDLE = "org.yanoproject.x.app-ledger"
SCRIPTS = {
    "effect-failover-e2e.sh": (
        "assert_plugin_operations_all_nodes", "assert_metrics_all_nodes", "wait_cluster_agreement"
    ),
    "workflow-replay-e2e.sh": (
        "assert_plugin_inventory", "assert_metrics", "capture_cluster_state"
    ),
}


def source_manifests(repo):
    template = repo / "products/evidence/harness/config/templates/node-compose.properties.in"
    match = re.search(r"^yano\.plugins\.allow-list=(.*)$", template.read_text(), re.MULTILINE)
    if match is None:
        raise AssertionError("The evidence template must declare its optional plugin allow-list")
    optional = match.group(1).split(",")
    assert len(optional) == len(set(optional)) == 8
    assert REQUIRED_BUNDLE not in optional, "Required host activation is independent of the optional allow-list"
    manifests = {}
    for path in repo.glob("**/src/main/resources/META-INF/yano/plugins/*.json"):
        document = json.loads(path.read_text())
        if document["id"] in optional:
            assert document["id"] not in manifests, f"Duplicate optional manifest: {document['id']}"
            manifests[document["id"]] = document
    assert set(manifests) == set(optional)
    # Cardano and standalone roots intentionally share an ID. This Docker demo
    # uses the integrated Cardano root, so never pick a root by glob order.
    required_path = repo / "framework/cardano/src/main/resources/META-INF/yano/plugins" / f"{REQUIRED_BUNDLE}.json"
    required = json.loads(required_path.read_text())
    assert required["id"] == REQUIRED_BUNDLE
    manifests[REQUIRED_BUNDLE] = required
    assert len(manifests) == 9
    assert sum(len(value["contributions"]) for value in manifests.values()) == 31
    return manifests


def fixtures(manifests):
    fingerprint = "sha256:" + "a" * 64
    items = [{
        "id": name, "selected": True, "selectionStatus": "SELECTED",
        "lifecycle": "ACTIVE" if index % 2 else "VALIDATED",
        "health": "UP" if index % 2 else "UNKNOWN", "failure": {"code": "NONE"},
        "metricsStale": False, "contributionCount": len(manifest["contributions"]),
    } for index, (name, manifest) in enumerate(sorted(manifests.items()))]
    summary = {
        "catalogFingerprint": fingerprint, "generation": 1, "capturedAtEpochMillis": 1,
        "pluginApiMajor": 3, "pluginApiLevel": max(value["yanoApi"]["minLevel"] for value in manifests.values()),
        "totals": {"selectedBundles": len(items), "failedBundles": 0, "degradedBundles": 0, "staleSources": 0},
    }
    bundles = {"catalogFingerprint": fingerprint, "items": items, "nextAfter": None}
    metrics = ('yano_appchain_tip_height{chain="contract-chain"} 1\n'
               'yano_appchain_effects_open{chain="contract-chain"} 0\n'
               f'yano_plugin_bundles{{state="selected"}} {len(items)}\n')
    return [{"summary": copy.deepcopy(summary), "bundles": copy.deepcopy(bundles), "metrics": metrics}
            for _ in range(3)]


def function(source, name, following):
    start = source.index(f"{name}() {{")
    end = source.index(f"\n{following}() {{", start)
    return source[start:end]


STUBS = r'''
set -euo pipefail
DEMO_HTTP_BASE=38070
HTTP_BASE=38070
CHAIN_ID=contract-chain
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
scenario_failure_diagnostics() { :; }
fixture_node() {
  local url="$1" node
  for node in 0 1 2; do
    if [[ "$url" == "http://127.0.0.1:$((38070 + node))/"* ]]; then
      printf '%s' "$node"; return
    fi
  done
  fail "Unexpected fixture URL: $url"
}
wait_authenticated_json() {
  local node kind=summary
  node="$(fixture_node "$1")"
  [[ "$1" != *'/bundles?'* ]] || kind=bundles
  cp "$FIXTURE_DIR/node$node-$kind.json" "$3"
  jq -e "$2" "$3" >/dev/null
}
bounded_get() {
  local node
  node="$(fixture_node "$1")"
  cp "$FIXTURE_DIR/node$node-metrics.prom" "$2"
}
'''


def run_case(root, label, script, data, accepted):
    directory = root / label
    directory.mkdir()
    output = directory / "output"
    output.mkdir()
    for node, fixture in enumerate(data):
        for kind in ("summary", "bundles"):
            (directory / f"node{node}-{kind}.json").write_text(json.dumps(fixture[kind]))
        (directory / f"node{node}-metrics.prom").write_text(fixture["metrics"])
    env = os.environ.copy()
    env.update(ROOT=str(output), FIXTURE_DIR=str(directory))
    result = subprocess.run(["bash", "-c", STUBS + "\n" + script], env=env,
                            capture_output=True, text=True, timeout=20)
    if (result.returncode == 0) != accepted:
        raise AssertionError(f"{label}: expected {'acceptance' if accepted else 'rejection'}; "
                             f"exit={result.returncode}\n{result.stdout}\n{result.stderr}")


def main():
    repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[5]
    baseline = fixtures(source_manifests(repo))
    cases = 0
    with tempfile.TemporaryDirectory(prefix="yano-plugin-inventory-contract-") as temporary:
        root = Path(temporary)
        for filename, (inventory_name, metrics_name, following) in SCRIPTS.items():
            source = (repo / "products/evidence/harness/tests" / filename).read_text()
            inventory = function(source, inventory_name, metrics_name) + f"\n{inventory_name} contract\n"
            metrics = function(source, metrics_name, following) + f"\n{metrics_name} contract\n"
            prefix = filename.removesuffix(".sh")

            def check(name, accepted, mutate=None, metric=False):
                nonlocal cases
                data = copy.deepcopy(baseline)
                if mutate:
                    # A mutation on member 1 also proves the guard inspects all members.
                    mutate(data[1])
                run_case(root, f"{prefix}-{name}", metrics if metric else inventory, data, accepted)
                cases += 1

            check("exact-required-plus-optional", True)
            check("unselected-logging", True, lambda node: node["bundles"]["items"].insert(
                0, {"id": "org.yanoproject.plugins.logging", "selected": False, "contributionCount": 1}))
            check("missing-required", False, lambda node: node["bundles"]["items"].pop(0))
            check("extra-selected", False, lambda node: node["bundles"]["items"].append(
                dict(node["bundles"]["items"][0], id="org.yanoproject.x.unexpected")))
            check("duplicate-selected", False, lambda node: node["bundles"]["items"][1].update(id=REQUIRED_BUNDLE))
            for field, value in [("selected", False), ("selectionStatus", "DENIED"), ("lifecycle", "FAILED"),
                                 ("health", "DOWN"), ("metricsStale", True), ("contributionCount", 2)]:
                check(f"bad-{field}", False, lambda node, key=field, bad=value:
                      node["bundles"]["items"][0].update({key: bad}))
            check("failure-code", False, lambda node: node["bundles"]["items"][0]["failure"].update(code="FAILED"))
            check("pagination", False, lambda node: node["bundles"].update(nextAfter="more"))
            check("old-summary-count", False, lambda node: node["summary"]["totals"].update(selectedBundles=8))
            for field in ("failedBundles", "degradedBundles", "staleSources"):
                check(f"bad-{field}", False, lambda node, key=field: node["summary"]["totals"].update({key: 1}))
            check("old-api-level", False, lambda node: node["summary"].update(pluginApiLevel=7))
            check("wrong-api-major", False, lambda node: node["summary"].update(pluginApiMajor=4))
            check("malformed-fingerprint", False, lambda node: node["summary"].update(catalogFingerprint="bad"))
            check("snapshot-fingerprint", False, lambda node:
                  node["bundles"].update(catalogFingerprint="sha256:" + "b" * 64))

            def other_catalog(node):
                for kind in ("summary", "bundles"):
                    node[kind]["catalogFingerprint"] = "sha256:" + "b" * 64

            check("member-fingerprint", False, other_catalog)
            check("metrics-nine", True, metric=True)
            for value in ("8", "10", "NaN"):
                check(f"metrics-{value}", False, lambda node, bad=value: node.update(
                    metrics=node["metrics"].replace('state="selected"} 9', f'state="selected"}} {bad}')), metric=True)
            check("metrics-duplicate", False, lambda node: node.update(
                metrics=node["metrics"] + 'yano_plugin_bundles{state="selected"} 9\n'), metric=True)
            check("metrics-missing", False, lambda node: node.update(
                metrics='\n'.join(line for line in node["metrics"].splitlines()
                                  if not line.startswith("yano_plugin_bundles"))), metric=True)
    print(f"PASS: {cases} behavioral plugin inventory/metrics cases across both evidence E2Es")


if __name__ == "__main__":
    main()
