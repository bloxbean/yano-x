# Application-ledger framework API

Public contracts of the Yano X application-ledger framework: the app block and
state-machine model, consensus and state-commitment identities, proofs,
effects, observations, topics, snapshots and the plugin SPIs that state
machines, observers, sinks, signers and domain APIs implement. Plugin authors,
SDKs and every other framework module compile against it.

## Artifact

`org.yanoproject.x:yano-x-appchain-api` (`library`).

## Key contents

Packages under `org.yanoproject.x.framework.api`:

- `appchain`: `AppStateMachine`, `TypedAppStateMachine`, `AppStateMachineProvider`,
  `AppBlock`, `AppBlockHeader`, `AppChainConfig`, `AppChainGateway`/`AppChainGateways`,
  `AppChainConsensusProfile`, `FinalityCert`, membership and query types.
- `appchain.transition`: the transition kernel (`TransitionKernel`, `TransitionPlan`,
  `StateMutation`, work budgets) and finalized-message indexes.
- `appchain.state`, `appchain.proof`, `appchain.evidence`, `appchain.snapshot`:
  state-commitment profiles, `StateProof`, proof packages, evidence bundles and
  authenticated snapshots.
- `appchain.effects`, `appchain.observation`, `appchain.l1view`, `appchain.topics`,
  `appchain.authmap`, `appchain.sequencer`, `appchain.signer`, `appchain.sink`,
  `appchain.consensus`, `appchain.codec`, `appchain.schema`, `appchain.crypto`.
- `plugin.domain`: domain API and local read-model SPIs (`DomainApiProvider`,
  `LocalReadModelProvider`, `FinalizedChainView`, `L1TransactionBuilderService`).
- `events`: `AppBlockFinalizedEvent`, `AppMessageReceivedEvent`, `AppChainStalledEvent`.

Resources and specifications:

- `META-INF/yano/contribution-kinds/org.yanoproject.x.framework.api.json`: the X
  contribution kinds (`app-state-machine`, `authenticated-map-validator`,
  `sequencer-mode`, `l1-observer`, `l1-epoch-observer`, `observation-provider`,
  `signer-provider`, `finalized-sink`, `domain-api`, `local-read-model`). Yano has
  no built-in knowledge of these kinds (ADR-064 D3).
- `META-INF/yano/topics/v1/topics.cddl` and the L1-observation golden vectors.
- `src/main/cddl/appchain/*.cddl`: app block, certified consensus, effect record and
  observation wire formats.

## Dependencies / boundaries

Depends on [`sdk/proof-contracts`](../../sdk/proof-contracts/README.md) and Yano's
published `yano-node-api` only (plus CBOR, Jackson and crypto libraries). It must
not depend on the Yano Host API, Yano runtime or any other framework module; every
framework module depends on it. Yano never depends on this artifact (ADR-064 D1).
See [the host boundary](../../docs/core-host.md),
[ADR-057](../../adr/057-application-ledger-framework-extraction.md) and the
[ADR-064 migration notes](../../docs/appchain/MIGRATION-ADR-064.md).

## Build & test

```bash
./gradlew :framework:api:test
./gradlew frameworkCheck
```

Add the Yano inputs from [Build and test](../../docs/BUILD_AND_TEST.md) when the
pinned Yano version is local or staged; see also the
[framework gate](../../qa/framework/README.md). Plugin authoring is covered by the
[plugins tutorial](../../docs/appchain/tutorials/08-plugins-and-composites.md).
