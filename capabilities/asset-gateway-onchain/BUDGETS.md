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
| `Settle` | 1 | 1 | 556,557,645 | 1,881,286 | 5.6 | 11.4 | 1,735 | redeemers 559 |
| `Settle` | 4 | 1 | 956,685,583 | 3,353,519 | 9.6 | 20.3 | 3,848 | redeemers 2336 |
| `Settle` | 8 | 1 | 1,540,401,555 | 5,490,984 | 15.4 | 33.3 | 6,766 | redeemers 4805 |
| `Settle` | 16 | 1 | 2,622,954,036 | 9,466,809 | 26.2 | 57.4 | 12,381 | redeemers 9524 |
| `Settle` | 20 | 1 | 3,181,572,703 | 11,516,435 | 31.8 | 69.8 | 15,225 | redeemers 11920 |
| `Settle` | 21 | 1 | 3,337,530,895 | 12,084,150 | 33.4 | 73.2 | 15,957 | redeemers 12539 |
| `Settle` | 22 | 1 | 3,464,310,956 | 12,548,688 | 34.6 | 76.1 | 16,615 | redeemers 13085 **exceeds size** |
| `Settle` | 16 | 4 | 2,907,142,098 | 10,381,056 | 29.1 | 62.9 | 12,546 | redeemers 9536 |
| `Settle` | 21 | 4 | 3,621,718,957 | 12,998,397 | 36.2 | 78.8 | 16,122 | redeemers 12551 |
| `Settle` | 16 | 8 | 3,352,085,026 | 11,721,264 | 33.5 | 71.0 | 12,766 | redeemers 9552 |
| `Settle` | 21 | 8 | 4,066,661,885 | 14,338,605 | 40.7 | 86.9 | 16,342 | redeemers 12567 |
| `Exit` | 1 | 1 | 849,238,689 | 2,870,948 | 8.5 | 17.4 | 3,023 | folds 4 redeemers 2114 |
| `Exit` | 2 | 1 | 1,125,116,256 | 3,825,176 | 11.3 | 23.2 | 4,511 | folds 4 redeemers 3490 |
| `Exit` | 4 | 1 | 1,677,583,457 | 5,737,059 | 16.8 | 34.8 | 7,487 | folds 4 redeemers 6242 |
| `Exit` | 6 | 1 | 2,270,304,836 | 7,795,172 | 22.7 | 47.2 | 10,746 | folds 4 redeemers 9277 |
| `Exit` | 8 | 1 | 2,876,632,947 | 9,892,663 | 28.8 | 60.0 | 13,940 | folds 5 redeemers 12246 |
| `Exit` | 9 | 1 | 3,180,910,499 | 10,943,890 | 31.8 | 66.3 | 15,572 | folds 5 redeemers 13766 |
| `Exit` | 10 | 1 | 3,483,660,706 | 11,989,954 | 34.8 | 72.7 | 17,204 | folds 5 redeemers 15286 **exceeds size** |
| `Exit` | 8 | 4 | 3,160,821,009 | 10,806,910 | 31.6 | 65.5 | 14,105 | folds 5 redeemers 12258 |
| `Exit` | 9 | 4 | 3,465,098,561 | 11,858,137 | 34.7 | 71.9 | 15,737 | folds 5 redeemers 13778 |
| `BalanceExit` | 1 | 1 | 584,860,955 | 1,970,458 | 5.8 | 11.9 | 2,448 | folds 5 |
| `BalanceExit` | 1 | 4 | 931,462,226 | 3,043,051 | 9.3 | 18.4 | 2,613 | folds 5 |
| `BalanceExit` | 1 | 8 | 1,459,622,766 | 4,594,387 | 14.6 | 27.8 | 2,833 | folds 5 |
| `BalanceExit` | 1 | 16 | 2,742,317,030 | 8,112,643 | 27.4 | 49.2 | 3,273 | folds 5 |
| `BalanceExit` | 1 | 24 | 4,326,842,206 | 12,185,011 | 43.3 | 73.8 | 3,717 | folds 5 |
| `AcceptProved` | 1 | 1 | 390,995,383 | 1,334,377 | 3.9 | 8.1 | 1,418 | folds 4 redeemers 869 |
| `AcceptProved` | 4 | 4 | 1,117,668,462 | 3,778,869 | 11.2 | 22.9 | 4,172 | folds 4 redeemers 3470 |
| `AcceptProved` | 8 | 8 | 2,151,157,527 | 7,153,594 | 21.5 | 43.4 | 7,844 | folds 4 redeemers 6938 |
| `AcceptProved` | 12 | 12 | 3,288,004,219 | 10,762,826 | 32.9 | 65.2 | 11,660 | folds 4 redeemers 10550 |
| `AcceptProved` | 16 | 16 | 4,502,533,634 | 14,519,601 | 45.0 | 88.0 | 15,476 | folds 4 redeemers 14162 |
| `AcceptProved` | 18 | 18 | 5,146,259,952 | 16,474,487 | 51.5 | 99.8 | 17,456 | folds 4 redeemers 16040 **exceeds size** |
| `Consolidate` | 2 | 2 | 276,305,050 | 908,962 | 2.8 | 5.5 | 607 | redeemers 8 |
| `Consolidate` | 4 | 4 | 457,377,908 | 1,490,997 | 4.6 | 9.0 | 717 | redeemers 16 |
| `Consolidate` | 8 | 8 | 904,763,864 | 2,825,742 | 9.0 | 17.1 | 937 | redeemers 32 |
| `Consolidate` | 16 | 16 | 1,997,262,016 | 5,844,037 | 20.0 | 35.4 | 1,377 | redeemers 64 |
| `Consolidate` | 24 | 24 | 3,397,480,197 | 9,428,797 | 34.0 | 57.1 | 1,819 | redeemers 96 |
| `Consolidate` | 32 | 32 | 5,116,398,000 | 13,609,742 | 51.2 | 82.5 | 2,275 | redeemers 128 |
| `Consolidate` | 36 | 36 | 6,069,230,225 | 15,859,757 | 60.7 | 96.1 | 2,504 | redeemers 144 |
| `Consolidate` | 38 | 38 | 6,593,756,254 | 17,084,962 | 65.9 | 103.5 | 2,618 | redeemers 152 **exceeds units** |
| `EnterExitMode(b)` | 1 | 0 | 250,670,195 | 852,229 | 2.5 | 5.2 | 1,257 | absence folds=4 |

