# Yano App-Chain Studio

Static, release-pinned guided blueprint generation for multi-chain JVM projects.
Add or remove chain cards, select each recipe and its capabilities, and configure
shared member placement. Downloaded intent is validated by the matching CLI.
Local devnet users run `appchain prepare` to pin identities before starting.
See [the configuration guide](../../docs/appchain/deployment/configure.md).
Studio has no backend, telemetry, persistence, or secret inputs. The downloaded
`appchain.yaml` is rendered and semantically validated by the version-matched
`yano appchain` CLI, which remains authoritative.

For declarative bindings, `bindings.html` is a guided editor (ADR-031.2): forms
backed by an authoring catalog, a synchronized YAML view and graph, CLI handoff
with fixed file names, and explanation of imported, unauthenticated CLI reports.
It edits the same YAML the CLI compiles and never compiles, executes, submits or
stores anything. The blueprint page keeps its read-only viewer for
capability-manifest snapshots. See the
[editor chapter](../../docs/appchain/bindings/06-guided-editor.md).

The editor ships a generated reference catalog (`binding-authoring-catalog.json`,
for the tutorial context in `binding-authoring-context.json`) and two starters
copied from `examples/bindings/`. Regenerate the catalog and the test fixtures
from the real CLI with `./gradlew :tooling:devtools:regenerateStudioFixtures`.

Build with `./gradlew :tooling:studio:assemble`. Serve the generated
`build/studio` directory with any static HTTP server.

Run the browser-independent Studio checks with
`./gradlew :tooling:studio:testStudio`. Gradle downloads the pinned Node.js
runtime used by this task; a system-wide Node.js installation is not required.

Run the browser, keyboard and accessibility gates with
`./gradlew :tooling:studio:browserTestStudio`. They use the docsite's Playwright
installation, so run `npm ci` and `npx playwright install chromium` in `www/`
once. The task also passes the packaged CLI and bundles so the suite can
recompile and rehearse documents exported from the browser.
