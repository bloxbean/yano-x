# ADR-040 — Separately deployable product-specific user interfaces

- **Status:** Proposed
- **Date:** 2026-08-25
- **Owner:** Yano X
- **Scope:** Ownership, packaging, discovery, configuration, security, and deployment of browser
  applications for Yano X products. This ADR does not change consensus, state encoding, plugin
  activation, or the Yano host API.
- **Related:** [ADR-030 repository split](refactoring/030-repository-split-yano-x-execution-plan.md),
  [ADR-011 plugin architecture](app-layer/011-plugin-architecture.md),
  [ADR-035 Cardano History product console](app-layer/035-cardano-history-product-console-and-distribution.md),
  [ADR-037 generic proof lab](app-layer/037-generic-appchain-proof-lab-and-subject-discovery.md),
  [ADR-UTXO-004 EUTxO explorer](app-layer/utxo/004-capability-gated-eutxo-console-explorer.md),
  [ADR-UTXO-008 bridge showcase](app-layer/utxo/008-showcase-bridge-chain-and-user-driven-l1-deposits.md),
  and [Yano console boundary](../docs/console-ui.md)

## 1. Context

Yano owns the generic node and app-chain host. Yano X owns optional state machines, capabilities,
products, connectors, effects, examples, and the combined JVM distribution. Dependency direction is
strictly `yano-x -> yano`.

The repository split temporarily left all of `console-ui` in Yano, including specialized EUTxO and
Cardano History pages. Those pages do not create a Java dependency on Yano X, because they call
Yano's generic and plugin-domain HTTP APIs. They nevertheless give the upstream Yano repository
knowledge of downstream product identities, workflows, routes, and release timing.

The Preprod showcase exposed a concrete failure of that arrangement. The EUTxO bridge moved from a
host-owned route to the manifested Yano X domain API, while the retained Yano console continued to
call the removed route. The bridge remained healthy, but the console interpreted the `404` as an
unavailable bridge and hid its complete CIP-30 workflow. This is release coupling across the
repository boundary even though no Java classpath boundary was crossed.

Yano X will add other products with experiences that do not belong in a generic node console. A
document workflow, historical-ledger explorer, evidence application, and EUTxO payment system need
different terminology, navigation, validation, and task flows. Adding every workflow to Yano would
turn the minimal host into a downstream product catalog. Packaging executable web assets inside
plugin JARs and injecting them into the trusted Yano console would instead create an unacceptable
browser supply-chain and compatibility boundary.

## 2. Decision summary

Yano X will own separately built and separately deployable browser applications for coherent
products. The first application is the EUTxO product UI under `products/eutxo/ui`.

The decision has these rules:

1. **Yano's embedded console remains generic.** It owns node, L1, app-chain, plugin, anchoring,
   proof, health, metrics, and generic operations views. New product workflows do not go into Yano.
2. **A UI represents a product, not necessarily one plugin.** The EUTxO UI may coordinate the
   ledger, indexer, Cardano bridge, settlement, and proof capabilities. Yano X will not create a
   micro-frontend for every individual contribution JAR.
3. **Product UIs use public contracts only.** They call the normal Yano HTTP APIs and manifested
   Yano X domain APIs. They do not import runtime implementations, read node storage, or receive a
   privileged in-process browser bridge.
4. **Connection is runtime-configured.** One built UI artifact can connect to Devnet, Preprod, or
   another compatible deployment without rebuilding the frontend.
5. **Eligibility is discovered.** A UI selects chains using chain status, capability manifests,
   plugin-domain availability, network identity, and compatible API versions. A display name or
   configured default may guide selection but never replaces compatibility checks.
6. **Product UI assets are not plugin assets.** Server-side bundle JARs contain no executable
   frontend code, and Yano does not load JavaScript from plugins into its console.
7. **The existing specialized Yano pages are transitional.** They may receive bounded repairs while
   replacement UIs are developed. Removing them requires a coordinated Yano decision and parity
   evidence; this ADR does not authorize modifying the sibling repository.

## 3. Ownership and module layout

The first source layout is:

