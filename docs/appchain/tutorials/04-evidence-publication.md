# Tutorial 4 — Publish and Verify Immutable Evidence

To run this outcome with other operators, follow [Set up an application](../deployment/configure.md).

- **Level:** beginner to advanced
- **Time:** about 30 minutes for the first build
- **Outcome:** publish one immutable document through a threshold-approved
  workflow, preserve it in S3-compatible storage and IPFS, notify Kafka, and
  verify the chain, connector, proof, and Cardano-anchor evidence.

This is Yano's most complete no-code vertical scenario. Docker Compose starts
three Yano members plus Kafka, RustFS (S3-compatible object storage), Kubo
IPFS, and the Evidence Explorer.

**Before you start:**

- You need JDK 25, Docker with Compose v2, `curl`, `jq`, `openssl`, and
  Python 3. The stack is tested with Docker Desktop. On Colima with a
  `virtiofs` mount, RustFS refuses its owner-only secret files and the stack
  rolls back ([#36](https://github.com/bloxbean/yano-x/issues/36)).
- The harness ships in the extracted Yano X JVM distribution at
  `examples/evidence`. It finds the distribution's `yano.jar` by itself.
- It uses ports 7070–7072 for the members and 7080 for the Explorer, plus 9092,
  9000, and 5001 for Kafka, RustFS, and Kubo. A running showcase holds
  7070–7072; stop it first with `./showcase.sh stop` in `examples/showcase`.
  An `./yano.sh appchain try` rehearsal from earlier tutorials uses ports from
  8080 and 13337, so it does not clash, but you can remove it with
  `./yano.sh appchain try --clean`.
- The harness never moves a busy port. If another program holds one, export a
  free port before the first command and keep it set for the whole tutorial.
  `DEMO_HTTP_BASE` moves the members to that port and the next two, and
  `DEMO_UI_PORT`, `DEMO_KAFKA_PORT`, `DEMO_S3_PORT` and `DEMO_IPFS_PORT` move
  the other services. For example, `export DEMO_HTTP_BASE=7370` puts the
  members on 7370–7372, and the URLs the harness prints use those ports.
- The connector services share a fixed Docker subnet, `172.30.13.0/24`. If
  `up` fails with `Pool overlaps with other one on this address space`,
  another Docker network already uses that range. Choose a free range before
  an instance's **first** `up` (it is fixed after that) and keep it set:

  ```bash
  export DEMO_CONNECTOR_SUBNET=10.213.13.0/24
  export DEMO_S3_IP=10.213.13.10 DEMO_KUBO_IP=10.213.13.11 DEMO_KAFKA_IP=10.213.13.12
  ```
- `prepare` and every `up` build two local Docker images from the
  distribution. By default they are tagged `yano-adr013-node:local` and
  `yano-adr013-runner:local` (inside a Git working tree, the tag is that
  tree's short commit instead). Building **replaces any existing local image
  with the same name and tag**, for example one from another Yano X version.
  To keep other images, choose your own names before the first command and
  keep them set for the whole tutorial:

  ```bash
  export DEMO_YANO_IMAGE=yano-adr013-node:tutorial
  export DEMO_RUNNER_IMAGE=yano-adr013-runner:tutorial
  ```

The harness runs the default `evidence-v1-gated` profile, in which validator
members approve each release. The `evidence-ledger` recipe
(`./yano.sh appchain new <app> --chain <id>=evidence-ledger`) selects the
role-aware `role-evidence` profile instead, where named business actors
approve; the harness runs that profile when you add `--machine role` to every
command.

## How one publication flows

<!-- illustration: evidence-pipeline -->
1. **Stage.** The runner stages the exact document bytes in RustFS and computes
   the IPFS CID.
2. **Approve.** It registers the document hash and records a member approval for
   the exact evidence command.
3. **Release.** `evidence.release.v1` records the version and emits `object.put`
   and `ipfs.pin` effects.
4. **Store and pin.** After finality, the effect owner archives the object and
   pins the CID.
5. **Incorporate.** Both results return to the ledger; status becomes
   `STORAGE_READY`.
6. **Notify.** A `kafka.publish` effect publishes the event; status becomes
   `READY`.
7. **Agree and anchor.** The runner waits for all members to agree and for a
   devnet anchor that covers the result.
8. **Verify.** It checks finality, the anchor, state and effect proofs, and the
   Kafka event.
<!-- /illustration -->

The source document itself is not put into consensus state. The chain commits
its business identity, version, hashes, workflow decisions, effect intents,
and acknowledged outcomes.

## 1. Prepare and start a fresh direct-continuation profile

Run every command in this tutorial from the harness directory. From the
top-level directory of the extracted distribution, the one containing
`yano.sh`:

```bash
cd examples/evidence

./demo.sh prepare \
  --instance tutorial-evidence \
  --continuation direct

./demo.sh up \
  --instance tutorial-evidence \
  --continuation direct
```

`prepare` stages the plugins, runner, and images and generates private
configuration and keys. It ends with:

```text
Prepared compose demo instance 'tutorial-evidence'.
Secrets: <path> (values are not printed)
```

`up` starts the services, bootstraps the devnet script anchor, and runs a
read-only readiness probe. Near the end of its output, look for:

```text
PASS command=probe
...
Yano status: http://127.0.0.1:7070/ui/app-chain/ (nodes: 7070, 7071, 7072)
Evidence UI: http://127.0.0.1:7080/
API key file: <path>
```

`direct` means the deterministic workflow emits the next result-driven
transition directly. `explicit`, the harness default, keeps the first version
of this workflow, in which a separate notify command advances that
continuation. The choice is part of the fresh chain's committed profile; pass
the same option to every later command.

If startup fails, the launcher rolls back the partial deployment. Use the
reported container health/log command rather than repeatedly deleting random
directories; retained L1 and app ledger identities are deliberately checked.

## 2. Publish version 1

```bash
./demo.sh publish \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id product-passport-001 \
  --sample-file samples/inspection-certificate.json
```

The runner waits for the whole dependency-ordered workflow shown above, from
staging to the anchor. On success it prints:

```text
PASS command=publish scenario=<scenario id>
```

On failure it prints `FAIL code=<CODE>` instead; for example,
`STORAGE_FAILED` when a storage connector did not confirm.

## 3. Verify without changing anything

```bash
./demo.sh verify \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id product-passport-001 \
  --business-version 1
```

`verify` is read-only. It does not submit an app message, stage an object, pin
content, publish Kafka data, or force another anchor. It re-reads the immutable
object and IPFS content, checks their hashes, checks the Kafka acknowledgement,
and validates application finality, state/effect proofs, and anchor linkage.
It ends with `PASS command=verify scenario=<scenario id>`.

Open the Evidence Explorer at the `Evidence UI` address that `up` printed,
<http://127.0.0.1:7080/> by default (or your `DEMO_UI_PORT`), and select the
record. The JSON preview is a
bounded presentation copy produced only after the runner re-downloads and
verifies the external bytes; it is not the browser's original submission.

## 4. Demonstrate immutability and versioning

An evidence version never changes. `publish` refuses an evidence id that
already exists with `EVIDENCE_ALREADY_EXISTS`, and the guided `run` command
stops with `REPUBLISH_REQUIRED`, before any connector write, when the bytes
differ from the retained version. To add a legitimate revision, create the
exact next immutable version:

```bash
./demo.sh republish \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id product-passport-001 \
  --business-version 2 \
  --sample-file samples/inspection-certificate-product-a-v2.json

./demo.sh verify \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id product-passport-001 \
  --business-version 1

./demo.sh verify \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id product-passport-001 \
  --business-version 2
```

Both versions remain independently selectable and verifiable. Any other
version number fails with `VERSION_CONFLICT`: the next version must be exactly
the latest plus one.

## 5. Demonstrate idempotent replay

```bash
./demo.sh replay \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id product-passport-001 \
  --business-version 2 \
  --sample-file samples/inspection-certificate-product-a-v2.json
```

The replay envelope finalizes, but the accepted business record, effect set,
and logical external outcomes do not duplicate: the runner checks that the
record and the Kafka end offset are unchanged, then prints
`PASS command=replay scenario=<scenario id>`. A file whose bytes differ from
version 2 stops with `REPLAY_INPUT_MISMATCH`.

## 6. Optional bounded parallel workload

Start small and use a fresh prefix:

```bash
./demo.sh load \
  --instance tutorial-evidence \
  --continuation direct \
  --load-mode pipeline \
  --count 8 \
  --concurrency 8 \
  --max-in-flight 8 \
  --id-prefix tutorial-load \
  --sample-file samples/inspection-certificate.json

./demo.sh verify \
  --instance tutorial-evidence \
  --continuation direct \
  --evidence-id tutorial-load-000008 \
  --business-version 1
```

This measures a full workflow with approvals, external actions, proofs, and
anchors—not raw HTTP admission TPS. The load command prints `PASS` with a
summary when every item succeeded, and the Explorer shows the aggregate report.

## 7. Stop and retain the scenario

```bash
./demo.sh status --instance tutorial-evidence --continuation direct
./demo.sh stop --instance tutorial-evidence --continuation direct
```

Run `up` with the same instance/profile to resume retained data. Delete data
only when you intend to retire that chain identity. Cleanup always names a
distinct replacement instance, so the retired chain id is never reused. To
retire this instance and also delete the shared devnet L1 it uses:

```bash
./demo.sh clean \
  --instance tutorial-evidence \
  --continuation direct \
  --scope all \
  --new-instance tutorial-evidence-next \
  --yes
```

`--scope all` refuses while another instance on the same devnet, such as
Tutorial 5's `tutorial-roles`, still uses the L1. Retire only this instance
and keep the L1 with `--scope instance` and the same `--new-instance` option.
The built Docker images are not removed; delete them with `docker image rm`
if you no longer need them.

## What is and is not proven

The scenario proves that the approved bytes and connector outcomes are bound
to threshold-finalized application state and an L1 anchor. It does not prove
that an inspection statement is factually true, that an actor had a legal
credential, or that every future copy of a referenced document remains
available. Those require domain onboarding, custody, retention, and possibly
independent real-world auditing.

## Go deeper

- Read the [plain-language evidence flow](../../EVIDENCE_CHAIN_DEMO.md).
- Compare lifecycle and pipeline scheduling.
- Stop one member and exercise catch-up using the isolated E2E gate.
- Replace RustFS with a tested S3-compatible production service while keeping
  the `object.put` contract unchanged.
- Review the [optional connector packaging and security matrix](../OPTIONAL_CONNECTORS.md)
  before translating the demo into a deployment.

Next: [domain-role authorization](05-domain-role-approvals.md).
