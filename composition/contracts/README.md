# App-chain composite contracts

This lightweight artifact contains the public, no-SPI contracts required to
submit to or verify a composite chain without loading Yano state-machine or
plugin implementations. It intentionally contributes no plugin manifest or
`ServiceLoader` entry.

Public v1 surfaces are:

- `CompositeCommitmentV1` — profile marker key, 64 KiB profile bound,
  domain-separated profile digest, and component-local to physical MPF key
  mapping;
- `AggregateQueryCodecV1` and `AggregateQueryLimitsV1` — the one canonical,
  bounded `composite/aggregate-v1` request/response wire used by both clients
  and runtime; and
- profile epoch/governance records and verification helpers used by any
  governed composite product;
- experimental ADR-031.1 `BindingIrV1`, `BindingSourceV1`, and
  `BindingExpressionV1` canonical authoring contracts, plus `BindingReceiptV1`
  for authenticated cascade explanations. The contracts contain no CEL engine
  or plugin implementation. See the [binding guide](../../docs/appchain/DECLARATIVE_BINDINGS.md).

The binding wire shapes and frozen vectors ship in
`cddl/declarative-bindings-v1.cddl` and its adjacent properties resource.
`BindingPublishedVectorsTest` pins codecs to those bytes;
`scripts/verify-cddl.sh` additionally checks the published schemas with the
external CDDL validator. Shape conformance alone does not establish executable
types, authorization, acyclicity, or finality.

Example aggregate request:

```java
byte[] request = AggregateQueryCodecV1.encodeRequest(List.of(
        new AggregateQueryCodecV1.Subquery(
                "evidence", "get", evidenceGetRequest)),
        AggregateQueryLimitsV1.DEFAULT);
```

Example proof key and profile trust root:

```java
byte[] markerKey = CompositeCommitmentV1.profileMarkerKey();
byte[] profileDigest = CompositeCommitmentV1.profileDigest(canonicalProfile);
byte[] recordProofKey = CompositeCommitmentV1.componentKey(
        "evidence", evidenceRecordKey);
```

The digest proves which canonical profile was committed; it does not by itself
establish that the root is finalized. Bind the proof root and height to a
trusted threshold finality certificate or Cardano L1 anchor.

```bash
./gradlew :composition:contracts:check
```
