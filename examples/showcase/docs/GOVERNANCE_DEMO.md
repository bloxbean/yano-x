# Governed Join Demo

Start with governed membership (the showcase template already uses it):

```bash
./showcase.sh up --nodes 3 --instance governance
```

Join the next node index:

```bash
./showcase.sh member join 3 --instance governance
```

This is the same flow real operators follow with the setup commands. Node 3
runs `yano.sh appchain request` with the sealed `showcase.chain`, which writes
its member card. Every existing node then runs `yano.sh appchain approve` with
that card. Where the new member count needs it, `approve` first raises the
threshold on every chain (so 2-of-3 becomes 3-of-4), waits for it to
activate, then approves the `ADD` on every chain, adds the new peer, and
restarts that node once. While the approvals wait for their membership epochs
to activate, the showcase keeps every chain making blocks with real demo
messages. Node 3
then joins with the same fingerprint check and starts, and the command ends
with `governance activate`.

```bash
./showcase.sh governance activate --instance governance
```

`governance activate` advances any pending membership epoch with explicit,
normally sequenced showcase traffic and finalizes one proof block under the
new member set on every showcase chain. Run it on its own if the join was
interrupted, and before scheduling another membership change.

```bash
./showcase.sh verify all --instance governance
```

There is no separate threshold command: the threshold changes only as part of
a membership change, to a value that suits both the old and the new member
count. `--threshold` at `prepare` sets the founding threshold, which must be
more than half of the nodes.

This threshold controls app-chain block finality. It does not rewrite the
`required` voter count already stored inside an application approval proposal.
The business approval protocol and MPF consensus/finality protocol are
separate even when both happen to use the number two.

Membership changes apply to the `showcase` application only. The `history`
and `settlement` applications keep their founding nodes.

Nodes must join in index order: `3`, then `4`, and so on. `restart` starts the
founding nodes and then the recorded joiners from their node directories, so
it never submits a second membership command. `config show` reports active
and scheduled members/thresholds plus the activation height. `governance
activate` submits a valid command for each showcase state machine: an
ordered-log heartbeat, KV put, approval proposal, balance mint, document
append, composite KV put, role-policy probe, signed virtual EUTxO
self-payment, authenticated-map writes, and a document-review proposal. The
role probe is deliberately a well-formed business no-op. This is real MPF
traffic, not a database edit, so it advances height and produces finality
certificates.
