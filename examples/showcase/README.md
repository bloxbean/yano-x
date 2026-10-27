# Yano App-Chain Showcase

This distribution is the single demo entry point for Yano app chains. Start
with [MASTER_DEMO.md](docs/MASTER_DEMO.md), use the
[curl/API version](docs/MASTER_DEMO_CURL.md), or run:

```bash
./showcase.sh doctor
./showcase.sh quickstart --profile light --nodes 3 --instance demo
```

The light profile runs thirteen app chains on local Yano nodes (three by
default), including `payment-chain-settlement`, with no Kafka, object store, IPFS, or
separate effect service. Seven chains present standalone foundations; the
remaining reference chains demonstrate how ADR-031 composes those foundations
with cross-cutting concerns instead of creating another application SPI:

| Reference application | Reused capabilities |
|---|---|
| `workflow-chain` | orders + basic approval + audit + finality-gated outbox effect |
| `document-review-chain` | document trail + domain actors/roles + actor approval + one-use receipt |
| `authenticated-map-chain` | authenticated map + direct actor/role authorization + multi-organization approval |
| `payment-chain-settlement` | EUTxO + L1 observers + settlement effects + rebuildable lifecycle index |
| `cardano-history-chain` | protocol parameters + epoch stake + governance, with optional per-epoch authenticated snapshots |

`authenticated-map-jmt-chain` uses the same collections, policies, actors,
commands, and receipts as the MPF chain; only its commitment backend and
off-chain-only verification target differ.

Cardano History defaults to the low-cost `params-only-v1` preset. Select
`--cardano-history-profile params-stake|params-governance|full` on a fresh
instance to enable the corresponding L1 scans. Enable reusable authenticated snapshots selectively
with `--enable-authenticated-snapshots=cardano-history-chain`. The default MPF
secondary roots support nested off-chain/on-chain proof verification; the
optional `--authenticated-snapshot-profile jmt-blake2b256-v1` profile is an
off-chain comparison. The console shows the capability only when enabled.
MPF deployments can evaluate archive-time reachable-node pruning with
`--enable-authenticated-snapshot-mpf-pruning=cardano-history-chain`. It is a
node-local, retained instance setting and remains disabled by default while retained-root
qualification expands.

The built-in `authenticated-map` scenario demonstrates multiple collections,
opaque and canonical-CBOR values, a declarative schema, the first-party GS1
validator SPI example, and the ADR-025.2 governed flows: a direct-role
collection written with externally signed actor evidence and an approval
collection executed only after two auditors from distinct organizations
approve. The packaged console renders every governed record at
`/ui/app-chain/authenticated-map/`. The light profile also includes one clearly
demo-only plugin, `showcase-composite` / `showcase-outbox`, to make the
deterministic-intent → finality-gate → external-execution → on-chain-result
flow visible.

`showcase-composite`, its `order-approval-outbox-v1` preset, and the local
`showcase-outbox` executor are demo-only showcase artifacts. They are not
built-in Yano state machines, production connectors, or core changes. They use
Yano's public composite, plugin, and effect SPIs.

The `evidence` profile delegates to the maintained evidence product demo with
Kafka, S3-compatible storage, and IPFS. The evidence product is reusable; the
showcase and effects-demo directories are presentation harnesses, not the
product itself. The `eutxo` profile delegates to the maintained ledger
and bridge EUTxO demos.

## Requirements

- Java 25
- Python 3 standard library only (no `pip`, virtual environment, or
  `cryptography` package for the supported 1–16-node showcase)
- `curl` and `jq`
- Docker only for the `evidence` profile

Run `./showcase.sh doctor --profile <profile>` before presenting.

The `demos/` directory contains one-command wrappers for each light scenario,
separate proposal/approval and composite workflow steps, functional load,
burst load, soak load, and the complete interactive master run.

Governed changes are intentionally height-delayed. `member join <i>` adds a
node the way real operators do: the new node requests membership with the
sealed file, every existing node runs `yano.sh appchain approve` (which raises
the threshold first where needed, for example 2-of-3 to 3-of-4), and the new
node joins and starts. The command ends with `governance activate`, which
advances labeled real messages on every showcase chain and finalizes a proof
block under the new member set. Run `./showcase.sh governance activate
--instance demo` again if that step was interrupted.

## How the nodes are set up

The light profile runs the ordinary app-chain setup commands
(`yano.sh appchain new`, `request`, `seal`, `join`, `start`), the same ones
real operators use. `prepare` seals up to three applications from the
templates in `config/`:

