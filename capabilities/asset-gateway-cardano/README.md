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
| `validity-window-slots` (600) | owner | The `Settle` validity window, at most 10 minutes. |
| `max-settle-batch` (16) | every signing member | Claims per `Settle`. The owner splits larger batches, and every member refuses a `Settle` with more claims, so members must allow at least the owner's cap. |
| `max-vault-inputs` (8) | owner | Vault inputs per `Settle`; a batch that needs more is split. |
| `max-supplement-per-payout` (0) | owner | The most lovelace the fee wallet adds to one payout's min-UTxO. A claim that needs more stays `RESERVED` for `Exit`. |
| `stability-window-ms` (600000), `inclusion-timeout-ms` (900000) | owner | How long a payout takes to be observed after inclusion, and how long a submitted `Settle` may take to be confirmed; the timeout must exceed the window. |
| `fee-wallet.key-file` | owner | The fee wallet's 32-byte Ed25519 seed (hex). |
| `round-timeout-ms` (30000) | every node | The fail-closed co-sign round timeout. |

**Clocks.** Every member's clock must be within about 5 minutes of the owner's. A member whose clock lags further
refuses honest bodies as `l1-tip-bound`; its co-sign product then reports `DEGRADED` with a `CONFIGURATION` failure in
its operational status, and the owner's rounds stay incomplete until enough other members answer.
