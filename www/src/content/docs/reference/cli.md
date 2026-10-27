---
title: CLI reference
description: The ./yano.sh appchain command surface — rehearsing on one machine, setting up an application with other operators, configuration, state and proofs, bindings, identity tools, and plugin tooling.
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

The setup commands below are the one way `yano.sh` runs app ledger nodes:
`try` rehearses them on one machine, and the others set up and run an
application with other operators. Every other `appchain` command runs the
offline developer tools. Read command help
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
each on their own node. `try` runs the same steps on one machine first. Only
public files travel between operators: the draft, the member cards, and the
sealed `.chain` file, sent by hand or carried by join links. [Set up an application](/deployment/configure/) walks through them.

```bash
./yano.sh appchain try                                   # rehearse every step on one machine
./yano.sh appchain try --chain registry=owned-registry   # rehearse with your own chains (as for new)
./yano.sh appchain try --from shop.template.yaml         # rehearse the chains in a template
./yano.sh appchain try --clean                           # stop the rehearsal's nodes and delete it
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
| `try` | Run every step on one machine: a coordinator and three operators, alice, bob, and carol, with node directories under `appchain-try/<name>/yano-node`. It prints each command it runs, and for an `audit-log` chain finalizes one message on every node. `--clean` stops the nodes and deletes the rehearsal. | `✓ Message … is final at height N on every node` |
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
| `ui` | Open a page on this computer (127.0.0.1 only) with the same steps. Every button runs the command shown under it. | `✓ App-chain setup UI for …` and the page address |

Common options:

- `try`: `--chain <id>=<recipe>` or `--chain <id>=bindings:<file>` (repeatable;
  default one `orders=audit-log` chain), `--answer <chain>.<name>=<value>`,
  `--from <template.yaml>`, `--setting <chain>.<key>=<value>`,
  `--without <chain>` (all as for `new`), `--dir <dir>` (default
  `appchain-try`), `--clean`. It gives each node the first free ports from
  `8080` (HTTP) and `13337` (node).
- `new`: `--network devnet|preview|preprod|mainnet`, `--operators alice,bob,carol`
  (the first is the lead), `--chain <id>=<recipe>` or `--chain <id>=bindings:<file>`
  (repeatable), `--answer <chain>.<name>=<value>` (repeatable; the wizard asks
  for any answer a recipe still needs), `--from <template.yaml>` (start from a
  template's chains and defaults), `--setting <chain>.<key>=<value>`
  (repeatable; a sealed chain setting), `--without <chain>` (leave out one of
  the template's chains), `--threshold majority|two-thirds|all|<n>`,
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
- `anchor`: `--chain <id>`, `--stop`, `--take-over`, `--key <wallet-seed-file>`
  (use a funded wallet you already have), `--ada <devnet-funding>`,
  `--timeout <seconds>`, `--yes`.
- `ui`: `--dir <work-dir>`, `--port <port>`, `--no-open`.
- Every command that acts on a node takes `--node-dir` (default `./yano-node`).
  Node-only settings go in that directory's `config/local.yaml`, which `join`
  creates and never rewrites; `start` refuses it if it sets a sealed consensus
  key. The node's API key is in `secrets/api.key`; it is needed only for
  privileged and admin endpoints, because reads and message submissions are
  open unless broad API authentication is enabled.

A template for `--from` is a draft-shaped YAML file: `chains:` and, optionally,
`threshold`, `membership`, `sequencing`, and `network`. Each chain has an `id`
and a `recipe`, and optionally `capabilities`, `answers`, `settings` (flat
dotted keys, sealed), `authenticatedMap`, and `bindings`:

```yaml
chains:
  - id: approvals
    recipe: approval-workflow
    capabilities: [effects:on-approved, executor:webhook]
    answers: {effectType: webhook.post}
```

Exit codes: `0` success, `1` failure, `2` usage error, `3` not ready yet or
timed out. Every command ends with a `Next:` line.

## Configuration tools

These commands work on configuration files directly.

```bash
# A YAML file checked against the metadata, optionally against a template contract.
./yano.sh appchain config validate --mode template \
  --template-contract contract.yml application.yml

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

- [Setup CLI README](https://github.com/bloxbean/yano-x/blob/main/tooling/appchain-cli/README.md)
  — the setup commands.
- [Developer tools README](https://github.com/bloxbean/yano-x/blob/main/tooling/devtools/README.md)
  — the offline tools behind the other `appchain` commands.
