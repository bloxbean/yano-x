# App Ledger Tutorials

These tutorials are progressive, but each one can be used on its own. Every
tutorial starts with its goal, what you will learn, what you need, and how long
it takes. Most end with a **Go deeper** section for the trust, consensus,
proof, or operational details.

Yano's tooling calls an app ledger an *app chain*, so commands and file names
say `appchain`.

| Tutorial | Level | Time | What you end with |
|---|---|---:|---|
| [1. Your first app ledger](01-first-app-chain.md) | Beginner | 15 min, plus optional extras | Three members finalize the same event history |
| [2. Registry and proofs](02-registry-and-proofs.md) | Beginner | 15 min | Owner-controlled data with an MPF proof |
| [3. Stock state machines](03-stock-state-machines.md) | Beginner to intermediate | 20 min | The smallest built-in application model for your use case |
| [4. Evidence publication](04-evidence-publication.md) | Intermediate | 30 min | S3, IPFS, and Kafka effects plus proofs and an anchor |
| [5. Domain-role approvals](05-domain-role-approvals.md) | Intermediate to advanced | 30 min | Role-gated approvals, then the evidence specialization |
| [6. Webhook effects](06-webhook-effects.md) | Intermediate | 20 min | A finalized decision invokes an external HTTP endpoint |
| [7. Anchors and verification](07-anchors-and-verification.md) | Intermediate | 20 min | An application proof connected to a Cardano anchor |
| [8. Plugins and composites](08-plugins-and-composites.md) | Advanced | 30–60 min | Yano extended without rebuilding or forking the host |
| [9. From demo to pilot](09-from-demo-to-pilot.md) | Advanced | Planning session | A reviewed project and a deployment plan |
| [10. EUTxO ZK rollup on devnet](../../../ledgers/eutxo-zk/DEVNET_WALKTHROUGH.md) | Advanced | 60+ min | An L1 deposit, L2 spend, proof, root settlement, and L1 withdrawal traced end to end |

Tutorials 4 and 5 use Docker for their connector services. Tutorial 8 also
needs a Java build tool.

**Declarative bindings** have their own track:
[connect existing state machines without Java](../bindings/README.md). It is
experimental and needs a Yano X build that includes the feature; its overview
explains which build to use.

## Which build to use

Every tutorial runs `./yano.sh` from the top-level directory of an extracted
Yano X JVM distribution, the one containing `yano.sh` and `yano.jar`. Use a
[release download](../../RELEASE_DOWNLOADS.md), or
[build the distribution from source](../../BUILD_DISTRIBUTIONS.md) and extract
the archive it produces. The commands are the same either way.

These guides follow the current source. A feature added after your release
appears only after you build from source; when a command is missing, check
`./yano.sh appchain help` in your distribution.

## Tutorial conventions

- Treat `./yano.sh` as the product command. Use `./yano.sh appchain cluster ...`
  for the bundled single-host cluster, and the same wrapper for `appchain init`,
  `render`, `config`, `doctor`, `diff`, and `drift`. The internal
  `appchain-devtools` executable is a packaging boundary, not a second CLI to
  learn.
- Local devnet data is disposable. `stop` preserves it; `clean` deletes it.
- The cluster's member HTTP ports are `7070`–`7072`, and the Evidence Explorer
  uses `7080`. If the defaults are busy, launchers print the range they chose.
- Demo credentials are known or generated locally. Never reuse them outside an
  isolated development environment.
- A successful HTTP submission means "accepted for sequencing", not "already
  final". Wait for the block, or use the scenario verifier.
- A finalized command that breaks a business rule is a deterministic no-op.
  Always check the resulting state, not only the HTTP response or block height.
- Each tutorial marks the output to look for with **✓ You should see**.

## Confidence levels used here

- **Shipped:** present in the current branch and covered by module tests.
- **Demo-proven:** exercised by the packaged multi-member demo and its
  connector and proof verification.
- **Preview:** useful for devnet, testnet, or a tightly controlled pilot, with a
  named production-hardening boundary.
- **Experimental:** not a production claim; follow its dedicated guide.

Use the [release capability catalog](../CAPABILITIES.md) to tell bundled,
first-party optional, reference, and experimental features apart before you
choose a tutorial path.

Return to the [start-here hub](../README.md).
