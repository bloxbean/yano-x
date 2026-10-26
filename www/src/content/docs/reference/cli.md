---
title: CLI reference
description: The ./yano.sh appchain command surface — setting up an application with other operators, the single-host cluster, configuration, state and proofs, bindings, identity tools, and plugin tooling.
sidebar:
  order: 1
---

`./yano.sh` is the public CLI, shipped inside the Yano X JVM distribution.
There is no separate `yano-x` executable. Run it from the directory that
contains it. Yano's tooling calls an app ledger an *app chain*, so every
command lives under `appchain`.

```bash
cd ~/yano-x/yano-x-jvm-*
./yano.sh appchain help
```

`./yano.sh appchain cluster …` runs the bundled single-host launcher. The
setup commands below set up and run an application with other operators.
Every other `appchain` command runs the offline developer tools. Read command help
from the **version-matched** executable when an option is uncertain: the
binary you run is the authority for its version.

## Discovery

```bash
./yano.sh appchain recipes                    # release-pinned recipes
./yano.sh appchain capabilities               # support tier, scope, selection
./yano.sh appchain capabilities --format json # canonical structured catalog
```

## Set up and run an application

These commands set up an application that several operators run together,
each on their own node. Only public files travel between them: the draft, the
member cards, and the sealed `.chain` file, sent by hand or carried by join
links. [Set up an application](/deployment/configure/) walks through them.

```bash
./yano.sh appchain try                                   # rehearse every step on one machine
./yano.sh appchain new supply                            # coordinator: write supply.draft.yaml
./yano.sh appchain request supply.draft.yaml             # each operator: key + alice.member
./yano.sh appchain seal supply.draft.yaml                # coordinator: write supply.chain
./yano.sh appchain join supply.chain                     # each operator, after comparing the fingerprint
./yano.sh appchain start
./yano.sh appchain status supply
./yano.sh appchain approve dave.member                   # each existing operator, to add a member
./yano.sh appchain anchor supply                         # optional, one operator
```

With join links, the coordinator serves the draft instead of sending files,
and each operator's `request` sends the card, waits for the seal and joins:

```bash
./yano.sh appchain invite supply.draft.yaml --serve --host coord.example.com  # coordinator: one link per operator
./yano.sh appchain request 'yano://coord.example.com:7099/<token>#<invite>'   # each operator: card, seal, join
```

| Command | Use it to | Prints on success |
|---|---|---|
| `try [--dir <dir>] [--clean]` | Run every step on one machine with three simulated operators; `--clean` stops and deletes the rehearsal. | `✓ Message … is final at height N on every node` |
| `new [<app>]` | Write `<app>.draft.yaml` with a fresh invite code. Without flags, a wizard asks the questions. | `✓ Wrote ./<app>.draft.yaml` |
| `invite <app>.draft.yaml --serve` | Print one `yano://` join link per operator and serve the draft, the cards, and the sealed file until every operator has it. Port `7099` must be reachable from the operators. | One `request` line per operator |
| `request <app>.draft.yaml\|<app>.chain\|<link>` | Make this node's key (if it has none) and its member card. Against a `.chain` file, it asks to join later. With a join link, it fetches the draft, sends the card, waits for the seal, saves `<app>.chain`, and in a terminal continues into `join`; running it again resumes. | `✓ Wrote ./<name>.member  (key id …)` |
| `seal <app>.draft.yaml [<card>.member …]` | Check the cards beside the draft and write `<app>.chain`. Exit code 3 while a card is missing. For a `role-approvals` or `role-evidence` chain it also writes `<chainId>.role-plan.yaml` beside the sealed file and prints the `role bootstrap --plan` step. | `✓ Sealed …/<app>.chain` and the chain fingerprint |
| `join <app>.chain` | Check the sealed file, confirm the fingerprint, and write this node's configuration. On Linux, also write a systemd unit. | `✓ Joined <app> (<fingerprint>)` |
| `start`, `stop` | Run or stop this node. `stop` keeps all data. | `✓ Node <name> is up at …` |
| `status [<app>\|<app>.draft.yaml]` | Show the stage, every member's state, each chain's height, agreement and anchoring, this node's Cardano view (sync, or the devnet genesis), and its plugin catalog fingerprint. Members compare the last two. | The stage ladder and a `Next:` line |
| `approve <name>.member`, `approve --remove <name>` | Add or remove a member on every chain, raising or lowering the threshold safely, then restart this node once. | `✓ <chain>: <name> is a member` |
| `anchor [<app>]` | Make this node the anchor leader of a chain: create the wallet, wait for funds, restart. `--stop` gives the role up. | `✓ This node anchors <chain>` |
| `inspect <file>` | Show and check a draft, a member card, or a sealed file. `--map-genesis <chain>` prints an authenticated-map chain's genesis CBOR hex instead. | The summary |

