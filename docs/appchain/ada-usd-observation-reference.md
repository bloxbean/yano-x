# ADA/USD certified-observation reference

This is an opt-in teaching example, not a production oracle, a market-price
recommendation, or a Cardano price publisher. The three `venue-a/b/c` sources
are synthetic fixtures. Their independence labels do not authenticate a real
exchange or prove economic independence.

The standard-library plugin contributes `ada-usd-reference-v1` through its
normal schema-v1 manifest and provider registry. The bundle requires Yano API level 8
and an enabled v2 observation profile. Do not install it into an existing
chain: its fixed application semantics start at genesis.

## What runs where

- External reporters acquire source values and sign canonical host reports.
  Each reporter owns one retained `ObservationReporterJournal` directory.
- Yano admits reports through `POST /app-chain/chains/{chainId}/observations/reports`,
  diffuses them, verifies their signatures and pinned identity, and constructs
  the complete-source certificate. This is not an ordinary app-message topic.
- The reference machine calls `observations.watch(...)` once and consumes only
  the host's finalized `onObservationResult` callback. It contains no HTTP
  client, reporter signature domain, report mempool, or custom round scanner.
- `query("latest", emptyBytes)` returns the canonical `ObservationResult`,
  including status, result/certificate digests and finalized height.
  `query("subscription", emptyBytes)` returns the 32-byte subscription ID.
  An empty response means no corresponding state exists yet.

The subscription observes `ADA/USD` with definition `ada-usd`. There are 100
scheduled rounds, due at app heights `2, 12, ..., 992`, each with a two-block
report window. Submit the ordinary member-authenticated topic
`ada-usd/advance` with the single byte `0x01` to generate application activity.
It carries no price, timestamp or reporter authority. These heights are a
logical cadence, **not seconds or minutes**. If the chain stops advancing,
the schedule stops too.

The sample policy uses scale 6 (one unit is USD 0.000001), values from one unit
through USD 1000, at least two independent groups, and outlier tolerance
`max(USD 0.01, 10% × abs(preliminary lower median))`. Exact reporter quorums
must cover all three pinned sources before aggregation. Each group contributes
its lower median; filter group medians against the preliminary lower median,
then take the lower median of the remaining groups. A missing source prevents
certification even if two sources agree. An expired round replaces the latest
result with a non-value status; the example never labels an older price fresh.

## Executable reference and validation

The runnable fixture is `AdaUsdReferenceRuntimeTest` in `state-machines/stdlib`.
It contains the complete profile/definition configuration, synthetic key
setup, public-API signature creation, SDK journal use, gateway submission,
finalized result/audit queries, restart/root parity, and a missing-source round.
It uses one host validator and five **separate** external reporters (`p=5,
g=1, r=4`). Host consensus membership and external reporter authority are
different sets. Yano's companion five-networked-validator test covers host
`n=5, q=4, f=1` with the same external quorum rule.

Use released Yano `0.1.0-pre15` Maven artifacts and its matching ordinary JVM
ZIP, following `docs/BUILD_AND_TEST.md`. From the Yano X worktree:

```sh
./gradlew :sdk:client:test :state-machines:stdlib:test \
  --tests '*Observation*' --tests '*AdaUsdReference*' \
  -PyanoVersion=0.1.0-pre15
```

Maven Local is not required and remains disabled by default.
The production provider checks that the definition's policy and source-set
digests match `AdaUsdReferenceStateMachine.parameters()`; changing these
semantics requires a new application/profile identity, not an operator-local
override. The executable fixture uses the normal provider registry; it does
not inject a custom state machine into the host.

## Reporter SDK rules

`ObservationReporterJournal.sign(round, unsignedReport, committedHeight,
logicalAnchor, signer)` forces the unsigned claim and identity to durable
storage before invoking `signer`. The signer receives the canonical host
signing digest and must return a 64-byte Ed25519 signature. The SDK does not
store private keys. Reopening the journal permits the same preimage, never a
different claim for the same subscription/round/source. Capacity exhaustion,
partial records, identity mismatch and a second local owner fail closed.

Authenticate the chain genesis, consensus/observation profiles, pinned
definition, reporter set, round and current logical anchor before calling it.
The journal checks internal consistency; it does **not** turn an unauthenticated
REST response into a trusted round or assert that the remote source is true.
Its file lock protects one retained directory, not two copied directories on
different machines. Never clone a live reporter identity/journal into a second
active owner, discard a journal to bypass a conflict, or use a filesystem that
cannot honor file and directory fsync. Provision the retained directory
durably before enabling a signing key. Automatic journal pruning is not supplied.

`AppChainClient.submitObservationReport(signedReport)` sends the canonical body
and validates the bounded HTTP 202 `QUEUED` receipt, chain and report digest.
The receipt means **queue admission only**. It proves neither validation nor
durable retention, certificate construction, block inclusion, or finality.
Retry the same signed report and confirm the finalized host result separately.
Duplicate JSON fields, trailing JSON, oversized receipts and wrong digests are
rejected. Gateway transport does not replace inner reporter authorization.

## Scope and migration

Yano X ADR-012 retains feed governance, prior-value circuit breakers, Cardano
publication effects and settlement state. This example does not implement
those product features. Policy changes use explicit cancel-and-re-watch with
another profile-authorized definition; they do not modify an already opened
round. Partial-source medians and latest-arrival policies remain disabled.

Tracking: [Yano X issue #5](https://github.com/bloxbean/yano-x/issues/5),
[companion PR #6](https://github.com/bloxbean/yano-x/pull/6), and Yano ADR-037.
