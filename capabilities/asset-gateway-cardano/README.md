# Asset gateway, Cardano runtime plugin

This module is the runtime bundle `org.yanoproject.x.asset-gateway.cardano` of ADR-059. It contributes the gateway's
L1 observers (`asset-vault-deposit-v1`, `asset-vault-payout-v1`, `asset-anchor-v1`, `asset-shard-bootstrap-v1`), the
`asset-anchor-handoff-v1` membership-handoff gate, and the `asset-settlement` effect executor. The settlement
builders and the members' body check live in the pure support library `asset-gateway-cardano-support`.

## The `asset-settlement` executor

One executor serves every vault of a chain, configured under `effects.executors.asset-settlement.*`. Every member
registers the co-sign service, which signs a `Settle` only after its own body check (ADR-059 D8h §1); exactly one
node, `owner=true`, builds, collects signatures and submits. The settings are node-local, not consensus settings;
`AssetSettlementSettings` lists them all.

| Setting | Nodes | Meaning |
|---|---|---|
| `slot.zero-time-ms`, `slot.zero-slot`, `slot.length-ms` | every node | The L1 slot clock. The owner sets validity intervals with it; every member bounds the interval it signs by its own clock: a TTL at most 15 minutes past its tip and a start at most 5 minutes past it. |
| `validity-window-slots` (600) | owner, relayer | The validity window of every `Settle`, `Accept` and relayed `Exit`, at most 10 minutes. A pending transaction holds its inputs until the node's L1 view is past its TTL or shows it landed. |
| `pending-ttl-file` | owner, relayer | Optional. A node-local file, under the node's data directory, holding the latest TTL slot this node submitted; a restart builds nothing until the L1 view is past it. Without it, a restart waits 12 minutes. |
| `max-settle-batch` (16) | every signing member | Claims per `Settle`. The owner splits larger batches, and every member refuses a `Settle` with more claims, so members must allow at least the owner's cap. |
| `max-vault-inputs` (8) | owner, relayer | Vault inputs per payout; a batch that needs more is split. |
| `max-supplement-per-payout` (0) | owner | The most lovelace the fee wallet adds to one payout's min-UTxO. A claim that needs more stays `RESERVED` for `Exit`. |
| `stability-window-ms` (600000), `inclusion-timeout-ms` (900000) | owner | How long a payout takes to be observed after inclusion, and how long a submitted `Settle` may take to be confirmed; the timeout must exceed the window. |
| `fee-wallet.key-file` | owner | The fee wallet's 32-byte Ed25519 seed (hex). |
| `round-timeout-ms` (30000) | every node | The fail-closed co-sign round timeout. |
| `deposit-key-prefix` (`ap/deposit/`) | every node | The vault script's deposit-record key prefix (`Accept`). |
| `accept.interval-ms` (60000) | owner, relayer | How often the owner batches credited deposits into an `Accept`, and a relaying non-owner into a proved `Accept`; 0 disables it. |
| `accept.max-batch` (16), `accept.max-proved-batch` (12) | owner, relayer | Deposits per co-signed and per proved `Accept`. |
| `accept.relayer-delay-ms` (3 × `accept.interval-ms`) | relaying non-owner | How old a deposit's L1 output must be before the relayer proves it, so it does not race a healthy owner. |
| `exit-relayer.enabled` (false), `exit-relayer.interval-ms` (60000) | any node | Relay `EXIT_ELIGIBLE` claims by proof `Exit`. |
| `exit-relayer.fee-wallet.key-file` | relaying non-owner | The relayer's own fee wallet; the owner relays with `fee-wallet.key-file`. |

**Clocks.** Every member's clock must be within about 5 minutes of the owner's. A member whose clock lags further
refuses honest bodies as `l1-tip-bound`; its co-sign product then reports `DEGRADED` with a `CONFIGURATION` failure in
its operational status, and the owner's rounds stay incomplete until enough other members answer.

## `Accept` and the exit relayer

Deposits become spendable custody only through `Accept` (ADR-059 D8f). The owner looks for deposit-shaped outputs at
each vault address every `accept.interval-ms`, keeps those its committed state records `CREDITED` at or below the live
anchor's height, and builds a co-signed `Accept`. Every member checks it against its own committed deposit records
before signing (D8h §1 step 4), including that the required signers reach the anchor's threshold. Without a quorum,
the owner falls back to the proved `Accept`, with inclusion proofs from its node at the live anchor's height and a
validity interval taken after the co-sign round. Each pass reads one window of at most 5,000 vault outputs and the
next pass the next window, wrapping around, so every credited deposit is reached.

A node with `exit-relayer.enabled` pays `EXIT_ELIGIBLE` claims by proof `Exit` (D9). It references the live anchor and
proves at its height: the live anchor is never older than the latest L1-stable one, so its height lies within the proof
floor (`state.proof-pruning.l1-stable-anchor-floor`). A transaction whose anchor then rolls back fails on L1 and is
rebuilt. Payout custody is read page by page through the vault address until it covers the batch. When the free
custody or the fee funding does not cover a batch, the relayer tries smaller ones and skips a claim that does not fit
alone. A relaying non-owner also submits proved `Accept`s of deposits at least `accept.relayer-delay-ms` old, so
custody keeps flowing while the owner is down. The relayer refuses in exit mode, which is M4a.

On the owner, the `Settle` executor, the `Accept` batcher and the relayer share one assembly lock, one fee wallet and
one set of pending-spend holds: none selects an output that another's pending transaction spends, and a payout never
builds over a shard input a pending payout spends. A relaying non-owner has its own fee wallet and holds.

**Hold lifetime and restarts.** Holds end only on L1 evidence, never by the wall clock. A pending transaction's inputs
stay held until the node's applied L1 view either is past the transaction's TTL slot (it can never land) or shows one
of its outputs, spent or unspent (it landed). A view that cannot answer, or that is stuck, keeps the hold. A payout's
nullifier inserts stay in the shard mirror until committed state has the claims `PAID` or `PAID_MISMATCH`, or until
the transaction can never land. A `Settle` whose confirmation times out closes its effect but keeps its input hold.

Holds live in memory. Before each submission the transaction's TTL slot is written to `pending-ttl-file`; after a start
or restart, an owner or relayer builds nothing new until its L1 view is past that slot. Without the file it waits the
longest permitted validity window plus 2 minutes. A `Settle` submitted before the restart is still followed from the
reference the effect runtime persisted.
