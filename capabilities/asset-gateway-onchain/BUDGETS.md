# Asset gateway validators: measured budgets

ADR-059 §10 requires budgets to be measured, not estimated, and M4 asks for the `Settle` and `Exit` batch caps.
These figures come from `GatewayBudgetTest`, which evaluates the compiled, parameterized Plutus V3 scripts on the
julc VM against real tries and serializes each whole transaction. Rerun it with:

```bash
./gradlew :capabilities:asset-gateway-onchain:test --tests '*GatewayBudgetTest' -i | grep -E 'BUDGET\||CAP\||SIZE\|'
```

## Method

- **Per transaction.** Each row is one whole transaction. The units are the sum over every script it runs: the lead
  vault input (which runs the full check over every vault input), the follower vault inputs (which carry the
  `Follow` marker and only check that the lead carries an action, V13) and the shard.
- **Transaction size.** The bytes column is the full serialized transaction (`TxSizes`): the body (inputs, outputs
  with their inline datums, the reference inputs including the reference-script UTxOs, collateral and collateral
  return, required signers, fee, validity interval, script data hash) and the witness set (a vkey witness per
  required signer plus the fee payer, and one spend redeemer, with its execution units, per script input). Scripts
  are always referenced, never inlined. Every payout transaction also carries a change output.
- **Limits.** Mainnet per-transaction limits: 16,500,000 memory units, 10,000,000,000 CPU steps and 16,384 bytes.
  The percentages are of the unit limits. A batch cap is the largest batch whose units and size both fit.
- **Depth.** The app-ledger state trie (mpf-blake2b256-v1) holds 20,000 leaves, so claim, exit, deposit and status
  proofs have 4–5 folds. Each nullifier shard holds 4,000 paid ids (a vault with 64,000 paid claims), so every
  exclusion and insert proof walks about three levels. Costs grow linearly with depth.
- **Shape.** The token vault, 2-of-3 members for co-signed redeemers, recipients with enterprise key addresses
  (29 bytes; a base address adds 28 bytes per payout). "Vault inputs" counts the lead plus `Follow` inputs (accepted
  floor top-ups for `Settle` and `Exit`, small continuations for `BalanceExit`, deposits for `AcceptProved`,
  alternating continuations and top-ups for `Consolidate`). Caps are measured with the profile's batch caps set to
  the on-chain maximum (64).
- **Toolchain.** julc 0.1.0-pre20, default optimization level (`pv11-safe`), the same toolchain that compiles the
  committed artifacts. The Aiken shard backend has its own section below.

## Execution units and transaction sizes

