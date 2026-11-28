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
| `AssetShardValidator` | one of a vault's 16 nullifier threads (julc shard backend, the default) | shard thread policy id, vault script hash |
| `AssetShardValidatorAiken` | the same shard, Aiken backend (`aiken/validators/asset_shard.ak`, r23) | shard thread policy id, vault script hash |
| `ProgrammableVaultExitDelegate` | `e`, the EXIT-only actions of a CIP-113 vault (ADR-061 D2) | the vault's, then the pinned PLB hash and `maxInlineDatumBytes` |
| `ProgrammableVaultValidator` | `h`, the custody script of a CIP-113 vault (ADR-061 D2, D5) | the same as `e`, then `e`'s hash |
| `ProgrammableShardValidator` | the shard binding variant of a CIP-113 vault (ADR-061 D5) | shard thread policy id, `h` |
| `ProgrammableVaultExitDelegateAiken`, `ProgrammableVaultValidatorAiken`, `ProgrammableShardValidatorAiken` | the same three scripts, Aiken backend (`aiken/validators/programmable_vault_exit.ak`, `programmable_vault.ak`, `programmable_shard.ak`, ADR-061 D3) | as their julc twins |

The vault's redeemers are `Settle` (co-signed by a threshold of the referenced v2 anchor's members), `Exit` (per-claim
proof against the anchor root, or the frozen root in exit mode), `SweepExit` (`SWEEP_UNATTRIBUTED`: one unaccepted
outpoint, its native assets paid exactly and at least its lovelace, with a `SweepPaidV1(claimId)` datum),
`AcceptCosigned`, `AcceptProved`, `RefundAbsent`, `RefundUncredited`, `BalanceExit` and the permissionless
`Consolidate` (EXIT only: accepted custody merged into one continuation; in NORMAL it would let anyone invalidate
in-flight payouts every round, and `Settle` already merges its inputs). Every payout inserts exactly the paid ids, in order, into one shard of the same vault, so a claim
or an exit leaf is paid at most once on either path. Rules V1–V11 and V13 are enforced on chain; V12 (`Settle` covers
only anchored reservations) is the members' body check and is not visible to the script.

## CIP-113 custody (ADR-061)

A registered CIP-113 programmable token can never leave the pinned deployment's base script (PLB), so its vault is
the smart wallet `base(Script(PLB), Inline(Script(h)))`. PLB spends every vault UTxO, and CIP-113 accepts a script
owner's consent only as that script's withdrawal, so the vault rules run in `h`'s withdraw handler, once per
transaction, over every input at the vault smart wallet (V14). There is no lead input and no `Follow`.

- `h` (`ProgrammableVaultValidator`) runs `Settle`, `Exit`, `SweepExit`, `AcceptCosigned` and `AcceptProved`. For the
  EXIT-only actions (`RefundAbsent`, `RefundUncredited`, `BalanceExit`, `Consolidate`) it requires the exit-mode
  thread in `EXIT` and `e`'s withdrawal with the redeemer `DelegatedActionV1(h, action)`.
- `e` (`ProgrammableVaultExitDelegate`) learns `h` from that redeemer, requires `h`'s withdrawal and runs the full
  rules of the action, V11 included. It alone holds the absence verifier. It is derived first; `h` takes its hash.
- Both have one entrypoint dispatched on the purpose: `withdraw` runs the actions, `publish` accepts only
  `RegisterCredential`, and `spend` and every other purpose fail.
- `ProgrammableShardValidator` is `AssetShardValidator` with its vault binding replaced: `Script(h)` among the
  withdrawals, since no vault transaction has an input whose payment credential is `h`. The native shard is
  unchanged.
- V15: no output pays `h` or `e`, and only the continuation sits at the vault smart wallet. V16: a continuation lists
  at most `floor((maxInlineDatumBytes - 6) / 34)` ids (29 at 1,024 bytes). V17: no certificates and no mint;
  withdrawals, and their amounts, are not restricted.
- Deposits carry `ProgrammableDepositDatumV1` with an origin; `Accept`, the deposit-shaped sweep and the refunds key
  a deposit by its origin `DepositId`, never its outpoint, and use one total deposit-shaped predicate.
