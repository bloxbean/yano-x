---
title: CLI reference
description: The ./yano.sh appchain command surface — project lifecycle, configuration, the single-host cluster, state and proofs, bindings, identity tools, and plugin tooling.
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

`./yano.sh appchain cluster …` runs the bundled single-host launcher. Every
other `appchain` command runs the offline developer tools. Read command help
from the **version-matched** executable when an option is uncertain: the
binary you run is the authority for its version.

## Discovery

```bash
./yano.sh appchain recipes                    # release-pinned recipes
./yano.sh appchain capabilities               # support tier, scope, selection
./yano.sh appchain capabilities --format json # canonical structured catalog
```

## Project lifecycle

A project is a directory holding `appchain.yaml`, generated runtime files, and
`appchain.lock`. **Edit only `appchain.yaml`**: rendering stops if a generated
file has a manual edit.

```bash
# Reproducible non-interactive generation.
./yano.sh appchain init --non-interactive \
  --recipe owned-registry --network preprod --members 3 \
  --node-host node-a.example --node-host node-b.example \
  --node-host node-c.example \
  --deployment host --output product-registry

# Local devnet only: generate member keys and private env files.
./yano.sh appchain prepare product-registry

# Regenerate derived output after editing appchain.yaml.
./yano.sh appchain render product-registry

# Verify the project, and inspect an extracted JVM release directory.
./yano.sh appchain config validate --mode project product-registry
./yano.sh appchain doctor product-registry --distribution /path/to/yano-x-jvm-<version>

# Classify a change, preview a migration, and detect running drift.
./yano.sh appchain diff previous.lock product-registry/appchain.lock
./yano.sh appchain migrate product-registry --dry-run
./yano.sh appchain drift product-registry \
  --peer https://node-a.example:8080/api/v1/ --api-key-env YANO_APPCHAIN_API_KEYS

# Export reviewed, deterministic deployment derivatives.
./yano.sh appchain gitops product-registry --target helm      --output deploy/helm
./yano.sh appchain gitops product-registry --target kustomize --output deploy/kustomize
./yano.sh appchain gitops product-registry --target ansible   --output deploy/ansible
```

| Command | Use it to | Prints on success |
|---|---|---|
| `init` | Create a project from a recipe. | `PROJECT_INITIALIZED` |
| `prepare` | Generate local member keys and `secrets/nodeN.env` files for a local JVM devnet project. | `PREPARED` |
| `render` | Regenerate derived files after an `appchain.yaml` edit. | `PROJECT_RENDERED` |
| `config validate --mode project` | Check the blueprint, lock, catalogs, configuration, and generated-file digests. | `VALID_PROJECT` |
| `doctor [--distribution <path>]` | Check readiness against a real distribution before startup. | `DOCTOR_OK` or `DOCTOR_WARNINGS`; `DOCTOR_FAILED` exits non-zero |
| `diff <old.lock> <new.lock>` | Classify what changed between two locks. | One line per change, then a summary |
| `migrate [--dry-run]` | Preview or apply a blueprint migration. | |
| `drift --peer <url> …` | Compare every chain's identity on each running node. `--peer` is a node's API base URL. | `DRIFT_OK`; `DRIFT_DETECTED` exits non-zero |
| `gitops --target helm\|kustomize\|ansible --output <empty-dir>` | Export deployment derivatives. Ansible needs a JVM host project with one public key and host per member. | `GITOPS_EXPORTED` |

`init` options include `--recipe`, `--network devnet|preview|preprod|mainnet`,
`--members 1..32`, `--member-key` (once per member), `--node-host` (once per
member), `--finality majority|two-thirds|all`, `--sequencing fixed|rotating`,
`--membership static|governed`, `--runtime jvm|native`,
`--deployment host|docker-compose`, `--http-port-base` (default `8080`),
`--server-port-base` (default `13337`), `--capability`, `--answer name=value`,
`--name`, `--chain-id`, `--output`, and `--non-interactive`. Run
`./yano.sh appchain init --help` for the rest.

## Changing a running project

These commands add a chain to a generated local host project without resetting
it. [Add a chain](/deployment/add-chain/) walks through them.

```bash
./yano.sh appchain chain add product-registry --chain-id documents --recipe document-trail
./yano.sh appchain plan product-registry
./yano.sh appchain apply product-registry --plan <digest-from-plan>
```

| Command | Use it to | Prints on success |
|---|---|---|
| `chain add <project> --chain-id <id> --recipe <recipe>` | Add a chain to `appchain.yaml` only. Accepts repeatable `--capability` and `--answer`. | `CHAIN_PROPOSED` |
| `plan [project]` | Compare the proposed blueprint with the current lock. Prints JSON with each chain's action, blockers, activation, and a digest. | `"status" : "PLAN_READY"`; `PLAN_BLOCKED` exits non-zero |
| `apply [project] --plan <digest>` | Install the reviewed revision while the nodes are stopped. Writes the lock last and never deletes chain data. | `APPLIED` |
| `start-check [project]` | Refuse to start while an apply is pending or the lock differs from the applied one. The generated `scripts/start-node` runs it for you. | |

## Configuration tools

These commands work on configuration files directly, without a project.

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
| `role` | `public-key`, `sign`, `key-proof`, `key-proof-signature`, `govern-propose`, `govern-approve`, `govern-activate` | Derive an actor key, sign a role approval, prove key possession, and encode governed role mutations. |
| `authenticated-map` | `action`, `action-commitment`, `direct-preimage`, `direct-complete`, `approval-payload`, `approval-reference`, `command` | Build and sign `authenticated-map` commands for governed collections. |

Pass private seeds with `--seed-file` only. A generated project's bootstrap plan
lists the exact options for its policies.

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

## Experimental: EUTxO and validity

`./yano.sh appchain eutxo transaction|utxo|proof|doctor|demo` and
`./yano.sh appchain validity bootstrap|status|prove|proof|doctor|…` support the
experimental EUTxO ledger and its ZK validity flow. See
[eUTxO and ZK](/products/eutxo-and-zk/).

## Safety rules

- Never request, print, copy, infer, or commit secret values. Refer only to
  documented environment-variable or secret-provider names.
- Never invent configuration keys, values, defaults, recipes, or compatibility
  claims. If a capability is unavailable in your release, it is unsupported.
- Keep blueprint, resolved-config, release, plugin-catalog, and consensus
  identities distinct.
- Do not mutate a running node or call privileged runtime APIs unless that is
  what you intend.

These are the same rules the in-repo `configure-yano-appchain` agent skill
follows; see [Using Yano X with AI agents](/ai/).

## Deeper reading

- [Cluster launcher README](https://github.com/bloxbean/yano-x/blob/main/scripts/appchain-cluster/README.md)
  — per-node overlays, chain definitions, membership, and the effects demo.
- [Developer tools README](https://github.com/bloxbean/yano-x/blob/main/tooling/devtools/README.md)
  — the offline engine behind `yano.sh appchain`.
