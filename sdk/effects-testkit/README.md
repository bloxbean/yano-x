# Yano X App-Chain Effects Testkit

Effect and connector conformance harnesses for Yano X effect executors. Connector
test suites use it to prove one black-box contract (identity, routing and receipt,
invalid payloads, idempotent close, cleanup after failed construction, retry and
unknown-acknowledgement behavior, existing-state confirmation or conflict, bounded
blocked calls) and to run an executor inside a real one-node Effect Runtime.

## Artifact

`org.yanoproject.x:yano-x-effects-testkit` (`test-library`; use it in test scope).

## Key contents

Package `org.yanoproject.x.testkit.effects`:

- `EffectExecutorConformance.tests(ExecutorConformanceSpec)`: the reusable dynamic
  test suite; expose it from one `@TestFactory`.
- `ExecutorConformanceSpec`, `ExecutorFixture`, `ExternalProbe`: the
  connector-specific adapters the suite drives (fresh executor/provider fixtures
  and bounded facts about fake or external provider calls).
- `PayloadCase`, `IdempotencyModel`: malformed or boundary payloads with their
  expected `ConnectorErrorCode`, and the mutation guarantee a connector declares.
- `EffectRuntimeHarness`: finalizes app messages through a real `AppChainSubsystem`,
  emits one effect per message and exposes the runtime's status and metrics.
  The caller owns and cleans the storage directory.
- `EffectRuntimeSnapshotAssertions`, `CapturedLogObservation`,
  `FailedConstructionObservation`: assertion helpers.

```java
@TestFactory
Stream<DynamicNode> conformance() {
    return EffectExecutorConformance.tests(new MyConnectorSpec());
}
```

No provider SDK enters this module. The Kafka, S3 object store and IPFS connectors
use it; see [optional connectors](../../docs/appchain/OPTIONAL_CONNECTORS.md).

## Dependencies / boundaries

`api`: [`sdk/appchain-testkit`](../appchain-testkit/README.md) (which brings
`framework/cardano` and Yano's plugin catalog) and
[`sdk/integration-contracts`](../integration-contracts/README.md). Production
modules must not depend on it.

## Build & test

```bash
./gradlew :sdk:effects-testkit:test
```

Add the Yano inputs from [Build and test](../../docs/BUILD_AND_TEST.md) when the
pinned Yano version is local or staged.
