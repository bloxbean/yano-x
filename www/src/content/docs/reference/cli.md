---
title: CLI reference
description: The ./yano.sh appchain command surface — project lifecycle, cluster operation, plugin tooling, and authenticated-state inspection.
sidebar:
  order: 1
---

`./yano.sh` is the public CLI, shipped inside the Yano X JVM distribution.
There is no separate `yano-x` executable. Run it from the directory that
contains it.

```bash
cd ~/yano-x/yano-x-jvm-*
./yano.sh appchain help
```

Read command help from the **version-matched** executable when an option is
uncertain. The binary you built is the authority for the version you will run.

## Discovery

```bash
./yano.sh appchain recipes                    # release-pinned recipes
./yano.sh appchain capabilities               # support tier, scope, selection
./yano.sh appchain capabilities --format json # canonical structured catalog
```

## Project lifecycle

A project is a directory holding `appchain.yaml`, generated runtime files, and
`appchain.lock`. **Edit only `appchain.yaml`** — rendering stops if a generated
file has an unaccounted manual edit.

```bash
# Reproducible non-interactive generation.
./yano.sh appchain init --non-interactive \
  --recipe owned-registry --network preprod --members 3 \
  --node-host node-a.example --node-host node-b.example \
  --node-host node-c.example \
  --deployment host --output product-registry

# Regenerate derived output after editing appchain.yaml.
./yano.sh appchain render product-registry

# Verify the project, and inspect a final JVM release.
./yano.sh appchain config validate --mode project product-registry
./yano.sh appchain doctor product-registry --distribution yano-x-jvm.zip

# Classify a change, preview a migration, and detect running drift.
./yano.sh appchain diff previous.lock product-registry/appchain.lock
./yano.sh appchain migrate product-registry --dry-run
./yano.sh appchain drift product-registry --peer <node-identity-url>

# Export reviewed, deterministic deployment derivatives.
./yano.sh appchain gitops product-registry --target helm      --output deploy/helm
./yano.sh appchain gitops product-registry --target kustomize --output deploy/kustomize
```

| Command | Use it to |
|---|---|
| `init` | Create a project from a recipe. |
| `render` | Regenerate derived files after an `appchain.yaml` edit. |
| `config validate --mode project` | Check the blueprint and its resolved configuration. |
| `doctor` | Check artifact readiness against a real distribution before startup. |
| `diff` | Classify what changed between two locks. |
| `migrate --dry-run` | Preview a blueprint migration. |
| `drift` | Compare the project against running nodes. |
| `gitops` | Export Helm or Kustomize derivatives. |

## Cluster (single host)

`appchain cluster` runs N members as processes on one machine — a disposable
demo, a repeatable integration test, or a controlled single-host deployment.

```bash
export YANO_CLUSTER_DIR=/tmp/yano-demo

./yano.sh appchain cluster start 3            # 3-member self-contained devnet
./yano.sh appchain cluster status             # tips, roots, and per-chain agreement
./yano.sh appchain cluster submit orders-chain orders '{"id":1}'
./yano.sh appchain cluster submit orders-chain orders '{"id":2}' --node 1
./yano.sh appchain cluster effect demo        # emit and execute one effect
./yano.sh appchain cluster loadtest orders-chain -n 500 -c 10 -s 256
./yano.sh appchain cluster loadtest orders-chain -n 1000 -c 20 --spread
./yano.sh appchain cluster node join 3        # govern, start, and catch up node 3
./yano.sh appchain cluster node resume 3      # restart an already governed joiner
./yano.sh appchain cluster member add <64-hex-ed25519-public-key>
./yano.sh appchain cluster anchor-bootstrap   # one-time script-anchor setup
./yano.sh appchain cluster stop               # stop, keep data
```

| Lifecycle command | Effect |
|---|---|
| `stop` | Stops processes, **preserves** state. |
| `clean` | `stop` plus a wipe. |
| `reset --yes` | Destructive. |

:::caution
Never point `clean` or `reset` at a deployment you care about. Retained
clusters are identity and data, not disposable test output.
:::

### Cluster environment

| Variable | Meaning | Default |
|---|---|---|
| `YANO_CLUSTER_DIR` | Where cluster state lives. | A launcher default |
| `YANO_HOME` | The tree holding `config/`; nodes launch with this as cwd. | The distribution root |
| `YANO_JAR` / `YANO_NATIVE` | Explicit binary path. | Auto-detected under `YANO_HOME` |
| `YANO_CLUSTER_API_KEY` | Key for privileged operations. | `yano-local-cluster-full-key` |
| `YANO_CLUSTER_NODE_CONFIG_DIR` | Private per-node configuration overlays. | Unset |
| `YANO_CLUSTER_MEMBER_KEY_DIR` | Operator-supplied member keys. | Unset |

The launcher keeps reads, submissions, status, and live streams public on its
loopback-only HTTP API, and requires the full key for admin, effect, and plugin
operations.

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

## Authenticated state and proofs

```bash
./yano.sh appchain state identity --url http://node:8080/api/v1 --chain registry
./yano.sh appchain state oldest   --url http://node:8080/api/v1 --chain registry
./yano.sh appchain state entry    --url http://node:8080/api/v1 --chain registry --key 0123
./yano.sh appchain state proof    --url http://node:8080/api/v1 --chain registry --key 0123
```

`identity` shows the genesis-selected profile; `oldest` shows the retention
boundary. A pruned proof is unavailable, not evidence of absence — see
[State and proofs](/concepts/state-and-proofs/).

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
