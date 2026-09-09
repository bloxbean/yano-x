# Yano App Chains — Start Here

This is the task-oriented entry point for Yano app chains. It is designed for
two kinds of reader:

- **New to app chains:** start a local three-member chain, submit useful data,
  and see what is finalized before learning the internals.
- **Experienced distributed-systems or blockchain developer:** jump directly
  to state proofs, L1 anchoring, deterministic composition, effects, plugins,
  governance, and operational boundaries.

Start with the [deployment guide](deployment/README.md) for released archives,
Studio configuration, local chain additions, and VM operations.

The tutorials use the current source tree. Yano is still pre-release; use local
devnet or a Cardano test network, disposable application data, and non-production
credentials unless a guide explicitly says otherwise.

## Choose your first outcome

| I want to… | Start with | Coding required? |
|---|---|---:|
| See three members finalize the same events | [Your first app chain](tutorials/01-first-app-chain.md) | No |
| Use or customize the built-in append-only event log | [`ordered-log` reference](../core-host.md) | No, unless adding business rules |
| Maintain a provable owner-controlled registry | [`kv-registry` reference](state-machines/kv-registry.md) | No |
| Maintain multiple proof-oriented collections with optional value validation | [Authenticated-map guide](state-machines/authenticated-map.md) | Configuration; plugin only for custom rules |
| Collect member approvals and optionally trigger an action | [`approvals` reference](state-machines/approvals.md) | Configuration + typed commands |
| Maintain a document-hash trail per product or case | [`doc-trail` reference](state-machines/doc-trail.md) | Configuration + typed commands |
| Select a stock ledger/workflow capability | [Stock state-machine cookbook](tutorials/03-stock-state-machines.md) | Configuration + typed commands |
| Publish immutable evidence to object storage/IPFS and notify Kafka | [Evidence publication](tutorials/04-evidence-publication.md) | No for the demo; optional connector plugins in deployments |
| Approve arbitrary payload hashes using application-defined roles | [`role-approvals` reference](state-machines/role-approvals.md) | Configuration + actor integration |
| Require manufacturers, auditors, and regulators to sign evidence by role | [Domain-role approvals](tutorials/05-domain-role-approvals.md) | No for the stock scenario |
| Call an ERP/API after a finalized decision | [Webhook effects](tutorials/06-webhook-effects.md) | Configuration; emission is stock or plugin logic |
| Learn certified external reports and multi-source aggregation | [ADA/USD observation reference](ada-usd-observation-reference.md) | Java reference; preview, synthetic sources |
| Link payment, certified shipment, release, and settlement | [Shipment observation reference](shipment-observation-reference.md) | Preview; bounded signed Merkle receipts |
| Understand and verify Cardano settlement | [Anchors and independent verification](tutorials/07-anchors-and-verification.md) | No |
| Build or independently verify portable message and typed state proofs | [Proof Lab guide](PROOF_LAB.md) | No for stock subjects; Java for custom subjects |
| Implement new business rules without forking Yano | [Plugins and composites](tutorials/08-plugins-and-composites.md) | Small Java plugin |
| Reuse stock transitions and verify portable proofs | [Composable state and proofs](COMPOSABLE_STATE_AND_PROOFS.md) | Java/configuration |
| Archive and prove large immutable period datasets | [Authenticated snapshots](AUTHENTICATED_SNAPSHOTS.md) | Configuration/API |
| Query and prove historical Cardano parameters, stake, and governance | [Cardano History product](CARDANO_HISTORY.md) | Plugin configuration/CLI |
| Attest a document digest and hand out a portable, offline-verifiable certificate | [Attest product](ATTEST.md) | No; CLI or browser UI |
| Propose, approve, and release through the role workflow from a browser, with in-browser actor signing and proof-bound records | [Evidence Desk](EVIDENCE_DESK.md) | No; browser UI |
| Run a trust and status registry: proof-bound status answers, Bitstring Status Lists, TRQP answers, offline verification | [Trust Registry](TRUST_REGISTRY.md) | No; launcher, CLI, service, browser UI |
| Browse, search, and prove the rows of stock app chains: a verify-on-ingest index, entity trails with a state check, a content archiver, offline-verifiable row bundles | [Verifiable Explorer](EXPLORER.md) | No; CLI, service, browser UI |
| Run a Digital Product Passport prototype: governed product records, versions, claims, events, a certification round, a public portal, an operator gateway, offline-verifiable passports | [DPP Starter](DPP_STARTER.md) | No; launcher, CLI, portal, gateway, browser UI (prototype) |
| Run a consortium data feed: source-signed observations, rounds aggregated by a deterministic rule every verifier recomputes, round records closed by independent publishers, a candidate Cardano datum, a public portal, a signing gateway, offline-verifiable round bundles | [Attestation Feed](ATTESTATION_FEED.md) | No; launcher, CLI, portal, gateway, browser UI (experimental starter) |
| Demonstrate the DPP starter end to end: the cast and where its roles come from, how keys are held, the lifecycle in the browser, and what the proof says | [DPP demonstration](DPP_DEMO.md) | No; launcher, gateway, browser UI |
| Take any of these products from a demonstration to a deployment: the genesis ceremony, members and threshold, where keys live, what each service may reach, anchoring, and what the demo does that production must not | [Production deployment](PRODUCTION_DEPLOYMENT.md) | Operations and governance work |
| Prepare a pilot deployment | [From demo to pilot](tutorials/09-from-demo-to-pilot.md) | Operations work |
| Trace an experimental EUTxO ZK round trip on devnet | [EUTxO ZK devnet walkthrough](../../ledgers/eutxo-zk/DEVNET_WALKTHROUGH.md) | Cardano builder integration |

