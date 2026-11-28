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
| `validity-window-slots` (600) | owner, relayer | The validity window of every `Settle`, `Accept` and relayed `Exit`, at most 10 minutes. A pending transaction holds its inputs until the node's applied L1 tip is past its TTL. |
| `pending-ttl-file` | owner, relayer | Optional base path, under the node's data directory. The file `<base>.<chain id>.<fee wallet key hash, 4 bytes hex>` holds the latest TTL slot this chain and wallet submitted; a restart builds nothing until the L1 view is past it. Without a valid file, a restart waits 12 minutes and until the applied tip is 10 minutes past its start. |
| `max-settle-batch` (16) | every signing member | Claims per `Settle`. The owner splits larger batches, and every member refuses a `Settle` with more claims, so members must allow at least the owner's cap. |
| `max-vault-inputs` (8) | owner, relayer | Vault inputs per payout; a batch that needs more is split. |
| `max-supplement-per-payout` (0) | owner | The most lovelace the fee wallet adds to one payout's min-UTxO. A claim that needs more stays `RESERVED` for `Exit`. |
| `stability-window-ms` (600000), `inclusion-timeout-ms` (900000) | owner (window: also relayer) | How long a payout or a deposit takes to be observed after inclusion, and how long a submitted `Settle` may take to be confirmed; the timeout must exceed the window. |
| `fee-wallet.key-file` | owner | The fee wallet's 32-byte Ed25519 seed (hex). |
| `round-timeout-ms` (30000) | every node | The fail-closed co-sign round timeout. |
| `deposit-key-prefix` (`ap/deposit/`) | every node | The vault script's deposit-record key prefix (`Accept`). |
| `accept.interval-ms` (60000) | owner, relayer | How often the owner batches credited deposits into an `Accept`, and a relaying non-owner into a proved `Accept`; 0 disables it. |
| `accept.max-batch` (16), `accept.max-proved-batch` (12) | owner, relayer | Deposits per co-signed and per proved `Accept`. |
| `accept.relayer-delay-ms` (3 × `accept.interval-ms`) | relaying non-owner | How long ago a deposit must have become creditable (its L1 output's time plus `stability-window-ms`) before the relayer proves it, so it does not race a healthy owner. |
| `exit-relayer.enabled` (false), `exit-relayer.interval-ms` (60000) | any node | Relay `EXIT_ELIGIBLE` claims by proof `Exit`. |
| `exit-relayer.fee-wallet.key-file` | relaying non-owner | The relayer's own fee wallet; the owner relays with `fee-wallet.key-file`. |

**Clocks.** Every member's clock must be within about 5 minutes of the owner's. A member whose clock lags further
refuses honest bodies as `l1-tip-bound`; its co-sign product then reports `DEGRADED` with a `CONFIGURATION` failure in
its operational status, and the owner's rounds stay incomplete until enough other members answer.

**The anchor's genesis id.** `anchor.genesis-id` (and the `chainGenesisId` the vault, exit-mode and inbox scripts are
parameterized with) is the genesis id the anchor datum carries: the chain's effective state genesis id, which
`GET /app-chain/state/identity` reports as `genesisId`. It is the configured `state.genesis-id` with the framework's
application profiles mixed in, so it differs from the configured value. Pinning the configured value derives scripts
that never find the live anchor.

## The shard backend

The nullifier-shard validator has two interchangeable backends (ADR-059 D8c, r23): `julc` (the default) and `aiken`
(aiken-lang/merkle-patricia-forestry v2.1.0 behind Yano X's own proof-shape checks, the same rules as the julc
backend). Both enforce the same rules on the same datum and redeemer; they differ only in the shard script hash and
address. The setting is the same key in the settings of the `asset-vault-payout-v1` and `asset-shard-bootstrap-v1`
observers and of the `asset-settlement` executor:

| Setting | Nodes | Meaning |
|---|---|---|
| `shard-backend` (`julc`) | every node | `julc` or `aiken`: which shard validator the vaults' shard threads run. Every `vaults.N.shard-script-hash` (and `shard-address`) must be that backend's shard of the vault's `shard-policy-id` and `script-hash`, or startup fails. |

**Choosing.** Pick the backend before the vaults are bootstrapped; it is fixed for the life of the vaults, as the
shard threads have no upgrade path (D8c). Derive each vault's shard hash with the chosen backend
(`ShardBackend.derive(shardPolicyId, vaultScriptHash, networkId)` in `asset-gateway-cardano-support`, or the matching
template in `asset-gateway-onchain`), publish that shard's reference script, bootstrap the 16 threads at its
address, and give every member the same `shard-backend` and pins. The value is part of each observer's consensus
identity, so members with different backends disagree at startup. `julc` is the default and the measured baseline;
`aiken` builds the shard on the upstream Aiken MPF library at about the same cost (`BUDGETS.md`: the `Settle` and
`Exit` caps are unchanged). The members' body checks, the builders and the observers read only the pinned shard hash
and address, so they serve either backend unchanged.

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
rebuilt. Payout custody is collected from every page of the vault address (up to 100,000 outputs), and the builder
picks the most useful inputs from all of it. When the free
custody or the fee funding does not cover a batch, the relayer tries smaller ones and skips a claim that does not fit
alone. A relaying non-owner also submits proved `Accept`s of deposits creditable for at least `accept.relayer-delay-ms`, so
custody keeps flowing while the owner is down. The relayer refuses in exit mode, which is M4a.

On the owner, the `Settle` executor, the `Accept` batcher and the relayer share one assembly lock, one fee wallet and
one set of pending-spend holds: none selects an output that another's pending transaction spends, and a payout never
builds over a shard input a pending payout spends. A relaying non-owner has its own fee wallet and holds.

**Hold lifetime and restarts.** Holds end only on L1 evidence, never by the wall clock. A pending transaction's inputs
stay held until the node's applied L1 tip is past the transaction's TTL slot, even once it has landed, so a rollback
of its inclusion cannot free them early. A view that cannot answer, or that is stuck, keeps the hold. A payout's
nullifier inserts stay in the shard mirror until committed state has the claims `PAID` or `PAID_MISMATCH`, or until
the tip is past the TTL and a readable view definitely holds none of its outputs; a read that fails never retires
them. A `Settle` whose confirmation times out closes its effect but keeps its input hold.

Holds live in memory. Before each submission the transaction's TTL slot is written durably (synced, moved atomically,
directory synced) to the chain's and wallet's `pending-ttl-file`, keeping the larger of the stored and the new slot;
after a start or restart, an owner or relayer builds nothing new until its applied L1 tip is past that slot. Without a
valid file (missing, unreadable, negative, or further ahead of the tip than any TTL can be) it waits 12 minutes and
until its applied tip is 10 minutes past its start slot. A `Settle` submitted before the restart is still followed
from the reference the effect runtime persisted. Pending inserts are not persisted: after a restart, the next payout on
a shard whose payout is still unconfirmed waits for the payout observer, about the stability window.
