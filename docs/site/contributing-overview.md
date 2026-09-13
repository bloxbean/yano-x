# Developing Yano X

This is the **contributor** track. If you only want to run Yano X, you want
[Release downloads](/start-here/release-downloads/) instead.
[Build from source](/start-here/build-from-source/) is available for unpublished changes.

You are here because you are changing Yano X itself, or changing Yano and Yano X
together.

## Two repositories

```text
bloxbean/yano     the host: Cardano data node, minimal app-chain host,
                  public plugin SPI, generic runtime/REST/operations,
                  anchoring and proofs, OrderedLog. GraalVM is its primary
                  deployment target; it also publishes Java artifacts and an
                  ordinary JVM ZIP for Yano X.

bloxbean/yano-x   JVM extensions and products. Dependency direction is
                  strictly yano-x -> yano.
```

:::danger[The boundary is a hard rule]
Do **not** add a source-checkout dependency, a composite Gradle build, a sibling
task invocation, or a generated-file dependency from Yano X to Yano. Consume an
exact published Yano version and its matching ordinary JVM ZIP.

Do not modify the sibling Yano repository as a side effect of a Yano X task
unless that is explicitly in scope.
:::

## Coordinated local development

When your change spans both repositories, publish the Yano inputs first:

```bash
# In the Yano repository.
cd /path/to/yano
./gradlew publishToMavenLocal :app:yanoDistZip -PskipSigning=true --no-parallel
```

Then build Yano X against exactly that version:

```bash
cd /path/to/yano-x
./gradlew test verifyArtifactInventory verifyJvmOnlyBuild \
  -PyanoVersion=<published-yano-version> \
  -PuseMavenLocal=true --offline
```

`mavenLocal()` is disabled unless `-PuseMavenLocal=true`. The Maven version and
the JVM ZIP identity must match exactly; for a SNAPSHOT or staged version also
pass `-PyanoJvmDist=/absolute/path/to/yano-<build-identity>.zip`.

## The gates

Choose gates in proportion to the change, but **do not stop at unit tests** when
a plugin, catalog, distribution, persistence, consensus, proof, anchor, or
cross-node behavior changed.

```bash
# Focused, while iterating.
./gradlew :state-machines:stdlib:test -PyanoVersion=<v>
./gradlew :tooling:devtools:test      -PyanoVersion=<v>
./gradlew integrationTest             -PyanoVersion=<v>
./gradlew cryptoTest                  -PyanoVersion=<v>

# A full clean build, which also validates the release archives.
./gradlew clean build -PyanoVersion=<v> -PskipSigning=true

# Release rehearsal: publish every coordinate to a NEW EMPTY directory.
./gradlew publishAllPublicationsToInternalRepository \
  -PinternalRepository=/absolute/path/to/empty/yano-x-staging \
  -PyanoVersion=<v> -PskipSigning=true
```

| Gate | Run it when |
|---|---|
| `verifyArtifactInventory` | Any module, publication, manifest, bundle, or contribution change. Checks that each module has exactly one declared artifact identity and that runtime plugins have bundle publications. |
| `verifyJvmOnlyBuild` | Build topology changes. Rejects accidental native-image tasks. |
| `integrationTest` / `cryptoTest` | Their domains changed. |
| `distributionCheck` or a clean `build` | Dependency, bundle, class-isolation, launch, or packaging changes. |
| Showcase contracts | Showcase changes: `./gradlew :examples:showcase:showcaseScriptContract :examples:showcase:showcaseDistributionContract` |

### Runtime and persistence changes

Validate a **real multi-node cluster**: identical chain height, root, profile,
genesis, and capability-manifest digest across members; finality certificates;
proof retrieval; catch-up and restart; and anchor state where applicable.

Persistence changes additionally require apply, rollback, replay, restart, and
root-parity checks. Derived indexes must never advance beyond authoritative
app-chain state, and must remain separate from L1 `chainstate`.

## Architecture invariants