| Transaction | k | Vault inputs | CPU | Memory | CPU % | Memory % | Tx bytes | Notes |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `Settle` | 1 | 1 | 562,524,910 | 1,903,529 | 5.6 | 11.5 | 1,735 | redeemers 559 |
| `Settle` | 4 | 1 | 981,400,028 | 3,441,816 | 9.8 | 20.9 | 3,848 | redeemers 2336 |
| `Settle` | 8 | 1 | 1,599,056,400 | 5,698,538 | 16.0 | 34.5 | 6,766 | redeemers 4805 |
| `Settle` | 16 | 1 | 2,734,594,673 | 9,860,528 | 27.3 | 59.8 | 12,381 | redeemers 9524 |
| `Settle` | 20 | 1 | 3,321,916,687 | 12,010,722 | 33.2 | 72.8 | 15,225 | redeemers 11920 |
| `Settle` | 21 | 1 | 3,487,831,046 | 12,612,951 | 34.9 | 76.4 | 15,957 | redeemers 12539 |
| `Settle` | 22 | 1 | 3,620,146,372 | 13,097,032 | 36.2 | 79.4 | 16,615 | redeemers 13085 **exceeds size** |
| `Settle` | 16 | 4 | 3,019,022,735 | 10,776,275 | 30.2 | 65.3 | 12,546 | redeemers 9536 |
| `Settle` | 21 | 4 | 3,772,259,108 | 13,528,698 | 37.7 | 82.0 | 16,122 | redeemers 12551 |
| `Settle` | 16 | 8 | 3,464,285,663 | 12,118,483 | 34.6 | 73.4 | 12,766 | redeemers 9552 |
| `Settle` | 21 | 8 | 4,217,522,036 | 14,870,906 | 42.2 | 90.1 | 16,342 | redeemers 12567 |
| `Exit` | 1 | 1 | 859,626,856 | 2,908,162 | 8.6 | 17.6 | 3,023 | folds 4 redeemers 2114 |
| `Exit` | 2 | 1 | 1,141,039,688 | 3,881,933 | 11.4 | 23.5 | 4,511 | folds 4 redeemers 3490 |
| `Exit` | 4 | 1 | 1,704,577,419 | 5,832,902 | 17.0 | 35.4 | 7,487 | folds 4 redeemers 6242 |
| `Exit` | 6 | 1 | 2,310,510,713 | 7,937,526 | 23.1 | 48.1 | 10,746 | folds 4 redeemers 9277 |
| `Exit` | 8 | 1 | 2,932,330,256 | 10,089,074 | 29.3 | 61.1 | 13,940 | folds 5 redeemers 12246 |
| `Exit` | 9 | 1 | 3,242,143,073 | 11,159,844 | 32.4 | 67.6 | 15,572 | folds 5 redeemers 13766 |
| `Exit` | 10 | 1 | 3,550,428,545 | 12,225,451 | 35.5 | 74.1 | 17,204 | folds 5 redeemers 15286 **exceeds size** |
| `Exit` | 8 | 4 | 3,216,758,318 | 11,004,821 | 32.2 | 66.7 | 14,105 | folds 5 redeemers 12258 |
| `Exit` | 9 | 4 | 3,526,571,135 | 12,075,591 | 35.3 | 73.2 | 15,737 | folds 5 redeemers 13778 |
| `BalanceExit` | 1 | 1 | 590,828,220 | 1,992,701 | 5.9 | 12.1 | 2,448 | folds 5 |
| `BalanceExit` | 1 | 4 | 938,149,491 | 3,069,794 | 9.4 | 18.6 | 2,613 | folds 5 |
| `BalanceExit` | 1 | 8 | 1,467,270,031 | 4,627,130 | 14.7 | 28.0 | 2,833 | folds 5 |
| `BalanceExit` | 1 | 16 | 2,751,884,295 | 8,157,386 | 27.5 | 49.4 | 3,273 | folds 5 |
| `BalanceExit` | 1 | 24 | 4,338,329,471 | 12,241,754 | 43.4 | 74.2 | 3,717 | folds 5 |
| `AcceptProved` | 1 | 1 | 391,187,383 | 1,335,577 | 3.9 | 8.1 | 1,418 | folds 4 redeemers 869 |
| `AcceptProved` | 4 | 4 | 1,118,436,462 | 3,783,669 | 11.2 | 22.9 | 4,172 | folds 4 redeemers 3470 |
| `AcceptProved` | 8 | 8 | 2,152,693,527 | 7,163,194 | 21.5 | 43.4 | 7,844 | folds 4 redeemers 6938 |
| `AcceptProved` | 12 | 12 | 3,290,308,219 | 10,777,226 | 32.9 | 65.3 | 11,660 | folds 4 redeemers 10550 |
| `AcceptProved` | 16 | 16 | 4,505,605,634 | 14,538,801 | 45.1 | 88.1 | 15,476 | folds 4 redeemers 14162 |
| `AcceptProved` | 18 | 18 | 5,149,715,952 | 16,496,087 | 51.5 | 100.0 | 17,456 | folds 4 redeemers 16040 **exceeds size** |
| `Consolidate` | 2 | 2 | 279,425,238 | 922,300 | 2.8 | 5.6 | 607 | redeemers 8 |
| `Consolidate` | 4 | 4 | 460,818,096 | 1,506,335 | 4.6 | 9.1 | 717 | redeemers 16 |
| `Consolidate` | 8 | 8 | 909,004,052 | 2,846,080 | 9.1 | 17.2 | 937 | redeemers 32 |
| `Consolidate` | 16 | 16 | 2,002,942,204 | 5,873,375 | 20.0 | 35.6 | 1,377 | redeemers 64 |
| `Consolidate` | 24 | 24 | 3,404,600,385 | 9,467,135 | 34.0 | 57.4 | 1,819 | redeemers 96 |
| `Consolidate` | 32 | 32 | 5,125,118,188 | 13,658,080 | 51.3 | 82.8 | 2,275 | redeemers 128 |
| `Consolidate` | 36 | 36 | 6,078,590,413 | 15,912,095 | 60.8 | 96.4 | 2,504 | redeemers 144 |
| `Consolidate` | 38 | 38 | 6,603,596,442 | 17,140,300 | 66.0 | 103.9 | 2,618 | redeemers 152 **exceeds units** |
| `EnterExitMode(b)` | 1 | 0 | 250,862,195 | 853,429 | 2.5 | 5.2 | 1,257 | absence folds=4 |