```text
products/
└── eutxo/
    └── ui/                       EUTxO product browser application
```

`products/eutxo/ui` is intentionally preferred over `products/eutxo/eutxo-ui`: the parent already
provides the product name. It may consume released, browser-safe TypeScript contracts or generated
OpenAPI/JSON contracts, but it must not copy Java transition logic or Cardano transaction-building
rules into TypeScript.

The existing server, bridge, client, indexer, and test modules remain under `ledgers/eutxo`. The new
`products/eutxo` directory is the user-facing product assembly; it does not duplicate or relocate the
ledger implementation. Later product UIs follow their owning product, for example
`products/cardano-history/ui` or `products/evidence/ui`.

When a second product UI proves which concerns are genuinely shared, Yano X may extract a small
frontend package such as:

```text
ui/
└── product-ui-kit/               connection, discovery, auth, errors, and shared primitives
```

The shared package must remain infrastructure rather than a second monolithic console. Product
navigation, terminology, business validation, and workflows stay with each product. The first UI
must not invent a speculative framework for every future frontend.

### 3.1 Dependency direction

```text
Yano X product UI
        |
        +---- Yano generic HTTP APIs
        |
        `---- Yano X plugin domain APIs through Yano's generic gateway
```

Yano builds, tests, and releases without the product UI. Yano X may build against an exact released
Yano API contract, but neither the Yano build nor its console may read generated assets, source
paths, tasks, or metadata from Yano X.

## 4. Runtime connection contract

The UI is configured when served, not when compiled. Its bounded runtime document has the following
shape; exact property names will be fixed by the implementation schema:

```json
{
  "schemaVersion": 1,
  "productId": "eutxo",
  "endpoints": [
    {
      "id": "primary",
      "nodeUrl": "https://node.example.com",
      "apiPrefix": "/api/v1"
    }
  ],
  "defaultChainId": "payment-chain-settlement",
  "expectedNetwork": "preprod",
  "allowEndpointOverride": false
}
```

`defaultChainId` is a usability hint. The UI must still verify the selected chain's state-machine,
profile/capability manifest, bridge availability, network, and required route versions. This lets a
deployer rename chains without recompiling the UI and prevents a familiar chain name from being
treated as a trust decision.

The runtime configuration contains no API key, wallet credential, seed, signing material, or effect
secret. It is served as a same-origin immutable asset or injected by a reviewed deployment adapter.
Query-string endpoint overrides are disabled by default because a shared URL must not silently
redirect a user and wallet to an untrusted node.

When `allowEndpointOverride` is true, the UI presents an explicit connection screen where the user
may type the HTTPS origin of any compatible Yano installation. The standalone EUTxO artifact uses
this mode by default, so it can operate against an existing remote VM, a provider-managed cluster,
or a local node without rebuilding. The UI fetches the live node identity and compatible chain
catalog before showing product operations. This visible, verified action is distinct from accepting
an endpoint silently from a query parameter.

### 4.1 Endpoint and failover behavior

A configuration may advertise multiple API nodes. Before treating them as interchangeable, the UI
must compare network ID, chain ID, genesis ID, commitment profile, format fingerprint, and effective
capability/API versions. Read requests may fail over only between matching nodes.

A wallet or state-changing workflow is pinned to one endpoint from build through submission and
confirmation. The UI does not build an unsigned transaction on one node and silently submit or
confirm it through an identity-mismatched node.

## 5. Discovery and API compatibility

The EUTxO UI discovers compatible chains using generic host surfaces and then uses the plugin domain
gateway:

```text
/api/v1/plugins/{bundle-id}/{manifested-route}
```

It must not call removed host-owned EUTxO routes or infer support only from an HTTP `404`. Discovery
must distinguish at least:

- plugin or route not installed;
- chain exists but does not enable the capability;
- incompatible API/contract version;
- authorization required;
- node temporarily unavailable; and
- malformed or identity-mismatched response.

Product route contracts require stable bundle IDs and explicit API versions before the UI becomes a
release artifact. A product UI release records the supported Yano API range and product-domain API
range. Distribution gates verify that the packaged UI, server bundles, and Yano host identity form a
compatible set.

## 6. EUTxO product UI version one

The first product UI provides one coherent user journey for a bridge-and-settlement EUTxO chain:

1. discover and select a compatible deployment and chain;
2. connect a CIP-30 wallet and verify its Cardano network;
3. show the L1 wallet balance, bridge vault facts, limits, and withdrawal state;
4. request an unsigned L1 deposit transaction from the bridge domain API;
5. show the amount, destination, fee, validity interval, and network before wallet approval;
6. ask the wallet to sign and submit through the supported bridge/L1 transaction contract;
7. track L1 confirmation and the corresponding L2 deposit;
8. display L2 UTxOs, balance, transaction history, roots, and relevant proofs;
9. build and submit L2 transfers; and
10. initiate a withdrawal and track settlement through L1 payment confirmation.

The node and Yano X Java libraries remain the owners of Cardano transaction construction, datum
encoding, deterministic state transitions, and proof semantics. The browser renders typed results,
collects bounded user intent, and coordinates wallet signing. It never reconstructs consensus or
bridge rules independently.

The initial showcase may configure `payment-chain-settlement` as its default, but neither source code
nor the reusable UI artifact hard-codes that chain as the only valid target.

## 7. Browser and wallet security

The following requirements are mandatory:

- CIP-30 interaction runs only in a secure browser context, except an explicit local-development
  allowance for loopback.
- The UI verifies the wallet network against both runtime configuration and live node identity
  before requesting a signature.
- The confirmation view shows human-readable value, destination, network, fee, and action type. The
  user is never asked to sign unexplained CBOR.
- Mnemonics, private keys, wallet seeds, and signing keys never enter the node, runtime
  configuration, browser logs, analytics, URLs, or deployment manifests.
- API credentials are scoped to the selected origin and are not placed in query parameters. A
  future persistent-credential option requires a separate threat review; memory-only is the
  default.
- Content Security Policy denies unneeded origins and executable content. Frontend dependencies are
  lockfile-pinned and included in the UI SBOM and provenance.
- Domain API responses are treated as untrusted network input and validated against bounded schemas.
- The UI does not execute HTML, scripts, styles, URLs, or expressions supplied by a plugin manifest
  or domain response.

## 8. Deployment model

The product UI is a versioned static artifact distinct from the Yano X JVM/plugin artifacts. The
Yano X distribution may include it for convenience, but installing the server bundles does not
implicitly expose a browser application.

The recommended production topology is same-origin:

```text
https://eutxo.example.com/         product UI static assets
https://eutxo.example.com/api/     reverse proxy to a selected Yano API node
```

Same-origin hosting avoids broad CORS rules and simplifies CSP, credential scoping, and API routing.
A standalone UI may connect directly to an HTTPS Yano node URL when that node explicitly allow-lists
the UI origin. Wildcard credentialed CORS is prohibited.

ADR-039 deployment automation may later accept an optional product-UI role that:

- installs a checksum-pinned UI artifact;
- writes its non-secret runtime configuration;
- configures the HTTPS gateway and API upstream;
- applies CSP, cache, compression, and response-header policy; and
- verifies UI, API, chain, network, and plugin compatibility after deployment.

The role may share a VM with an API node for a showcase or run on a dedicated host/CDN. Product UI
placement does not affect validator membership or consensus topology.

## 9. Relationship to the Yano console

The generic Yano console remains useful even when no product UI is installed. It must continue to
operate a Yano node and inspect an unknown third-party app chain without downstream product code.

During transition:

1. existing specialized pages in Yano may be repaired against stable public APIs;
2. new EUTxO workflow functionality is implemented in the Yano X product UI;
3. the product UI must reach packaged-distribution and live-cluster parity before removal is
   proposed; and
4. removal or simplification of Yano pages is performed in Yano under a coordinated decision, with
   links or deployment metadata remaining data-only.

The target state is that Yano does not need a source change when Yano X introduces or revises a
product workflow. This ADR does not reintroduce the superseded `ui-extension` contribution or permit
plugin bundles to inject executable assets into the main console.

## 10. Rejected alternatives

### 10.1 Continue adding specialized pages to Yano

Rejected because it reverses product knowledge into the upstream host, couples two release cycles,
and repeats the bridge-route regression for every new product.

### 10.2 Load frontend assets from plugin JARs

Rejected because arbitrary plugin JavaScript would execute near operator credentials and wallet
providers, complicate CSP and isolation, and create a browser compatibility contract inside the JVM
plugin lifecycle.

### 10.3 Create one UI for every plugin bundle

Rejected because user workflows commonly span several capabilities. Ledger, bridge, indexer,
settlement, and proof plugins are implementation components of one EUTxO experience, not five
separate applications.

### 10.4 Create one new monolithic Yano X console

Rejected as the default because it would reproduce the growth and release coupling of the current
console at a different repository path. Shared infrastructure may be extracted, but products remain
independently buildable and deployable.

### 10.5 Build the API endpoint into the frontend artifact

Rejected because it creates one artifact per environment, complicates promotion, and prevents a
checksum-identical UI from moving from development to Preprod and production.

## 11. Consequences

### Positive

- Product knowledge follows product ownership and the repository dependency direction.
- A UI can evolve and release with its Yano X APIs without rebuilding the Yano host console.
- One UI artifact can be promoted across deployments through runtime configuration.
- The EUTxO workflow can be cohesive without making every low-level plugin independently visible to
  an end user.
- Arbitrary plugin code remains outside the trusted Yano console and wallet context.

### Costs and risks

- Operators may deploy an additional static artifact and hostname or reverse-proxy route.
- Product UIs need explicit compatibility metadata and cross-version contract tests.
- Authentication, connection, discovery, styling, and error behavior could diverge until common
  needs are extracted from at least two products.
- During migration, the generic console and product UI may temporarily overlap.
- A separately hosted UI needs carefully bounded CORS and CSP configuration.

## 12. Implementation plan and acceptance

### PUI-0 — Contract and skeleton

- Add `products/eutxo/ui` as an independently buildable frontend module.
- Define and validate the runtime configuration schema.
- Pin the frontend toolchain and dependency lock; add license, SBOM, and provenance output.
- Add the UI artifact to `config/artifacts-v1.json` only when its publication identity is fixed.

### PUI-1 — Connection and discovery

- Implement endpoint configuration, chain discovery, compatibility checks, and identity display.
- Test absent plugins, disabled bridge, wrong network, unauthorized access, stale API versions,
  mismatched failover nodes, and unavailable nodes.
- Keep API credentials out of URLs, static configuration, and logs.

### PUI-2 — EUTxO read experience

- Implement chain overview, L2 UTxOs, balances, transaction history, bridge status, settlement
  status, and proof links through public APIs.
- Verify bounded decoding and rendering against packaged server contracts.

### PUI-3 — CIP-30 lifecycle

- Implement wallet discovery/connect, deposit build/sign/submit/confirm, L2 transfer, withdrawal,
  settlement tracking, cancellation, and actionable failures.
- Test wrong-network refusal, wallet rejection, stale UTxOs, API timeouts, reload/resume, duplicate
  submission, and transaction confirmation.
- Never use real public-network funds in automated tests. Fresh Preprod transactions require the
  explicit authorization already required by repository policy.

### PUI-4 — Distribution and deployment

- Produce a deterministic static archive and verify it against the exact Yano/Yano X compatibility
  set used by the combined distribution.
- Add optional ADR-039 deployment rendering for same-origin hosting and direct-node CORS mode.
- Smoke-test through HTTPS and a real CIP-30 wallet in a controlled browser session.

### PUI-5 — Console transition

- Demonstrate feature and operational parity on a real multi-node cluster.
- Record which generic views remain in Yano and which specialized EUTxO views can be retired.
- Propose the corresponding Yano change separately; do not remove or fork the retained console as a
  side effect of Yano X implementation.

The decision is complete when the same checksum-pinned EUTxO UI artifact can connect through runtime
configuration to two compatible deployments, complete the authorized deposit/transfer/withdrawal
journey, reject an incompatible deployment before signing, and leave Yano free of any new EUTxO UI
source dependency.
