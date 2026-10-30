# Tutorial 1 — Your First App Ledger

To run this outcome with other operators, follow [Set up an application](../deployment/configure.md).

- **Goal:** run three members on one machine and watch them finalize the same
  event.
- **You'll learn:** how a submission becomes final, what `members agree`
  means, how to get a proof for a message, and what a restart keeps.
- **Before you start:** an extracted Yano X JVM distribution, Java 25, `curl`,
  `jq`, and `python3`. The rehearsal picks the first free HTTP ports from
  `8080` and node ports from `13337`, and prints them. No earlier tutorial is
  needed.
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
at that point. On the website, the "What if" scenarios of the picture show what
happens when a member is down.

<!-- illustration: first-chain-finality -->
1. **Start.** `try` starts alice's node first. It produces the local devnet, is
   a member, and proposes the blocks. Then bob and carol start. The threshold
   is 2 of 3.
2. **Check agreement.** `status` reads each chain's status from every member
   and prints `members agree` when they report the same chain.
3. **Submit.** You send an event to bob's node. Bob's node signs the envelope
   with its member key and answers 202 with a message id.
4. **Gossip.** Bob's node shares the envelope with alice's and carol's nodes.
5. **Propose.** Alice's node, the proposer of `orders`, builds a block, applies
   it, and sends it with its PREPARE vote.
6. **Vote.** Bob's and carol's nodes re-execute the block and vote PREPARE,
   then COMMIT, only if their own state root matches.
7. **Certify.** Two COMMIT votes form the finality certificate, and every node
   commits the block.
8. **Agree again.** `status` prints `members agree` with a higher height.
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
./yano.sh appchain try
```

`try` runs the real setup steps on this machine and prints each command as it
runs it. A coordinator creates an application called `demo` with one chain,
`orders`, which uses the `audit-log` recipe: the `ordered-log` state machine.
Three operators, alice, bob and carol, each make a member key and a node
directory under `./appchain-try/`, and the coordinator seals the application.
Then each operator joins and starts their node:

- alice is the lead. Her node produces the local Cardano devnet, is a member,
  and proposes every block of `orders`;
- bob's and carol's nodes are members that follow alice's devnet.

With three members, the threshold is a majority: 2. Startup can take a few
minutes, because alice's node starts first and the others download its devnet
genesis.

> **✓ You should see** the numbered steps, then a message finalized at the
> same height and root on every node:
>
> ```text
>   alice  height 1  root 3d7d41336074db26…
>   bob    height 1  root 3d7d41336074db26…
>   carol  height 1  root 3d7d41336074db26…
> ✓ Message 53c00fd815014ae5… is final at height 1 on every node
> ```

Each node's HTTP port is in its `node.json`. Read the three ports into your
shell; the commands below use them:

```bash
ALICE=$(jq -r .httpPort appchain-try/alice/yano-node/node.json)
BOB=$(jq -r .httpPort appchain-try/bob/yano-node/node.json)
CAROL=$(jq -r .httpPort appchain-try/carol/yano-node/node.json)
echo "alice $ALICE  bob $BOB  carol $CAROL"
```

## 2. Confirm agreement

```bash
./yano.sh appchain status --node-dir appchain-try/alice/yano-node
```

> **✓ You should see** `members online 3/3`, each member `online`, then:
>
> ```text
>   chain orders         height 1      members agree  members 3, 2 signatures  anchoring: not set up
> ```

`members agree` means every online member reports the same chain identity.
If two members report different state roots at the same height, `status`
also prints a warning that names the height. It is more than "three processes
are running", but it is a comparison, not a vote.

Open the status pages if you prefer a UI:

```bash
echo "http://127.0.0.1:$ALICE/ui/app-chain/"
echo "http://127.0.0.1:$BOB/ui/app-chain/"
echo "http://127.0.0.1:$CAROL/ui/app-chain/"
```

## 3. Submit a business event

Submit through bob's node rather than through the proposer:

```bash
curl -s -X POST "http://127.0.0.1:$BOB/api/v1/app-chain/chains/orders/messages" \
  -H 'Content-Type: application/json' \
  -d '{"topic":"orders","body":"{\"event\":\"order-created\",\"orderId\":\"A-1001\",\"quantity\":4}"}' \
  | jq .
