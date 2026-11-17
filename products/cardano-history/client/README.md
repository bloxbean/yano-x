# Cardano History client

Typed Java client for the Cardano History product: explicit-chain, bounded
queries against its domain API, exact-height proof pairs from the generic
root-fixed proof surface, and a standalone verifier for exported proof
bundles. The server-side product is described in the
[Cardano History plugin](../runtime/README.md) README; usage and trust labels
are in [Cardano History](../../../docs/appchain/CARDANO_HISTORY.md).

## Artifact

`org.yanoproject.x:yano-x-cardano-history-client` (library; not released to
Maven Central). The [CLI](../cli/README.md) embeds it.

## Key contents

Package `org.yanoproject.x.history.client`:

- `CardanoHistoryClient` — `status`, `epochs`, `confirmedAnchor`, and
  `parameters`, `stake`, `drep`, and `proposal` queries; built with an
  explicit node API URL and chain ID;
- `CardanoHistoryProofClient` — fetches exact-height typed proof pairs for a
  composed Cardano History chain;
- `CardanoHistoryProofBundle` with `CardanoHistoryPortableParametersProof` and
  `CardanoHistoryPortableStakeProof` — the portable proof bundle; and
- `CardanoHistoryPortableProofVerifier` and `CardanoHistoryTrustedRoot` —
  offline bundle verification against a caller-supplied trusted root.

## Dependencies

API dependencies are `:sdk:client` and `:state-machines:stdlib-contracts`; it
uses Jackson. It does not depend on the runtime plugin.

## Build and test

```bash
./gradlew :products:cardano-history:client:test
```

See [Build and test Yano X](../../../docs/BUILD_AND_TEST.md) for the
required Yano version arguments. Design:
[ADR app-layer/028](../../../adr/app-layer/028-l1-historical-ledger-state-attestation-chain.md)
and
[ADR app-layer/036](../../../adr/app-layer/036-cardano-history-proof-ux-and-protocol-parameter-projections.md).
