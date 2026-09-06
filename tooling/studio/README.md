# Yano App-Chain Studio

Static, release-pinned guided blueprint generation for multi-chain JVM projects.
Add or remove chain cards, select each recipe and its capabilities, and configure
shared member placement. Downloaded intent is validated by the matching CLI.
Local devnet users run `appchain prepare` to pin identities before starting.
See [the configuration guide](../../docs/appchain/deployment/configure.md).
Studio has no backend, telemetry, persistence, or secret inputs. The downloaded
`appchain.yaml` is rendered and semantically validated by the version-matched
`yano appchain` CLI, which remains authoritative.

Build with `./gradlew :tooling:studio:assemble`. Serve the generated
`build/studio` directory with any static HTTP server.

Run the browser-independent Studio checks with
`./gradlew :tooling:studio:testStudio`. Gradle downloads the pinned Node.js
runtime used by this task; a system-wide Node.js installation is not required.