Common options:

- `new`: `--network devnet|preview|preprod|mainnet`, `--operators alice,bob,carol`
  (the first is the lead), `--chain <id>=<recipe>` or `--chain <id>=bindings:<file>`
  (repeatable), `--answer <chain>.<name>=<value>` (repeatable; the wizard asks
  for any answer a recipe still needs), `--threshold majority|two-thirds|all|<n>`,
  `--membership governed|static`, `--sequencing fixed|rotating` (default
  `fixed`: the lead proposes every block), `--output <dir>`, `--force`, `--yes`.
- `invite`: `--serve` (required), `--host <public-host>`, `--port` (default
  `7099`), `--expires-hours` (default `24`), `--seal` (seal without asking once
  every card is in).
- `request`: `--name`, `--host <public-host>`, `--node-port` (default `13337`),
  `--http-port` (default `8080`), `--key <seed-file>`, `--output <dir>`,
  `--no-wait` (with a link: send the card and return), `--yes`.
- `join`: `--expect-fingerprint <fp>` (required with `--yes`), `--listen <port>`,
  `--peer <name>=<host:port>` (repeatable), `--replace`, `--accept-bundles`.
- `start`: `--timeout <seconds>`, `--foreground`.
- `status`: `--json`, `--wait live --timeout <seconds>`.
- `inspect`: `--json`, `--map-genesis <chain>`.
- `approve`: `--heartbeat`, `--timeout <seconds>`, `--yes`.
- `anchor`: `--chain <id>`, `--stop`, `--take-over`, `--ada <devnet-funding>`,
  `--timeout <seconds>`, `--yes`.
- Every command that acts on a node takes `--node-dir` (default `./yano-node`).
  Node-only settings go in that directory's `config/local.yaml`, which `join`
  creates and never rewrites; `start` refuses it if it sets a sealed consensus
  key.

Exit codes: `0` success, `1` failure, `2` usage error, `3` not ready yet or
timed out. Every command ends with a `Next:` line.

## Configuration tools

These commands work on configuration files directly.

```bash
# A YAML file checked against the metadata, optionally against a template contract.
./yano.sh appchain config validate --mode template \
  --template-contract builtin:cluster config/application-appchain.yml

# The values a node would resolve from one or more files; later files win.
./yano.sh appchain config validate --mode resolved --config node.yml
./yano.sh appchain config effective --mode resolved --config shared.yml --config node.yml --show-sources

# What one property means, and its scope and change policy.
./yano.sh appchain config explain yano.app-chain.block.max-bytes
./yano.sh appchain config explain --format json yano.app-chain.block.max-bytes
```

`config effective` prints YAML by default and redacts secret values. Both
resolved commands leave out environment variables and system properties unless
you pass `--include-environment` or `--include-system-properties`.
`--metadata <descriptor|plugin.jar>` adds a plugin's configuration metadata to
`validate`, `effective`, and `explain`.

## Cluster (single host)

`appchain cluster` runs N members as processes on one machine: a disposable
demo, a repeatable integration test, or a controlled single-host deployment.
It hosts the chains in `config/application-appchain.yml`.

```bash
export YANO_CLUSTER_DIR=/tmp/yano-demo

./yano.sh appchain cluster start 3            # 3-member self-contained devnet
./yano.sh appchain cluster status             # tips, roots, and per-chain agreement
./yano.sh appchain cluster submit orders-chain orders '{"id":1}'
./yano.sh appchain cluster submit orders-chain orders '{"id":2}' --node 1
./yano.sh appchain cluster kv registry-chain set supplier-42 active --node 1
./yano.sh appchain cluster effect demo        # emit, execute, and prove one effect
./yano.sh appchain cluster loadtest orders-chain -n 500 -c 10 -s 256
./yano.sh appchain cluster logs 1 -f          # follow node 1's log
./yano.sh appchain cluster stop               # stop, keep data
```