| Application | Chains | Template |
|---|---|---|
| `showcase` | the eleven showcase chains | `config/showcase.template.yaml` |
| `history` | `cardano-history-chain`, unless `--disable-cardano-history` | `config/history.template.yaml` |
| `settlement` | `payment-chain-settlement`, on a devnet with at least three nodes; always the three founding nodes | `config/settlement.template.yaml` |

Each node is a node directory with its own keys, logs, and stores:

```text
data/showcase/<instance>/
  instance.json      options retained for this instance
  coordinator/       showcase draft, member cards, showcase.chain
  history/           history.chain
  settlement/        settlement.chain
  node0/ node1/ ...  node directories (secrets/, config/local.yaml, logs/node.log, data/)
  outbox/            workflow-chain outbox receipts
```

Showcase options such as `--cardano-history-profile` or
`--enable-finalized-message-index` become sealed settings of the application.
What only one node runs, such as the outbox executor and settlement executor
ownership on node 0 or snapshot archive directories, goes into that node's
`config/local.yaml`. `stop` preserves state; `restart` starts the same node
directories. A later command that asks for a different `--nodes`,
`--network`, port base, or `--threshold` than `instance.json` records is
refused; use a new `--instance`. `reset --instance <name> --yes` is the only
showcase command that deletes the named instance.

```bash
./showcase.sh config show  --instance demo
./showcase.sh config paths --instance demo
./showcase.sh config export ./demo-showcase.chain --instance demo
```

`show` prints each sealed application with its fingerprint and, while the
nodes run, each chain's live identity, including active and scheduled
members, thresholds, and activation heights. It never prints seeds or API
keys. `paths` lists the instance files, node directories, per-node settings,
logs, outbox, and plugin bundle. `export` copies the sealed `showcase.chain`,
a public file with no keys. Inspect any sealed file directly:

```bash
../../yano.sh appchain inspect data/showcase/demo/coordinator/showcase.chain
```

Configuration safety comes from the sealed files. Every node joins only after
comparing the application fingerprint, and `start` refuses a
`config/local.yaml` that changes a sealed setting. Per-chain RocksDB stores
finalized blocks and MPF state, the committed consensus profile, and every
governed membership epoch, so governed member and threshold changes survive
restart. Change membership with `member join`, not by editing node files.

The HTTP API is bound to loopback. Reads and message submissions need no key;
privileged calls use each node's own key from `node<i>/secrets/api.key`, which
the showcase reads when needed and never prints. Do not expose these nodes on
a public interface.

## Status UI

```bash
./showcase.sh ui --instance demo
```

Open the printed URL. A chain with no effects legitimately has an empty Effect
Executors section. On `workflow-chain`, only node 0 owns `showcase-outbox`;
other nodes still validate the effect intent and its incorporated result.

## Profiles and docs

- [LIGHT_PROFILE.md](docs/LIGHT_PROFILE.md)
- [CAPABILITY_CATALOG.md](docs/CAPABILITY_CATALOG.md)
- [CARDANO_HISTORY.md](docs/CARDANO_HISTORY.md)
- [EVIDENCE_PROFILE.md](docs/EVIDENCE_PROFILE.md)
- [EUTXO_PROFILE.md](docs/EUTXO_PROFILE.md)
- [GOVERNANCE_DEMO.md](docs/GOVERNANCE_DEMO.md)
- [MESSAGE_SUBMISSION.md](docs/MESSAGE_SUBMISSION.md)
- [LOAD_AND_SOAK.md](docs/LOAD_AND_SOAK.md)
- [ANCHORING_DEMO.md](docs/ANCHORING_DEMO.md)
- [PREPROD_ANCHORING.md](docs/PREPROD_ANCHORING.md)
- [SETTLEMENT_CHAIN.md](docs/SETTLEMENT_CHAIN.md)
- [PREPROD_SETTLEMENT.md](docs/PREPROD_SETTLEMENT.md)
- [MASTER_DEMO_CURL.md](docs/MASTER_DEMO_CURL.md)
- [JAVA_CLI_ROADMAP.md](docs/JAVA_CLI_ROADMAP.md)
- [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)
- [PRESENTERS.md](docs/PRESENTERS.md)

Architecture and product/demo boundaries are recorded in ADR-023. ADR-033
defines the catalog, manifest discovery contract, generic finalized-message
index wrapper, console presentation, and qualification requirements.
