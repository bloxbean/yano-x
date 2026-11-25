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
  committed artifacts.

## Execution units and transaction sizes

| Transaction | k | Vault inputs | CPU | Memory | CPU % | Memory % | Tx bytes | Notes |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `Settle` | 1 | 1 | 556,797,645 | 1,882,786 | 5.6 | 11.4 | 1,735 | redeemers 559 |
| `Settle` | 4 | 1 | 956,925,583 | 3,355,019 | 9.6 | 20.3 | 3,848 | redeemers 2336 |
| `Settle` | 8 | 1 | 1,540,641,555 | 5,492,484 | 15.4 | 33.3 | 6,766 | redeemers 4805 |
| `Settle` | 16 | 1 | 2,623,194,036 | 9,468,309 | 26.2 | 57.4 | 12,381 | redeemers 9524 |
| `Settle` | 20 | 1 | 3,181,812,703 | 11,517,935 | 31.8 | 69.8 | 15,225 | redeemers 11920 |
| `Settle` | 21 | 1 | 3,337,770,895 | 12,085,650 | 33.4 | 73.2 | 15,957 | redeemers 12539 |
| `Settle` | 22 | 1 | 3,464,550,956 | 12,550,188 | 34.6 | 76.1 | 16,615 | redeemers 13085 **exceeds size** |
| `Settle` | 16 | 4 | 2,907,622,098 | 10,384,056 | 29.1 | 62.9 | 12,546 | redeemers 9536 |
| `Settle` | 21 | 4 | 3,622,198,957 | 13,001,397 | 36.2 | 78.8 | 16,122 | redeemers 12551 |
| `Settle` | 16 | 8 | 3,352,885,026 | 11,726,264 | 33.5 | 71.1 | 12,766 | redeemers 9552 |
| `Settle` | 21 | 8 | 4,067,461,885 | 14,343,605 | 40.7 | 86.9 | 16,342 | redeemers 12567 |
| `Exit` | 1 | 1 | 849,478,689 | 2,872,448 | 8.5 | 17.4 | 3,023 | folds 4 redeemers 2114 |
| `Exit` | 2 | 1 | 1,125,356,256 | 3,826,676 | 11.3 | 23.2 | 4,511 | folds 4 redeemers 3490 |
| `Exit` | 4 | 1 | 1,677,823,457 | 5,738,559 | 16.8 | 34.8 | 7,487 | folds 4 redeemers 6242 |
| `Exit` | 6 | 1 | 2,270,544,836 | 7,796,672 | 22.7 | 47.3 | 10,746 | folds 4 redeemers 9277 |
| `Exit` | 8 | 1 | 2,876,872,947 | 9,894,163 | 28.8 | 60.0 | 13,940 | folds 5 redeemers 12246 |
| `Exit` | 9 | 1 | 3,181,150,499 | 10,945,390 | 31.8 | 66.3 | 15,572 | folds 5 redeemers 13766 |
| `Exit` | 10 | 1 | 3,483,900,706 | 11,991,454 | 34.8 | 72.7 | 17,204 | folds 5 redeemers 15286 **exceeds size** |
| `Exit` | 8 | 4 | 3,161,301,009 | 10,809,910 | 31.6 | 65.5 | 14,105 | folds 5 redeemers 12258 |
| `Exit` | 9 | 4 | 3,465,578,561 | 11,861,137 | 34.7 | 71.9 | 15,737 | folds 5 redeemers 13778 |
| `BalanceExit` | 1 | 1 | 585,100,955 | 1,971,958 | 5.9 | 12.0 | 2,448 | folds 5 |
| `BalanceExit` | 1 | 4 | 932,422,226 | 3,049,051 | 9.3 | 18.5 | 2,613 | folds 5 |
| `BalanceExit` | 1 | 8 | 1,461,542,766 | 4,606,387 | 14.6 | 27.9 | 2,833 | folds 5 |
| `BalanceExit` | 1 | 16 | 2,746,157,030 | 8,136,643 | 27.5 | 49.3 | 3,273 | folds 5 |
| `BalanceExit` | 1 | 24 | 4,332,602,206 | 12,221,011 | 43.3 | 74.1 | 3,717 | folds 5 |
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
| `AcceptProved` | 16 deposits | size: 18 need 17.5 KB (memory 100.0%) | 12 |
| `Consolidate` (EXIT only) | 36 inputs | memory: 38 inputs need 103.9% | 32 |
| `BalanceExit` | 24+ vault inputs measured (74.1% memory, 3.7 KB) | memory | n/a |

- **`Settle`.** 16 claims (the ADR's target) use 57% of the memory limit and 12.4 KB; the redeemers (about 600 B per
  claim) and the payout outputs bind first, at 21. Extra vault inputs cost about 95 M CPU, 305 K memory and 55 bytes
  each, so a batch can draw on fragmented custody without lowering the cap.
- **`Exit`.** 9 claims fit in 15.6 KB; each claim carries a state proof (about 1 KB at 4–5 folds) and a shard insert
  proof. Deployments set `maxExitBatch` to 8, which leaves about 2.4 KB for deeper tries and base-address recipients.
  Re-measure before raising it.
- **`AcceptProved`.** The lead carries one proof per deposit and every other deposit input carries the 4-byte
  `Follow` marker, so the cap doubled from 8 (when every input repeated the full redeemer) to 16; relayers batch 12.
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

## Script sizes

A reference script must fit in one output of a transaction of at most 16,384 bytes. Sizes are the CBOR-wrapped
script bytes, as the ledger stores them.

| Script | Template bytes | Applied bytes |
|---|---:|---:|
| `AssetVaultValidator` | 14,881 | 15,397 (token vault), 15,364 (ADA vault) |
| `AssetShardValidator` | 4,142 | |
| `ExitModeThreadValidator` | 7,152 | |
| `ForcedInboxValidator` | 10,237 | |
| `AssetShardThreadPolicy` | 1,713 | |
| `ExitModeThreadPolicy` | 1,042 | |

The vault is the largest script. A first draft compiled to 18,630 bytes, which cannot be published as a reference
script. It fits after changes that keep the rules unchanged: the redeemer is decoded without julc's typed boundary
check (an ill-typed field still fails the script), multi-accumulator loops became explicit recursion, the
`SweepExit` payout check compares the swept native assets as one map instead of with a general multi-asset
comparison, and `accepted(datum, value)` reads the datum's canonical Plutus serialization (`serialiseData`), which is
also what makes it total. `GatewayBudgetTest.scriptSizes` fails if an applied vault reaches 15,500 bytes; about 100 bytes of headroom
remain under that self-imposed guard (about 600 under the real ceiling of roughly 16,000 bytes after the publishing
transaction's overhead). The next on-chain rule may raise the guard, to at most 15,750 bytes, with its reason
recorded here.

`ForcedInboxValidator` was 6,890 bytes. With the previous `MpfOnChainVerifier` sources it compiled to 7,067 bytes
(before the single-script-input rule); the rest of the growth comes from the rewritten `verifyAbsence`, which the inbox never calls (it only calls
`verifyInclusion`), so julc 0.1.0-pre20 evidently does not eliminate that code. The size is still well within the
limit.
