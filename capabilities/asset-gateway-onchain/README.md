# Asset gateway on-chain contracts

This module owns the Julc-authored Plutus V3 contracts of the ADR-059 custody profile
`anchored-claim-vault-v1`. The checked artifacts under `src/main/resources/META-INF/plutus` are the unparameterized
templates that deployments parameterize; `GatewayArtifactBundleTest` recompiles them and fails on drift (regenerate
with `-Dyano.regenerate.plutus=true`). The on-chain ABI (datums and redeemers) is
`META-INF/yano/contracts/asset-gateway/v1/asset-gateway-onchain-v1.cddl`. Measured costs and batch caps are in
[`BUDGETS.md`](BUDGETS.md).

| Script | Purpose | Parameters (D4 order) |
|---|---|---|
| `ExitModeThreadPolicy` | one-shot thread token (empty name) | seed outpoint |
| `ForcedInboxValidator` | request token policy and inbox address, one script (mint + spend) | domain id, anchor identity, exit-mode policy id, forced key prefix, mint window |
| `ExitModeThreadValidator` | `NORMAL` → `EXIT(frozenRoot, height, atMs)`, terminal | exit-mode policy id, anchor identity, inbox policy id, heartbeat / forced-deadline / max-pause timeouts, forced key prefix, status key |
| `AssetShardThreadPolicy` | 16 shard thread tokens per vault, one-shot | seed outpoint |
| `AssetVaultValidator` | one vault per asset | `VaultId` fields, address network id, anchor identity, shard thread policy id, exit-mode policy id and script hash, claim / deposit / exit key prefixes, status key, profile (batch caps, floor, exit-delay bounds, max pause) |
| `AssetShardValidator` | one of a vault's 16 nullifier threads | shard thread policy id, vault script hash |

The vault's redeemers are `Settle` (co-signed by a threshold of the referenced v2 anchor's members), `Exit` (per-claim
proof against the anchor root, or the frozen root in exit mode), `SweepExit` (`SWEEP_UNATTRIBUTED`, bound to one
outpoint), `AcceptCosigned`, `AcceptProved`, `RefundAbsent`, `RefundUncredited` and `BalanceExit`. Every payout
inserts exactly the paid ids, in order, into one shard of the same vault, so a claim or an exit leaf is paid at most
once on either path. Rules V1–V11 and V13 are enforced on chain; V12 (`Settle` covers only anchored reservations) is
the members' body check and is not visible to the script.

Implementation notes:

- The nullifier shards use the EUTxO bridge's Aiken-format MPF library, copied unchanged into `mpf/`, so this module
  does not depend on the bridge. App-ledger state proofs use `sdk/proof-onchain` (`MpfOnChainVerifier`,
  `MpfAnchorLib`).
- Only the first vault input of a transaction runs the full check; the others check that their redeemer equals it,
  which is what V13 requires anyway and keeps multi-input transactions within budget.
- Script hashes in the tests are placeholders: each script is evaluated directly with its context. The real hashes
  come from applying the parameters to the committed templates.

The contracts have not been audited and must not hold material funds (ADR-059 §12).