| Command | Effect |
|---|---|
| `start [N] [options]` | Start an N-node cluster; N defaults to 3 and may be at most 32. |
| `status` | Health, per-chain tips and roots, and `AGREED` or `MISMATCH` per chain. |
| `submit <chain> <topic> <payload> [--node i] [--count n]` | Submit text messages through node `i`. |
| `kv <chain> set <key> <value> [--node i]`, `kv <chain> del <key> [--node i]` | Write to or delete from a `kv-registry` chain. |
| `loadtest <chain> [-n M] [-c C] [-s bytes] [--spread]` | Parallel load test that reports submit and finalize rates. |
| `effect demo ["message"]` | Emit, execute, and prove one demo effect on `effects-chain`. |
| `node join <index>`, `node resume <index>` | Govern, start, and catch up a later member; restart a joined member. |
| `member add <public-key>` | Governance-only member addition across the configured chains. |
| `threshold set <n>` | Govern a new finality threshold on every chain. |
| `anchor-bootstrap <chain-id>` | One-time script-anchor bootstrap for a chain; devnet funds the wallet first. |
| `logs <node> [-f]` | Show the last 60 lines of a node's log, or follow it. |
| `keys [N]` | Print the deterministic demo seeds and member keys. |
| `chains` | List the chains in the configuration. |
| `stop` | Stop the nodes and **keep** all data. |
| `clean` | Stop the nodes and **delete** `YANO_CLUSTER_DIR`. |

`start` options: `--network devnet|preprod|preview|mainnet|sanchonet`,
`--jar` or `--native`, `--threshold <t>` (default `N/2 + 1`),
`--transport shared|dedicated`, `--anchor`, `--anchor-mode metadata|script`,
`--anchor-chain <id>` (repeatable), `--anchor-key <hex>`, `--anchor-every <n>`,
`--data-dir <dir>`, `--http-base <port>` (default `7070`), and
`--server-base <port>` (default `13337`).

The node count, threshold, and network are part of a retained cluster's
identity. Starting the same directory with different values fails; use a new
`YANO_CLUSTER_DIR` instead.

:::caution
Never point `clean` at a deployment you care about. Retained clusters are
identity and data, not disposable test output.
:::

### Cluster environment

| Variable | Meaning | Default |
|---|---|---|
| `YANO_CLUSTER_DIR` | Where cluster state lives. | `/tmp/yano-appchain-cluster` |
| `YANO_CLUSTER_HTTP_BASE`, `YANO_CLUSTER_SERVER_BASE` | Port bases. Set them and they are strict. | `7070`, `13337` |
| `YANO_HOME` | The tree holding `config/`; nodes launch with this as cwd. | The distribution root |
| `YANO_JAR` / `YANO_NATIVE` | Explicit binary path. | Auto-detected under `YANO_HOME` |
| `YANO_CLUSTER_API_KEY` | Key for privileged operations. | `yano-local-cluster-full-key` |
| `YANO_CLUSTER_NODE_CONFIG_DIR` | Private per-node configuration overlays. | Unset |
| `YANO_CLUSTER_MEMBER_KEY_DIR` | Operator-supplied member keys. | Unset |
| `YANO_CLUSTER_ANCHOR_CHAINS` | Chains to anchor, comma-separated. | Every chain, when anchoring is on |
| `YANO_CLUSTER_ANCHOR_KEY_FILE` | File holding the anchor wallet seed. | Unset |
| `YANO_CLUSTER_PRIVATE_CONFIG_DIR` | Where generated private overlays are written. | `<data-dir>/private-config` |
| `YANO_CLUSTER_DEVNET_GENESIS_FILE` | A fixed devnet genesis for a fresh cluster. | Unset |

The launcher keeps reads and submissions public on its loopback-only HTTP API,
and requires the full key for privileged calls: admin, effect-worker, and
plugin-operations endpoints.

:::danger[The default key is a demo credential]
`yano-local-cluster-full-key` is publicly known. Override it on a shared
machine or a public-network test:

```bash
export YANO_CLUSTER_API_KEY="$(openssl rand -hex 32)"
```

Outside this launcher, configure real nodes through a secret source with
`YANO_APP_CHAIN_API_KEYS`, and set `YANO_APP_CHAIN_API_AUTH_ENABLED=true` when
reads and submissions must require keys too. There is no production default.
:::

## Authenticated state and proofs

```bash
./yano.sh appchain state identity --url http://node:8080/api/v1 --chain registry
./yano.sh appchain state oldest   --url http://node:8080/api/v1 --chain registry
./yano.sh appchain state entry    --url http://node:8080/api/v1 --chain registry --key 0123
./yano.sh appchain state proof    --url http://node:8080/api/v1 --chain registry --key 0123 --height 42

# Verify a saved proof offline against a root you trust.
./yano.sh appchain state verify --proof-file proof.json \
  --trusted-root <hex> --profile mpf-blake2b256-v1 --genesis-id <64-hex> \
  --chain registry --height 42 --root-source finality-certificate
```