These are the rules a reviewer will hold you to. The full list lives in
[`AGENTS.md`](https://github.com/bloxbean/yano-x/blob/main/AGENTS.md).

- Every optional behavior added to a running node crosses the plugin catalog
  boundary — stock state machines, capabilities, connectors, effects, observers,
  indexers, and product runtime behavior.
- Pure libraries, DTOs, clients, codecs, testkits, CLIs, on-chain validators,
  and deterministic helpers are **not** runtime plugins unless the host
  independently selects or manages them.
- Runtime activation goes through `PluginProviderRegistry` and a schema-v1
  manifest. No raw `ServiceLoader`, direct host construction, product switches,
  or product-specific host CDI/REST activation.
- Runtime plugins publish dependency-complete bundles, do not embed host SPI
  classes, declare compatible Yano API major and min/max levels, and have
  bounded lifecycle cleanup.
- Package names remain `org.yanoproject.x.*`; repository and
  artifact names use `yano-x`.
- The plugin directory property is `yano.plugins.directory`. Do not reintroduce
  `yaci.plugins.directory`.
- Yano X is JVM-only.
- The app-chain feature has not had a public release: remove obsolete adapters,
  aliases, and duplicate activation paths rather than preserving accidental
  compatibility. Preserve documented Cardano node, OrderedLog, wire/storage,
  proof, replay, and distribution invariants.
- Do not duplicate the artifact inventory in build logic or documentation.
  Update `config/artifacts-v1.json` and its verification tests.

## Coding conventions

- Java 25, four-space indentation, lines at most 120 characters.
- Prefer package imports to fully qualified names.
- SLF4J for logging.
- JUnit 5, Mockito, AssertJ. Unit tests in `src/test/java`; integration tests in
  the configured integration source sets, normally named `*IT`.
- License: MIT.

For deterministic state, avoid wall-clock time, randomness,
environment-dependent iteration order, network calls, and node-local mutable
decisions. Version any change to consensus semantics, state encoding,
commitment profile, proof subject, or genesis-selected configuration — see
[Consensus rules](/plugins/consensus-rules/).

## Working-tree and Git safety

- Inspect `git status --short` before editing. Preserve unrelated or overlapping
  user changes.
- Never stage or commit automatically. Before committing, show the exact files
  and diff intended and get approval.
- Do not delete retained clusters, state, keys, or staging repositories without
  reviewing the exact path and obtaining explicit authorization. `stop`
  preserves state; `reset --yes` is destructive.
- Keep ADRs, implementation, tests, artifact inventory, and user docs aligned
  when architecture or behavior changes.
- Preprod operations submit real test-network transactions and spend test ADA.
  Require explicit authorization before fresh deployment, anchor bootstrap,
  settlement bootstrap, or smoke traffic. Never print seed contents, signing
  material, API credentials, or effect secrets.

## Working on this documentation site

The site lives in `www/` and is independent of the Gradle build — it reads
repository text files only, so it needs no Java and no Yano artifacts.

```bash
cd www
npm ci
npm run dev      # imports repo docs, then serves on localhost:4321
npm run build    # imports, builds to dist/, generates the AI artifacts
```

`npm run import` regenerates the imported tutorial and state-machine pages from
`docs/`, mirrors the App-Chain Studio into `public/studio/`, and re-renders every
`<!-- catalog:... -->` block from the repository's JSON catalogs. An unresolvable
relative link in an imported document **fails the build** rather than shipping a
404.

Edit the sources under `docs/` for imported pages, and
`www/src/content/docs/` for authored ones. Never hand-edit content between
catalog anchors — it is regenerated.

The site's content strategy and design decisions are recorded in ADR-038, in
the repository's `adr/` directory.

## Sources of truth

Read only what the task needs:

| Topic | Source |
|---|---|
| Repository split and ownership | ADR-030, in `adr/refactoring/` |
| Plugin SPI and lifecycle | ADR-011, in `adr/app-layer/` |
| Composition and portable proofs | ADR-031, in `adr/app-layer/` |
| Module topology | [`settings.gradle`](https://github.com/bloxbean/yano-x/blob/main/settings.gradle) |
| Artifact ids and bundle ids | [`config/artifacts-v1.json`](https://github.com/bloxbean/yano-x/blob/main/config/artifacts-v1.json) |
| Build workflows | [BUILD_AND_TEST](https://github.com/bloxbean/yano-x/blob/main/docs/BUILD_AND_TEST.md), [BUILD_DISTRIBUTIONS](https://github.com/bloxbean/yano-x/blob/main/docs/BUILD_DISTRIBUTIONS.md) |
| Open work | `adr/app-layer/open_item.md` |
