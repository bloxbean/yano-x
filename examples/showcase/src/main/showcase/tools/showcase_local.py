#!/usr/bin/env python3
"""Write a showcase node's config/local.yaml: the settings only that node runs.

The sealed applications carry every consensus setting; `yano.sh appchain start` refuses a local file that
changes one. This file adds node-local roles only: the workflow outbox executor and the settlement
executor's ownership on node 0, snapshot archive storage, and L1 snapshot retention. It is written as JSON,
which every YAML reader accepts, and chains are addressed by the indexes `join` assigned
(apps/chain-indexes.json).
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import tempfile

WORKFLOW = "workflow-chain"
SETTLEMENT = "payment-chain-settlement"


def put(tree: dict, dotted: str, value) -> None:
    node = tree
    parts = dotted.split(".")
    for part in parts[:-1]:
        node = node.setdefault(part, {})
    node[parts[-1]] = value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--node", type=int, required=True)
    parser.add_argument("--node-dir", required=True)
    parser.add_argument("--outbox", required=True)
    parser.add_argument("--config-dir", required=True)
    parser.add_argument("--settlement-executor")
    parser.add_argument("--snapshot-retention-epochs")
    parser.add_argument("--snapshot-archives")
    parser.add_argument("--snapshot-mpf-pruning")
    args = parser.parse_args()

    node_dir = pathlib.Path(args.node_dir)
    indexes = json.loads((node_dir / "apps" / "chain-indexes.json").read_text(encoding="utf-8"))
    lead = args.node == 0
    chains: dict = {}

    def chain(chain_id: str) -> dict:
        return chains.setdefault(f"chains[{indexes[chain_id]}]", {})

    if WORKFLOW in indexes:
        settings = chain(WORKFLOW)
        put(settings, "effects.executor.enabled", lead)
        put(settings, "effects.executor.tick-ms", 250)
        put(settings, "effects.executors.showcase-outbox.enabled", lead)
        if lead:
            put(settings, "effects.executors.showcase-outbox.directory", str(pathlib.Path(args.outbox).resolve()))
    if args.settlement_executor and SETTLEMENT in indexes:
        settings = chain(SETTLEMENT)
        executor = json.loads(pathlib.Path(args.settlement_executor).read_text(encoding="utf-8"))
        for key, value in executor.items():
            if key.endswith("-script-file") and not os.path.isabs(value):
                value = str((pathlib.Path(args.config_dir) / value).resolve())
            put(settings, key, value)
        # Every node co-signs settlement rounds; exactly one owns the effect.
        put(settings, "effects.executors.eutxo-settlement.owner", lead)
    if args.snapshot_archives and args.snapshot_archives in indexes:
        put(chain(args.snapshot_archives), "capabilities.authenticated-snapshots.archive-directory",
            str((node_dir / "data" / "appchain-snapshot-archives").resolve()))
    if args.snapshot_mpf_pruning and args.snapshot_mpf_pruning in indexes:
        put(chain(args.snapshot_mpf_pruning), "capabilities.authenticated-snapshots.storage.mpf-pruning-enabled", True)

    yano: dict = {"app-chain": chains}
    # The showcase reads no L1 history, and the JVM ZIP loads DuckLake only on linux_amd64 (bloxbean/yano#137).
    put(yano, "history.projection.enabled", False)
    if args.snapshot_retention_epochs:
        put(yano, "account-state.snapshot-retention-epochs", int(args.snapshot_retention_epochs))

    target = node_dir / "config" / "local.yaml"
    descriptor, temporary = tempfile.mkstemp(dir=target.parent, prefix=".local.", suffix=".yaml")
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write("# Written by showcase.sh for this node only; sealed settings cannot be changed here.\n")
        json.dump({"yano": yano}, stream, indent=2)
        stream.write("\n")
    os.chmod(temporary, 0o600)
    os.replace(temporary, target)


if __name__ == "__main__":
    main()