"redeemers" is the CBOR size of all spend redeemers together (vault and shard); "folds" is the state-proof depth.

## Batch caps

| Redeemer | Largest batch that fits | Binding limit | Recommended profile cap |
|---|---:|---|---:|
| `Settle` (`maxSettleBatch`) | 21 claims (1, 4 or 8 vault inputs) | size: 22 claims need 16.6 KB | 16 |
| `Exit` (`maxExitBatch`) | 9 claims (1 or 4 vault inputs) | size: 10 claims need 17.2 KB | 8 |
| `AcceptProved` | 16 deposits | size: 18 need 17.5 KB (memory 99.8%) | 12 |
| `Consolidate` | 36 inputs | memory: 38 inputs need 103.5% | 32 |
| `BalanceExit` | 24+ vault inputs measured (73.8% memory, 3.7 KB) | memory | n/a |

- **`Settle`.** 16 claims (the ADR's target) use 57% of the memory limit and 12.4 KB; the redeemers (about 600 B per
  claim) and the payout outputs bind first, at 21. Extra vault inputs cost about 95 M CPU, 305 K memory and 55 bytes
  each, so a batch can draw on fragmented custody without lowering the cap.
- **`Exit`.** 9 claims fit in 15.6 KB; each claim carries a state proof (about 1 KB at 4–5 folds) and a shard insert
  proof. Deployments set `maxExitBatch` to 8, which leaves about 2.4 KB for deeper tries and base-address recipients.
  Re-measure before raising it.
- **`AcceptProved`.** The lead carries one proof per deposit and every other deposit input carries the 4-byte
  `Follow` marker, so the cap doubled from 8 (when every input repeated the full redeemer) to 16; relayers batch 12.
- **`Consolidate`.** Permissionless; it merges accepted custody in NORMAL and EXIT. 32 inputs per transaction, so 512
  one-unit outputs need 16 consolidations, after which any `BalanceExit` or payout is a one-input transaction.
- The lead's per-input work and each follower's search for the lead both grow with the input count, so the cost of a
  transaction with many vault inputs grows faster than linearly (`Consolidate`: 0.9 M memory at 2 inputs, 15.9 M at
  36).

## Script sizes

A reference script must fit in one output of a transaction of at most 16,384 bytes. Sizes are the CBOR-wrapped
script bytes, as the ledger stores them.

| Script | Template bytes | Applied bytes |
|---|---:|---:|
| `AssetVaultValidator` | 14,731 | 15,247 (token vault), 15,214 (ADA vault) |
| `AssetShardValidator` | 4,142 | |
| `ExitModeThreadValidator` | 7,074 | |
| `ForcedInboxValidator` | 9,985 | |
| `AssetShardThreadPolicy` | 1,713 | |
| `ExitModeThreadPolicy` | 1,042 | |

The vault is the largest script. A first draft compiled to 18,630 bytes, which cannot be published as a reference
script. It fits after changes that keep the rules unchanged: the redeemer is decoded without julc's typed boundary
check (an ill-typed field still fails the script), multi-accumulator loops became explicit recursion, the
`SweepExit` payout check compares the swept value exactly instead of with a general multi-asset comparison, and
`acceptedDatum` reads the datum's canonical Plutus serialization (`serialiseData`), which is also what makes it
total. `GatewayBudgetTest.scriptSizes` fails if an applied vault reaches 15,500 bytes; about 250 bytes of headroom
remain under that guard (about 850 under the real limit).

`ForcedInboxValidator` was 6,890 bytes. With the previous `MpfOnChainVerifier` sources it compiles to 7,067 bytes
now; the rest of the growth comes from the rewritten `verifyAbsence`, which the inbox never calls (it only calls
`verifyInclusion`), so julc 0.1.0-pre20 evidently does not eliminate that code. The size is still well within the
limit.
