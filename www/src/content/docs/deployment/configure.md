---
title: "Set up an application with other operators"
description: "A live application whose members all agree, and a message that every node has finalized."
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/appchain/deployment/configure.md"
---
- **Goal:** run your own application with other organizations, each running
  their own node, and check that everyone has the same chain.
- **Before you start:** every operator needs the same extracted Yano X JVM
  release and Java 25 (or the self-contained edition for their platform, which
  bundles Java), and `curl`. For the message check at the end you also need
  `jq`.
- **Time:** 10 minutes for the rehearsal; about 30 minutes with real
  operators, most of it sending files and one short call.
- **Outcome:** a live application whose members all agree, and a message that
  every node has finalized.

There are two roles. The **coordinator** defines the application. Each
**operator** runs one member node; the coordinator is usually one of them.
Run every command from the extracted Yano X directory, the one that contains
`yano.sh`.

<!-- illustration: setup-ceremony -->
1. **New.** The coordinator runs `new`, which writes `supply.draft.yaml`, and
   sends it to every operator.
2. **Request.** Each operator runs `request supply.draft.yaml`, which makes
   their key and a member card such as `alice.member`, and sends the card back.
3. **Seal.** The coordinator puts the cards beside the draft and runs `seal`,
   which writes `supply.chain` and prints the chain fingerprint.
4. **Join.** On a call, everyone compares the fingerprint and each key id.
   Each operator then runs `join supply.chain` and confirms.
5. **Start.** Each operator runs `start`. On a devnet the lead starts first.
6. **Status.** Anyone runs `status supply` and sees `Live`.
<!-- /illustration -->

Every command ends with a `Next:` line that says what to do next. Add `--help`
to any command to see its options.

## 1. Practise on one machine

`try` runs every real step on this machine, with one coordinator and three
simulated operators (alice, bob and carol), each in their own directory under
`./appchain-try/`. It prints each command as it runs it, so you can read along.

```bash
./yano.sh appchain try
```

> **✓ You should see** the seven numbered steps, then a message finalized at the
> same height and root on every node:
>
> ```text
>   alice  height 1  root 3d7d41336074db26…
>   bob    height 1  root 3d7d41336074db26…
>   carol  height 1  root 3d7d41336074db26…
> ✓ Message 53c00fd815014ae5… is final at height 1 on every node
> ```

Look around before you clean up:

```bash
./yano.sh appchain status --node-dir appchain-try/alice/yano-node
./yano.sh appchain try --clean     # stops the three nodes and deletes appchain-try/
```

`try --clean` deletes only its own rehearsal directory.

To rehearse your own chains, give `try` the same `--chain` and `--answer`
values as `new`. Without them it uses one `orders=audit-log` chain. The final
message check runs when one of the chains uses `audit-log`.

```bash
./yano.sh appchain try --chain orders=audit-log --chain registry=owned-registry
./yano.sh appchain try --chain flow=bindings:examples/bindings/approval-to-audit.yaml
```

**Prefer a browser?** `./yano.sh appchain ui` opens a page on this computer
(127.0.0.1 only) with the same steps: create (topic policies included), member
cards, seal, compare and join, start, status, approve, topic votes and anchor.
A progress bar shows where the application is, and once this node has joined,
its live dashboard comes first. Every button runs the command shown under it,
so the files are the same as from the terminal. The page can vote on a topics
plan you received; planning and signing stay in the terminal with their keys.

## 2. The coordinator creates the draft

```bash
./yano.sh appchain new supply
```

A short wizard asks for the Cardano network, the operator names (you first),
and each chain: its id, and whether it runs one ready-made state machine
(a *recipe*) or several working together. If a recipe needs answers, such as
the genesis address of an `eutxo-ledger` chain, the wizard asks for each one.
`./yano.sh appchain recipes` lists the recipes; `recipes --format json` also
shows the answers each needs (`nonSecretAnswers`). To
skip the wizard, pass everything as flags, with one `--answer
<chain>.<name>=<value>` per answer:

```bash
./yano.sh appchain new supply --network devnet --operators alice,bob,carol \
  --chain orders=audit-log --yes
```

> **✓ You should see** `✓ Wrote ./supply.draft.yaml`, the invite code, the
> operators with the lead, and how many signatures each block needs.

The draft is a YAML file you can edit until you seal it:

```yaml
name: supply
invite: 7c1e-52aa-9d03            # written by `new`; do not edit
network: devnet                   # devnet | preview | preprod | mainnet
operators: [alice, bob, carol]    # the first is the lead
threshold: majority               # signatures per block: majority | two-thirds | all | a number
membership: governed
sequencing: fixed                 # fixed: the lead proposes every block | rotating: members take turns
chains:
  - id: orders                    # one state machine, chosen by recipe
    recipe: audit-log
  - id: approvals                 # several state machines joined by bindings
    recipe: declarative-composite
    bindings: ./approvals.bindings.yaml
    anchor: {mode: script, every-blocks: 10}   # optional anchor policy
    topics:                       # optional: namespaces and writer policies
      create: members             # who creates namespaces: members (vote) | registrars | anyone
      unregistered: open          # messages on topics no namespace covers: open | reject
```

- **The lead** is the first operator. On a devnet its node also produces the
  Cardano devnet. With `sequencing: fixed` (the default) the lead's node
  proposes every block, and the lead cannot be removed later, so the lead must
  back up their node directory.
- **Sequencing** chooses who proposes blocks. `fixed` keeps one proposer, the
  lead. `rotating` (`new --sequencing rotating`) lets the members take turns
  over Cardano slot windows, so no chain depends on one proposer, and
  `approve --remove` can then remove the lead too. On a devnet the lead's node
  still produces the Cardano devnet either way.
- **The threshold** is how many members must sign each block. With three
  operators, `majority` means two. While a chain grows by one member, it
  briefly needs one more signature.
- **A composite chain** runs several state machines that commit together.
  Create one with `--chain flow=bindings:<file>`, or choose "several" in the
  wizard and pick a starter; `new` copies the bindings file next to the draft.
  See [declarative bindings](/bindings/).