```

> **✓ You should see** a JSON object with a `messageId`.

A rehearsal node accepts submissions without an API key. A node can require
one for every call; [Set up an application](../deployment/configure.md#7-send-a-message-and-see-it-final)
sends it with `X-API-Key`, which works either way.

You did not sign anything. Bob's node checked the command, signed the envelope
with its own member key, and gossiped it. Alice's node put it in a block, two
of the three members voted for that block, and every member applied the same
bytes. The submission itself only means "queued on bob's node".

After a few seconds, inspect the finalized blocks and agreement:

```bash
curl -s "http://127.0.0.1:$ALICE/api/v1/app-chain/chains/orders/blocks" | jq .
./yano.sh appchain status --node-dir appchain-try/alice/yano-node
```

> **✓ You should see** a new block with `"messageCount": 1` and
> `certSignatures` of at least 2, then `chain orders` with a higher height and
> `members agree`.

## 4. Capture a message ID and its proof

This time, submit through carol's node and keep the message id:

```bash
RESPONSE=$(curl -s -X POST \
  "http://127.0.0.1:$CAROL/api/v1/app-chain/chains/orders/messages" \
  -H 'Content-Type: application/json' \
  -d '{"topic":"orders","body":"{\"event\":\"packed\",\"orderId\":\"A-1001\"}"}')

