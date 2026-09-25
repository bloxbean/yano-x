# Yano App-Chain Studio

Static, release-pinned guided blueprint generation for multi-chain JVM projects.
Add or remove chain cards, select each recipe and its capabilities, and configure
shared member placement. Downloaded intent is validated by the matching CLI.
Local devnet users run `appchain prepare` to pin identities before starting.
See [the configuration guide](../../docs/appchain/deployment/configure.md).
Studio has no backend, telemetry, persistence, or secret inputs. The downloaded
`appchain.yaml` is rendered and semantically validated by the version-matched
`yano appchain` CLI, which remains authoritative.

For declarative bindings, Studio currently provides a **read-only graph viewer**:
import a capability-manifest JSON snapshot under **Inspect a declarative binding
graph**. It is not a drag-and-drop binding editor, does not change the blueprint,
and does not authenticate the imported chain identity. Author bindings in YAML
and validate them with the CLI; see the
[developer learning path](../../docs/appchain/bindings/README.md).

Build with `./gradlew :tooling:studio:assemble`. Serve the generated
`build/studio` directory with any static HTTP server.

Run the browser-independent Studio checks with
`./gradlew :tooling:studio:testStudio`. Gradle downloads the pinned Node.js
runtime used by this task; a system-wide Node.js installation is not required.
