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
outpoint, paid whole with a `SweepPaidV1(claimId)` datum), `AcceptCosigned`, `AcceptProved`, `RefundAbsent`,
`RefundUncredited`, `BalanceExit` and the permissionless `Consolidate` (accepted custody merged into one continuation,
in NORMAL and EXIT). Every payout inserts exactly the paid ids, in order, into one shard of the same vault, so a claim
or an exit leaf is paid at most once on either path. Rules V1–V11 and V13 are enforced on chain; V12 (`Settle` covers
only anchored reservations) is the members' body check and is not visible to the script.

Implementation notes:

- The nullifier shards use the EUTxO bridge's Aiken-format MPF library, copied unchanged into `mpf/`, so this module
  does not depend on the bridge. App-ledger state proofs use `sdk/proof-onchain` (`MpfOnChainVerifier`,
  `MpfAnchorLib`); `verifyAbsence` covers every absent key (empty slot, other leaf, or a branch whose prefix leaves the
  key's path), so a post-freeze deposit is always refundable and a censored request always provable.
- V13: only the first vault input of a transaction (the lead) runs the full check, under its own redeemer, which must
  be an action. Every other vault input carries either the identical redeemer or the 4-byte `Follow` marker and only
  checks that the lead carries an action, so exactly one action applies and large proofs appear once per transaction.
  This doubled the `AcceptProved` cap and keeps multi-input transactions within size (`BUDGETS.md`).
- The spend entrypoint takes no datum argument: a vault output with no datum, or only a datum hash, is spendable
  (as unattributed custody, through `SweepExit`). Vault shapes are judged on the decoded datum (`acceptedDatum` reads
  its canonical serialization), exactly as `VaultOutputShape.classify` does off chain (`GatewayShapeCrossTest`).
- Recipients may have base, enterprise or pointer key addresses: the script rebuilds pointer addresses' raw bytes
  (slot, transaction index and certificate index as CIP-19 variable-length naturals, shortest form), so a ledger
  recipient must use that shortest encoding.
- D10 for `SweepExit`: a sweep spends one outpoint whole and leaves no `VaultContinuationV1` to name the paid claim,
  so the script requires output 0, the payout, to carry the inline datum `SweepPaidV1(claimId)` (constructor 4, no
  vault shape uses it) for the very id it inserts into the shard. The payout observer reads the paid claim id from that
  datum, in a transaction it has already authenticated by the shard thread output.
- Script hashes in the tests are placeholders: each script is evaluated directly with its context. The real hashes
  come from applying the parameters to the committed templates.

The contracts have not been audited and must not hold material funds (ADR-059 §12).
