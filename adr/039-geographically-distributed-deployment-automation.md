# ADR-039 — Provider-neutral deployment automation for cloud and existing VMs

- **Status:** Accepted — offline compiler and lifecycle implementation complete; live-provider qualification pending
- **Amended 2026-09-13:** the imported artifact is the Yano X JVM ZIP
  (`yano-x-jvm-<version>.zip`), which carries the showcase profile under
  `examples/showcase/`; the separate showcase ZIP named below is no longer built.
  Nodes run from `/opt/yano/home`, which pairs the release runtime with the
  showcase configuration.
- **Date:** 2026-08-23
- **Owner:** Yano X
- **Scope:** Declarative provisioning and operation of a new geographically distributed,
  JVM-based Yano app-chain cluster on Contabo, Hetzner Cloud, DigitalOcean, existing Linux VMs, or
  a mixture of them over public networking. This ADR does not change Yano consensus/runtime behavior.
- **Related:** [ADR-030 repository split](refactoring/030-repository-split-yano-x-execution-plan.md),
  [ADR-011 plugin architecture](app-layer/011-plugin-architecture.md),
  [ADR-016 consensus profile](https://github.com/bloxbean/yano/blob/main/adr/app-layer/016-authenticated-appchain-consensus-profile-and-typed-runtime-limits.md),
  [ADR-NET-008F relay discovery](https://github.com/bloxbean/yano/blob/main/adr/network/008f-relay-discovery-topology-and-ledger-peers.md),
  [ADR-NET-008G relay risks](https://github.com/bloxbean/yano/blob/main/adr/network/008g-relay-production-hardening-risks.md),
  and [from demo to pilot](../docs/appchain/tutorials/09-from-demo-to-pilot.md)

## 1. Decision summary

Yano X owns a provider-neutral deployment bundle and `yano-x-deploy` CLI that compile a reviewed
deployment manifest into immutable deployment inputs. Version one supports the version-pinned
`contabo/contabo`, `hetznercloud/hcloud`, and `digitalocean/digitalocean` OpenTofu providers plus an
`existing` adapter for externally managed VMs. Machine configuration is applied through one common
Ansible path after cloud provisioning or immediately for existing hosts.

Version one will:

1. provision public-addressed instances on any supported cloud and/or adopt operator-supplied VMs;
2. attach provider firewalls to cloud nodes and configure a second default-deny host firewall on all nodes;
3. import a locally built, contract-verified Yano showcase ZIP, pin its provenance and SHA-256,
   and push that exact archive to every node over the configuration channel;
4. install one member identity per validator without putting private material in OpenTofu state,
   cloud-init, generated manifests, logs, or command arguments;
5. render byte-identical consensus configuration and distinct node-local overlays from a new
   locked multi-chain deployment profile derived from the packaged showcase catalog;
6. start a permissioned five-member app-chain cluster only after every member passes identity,
   plugin-catalog, configuration, storage-layout, and network preflight checks;
7. expose selected APIs through HTTPS, API authentication, source allow-lists, and rate limits;
8. optionally provision DNS records and a small Prometheus-based monitoring plane through separate
   adapters; and
9. make replacement and destruction fail closed by default; and
10. keep existing VMs externally managed: apply may configure them but never creates, reinstalls,
    snapshots, replaces, or destroys them.

The initial application profile targets Cardano Preprod by default, while `spec.l1.network` remains
an explicit input so later deployments can select another supported Cardano network. It activates
the curated light-showcase state machines and their safe demonstration capabilities. Kafka, S3, and
IPFS effect/connector bundles are outside the profile and no corresponding services, ports, or
credentials are provisioned.

The deployment schema will not enumerate provider regions, products, sizes, or image IDs. Discovery and
preflight will query the current provider/API for the authenticated account, and the generated lock
will pin the resolved identifiers. The five region names in the initiating requirement are examples,
not a permanent application enum.

WireGuard and other private overlays are not implemented in version one. The compiled node inventory
will nevertheless distinguish public, transport, and advertised addresses so a later WireGuard
adapter can replace transport addresses without changing instance provisioning, consensus inputs,
or node roles.

## 2. Repository and runtime findings

### 2.1 Ownership boundary

The repository split makes Yano X the correct owner for this deployment product:

- Yano owns the Cardano node, minimal app-chain host, N2N transport, consensus/finality, storage,
  generic operations, and the ordinary JVM distribution input.
- Yano X owns JVM plugins, products, devtools, examples, the combined JVM distribution, and
  downstream deployment tooling.
- Dependency direction remains `yano-x -> yano`. The deployment project consumes an already-built,
  checksum-pinned archive. Until Yano X has a release, a separate operator or CI build may produce
  that archive from exact Yano Maven/JVM-ZIP inputs. Infrastructure `plan` and `apply` must not
  invoke sibling Yano Gradle tasks or read either source checkout.

The automation is tooling and infrastructure, not a runtime plugin. It belongs under a Yano X
deployment/tooling directory and must not cross `PluginProviderRegistry` or introduce host product
switches.

### 2.2 Existing capabilities that can be reused

| Existing capability | Current contract | Reuse in this decision |
|---|---|---|
| App-chain project model | `appchain.yaml`, `appchain.lock`, rendered shared/node YAML, member keys, node hosts, topology, and release catalog digests | Reuse its validation and locking model; the 13-chain pilot needs a new bounded multi-chain profile contract |
| GitOps exporter | Deterministic Helm/Kustomize output with secret references and `gitops.lock` | Reuse renderer/lock concepts; do not deploy Kubernetes on bare Contabo VPSs merely because this exporter exists |
| Single-host cluster launcher | N-node launch, identity markers, key checks, retained-state protection, root parity, join/catch-up, and app-chain/L1 store separation | Port its invariants and tests; do not distribute or remotely invoke the 3,651-line launcher |
| Combined JVM distribution | `yano-x-jvm-<version>.zip`, verified against an exact ordinary Yano JVM ZIP and Maven identity | Runtime base embedded by the showcase archive |
| Packaged showcase | `yano-showcase-<version>.zip`, distribution contract, light-profile config, 13-chain catalog, demo CLI, and the combined Yano X JVM runtime | Initial pre-release deployment artifact, after local build/import/provenance verification |
| Yano Docker image | Artifact-first `bloxbean/yano:<version>-jvm` image and single-node Compose bundle | Core-only image is insufficient for Yano X plugins; a compatible Yano X image needs its own contract |
| Yano X Compose scaffold | Three containers with demo keys and a single chain | Historical/local scaffold only; it is not safe public infrastructure |
| Runtime health and metrics | `/q/health/ready`, `/q/health/group/appchain`, `/q/metrics`, per-chain identity/status/proof endpoints | Deployment gates and monitoring inputs |
| API authentication | Full keys, topic-scoped submit keys, and a separate snapshot-admin realm | Required behind the HTTPS gateway; not a substitute for network controls |
| Relay discovery | Public L1 relay advertisement and peer sharing | Optional L1 bootstrap role only; not app-chain membership discovery |

### 2.3 Gaps and unsafe assumptions

The requested deployment does not map directly onto the current implementation:

1. **There is no first-class app-chain observer node.** `AppChainConfig` requires a private member
   seed, `AppChainEngine` requires that signer's public key in the member group, app messages are
   admitted from members, and app-chain peers are statically configured. The open item
   `CON-011` already tracks a read-only observer role. Version one therefore cannot claim that an
   arbitrary external observer joins and verifies the app chain through a public bootstrap node.
2. **L1 bootstrap is not app-chain bootstrap.** Yano relay auto-discovery and peer sharing concern
   Cardano N2N peers. They may help external Yano L1 relays discover the public Cardano topology,
   but they do not enroll a validator or read-only app-chain replica.
3. **Public relay maturity remains qualified.** ADR-NET-008G explicitly avoids claiming mature
   production public-relay parity. A Preprod pilot may exercise public relay behavior, but the
   automation must surface the remaining diagnostics/discovery limitations.
4. **The current app-chain launcher is single-host.** It assumes local processes, local ports,
   loopback APIs, and shared orchestration state. Generating remote shell commands around it would
   preserve the wrong lifecycle and failure-domain model.
5. **The core Yano image is not a Yano X image.** Loading the required dependency-complete Yano X
   plugin bundles is part of the runtime identity. A bare `bloxbean/yano:*` image cannot silently be
   treated as the batteries-included Yano X product.
6. **There is no Yano X release or released Yano X OCI image today.** Version one therefore accepts
   a locally or CI-built showcase ZIP only through an explicit artifact-import step. Import verifies
   the showcase distribution contract, embedded Yano/Yano X identities, plugin manifests, bundle
   checksums, SBOM, source provenance, and whole-archive SHA-256. OCI mode remains later work and
   must be built from an already-verified archive, never from a Dockerfile that clones `main`.
7. **The API is not ready for anonymous adversarial submission.** Authentication is disabled by
   default, API-key scoping does not replace mTLS/OIDC, and the current open-items list retains API,
   audit, and production hardening work. Limited testers require authenticated, rate-limited,
   allow-listed access. `0.0.0.0/0` submit exposure is rejected in version one.
8. **Public N2N is authenticated but not a confidentiality channel.** Application messages,
   proposals, votes, and certificates have cryptographic identity/integrity checks, but public TCP
   transport does not provide WireGuard-like traffic confidentiality or hide metadata.
9. **Five continents do not automatically create five independent fault domains.** Region slugs do
   not prove distinct power/network providers or data centers, and Contabo may place a regional
   product only where capacity exists.
10. **A 4-of-5 global quorum is latency-sensitive.** The current `two-thirds` resolver maps five
    members to threshold four. It tolerates one unavailable member but requires a fourth global vote
    for every block. Block interval and round timeout must be measured, not copied from the 1-second
    local showcase.
11. **Changing `image_id` reinstalls a Contabo instance.** The current provider documentation marks
    image, root password, default user, and SSH-key updates as reinstalling operations. Plans that
    imply reinstall/replacement must be blocked unless an explicit replacement workflow is active.
12. **Cloud firewalls are inbound-only and lack traffic logs.** Contabo documents a permanent
    default drop for assigned active firewalls and unrestricted outbound traffic. Host firewalling,
    service binding, access logs, and monitoring remain necessary.
13. **The current app-chain project schema describes exactly one chain.** The runtime and showcase
    can host multiple chains, but `appchain-blueprint.schema.json` currently has `chains.maxItems=1`.
    Because this pilot intentionally deploys the packaged 13-chain showcase, implementation must
    add a reviewed, bounded multi-chain deployment-profile schema and lock. It must not concatenate
    generated YAML or pretend the current single-chain project lock covers the showcase.
14. **“All state machines” cannot mean every mutually exclusive plugin variant in one catalog.**
    The standard and ZK EUTxO runtime bundles intentionally provide the same contribution and cannot
    be loaded together. The initial cluster uses the standard EUTxO implementation represented by
    the light showcase; the ZK variant needs a separately locked cluster/profile.
15. **The checked-in settlement example is not Preprod configuration.** Its profile and public demo
    material are explicitly devnet-only. Offline compilation must replace that block with the
    production-v3 identity derived from an operator-owned seed, resumable Preprod deployment record,
    and the exact five-member federation. Funding and L1 bootstrap remain separate explicitly
    authorized operations even though the final chain configuration is present at first start.

## 3. Provider and existing-host evidence

The Contabo evidence was rechecked on 2026-08-23 against primary sources:

- The [`contabo/contabo` provider](https://registry.terraform.io/providers/contabo/contabo/0.1.44)
  was at `0.1.44`. It exposes instances, images, SSH-key secrets, tags, firewalls, snapshots, and
  private networks. The implementation will pin an exact tested version and commit the dependency
  lock; it will not use `latest` or an unconstrained range.
- The current
  [`contabo_instance` resource](https://registry.terraform.io/providers/contabo/contabo/0.1.44/docs/resources/contabo_instance)
  documents the region values `EU`, `US-central`, `US-east`, `US-west`, and `SIN`, plus `image_id`,
  `product_id`, SSH-key secret IDs, and cloud-init `user_data`.
- The [Contabo API](https://api.contabo.com/) exposes live instance and image inventory, firewalls,
  DNS zones/records, tags, snapshots, and request/trace IDs. The API can return states such as
  `product_not_available`; documented slugs do not guarantee account/product capacity.
- Contabo's
  [firewall documentation](https://help.contabo.com/en/support/solutions/articles/103000390430-firewall-what-is-it-and-how-does-it-protect-my-vps-vds-)
  states that an active assigned firewall blocks inbound traffic by default, permits explicit accept
  rules, leaves outbound traffic unrestricted, and does not currently provide firewall traffic logs.

These facts are observations, not application constants. The implementation must provide a read-only
`discover` command and live `preflight` that:

- reports provider/plugin version and checksum;
- queries available standard images for the authenticated account;
- validates requested region strings against current provider/API behavior;
- verifies the requested product/region combination as far as the API allows and treats capacity as
  an apply-time, resumable failure when it cannot be proven in advance;
- verifies referenced SSH public-key secret IDs;
- verifies optional DNS zone ownership/access;
- never prints provider credentials or access tokens; and
- records resolved non-secret IDs and observations in `deployment.lock` with a timestamp, while
  requiring revalidation when the plan is later applied.

The deployment schema accepts region and product identifiers as bounded strings. It must not add a
JSON Schema enum copied from today's documentation.

The other v1 adapters use the official OpenTofu-compatible providers:

- [`hetznercloud/hcloud`](https://registry.terraform.io/providers/hetznercloud/hcloud/latest/docs)
  supplies `hcloud_server` and `hcloud_firewall`; locations, server types, images, and SSH keys remain
  live account inputs. Credentials come from `HCLOUD_TOKEN`.
- [`digitalocean/digitalocean`](https://registry.terraform.io/providers/digitalocean/digitalocean/latest/docs)
  supplies `digitalocean_droplet` and `digitalocean_firewall`; regions, sizes, images, and SSH keys
  remain live account inputs. Credentials come from `DIGITALOCEAN_TOKEN`.

The `existing` adapter owns no infrastructure resources or state. It accepts a stable address, SSH
user/port, failure-domain metadata, and node role. The same host preflight, host firewall, artifact,
configuration, systemd, and status contracts apply, but lifecycle actions that imply VM mutation are
unavailable. Mixed clusters compile every node into one provider-neutral inventory after cloud
outputs are resolved.

## 4. Declarative model

### 4.1 Two documents, one locked deployment bundle

Do not duplicate consensus configuration in infrastructure HCL. A deployment bundle contains:

```text
cluster/
├── deployment.yaml                 operator-owned infrastructure/operations source
├── deployment.lock                 generated; binds every resolved and rendered input
├── artifacts/                      ignored local cache; content-addressed imported ZIP
│   └── sha256/<digest>.zip
└── appchain/                       locked multi-chain deployment profile
    ├── profile.yaml                chain/capability + topology + consensus source
    ├── profile.lock
    ├── config/shared-consensus.yaml
    └── config/nodeN.yaml
```

`deployment.yaml` references the imported artifact digest and deployment profile, and pins the
`profile.lock` digest. The content-addressed artifact cache is not committed, but a plan/apply cannot
proceed unless the exact locked bytes are locally available. The combined
`deployment.lock` binds:

- deployment source digest and schema version;
- multi-chain deployment profile, resolved-config, release-catalog, and generated-file digests;
- exact Yano and Yano X identities and source revisions;
- showcase distribution identity, build provenance, dirty-source status, and exact ZIP checksum;
- provider and OpenTofu versions/checksums;
- resolved Contabo image, product, region, instance, firewall, and public-address IDs;
- public member keys and their node assignment, never private seeds;
- shared consensus and per-node overlay digests;
- plugin catalog/manifest digest and each bundle checksum;
- L1 network/genesis identity;
- app-chain chain IDs, state commitment identities, and immutable genesis IDs;
- firewall policy, DNS, TLS, monitoring, and backup policy digests; and
- generated HCL, cloud-init, inventory, service-unit, and validation-script checksums.

Any input drift requires an explicit re-render and reviewed lock change. Live `status` compares
redacted runtime identity with the lock rather than considering an OpenTofu apply sufficient proof.

### 4.2 Avoid an overloaded “network ID”

The requested “network ID” is ambiguous. The schema will keep these identities distinct:

| Identity | Meaning |
|---|---|
| `metadata.name` | Human-readable deployment name |
| `spec.identity.clusterId` | Generated immutable UUID for this deployment lineage |
| `spec.l1.network` | `preview`, `preprod`, `mainnet`, or a separately defined custom Cardano network |
| L1 protocol magic/genesis hashes | Derived from and verified against the exact selected network profile |
| app-chain `chainId` | Application-ledger namespace from the locked application profile |
| commitment profile + format fingerprint + genesis ID | Pinned authenticated-state identity per chain |

For the first public pilot, the automation creates a new app-chain cluster over the existing Cardano
Preprod L1. Preprod is the example and initial qualification target, not a schema default hidden in
code: the operator must still set `spec.l1.network`, and the lock pins the resolved L1 genesis
identity. The automation does not create a Cardano network or generate Cardano genesis files.

### 4.3 Illustrative manifest

The following demonstrates the intended shape. Region values are examples verified on the ADR date,
not schema enums. Product and image placeholders must be selected through live discovery.

```yaml
apiVersion: yano.bloxbean.com/v1alpha1
kind: YanoClusterDeployment
metadata:
  name: yano-preprod-showcase-1
spec:
  identity:
    clusterId: "<uuid>"
  infrastructure:
    openTofuVersion: "<tested-exact-version>"
    state:
      backendType: s3
      backendConfigFile: secrets/backend.hcl
  providers:
    - name: contabo
      type: contabo
      providerVersion: "0.1.44"
      settings: {sshKeyIds: ["<numeric-secret-id>"]}
    - name: hetzner
      type: hetzner-cloud
      providerVersion: "<tested-exact-version>"
      settings: {sshKeys: ["<key-name-or-id>"]}
    - name: digitalocean
      type: digitalocean
      providerVersion: "<tested-exact-version>"
      settings: {sshKeys: ["<key-fingerprint-or-id>"]}
    - name: internal
      type: existing
  nodes:
    - {name: node-0, index: 0, providerRef: contabo, region: "<live-region>",
       instanceType: "<product-id>", image: "<image-id>", sshUser: admin, sshPort: 22,
       roles: [validator, api-gateway], memberPublicKey: "<64-hex>",
       memberPrivateKeyFile: secrets/node-0.seed}
    - {name: node-1, index: 1, providerRef: hetzner, region: "<live-location>",
       instanceType: "<server-type>", image: "<image>", sshUser: admin, sshPort: 22,
       roles: [validator], memberPublicKey: "<64-hex>", memberPrivateKeyFile: secrets/node-1.seed}
    - {name: node-2, index: 2, providerRef: digitalocean, region: "<live-region>",
       instanceType: "<droplet-size>", image: "<image>", sshUser: admin, sshPort: 22,
       roles: [validator], memberPublicKey: "<64-hex>", memberPrivateKeyFile: secrets/node-2.seed}
    - {name: node-3, index: 3, providerRef: internal, address: "203.0.113.13",
       sshUser: admin, sshPort: 22, roles: [validator], memberPublicKey: "<64-hex>",
       memberPrivateKeyFile: secrets/node-3.seed}
    - {name: node-4, index: 4, providerRef: internal, address: "203.0.113.14",
       sshUser: admin, sshPort: 22, roles: [validator], memberPublicKey: "<64-hex>",
       memberPrivateKeyFile: secrets/node-4.seed}
  destructionProtection: true
  l1:
    network: preprod
    # Optional; omitted defaults to the selected network alone.
    profile: preprod
  runtime:
    # The build is a separate local/CI action. Import copies these exact bytes
    # into the deployer's content-addressed cache before render/plan/apply.
    source:
      kind: local-showcase-zip
      yanoVersion: "<exact-version>"
      yanoXVersion: "<exact-version>"
      file: "/operator/path/yano-showcase-<version>.zip"
      sha256: "<64-hex>"
      provenanceFile: "/operator/path/yano-showcase-<version>.provenance.json"
  application:
    profile: distributed-showcase-preprod-anchored-settlement-v1
    catalog: "packaged:config/showcase-catalog-v1.json"
    expectedProfileLockSha256: "<64-hex>"
    excludedIntegrations: [kafka, s3-effects, ipfs]
    anchoring:
      mode: script
      chains: all
      leaderNode: node-0
      seedFile: ../private-anchor/anchor.seed
      # Optional overrides; unlisted chains use seedFile.
      chainSeedFiles: {}
      everyBlocks: 30
      maxIntervalMinutes: 60
    settlement: {mode: preprod, chainId: payment-chain-settlement, ownerNode: node-0}
    cardanoHistory:
      preset: full
      authenticatedSnapshots: true
  topology:
    validators: 5
    bootstrapNodes: [0, 1]
    transport:
      mode: public
      peerPort: 13337
      # wireguard is a reserved future mode and is rejected by v1.
  access:
    ssh:
      sourceCidrs: ["<team-cidr>"]
    api:
      exposure: public-https
      proxy: cloudflare
      gatewayNodes: [0, 1]
      sourceCidrs: []
      hostnames: ["api-0.example.org", "api-1.example.org"]
      tls: traefik-acme-http-01
      rateLimit: "<reviewed-policy>"
      broadAuthentication: true
      testerCredentialRefs: ["<secret-reference>"]
      adminCredentialRef: "<separate-secret-reference>"
  dns:
    mode: external
  monitoring:
    mode: central-prometheus
    node: 0
    retention: 15d
  backup:
    mode: s3
    endpoint: "<object-storage-endpoint>"
    bucket: "<bucket>"
    credentialRef: "<secret-reference>"
    encryption: client-side
    appchainSnapshotSchedule: "<schedule>"
```

The referenced deployment profile is derived from the packaged showcase catalog and becomes the
source for validator public keys, finality policy, sequencing mode, chain IDs, state machines,
commitment identities, and deterministic limits. For the initial five-node pilot it explicitly
selects five known member public keys, `two-thirds` finality (threshold four), an explicit
fixed-proposer node for the initial geographically distributed deployment, and governed membership.
The renderer resolves the proposer node name to its validator member public key and installs the same
value on every member. Experimental rotating sequencing remains a declarative option, but it is not
the initial default because L1-window disagreement can split persistent vote locks and halt liveness.

While dedicated app-chain transport uses one connection per active chain, the renderer also derives
and locks a bounded per-peer connection allowance from the active chain count. Relying on Yano's
conservative default per-IP limit would silently connect only a prefix of a multi-chain profile.

The initially pinned pre-release Yano build can also retain a stale dedicated-client lifecycle after
an established peer restarts. Until the pinned host artifact contains reconnect-safe dedicated peer
lifecycle behavior, apply follows its serial configuration phase with a coordinated clean stop and
parallel start of all members, then fails unless every chain reports every other member connected.
This compatibility step preserves all retained stores but creates a bounded full-cluster interruption;
it must be removed once the upstream reconnect gate is proven by rolling-restart qualification.

Schema validation must reject disagreement between the deployment topology and application profile,
including member count, node host count, port allocation, runtime type, and artifact identity.

### 4.4 Pre-release artifact promotion

Building is deliberately outside infrastructure reconciliation. An operator or CI job first builds
Yano's exact Maven artifacts and ordinary JVM ZIP, stages the matching Yano X publications, and runs
the showcase test, script contract, `distZip`, and distribution contract. The result is
`yano-showcase-<yano-x-version>.zip`; the generic combined `yano-x-jvm` ZIP is insufficient because
it does not by itself provide the packaged showcase profile configuration, 13-chain catalog, demo
CLI, and showcase distribution contract as one deployable unit. Its default plugin inventory may
contain the showcase bundle, but bundle presence alone does not define the demonstration network.

The deployment CLI then performs a state-changing local import, for example:

```text
yano.sh deploy artifact import <cluster> \
  --file /absolute/path/yano-showcase-<version>.zip \
  --provenance /absolute/path/yano-showcase-<version>.provenance.json
```

The source build behind that import follows the existing coordinated build contract:

```text
# In Yano: publish one exact Maven/JVM-ZIP identity.
./gradlew publishToMavenLocal :app:yanoDistZip \
  -PskipSigning=true --no-parallel

# In Yano X: run a clean build, publish to a new empty internal staging
# repository, then assemble and verify the showcase archive from that stage.
./gradlew clean build <exact-yano-version-and-jvm-zip-properties>
./gradlew publishAllPublicationsToInternalRepository \
  -PinternalRepository=<new-empty-staging> <same-exact-yano-properties>
./gradlew :examples:showcase:test \
  :examples:showcase:showcaseScriptContract \
  :examples:showcase:distZip \
  :examples:showcase:showcaseDistributionContract \
  -PinternalRepository=<same-staging> <same-exact-yano-properties>
```

The current build verifies distribution identity and contents but does not yet emit the proposed
deployment provenance document. Producing and validating that document is therefore Phase 1 work,
not a capability that the ADR assumes already exists.

Import must verify the whole archive and embedded distribution identities, record SHA-256 and build
provenance, and copy the bytes into a content-addressed operator cache. A shared Preprod cluster
normally requires a clean source revision. A deliberately dirty development build requires an
explicit override and a digest of the complete source diff in provenance; a version string alone is
never sufficient. OpenTofu never sees the archive bytes or local path.

After instances exist, Ansible pushes the cached archive over SSH to a temporary path on every node,
verifies SHA-256 before extraction, installs it under `/opt/yano/releases/<artifact-digest>/`, and
atomically changes `current` only after all stopped-node preflights succeed. Optional future upload
to an artifact repository is a transport optimization; nodes must never independently download
mutable or unauthenticated build output.

### 4.5 Initial demonstration capability profile

`distributed-showcase-preprod-anchored-settlement-v1` starts from the 13-chain packaged
light-showcase catalog and selects all thirteen active chains:

| Chain | State machine / purpose | Initial Preprod posture |
|---|---|---|
| `orders-chain` | Ordered log and finalized-message proofs | Active |
| `registry-chain` | Key/value registry | Active |
| `approvals-chain` | Basic threshold approvals | Active |
| `balances-chain` | Token balances | Active; demonstration values only |
| `documents-chain` | Document trail | Active |
| `workflow-chain` | Order + approval composite | Active; local file outbox effect allowed |
| `roles-chain` | Actor/role approvals | Active |
| `payments-chain` | Standard virtual EUTxO ledger | Active; never fund its public demo address on L1 |
| `authenticated-map-chain` | Governed authenticated map with MPF proofs | Active with non-demo actor keys |
| `authenticated-map-jmt-chain` | Same policy with JMT proof backend | Active with non-demo actor keys |
| `document-review-chain` | Roles + approvals + document transitions | Active with non-demo actor keys |
| `cardano-history-chain` | Stable L1 history and authenticated snapshots | Active; broad preset subject to capacity qualification |
| `payment-chain-settlement` | Preprod L1 custody/settlement boundary | Active with the operator's production-v3 deployment record and one effect owner |

Kafka, S3, and IPFS effects/connectors are not in the runtime plugin allow-list. The deployment does
not start the evidence profile, Kafka, an S3-compatible sink, or IPFS, and does not create their
credentials or firewall rules. This does not prohibit an S3-compatible object store from being used
independently as encrypted backup storage; backup transport is not an app-chain effect.

The safe showcase file-outbox effect remains enabled only on its designated owner node so testers
can demonstrate intent, finality gating, idempotent execution, result commitment, and proofs without
an external service. The settlement effect and bridge plugin are active only on the settlement
chain, with the effect executor pinned to one declared owner.
The optional ZK EUTxO implementation is excluded because it conflicts with the standard EUTxO
contribution; it requires a separate deployment profile and cluster.

The first real five-node rollout enables SCRIPT anchoring for all thirteen chains and the production
Preprod settlement identity. Offline render derives the settlement configuration from an existing,
resumable deployment record and verifies it against the operator seed, five federation keys, and
threshold. Kafka, S3, and IPFS remain separately selectable future profiles.

## 5. Compilation and apply architecture

### 5.1 Provider-neutral intermediate representation

The renderer compiles the two source documents into a provider-neutral node inventory:

```text
NodePlan
  nodeId
  providerRef/providerType
  infrastructureLifecycle: managed | externally-managed
  region
  roles: validator, l1-bootstrap, api-gateway, monitoring
  providerInstanceId (absent for existing VMs)
  publicIpv4/publicIpv6
  transportAddress
  advertisedAddress
  apiAddress
  memberPublicKey
  sharedConfigDigest/nodeConfigDigest/pluginCatalogDigest
  authoritativeStorePaths/rebuildableIndexerPath
```

Public mode sets transport and advertised addresses to reviewed DNS names or public IPs. A future
WireGuard compiler can replace only those two fields with overlay addresses while keeping the
provider instance, node identity, storage, app-chain project, and operation phases unchanged.

Provider-specific HCL consumes this intermediate representation. Provider-specific fields must not
leak into app-chain configuration or consensus identity.

### 5.2 Staged reconciliation

One `apply` is a resumable state machine, not an opaque shell script:

1. **Validate** schemas, locks, imported artifact/provenance, exact versions, source CIDRs, secret
   references, port collisions, topology, quorum, capability exclusions, and destructive intent.
2. **Discover** current provider images/regions/SSH secrets/DNS access and refresh non-secret facts.
3. **Plan infrastructure** and classify every change as create, in-place, replace, or destroy.
4. **Bind apply to the saved plan and exact cluster UUID.** Apply is non-interactive after the
   deployer supplies that identifier, but rejects every reinstall/replacement/delete action; this
   prevents unattended convenience from becoming unattended destruction.
5. **Provision** instances, tags, active cloud firewalls, and optional DNS through OpenTofu.
6. **Bootstrap hosts** with secret-free cloud-init: operator account, SSH hardening, time sync,
   unattended security updates policy, host firewall default deny, directories, and a one-shot
   readiness marker. Cloud-init must not install member/API/anchor/connector secrets.
7. **Resolve inventory** from provider outputs and re-render peer/DNS facts. New public IPs are plan
   changes, never silently accepted drift.
8. **Push and configure hosts** over SSH using an idempotent configuration layer (Ansible in
   version one): transfer and verify the imported showcase ZIP, install the exact artifact, systemd
   units, configuration, credentials, TLS gateway, backup jobs, and monitoring agents. No node
   builds from source or fetches a mutable URL.
9. **Preflight all nodes while stopped**: artifact identity, Java 25, exact chain/capability catalog,
   forbidden Kafka/S3/IPFS plugin absence, generated configuration, own member key, membership
   list, commitment/genesis identity, store ownership/layout, disk space, clocks, DNS, and peer
   reachability.
10. **Start validators** with the public API gateway disabled. The service uses the immutable
    bootstrap member set; it does not generate or govern membership implicitly.
11. **Convergence gate**: require five healthy members with identical chain set, height/root where
    expected, commitment profile, genesis ID, consensus-profile digest, capability-manifest digest,
    and finality certificate verification. Verify catch-up and L1 network identity independently.
12. **Enable ingress** only after convergence: start HTTPS gateways and enable tester firewall
    rules. A failed gate leaves APIs closed and reports the first relevant node error.
13. **Record evidence**: sanitized outputs, plan digest, deployment lock digest, node identities,
    health/root/capability results, firewall/DNS checks, and artifact checksums.

Each phase writes a non-secret journal keyed by deployment lock digest. Resume verifies the journal
and live state before continuing; it never assumes the previous command completed atomically.

## 6. Infrastructure decisions

### 6.1 OpenTofu and provider pinning

OpenTofu is the version-one infrastructure engine for cloud nodes. The three cloud providers speak
the standard provider protocol and generated HCL remains Terraform-compatible, but only exact
tested OpenTofu/provider matrices are claimed. Existing-VM-only clusters skip OpenTofu entirely.

The repository will commit:

- exact OpenTofu and provider constraints;
- `.terraform.lock.hcl` provider checksums for supported platforms;
- provider adapters for Contabo, Hetzner Cloud, and DigitalOcean plus an infrastructure-free
  existing-host adapter;
- no provider credentials, access tokens, backend secrets, or state files; and
- tests that generated plans contain no Yano member/API/anchor/connector secrets.

Credentials are supplied through provider environment variables from a CI or operator secret
store: `CNTB_OAUTH2_*`, `HCLOUD_TOKEN`, and `DIGITALOCEAN_TOKEN`. Existing hosts use SSH agent or
an Ansible-supported external credential source. Read-only discovery should use least privilege;
apply uses a separate identity scoped to required endpoints where the provider supports that split.

### 6.2 State backend

Local state is forbidden for a shared/persistent cluster. The operator must configure an encrypted,
versioned remote backend with locking and restricted access. A Contabo S3-compatible object-storage
backend may be documented, but backend choice remains provider-neutral.

Even though secrets are excluded, state contains infrastructure identifiers and public topology and
is treated as sensitive. CI serializes applies, retains saved plans/evidence, and rejects concurrent
mutation.

### 6.3 Destruction and replacement protection

`destructionProtection: true` is the default and compiles to literal `prevent_destroy = true` on
instances and other retained resources. A manifest flag must not dynamically bypass the guard in an
already-approved plan.

The deployer classifies these as destructive:

- instance destroy or replacement;
- image reinstall;
- root disk loss;
- change of member signer assignment;
- loss/change of an app-chain genesis or commitment identity;
- authoritative app-chain state path replacement;
- firewall removal that broadens exposure during transition; and
- DNS change that redirects a bootstrap/API identity unexpectedly.

Break-glass replacement is a separate command and reviewed plan. It requires the exact cluster name,
cluster UUID, deployment-lock digest, target node IDs, a recent verified app-chain snapshot/backup,
and an explicit acknowledgement. Full cluster destroy additionally requires APIs disabled, all
nodes stopped or fenced, a retained backup manifest, and a second exact confirmation. Provider
destroy behavior and billing/cancellation semantics must be proven on a disposable instance before
the command is offered.

## 7. Host and runtime layout

### 7.1 Storage

Every node keeps these as siblings:

```text
/var/lib/yano/chainstate/             authoritative Cardano L1 state
/var/lib/yano/appchain-chainstate/    authoritative app-chain state
/var/lib/yano/appchain-indexers/      rebuildable node-local indexes
```

The automation rejects app-chain indexes beneath L1 `chainstate`, rejects restoring index data as
authoritative, and rejects an index checkpoint ahead of authoritative app-chain state.

Artifacts live under `/opt/yano/releases/<artifact-digest>/` with a controlled `current` pointer.
Configuration lives under `/etc/yano/`; non-secret rendered files are read-only and digest-checked.
The service runs as a dedicated unprivileged account with systemd hardening and explicit resource
limits. Logs go to journald with retention/forwarding policy.

### 7.2 Locally promoted showcase ZIP mode

Version one installs the imported `yano-showcase-<version>.zip`. It verifies the whole-archive
SHA-256, embedded Yano and Yano X identity manifests, Java version, showcase catalog, plugin
manifests, bundle checksums, SBOM, and provenance, then runs the packaged Yano JVM host under
systemd. Every validator receives byte-identical archive and shared-profile bytes. It never builds
on a server or downloads an unpinned “latest” asset.

The archive may physically contain optional profile assets, including the local evidence demo, but
only the locked `distributed-showcase-preprod-anchored-settlement-v1` allow-list is copied into the active plugin
directory. Kafka, S3-effect, and IPFS bundles must be absent from that directory and from the live
plugin catalog. Manually deleting files and then treating the result as the original ZIP is not
allowed; the installer selects active bundles while preserving the imported archive's identity.

### 7.3 OCI mode

OCI mode requires an image reference by manifest digest, never only a mutable tag. The image must be
artifact-first: copy the already-verified showcase/combined JVM payload into a Java 25 runtime
image, run non-root, expose only declared ports, and carry machine-verifiable labels/manifests for:

- Yano version and base distribution digest;
- Yano X version and combined distribution digest;
- plugin catalog digest; and
- source revision/SBOM/provenance.

The deployer runs the same offline artifact checks against the image before apply and the same live
identity checks after start. A core-only Yano image fails this contract when the selected app-chain
project requires Yano X bundles.

## 8. Identities and secrets

### 8.1 Separate identities

The deployment must never reuse one secret for multiple roles:

| Identity | Scope |
|---|---|
| Cloud-provider API principal(s) | Infrastructure discovery/apply |
| SSH host key and operator key | Host access |
| Member Ed25519 seed | One validator's app messages, votes, and certificates |
| API full key | Privileged node operations; never given to testers |
| Topic-scoped tester keys | Read plus bounded topic submission |
| Snapshot-admin key | Snapshot lifecycle only |
| TLS private key | HTTPS endpoint |
| Anchor wallet seed | L1 fees/collateral on designated anchor leader only |
| Connector/effect credentials | Only executor nodes that own the connector |

The manifest and lock contain secret references and public/fingerprint material only.

### 8.2 Member identity ceremony

The current runtime consumes a raw 32-byte Ed25519 seed; a production KMS/HSM signer is open work.
Version one therefore supports an explicit identity ceremony before infrastructure apply:

1. generate five independent member seeds with a reviewed CSPRNG, or import operator-generated
   seeds;
2. derive and display only public keys in normal output;
3. store each seed in a selected secret backend under a node-specific reference;
4. write public keys into the multi-chain profile, render, validate, and lock it;
5. install only node N's seed on node N through the configuration channel; and
6. verify on-host that the seed derives the locked public key without returning the seed.

The initial backend may be encrypted SOPS/age or an external operator secret store. Plaintext files
are allowed only as an explicit local backend with strict owner/mode/symlink checks equivalent to the
single-host launcher and are never committed. OpenTofu variables, `user_data`, remote state, process
arguments, and generated `.env` files are forbidden secret carriers.

On systemd hosts, secret material is exposed to the service through protected credential/config
files, not printed environment arguments. Configuration tooling and Ansible use `no_log`/redaction
and test that failure paths do not reveal values.

## 9. Networking and exposure

### 9.1 Public transport mode

All five validators have public addresses. The app-chain peer topology is an explicit full mesh for
the first five-node deployment. DNS names are preferred because replacement IPs then become a DNS
and lock transition rather than a consensus-file edit; Yano's bounded DNS cache policy still applies.

Node roles are independent flags:

- every initial node is an app-chain validator/member;
- two selected nodes may advertise as public **L1 relay bootstrap** nodes;
- selected nodes may run HTTPS API gateways; and
- one selected node may host the small monitoring control plane.

No role implies another. In particular, a public relay should not automatically be the fixed
sequencer or anchor-wallet owner.

### 9.2 Firewall matrix

Both each cloud-provider firewall and the host firewall enforce the compiled policy; existing VMs
use the host layer and any separately operated data-center firewall:

| Port/surface | Allowed sources | Notes |
|---|---|---|
| SSH | BloxBean management CIDRs; explicit rate-limited public-key-only mode for dynamic operator addresses | Password, keyboard-interactive, and direct root login disabled before public ingress is accepted |
| Yano N2N/appmsg port | Other validator public IPs on all validators; Internet only on explicitly selected L1 bootstrap nodes | Public bootstrap is exposed to DoS and must use Yano connection/per-IP limits plus host rate controls |
| Yano HTTP port | Loopback and validator/monitoring IPs only | Never directly public |
| HTTPS 443 | Tester/team allow-list on API gateway nodes | Reverse proxy, TLS, auth, bounds, rate limits, redacted access logs |
| ACME HTTP 80 | Internet only when the selected challenge requires it | Redirect/challenge only; close when not required |
| Metrics | Monitoring node/validator IPs only | Never Internet-public |

Rules must include IPv4 and IPv6 deliberately. If IPv6 is assigned but not configured, it is blocked
rather than left outside an IPv4-only policy.

The preferred SSH policy is a non-wildcard management CIDR allow-list. An operator with a genuinely
dynamic management address may explicitly select `public-key-only` mode instead of disabling the
firewall. That mode has no CIDR entries, opens only the configured SSH port on IPv4 and IPv6 with
host rate limiting, and fails closed unless the effective sshd policy enables public keys while
disabling password, keyboard-interactive, and direct-root login. It is a recorded deployment-lock
choice, does not broaden API exposure, and should be replaced by an allow-list or static management
egress when available. Existing-VM deployments configure the host firewall but do not claim
ownership of a separately managed cloud or data-center firewall.

Firewall attachment is not assumed atomic with VM creation. Secret-free cloud-init installs the host
default-deny policy before application installation, reducing the provider attachment window. Apply
does not start Yano until both firewall layers are verified.

### 9.3 API policy

Version one accepts `disabled` and `allowlist` API exposure. A `public` wildcard submit mode is
reserved and rejected.

For `allowlist`:

- Yano binds its HTTP service to loopback on gateway nodes or to a firewall-limited host address
  when validator/monitoring access is required;
- a reverse proxy terminates TLS and forwards only intended routes;
- `yano.app-chain.api.auth.enabled=true` protects reads and submissions;
- testers receive topic-scoped keys; the unscoped full/admin keys remain in the operator backend;
- the gateway enforces request/connection/body/rate bounds before Yano;
- CORS lists exact origins and never combines wildcard origin with credentials;
- Swagger, plugin operations, admin, metrics, and health-group exposure are individually reviewed;
  and
- logs never record `X-API-Key`, bodies, seeds, or connector credentials.

mTLS/OIDC and privileged-operation audit logging remain follow-up hardening, not claims made by this
deployment.

### 9.4 WireGuard extension seam

The future `transport.mode: wireguard` adapter will produce overlay addresses and firewall rules and
install peer credentials. It will not change:

- Contabo instance resources or placement;
- member public keys or app-chain genesis membership;
- shared consensus config;
- artifact/plugin identity;
- storage layout; or
- deployment lifecycle phases.

The public API may remain public/allow-listed independently of validator transport mode.

## 10. Consensus, genesis, and topology

### 10.1 Initial five-node posture

The recommended initial Preprod pilot is:

- five permissioned validators/members;
- one validator in each live-validated requested region where product capacity exists;
- four-of-five (`two-thirds`) finality;
- fixed sequencing with an explicitly selected validator proposer;
- governed membership;
- full-mesh app-chain peers; and
- the locked broad showcase profile, with Kafka/S3/IPFS integrations excluded and the production
  Preprod settlement identity configured; and
- SCRIPT anchoring configured for every chain, while fee-paying anchor and settlement bootstrap
  transactions remain behind separate safety, funding, and spend-authorization gates.

The default anchor account is intentionally shared by every chain to keep the first deployment
operable. `anchoring.chainSeedFiles` may override the controller-side seed-file reference for any
catalog chain that requires an independent hot wallet and fee budget. Only override chain IDs enter
the deployment lock; seed contents never do. Bootstrap remains sequential even with independent
accounts so the default shared-account behavior cannot race its own change UTxO.

Four-of-five preserves safety and progress with one member unavailable. It does not progress with
two unavailable members. A 3/2 partition must halt both sides rather than finalize competing blocks.
Global latency testing determines block interval, proposer window, and round timeout. The local
showcase values are not defaults for this cluster.

Fixed sequencing means proposer loss halts progress, so the selected node requires monitoring and a
reviewed recovery runbook. It must be selected explicitly for its operational role, not merely
because it is node zero. Rotating sequencing is retained as an experimental opt-in and requires a
qualified window size plus a recovery drill for split persistent vote locks.

For a retained prerelease chain, changing only the sequencer policy does not regenerate member keys,
genesis IDs, commitment profiles, finalized blocks, roots, anchors, or indexes. The change must be
rendered identically and activated with a coordinated restart of every validator; it is not a rolling
node-local update. Any stale-round unlock remains a separate audited recovery action after operators
prove that no finality certificate exists for the locked height.

### 10.2 Fresh-chain bootstrap

Creating infrastructure is not app-chain genesis. The genesis ceremony is complete only when:

1. all five member public keys and threshold are locked;
2. every chain's chain ID, state machine/profile, commitment profile, format fingerprint, genesis ID,
   and consensus-affecting settings are locked;
3. all nodes carry byte-identical shared config/plugin bundles and their distinct matching signer;
4. all peers can reach one another and agree on the L1 network/genesis identity;
5. no retained authoritative app-chain state exists under a conflicting identity; and
6. the first certified history is observed with matching roots/certificates.

The deployer never regenerates a genesis ID during resume, repair, or host replacement. Governed
membership changes are finalized chain operations after bootstrap and do not rewrite the bootstrap
identity. Infrastructure scaling does not imply validator membership.

SCRIPT anchor identity is a second, explicitly authorized L1 bootstrap phase. The generated
`bootstrap-anchors.yml` aborts globally before transaction submission unless the anchor leader and an entire
signing quorum are L1-current, healthy, and fully connected for every hosted app chain. The one-time
identity transaction itself is leader-authorized; the quorum requirement protects the immediate first
threshold-signed state advancement. A non-current member is therefore not a hard blocker when the
remaining current members still meet the configured threshold. The playbook does not hold an Ansible connection open
during initial Cardano synchronization. Operators or a scheduler rerun the separate command after
readiness converges. The default selector is the complete locked catalog; the leader checks and skips
already bootstrapped chains, resumes pending confirmations, and processes remaining chains in catalog
order. Each newly submitted bootstrap has a bounded confirmation wait before the next chain begins.

### 10.3 External observers

Version one supports external testers through HTTPS and external Cardano relay experimentation
through selected public L1 bootstrap nodes. It does **not** support a non-member replicated app-chain
observer.

Delivering that requirement needs a Yano host ADR/change that defines at least:

- a node role with no member signing seed and no vote/proposal capability;
- authenticated block/certificate catch-up from member peers;
- admission and transport rules for non-member peers;
- snapshot trust/onboarding and pruning-horizon behavior;
- peer-discovery or explicit bootstrap semantics distinct from validator governance;
- resource/DoS limits and whether observers serve other observers; and
- proof/root trust wording and compatibility tests.

The deployment schema may reserve `observerBootstrap` fields, but v1 validation rejects them with a
clear `CON-011` diagnostic rather than generating insecure pseudo-observers.

## 11. DNS, TLS, monitoring, and backups

### 11.1 DNS and TLS

DNS is an adapter boundary:

- `external`: generate required A/AAAA records and verify the operator-created records;
- `contabo-api`: future adapter for the Contabo DNS API; and
- provider-specific adapters such as Cloudflare may be added independently.

The current Contabo provider does not need to be extended or bypassed inside the infrastructure
module to deliver v1. External DNS is the initial safe path. API hostnames are per node and optional;
only a node with both an API-gateway role and a hostname receives the checksum-pinned Traefik
gateway. Traefik retains ACME account/certificate state and proxies to Yano's loopback-only HTTP
port. With `proxy: cloudflare`, origin HTTP/HTTPS ingress is restricted to Cloudflare's published
proxy ranges. The proxied API hostname is never used for Yano P2P; app-chain peers continue to use
direct advertised addresses on the public P2P port. Without DNS, external tester API exposure
remains disabled unless the operator supplies a separately verified certificate.

### 11.2 Monitoring

`monitoring.mode: none` is valid. The initial optional mode deploys one pinned Prometheus instance
with persistent retention and scrapes only firewall-allow-listed node endpoints. It collects:

- system CPU, memory, filesystem, clock, restart, and process health;
- Yano readiness and app-chain operational health group;
- tip/root parity, connected peers, stall, pool/drop pressure, finality rate;
- anchor, sink, effect, executor, and index lag where configured; and
- TLS expiry, backup age, disk pressure, and service restart loops.

Monitoring failure must not affect consensus. Prometheus/Grafana, if exposed, use separate
authentication and are not public by default. The existing local observability launcher is not
remotely reused; only its metrics contracts and pinned-image discipline carry forward.

### 11.3 Backups and restore

L1 state is rebuildable but expensive. App-chain state is authoritative and needs signed snapshots,
encrypted off-host retention, and a tested restore path. Rebuildable app-chain indexes are excluded
from authoritative backups.

Before the Preprod cluster is considered operable:

- schedule signed app-chain snapshots from more than one member;
- upload client-side encrypted archives to a versioned object store;
- retain deployment/app-chain locks and artifact/plugin checksums beside backup metadata;
- verify finality certificate, snapshot manifest signature, file hashes, chain identity, and root on
  restore; and
- complete one fresh-instance restore and catch-up rehearsal.

Provider instance snapshots may reduce recovery time but do not replace application-consistent,
independently verifiable app-chain snapshots.

## 12. Implementation layout

The implemented source layout is:

```text
deployment/
├── README.md
├── ansible/
│   ├── bootstrap-existing-vms.yml
│   └── bootstrap-inventory.example.yml
├── schema/
│   └── yano-cluster-deployment.schema.json
└── examples/mixed-five-node/deployment.yaml

tooling/deployment/
├── Java CLI/model/renderer/validator/artifact/lifecycle/provider adapters
└── generated OpenTofu, Ansible (including SCRIPT-anchor bootstrap and central Prometheus),
    cloud-init, Traefik, systemd, inventory, and lock inputs
```

The existing-VM bootstrap belongs to the deployment module rather than a
separate repository or product. It is provider-neutral and is packaged with
the same schema and operational contract. SSH hardening remains a separate
two-phase operation from normal application `apply`: phase one establishes
the locked admin account and public key, while phase two fails unless Ansible
is already connected through that verified admin account.

The Java CLI follows existing devtools conventions and is published as part of the Yano X JVM
tooling/distribution. Implemented command surface:

```text
yano-x-deploy init <directory>
yano-x-deploy artifact import <directory> --file <showcase.zip>
yano-x-deploy validate <directory>
yano-x-deploy render <directory> [--output <empty-directory>]
yano-x-deploy plan <directory>
yano-x-deploy apply <directory> --confirm <cluster-id>
yano-x-deploy bootstrap-anchors <directory> --confirm-network <network> [--chain <chain-id|all>]
yano-x-deploy monitoring <directory>
yano-x-deploy status <directory>
```

`artifact import`, `render`, and `validate` are offline. `plan` is read-only against cloud resources.
`apply` is bound to the cluster UUID and never submits Cardano transactions. `bootstrap-anchors` is
the only implemented L1-spending command: it requires the configured public network to be repeated
through `--confirm-network`, defaults to all locked chains, and is idempotent across reruns.
`monitoring` reconciles the selected Prometheus owner, validator-only scrape ingress, persistent
retention, HTTPS route, and five-target health gate without restarting Yano. Normal `apply` invokes
the same monitoring playbook after validator convergence when `monitoring.mode` is
`central-prometheus`.
Destructive infrastructure plans are rejected. Replacement and destroy commands remain unavailable
until their live-provider semantics are qualified. No command submits smoke traffic or performs
settlement bootstrap.

The release-matched showcase profile compiler derives the authenticated-map genesis inputs from the
selected member keys. The new published `:tooling:deployment` module is recorded once in
`config/artifacts-v1.json` and packaged under `tools/yano-deploy` in the combined JVM distribution.

## 13. Implementation plan

Phases 1–3 and the non-destructive plan/apply/status portion of phase 4 are implemented in this
change. Phases 0 and 5 require real accounts, billable resources, reachable hosts, and a named
operator deployment; they are qualification work rather than missing offline implementation.

### Phase 0 — provider and runtime spike

Use disposable, explicitly authorized projects and non-retained instances on each cloud to:

- pin/test OpenTofu and all three provider versions;
- validate live region/image/SSH-secret discovery and product-capacity failure behavior;
- prove firewall create/attach/update status and the exposure window;
- prove cloud-init, non-root SSH, IPv4/IPv6 policy, and host firewall boot order;
- test instance replace/delete/cancellation and billing semantics; and
- decide the first supported OS image through live discovery.

This phase costs money and may destroy only its named disposable resources; it requires explicit
operator authorization.

### Phase 1 — deployment schema, artifact import, lock, and offline compiler

- Implement strict JSON schemas and Java records/parser with duplicate-key/trailing-token rejection.
- Implement the bounded multi-chain deployment-profile contract and derive the initial profile from
  the packaged showcase catalog; do not weaken the existing single-chain project contract silently.
- Implement local showcase-ZIP import, provenance/SBOM/identity verification, content-addressed
  caching, and whole-archive checksum locking.
- Compile deterministic provider-neutral `NodePlan` inventory.
- Generate HCL, cloud-init, inventory, systemd, firewall, gateway, and validator scripts into a new
  empty non-symlink output directory.
- Generate `deployment.lock` and drift/change classification.
- Add secret scanning and golden deterministic rendering tests.

### Phase 2 — cloud and existing-host adapters

- Implement Contabo, Hetzner Cloud, and DigitalOcean instances, SSH-key references, active
  firewalls, public-address outputs, and the externally managed existing-host inventory.
- Implement exact provider pin/checksum and remote-backend documentation.
- Resolve cloud inventory through provider plans/outputs with redaction; add richer provider-native
  discovery as a follow-up where account APIs expose useful capacity facts.
- Add saved-plan policy checks for replace/destroy/reinstall and default `prevent_destroy`.
- Keep DNS external initially; emit records and verification commands.

### Phase 3 — host configuration and runtime packaging

- Implement hardened cloud-init and idempotent Ansible roles.
- Implement checksum-verified controller push and ZIP/systemd deployment from the imported showcase
  archive, including atomic activation and rollback to the prior artifact digest.
- Compile the active plugin allow-list from the locked profile and prove Kafka/S3/IPFS integrations
  are not installed or activated.
- Defer OCI mode until a later milestone; when added, build it from the verified archive and enable
  it only by manifest digest.
- Implement protected systemd credential/config delivery, non-root runtime, store separation,
  journald, backup jobs, TLS gateway, and optional Prometheus.
- Keep secrets out of OpenTofu state and automation logs; test failure redaction.

### Phase 4 — lifecycle and safety gates

- Implement deterministic render, plan, non-destructive apply, and status; add a resumable external
  journal after live interruption behavior is qualified.
- Gate API ingress on five-node convergence and close it on failed initial bootstrap.
- Implement non-destructive rolling service restart for node-local changes.
- Reject in-place consensus/genesis/plugin drift and direct infrastructure scaling as membership.
- Keep node replacement and full destroy unavailable until disposable exercises prove each
  provider's semantics and the verified snapshot/catch-up path exists.

### Phase 5 — five-node Preprod qualification

- Deploy one fresh five-node cluster across live-available regions and at least two infrastructure
  adapters; separately qualify an existing-VM-only cluster and all three cloud adapters.
- Measure global RTT and select block/proposer/round timing from evidence.
- Run root/profile/genesis/capability parity, finality, proof, catch-up, restart, and restore gates.
- Stop one member and prove continued 4-of-5 progress; stop two and prove safe halt.
- Exercise a 3/2 network partition and prove neither partition can finalize at threshold four.
- Scan public ports from outside, test IPv4 and IPv6, auth/rate limits, TLS, and log redaction.
- Test L1 relay/bootstrap behavior separately and record that it is not app-chain observer join.
- Exercise every active chain and its advertised capability, including representative proofs,
  governed flows, the local outbox effect, and Cardano-history reads/snapshots.
- Prove settlement is absent from the active chain set and Kafka/S3/IPFS services, ports,
  credentials, bundles, and catalog contributions are absent.
- Run load/soak within a documented Preprod pilot envelope and retain deployment evidence.

### Phase 6 — later extensions

- WireGuard transport adapter using the existing `transportAddress` seam.
- First-class Yano read-only observer support after a host ADR and implementation.
- mTLS/OIDC, KMS/HSM/Vault signer bundle, privileged-operation audit log, and HA monitoring.
- Additional infrastructure providers that compile from the same intermediate model.

## 14. Acceptance criteria

### 14.1 Offline and static

- Strict schema and semantic validation covers every requested user field.
- Region/product/image values are live-resolved and lock-pinned, not application enums.
- Identical sources produce byte-identical rendered output and lock files.
- Any source/appchain/artifact/provider/plugin/firewall change changes the correct lock digest.
- Artifact import rejects a failed showcase distribution contract, mismatched embedded Yano/Yano X
  identity, missing provenance/SBOM, checksum mismatch, or unapproved dirty-source build.
- Generated files, HCL plan JSON, cloud-init, logs, and test reports contain no private seed, API
  key, provider credential, anchor key, TLS key, or connector credential.
- OpenTofu format/validate and policy tests pass with an initialized provider lock.
- Ansible lint/idempotence and OS-image matrix tests pass.
- Reinstall/replacement/destruction plans are rejected without the dedicated workflow.

### 14.2 Infrastructure

- Requested node count equals placement count and live created instance count.
- Every node has the locked region/product/image and a distinct public address/member identity.
- Both cloud and host firewalls match the compiled matrix; no unintended port is reachable from an
  external probe over IPv4 or IPv6.
- SSH password/root access is disabled and operator access is CIDR-limited, except for an explicitly
  locked, externally probed, rate-limited `public-key-only` deployment.
- Re-running apply is a no-op after convergence.
- Partial instance/firewall/DNS failures resume safely without duplicate instances or broadened
  ingress.
- A normal plan cannot destroy/reinstall any retained validator.

### 14.3 Runtime and cluster

- Exact Yano/Yano X/artifact/plugin identities match on all five nodes.
- All nodes report the expected L1 network/genesis identity and separate three-store layout.
- Every node reports the exact locked 12-chain active set derived from the packaged 13-chain
  showcase catalog; settlement remains a deferred catalog entry until separately enabled.
- Every chain agrees on height, root, commitment profile, format fingerprint, genesis ID, consensus
  profile, membership epoch, threshold, and capability-manifest digest.
- Kafka, S3-effect, and IPFS bundles/contributions are absent from the active plugin catalog; no
  related service, credential, listener, or outbound destination is configured.
- Finality certificates and representative proofs verify independently.
- One node restart catches up; full stop/start preserves identity and parity.
- A restored member verifies snapshot identity/signature/root and catches up.
- Four-of-five progresses with one member unavailable; two unavailable halt safely.
- API gateways remain closed until convergence and enforce TLS, allow-list, broad auth, topic-scoped
  tester keys, rate bounds, and privileged-key separation.
- Monitoring detects stalled finality, peer loss, disk pressure, restart loops, backup age, and root
  divergence without becoming consensus-critical.

### 14.4 Explicit negative tests

- Unknown/unavailable region, product, image, or SSH secret fails before retained state mutation
  when discoverable.
- Core-only or tag-only image references fail artifact validation.
- Missing local artifact bytes, a source-revision-only reference without SHA-256, independently
  rebuilt node artifacts, or a checksum mismatch fails before service start.
- Mismatched member count/key assignment, threshold, node host, chain identity, plugin digest, or
  config digest fails before service start.
- A retained store under another cluster/genesis identity is never adopted or reset.
- `0.0.0.0/0` API submit exposure, plaintext external API, demo keys, demo API credentials, and
  secrets in cloud-init/OpenTofu variables are rejected.
- Checked-in devnet settlement parameters or public demo actor/operator keys are rejected for
  Preprod; settlement activation without its explicit ceremony and spend authorization is rejected.
- Observer configuration fails with the `CON-011` unsupported diagnostic.
- WireGuard mode fails as not implemented rather than silently falling back to public transport.

## 15. Risks and mitigations

| Risk | Consequence | Mitigation |
|---|---|---|
| Contabo product unavailable in a requested region | Partial cluster purchase | Live best-effort discovery, saved plan, resumable create, no app start until all nodes exist; operator may revise placement explicitly |
| Provider regression or destructive behavior | Reinstall, cancellation, billing surprise | Exact pin/checksum, disposable acceptance, plan classification, `prevent_destroy`, manual provider upgrades |
| Unreleased local artifact is irreproducible or differs by node | Unknown code runs in consensus | Separate verified build/import, clean-source provenance by default, whole-archive SHA-256, content-addressed cache, controller push, on-host verification |
| Broad showcase profile activates unsafe integrations | Credential leakage, unintended egress or L1 spending | Explicit plugin/chain allow-list, Kafka/S3/IPFS exclusion tests, one settlement owner, separate anchor/settlement transaction authorization |
| Public bootstrap node DoS | Relay/API degradation and possible validator resource pressure | Separate bootstrap/API roles where possible, connection/per-IP limits, rate controls, monitoring, no fixed proposer coupling |
| Global 4-of-5 latency | Slow/unstable finality | RTT/soak qualification; tune intervals/timeouts; document supported envelope |
| One region/provider-wide outage | Quorum loss if two nodes affected | Place no threshold-sized group in one failure domain; mixed providers are supported but not forced |
| Raw member seed on VPS | Root/host compromise can sign as a member | One seed per node, protected credentials, no central plaintext, rotation runbook; prioritize KMS/HSM signer work |
| Mutable DNS or replacement IP | Peer/API redirection or outage | DNS/instance facts lock-pinned, short bounded TTL, TLS, explicit replacement plan, live drift checks |
| API key theft | Read/submit abuse | TLS, allow-list, topic scope, rate limits, rotation, separate admin realm, no key logs; later mTLS/OIDC |
| Split infrastructure and consensus lifecycles | New VM mistaken for member | Membership sourced only from locked app-chain config/history; scaling never implies governance |
| Backup exists but is unusable | Irrecoverable authoritative state | Signed encrypted off-host snapshots plus scheduled restore rehearsal |

## 16. Alternatives considered

### 16.1 Extend the single-host launcher over SSH

Rejected. It owns local PIDs/ports/data directories and has safe single-host invariants, but remote
shell fan-out would not provide durable cloud state, replacement classification, firewall/DNS
reconciliation, or independent failure domains. Its tests should seed the new lifecycle tests.

### 16.2 Use the existing three-node Docker Compose scaffold

Rejected. It contains demo keys, assumes one Docker network/host, uses a single thin Yano image, and
does not model retained identity, cloud firewalls, TLS, secrets, geographic placement, or five-node
operations.

### 16.3 Deploy generated Helm/Kustomize output

Rejected for version one. The targeted VPSs and existing VMs do not supply a common managed Kubernetes control plane,
and adding one creates a larger system before bare-node lifecycle is understood. The existing GitOps
export remains useful for future Kubernetes consumers.

### 16.4 Cloud-init-only deployment

Rejected. Cloud-init is recorded in infrastructure state and runs before all peer/member facts are
known. Embedding secrets or self-coordination logic there creates secret leakage and fragile partial
genesis. Cloud-init is limited to secret-free host bootstrap.

### 16.5 Build only a Contabo-specific manifest

Rejected. It would mix cloud placement with Yano consensus and force a rewrite for WireGuard or a
second provider. The provider-neutral inventory is small and directly supports the requested future
overlay seam.

### 16.6 Add WireGuard immediately

Rejected by scope. Public transport is an explicit version-one decision. Address separation in the
intermediate model prevents that decision from becoming permanent architecture.

## 17. Consequences

Positive consequences:

- A new cluster is reproducible from reviewed, versioned, checksum-pinned inputs.
- Development can deploy before the first Yano X release without allowing cloud hosts to build from
  source or letting different nodes receive different bytes.
- One cluster demonstrates the curated showcase state-machine families and proof/capability surfaces
  while excluding Kafka, S3, and IPFS effects.
- Infrastructure identity, app-chain identity, and secrets remain separate.
- The implementation does not bind Yano consensus or deployment config to one cloud or to cloud ownership.
- Unsafe public API and false observer/bootstrap claims fail closed.
- Replacement, recovery, and destruction are designed before automation can erase retained state.
- A later WireGuard mode can reuse provisioning and lifecycle code.

Costs and limitations:

- The solution introduces OpenTofu, Ansible, remote state, and an operator secret backend.
- A single-provider placement remains exposed to provider-wide failures; mixed placement reduces
  that concentration but adds several provider control planes to operate.
- Public transport reveals metadata and increases DoS exposure.
- Raw member seeds remain on hosts until Yano gains a supported remote/hardware signer.
- External replicated app-chain observers remain blocked on core runtime work.
- The optional ZK EUTxO implementation requires a separate cluster/profile because it conflicts with
  the standard EUTxO contribution.
- Preprod settlement remains outside the active chain set until its separate validator/key/funding
  ceremony is delivered.
- Full geographic qualification incurs real cloud charges and operational lead time.

## 18. Open decisions before implementation

1. Which products/sizes and standard OS images are live-available in the selected Contabo, Hetzner,
   and DigitalOcean regions at qualification time?
2. Which remote-state backend and lock mechanism will the BloxBean team operate?
3. Which secret backend is mandatory for the first shared cluster: SOPS/age, Vault, or another
   existing team system?
4. Are tester CIDRs stable enough for allow-list mode, or is mTLS/OIDC required before external
   access?
5. Who owns DNS zones and which DNS adapter should follow the initial external-record workflow?
6. A central Prometheus on the selected `monitoring` validator is sufficient for the initial
   Preprod pilot. A separate non-validator monitoring instance remains a future topology option;
   moving the owner requires an explicit history-volume migration if retention must be preserved.
7. What machine-size floor is required for the 13 active showcase chains, full Cardano-history
   preset, authenticated snapshots, five-member consensus, and local monitoring?
8. Must local pre-release builds be clean-only, or may an explicitly diff-digested dirty build be
   used for a short-lived internal cluster?
9. Who owns the Preprod settlement validator/key/funding ceremony and authorizes its test-ADA
   transactions?

These decisions select the first operated environment; they do not block the provider-neutral
implementation. This change creates no cloud resources, mutates no existing VM, creates no DNS
record or public endpoint, submits no Cardano transaction, and does not touch retained cluster state.
