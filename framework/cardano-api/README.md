# Application-ledger Cardano API

Optional Cardano-specific public contracts of the application-ledger framework:
the script-anchor datum ABI, the effect-executor SPI and its node-coupled
context, the anchored-block event and the canonical protocol-parameter codec.
Kept apart from [`framework/api`](../api/README.md) so that a standalone (no-L1)
composition never carries Cardano types it cannot use.

## Artifact

`org.yanoproject.x:yano-x-appchain-cardano-api` (`library`).

## Key contents

Packages under `org.yanoproject.x.framework.cardano.api`:

- `anchor.AnchorDatumV1`: strict codec for the script-anchor datum ABI v1
  (canonical CBOR, UTF-8 chain id without NUL, sorted unique members). The ABI is
  specified in `src/main/cddl/appchain/anchor-v1.cddl`.
- `anchor.AnchorDatumV2`: the same strict codec for the opt-in anchor datum v2
  (ADR-059 framework addition 5): the v1 fields with version 2 and an appended
  `updatedAtMs`. The ABI, including the heartbeat and validity-window rules, is
  specified in `src/main/cddl/appchain/anchor-v2.cddl`.
- `effects.AppEffectExecutorFactory`, `effects.AppChainEffectContext`: the
  `effect-executor` contribution SPI, configured under
  `yano.app-chain.effects.executors.<scheme>.*`.
- `events.AppChainAnchoredEvent`: published when an anchor transaction is seen in
  an applied L1 block.
- `l1view.ProtocolParamsCanonicalCodec`.
- `META-INF/yano/contribution-kinds/org.yanoproject.x.framework.cardano.api.json`:
  declares the `effect-executor` kind. `framework/cardano` binds it; a
  standalone-only composition offers no effect executors.

## Dependencies / boundaries

`api`: `framework/api` and Yano's published `yano-cardano-api`; `implementation`:
cardano-client-lib core, Jackson and CBOR. Implemented by
[`framework/cardano`](../cardano/README.md) and consumed by connectors, the eUTxO
bridge, the Cardano History product and SDK clients. `framework/core` and
`framework/standalone` must not depend on it.

## Build & test

```bash
./gradlew :framework:cardano-api:test
```

`frameworkCheck` includes these tests. Add the Yano inputs from
[Build and test](../../docs/BUILD_AND_TEST.md) when the pinned Yano version is
local or staged.
