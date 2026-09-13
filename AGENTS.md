# AGENTS.md

This file gives Codex durable project context for the Yano X repository.

## Project mission

Yano X is the Java 25, JVM-only extension ecosystem for Yano app chains. It
contains optional state machines, capabilities, composition, connectors,
products, SDKs, examples, tooling, on-chain artifacts, and the batteries-
included JVM distribution.

The sibling Yano repository is the upstream host:

- `/Users/satya/work/bloxbean/yano`: Cardano data node, minimal app-chain host,
  public plugin SPI, generic runtime/REST/operations, anchoring/proof support,
  and OrderedLog as the only built-in state machine. GraalVM is Yano's primary
  deployment target, though it also publishes Java artifacts and its ordinary
  JVM ZIP for Yano X.
- `/Users/satya/work/bloxbean/yano-x`: JVM extensions and products. Dependency
  direction is strictly `yano-x -> yano`.

Do not add a source checkout dependency, composite Gradle build, sibling task
invocation, or generated-file dependency from Yano X to Yano. Consume an exact
published Yano version and its matching ordinary JVM ZIP.

## Architecture invariants

- Every optional behavior added to a running Yano node crosses the plugin
  catalog boundary. This includes stock state machines, independently selected
  capabilities, connectors, effects, observers, indexers, and product runtime
  behavior.
- Pure libraries, DTOs, clients, codecs, testkits, CLIs, on-chain validators,
  and deterministic helpers do not need to be runtime plugins unless the host
  independently selects or manages them.
- Runtime activation goes through `PluginProviderRegistry` and a schema-v1
  plugin manifest. Do not add raw `ServiceLoader`, direct host construction,
  product switches, or product-specific host CDI/REST activation paths.
- Runtime plugins publish dependency-complete bundles, do not embed host SPI
  classes, declare compatible Yano API major/min/max-level constraints, and
  have bounded lifecycle cleanup.
- Package names intentionally remain under
  `org.yanoproject.x.*`; repository and artifact names use
  `yano-x`.
- The plugin directory property is `yano.plugins.directory`. Do not reintroduce
  `yaci.plugins.directory`.
- Yano X is JVM-only. Do not add GraalVM/native-image tasks, reachability
  metadata, or native executables. `verifyJvmOnlyBuild` enforces this boundary.
- UI currently remains a deliberate exception in Yano. Do not move or fork it
  here without a new decision.
- The app-chain feature has not had a public release. Remove obsolete adapters,
  aliases, duplicate activation paths, and other prerelease technical debt now;
  do not preserve accidental compatibility. Preserve documented Cardano node,
  OrderedLog, wire/storage, proof, replay, and distribution invariants.

## Documentation layout

- `docs/` is the markdown documentation corpus and the **source of truth**. It
  also ships inside the JVM distribution. Edit documentation here.
- `www/` is the published Astro + Starlight site (yano-x.io). It imports the
  tutorials and state-machine references from `docs/` at build time and
  generates its catalog tables from the JSON catalogs, so never hand-edit an
  imported page or content between `<!-- catalog:... -->` anchors.

See `adr/038-yano-x-documentation-site.md`.

## Sources of truth

Read only the references relevant to the task:

- Repository split and ownership: `adr/refactoring/030-repository-split-yano-x-execution-plan.md`
- Split evidence and closeout: `adr/refactoring/baselines/030-phase-*.md`
- App-chain decisions and open work: `adr/app-layer/` and
  `adr/app-layer/open_item.md`
- Module topology: `settings.gradle`
- Artifact IDs, publication types, runtime bundle IDs: `config/artifacts-v1.json`
- Build workflows: `docs/BUILD_AND_TEST.md` and `docs/BUILD_DISTRIBUTIONS.md`
- User-facing architecture: `docs/APP_CHAIN_OVERVIEW.md` and
  `docs/APP_CHAIN_USER_GUIDE.md`
- Task-oriented docs: `docs/appchain/README.md`
- Host/core boundary: `docs/core-host.md`
- Showcase operation: `examples/showcase/DEMO_SHOWCASE.md`
- Plugin implementation: `docs/appchain/tutorials/08-plugins-and-composites.md`

Do not duplicate the artifact inventory in build logic or documentation. Update
`config/artifacts-v1.json` and its verification tests when module identity
changes.

## Build and test

Java 25 is required. Maven Local is disabled unless explicitly enabled with
`-PuseMavenLocal=true`.

For coordinated local development, first publish the exact Yano inputs:

```bash
cd /Users/satya/work/bloxbean/yano
./gradlew publishToMavenLocal :app:yanoDistZip \
  -PskipSigning=true --no-parallel
```

Then run the normal Yano X source and boundary gates:

```bash
cd /Users/satya/work/bloxbean/yano-x
./gradlew test verifyArtifactInventory verifyJvmOnlyBuild \
  -PyanoVersion=<published-yano-version> \
  -PuseMavenLocal=true --offline
```

Useful focused gates:

```bash
./gradlew :state-machines:stdlib:test -PyanoVersion=<yano-version>
./gradlew :tooling:devtools:test -PyanoVersion=<yano-version>
./gradlew integrationTest -PyanoVersion=<yano-version>
./gradlew cryptoTest -PyanoVersion=<yano-version>
```

