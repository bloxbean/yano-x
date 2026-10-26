# Yano App-Chain Studio

Static, release-pinned binding editor for chains that combine several state
machines. Studio has no backend, telemetry, persistence, or secret inputs, and
never requests or stores private keys. Applications themselves are created and
sealed with the CLI (`./yano.sh appchain try`, `new`, `request`, `seal`); the
start page points there. See
[the configuration guide](../../docs/appchain/deployment/configure.md).

For declarative bindings, `bindings.html` is a guided editor (ADR-031.2): forms
backed by an authoring catalog, a synchronized YAML view and graph, CLI handoff
with fixed file names, and explanation of imported, unauthenticated CLI reports.
It edits the same YAML the CLI compiles and never compiles, executes, submits or
stores anything. Its composite import opens a chain's inline `composite` from a
setup draft (`<app>.draft.yaml`, with `chains:` at the top level) or from the
older `spec.chains` layout, and writes back only that composite. A bindings
file reaches an application through `appchain new <app> --chain
<id>=bindings:<file>` or `bindings:` in the draft. See the
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
