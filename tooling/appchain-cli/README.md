# App-chain setup CLI

`yano.sh appchain` in the Yano X JVM distribution sets up and runs an app chain
with other operators (ADR-056). Each operator runs their own node; private keys
never leave their machine, and only public files travel. Start with a
one-machine rehearsal:

```bash
./yano.sh appchain try
```

| Who | Command | What it does |
|---|---|---|
| Anyone | `try [--clean]` | Rehearse every step on one machine with three simulated operators |
| Coordinator | `new [<app>]` | Write `<app>.draft.yaml` with a fresh invite code (a wizard asks the questions; `--answer <chain>.<name>=<value>` fills recipe answers, `--sequencing fixed\|rotating` chooses the proposer, `--topics <chain>=members\|anyone\|registrars:<a>+<b>` protects topics) |
| Coordinator | `invite <app>.draft.yaml --serve` | Optional: print one `yano://` join link per operator and serve the draft, the cards and the sealed file until everyone has it (`--seal` seals once every card is in) |
| Every operator | `request <app>.draft.yaml\|<app>.chain\|<link>` | Make this node's key and its member card, `<name>.member`; with a link, send the card, wait for the seal and continue into `join` (`--no-wait` returns after sending) |
| Coordinator | `seal <app>.draft.yaml` | Seal the draft with the cards beside it into `<app>.chain`; prints the chain fingerprint, and writes `<chainId>.role-plan.yaml` for role-approval and evidence chains |
| Every operator | `join <app>.chain` | Check the sealed file and the fingerprint, and set up this node |
| Every operator | `start`, `stop` | Run or stop this node |
| Anyone | `status [<app>]` | Where the application is, what to do next, this node's Cardano view and plugin catalog fingerprint |
| Every existing operator | `approve <name>.member`, `approve --remove <name>` | Add or remove a member on every chain, quorum-safely |
| One operator | `anchor [<app>]` | Optional: anchor a chain to Cardano from this node |
| Every existing operator | `topics approve <plan>` | Vote on a namespace change of a chain sealed with `topics:`, through this node |
| Namespace owners | `topics init\|plan\|sign\|submit\|resolve\|delegate\|doctor` | Manage a namespace's owners, writers and schema from this node directory; `--json` for scripts |
| Anyone | `inspect <file>` | Show and check a draft, card, or sealed file; `--map-genesis <chain>` prints an authenticated map's genesis CBOR hex |

Every command prints a `Next:` line. Add `--help` to a command for its options.
Commands that act on a node take `--node-dir` (default `./yano-node`). `join`
writes `config/consensus.yaml` and `config/node.yaml` in the node directory and
creates `config/local.yaml` for the operator's own node-only settings, which it
never rewrites; `start` refuses a local file that sets a sealed consensus key.

Join links are plain HTTP and carry only public data. They are single-use per
operator and expire (24 hours by default); comparing the chain fingerprint and
each key id on a call stays the trust step.

The other `appchain` tools (`recipes`, `capabilities`, `bindings`, `state`,
`role`, `plugin`, `metadata`, `config`, `authenticated-map`, `eutxo`) are passed
to [`tooling/devtools`](../devtools/README.md). These setup commands are the
only way `yano.sh` runs app-chain nodes; `try` rehearses them on one machine.

User guides: [set up an application](../../docs/appchain/deployment/configure.md),
[grow and change it](../../docs/appchain/deployment/add-chain.md), and
[run your node on a server](../../docs/appchain/deployment/operators.md).