echo "$RESPONSE" | jq .
MESSAGE_ID=$(echo "$RESPONSE" | jq -r .messageId)
```

> **✓ You should see** a JSON object with `messageId`,
> `"chainId": "orders"`, and `"topic": "orders"`.

Wait until the message is final. A finalized message has a block height:

```bash
HEIGHT=0
for attempt in $(seq 1 30); do
  HEIGHT=$(curl -s "http://127.0.0.1:$ALICE/api/v1/app-chain/chains/orders/messages/$MESSAGE_ID" \
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
  "http://127.0.0.1:$ALICE/api/v1/app-chain/chains/orders/proof-subjects/finalized-message-v1/proof" \
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
`sender` is carol's member key, because carol's node accepted and signed the
envelope; `jq -r .publicKey appchain-try/carol/yano-node/node.json` prints it.
Tutorial 7 connects a state root to a Cardano anchor.

## 5. Stop and restart

`stop` keeps both the L1 and the app ledger data. Stop the three nodes, then
start them again with the lead first, because the other nodes follow her
devnet:

```bash
for name in carol bob alice; do
  ./yano.sh appchain stop --node-dir appchain-try/$name/yano-node
done
for name in alice bob carol; do
  ./yano.sh appchain start --node-dir appchain-try/$name/yano-node
done
./yano.sh appchain status --node-dir appchain-try/alice/yano-node
```

> **✓ You should see** `✓ Stopped carol; its data is kept` for each node,
> then `✓ Node alice is up at http://127.0.0.1:…` for each node, then the same
> height and `members agree` as before the stop.

The retained heights and roots return unchanged before any new traffic is
finalized. Explore what each node keeps in its directory, for example
`appchain-try/alice/yano-node`:

<!-- illustration: node-directory -->

- `node.json`: the operator name, public key, addresses, and ports;
- `secrets/`: the member key and the node's API key;
- `apps/demo.chain`: the sealed application the node joined;
- `config/`: `consensus.yaml` and `node.yaml`, written by `join`, and
  `local.yaml` for the node's own settings;
- `data/`: `chainstate` (Cardano L1) and `appchain-chainstate` (the app
  ledger), plus `appchain-indexers` (rebuildable read indexes) once a read
  index is enabled;
- `logs/node.log`: the node's log.

`appchain-try/coordinator/` holds the draft, the member cards, and the sealed
`demo.chain`. See [where each setting lives](../deployment/configure.md#where-each-setting-lives).

This is a useful distinction:

- **restart:** the same chain identity and retained state;
- **`try --clean`:** delete the rehearsal; the next `try` seals a new
  application with a new identity and history.

The members and the threshold are sealed in `demo.chain`. To try different
values, rehearse in a new directory (see [Go deeper](#go-deeper)).

## What you just proved

- A submission can enter through any member, and that member signs it.
- A threshold of members, not one REST server, finalizes the ordered block.
- All members deterministically derive the same state root.
- A message has a proof against that root.
- A retained restart recovers the same application history.

You did **not** yet prove Cardano settlement or the truth of the order fields.
Those are separate trust layers.

## Optional extras

These sections reuse the running rehearsal. Skip them if you are short of
time.

### A. Run a small `ordered-log` load test

The distribution includes the showcase's parallel load driver,
`examples/showcase/tools/loadtest.sh`. It finds members on consecutive HTTP
ports, starting at `YANO_SHOWCASE_HTTP_BASE`. `try` gives alice, bob and carol
consecutive ports when they are free; check that `$BOB` and `$CAROL` are
`$ALICE + 1` and `$ALICE + 2`. Start with a bounded workload of 500 messages,
10 concurrent submitters, and payloads of about 256 bytes, all sent to alice's
node:

```bash
YANO_SHOWCASE_HTTP_BASE=$ALICE examples/showcase/tools/loadtest.sh orders -n 500 -c 10 -s 256
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
ingress path, then confirm that every member still agrees:

```bash
YANO_SHOWCASE_HTTP_BASE=$ALICE examples/showcase/tools/loadtest.sh orders -n 1000 -c 20 -s 256 --spread
./yano.sh appchain status --node-dir appchain-try/alice/yano-node
```

This is a functional throughput exercise, not a production benchmark. Results
depend on the machine, the JVM, the devnet producer, the block interval, the
payload size, and the member count. Record those inputs when comparing runs.
The test appends real finalized messages to the retained `orders` history.

For capacity settings and workload boundaries, see the
[`ordered-log` reference](https://github.com/bloxbean/yano/blob/main/docs/appchain/state-machines/ordered-log.md#operational-tuning).

### B. Try an effect

An effect is an external action, such as a webhook call, that a state machine
commits as part of a final block and an executor node carries out.
[Tutorial 6](06-webhook-effects.md) runs the full emit, delivery, result, and
proof lifecycle: a stock approval reaches its threshold and one node calls a
local webhook receiver. It needs its own rehearsal, so finish or clean up
this one first.

A production workflow should carry an explicit business id or finalized source
message id in its committed command or effect payload. The proof establishes
that the effect intent was committed; a real external action also depends on a
trusted executor and a verifiable receipt.

### C. Governed member onboarding

The application is `governed`, so its members can add a fourth node, dave, on
the same host. These are the same steps as in
[grow and change your application](../deployment/add-chain.md).

First, dave makes a key, a node directory, and a member card from the sealed
file. Choose two free ports for his node; the example uses the ones after
carol's:

```bash
CAROL_NODE=$(jq -r .listenPort appchain-try/carol/yano-node/node.json)
./yano.sh appchain request appchain-try/coordinator/demo.chain --name dave \
  --host 127.0.0.1 --node-port $((CAROL_NODE + 1)) --http-port $((CAROL + 1)) \
  --node-dir appchain-try/dave/yano-node --output appchain-try/coordinator --yes
```

> **✓ You should see** `✓ Wrote …/dave.member` with dave's key id.

Then every existing operator approves the card on their own running node.
Each `approve` waits until enough operators have approved, so on one machine
run the three in parallel:

```bash
for name in alice bob carol; do
  ./yano.sh appchain approve appchain-try/coordinator/dave.member \
    --node-dir appchain-try/$name/yano-node --heartbeat &
done
wait
```

With 2 of 3, a fourth member would leave 2 of 4, which cannot certify blocks
(a threshold must be more than half the members). `approve` therefore raises
each chain's threshold to 3 first and waits until that is active, then adds
dave and waits again, then restarts the operator's node once to connect to
him.

A change activates ten blocks after enough operators approve, and a chain
makes blocks only when it has messages. `--heartbeat` lets `approve` send
small heartbeat messages to an idle chain. On an `ordered-log` chain such as
`orders` each heartbeat is a permanent entry on the `appchain.heartbeat`
topic; your existing entries are unchanged. Without `--heartbeat`, send
ordinary traffic instead.

> **✓ You should see** `orders: first raising the threshold from 2 to 3 (4 members)`,
> then `✓ orders: dave is a member` and `Restarting this node to connect to dave …`
> from each operator.

When every operator has approved, dave joins and starts. `join` shows the
application and asks you to confirm the fingerprint:

```bash
./yano.sh appchain join appchain-try/coordinator/demo.chain --node-dir appchain-try/dave/yano-node
./yano.sh appchain start --node-dir appchain-try/dave/yano-node
./yano.sh appchain status --node-dir appchain-try/dave/yano-node
```

> **✓ You should see** `members online 4/4` and `chain orders … members agree
> members 4, 3 signatures`. The new member votes from the height at which
> the change activated.

A newly joined node accepts submissions only from that activation height on.
Before then, a submission through dave's node is refused with HTTP `503`
("not an active member at height ... yet"). Check a chain's
`memberActiveForNextBlock` field and retry once it is `true`:

```bash
DAVE=$(jq -r .httpPort appchain-try/dave/yano-node/node.json)
curl -s "http://127.0.0.1:$DAVE/api/v1/app-chain/chains/orders/status" \
  | jq .memberActiveForNextBlock
```

## Clean up

If you added dave, stop his node first: `try --clean` stops only alice, bob
and carol.

```bash
./yano.sh appchain stop --node-dir appchain-try/dave/yano-node   # only if you added dave
./yano.sh appchain try --clean
```

`try --clean` stops the nodes and deletes everything under `appchain-try/`,
including the chain identities.

## Troubleshooting

| You see | What it means and what to do |
|---|---|
| `…/appchain-try already exists. Look around with …` | An earlier rehearsal is still there. Look at it with `status`, or remove it with `./yano.sh appchain try --clean`. |
| `The rehearsal stopped at that step (exit …)` | The command printed just above failed. If a node did not start, read the first error in its log, for example `appchain-try/bob/yano-node/logs/node.log`. |
| `members report different state roots at height …` from `status` | A node can be one block behind while `status` reads it. Run `status` again after a few seconds. If it persists, check that node's log. |
| `Port … is already in use by another program.` from `request` | Choose other free ports for dave with `--http-port` and `--node-port`. |
| `This node must be running; run  yano.sh appchain start  first` from `approve` | Start that operator's node with `start --node-dir`, then run `approve` again. |
| `orders: still waiting for …` and exit code 3 from `approve` | Not every operator has approved yet, or the chain has not made ten more blocks. Run the same `approve` again: it continues where it stopped. |

## Go deeper

- **Try a stricter threshold.** The threshold is sealed in the application, so
  rehearse a second application in a new directory. A small template sets the
  threshold and the chain; `try --from` uses it:

  ```bash
  cat > strict.template.yaml <<'EOF'
  threshold: all
  chains:
    - id: orders
      recipe: audit-log
  EOF
  ./yano.sh appchain try --from strict.template.yaml --dir appchain-try-strict
  ```

  `seal` prints `3 members, 3 signatures per block`, and every block now needs
  all three votes, so one stopped member then halts finality (the website's
  "Threshold 3, one member down" scenario shows it). If the first rehearsal still runs,
  `try` picks the next free ports. Remove this rehearsal when you are done:

  ```bash
  ./yano.sh appchain try --clean --dir appchain-try-strict
  rm strict.template.yaml
  ```

- Read Yano's
  [consensus guide](https://github.com/bloxbean/yano/blob/main/docs/APP_CHAIN_CONSENSUS_GUIDE.md)
  for the proposer, votes, certificates, replay, and catch-up.

**Next:** [Tutorial 2 — registry ownership and state proofs](02-registry-and-proofs.md).
