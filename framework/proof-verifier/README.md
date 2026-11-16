# Application-ledger proof verifier

Implementation-neutral, release-matched verification of native app-chain state
proofs. It is a plain library with no Quarkus or node runtime, so a REST server,
a client SDK or an offline tool verifies proofs with exactly the same code.

## Artifact

`org.yanoproject.x:yano-x-appchain-proof-verifier` (`library`). It replaces Yano's
former `org.yanoproject:yano-appchain-proof-verifier`; see the
[ADR-064 migration notes](../../docs/appchain/MIGRATION-ADR-064.md).

## Key contents

Package `org.yanoproject.x.framework.proof`:

- `AppChainProofVerifier`: profile-dispatched `verify(...)` of a key's presence,
  tombstone or absence against an independently trusted root, plus
  `verifyMpfInclusion`, `verifyMpfExclusion` and `verifyClassicJmt`. Supported
  profiles are `mpf-blake2b256-v1` and `jmt-blake2b256-v1`; the declared
  `jmt-poseidon-bls12381-v1` profile fails closed. Keys are bounded to 256 bytes,
  values and proof wires to 1 MiB.
- `MpfProofWirePreflight`: non-recursive structural preflight of the MPF proof CBOR
  before it reaches the upstream verifier.
- `TopicPolicyProofVerifier`: offline verification of `topic-policy-v1` proofs
  against a trusted parent root (`verify`, `verifyDigest`).

The caller supplies the trusted root (from a verified finality certificate, local
chain or Cardano anchor); this module never fetches or trusts a node's root.

## Dependencies / boundaries

`api`: `framework/api` and cardano-client MPF and JMT. It must stay free of
Quarkus, the Yano runtime and the framework engine. Consumed by
[`framework/rest`](../rest/README.md), [`sdk/client`](../../sdk/client/README.md)
and the `framework/cardano` integration tests.

## Build & test

```bash
./gradlew :framework:proof-verifier:test
```

Add the Yano inputs from [Build and test](../../docs/BUILD_AND_TEST.md) when the
pinned Yano version is local or staged.
