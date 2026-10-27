# Troubleshooting

## An instance was made by an earlier showcase

`instance '<name>' was made by an earlier showcase` means the instance
directory predates the app-chain setup commands. It cannot be adopted: use a
new `--instance`, or remove only a deliberately disposable instance with
`reset --instance <name> --yes`.

## A requested option differs from the retained instance

`instance.json` records the options an instance was sealed with. Repeating
`--nodes`, `--network`, `--threshold`, a port base, or a Cardano History,
index, or snapshot option with a different value is refused, because the
sealed applications cannot change. Omit the option (it is restored
automatically) or use a new `--instance`.

## Script-anchor co-sign round failed

Check node 0’s log. Typical causes are an unsynchronized L1 view, funding that
is not visible/stable yet, a bootstrap output that members cannot verify, an
insufficient/fragmented anchor wallet, or fewer ready current members than the
app-chain threshold. Creating a fresh wallet does not fix member/L1 visibility.
After a governed join, every existing node's `approve` adds the new peer and
restarts that node once. If co-sign rounds keep collecting one fewer witness
than the member count, check that every node is running
(`./showcase.sh status`) before changing membership or funding.

## Effect Executors is empty

This is correct on chains that emit no effects and on follower nodes. In the
light profile, node 0 owns `showcase-outbox` for `workflow-chain`; every member
still validates/finalizes the intent and its result. Use node 0’s UI and run a
composite release first.

## Proposal is approved but no effect appears

Stock approval status does not automatically trigger the showcase composite
workflow. The application sends a separate release command after approval.
That command checks the exact order/payload hash, claims the release ID,
updates audit/release state, and emits the effect.

## Ports are occupied

Choose explicit non-overlapping bases:

```bash
./showcase.sh up --instance alternate --http-base 7170 --server-base 14337
```

## A prepared evidence devnet fails before its first block

The evidence devnet deliberately uses short generated epochs. `prepare`
creates its immutable devnet identity and `systemStart`; normally use
`quickstart` directly or follow `prepare` with `up` promptly. If a disposable
instance was prepared and then left idle for multiple epochs before it ever
started, use a fresh `--instance` name. Do not edit the retained genesis or
identity marker in place. Once an instance has history, normal stop/start
reuses that exact identity.

## A node will not start

`./showcase.sh restart` starts every node directory of the instance, founding
nodes first and then the recorded joiners. A failed start names the node's
log, `data/showcase/<instance>/node<i>/logs/node.log`. `yano.sh appchain start`
refuses a `config/local.yaml` that changes a sealed setting; the showcase
writes that file at `prepare`, `member join`, and `settlement adopt`. Inspect
`config paths` and the node log before changing retained files.

## A member join stopped part-way

`member join <i>` is safe to run again: `yano.sh appchain approve` repeats
harmlessly on nodes that already approved. The approval logs are in
`data/showcase/<instance>/approve-node<i>-by-node<j>.log`. If only the final
activation was interrupted, run `./showcase.sh governance activate`.
