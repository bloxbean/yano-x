# Governed recipe fixtures

Realistic governed authenticated-map profiles and genesis builders, used only
as test fixtures for the binding, admission-rule and typed-view tests in
[`state-machines/stdlib`](../../state-machines/stdlib/README.md) and
[`tooling/devtools`](../../tooling/devtools/README.md) (ADR-055).

## Artifact

Not published (`test-fixture`, artifact id `yano-x-governed-recipes` in
[`config/artifacts-v1.json`](../../config/artifacts-v1.json)).

## Key contents

Each recipe has a profile (collections, policies and genesis-bound schemas), a
values helper and a genesis builder that uses the stock
`AuthenticatedMapGenesisFactory` and prints the four properties a node needs.
The same descriptor, members and threshold always yield the same genesis id.

| Package | Types | Recipe |
|---|---|---|
| `org.yanoproject.x.trust.profile` | `TrustRegistryProfile`, `TrustRegistryGenesis`, `TrustRegistryValues` | [ADR-049](../../adr/049-trust-and-status-registry.md) trust and status registry |
| `org.yanoproject.x.dpp.profile` | `DppStarterProfile`, `DppGenesis`, `DppValues` | [ADR-051](../../adr/051-digital-product-passport-starter.md) digital product passport starter |
| `org.yanoproject.x.feed.profile` | `FeedStarterProfile`, `FeedGenesis`, `FeedValues`, `Aggregation` | [ADR-052](../../adr/052-data-attestation-feed-starter.md) attestation feed starter |

Nothing here executes on chain or enforces a product lifecycle; the profiles only
fix what a genesis declares and how keys and values are laid out.

## Dependencies / boundaries

`api`: `state-machines:stdlib-contracts`, `capabilities:role-workflow-contracts`;
`implementation`: `state-machines:stdlib`. Only `testImplementation` edges may
depend on this module; production modules and published artifacts must not.

## Build & test

```bash
./gradlew :fixtures:governed-recipes:test
```

Add the Yano inputs from [Build and test](../../docs/BUILD_AND_TEST.md) when the
pinned Yano version is local or staged.
