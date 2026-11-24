# Asset gateway validators: measured budgets

ADR-059 §10 requires budgets to be measured, not estimated, and M4 asks for the `Settle` and `Exit` batch caps.
These figures come from `GatewayBudgetTest`, which evaluates the compiled, parameterized Plutus V3 scripts on the
julc VM against real tries. Rerun it with:

```bash
./gradlew :capabilities:asset-gateway-onchain:test --tests '*GatewayBudgetTest' -i | grep 'BUDGET|\|SIZE|'
```

## Method

- **Per transaction.** Each row is the sum over every script the transaction runs: the lead vault input (which runs
  the full check), any other vault inputs (which only compare their redeemer with the lead's, V13) and the shard.
- **Limits.** Mainnet per-transaction limits: 16,500,000 memory units and 10,000,000,000 CPU steps. The percentages
  are of those limits.
- **Depth.** The app-ledger state trie (mpf-blake2b256-v1) holds 20,000 leaves, so claim, exit, deposit and status
  proofs have 4–5 folds. Each nullifier shard holds 4,000 paid ids (a vault with 64,000 paid claims), so every
  exclusion and insert proof walks about three levels. Costs grow linearly with depth.
- **Shape.** The token vault, one vault input, 2-of-3 members for co-signed redeemers, recipients with enterprise
  key addresses.
- **Toolchain.** julc 0.1.0-pre20, default optimization level (`pv11-safe`), the same toolchain that compiles the
  committed artifacts.

## Execution units

| Transaction | k | CPU | Memory | CPU % | Memory % | Notes |
|---|---:|---:|---:|---:|---:|---|
| `Settle` | 1 | 549,786,466 | 1,861,782 | 5.5 | 11.3 | redeemers 559 B |
| `Settle` | 4 | 953,597,069 | 3,334,015 | 9.5 | 20.2 | redeemers 2,336 B |
| `Settle` | 8 | 1,542,223,261 | 5,471,480 | 15.4 | 33.2 | redeemers 4,805 B |
| `Settle` | 16 | 2,634,596,182 | 9,447,305 | 26.3 | 57.3 | redeemers 9,524 B |
| `Exit` | 1 | 856,188,286 | 2,848,438 | 8.6 | 17.3 | redeemers 2,114 B |
| `Exit` | 2 | 1,140,972,970 | 3,803,266 | 11.4 | 23.1 | redeemers 3,490 B |
| `Exit` | 3 | 1,425,725,288 | 4,758,500 | 14.3 | 28.8 | redeemers 4,866 B |
| `Exit` | 4 | 1,711,254,405 | 5,716,349 | 17.1 | 34.6 | redeemers 6,242 B |
| `Exit` | 5 | 2,039,154,504 | 6,822,808 | 20.4 | 41.4 | redeemers 7,901 B |
| `Exit` | 6 | 2,323,317,642 | 7,775,662 | 23.2 | 47.1 | redeemers 9,277 B |
| `Exit` | 7 | 2,636,843,762 | 8,831,912 | 26.4 | 53.5 | redeemers 10,726 B |
| `Exit` | 8 | 2,948,987,611 | 9,874,353 | 29.5 | 59.8 | redeemers 12,246 B |
| `Exit` | 9 | 3,263,699,904 | 10,926,180 | 32.6 | 66.2 | redeemers 13,766 B |
| `Exit` | 10 | 3,576,884,852 | 11,972,844 | 35.8 | 72.6 | redeemers 15,286 B: too large to submit |
| `BalanceExit` | 1 | 591,304,343 | 1,959,166 | 5.9 | 11.9 | exit-leaf proof 5 folds, one escape insert |
| `AcceptProved` | 1 | 406,216,407 | 1,344,969 | 4.1 | 8.2 | one CREDITED record proof per deposit; redeemer 869 B |
| `AcceptProved` | 4 | 1,438,780,130 | 4,024,031 | 14.4 | 24.4 | redeemer 3,458 B |
| `AcceptProved` | 8 | 3,466,500,339 | 8,119,504 | 34.7 | 49.2 | redeemer 6,910 B |
| `EnterExitMode` (b) | 1 | 263,363,047 | 891,922 | 2.6 | 5.4 | exclusion proof of `forced/<id>`, 5 folds |

Without the size bound, execution units alone would allow 14 `Exit` claims (13,960,330 memory, 84.6%); 15 exceed
the memory limit (17,011,177, 103.1%).

## Batch caps

- **`Settle`: 16 claims** (the ADR's target) uses 57% of the memory limit. Execution units are not the binding
  limit; the redeemers (9.5 KB at 16) and the 16 payout outputs share the 16,384-byte transaction.
- **`Exit`: 8 claims.** Memory would allow 14, but each claim carries a state proof (about 1 KB) and a shard
  insert proof in the redeemers, so transaction size binds first: 9 claims need 13.8 KB of redeemers, which leaves
  too little room for 9 payouts, the continuation, the shard output and the inputs. The ADR's estimate of 1–5 claims
  was conservative. Deployments set `maxExitBatch` to 8. A deeper state trie (more folds) lowers it; at the
  measured ~1.1 M memory and ~1.5 KB per claim, re-measure before raising it.
- **`AcceptProved`**: 8 deposits use half the memory limit and 6.9 KB of redeemer. The CPU cost grows faster than
  linearly (34.7% at 8), because every deposit input is a script input whose context grows with the batch; batches of
  8 are a safe default.

## Script sizes

A reference script must fit in one output of a transaction of at most 16,384 bytes. Sizes are the CBOR-wrapped
script bytes, as the ledger stores them.

| Script | Template bytes | Applied bytes |
|---|---:|---:|
| `AssetVaultValidator` | 13,803 | 14,319 (token vault), 14,286 (ADA vault) |
| `AssetShardValidator` | 4,027 | |
| `ExitModeThreadValidator` | 6,987 | |
| `ForcedInboxValidator` | 6,890 | |
| `AssetShardThreadPolicy` | 1,248 | |
| `ExitModeThreadPolicy` | 681 | |

The vault is the largest script. A first draft compiled to 18,630 bytes, which cannot be published as a reference
script. It fits after three changes that keep the rules unchanged: the redeemer is decoded without julc's typed
boundary check (an ill-typed field still fails the script), multi-accumulator loops became explicit recursion, and
the `SweepExit` payout check compares the swept value exactly instead of with a general multi-asset comparison.
`GatewayBudgetTest.scriptSizes` fails if an applied vault reaches 15,500 bytes. About 1,000 bytes of headroom remain
for future rules.