If you are unsure, complete tutorials 1, 2, 4, and 5 in that order. They show
the progression from a replicated log to proofs, external actions, and
business-role authorization.

## What ships out of the box

| Availability | Capabilities |
|---|---|
| Bundled deterministic state | `ordered-log`, `kv-registry`, `authenticated-map`, `approvals`, `balances`, `doc-trail`, `role-approvals`, evidence registry/profile |
| Bundled external delivery | `webhook.post` effect executor and finalized webhook sink |
| First-party optional | Kafka effect/sink, S3-compatible `object.put`, `ipfs.pin`, `cardano.payment` |
| Experimental optional | ZK gate, anonymous membership and credential registry |
| Reference | Custom JVM plugin workflow and the complete evidence demo environment |

Deterministic state runs identically on every member and contributes to the
state root. External executors run after the relevant finality gate and report
an outcome through the effect system. External execution is at-least-once;
effect outcome incorporation is exactly once. Receivers must honor the supplied
idempotency identity.

“First-party optional” means maintained and tested by Yano X, but not selected
in every deployment. Install the release-matched JVM bundle. Yano X extensions
are not part of Yano's native image. See the exact
[capability catalog](CAPABILITIES.md) and
[connector installation guide](OPTIONAL_CONNECTORS.md).

## The mental model

An app chain has four layers:

```mermaid
flowchart LR
    C[Client command] --> M[Deterministic state machine]
    M --> F[Threshold-finalized app block]
    F --> P[State root and MPF proofs]
    P --> A[Cardano L1 anchor]
    M -. effect intent .-> E[Effect runtime]
    E --> X[Kafka, S3, IPFS, webhook, Cardano]
    X -. acknowledged result .-> M
```

1. A member authenticates and relays a command.
2. The proposer orders commands into an app block.
3. A threshold of members signs the same block and state root.
4. State and effect intents become provable against that root.
5. An optional anchor settles the root on Cardano.
6. Effect executors act outside consensus and report bounded outcomes.

For role-aware workflows, the relay member and the business actor are separate
identities: a node transports a command; an actor signature authorizes its
business meaning.

## Learning tracks

### Beginner: operate first, understand second

1. Use the self-contained devnet. No Cardano funds or external node is needed.
2. Submit through `./yano.sh appchain cluster` rather than constructing wire bytes.
3. Compare tips and roots on all members.
4. Inspect one proof and one intentional no-op/failure.
5. Stop while keeping data, restart, and verify the same state.

### Application developer

1. Choose a stock machine or committed composite profile.
2. Use the Java client or REST API for typed commands and proof queries.
3. Add a small composite plugin when existing components need new ordering or
   terminal transitions.
4. Add a custom state-machine plugin only for genuinely new state or rules.
5. Treat any change to deterministic application semantics as a versioned
   consensus upgrade, not an ordinary rolling code change.

### Platform and security engineer

1. Verify finality certificates and MPF proofs independently.
2. Choose metadata or threshold-script anchoring and define the finality gate.
3. Separate member keys, business-actor keys, API keys, effect credentials, and
   anchor funds.
4. Pin the binary, plugin catalog, committed profile, runtime, and connector
   security profiles across members.
5. Exercise restart, catch-up, restore, executor retry, and root-parity gates.

## Prerequisites used by the tutorials

- Java 25 when building from source.
- Docker Desktop for the complete evidence/connector demo.
- `bash`, `curl`, `jq`, `python3`, and `openssl`.
- From source, follow [the Yano X distribution build](../BUILD_DISTRIBUTIONS.md),
  then run the extracted JVM distribution. The upstream Yano `:app` build is
  not a task in this repository.

The cluster launcher can also use a released Yano tree; see
[`scripts/appchain-cluster/README.md`](../../scripts/appchain-cluster/README.md).

## Reference shelf

Tutorials deliberately stay outcome-focused. Use these references when you
need full detail:

- [10–15 minute overview](../APP_CHAIN_OVERVIEW.md)
- [State-machine references](state-machines/README.md)
- [Release capability and recipe catalog](CAPABILITIES.md)
- [Optional first-party connector installation](OPTIONAL_CONNECTORS.md)
- [`ordered-log` state-machine reference](../core-host.md)
- [`kv-registry` state-machine reference](state-machines/kv-registry.md)
- [Authenticated-map guide](state-machines/authenticated-map.md)
- [Authenticated-map value-validation guide](state-machines/authenticated-map-validation.md)
- [Proof Lab: message, typed-state, imported, and on-chain proofs](PROOF_LAB.md)
- [`approvals` state-machine reference](state-machines/approvals.md)
- [`doc-trail` state-machine reference](state-machines/doc-trail.md)
- [`role-approvals` state-machine reference](state-machines/role-approvals.md)
- [Complete user and configuration guide](../APP_CHAIN_USER_GUIDE.md)
- [Use-case catalogue](../APP_CHAIN_USE_CASES.md)
- [Consensus and state-machine internals](../core-host.md)
- [Profile governance runbook](../APP_CHAIN_PROFILE_GOVERNANCE.md)
- [Domain actors and role-aware approvals](../APP_CHAIN_DOMAIN_ROLES.md)
- [Plugin query and domain API contract](../core-host.md)
- [Release acceptance and schema status](RELEASE_ACCEPTANCE.md)
- [Deployment experience review and design plan (proposal)](DEPLOYMENT_EXPERIENCE_REVIEW_AND_PLAN.md)
- [Canonical open-work tracker](../../adr/app-layer/open_item.md)

## A note on “no code”

No-code means the required state machine, composite, connector, and launcher
already ship with Yano. A real application still sends typed commands and owns
its UI, identity onboarding, key custody, and business data. Configuration
cannot invent arbitrary consensus transitions. New combinations use a small
composite plugin; new domain logic uses a custom state-machine plugin.
