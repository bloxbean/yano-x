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
proof against the anchor root, or the frozen root in exit mode), `SweepExit` (`SWEEP_UNATTRIBUTED`: one unaccepted
outpoint, its native assets paid exactly and at least its lovelace, with a `SweepPaidV1(claimId)` datum),
`AcceptCosigned`, `AcceptProved`, `RefundAbsent`, `RefundUncredited`, `BalanceExit` and the permissionless
`Consolidate` (EXIT only: accepted custody merged into one continuation; in NORMAL it would let anyone invalidate
in-flight payouts every round, and `Settle` already merges its inputs). Every payout inserts exactly the paid ids, in order, into one shard of the same vault, so a claim
or an exit leaf is paid at most once on either path. Rules V1–V11 and V13 are enforced on chain; V12 (`Settle` covers
only anchored reservations) is the members' body check and is not visible to the script.

Implementation notes:

- The nullifier shards use the EUTxO bridge's Aiken-format MPF library, copied unchanged into `mpf/` (at PR #113's
  `e8aefa38`: `miss()` rejects a terminal leaf that reads as a branch, and a fork neighbor must end above the leaf
  level; the shard's `fold` calls `miss()` before `including()`, so a forged miss can no longer re-insert a paid id),
  so this module
  does not depend on the bridge. App-ledger state proofs use `sdk/proof-onchain` (`MpfOnChainVerifier`,
  `MpfAnchorLib`); `verifyAbsence` covers every absent key (empty slot, other leaf, or a branch whose prefix leaves the
  key's path), so a post-freeze deposit is always refundable and a censored request always provable.
- V13: only the first vault input of a transaction (the lead) runs the full check, under its own redeemer, which must
  be an action. Every other vault input carries either the identical redeemer or the 4-byte `Follow` marker and only
  checks that the lead carries an action, so exactly one action applies and large proofs appear once per transaction.
  This doubled the `AcceptProved` cap and keeps multi-input transactions within size (`BUDGETS.md`).
- The spend entrypoint takes no datum argument: a vault output with no datum, or only a datum hash, is spendable
  (as unattributed custody, through `SweepExit`). Vault shapes are judged on the decoded datum (`accepted` reads its
  canonical serialization), exactly as `VaultOutputShape.classify` does off chain (`GatewayShapeCrossTest`). One
  acceptance rule serves payouts, `Consolidate` and the sweep exclusion: a `VaultContinuationV1` holding only lovelace
  and the vault asset, or a `FloorTopUpV1` holding lovelace alone, as deposit admission requires. `MigrationInV1` is
  classified off chain but is not accepted custody before M4b: the script rejects it and admission records it
  `UNATTRIBUTED(NOT_A_DEPOSIT)`.
- Recipients may have base, enterprise or pointer key addresses: the script rebuilds pointer addresses' raw bytes
  (slot, transaction index and certificate index as CIP-19 variable-length naturals, shortest form). It does not
  decode addresses, so the canonical form, within the Conway limits (slot below 2^32, indexes below 2^16), is
  enforced off chain (`CardanoAddresses`): the ledger's reservation rejects
  a non-canonical pointer recipient as infeasible (D7), deposit admission treats a `DepositDatumV1` whose refund address
  is a non-canonical pointer as malformed (recovered by a governed sweep), and builders emit only the canonical form.
- A sweep commits to the swept output's full value as an `AssetValue`, at most 16 entries. An output with more
  entries (17 or more assets, or 16 native assets on the ADA vault) cannot be swept: it stays as stranded donor dust.
  It is not custody, so it has no solvency impact (ADR-059 D8d).
- The forced inbox's spend admits no script input other than its own address, so a refund output can only be counted
  by requests of this one inbox. A relayer or wallet burning requests must pay the fee and collateral from a key-held
  input; a script or multisig fee input fails the spend.
- A `BalanceExit` continuation lists the escape nullifier, not a claim id: the payout observer records a balance-exit
  observation (the nullifier redeems the exit leaf) and never a claim transition. A `SweepExit` observation has no
  continuation fields.
- D10 discriminator. On a transaction authenticated by its shard thread output, the payout observer reads the paid
  claim ids from the vault's `VaultContinuationV1` output whenever one is present (`Settle`, `Exit`, `BalanceExit`),
  and reads output 0's `SweepPaidV1(claimId)` only when there is no vault continuation. A sweep spends one outpoint
  whole and leaves no continuation, so the script requires its output 0 to carry `SweepPaidV1` (constructor 4, which
  no vault shape uses) for the very id it inserts into the shard; a `SweepPaidV1` on any other transaction's output 0
  is ignored.
- Script hashes in the tests are placeholders: each script is evaluated directly with its context. The real hashes
  come from applying the parameters to the committed templates.

The contracts have not been audited and must not hold material funds (ADR-059 §12).
