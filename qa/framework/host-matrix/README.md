# Packaged thin-host qualification

This QA-only Maven consumer exercises the public Yano embedding API with an independently selected X framework. It does not modify the integrated Quarkus distribution, add a CLI/configuration parser, or load a new framework over an older uber-JAR. Ordinary embedding deployments compose the same thin artifacts on one classpath.

Prerequisites: Java 25, Python 3, and externally published/staged immutable Yano, Host API, X and Yaci artifacts. No Maven Local, sibling checkout, composite build or production source output is consumed. The existing wrapper only runs the isolated resolver in this directory. Supply exact versions and repository URLs in a copy of `inputs.example.json`. Each host row pins every Yano module, with separately selected foundation and Host API versions. The host has no X dependency (ADR-064); X is explicitly supplied at one selected framework version.

The required `yaciCoreVersion` input explicitly selects the coordinated app-only transport candidate. The resolver verifies that exact selected version and records its SHA-256 with the other inputs; `yaci-helper` retains its published dependency/version. This override is visible qualification input because the staged foundation POM still names the original Yaci core. The final production release must pin the actual upstream version containing the transport change in its publication metadata, rather than rely on this QA override or a same-version local replacement.

The mandatory `standalone` profile selects the standalone root only. The Cardano API, runtime/activation and on-chain artifacts are forbidden in this profile. The host accepts the X contribution kinds through the descriptors and bindings of the selected framework, so no Cardano API carrier is needed. The separate framework-core tests establish absence of Cardano SDK dependencies; the host fixture does not claim the entire integrated host library closure is SDK-free.

The optional `cardano-construction` profile selects the Cardano root and on-chain artifact instead; the standalone root is forbidden. It enforces exactly one framework manifest and exact ownership of the Cardano runtime classes and both on-chain resources. Set `"profiles": ["standalone", "cardano-construction"]` to include it. Each profile gets its own independently packaged JVM/classpath, resolved-input hashes and frozen host identity.

The Cardano check constructs and closes a real in-memory `YanoAssembly.relay` with the app ledger enabled, plugins enabled, peers empty, and anchoring disabled. It validates the actual Yano `CardanoHostServices` linkage, Cardano chain assembly, one manager lifecycle hook, and public handles. It never calls `start()` and must leave both persistent-store paths absent. Its JVM has a maximum 60-second timeout. This construction check does not claim Cardano network synchronization or anchoring coverage; those remain integrated release-qualification gates.

Activation uses Host API 1.2 only: the fixture lists the framework root in `yano.plugins.required`, passes `yano.app-chain.*` (including `yano.app-chain.storage.path`) as `RuntimeOptions` globals and reaches chains through `AppLedgers.of(yano)`. The frozen fixtures that modelled hosts embedding X (`first-host`, `supported-host`, `namespace-host`) were retired with ADR-064; `run` rejects any fixture whose provenance lacks the `host-api-1.2` contract. A new frozen Host API 1.2 baseline is prepared once from the first ADR-064 Yano artifacts and then reused unchanged for subsequent compatible X releases. Replace the example's repository path with verified staged inputs. The preparation command below is for deliberately establishing a new baseline, not for normal upgrades:

```sh
python3 qa/framework/host-matrix/run.py prepare \
  --inputs /absolute/host-matrix.json --out /private/tmp/frozen-host-fixture
```

Preparation resolves and freezes each requested profile's baseline inputs, verifies artifact ownership, then compiles the retained fixture source with an explicit external JAR classpath and empty sourcepath, and records compiler/source/bytecode and every compilation-input hash. Archive the fixture JAR and provenance unchanged. Preparation refuses an existing output directory; normal qualification never compiles the fixture.

Run the unchanged fixture against the oldest supported and current hosts:

```sh
python3 qa/framework/host-matrix/run.py run \
  --inputs /absolute/host-matrix.json --fixture qa/framework/host-matrix/fixtures/<host-api-1.2-baseline> \
  --out /private/tmp/host-matrix-run
```

Each cell resolves Maven artifacts, checks hashes and single class/resource ownership, generates one strict `ARTIFACT_CLOSURE` catalog using the published `PluginIndexGenerator` and the resolved Maven dependency graph (the same `gradle/plugin-index.gradle` that builds the integrated distribution's index), packs an ordinary ZIP, unpacks outside the source checkout, and launches a separate bounded JVM with only those packaged inputs. Loaded class origins must match the recorded JARs. Child processes get dedicated process groups and are terminated on timeout/interruption.

The fixture reuses the public builders/scenario of the standalone assembly tests: three actual TCP nodes, optional plugins disabled but required framework active, follower submission, threshold certificates/evidence, late-join protocol 103, common block/root/commitment, restart/reopen, subsequent finality, and no Cardano subsystem or chainstate. It intentionally provides no REST server or operator configuration surface. Existing integrated packaged/API/tooling regression gates remain required.

`results.json` reports each exact host/framework combination, input hashes, fixture provenance, loaded origins and outcome. A baseline host hash change fails rather than silently replacing the baseline. At the first supported release, baseline and current are genuinely the same artifact set: the harness reports one deduplicated cell per selected profile with both labels, not fabricated older-host evidence. Future X versions reuse the same fixture and baseline host bytes. Candidate artifacts are not described as previously published releases, and this matrix does not replace final coordinated release qualification.
