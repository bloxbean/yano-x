# Tutorial 1 — Your First App Ledger

[Open this outcome in App-Chain Studio](../../../tooling/studio/src/main/web/index.html#recipe=audit-log&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=host&name=my-appchain&chainId=my-appchain)

- **Goal:** run three members on one machine and watch them finalize the same
  event.
- **You'll learn:** how a submission becomes final, what `AGREED` means, how to
  get a proof for a message, and what a restart keeps.
- **Before you start:** an extracted Yano X JVM distribution, Java 25, `curl`,
  `jq`, and `python3`. The launcher uses HTTP ports `7070`–`7072` and node
  ports `13337`–`13339`; if they are busy it picks the next free range. No
  earlier tutorial is needed.
- **Level:** beginner
- **Time:** about 15 minutes for sections 1–5. The optional extras add 10–15
  minutes.
- **Outcome:** three members finalize the same ordered event, expose the same
  state root, and keep both across a restart.

This tutorial uses Yano's self-contained Cardano devnet and the built-in
`ordered-log` state machine. It needs no external Cardano node, wallet, funds,
Kafka, or plugin.

Yano's tooling calls an app ledger an *app chain*, so the commands below say
`appchain`.

## On this page

1. [Choose the working directory and start](#1-choose-the-working-directory-and-start)
2. [Confirm agreement](#2-confirm-agreement)
3. [Submit a business event](#3-submit-a-business-event)
4. [Capture a message ID and its proof](#4-capture-a-message-id-and-its-proof)
5. [Stop and restart](#5-stop-and-restart)
6. [Review what you proved](#what-you-just-proved)
7. [Optional extras](#optional-extras): load test, effects, and member
   onboarding
8. [Clean up](#clean-up) and [troubleshooting](#troubleshooting)

## What you will see

Here is the whole tutorial in one picture. Each step shows the command you run
at that point. The "What if" scenarios show what happens when a member is down.

<!-- illustration: first-chain-finality -->
1. **Start.** The launcher starts node 0, which produces the local devnet and is
   a member, then nodes 1 and 2. The threshold is 2 of 3.
2. **Check agreement.** `cluster status` compares each chain's state root
   across the nodes and prints `AGREED` when they match.
3. **Submit.** You send an event to node 1. Node 1 signs the envelope with its
   member key and answers 202 with a message id.
4. **Gossip.** Node 1 shares the envelope with nodes 0 and 2.
5. **Propose.** Node 0, the proposer of `orders-chain`, builds a block, applies
   it, and sends it with its PREPARE vote.
6. **Vote.** Nodes 1 and 2 re-execute the block and vote PREPARE, then COMMIT,
   only if their own state root matches.
7. **Certify.** Two COMMIT votes form the finality certificate, and every node
   commits the block.
8. **Agree again.** `cluster status` prints `AGREED` with a higher tip.
<!-- /illustration -->

## 1. Choose the working directory and start

Every tutorial runs `./yano.sh` from the directory that contains it. Choose one
setup.

From an extracted release, use its top-level directory, the one containing
`yano.sh` and `yano.jar`:

```bash
cd /path/to/extracted/yano-x-jvm-<version>
./yano.sh appchain help
```

From a source checkout, follow the
[Yano X distribution build](../../BUILD_DISTRIBUTIONS.md), extract the JVM
archive it produces, and use that top-level directory. Yano X has no `:app`
task. For the curated thirteen-chain demo, use the
[showcase quickstart](../deployment/quickstart.md) instead.

The remaining commands are the same for both setups:

```bash
export YANO_CLUSTER_DIR=/tmp/yano-tutorial-first-chain
./yano.sh appchain cluster start 3
```

The launcher starts:

- node 0 as the local Cardano devnet producer and a member. It is also the
  proposer for `orders-chain`;
- nodes 1 and 2 as members that follow node 0's devnet; and
- the three chains defined in `config/application-appchain.yml`:
  `orders-chain` (`ordered-log`), `registry-chain` (`kv-registry`, used in
  tutorial 2), and `effects-chain` (`approvals`, used by the optional effects
  demo).

With three members, the threshold defaults to a majority: 2. Startup takes a
minute or two, because node 0 runs for 25 seconds before the other nodes join.

> **✓ You should see**
>
> ```text
> Starting 3-node app-chain cluster
>   ...
>   chains  : orders-chain registry-chain effects-chain
>   members : 3   threshold: 2
>   data    : /tmp/yano-tutorial-first-chain
>   ports   : http 7070-7072   n2n 13337-13339
> ...
> Cluster up.
> ```

If the default ports are busy, the launcher prints a line such as
`HTTP range 7070-7072 unavailable; using 7073-7075`. Use the printed range in
the commands below.

## 2. Confirm agreement

```bash
./yano.sh appchain cluster status
```

> **✓ You should see** each node marked `[ready]`, then:
>
> ```text
> Consistency (per chain, roots must match across nodes):
>   orders-chain: AGREED (...)
>   registry-chain: AGREED (...)
>   effects-chain: AGREED (...)
> ```

`AGREED` means every running node reports the same authenticated state root
for that chain. It is more than "three processes are running", but it is a
comparison, not a vote: it compares current roots, not heights.

Open the status pages if you prefer a UI:

- <http://127.0.0.1:7070/ui/app-chain/>
- <http://127.0.0.1:7071/ui/app-chain/>
- <http://127.0.0.1:7072/ui/app-chain/>

## 3. Submit a business event

Submit through node 1 rather than through the proposer:

```bash
./yano.sh appchain cluster submit orders-chain orders \
  '{"event":"order-created","orderId":"A-1001","quantity":4}' \
  --node 1
```

> **✓ You should see** `submitted <message id>... to orders-chain`.

You did not sign anything. Node 1 checked the command, signed the envelope
with its own member key, and gossiped it. Node 0 put it in a block, two of the
three members voted for that block, and every member applied the same bytes.
The submission itself only means "queued on node 1".

After a few seconds, inspect the finalized blocks and agreement:

```bash
curl -s http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/blocks | jq .
./yano.sh appchain cluster status
```

> **✓ You should see** a block with `"messageCount": 1` and `certSignatures` of
> at least 2, then `orders-chain: AGREED (...)` with the same, higher tip on
> every node.

## 4. Capture a message ID and its proof

For a proof, call the same public API directly. This time, submit through
node 2:

```bash
RESPONSE=$(curl -s -X POST \
  http://127.0.0.1:7072/api/v1/app-chain/chains/orders-chain/messages \
  -H 'Content-Type: application/json' \
  -d '{"topic":"orders","body":"{\"event\":\"packed\",\"orderId\":\"A-1001\"}"}')

echo "$RESPONSE" | jq .
MESSAGE_ID=$(echo "$RESPONSE" | jq -r .messageId)
```

> **✓ You should see** a JSON object with `messageId`,
> `"chainId": "orders-chain"`, and `"topic": "orders"`.

Wait until the message is final. A finalized message has a block height:

```bash
HEIGHT=0
for attempt in $(seq 1 30); do
  HEIGHT=$(curl -s "http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/messages/$MESSAGE_ID" \
    | jq -r '.height // 0' 2>/dev/null)
  [ "${HEIGHT:-0}" -gt 0 ] && break
  sleep 1
done
echo "final at height $HEIGHT"
```

> **✓ You should see** `final at height` followed by a number greater than 0.

Then request a typed proof that the message is recorded:

```bash
curl -s -X POST \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/proof-subjects/finalized-message-v1/proof" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg id "$MESSAGE_ID" '
    {coordinates:{"message-id":$id}, view:"latest",
     claim:{claimId:"recorded",operands:{}}, includeEvidence:false}')" \
  | jq '{stateRoot:.proof.stateRoot,presence:.proof.presence,position:.fact.fields,claim:.claimResult.satisfied}'
```

> **✓ You should see** `"presence": "PRESENT"`, `"claim": true`, and a
> `position` with the message's `height`, `index`, `topic`, and `sender`.

The typed subject resolves the public message ID to its namespaced physical
state key and connects that record to the member's committed state root. The
`sender` is node 2's member key, because node 2 accepted and signed the
envelope; `./yano.sh appchain cluster keys` prints each node's key. Tutorial 7
connects a state root to a Cardano anchor.

## 5. Stop and restart

`stop` keeps both the L1 and the app ledger data:

```bash
./yano.sh appchain cluster stop
./yano.sh appchain cluster start 3
./yano.sh appchain cluster status
```

> **✓ You should see** `stopped 3 node(s)`, then `Cluster up.`, then the same
> tips and `AGREED` roots as before the stop.

The retained tips and roots return unchanged before any new traffic is
finalized. Explore what the launcher keeps on disk:

<!-- illustration: cluster-on-disk -->

This is a useful distinction:

- **restart:** the same chain identity and retained state;
- **clean:** delete the data and start a new identity and history next time.

The node count and threshold are part of the retained identity. To try
different values, use a new `YANO_CLUSTER_DIR` (see [Go deeper](#go-deeper)).

## What you just proved

- A submission can enter through any member, and that member signs it.
- A threshold of members, not one REST server, finalizes the ordered block.
- All members deterministically derive the same state root.
- A message has a proof against that root.
- A retained restart recovers the same application history.

You did **not** yet prove Cardano settlement or the truth of the order fields.
Those are separate trust layers.

## Optional extras

These sections reuse the running cluster. Skip them if you are short of time.

### A. Run a small `ordered-log` load test

The distribution includes a parallel load driver for the running local
cluster. Start with a bounded workload of 500 messages, 10 concurrent
submitters, and payloads of about 256 bytes:

```bash
./yano.sh appchain cluster loadtest orders-chain -n 500 -c 10 -s 256
```

Plain load-test mode suits any-bytes machines such as `ordered-log`. It
submits numbered UTF-8 bodies on the `load` topic, waits for the pending pool
to drain, and reports two different rates:

```text
==================== throughput ====================
  submitted attempts : 500
  accepted (2xx)     : 500
  dropped  (429 pool): 0
  errors             : 0

  SUBMIT rate        : ... msg/s
  finalized msgs     : 500   in ... block(s)
  FINALIZE rate      : ... msg/s
  end-to-end rate    : ... msg/s
====================================================
```

- **SUBMIT rate** measures how quickly an ingress REST API accepts messages.
- **FINALIZE rate** measures how quickly messages enter threshold-certified
  blocks; this is the meaningful chain-throughput measurement.
- **dropped (429 pool)** means backpressure worked because the pending pool
  filled. Reduce concurrency or tune the pool and block limits deliberately.
- **errors** are non-backpressure request failures and should be investigated.

Spread submissions across all ready members to exercise gossip from every
ingress path, then confirm that every member still exposes the same root:

```bash
./yano.sh appchain cluster loadtest orders-chain -n 1000 -c 20 -s 256 --spread
./yano.sh appchain cluster status
```

This is a functional throughput exercise, not a production benchmark. Results
depend on the machine, the JVM, the devnet producer, the block interval, the
payload size, and the member count. Record those inputs when comparing runs.
The test appends real finalized messages to the retained `orders-chain`
history.

For capacity settings and workload boundaries, see the
[`ordered-log` reference](https://github.com/bloxbean/yano/blob/main/docs/appchain/state-machines/ordered-log.md#operational-tuning).

### B. Try an effect

The default `effects-chain` can demonstrate the full emit, external-worker,
result, and proof lifecycle without Kafka, S3, IPFS, or a real webhook:

```bash
./yano.sh appchain cluster effect demo
./yano.sh appchain cluster effect demo "order A-1001 approved"
```

> **✓ You should see** `Effect emitted       effects-chain height=...`,
> `Delivery             CONFIRMED`, and `Proof                 AVAILABLE (...)`.

With no argument, the demo uses `hello from Yano effects`; one quoted argument
replaces that message. Briefly, the command:

1. creates a unique one-approval item on the separate `effects-chain`;
2. wraps your text in a JSON payload, then submits `PROPOSE` and `APPROVE`
   commands to its `approvals` state machine;
3. keeps the item decision `APPROVED` and emits one generic app-final
   `demo.webhook` effect when the approval threshold is reached;
4. acts as a simulated external worker that claims the effect and reports a
   synthetic successful delivery; no real webhook is called; and
5. feeds the result back through the effect lifecycle and checks that the
   finalized effect proof is available.

The supplied text is illustrative data. It is not linked to an event on
`orders-chain`, even if it contains an order id. A production workflow should
carry an explicit business id or finalized source message id in its committed
command or effect payload. The proof establishes that the effect intent was
committed; a real external action also depends on a trusted executor and a
verifiable receipt.

For the complete lifecycle and production webhook configuration, continue
with [webhook effects](06-webhook-effects.md).

### C. Governed member onboarding

You can also govern, start, catch up, and verify a fourth node on the same host:

```bash
./yano.sh appchain cluster node join 3
./yano.sh appchain cluster status
```

With the default 2-of-3, a fourth member would leave 2-of-4, which cannot
certify blocks (a threshold must be more than half the members). The launcher
therefore raises each chain's threshold to 3 first, advances the idle chains
until that change is active, and only then records the new member with three
approvals.

> **✓ You should see** `raising 'orders-chain' threshold 2 -> 3 first`, then
> `membership approval 3/3` for each chain, and finally
> `node 3 joined and caught up with member key ...`. The new member votes from
> the activation height each chain reports.

For an externally managed node, the lower-level
`appchain cluster member add <public-key>` command records membership but does
not configure or start the external process. Same-host `node join` performs
both steps.

## Clean up

```bash
./yano.sh appchain cluster clean
unset YANO_CLUSTER_DIR
```

`clean` stops the nodes and deletes everything under `YANO_CLUSTER_DIR`,
including the chain identities.

## Troubleshooting

| You see | What it means and what to do |
|---|---|
| `HTTP range 7070-7072 unavailable; using ...` | Another process holds the default ports. Use the printed range in every URL. |
| `app-chain identity differs from retained state; restore the original profile or use a new data directory` | You started the same `YANO_CLUSTER_DIR` with a different node count or `--threshold`. Start it with the original values, or export a new `YANO_CLUSTER_DIR`. |
| `node 1 is already running` | The cluster is still running. Run `./yano.sh appchain cluster stop` first. |
| `node 0 not ready within 180s` or `node 2 not ready after 3 attempts` | The message names the node's log file. Read its first error, for example with `./yano.sh appchain cluster logs 2`. |
| `orders-chain: MISMATCH` | A node can be one block behind while `status` reads it. Run `status` again after a few seconds. If it persists, check that node's log. |
| `config not found: .../config/application-appchain.yml` | `YANO_HOME` points somewhere other than the distribution. Run `unset YANO_HOME`, then retry from the distribution's top-level directory. |

## Go deeper

- **Try a stricter threshold.** The threshold is part of the cluster's
  identity, so start a second cluster in a new directory:

  ```bash
  ./yano.sh appchain cluster stop
  export YANO_CLUSTER_DIR=/tmp/yano-tutorial-threshold-3
  ./yano.sh appchain cluster start 3 --threshold 3
  ```

  The start banner shows `threshold: 3`, and every block now needs all three
  votes. The "Threshold 3, one member down" scenario above shows why one
  stopped member then halts finality. Remove this cluster with
  `./yano.sh appchain cluster clean`.
- Read Yano's
  [consensus guide](https://github.com/bloxbean/yano/blob/main/docs/APP_CHAIN_CONSENSUS_GUIDE.md)
  for the proposer, votes, certificates, replay, and catch-up.

**Next:** [Tutorial 2 — registry ownership and state proofs](02-registry-and-proofs.md).
