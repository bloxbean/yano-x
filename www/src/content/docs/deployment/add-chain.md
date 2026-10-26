---
title: "Grow and change your application"
description: "The new member finalizes blocks with the others, and no chain was reset."
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/appchain/deployment/add-chain.md"
---
- **Goal:** add or remove a member of a running application, recover from a
  lost key, host another application on the same nodes, and upgrade a node.
- **Before you start:** a live application from
  [Set up an application](/deployment/configure/), with every existing operator able to
  run one command on their own node.
- **Time:** a few minutes per operator; each change waits for the chain to
  make about ten blocks.
- **Outcome:** the new member finalizes blocks with the others, and no chain
  was reset.

Members are fixed at seal, but a `governed` application (the default) can
change them later. Every existing operator runs `approve` once; it does the
whole quorum-safe procedure on every chain of the application.

<!-- illustration: member-change -->
1. **Request.** The new operator runs `request supply.chain` and sends the
   card to every existing operator.
2. **Approve.** Each existing operator runs `approve dave.member` once, in any
   order.
3. **Raise the threshold.** If the new member count needs it, `approve` first
   raises the threshold and waits until that is active.
4. **Add.** It then adds the member and waits until that is active.
5. **Restart.** It adds the new peer to the operator's node and restarts the
   node once.
6. **Join and start.** The new operator runs `join supply.chain` and `start`;
   the node catches up.
<!-- /illustration -->

## Add a member

The new operator, dave, needs the same Yano X release and the sealed
`supply.chain` file. On his machine:

```bash
./yano.sh appchain request supply.chain
```

> **✓ You should see** `✓ Wrote ./dave.member  (key id ee22fe112c2e69cb, …)`
> and `Next: send dave.member to the existing operators`.

Each existing operator, with their node running, then runs:

```bash
./yano.sh appchain approve dave.member
```

> **✓ You should see**
>
> ```text
> Adding dave (key ee22fe112c2e69cb, 127.0.0.1:15450) to supply
> orders: first raising the threshold from 2 to 3 (4 members)
> orders: waiting for threshold 3 (it activates once enough operators approve and the chain makes 10 more blocks)
> ✓ orders: dave is a member
> Restarting this node to connect to dave …
> ```

Check the key id against the one dave reads out to you before you approve.

- **Why the threshold rises.** Two of three is a majority; two of four is not.
  So while a three-member application grows, it runs at three of four for a
  while, and one offline member stops it. `approve` refuses to raise the
  threshold unless enough members are online.
- **Why it waits.** A change activates ten blocks after enough operators
  approve, and a chain only makes blocks when it has messages. On an idle
  `ordered-log`, `kv-registry`, or `approvals` chain, `approve` offers to send
  small heartbeat messages (`--heartbeat` in scripts); on an ordered log each
  one is a permanent entry. Other chains need ordinary traffic.
- **Safe to run again.** If `approve` is interrupted or times out, run it
  again: it skips what is done and reports what is still waiting.
  Approvals expire after 600 blocks.

When every operator has approved, dave joins and starts:

```bash
./yano.sh appchain join supply.chain
./yano.sh appchain start
./yano.sh appchain status supply
```

> **✓ You should see** four members online and `members agree` for every
> chain. Before every operator has approved, dave's status shows
> `waiting for approval`.

## Remove a member

Each remaining operator runs:

```bash
./yano.sh appchain approve --remove carol
```

`approve` lowers the threshold if needed, removes carol on every chain, drops
her as a peer, and restarts the operator's node once. With `sequencing:
fixed` (the default) the lead cannot be removed: the runtime refuses to
remove the fixed proposer. An application sealed with `sequencing: rotating`
has no fixed proposer, so its lead can be removed like any other member
(on a devnet the lead's node still produces the Cardano devnet).

## Recover from a lost key

If carol loses her node directory, **remove her first, then add her again**:

1. The other operators each run `approve --remove carol`. The chain keeps
   finalizing without her.
2. Carol makes a fresh node directory and runs `request supply.chain`, which
   creates a new key and card.
3. The others run `approve carol.member`; carol runs `join supply.chain` and
   `start`.

Adding first would raise the threshold to a level the lost key can never help
reach. With fixed sequencing the lead's key is the exception: losing it
means sealing a new application, so the lead must back up their node
directory.

## Host another application on the same node

An operator can run several applications on one node. Each one is a separate
setup: the coordinator runs `new` and `seal` for it, and each operator runs
`request` and `join` with the new files. In an existing node directory,
`request` reuses your member key and addresses. Adding a chain to a running node
still needs a stop and start:

```bash
./yano.sh appchain join documents.chain
./yano.sh appchain stop
./yano.sh appchain start
```

`join` refuses a chain id the node already runs, and node-wide settings that
differ from those of the applications it already hosts. On a devnet, the
applications on one node must share the same lead.

A chain's state machines are fixed at its genesis. To add a new chain to an
existing application, seal a new application with the same operators.

## Upgrade Yano X on a node

`start` checks the plugin bundles against the ones the node accepted at
`join`. After installing a newer Yano X release, it refuses with
`Plugin bundles changed since this node joined supply`. Agree the upgrade with
the other operators first, then accept the new bundles:

```bash
./yano.sh appchain join supply.chain --accept-bundles
./yano.sh appchain start
```

`join --accept-bundles` lists the changed bundles and records the new set.
Once every node runs again, the `Plugins:` line of `status` must show the same
catalog fingerprint on every member.
Changing what a state machine computes is a consensus change, not a rolling
upgrade; see [consensus rules](/plugins/consensus-rules/#evolving-a-live-chain).

## Change an address

A peer's address is outside the chain fingerprint, so it can change without a
new chain. If bob moves, each other operator runs:

```bash
./yano.sh appchain join supply.chain --peer bob=node-b.example:13337
./yano.sh appchain stop && ./yano.sh appchain start
```

## Troubleshooting

| You see | What it means and what to do |
|---|---|
| `This node must be running; run  yano.sh appchain start  first` | `approve` talks to your own node. Start it first. |
| `orders: only 2 members are connected, but the new threshold needs 3; bring the others online first so the chain cannot stall` | Ask the offline operators to start their nodes, then run `approve` again. |
| `orders: still waiting for …` and exit code 3 | The chain has not made enough blocks yet, or other operators have not approved. Send traffic, or run again later. |
| `dave.member was made for another application (invite …)` | The card was made for a different `.chain`. Ask dave to run `request supply.chain`. |
| `alice is the fixed proposer of orders; the runtime refuses to remove it.` | With fixed sequencing the lead cannot be removed. Losing the lead's key means sealing a new application. |

**Next:** [run your node on a server](/deployment/operators/).