- **Topic policies** (`topics:`) let a chain give each namespace owners, a
  writer policy and a payload schema, and let users write through short-lived
  session keys. `create` says who creates a top-level namespace: the members
  by a vote (`members`), named operators or keys (`registrars: [alice]`), or
  anyone first come (`anyone`); members can always create and recover one by
  vote. `new --topics approvals=members` writes the block, and the wizard asks
  once per chain. It needs `membership: governed` and is fixed at seal. Keep
  `unregistered: open` unless every topic must be registered: a chain that
  refuses unregistered topics makes blocks only from registered traffic. See
  [protect a topic](#optional-protect-topics).
- **Answers** a recipe needs sit under the chain as `answers:`; `new --answer
  ledger.eutxoGenesisLovelace=100000000` writes the same thing. Answers are
  public: never put a secret in the draft.

Send `supply.draft.yaml` to every operator, including yourself, or hand out
join links instead (next section). Once operators have made cards, do not run
`new --force`: a new invite code makes their cards invalid.

## Easier: hand out join links

Steps 3 to 5 below pass three kinds of file around by hand: the draft out,
the cards back, the sealed file out again. A join link carries all three. The
coordinator serves the draft for a limited time:

```bash
./yano.sh appchain invite supply.draft.yaml --serve --host coord.example.com
```

`--host` is the address the operators reach the coordinator's machine at
(default port `7099`; change it with `--port`, and open it to the operators).
The links stay valid for 24 hours (`--expires-hours`).

> **✓ You should see** one link per operator:
>
> ```text
> Serving supply.draft.yaml on port 7099 until 2026-10-28 09:14 UTC. Send each operator their link:
>   alice        yano.sh appchain request yano://coord.example.com:7099/5f0c…#7c1e-52aa-9d03
>   bob          yano.sh appchain request yano://coord.example.com:7099/a91d…#7c1e-52aa-9d03
>   carol        yano.sh appchain request yano://coord.example.com:7099/03be…#7c1e-52aa-9d03
> ```

Send each operator their own line. Each operator runs it (with their own
`--host`, ports and other `request` options as in step 3):

```bash
./yano.sh appchain request 'yano://coord.example.com:7099/5f0c…#7c1e-52aa-9d03' --host node-a.example.com
```

The command fetches the draft, makes the key and card, sends the card to the
coordinator, and waits. When every card is in, the coordinator's terminal asks
`Seal now?` (`invite --serve --seal` seals without asking). Each waiting
`request` then saves `supply.chain` and, in a terminal, continues straight into
`join`, which asks you to confirm the fingerprint and key ids as in step 5. The
listener stops once every operator has the sealed file.

`request <link> --no-wait` sends the card and returns; run the same command
again later to receive the sealed file, and running it again after an
interruption resumes. Each link works for one operator only. Links use plain
HTTP and carry only public data (the draft, the cards, and the sealed file), so
they do not replace the call: compare the chain fingerprint and every key id
before anyone starts.

## 3. Each operator makes a key and a member card

On your own machine:

```bash
./yano.sh appchain request supply.draft.yaml
```

It asks for your operator name and the address other members reach your node
at: a host name or IP, and two ports (defaults: node-to-node `13337`, HTTP
`8080`). On a devnet where everyone is on one machine, the host is
`127.0.0.1` and each operator needs different ports; the examples below use
node ports `14437`–`14439` and HTTP ports `9180`–`9182` for alice, bob and
carol. For scripts, pass `--name`, `--host`, `--node-port`, `--http-port`, and
`--yes`.

> **✓ You should see**
>
> ```text
> ✓ Created node directory …/yano-node with a new member key (keep it safe; it never leaves this directory)
> ✓ Wrote ./alice.member  (key id a6622032b8e615ee, reachable at 127.0.0.1:14437)
> ```

Your private key is now in `yano-node/secrets/member.key`, readable only by
you. Never send it. Send `alice.member` to the coordinator, and note your
**key id**: you read it out on the call.

## 4. The coordinator seals the application

Put every card next to the draft. To see who is still missing:

```bash
./yano.sh appchain status supply.draft.yaml
```

When every card is there, seal:

```bash
./yano.sh appchain seal supply.draft.yaml
```

> **✓ You should see**
>
> ```text
> ✓ Sealed .../supply.chain
> Application supply on devnet (Yano …, Yano X …)
>   3 members, 2 signatures per block, governed membership
>   alice        key a6622032b8e615ee  127.0.0.1:14437  (lead)
>   bob          key 9be1fb9123767845  127.0.0.1:14438
>   carol        key 3a8ffa66e7726d76  127.0.0.1:14439
>   chain orders         audit-log              genesis 6997e41df21f3339…
>   chain fingerprint 94c9-2bad-1c59-cc7c-5e76-efed-e968-ab2e
> ```

`seal` refuses a card for a different invite, a card with a bad signature, an
unexpected name, or a key used twice. It fixes every chain's settings, the
release versions, and the digests of the plugin bundles in this distribution.
Every operator's `plugins/` must hold exactly the same JARs as the
coordinator's when `seal` runs: `join` refuses any difference. Send
`supply.chain` to every operator.

`seal` also checks what a chain can do from its first block:

- An authenticated-map collection that uses a schema must set
  `valueEncoding: canonical-cbor`; otherwise `seal` refuses.
- A governed or approval collection without an active policy at genesis is
  sealed with a warning: it accepts no writes until the governance authority
  activates a policy.
- A `role-approvals` or `role-evidence` chain starts with no organizations or
  actors. `seal` writes a template plan beside the sealed file, for example
  `approvals.role-plan.yaml`, and prints the next step: fill it in and, once
  the application is live, run
  `./yano.sh appchain role bootstrap --plan approvals.role-plan.yaml --expiry-height <n>`
  and submit the commands it prints. See
  [role approvals](/state-machines/role-approvals/).

## 5. Compare the fingerprint, then join

Hold a short call. The coordinator reads out the **chain fingerprint**; each
operator reads out their own **key id** and checks it is listed under their
name. A card proves a key, not who sent it, so this call is the trust step.

Then each operator joins:

```bash
./yano.sh appchain join supply.chain
```

`join` shows the same summary and asks whether the fingerprint and every key
id match. In a script, pass the fingerprint you compared instead:

```bash
./yano.sh appchain join supply.chain \
  --expect-fingerprint 94c9-2bad-1c59-cc7c-5e76-efed-e968-ab2e --yes
```

> **✓ You should see** `✓ Joined supply (94c9-2bad-…) in …/yano-node`, then
> `Next: yano.sh appchain start`.

`join` refuses if your key is not in the roster under your name, if the file
does not match the fingerprint, or if your plugin bundles differ from the
sealed ones. It writes the node configuration from the sealed settings; it
never works them out again, so every operator runs exactly the same chain. On
Linux it also writes a systemd unit; see
[Run your node on a server](/deployment/operators/).

## 6. Start, and check the status

Each operator starts their node:

```bash
./yano.sh appchain start
```

On a devnet the **lead starts first**: its node creates the Cardano devnet,
and the other nodes download that genesis from it on their first start. On
preview, preprod, or mainnet every node syncs Cardano itself first, which can
take a while; watch `yano-node/logs/node.log`.

> **✓ You should see** `✓ Node alice is up at http://127.0.0.1:9180`.

Then check the whole application from any member:

```bash
./yano.sh appchain status supply
```

> **✓ You should see**
>
> ```text
> supply  chain fingerprint 94c9-2bad-1c59-cc7c-5e76-efed-e968-ab2e
>   Draft ─ Members ─ Sealed ─ Starting ─ [Live]
>   members online 3/3
>     alice        key a6622032b8e615ee  online   http://127.0.0.1:9180
>     bob          key 9be1fb9123767845  online   http://127.0.0.1:9181
>     carol        key 3a8ffa66e7726d76  online   http://127.0.0.1:9182
>   chain orders         height 0      members agree  members 3, 2 signatures  anchoring: not set up
>   Cardano: devnet block 1834, genesis 5b0e9f6c2a41d7e3 (compare with the other members)
>   Plugins: catalog sha256:4f1a2c9be07d5a31… (compare with the other members)
> ```

Until everyone is up, the stage is `Starting` and `Next:` names who to ask.
For scripts, `status --json` prints the same view, and
`status supply --wait live --timeout 600` waits until the application is live.

The last two lines describe your own node, and every member compares them:

- **Cardano** on preview, preprod, or mainnet shows the latest block and
  `up to date`, or `syncing: about 3 hours behind` while the node catches up.
  On a devnet it shows the hash of the devnet genesis this node stored; every
  member must show the same one.
- **Plugins** is the fingerprint of the plugin catalog this node loaded. A
  different value on one member means it runs different plugins, so that
  member cannot agree on blocks; install the release the application was
  sealed with.

## 7. Send a message and see it final

Your node's API key is in `yano-node/secrets/api.key`. Read it into your shell
without printing it, and submit through your own node (use your HTTP port):

```bash
API_KEY=$(cat yano-node/secrets/api.key)
MESSAGE_ID=$(curl -fsS -X POST http://127.0.0.1:9180/api/v1/app-chain/chains/orders/messages \
  -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d '{"topic":"orders","body":"order A-100 created"}' | jq -er .messageId)
echo "Accepted message $MESSAGE_ID"
```

A successful submission means "accepted", not "final". A few seconds later,
look it up:

```bash
curl -fsS -H "X-API-Key: $API_KEY" \
  "http://127.0.0.1:9180/api/v1/app-chain/chains/orders/messages/$MESSAGE_ID" | jq .height
```

> **✓ You should see** a height of 1 or more. Until the message is final, the
> lookup answers 404.

Run `status supply` again: every member shows the same height and
`members agree`. Follow [the proof guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/PROOF_LAB.md) to retrieve and
verify a proof for the message.

## 8. Stop and start

```bash
./yano.sh appchain stop
./yano.sh appchain start
```

`stop` keeps all data. Nothing in this tool deletes chain data.

## Optional: anchor a chain to Cardano

A chain goes live without anchoring. To write a chain's height and state root
to Cardano, one operator runs:

```bash
./yano.sh appchain anchor supply
```

It creates an anchor wallet key on that node only, shows its address and the
cost per anchor, asks to go ahead (`--yes` in scripts), waits for funds, and
restarts the node with anchoring on. On
a devnet only the **lead** can anchor, and the lead's devnet funds the wallet.
On a public network, fund the wallet only with what you are willing to spend
on fees: its balance is the spending limit. Afterwards `status` shows the
chain's anchoring as, for example, `led by alice, 12 anchored (last height
118)`. See [anchors and verification](/tutorials/07-anchors-and-verification/).

## Optional: protect topics

On a chain sealed with `topics:`, namespaces are created and changed with
plans. Run these from the directory that holds your node directory (or pass
`--node-dir`); it supplies the node, its key and the chain:

```bash
./yano.sh appchain topics init games/     # writes topics.yaml: fill in the owner keys and policy
./yano.sh appchain topics plan topics.yaml
```

`plan` writes `topics.plan.json` and says who must act. A top-level namespace
is a member vote: send the plan to every operator, and each runs

```bash
./yano.sh appchain topics approve topics.plan.json
```

which shows what it votes for, asks for a yes (`--yes` in scripts), votes
through their own node and waits until the namespace is in effect; it never
votes twice, so it is safe to run again (`--again` votes again after an
earlier vote lapsed). On an idle chain add `--heartbeat`. Changes inside a
namespace are signed by its owners
instead: each owner runs `topics sign topics.plan.json --key-file <seed file>`,
and anyone runs `topics submit topics.signed.json`, which waits until it is in
effect. `topics public-key <seed file>` prints the public key to put in
`topics.yaml`. Studio's **Topics** page
writes `topics.yaml` with a form if you prefer. See
[protect a topic and give users session keys](/recipes/topic-session-keys/).

## Where each setting lives

<!-- illustration: config-layers -->

Your node directory (`./yano-node` by default; change it with `--node-dir`)
holds everything for your node:

| Path | What it holds |
|---|---|
| `node.json` | Your name, public key, advertised addresses, and ports |
| `secrets/member.key`, `secrets/api.key` | Your private member key and API key; `secrets/anchor.key` only on an anchor leader |
| `apps/supply.chain` | The sealed file you joined |
| `topic-votes/`, `topic-submissions/` | The topic votes this node cast and the signed changes it submitted, so `topics approve` never votes twice and a repeated `topics submit` follows the first one |
| `config/consensus.yaml`, `config/node.yaml` | Generated by `join`; do not edit, run `join` again instead |
| `config/local.yaml` | Your own node-only settings, such as connector endpoints. `join` creates it empty and never rewrites it; its values win over `config/node.yaml`. `start` refuses it if it sets any sealed consensus setting |
| `data/` | Chain data: `chainstate` and `appchain-chainstate`, plus `appchain-indexers` (rebuildable) when a read index is enabled |
| `logs/node.log`, `logs/yano.log` | The node's console log and its application log |

Back up the node directory. It holds your key and your chain data.

Put settings that belong to your node alone, such as a connector's endpoint
or a log level, in `config/local.yaml`. Every member must run the sealed
values, so `start` refuses a local file that sets a key from
`config/consensus.yaml`; to change a sealed setting, seal a new application.
Keep secrets out of it: reference them through the environment.

## Troubleshooting

| You see | What it means and what to do |
|---|---|
| `Members 2/3: waiting for carol` from `seal` | A card is missing. Ask carol to run `request` and send the card; put it next to the draft. |
| `carol.member was made for a different invite (…)` | The card was made for an older draft. Ask carol to run `request` again with the current draft. |
| `The sealed roster lists <name> with key …, but this node's key is …` | The sealed file holds someone else's card under your name. Do not start. Tell the coordinator. |
| `This node's key (…) is not a member of supply` | Your name is not in the sealed roster. A founding operator tells the coordinator; to join later, run `request supply.chain` first. |
| `The fingerprint is …, not …. Do not start: the file differs from the one the coordinator sealed.` | Your `supply.chain` is not the one compared on the call. Get the file again. |
| `This distribution's plugin bundles differ from the sealed ones: …` | Install the Yano X release named in the message, the same one the coordinator sealed with. |
| `Port 8080 is already in use by another program.` | Stop that program, or make a new node directory with other ports (`request --http-port`, `--node-port`). |
| `status` stays at `Starting` with `ask the lead (alice) to run  yano.sh appchain start  first`, or `start` says `Waiting for the lead (alice) to start the devnet …` | On a devnet the other nodes wait for the lead's devnet. Ask the lead to start; the waiting `start` continues by itself until its `--timeout`. |
| `config/local.yaml changes sealed consensus settings: …` from `start` | Your local file overrides a value every member must share. Remove the named keys from `config/local.yaml`. |
| `Port 7099 is in use; pass --port with a free one` from `invite --serve` | Choose another port with `--port`; the links printed afterwards carry it. |
| `The links expired before every operator received the sealed file` | Run `invite --serve` again; it prints new links. Operators who already sent a card can rerun `request` with their new link. |
| `Plugins:` differs between members | That member loads different plugins. Install the Yano X release the application was sealed with. |
| The devnet genesis in `Cardano:` differs between members | That node follows a different devnet from the lead's. Stop it and tell the coordinator before it signs anything. |

**Next:** [grow and change the application](/deployment/add-chain/), or
[run your node on a server](/deployment/operators/).
