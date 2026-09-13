# SPI and manifest

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
| `plugins/<bundle-id>.json` | The executable runtime contributions — what this bundle actually provides to a running node, and the Yano API major and min/max levels it is compatible with. |
| `appchain-config-metadata-v1.json` | Typed configuration definitions: keys, types, defaults, allowed values, scope, change policy, and whether a value is secret. |
| `appchain-component-catalog-v1.json` | Selectable product capabilities and the artifacts they require — what a user sees in `appchain capabilities` and in Studio. |

They are deliberately separate. A tool can read the catalog to offer a
capability without loading any code, and a node can validate the runtime
manifest without interpreting product metadata.

## Activation

Runtime activation goes through `PluginProviderRegistry` plus the schema-v1
plugin manifest. There is no other path:

- no raw `ServiceLoader` discovery by the host,
- no direct host construction,
- no product switches inside the host, and
- no product-specific host CDI or REST activation.

The host loads a bundle from `yano.plugins.directory`, validates its manifest,
resolves declared dependencies, checks API compatibility, and only then
activates the contributions that configuration has selected.

Requirements a runtime plugin must satisfy:

| Requirement | Why |
|---|---|
| Dependency-complete bundle | The node must not have to resolve your transitive dependencies at runtime. |
| Does not embed host SPI classes | Embedding them creates two incompatible copies of the same interface. |
| Declares Yano API major and min/max level | A bundle built against an incompatible host fails closed rather than misbehaving. |
| Bounded lifecycle cleanup | Shutdown must actually release threads, connections, and files. |

## The trust envelope

The Ed25519 signature binds:

- the component catalog,
- the runtime manifest,
- the optional configuration metadata,
- the bundle identity and version, and
- the publisher key id.

It authenticates **those exact bytes**. Understanding what it does *not* do
matters just as much:

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

Neither command loads provider classes, runs plugin code, fetches a registry,
nor installs the JAR. The public key is not secret; distribute it freely.

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
`appchain explain` useful for third-party plugins. Each property declares its
type, default, bounds, allowed values, scope, change policy, and secret flag.

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

- ADR-011 in the repository's `adr/app-layer/` directory — the host/plugin SPI,
  catalog, compatibility, isolation, and lifecycle contract.
- [`core-host.md`](https://github.com/bloxbean/yano-x/blob/main/docs/core-host.md)
  — plugin query and domain API contract, plugin operations.
- [Composite implementation guide](https://github.com/bloxbean/yano-x/blob/main/composition/runtime/README.md)
- [Plugin template scaffold](https://github.com/bloxbean/yano-x/tree/main/scaffolds/plugin-template/)
