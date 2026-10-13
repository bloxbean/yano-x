---
title: "SPI and manifest"
description: "A plugin JAR is not just code with a ServiceLoader entry. It carries three independent, bounded contracts, and an Ed25519 envelope that binds them."
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/plugins-spi-and-manifest.md"
---
A plugin JAR is not just code with a `ServiceLoader` entry. It carries three
independent, bounded contracts, and an Ed25519 envelope that binds them.

## The three contracts

```text
shipment-yano-plugin.jar
└── META-INF/yano/
    ├── plugins/plugin-bundle.shipment.json        runtime contributions
    ├── appchain-config-metadata-v1.json           typed configuration (optional)
    ├── appchain-component-catalog-v1.json         selectable product capabilities
    └── appchain-component-catalog-v1.sig.json     Ed25519 trust envelope
```

| File | Owns |
|---|---|
| `plugins/<bundle-id>.json` | The executable runtime contributions — what this bundle actually provides to a running node — and its `yanoApi` range: the `min` and `max` API majors it supports and the `minLevel` it needs. |
| `appchain-config-metadata-v1.json` | Typed configuration definitions: keys, types, defaults, allowed values, scope, change policy, and whether a value is secret. |
| `appchain-component-catalog-v1.json` | Selectable product capabilities and the artifacts they require — what a user sees in `appchain capabilities` and in Studio. |

They are deliberately separate. A tool can read the catalog to offer a
capability without loading any code, and a node can validate the runtime
manifest without interpreting product metadata.

## Activation

Runtime activation goes through `PluginProviderRegistry` plus the schema-v1
plugin manifest. There is no other path:

- no raw `ServiceLoader` activation outside the catalog,
- no direct host construction,
- no product switches inside the host, and
- no product-specific host CDI or REST activation.

At start-up the host snapshots each JAR in `yano.plugins.directory`, checks its
manifest and ServiceLoader entries without loading code, checks API
compatibility, applies the allow and deny lists, builds one shared loader,
correlates providers, orders bundles by their declared dependencies, and only
then constructs the contributions that configuration has selected.
[How plugins load](/plugins/how-plugins-load/) steps through each check and the
error each one reports.

A manifest declares its API range like this:

```json
"yanoApi": { "min": 3, "max": 3, "minLevel": 12 }
```

`min` and `max` bound the API **major**; `minLevel` is the lowest API **level**
the bundle needs. There is no maximum level: a host at a higher level accepts
the bundle.

Requirements a runtime plugin must satisfy:

| Requirement | Why |
|---|---|
| Dependency-complete bundle | The node must not have to resolve your transitive dependencies at runtime. |
| Does not embed host SPI classes | Embedding them creates two incompatible copies of the same interface. |
| Declares `yanoApi {min, max, minLevel}` | A bundle built against an incompatible host fails closed rather than misbehaving. |
| Bounded lifecycle cleanup | Shutdown must actually release threads, connections, and files. |

## The trust envelope

The Ed25519 signature binds:

- the component catalog,
- the runtime manifest,
- the optional configuration metadata,
- the bundle identity and version, and
- the publisher key id.

It authenticates **those exact bytes**. Understanding what it does *not* do
matters just as much. Tooling checks the signature (`plugin validate`,
`plugin inspect`, `metadata verify`, project rendering and `doctor`); a running
node does not check it at start-up.

:::caution[Signing is not approval]
A valid signature does not approve the code, and it does not elevate a custom
component to `BUNDLED`, `stable`, or native status. Custom entries remain
JVM-only `REFERENCE` or `EXPERIMENTAL`. All release id, namespace, and artifact
collisions fail closed.
:::

Verification is offline and code-free:

```bash
./yano.sh appchain plugin inspect  <jar> --trust-key <key-id>=<64-hex-public-key>
./yano.sh appchain plugin validate <jar> --trust-key <key-id>=<64-hex-public-key> \
  --output catalog-snapshot.json
./yano.sh appchain metadata verify <jar> --trust-key <key-id>=<key-hex>
```

None of these commands loads provider classes, runs plugin code, fetches a
registry, or installs the JAR. The public key is not secret; distribute it
freely.

## Pinning, in a project

When a project selects a custom capability, `appchain.lock` pins:

- the signed, data-only catalog snapshot under `component-catalogs/`;
- the catalog, runtime manifest, and configuration-metadata digests; and
- the complete plugin-JAR digests.

`render` and `doctor` reverify the snapshot automatically, and a JAR that does
not match the pinned digest fails artifact readiness. That is how a
"works on my node" plugin mismatch becomes a build error rather than a stalled
chain.

## Configuration metadata and coverage

Typed configuration metadata is what makes `appchain config validate` and
`appchain config explain` useful for third-party plugins. Each property declares
its type, default, bounds, allowed values, scope, change policy, and secret flag.

Coverage is reported honestly. Treat custom-plugin metadata as **`PARTIAL`**
unless Yano reports `FULL` coverage, and verify the signed metadata and its
runtime-manifest binding before trusting a third-party artifact.

The scope field is the one to read carefully:

| Scope | Meaning |
|---|---|
| `CONSENSUS_SHARED` | Must be identical on every member. A mismatch diverges the state root. |
| Node-local | Ports, storage, credentials, executor placement. Safe to differ. |

And the change policy:

| Policy | Meaning |
|---|---|
| `NEW_CHAIN_REQUIRED` | The value is part of chain identity. Changing it requires a new chain. Governed activation applies only to settings that explicitly support it. |
| Others | See the generated [configuration reference](/reference/configuration/). |

## Which SPI do you need?

| You want to… | Implement |
|---|---|
| Interpret new message bodies and own new state | `AppStateMachine` + `AppStateMachineProvider` |
| Arrange existing components in a new committed order | A composite profile provider |
| Perform an authorized external action | An effect executor |
| Deliver finalized blocks somewhere | A finalized-stream sink |
| Expose a bounded read surface over your state | A domain API and committed queries |
| Prove an application-level fact | `ProofSubjectProvider` |
| Hold keys outside the node | A signer |
| React to Cardano deposits or metadata labels | An L1 observer |

For a proof subject, put its `descriptorDigest` in the capability manifest; the
runtime activates the provider only when subject id, version, component id, and
digest all match. Resolution must be deterministic and side-effect free — see
[State and proofs](/concepts/state-and-proofs/).

## Deeper reading

- [How plugins load](/plugins/how-plugins-load/) — the start-up checks, the
  shared loader, and the trust boundaries.
- Yano's [manifested bundle catalog ADR](https://github.com/bloxbean/yano/blob/main/adr/app-layer/011.2-manifested-bundle-catalog.md)
  — the host's catalog, compatibility, isolation and lifecycle contract.
- Yano's [plugin query and domain API guide](https://github.com/bloxbean/yano/blob/main/docs/APP_CHAIN_PLUGIN_QUERY_AND_DOMAIN_API.md)
  and [plugin operations guide](https://github.com/bloxbean/yano/blob/main/docs/PLUGIN_OPERATIONS.md).
- [Composite implementation guide](https://github.com/bloxbean/yano-x/blob/main/composition/runtime/README.md)
- [Plugin template scaffold](https://github.com/bloxbean/yano-x/tree/main/scaffolds/plugin-template/)
