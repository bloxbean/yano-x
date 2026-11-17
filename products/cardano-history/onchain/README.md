# Cardano History on-chain predicates

Typed Cardano History predicates over the reusable Yano MPF on-chain verifier.
It fixes the Cardano History application ID, MPF profile, canonical component
and snapshot keys, dataset series, and predicate tags, and provides Julc
spending validators that let Cardano scripts consume proven history facts.
See [On-chain consumption](../../../docs/appchain/CARDANO_HISTORY.md#on-chain-consumption)
and the [Cardano History plugin](../runtime/README.md).

## Artifact

`org.yanoproject.x:yano-x-cardano-history-onchain` (on-chain artifact,
released to Maven Central).

## Key contents

Package `org.yanoproject.x.history.onchain`:

- `CardanoHistoryOnChainPredicates` — the frozen keys, schemas, and predicate
  tags applications compile the generic validators with;
- `CardanoHistoryParametersValidator` — named protocol-parameter equality and
  unsigned-range validator; and
- `CardanoHistoryPairValidator` — fact/completeness pair validator whose keys,
  epoch, and predicate operands are script parameters, not redeemer values.

JMT is deliberately excluded: it is off-chain only.

## Dependencies

API dependencies are `:sdk:proof-onchain`, `:state-machines:stdlib-contracts`,
and `:composition:contracts`; Julc stdlib and ledger API are `compileOnly`.
Tests compile and run the validators with the Julc compiler, testkit, and VM.

## Build and test

```bash
./gradlew :products:cardano-history:onchain:test
```

`test` first copies this module's and `:sdk:proof-onchain`'s sources into
`build/generated/julc-test-sources` for Julc compilation. See
[Build and test Yano X](../../../docs/BUILD_AND_TEST.md) for the required
Yano version arguments.