| Command | Use it to |
|---|---|
| `state identity` | Show the genesis-selected commitment profile. |
| `state oldest` | Show the retention boundary: the oldest provable height. |
| `state entry`, `state proof` | Read a value, or its proof, by hex key at the tip or `--height`. |
| `state verify` | Check a proof file offline against a trusted root. `--root-source` is `locally-verified-block`, `finality-certificate`, `cardano-anchor`, or `caller-pinned`; it never trusts the root inside the proof. |
| `state integrity` | Run the node's state integrity check. Privileged. |
| `state snapshot --path <server-path>` | Ask the node to write a ledger snapshot to a fresh directory on the server. Privileged. |
| `state validate`, `state validators`, `state explain --code <n>` | Check an `authenticated-map` value offline against the genesis validators, list them, or explain a validation code. |

`identity`, `integrity`, `oldest`, `entry`, `proof`, and `snapshot` accept
`--api-key <key>`. A pruned proof is unavailable, not evidence of absence —
see [State and proofs](/concepts/state-and-proofs/).

## Declarative bindings

```bash
./yano.sh appchain bindings validate bindings.yaml \
  --plugins-directory plugins --context context.json
./yano.sh appchain bindings dry-run bindings.yaml \
  --plugins-directory plugins --context context.json --fixture fixture.json
```

The subcommands are `compile`, `validate`, `graph`, `dry-run`, `catalog`,
`receipt-key`, `recipe`, and `profile-check`. See the
[bindings CLI reference](/reference/declarative-bindings-cli/).

## Business identities and authorizations

These commands encode and sign offline. They never contact a node.

| Command family | Subcommands | Use it to |
|---|---|---|
| `role` | `public-key`, `sign`, `key-proof`, `key-proof-signature`, `govern-propose`, `govern-approve`, `govern-activate`, `bootstrap --plan <chainId>.role-plan.yaml` | Derive an actor key, sign a role approval, prove key possession, encode governed role mutations, and encode every bootstrap step from the plan `seal` wrote. |
| `authenticated-map` | `action`, `action-commitment`, `direct-preimage`, `direct-complete`, `approval-payload`, `approval-reference`, `command` | Build and sign `authenticated-map` commands for governed collections. |

Pass private seeds with `--seed-file` only.

## Plugin tooling

```bash
./yano.sh appchain plugin scaffold --mode state-machine --id shipment \
  --package com.example.shipment --output shipment-plugin

./yano.sh appchain plugin sign \
  --catalog <catalog.json> --runtime-manifest <manifest.json> \
  --seed-file /secure/publisher.seed --key-id example-release-2026 \
  --output <catalog.sig.json>

./yano.sh appchain plugin validate <jar> --trust-key <key-id>=<public-key-hex> \
  --output catalog-snapshot.json
./yano.sh appchain plugin inspect  <jar> --trust-key <key-id>=<public-key-hex>
./yano.sh appchain metadata verify <jar> --trust-key <key-id>=<public-key-hex>
```

Scaffold modes: `state-machine`, `composite-role`, `effect-executor`, `sink`.
None of these commands load provider classes, run plugin code, fetch a
registry, or install a JAR. See
[Scaffold, sign, install](/plugins/scaffold-sign-install/).

## Experimental: EUTxO

`./yano.sh appchain eutxo transaction|utxo|proof|doctor|demo` supports the
experimental EUTxO ledger. See [eUTxO ledger](/products/eutxo/).

## Safety rules

- Never request, print, copy, infer, or commit secret values. Refer only to
  documented environment-variable or secret-provider names.
- Never invent configuration keys, values, defaults, recipes, or compatibility
  claims. If a capability is unavailable in your release, it is unsupported.
- Keep the chain fingerprint, genesis ids, resolved configuration, release,
  plugin-bundle, and consensus identities distinct.
- Do not mutate a running node or call privileged runtime APIs unless that is
  what you intend.

These are the same rules the in-repo `configure-yano-appchain` agent skill
follows; see [Using Yano X with AI agents](/ai/).

## Deeper reading

- [Cluster launcher README](https://github.com/bloxbean/yano-x/blob/main/scripts/appchain-cluster/README.md)
  — per-node overlays, chain definitions, membership, and the effects demo.
- [Setup CLI README](https://github.com/bloxbean/yano-x/blob/main/tooling/appchain-cli/README.md)
  — the setup commands.
- [Developer tools README](https://github.com/bloxbean/yano-x/blob/main/tooling/devtools/README.md)
  — the offline tools behind the other `appchain` commands.