"redeemers" is the CBOR size of all spend redeemers together (vault and shard); "folds" is the state-proof depth.

## Batch caps

| Redeemer | Largest batch that fits | Binding limit | Recommended profile cap |
|---|---:|---|---:|
| `Settle` (`maxSettleBatch`) | 21 claims (1, 4 or 8 vault inputs) | size: 22 claims need 16.6 KB | 16 |
| `Exit` (`maxExitBatch`) | 9 claims (1 or 4 vault inputs) | size: 10 claims need 17.2 KB | 8 |
| `AcceptProved` | 16 deposits | size: 18 need 17.5 KB (memory 100%) | 12 |
| `Consolidate` (EXIT only) | 36 inputs | memory: 38 inputs need 103.9% | 32 |
| `BalanceExit` | 24+ vault inputs measured (74% memory, 3.7 KB) | memory | n/a |

- **`Settle`.** 16 claims (the ADR's target) use 60% of the memory limit and 12.4 KB; the redeemers (about 600 B per
  claim) and the payout outputs bind first, at 21. Extra vault inputs cost about 95 M CPU, 305 K memory and 55 bytes
  each, so a batch can draw on fragmented custody without lowering the cap.
- **`Exit`.** 9 claims fit in 15.6 KB; each claim carries a state proof (about 1 KB at 4–5 folds) and a shard insert
  proof. Deployments set `maxExitBatch` to 8, which leaves about 2.4 KB for deeper tries and base-address recipients.
  Re-measure before raising it.
- **`AcceptProved`.** The lead carries one proof per deposit and every other deposit input carries the 4-byte
  `Follow` marker, so the cap doubled from 8 (when every input repeated the full redeemer) to 16; relayers batch 12.
