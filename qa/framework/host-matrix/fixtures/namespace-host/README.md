# First namespace-compatible host fixture

This immutable compiled client qualifies the prerelease `org.yanoproject.x.framework.*` migration. It supersedes the old-package fixtures only for new namespace qualification; historical fixtures remain unchanged.

Inputs:

- Yano host `0.1.0-pre20-bf45507fd`, commit `bf45507fdd39d37516fceab7eae17e3df171376d`, [successful staging run 43665604964](https://github.com/bloxbean/yano/actions/runs/43665604964).
- X framework `0.1.0-pre6-a3ef67b36`, commit `a3ef67b3696385270dc1083c165159d9f4f5f34a`, [successful staging run 43665017469](https://github.com/bloxbean/yano-x/actions/runs/43665017469).
- Unchanged foundation `0.1.0-pre20-272157da2` and Host API `1.0.0-272157da2`.
- Public timestamped Yaci core `0.5.0-pre16-f57fc88-20261114.141754-1`.

Both profiles passed. Standalone exercised three actual TCP nodes, protocols 100/103, common finality/roots, late join and restart/reopen, with no Cardano state. Cardano construction verified callback linkage, assembly and one manager lifecycle hook without starting L1 synchronization or creating persistent stores. The latter does not claim live anchoring/sync coverage.

This is the first namespace-compatible candidate: baseline and current are the same host, truthfully deduplicated. It is not evidence that old-package hosts accept new framework classes, or that these staged coordinates are public releases. Future compatible X versions must reuse these host and compiled-client bytes unchanged; do not regenerate this fixture to make an upgrade pass.

`provenance.json` records compiler, source, bytecode and artifact hashes. `qualification.json` records the verified CI inputs, both outcomes, loaded origins and full-report hash. The fixture JAR SHA-256 is `47c1cda2c459f90960ecdf3a6b8f6ab4606bf50d0c69bbc7464d49e3acfda11f`.

Use the [matrix runner](../../README.md) with these exact baseline inputs and a separately selected framework version. Actions artifacts expire after 90 days; retain the qualified artifacts and replace development pins with available publication coordinates before release. This evidence does not replace the one final coordinated Yano release-qualification cycle.
