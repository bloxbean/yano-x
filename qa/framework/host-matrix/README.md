# Packaged thin-host qualification

This QA-only Maven consumer exercises the public Yano embedding API with an independently selected X framework. It does not modify the integrated Quarkus distribution, add a CLI/configuration parser, or load a new framework over an older uber-JAR. Ordinary embedding deployments compose the same thin artifacts on one classpath.

Prerequisites: Java 25, Python 3, and externally published/staged immutable Yano, Host API, X and Yaci artifacts. No Maven Local, sibling checkout, composite build or production source output is consumed. The existing wrapper only runs the isolated resolver in this directory. Supply exact versions and repository URLs in a copy of `inputs.example.json`. Each host row pins every Yano module, with separately selected foundation and Host API versions. X dependencies are excluded from host edges and explicitly supplied at one selected framework version.

The required `yaciCoreVersion` input explicitly selects the coordinated app-only transport candidate. The resolver verifies that exact selected version and records its SHA-256 with the other inputs; `yaci-helper` retains its published dependency/version. This override is visible qualification input because the staged foundation POM still names the original Yaci core. The final production release must pin the actual upstream version containing the transport change in its publication metadata, rather than rely on this QA override or a same-version local replacement.

The mandatory `standalone` profile selects the standalone root and Cardano **API** module. The latter supplies the effect-factory vocabulary required by the existing host catalog. Cardano runtime/activation and on-chain artifact dependencies remain forbidden in this profile. The separate framework-core tests establish absence of Cardano SDK dependencies; the host fixture does not claim the entire integrated host library closure is SDK-free.

The optional `cardano-construction` profile selects the Cardano root and on-chain artifact instead; the standalone root is forbidden. It enforces exactly one framework manifest and exact ownership of the Cardano runtime classes and both on-chain resources. Set `"profiles": ["standalone", "cardano-construction"]` to include it. Each profile gets its own independently packaged JVM/classpath, resolved-input hashes and frozen host identity.

The Cardano check constructs and closes a real in-memory `YanoAssembly.relay` with the app ledger enabled, plugins enabled, peers empty, and anchoring disabled. It validates the actual `CardanoRuntimeServices` constructor linkage, Cardano chain assembly, one manager lifecycle hook, and public handles. It never calls `start()` and must leave both persistent-store paths absent. Its JVM has a maximum 60-second timeout. This construction check does not claim Cardano network synchronization or anchoring coverage; those remain integrated release-qualification gates.

The old-package candidates in [`fixtures/supported-host`](fixtures/supported-host/README.md) and `fixtures/first-host` are immutable historical evidence. They cannot qualify the migrated `org.yanoproject.x.framework.*` namespace. Recompile consumers. The [namespace-compatible baseline](fixtures/namespace-host/README.md) now retains its newly compiled client and exact qualification inputs; reuse those bytes for subsequent compatible X releases. The Host API interface/version contract remains unchanged, but the host implementation must use the new framework types. The example pins this first baseline/current candidate; replace its repository path with verified staged inputs. Do not mix old-package host candidates into this matrix. The preparation command below is for deliberately establishing a new baseline, not for normal upgrades:

```sh
python3 qa/framework/host-matrix/run.py prepare \
  --inputs /absolute/host-matrix.json --out /private/tmp/frozen-host-fixture
```

Preparation resolves and freezes each requested profile's baseline inputs, verifies artifact ownership, then compiles the retained fixture source with an explicit external JAR classpath and empty sourcepath, and records compiler/source/bytecode and every compilation-input hash. Archive the fixture JAR and provenance unchanged. Preparation refuses an existing output directory; normal qualification never compiles the fixture.

Run the unchanged fixture against the oldest supported and current hosts:

```sh
python3 qa/framework/host-matrix/run.py run \
  --inputs /absolute/host-matrix.json --fixture qa/framework/host-matrix/fixtures/namespace-host \
  --out /private/tmp/host-matrix-run
```

Each cell resolves Maven artifacts, checks hashes and single class/resource ownership, generates one strict `ARTIFACT_CLOSURE` catalog using the published `PluginIndexGenerator` and the resolved Maven dependency graph, packs an ordinary ZIP, unpacks outside the source checkout, and launches a separate bounded JVM with only those packaged inputs. Loaded class origins must match the recorded JARs. Child processes get dedicated process groups and are terminated on timeout/interruption.

The fixture reuses the public builders/scenario from Yano's `AppLedgerAssemblyTest`: three actual TCP nodes, optional plugins disabled but required framework active, follower submission, threshold certificates/evidence, late-join protocol 103, common block/root/commitment, restart/reopen, subsequent finality, and no Cardano subsystem or chainstate. It intentionally provides no REST server or operator configuration surface. Existing integrated packaged/API/tooling regression gates remain required.

`results.json` reports each exact host/framework combination, input hashes, fixture provenance, loaded origins and outcome. A baseline host hash change fails rather than silently replacing the baseline. At the first supported release, baseline and current are genuinely the same artifact set: the harness reports one deduplicated cell per selected profile with both labels, not fabricated older-host evidence. Future X versions reuse the same fixture and baseline host bytes. Candidate artifacts are not described as previously published releases, and this matrix does not replace final coordinated release qualification.
