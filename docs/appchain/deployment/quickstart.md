# Start a local Yano X showcase

Run three nodes, submit useful data, and inspect proofs without a source checkout,
wallet, public-network funds, or external Cardano node.

## 1. Get the matching archive

From the [Yano X releases page](https://github.com/bloxbean/yano-x/releases), select
one release and download its `yano-showcase-<version>.zip` and published checksum.
Use the checksum from that same release. If a release does not publish a showcase
archive, use a qualified build from your team or the
[distribution build instructions](../../BUILD_DISTRIBUTIONS.md); do not substitute
an unrelated Yano ZIP.

Extract into a new directory and open a terminal in the directory containing
`showcase.sh`. Keep this directory for later restarts. Install Java 25, Python 3,
`curl`, and `jq`; Python uses only its standard library. Docker is needed only
for the separate evidence demo.

```bash
./showcase.sh doctor --profile light
./showcase.sh quickstart --profile light --nodes 3 --instance first-demo
```

Quickstart starts a private devnet and thirteen application chains. It runs a
composite workflow and authenticated-map demonstration, checks convergence, and
prints the console address. Use the printed ports if the default ports are busy.

The light profile demonstrates logs, registries, document trails, approvals,
authenticated maps, effects, and more. Optional external connectors and ZK have
separate prerequisites. Quickstart bootstraps the workflow chain's **devnet**
anchor; it does not mean every chain is independently anchored on a public network.

## 2. Insert data and inspect the result

```bash
./demos/submit-orders.sh first-demo '{"order":"A-100","event":"created"}'
./demos/submit-documents.sh first-demo document-A-100
./showcase.sh verify all --instance first-demo
./showcase.sh ui --instance first-demo
```

The order command prints a finalized position and proof claim. The document
scenario appends to a trail. Verification checks node readiness, converged tips
and roots, and certificate counts for chains with finalized blocks. Open the
printed console URL to inspect messages, chain state, and effects.

A successful HTTP submission is admission to the message pool. Finality and a
successful application transition are later results. See the
[HTTP submission walkthrough](../../../examples/showcase/docs/MESSAGE_SUBMISSION.md)
for the actual requests, message lookup, and typed proof route.

## 3. Keep your state and resume

```bash
./showcase.sh config paths --instance first-demo
./showcase.sh stop --instance first-demo
./showcase.sh restart --instance first-demo
./showcase.sh verify all --instance first-demo
```

Retrieve the same order after restart. Do not reset an instance to fix a startup
error. Run `./showcase.sh logs --instance first-demo` and inspect the first failure;
keep the generated identity and data directories together.

Continue with [your own application profile](configure.md), or explore the
[complete showcase](../../../examples/showcase/docs/MASTER_DEMO.md).