The root `build` verifies distributions from the same dependency-complete
bundle JARs attached to Yano X's Maven publications. A released `yanoVersion`
resolves its matching ordinary JVM ZIP from the `bloxbean/yano` GitHub release
automatically:

```bash
./gradlew clean build \
  -PyanoVersion=<published-yano-version> \
  -PskipSigning=true
```

For a release rehearsal, also publish to a new empty isolated repository:

```bash
YANO_X_STAGING=/absolute/path/to/new-empty-directory

./gradlew publishAllPublicationsToInternalRepository \
  -PinternalRepository="$YANO_X_STAGING" \
  -PyanoVersion=<published-yano-version> \
  -PskipSigning=true
```

The Yano Maven version and JVM ZIP identity must match exactly. For local or
staged Yano development, explicitly supply the matching `-PyanoJvmDist` and
enable the appropriate Maven Local or staging repository input.

## Testing expectations

Choose gates in proportion to the change, but do not stop at unit tests when a
plugin, catalog, distribution, persistence, consensus, proof, anchor, or
cross-node behavior changed.

- Run focused unit tests while iterating.
- Run `verifyArtifactInventory` for any module, publication, manifest, bundle,
  or contribution change.
- Run `verifyJvmOnlyBuild` for build topology changes.
- Run integration/crypto suites when their domains change.
- Run `distributionCheck` or a clean `build` for dependency,
  bundle, class-isolation, launch, or packaging changes.
- Run showcase script and distribution contracts for showcase changes:

  ```bash
  ./gradlew :examples:showcase:showcaseScriptContract \
    :examples:showcase:showcaseDistributionContract <required-properties>
  ```

- For runtime changes, validate a real multi-node cluster: identical chain
  height/root/profile/genesis/capability-manifest digest, finality certificates,
  proof retrieval, catch-up/restart, and anchor state where applicable.
- Persistence changes require apply, rollback, replay, restart, and root-parity
  checks. Derived indexes must never advance beyond authoritative app-chain
  state and must remain separate from L1 `chainstate`.
- Before a release, and after distribution or Yano version changes, smoke-test
  the built ZIP with
  `.agents/skills/smoke-yano-x-release/scripts/smoke_release_zip.sh`. It runs
  the packaged multi-node acceptance gates, which `build` and CI do not, plus a
  live showcase with restart.

The pre-split low-level `test-app-chain-*` skills in the Yano repository are
historical evidence, not directly runnable Yano X procedures. Port their
invariants to the packaged showcase or current plugin APIs instead of reusing
old monorepo paths and configuration blindly.

## Showcase and retained state

The current side-by-side local Yano X deployment is:

- root: `/Users/satya/Downloads/yano-cluster/devnet-cluster-x`
- instance: `devnet-x`
- HTTP: `7170-7172`
- N2N: `14337-14339`
- runbook: `DEVNET_CLUSTER.md` inside that deployment

If that retained runbook still points to the historical validator under the
Yano repository, use the Yano X-owned validator at
`.agents/skills/deploy-showcase-devnet-cluster/scripts/validate_cluster.sh`.
Keep the runbook's build-provenance values as a record of what is deployed.

Treat these as retained identity and data, not disposable test output. `stop`
preserves state; `reset --yes` is destructive. Never replace, reset, or
regenerate an existing deployment unless the user explicitly requests it and
the exact target has been reviewed.

Each node has three sibling stores:

- `chainstate/`: authoritative Cardano L1 state
- `appchain-chainstate/`: authoritative app-chain state
- `appchain-indexers/`: rebuildable local read indexes

Never restore `appchain-indexers` as authoritative state or place derived
app-chain indexes below L1 `chainstate`. Never regenerate a retained
`genesis-id`; `(commitment-profile, format-fingerprint, genesis-id)` is a
pinned chain identity.

Preprod operations can submit real test-network transactions and spend test
ADA. Require explicit user authorization before fresh deployment, anchor
bootstrap, settlement bootstrap, or smoke traffic. Never print seed contents,
signing material, API credentials, or effect secrets.

## Coding conventions

- Java 25, four-space indentation, lines at most 120 characters.
- Prefer package imports to fully qualified names.
- Use SLF4J for logging.
- Tests use JUnit 5, Mockito, and AssertJ.
- Unit tests live in `src/test/java`; integration tests live in the configured
  integration-test source sets and normally use `*IT` names.
- License: MIT.

For deterministic state, avoid wall-clock time, randomness, environment-
dependent iteration/order, network calls, or node-local mutable decisions.
Version any change to consensus semantics, state encoding, commitment profile,
proof subject, or genesis-selected configuration.

## Working-tree and Git safety

- Inspect `git status --short` before editing. Preserve unrelated or
  overlapping user changes.
- Never stage or commit automatically. Only stage or commit when the user
  explicitly asks. Before committing, show the exact files/diff intended and
  obtain approval unless that exact commit was already explicitly requested.
- Do not modify the sibling Yano repository as a side effect of a Yano X task
  unless it is explicitly in scope.
- Do not delete retained clusters, state, keys, or staging repositories without
  reviewing the exact path and obtaining explicit authorization.
- Keep ADRs, implementation, tests, artifact inventory, and user docs aligned
  when architecture or behavior changes.