- Payouts carrying the asset go only to key-owned smart wallets of the pinned deployment; lovelace-only payouts may
  also go to a key-payment address.

The ABI is `META-INF/yano/contracts/asset-gateway/v1/asset-gateway-onchain-cip113-v1.cddl`. The tests compose the
custody scripts with the pinned CIP-113 v0.0.1 scripts (`src/test/resources/cip113/plutus.json`,
cip113-programmable-tokens `6b75ba32`, sha256 checked): `ProgrammableVaultCompositionTest` evaluates every script of
an honest transaction of each action (PLB per vault input, the dispatcher, the transfer delegate, a test transfer
hook, `h`, `e` and the shard), and `ProgrammableVaultNegativeTest` requires each V14 to V17 negative to fail in the
intended script. Sizes and costs are in [`BUDGETS.md`](BUDGETS.md#cip-113-custody-adr-061).

Each `@OnchainLibrary` call stays small, but a julc `@MultiValidator` with one `@Entrypoint` per purpose compiled
to about 4,300 bytes more than one entrypoint dispatching on the script purpose itself, so the custody scripts use
one entrypoint.

### The Aiken custody backend (ADR-061 D3)

The custody scripts have two interchangeable backends, selected together by `programmable-backend: julc | aiken`
(julc by default). The backend determines `e`, `h` and with them the vault smart wallet and the shard variant.

- **julc**: `ProgrammableVaultValidator`, `ProgrammableVaultExitDelegate`, `ProgrammableShardValidator`.
- **aiken**: `aiken/validators/programmable_vault.ak` (`h`), `programmable_vault_exit.ak` (`e`) and
  `programmable_shard.ak` (the shard variant), in the same Aiken project as `asset_shard.ak` and with its pinned
  toolchain (Aiken v1.1.21, stdlib v2.2.0). The lib modules are Yano X ports of what the julc scripts use:
  `yano_x/state_proof` (`MpfOnChainVerifier.verifyInclusion` and `verifyAbsence`, with the same bounds and
  proof-shape rules), `yano_x/anchor` (`MpfAnchorLib.acceptedAnchorStateIn` and `signedByThreshold`),
  `yano_x/codec` and `yano_x/gateway_tx` (`GatewayCodec`, `GatewayTx`, the value readers) and `yano_x/programmable`
  (`ProgrammableVaultLib`). The shard variant shares the native Aiken shard's rules through `yano_x/shard`, which
  leaves `asset_shard`'s compiled bytes unchanged.

Each Aiken script takes its julc twin's parameters in the same order and Data types (`byte[]` as `ByteArray`,
`BigInteger` as `Int`; parameters a script does not read are named with a leading `_`), has the same handlers, and
reads redeemers, datums and proofs positionally, checking a constructor tag only where the julc script does, so both
accept the same encodings. The deposit-shaped predicate and the V10 acceptance rule are total in both: julc reads
Data kinds from the canonical serialization, Aiken with `choose_data`.