- **`AcceptCosigned`** (M4 part 2 stage 3a). It carries no proof, only the members' threshold. Measured on the built
  transaction by `AcceptTxBuilderTest` in `asset-gateway-cardano-support` (same toolchain, 2-of-3): 16 deposits of the
  token vault use 6,484,642 memory (39% of the limit) in about 1.4 KB. The owner batches 16 (D8f: "batches of about
  16"); a proved fallback batches 12.
- **`Consolidate`.** Permissionless and EXIT-only (in NORMAL anyone could use it to invalidate in-flight payouts every
  round, and `Settle` already merges its inputs). 32 inputs per transaction: 512 one-unit outputs need 16
  consolidations into 16 outputs and a 17th that merges those 16 (or a `BalanceExit` that spends the 16 directly,
  which fits: 16 vault inputs use 49% of the memory limit and 3.3 KB).
- The lead's per-input work and each follower's search for the lead both grow with the input count, so the cost of a
  transaction with many vault inputs grows faster than linearly (`Consolidate`: 0.9 M memory at 2 inputs, 15.9 M at
  36).
- `Consolidate` rows are measured in EXIT mode. The custody predicate `accepted(datum, value)` (a clean continuation,
  or a `FloorTopUpV1` holding lovelace alone) judges the inputs of the payouts (`Settle`, `Exit`, `BalanceExit`) and of
  `Consolidate`, and the sweep exclusion; `Accept` takes deposit-shaped inputs instead.

## The Aiken shard backend (r23)

A deployment may select the Aiken shard (`shard-backend: aiken`, ADR-059 D8c r23): `aiken/validators/asset_shard.ak`,
aiken-lang/merkle-patricia-forestry v2.1.0 behind Yano X's own proof-shape checks, compiled with Aiken v1.1.21
(stdlib v2.2.0, default build without traces). `GatewayBudgetTest.settleAndExitCapsWithTheAikenShard` measures the
same transactions as above with the Aiken shard in place of the julc one; the vault and the transaction bytes are
unchanged (the redeemers and the shard's datum are the same), so only the shard's units differ.

| Transaction | k | Vault inputs | CPU | Memory | CPU % | Memory % | Tx bytes | julc shard, memory % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `Settle` | 1 | 1 | 559,447,392 | 1,826,370 | 5.6 | 11.1 | 1,735 | 11.5 |
| `Settle` | 8 | 1 | 1,700,372,558 | 5,704,849 | 17.0 | 34.6 | 6,766 | 34.5 |
| `Settle` | 16 | 1 | 2,944,560,053 | 9,945,137 | 29.4 | 60.3 | 12,381 | 59.8 |
| `Settle` | 21 | 1 | 3,774,729,719 | 12,760,306 | 37.7 | 77.3 | 15,957 | 76.4 |
| `Settle` | 16 | 8 | 3,676,011,004 | 12,201,447 | 36.8 | 73.9 | 12,766 | 73.4 |
| `Settle` | 21 | 8 | 4,506,180,670 | 15,016,616 | 45.1 | 91.0 | 16,342 | 90.1 |
| `Exit` | 1 | 1 | 863,986,514 | 2,843,869 | 8.6 | 17.2 | 3,023 | 17.6 |
| `Exit` | 8 | 1 | 3,030,347,573 | 10,093,951 | 30.3 | 61.2 | 13,940 | 61.1 |
| `Exit` | 9 | 1 | 3,351,957,210 | 11,172,117 | 33.5 | 67.7 | 15,572 | 67.6 |
| `Exit` | 8 | 4 | 3,315,529,904 | 11,008,993 | 33.2 | 66.7 | 14,105 | 66.7 |
| `Exit` | 9 | 4 | 3,637,139,541 | 12,087,159 | 36.4 | 73.3 | 15,737 | 73.2 |

The shard alone, inserting into a shard of 4,000 paid ids (`AssetShardBackendDifferentialTest`):

| Inserts | julc CPU | julc memory | Aiken CPU | Aiken memory |
|---:|---:|---:|---:|---:|
| 1 | 182,272,029 | 651,629 | 185,521,432 | 589,359 |
| 4 | 421,579,476 | 1,593,879 | 458,883,378 | 1,549,383 |
| 8 | 850,377,867 | 3,189,305 | 964,854,415 | 3,239,243 |
| 16 | 1,526,101,481 | 5,809,983 | 1,742,471,289 | 5,929,752 |

- The caps are unchanged: `Settle` 21 and `Exit` 9, both bound by transaction size as with the julc shard, so the
  profile caps (16 and 8) hold for either backend. The Aiken shard uses up to about 14% more CPU and 2% more memory
  at 16 inserts, and less memory for small batches; the largest difference in a whole transaction is 0.9 points of
  the memory limit (`Settle` k = 21 with 8 vault inputs, 91.0% against 90.1%).
- The Aiken shard is 502 bytes larger. Its reference script still fits easily in one output.

## Script sizes

A reference script must fit in one output of a transaction of at most 16,384 bytes. Sizes are the CBOR-wrapped
script bytes, as the ledger stores them.

| Script | Template bytes | Applied bytes |
|---|---:|---:|
| `AssetVaultValidator` | 14,936 | 15,452 (token vault), 15,419 (ADA vault) |
| `AssetShardValidator` | 4,493 | 4,562 |
| `AssetShardValidatorAiken` (r23) | 4,995 | 5,064 |
| `ExitModeThreadValidator` | 7,207 | |
| `ForcedInboxValidator` | 10,347 | |
| `AssetShardThreadPolicy` | 1,713 | |
| `ExitModeThreadPolicy` | 1,042 | |

The vault is the largest script. A first draft compiled to 18,630 bytes, which cannot be published as a reference
script. It fits after changes that keep the rules unchanged: the redeemer is decoded without julc's typed boundary
check (an ill-typed field still fails the script), multi-accumulator loops became explicit recursion, the
`SweepExit` payout check compares the swept native assets as one map instead of with a general multi-asset
comparison, and `accepted(datum, value)` reads the datum's canonical Plutus serialization (`serialiseData`), which is
also what makes it total. `GatewayBudgetTest.scriptSizes` fails if an applied vault reaches 15,500 bytes; 48 bytes of headroom
remain under that self-imposed guard (about 550 under the real ceiling of roughly 16,000 bytes after the publishing
transaction's overhead). The next on-chain rule may raise the guard, to at most 15,750 bytes, with its reason
recorded here.

`AssetShardValidator` grew from 4,142 to 4,368 bytes with M4-F1 (reviewer3): its copy of the bridge's MPF library
now rejects a terminal leaf that reads as a branch in `miss()`, and a fork neighbor that does not end above the leaf
level, exactly as PR #113 does for the bridge. The shard's share of every payout rose accordingly (for example
`Settle` k = 16 from 57.4% to 59.7% of the memory limit); no cap changed.

The converse rule (r17) grew the shard to 4,493 bytes and the vault to 14,936 (15,452 applied for the token vault):
the shard's `miss()` refuses a terminal fork whose reconstructed prefix has the shape of the excluded id's own odd
leaf, and `MpfOnChainVerifier.verifyAbsence`, which the vault (`RefundAbsent`) and the exit-mode thread call, refuses
the same shape as a conflicting branch. `ExitModeThreadValidator` (7,207) and `ForcedInboxValidator` (10,347, which
carries `verifyAbsence` as dead code) grew with it. Payout costs rose by at most 0.2 points of the memory limit
(`Settle` k = 16 now 59.8%); `AcceptProved`, `Consolidate` and `EnterExitMode` are unchanged, and no cap changed.

`ForcedInboxValidator` was 6,890 bytes. With the previous `MpfOnChainVerifier` sources it compiled to 7,067 bytes
(before the single-script-input rule); the rest of the growth comes from the rewritten `verifyAbsence`, which the inbox never calls (it only calls
`verifyInclusion`), so julc 0.1.0-pre20 evidently does not eliminate that code. The size is still well within the
limit.

## CIP-113 custody (ADR-061)

`ProgrammableVaultSizeTest` and `ProgrammableVaultCompositionTest` measure the custody scripts of a CIP-113 vault.
The composition runs evaluate **every** script of each honest transaction in one script context: the pinned PLB
(once per vault input), dispatcher and transfer delegate of cip113-programmable-tokens `6b75ba32`, applied with a
fixture deployment (`Cip113Blueprint`), a test transfer hook per registered policy present, `h`, `e` (EXIT-only
actions) and the shard binding variant. Rerun with:

```bash
./gradlew :capabilities:asset-gateway-onchain:test --tests '*ProgrammableVault*' -i | grep -E 'BUDGET\||SIZE\|'
```

### Script sizes

| Script | Template bytes | Applied bytes |
|---|---:|---:|
| `ProgrammableVaultValidator` (`h`) | 14,608 | 15,200 |
| `ProgrammableVaultExitDelegate` (`e`) | 9,687 | 10,244 |
| `ProgrammableShardValidator` | 4,517 | 4,586 |

All three are under the 15,500-byte guard; `h` has 300 bytes of headroom. The split of ADR-061 D2 is what makes `h`
fit: `e` carries the four EXIT-only actions and the absence verifier.

### Execution units

Whole transactions: the sum over every script. Shape: the token vault, 2-of-3 members for co-signed actions,
recipients at key-owned smart wallets (57-byte base addresses), one registered policy with a test hook (two
registered policies and an unregistered token for the refunds), state tries of 32 to 64 leaves and shards of 5 paid
ids. The `h` column is the custody script's share.

| Transaction | k | Vault inputs | CPU | Memory | CPU % | Memory % | `h` memory |
|---|---:|---:|---:|---:|---:|---:|---:|
| `Settle` | 1 | 1 | 793,533,536 | 2,512,483 | 7.9 | 15.2 | 1,664,417 |
| `Settle` | 4 | 1 | 1,170,358,337 | 3,749,548 | 11.7 | 22.7 | 2,310,857 |
| `Settle` | 8 | 1 | 1,739,846,407 | 5,634,990 | 17.4 | 34.2 | 3,172,889 |
| `Settle` | 16 | 1 | 2,887,786,486 | 9,455,076 | 28.9 | 57.3 | 4,897,337 |
| `Exit` | 1 | 1 | 1,015,570,831 | 3,264,172 | 10.2 | 19.8 | 2,301,364 |
| `Exit` | 4 | 1 | 1,729,106,279 | 5,598,811 | 17.3 | 33.9 | 3,922,114 |
| `Exit` | 8 | 1 | 2,710,622,053 | 8,806,839 | 27.1 | 53.4 | 6,229,508 |
| `Exit` (EXIT) | 2 | 1 | 1,022,677,161 | 3,250,203 | 10.2 | 19.7 | 2,208,898 |
| `SweepExit` (outpoint) | 1 | 1 | 944,112,039 | 3,017,456 | 9.4 | 18.3 | 2,222,386 |
| `SweepExit` (`DepositId`) | 1 | 1 | 964,445,975 | 2,956,488 | 9.6 | 17.9 | 2,191,046 |
| `AcceptCosigned` | 1 | 1 | 543,144,593 | 1,618,439 | 5.4 | 9.8 | 1,279,922 |
| `AcceptCosigned` | 4 | 4 | 924,287,957 | 2,455,043 | 9.2 | 14.9 | 1,742,435 |
| `AcceptCosigned` | 8 | 8 | 1,432,479,109 | 3,570,515 | 14.3 | 21.6 | 2,359,119 |
| `AcceptProved` | 1 | 1 | 620,492,775 | 1,881,654 | 6.2 | 11.4 | 1,543,137 |
| `AcceptProved` | 4 | 4 | 1,311,300,985 | 3,761,194 | 13.1 | 22.8 | 3,048,586 |
| `AcceptProved` | 8 | 8 | 2,187,194,672 | 6,113,191 | 21.9 | 37.0 | 4,901,795 |
| `AcceptProved` (EXIT) | 2 | 2 | 729,650,500 | 2,102,600 | 7.3 | 12.7 | 1,640,544 |
| `RefundUncredited` | 1 | 1 | 641,956,838 | 1,903,621 | 6.4 | 11.5 | 420,841 (`e` 977,160) |
| `RefundAbsent` | 1 | 1 | 592,766,279 | 1,743,736 | 5.9 | 10.6 | 420,841 (`e` 817,275) |
| `BalanceExit` | 1 | 1 | 851,384,883 | 2,651,915 | 8.5 | 16.1 | 431,091 (`e` 1,364,508) |
| `BalanceExit` | 1 | 4 | 1,095,816,942 | 3,305,273 | 11.0 | 20.0 | 463,437 (`e` 1,659,684) |
| `Consolidate` | 2 | 2 | 559,663,734 | 1,691,772 | 5.6 | 10.3 | 431,623 (`e` 815,321) |
| `Consolidate` | 4 | 4 | 726,289,494 | 2,129,817 | 7.3 | 12.9 | 453,187 (`e` 1,002,822) |
| `Consolidate` | 8 | 8 | 1,059,541,014 | 3,005,907 | 10.6 | 18.2 | 496,315 (`e` 1,377,824) |

The CIP-113 core's own share per transaction: PLB about 57,000 memory per vault input, the dispatcher about 23,500,
the transfer delegate 186,000 to 982,000 (it scans every input and output), and the test hook 72,000 to 362,000.
The shard's share is the same as for the native vault.

**Not yet measured:** whole-transaction sizes. The native suite serializes each transaction (`TxSizes`); for a
CIP-113 vault that needs the assembler's output, so the batch caps of ADR-061 D9 (provisional: `Settle` 16, `Exit`
8, `AcceptCosigned` 16, `AcceptProved` 12) are not yet replaced by measured ones. By units alone every provisional
cap fits (`Settle` 16 at 57.3 % and `Exit` 8 at 53.4 % of the memory limit); sizes, with the larger base addresses
and a spend redeemer per vault input, are expected to bind first.
