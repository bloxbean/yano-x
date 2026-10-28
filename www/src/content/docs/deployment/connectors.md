---
title: "Optional first-party connectors"
description: "Kafka, S3-compatible storage, IPFS/Kubo, and Cardano payments are maintained Yano integrations, but they are not present in the stock distribution. They…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/appchain/OPTIONAL_CONNECTORS.md"
---
Kafka, S3-compatible storage, IPFS/Kubo, and Cardano payments are maintained
Yano integrations, but they are not present in the stock distribution. They
are `FIRST_PARTY_OPTIONAL`: selecting their capability creates an artifact and
configuration requirement; it does not install a plugin or provision an
external service.

## Packaging matrix

| Integration | Capabilities | JVM bundle task | External ownership |
|---|---|---|---|
| Kafka | `executor:kafka`, `sink:kafka` | `:connectors:kafka:shadowJar` | Brokers, TLS/SASL identity, credentials, topic ACLs and retention |
| S3-compatible object storage | `executor:objectstore-s3` | `:connectors:objectstore-s3:shadowJar` | Versioned buckets, create-only policy, credentials, encryption and retention |
| IPFS/Kubo | `executor:ipfs` | `:connectors:ipfs:shadowJar` | Kubo API identity, authentication, pin durability and replication |
| Cardano payments | `executor:cardano-payment` | `:connectors:effects-cardano:shadowJar` | Funded payer, signing-key custody, backend trust, caps and reconciliation |

Build one JVM plugin from the repository root, for example:

```bash
./gradlew :connectors:kafka:shadowJar
```

Copy only the resulting `*-bundle.jar` into the `plugins/` directory of the
extracted Yano X distribution. Keep the plugin version aligned with the Yano
release:

```bash
cp connectors/kafka/build/libs/*-bundle.jar ~/yano-x/yano-x-jvm-*/plugins/
```

`appchain seal` records the SHA-256 of every JAR in `plugins/`, and
`appchain join` and `appchain start` refuse a distribution whose `plugins/`
has a JAR added, removed, or replaced. For a new application, every operator,
the coordinator included, installs the same connector JAR before `seal`. To
add one to a running application, the operators agree on it, and each copies
the identical JAR, runs `appchain stop`, then
`appchain join <app>.chain --accept-bundles`, then `appchain start`.

The setup commands read only `<extracted Yano X>/plugins/`. On a node set up
with `appchain join`, do not point `yano.plugins.directory` anywhere else: the
node would load bundles the sealed set does not cover.

Do not copy a thin build-time JAR into the JVM plugin directory. These connector
bundles are JVM-only and cannot be installed beside Yano's native executable.

## Configuration boundary

Consensus records contain a stable effect type, target alias, and bounded
action payload. Endpoints, credentials, wallet keys, TLS material, bucket
names, and broker/Kubo/backend policy are node-local. Never put them into an
effect payload, setup draft, sealed `.chain` file, or shared consensus YAML.

On a node set up with `appchain join`, put a connector's node-local settings
in `config/local.yaml` in the node directory. `join` creates that file and
never rewrites it, and its values win over the generated `config/node.yaml`.
`start` refuses it if it sets any sealed consensus setting. Supply credentials
through the environment or a secret provider, not in the file itself.

At-least-once execution is intentional. Targets must use the effect ID as an
idempotency key or provide the connector's documented reconciliation
semantics. An accepted effect is not proof that the external system acted;
verify the incorporated result and the external receipt.

## Kafka

Kafka contributes two independent features:

- `sink:kafka` publishes finalized app blocks; and
- `executor:kafka` executes allowlisted `kafka.publish` effects.

The sink and executor have separate configuration. Both require a node-local
security profile and operator-owned topic policy. See the
[Kafka integration guide](https://github.com/bloxbean/yano-x/blob/main/connectors/kafka/README.md).

## S3-compatible object storage

The `object.put` executor promotes a pre-staged, hash-verified object into a
versioned immutable destination. The target configuration owns endpoints,
buckets, credentials, encryption and retention. Its create-only bucket-policy
and version-history requirements are part of correctness, not optional
hardening. See the
[object-store guide](https://github.com/bloxbean/yano-x/blob/main/connectors/objectstore-s3/README.md).

## IPFS/Kubo

The `ipfs.pin` executor pins an already known CID. It does not add document
bytes, derive a CID, expose a gateway, or promise permanent replication. See
the [IPFS guide](https://github.com/bloxbean/yano-x/blob/main/connectors/ipfs/README.md).

## Cardano payments

The `cardano.payment` executor controls material funds and therefore has a
stricter readiness boundary. Use an operator-controlled payer, explicit
network/backend, transaction cap, protected signing credential, and a funded
testnet rehearsal before production. See the
[Cardano effects guide](https://github.com/bloxbean/yano-x/blob/main/connectors/effects-cardano/README.md).

## Release and demo distinction

The evidence demo stages Kafka, S3 and IPFS plugins and local services to prove
the complete scenario. That does not make those plugins bundled in the normal
Yano distribution. Always use the release capability catalog
(`./yano.sh appchain capabilities`) and the bundles in the distribution's
`plugins/` directory, not the contents of a demo Compose environment, as
packaging truth.