`ProgrammableVaultBackendDifferentialTest` builds every transaction once with the julc scripts and runs each Aiken
script on exactly its julc twin's script context (the Aiken `h` applied with the julc `e`'s hash, the Aiken shard
variant with the julc `h`'s), and fails on any differing verdict (I40). It covers every action and alternative
honest values, every negative of `ProgrammableVaultNegativeTest`, the state-proof verifier (honest inclusion at 23
trie sizes, absence at every terminal kind and cursor parity, absence proofs of present keys, systematic and random
proof changes), systematic and random changes of every redeemer, of the shard redeemer, of every datum the scripts
read and of the transaction (inputs, outputs, values, addresses, reference inputs, signers, validity range,
withdrawals, mint, certificates), and lenient encodings both accept. `AssetShardBackendDifferentialTest` also runs
both shard variants on each of its cases with the vault input turned into the vault script's withdrawal. Sizes and
units of the Aiken backend are in [`BUDGETS.md`](BUDGETS.md#the-aiken-backend-adr-061-d3).

The artifacts follow the Aiken shard's pattern (below): `aiken/plutus.json` holds every Aiken validator, and
`META-INF/plutus/ProgrammableVaultValidatorAiken.plutus.json`, `ProgrammableVaultExitDelegateAiken.plutus.json` and
`ProgrammableShardValidatorAiken.plutus.json` wrap the blueprint's `programmable_vault.programmable_vault.withdraw`,
`programmable_vault_exit.programmable_vault_exit.withdraw` and `programmable_shard.programmable_shard.spend`
`compiledCode` in one more CBOR byte string. `AikenShardArtifactTest` checks each envelope against its blueprint
validator and its parameters against the julc `@Param` order on every build.

## Shard backends (ADR-059 D8c, r23)

The nullifier shard has two interchangeable backends. Both take the same parameters, datum and redeemer and
enforce the same rules; they differ only in their compiled bytes, so the shard script hash and address depend on
the backend.

- **julc** (default): `AssetShardValidator`, with Yano X's copy of the MPF library in `mpf/`.
- **aiken**: `aiken/validators/asset_shard.ak`. It uses aiken-lang/merkle-patricia-forestry v2.1.0 (pinned in
  `aiken/aiken.toml`), and Yano X applies its own proof-shape checks (`aiken/lib/yano_x/mpf_guard.ak`: well-formedness
  and terminal unambiguity) before the library's has, miss and insert, matching the julc backend's rules
  (`isWellFormed`, `excludedTerminalIsUnambiguous`). The redeemer, values and addresses are read positionally from
  their Plutus Data, as the julc script reads them, so both accept the same encodings.

A deployment selects the backend with the gateway setting `shard-backend` (`asset-gateway-cardano` README); the
shard thread policy and the vault are the same under either. `AssetShardBackendDifferentialTest` runs both backends
on the same transactions (honest inserts at 26 trie sizes and 6 batch sizes covering every terminal proof shape,
the conformance proof shapes, systematic and random proof changes, lenient encodings, and every transaction-shape
rule) and fails on any differing verdict. Their costs are in [`BUDGETS.md`](BUDGETS.md).

The checked Aiken artifacts are `aiken/plutus.json` (the blueprint, which also holds the CIP-113 custody validators)
and `src/main/resources/META-INF/plutus/AssetShardValidatorAiken.plutus.json` (its spend validator, one more CBOR
byte-string layer, the envelope deployments parameterize). The ordinary build never runs the Aiken compiler;
`AikenShardArtifactTest` checks on every build that the envelope is the blueprint's validator and that the toolchain
and library are pinned. With Aiken v1.1.21 installed, run the module's Aiken tests and check that the blueprint is
reproducible:

```bash
./gradlew :capabilities:asset-gateway-onchain:verifyAikenShard -Paiken=/path/to/aiken   # aiken check + aiken build
# or, in capabilities/asset-gateway-onchain/aiken: aiken check && aiken build
```

After changing the Aiken sources, run `aiken build` there, rewrite each envelope from its blueprint validator's
`compiledCode` (wrapped in one more CBOR byte string: `asset_shard.asset_shard.spend` for the shard, and the three
custody validators above), and rerun the differential and budget tests.

Implementation notes:

- The nullifier shards use the EUTxO bridge's Aiken-format MPF library, copied unchanged into `mpf/`, so this module
  does not depend on the bridge. It tracks PR #113: `miss()` rejects a terminal step that reads as the other node
  kind (a terminal leaf at an odd cursor with raw key bytes all below 16, read as a branch; a terminal fork whose
  reconstructed prefix starts with nibble 0 at exactly the odd-leaf length, read as the excluded id's own leaf),
  and a fork neighbor must end above the leaf level. The shard's `fold` calls `miss()` before `including()`, so a
  malformed exclusion proof cannot re-insert a paid id. App-ledger state proofs use `sdk/proof-onchain`
  (`MpfOnChainVerifier`, `MpfAnchorLib`); `verifyAbsence` covers every absent key (empty slot, other leaf, or a
  branch whose prefix leaves the key's path and does not have the shape of the key's own odd leaf), so a post-freeze
  deposit is always refundable and a censored request always provable.
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
