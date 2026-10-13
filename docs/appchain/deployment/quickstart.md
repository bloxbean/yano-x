# Start a local Yano X showcase

Run three nodes, submit useful data, and inspect proofs without a source checkout,
wallet, public-network funds, or external Cardano node.

- **Goal:** run the curated showcase locally and check that its members agree.
- **Before you start:** Java 25, Python 3, `curl`, and `jq`. Python uses only
  its standard library. Docker is needed only for the separate evidence demo.
  The showcase uses HTTP ports `7070`–`7072` and node ports `13337`–`13339`.
- **Time:** about 10 minutes, most of it startup.
- **Outcome:** three nodes running thirteen chains, a finalized order with a
  proof, and an instance you can stop and resume.

## The showcase in six commands

<!-- illustration: showcase-quickstart -->
1. **Check.** `./showcase.sh doctor --profile light` checks Java 25, Python 3,
   `curl`, `jq`, and the packaged artifacts.
2. **Start.** `./showcase.sh quickstart` starts three nodes with the thirteen
   showcase chains on a private devnet and runs the first demonstrations.
3. **Submit an order.** `./demos/submit-orders.sh` submits an order, waits
   until it is final, and requests its proof.
4. **Verify.** `./showcase.sh verify` checks that every node reports the same
   tip and root for every chain.
5. **Stop.** `./showcase.sh stop` stops the nodes and keeps the instance.
6. **Restart.** `./showcase.sh restart` resumes the same instance from its
   retained state.
<!-- /illustration -->

## 1. Get the matching archive

From the [Yano X releases page](https://github.com/bloxbean/yano-x/releases), select
one release and download its `yano-x-jvm-<version>.zip` and published checksum.
Use the checksum from that same release. Without a release, use a qualified build
from your team or the [distribution build instructions](../../BUILD_DISTRIBUTIONS.md);
do not substitute an unrelated Yano ZIP.

Extract into a new directory and open a terminal in
`yano-x-jvm-<version>/examples/showcase`, the directory containing `showcase.sh`.
Keep this directory for later restarts.

```bash
./showcase.sh doctor --profile light
```

> **✓ You should see** a line starting with
> `doctor: Java 25` and ending with `and packaged artifacts are present`.

```bash
./showcase.sh quickstart --profile light --nodes 3 --instance first-demo
```

Quickstart starts a private devnet and thirteen application chains on three
nodes. It bootstraps the `workflow-chain` anchor on that devnet, runs a
composite workflow and an authenticated-map demonstration, checks convergence,
and prints the console address.

> **✓ You should see** `Cluster up.`, one line per chain such as
> `orders-chain       nodes=3 tip=... cert=.../...`, and finally
> `Status UI: http://127.0.0.1:7070/ui/app-chain/`.

The light profile demonstrates logs, registries, document trails, approvals,
authenticated maps, effects, and more. Optional external connectors and ZK have
separate prerequisites. The quickstart anchor is a **devnet** anchor for
`workflow-chain`; it does not mean every chain is anchored on a public network.

## 2. Insert data and inspect the result

```bash
./demos/submit-orders.sh first-demo '{"order":"A-100","event":"created"}'
./demos/submit-documents.sh first-demo document-A-100
./showcase.sh verify --instance first-demo
./showcase.sh ui --instance first-demo
```

> **✓ You should see** a proof summary with `"claimSatisfied": true`, then
> `ORDERED: message finalized at height N`.

The order command prints a finalized position and proof claim. The document
scenario appends to a trail. `verify` checks node readiness, converged tips and
roots, certificate counts for chains with finalized blocks, and the
`workflow-chain` anchor. Open the printed console URL to inspect messages,
chain state, and effects.

A successful HTTP submission is admission to the message pool. Finality and a
successful application transition are later results. See the
[HTTP submission walkthrough](../../../examples/showcase/docs/MESSAGE_SUBMISSION.md)
for the actual requests, message lookup, and typed proof route.

## 3. Keep your state and resume

```bash
./showcase.sh config paths --instance first-demo
./showcase.sh stop --instance first-demo
./showcase.sh restart --instance first-demo
./showcase.sh verify --instance first-demo
```

> **✓ You should see** `stopped 3 node(s)` after `stop`, and `Cluster up.`
> after `restart`.

Retrieve the same order after the restart. Do not reset an instance to fix a
startup error: `reset --yes` deletes the instance and is not recoverable. Run
`./showcase.sh logs --instance first-demo` and inspect the first failure; keep
the generated identity and data directories together.

## Troubleshooting

| You see | What it means and what to do |
|---|---|
| `Java 25 is required (found ...)` | Put a Java 25 JDK first on your `PATH`, then run `doctor` again. |
| `required command not found: jq` | Install the named command and run `doctor` again. |
| `explicit HTTP range 7070-7072 is busy (port ...)` | Another program holds the showcase ports. Stop it and run the same command again, or start a new instance with other ports, for example `--instance second-demo --http-base 7170 --server-base 14337`. |
| `run showcase.sh from examples/showcase in an extracted Yano X JVM distribution` | Run the script from inside the extracted JVM ZIP, not from a source checkout. |

**Next:** continue with [your own application profile](configure.md), or explore the
[complete showcase](../../../examples/showcase/docs/MASTER_DEMO.md).
