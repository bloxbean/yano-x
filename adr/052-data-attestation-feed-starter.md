# ADR-052: Data Attestation Feed Starter

- **Status:** Accepted (2026-09-06), implemented on `feat/products-oob`; §11 records the
  implementation and its deviations
- **Classification:** `EXPERIMENTAL` (ADR-046 §4.6), labelled a starter. It is the observation
  ledger that Yano ADR app-layer/014 §3 describes as available today ("ordered observations +
  approvals/aggregation + proofs"); it is not the oracle pipeline ADR app-layer/012 designs, it
  publishes nothing to Cardano, and it must not be pitched as a price oracle.
- **Scope:** `products/attestation-feed/{profile,client,cli,ui,harness}`; a config-only chain on
  the stock governed `authenticated-map`, no consensus, contract, effect, or showcase-identity
  change
- **Relates to:** ADR-046 §4.6 (the product this starter previews), Yano ADR app-layer/012 (the
  oracle design whose vocabulary, round and aggregation rules it borrows in bounded form),
  app-layer/010.2 (the Cardano executor hardening the full product waits for), app-layer/013.2
  (composite review the aggregation component would need), ADR-049 (the map client, signer,
  verifier, and actor descriptor it reuses), ADR-051 (the approval round, request document,
  bundle, portal, gateway, and console patterns it repeats), ADR-047 (trust input and levels)

## 1. Context

ADR-046 §4.6 wants a consortium-governed data feed: domain actors submit signed observations, a
deterministic component aggregates each round with outlier rules, the members co-sign the
aggregate, a Cardano datum-publication executor writes it to one canonical UTxO, and a consumer
validator reads it. It gates the product on two things that do not exist yet and that this
branch may not build: a hardened Cardano publication executor owned by Yano core (ADR-012
§10–13, planned ADR-010.2) and a reviewed aggregation composite component (ADR-013.2). ADR-012
itself is "Proposed — design for review before implementation"; no oracle state machine, effect
type, executor, datum codec, or validator exists in `yano-x` or in the Yano pre14 distribution.
ADR-046 §7 says plainly: "do not start before core owns the executor."

Under the standing constraint of this branch (no core change), the full product is therefore out
of reach, exactly as the full DPP provider was for ADR-051. What ADR-014 §3 lists as available
today is narrower and honest: an observation ledger with ordered, actor-signed observations,
approval-gated round results, and proofs, with the Cardano publication deferred to ADR-010.2.
This ADR builds that ledger as a starter, labels it everywhere a reader can see it, and records
in §8 what the gated stage owns. It adds no trust it cannot prove:

- Observations are governed-map entries written by the source's own actor under a direct-role
  policy; a proof of the entry at a height shows who wrote what and when the chain finalized it.
- The aggregate is a pure function of the proven observations and the proven feed policy. It is
  not computed on chain. Every verifier recomputes it from the answers in a round bundle and
  compares it with what the consortium recorded; a disagreement is a flagged verification
  failure, not a hidden one. This is the trust registry's status-list pattern: the on-chain
  record is the operators' claim, the verifier's recomputation is the check.
- The consortium's agreement is the map's approval route (ADR-049 §7, ADR-051 §2.2): a
  `feed-operator` proposes the round record, two `publisher` actors from distinct organizations
  approve it after recomputing it themselves (the CLI and gateway refuse to sign an approval
  whose numbers they cannot reproduce), and the map applies it once, atomically with the
  terminal proposal. The approval consumption record is proven in the bundle as ADR-051 does.
- Cardano publication is deferred. The starter computes a candidate datum from the closed round
  (its hash is recorded in the round record) so that a future executor has something exact to
  bind to, and so that an operator can inspect what would be published. No transaction is ever
  built or submitted.

What the platform provides, found while designing:

- The governed map's schema language has bounded variable-length arrays
  (`Occurrence(minimum, maximum, node)`), so a feed record can carry its source list on chain
  and the verifier can enumerate a round's observations from the proven feed record instead of
  from a projection: one exact or absence proof per configured source, a bounded set.
- The map answers any retained height, including an absence proof at a historical height for a
  key that is present at the tip (probed on the ADR-051 test cluster before this design was
  fixed: the answer carries the historical height, `ABSENT`, and verifies under the members
  file). A round is closed at a declared height `h0` (the height at which the proposer
  aggregated) and recorded at a later height `H` when the approvals are applied. The bundle
  therefore answers the feed record and the observations at `h0` and the round record at `H`;
  a late observation that lands between `h0` and `H` cannot change the agreed result, and the
  verifier sees exactly what the proposer saw.
- ADR-012's round clock uses the verified L1 slot of each block, which needs an anchored chain
  and consensus code. Configuration-only, the starter cannot read time in consensus; it uses the
  source's signed `observedAt` and a per-feed round calendar (`epochStart`, `roundSeconds`)
  fixed in the feed record, and the verifier decides off chain whether an observation belongs to
  its round. Sources that lie about `observedAt` are equivalent to sources that lie about the
  value; the outlier rule and the consortium's source governance bound it, not consensus.
- ADR-012's equivocation rule ("any non-byte-identical signed report under the same key")
  maps onto the map's revision: a source that rewrites its observation raises the revision above
  1, and the starter excludes the source for that round (`EQUIVOCATED`). A direct-role policy
  authorizes the role, not the key, so a source could write another source's key; the answer's
  authorization evidence names the writer, and the verifier excludes such entries
  (`FOREIGN_WRITER`), as ADR-051 does for passports.

## 2. Decision

Build `products/attestation-feed` as four Gradle modules, a launcher, and a guide. Version one is
scoped so that every journey it claims runs end to end in unit tests, in an in-process cluster
test, and on a live three-member chain started by the launcher.

### 2.1 Profile (`products/attestation-feed/profile`, library and generator)

`FeedStarterProfile` (`attestation-feed-starter-v1`) fixes three collections on the governed map.
Every value is canonical CBOR checked by a genesis-bound schema; no collection allows restore.

| Collection | Authorization | Policy | Key (≤ bytes) | Value (canonical CBOR) |
|---|---|---|---|---|
| `feeds` | `governed-role` | `feed-admin-write` (role `feed-admin`) | feed id, 32 | `[1, description, unit, scale, epochStart, roundSeconds, sources[], minimumSources, maximumDeviationPpm, maximumDeviationAbsolute, minimumValue, maximumValue, status]` |
| `observations` | `governed-role` | `source-write` (role `source`) | `<feedId>/<round>/<sourceId>`, 118 | `[1, value, observedAt, evidenceSha256, note]` |
| `rounds` | `approval` | `round-close` (proposer `feed-operator`; clause `independent-publishers`: two `publisher` approvals from distinct organizations) | `<feedId>/<round>`, 53 | `[1, status, closedAtHeight, aggregate, scale, acceptedSources[], policySha256, datumSha256]` |

Layout rules:

- A feed id matches `[a-z0-9][a-z0-9._-]{0,31}`; a round is the decimal of an unsigned integer
  below 2^63 without leading zeros; a source id is the actor id of the role workflow
  (`[a-z][a-z0-9-]{0,62}`). `/` separates key segments and appears in no identifier.
- `feeds`: `description` (≤ 256 bytes) and `unit` (≤ 16, for example `USD`, `degC`) are display
  text; `scale` (0..18) means the quantity is `value × 10^-scale` (ADR-012 §7.1); `epochStart`
  (epoch seconds) and `roundSeconds` (1..604800) define the round calendar
  `start(r) = epochStart + r × roundSeconds`, `end(r) = start(r + 1) − 1`; `sources` is 1..16
  distinct source actor ids; `minimumSources` (1..16) is the quorum; `maximumDeviationPpm`
  (0..1,000,000) and `maximumDeviationAbsolute` (unsigned) are the outlier rule of ADR-012 §8.4
  step 9; `minimumValue` ≤ `maximumValue` bound accepted values; `status` is `0 ACTIVE` or
  `1 PAUSED`. Values are signed integers within ±(2^63 − 1) so that every client, including the
  browser, computes with exact integers.
- `observations`: `value` is the source's reading at `scale`; `observedAt` is the source's signed
  business time in epoch seconds, never compared with a clock on chain; `evidenceSha256` is empty
  or 32 bytes (ADR-012's `source-evidence-hash`: the upstream response or sensor frame the source
  retains); `note` ≤ 256 bytes. Each source is its own reporter and its own independence group
  in ADR-012 terms (`REPORTER_ATTESTED`); relays, aliases, and source-native assertions are the
  gated stage's.
- `rounds`: `status` is `1 CLOSED` or `2 NO_QUORUM`; `closedAtHeight` is the height `h0` at which
  the proposer read the feed record and the observations; `aggregate` and `scale` are the result
  (`0` and the feed's scale for `NO_QUORUM`); `acceptedSources` lists the sources whose
  observations survived every rule, sorted; `policySha256` is the SHA-256 of the canonical feed
  value at `h0`, binding the record to the policy it was computed under; `datumSha256` is the
  SHA-256 of the candidate datum (§2.2) for `CLOSED`, empty for `NO_QUORUM`.

Aggregation (`feed-aggregation-v1`) is ADR-012 §8.4 reduced to one reporter per source and no
prior-round state, computed from the feed record `F` and the observation answer of every source
in `F.sources`, all at one height:

1. Disposition per source: `ABSENT` (no entry), `REVOKED` (tombstone), `FOREIGN_WRITER` (the
   entry's writer is not the source), `EQUIVOCATED` (revision above 1), `WRONG_ROUND`
   (`observedAt` outside `[start(r), end(r)]`), `OUT_OF_RANGE` (`value` outside
   `[minimumValue, maximumValue]`), else `CANDIDATE`.
2. If fewer than `minimumSources` candidates remain, the round is `NO_QUORUM`.
3. Sort candidates by `(value, sourceId)`; the reference `m` is the lower median at index
   `floor((n − 1) / 2)`.
4. `permitted = max(maximumDeviationAbsolute, floor(|m| × maximumDeviationPpm / 1,000,000))`; a
   candidate with `|value − m| > permitted` is `OUTLIER`.
5. If fewer than `minimumSources` remain, the round is `NO_QUORUM`.
6. The aggregate is the lower median of the remaining values, sorted as in step 3; the remaining
   sources, `ACCEPTED`, are listed sorted by id.

The lower median is chosen for even counts exactly as ADR-012 requires; nothing averages or
rounds. `PAUSED` feeds close no rounds: proposing one is refused, and a record for a paused feed
verifies with `FEED_PAUSED`. The round-jump circuit breaker (ADR-012 §8.4 step 12) needs the
previous valid round and is deferred (§8).

Roles: `feed-admin` (the administrator authority), `source`, `feed-operator`, `publisher`.
Direct authorizations live at most 100 blocks; a round-close proposal at most 600.

`FeedGenesis` builds the map genesis with the stock `AuthenticatedMapGenesisFactory.mpf` from the
ADR-049 actor descriptor through the `TrustRegistryGenesis.governedGenesis(descriptor,
directPolicies, approvalPolicies)` overload ADR-051 added; nothing outside
`products/attestation-feed` changes. The same descriptor always yields the same genesis id. The
`--demo` descriptor holds `feed-consortium` (`feed-admin-a`: `feed-admin`), `exchange-alpha`
(`source-alpha`: `source`), `exchange-beta` (`source-beta`: `source`), `exchange-gamma`
(`source-gamma`: `source`), `feed-ops` (`ops-a`: `feed-operator`; `publisher-a` and
`publisher-c`: `publisher`, so that a second approval from the same organization can be shown
to be insufficient), and `audit-guild` (`publisher-b`: `publisher`), with deterministic demo
seeds
`sha256("yano-attestation-feed-demo-actor:" + actorId)`, showcase-only material.

### 2.2 Client (`products/attestation-feed/client`)

`FeedClient` wraps a `TrustRegistryClient` pinned to one chain and adds the feed shape:

- Writes: `createFeed` (`PUT_IF_ABSENT`) and `updateFeed` (`COMPARE_AND_SET` on the revision
  read) as a `feed-admin`; `observe` (`PUT_IF_ABSENT`, so an honest retry is harmless and a
  rewrite must be deliberate) as a `source`; a raw `put` for tests. All go through
  `TrustRegistrySigner.governedCommand` with one-use authorizations and await the receipt.
- The round close is the ADR-051 approval round with a round-specific request:
  `proposeRound(feedId, round, h0)` reads the feed record and every source's observation at
  `h0`, aggregates, builds the approval-routed `PUT_IF_ABSENT` of the round record (with the
  candidate datum hash), signs the operator's `PROPOSE`, and returns a `feed-round-request-v1`
  document (chain, genesis id, policy id and revision, proposal id, action bytes, payload hash,
  deadline, feed, round, `closedAtHeight`, the aggregation result). `approveRound(publisher,
  request)` recomputes the round at `request.closedAtHeight` from the chain and refuses to sign
  when the record it would approve does not equal the recomputation; `rejectRound` signs a
  `REJECT`; `applyRound` submits the map command with the approval reference. A `NO_QUORUM`
  round is recorded the same way, so consumers can distinguish "not closed yet" from "closed
  without a value".
- `round(feedId, round, height)` assembles a `feed-round-v1` bundle: the feed answer and one
  observation answer per configured source at `h0` (the requested height, or the record's
  `closedAtHeight` when a record exists, or the tip for an open round), the round record answer
  at `H` when closed, with the `approval-consumption` fact ADR-051 §2.2 defines, the chain's
  identity, and the starter notice. `latestRound(feedId)` probes `rounds/<feed>/<r>` backwards
  from the calendar's current round (the portal's clock, labelled) through at most 64 rounds,
  using absence proofs, so it never replays the ledger; `feeds()` replays finalized `feeds`
  commands through the ADR-051 projection bound (5,000 commands), which is a bound on feed
  definitions, not on observations, and returns the keys it found, each answered with a proof.
- `Aggregation` is the pure §2.1 function over decoded answers and returns the result with each
  source's disposition. `FeedDatum` encodes the candidate datum `feed-datum-candidate-v1` as
  Plutus data (constructor 0: `[sha256(chainId), sha256(feedId), round, aggregate, scale,
  end(round), closedAtHeight, stateRoot at closedAtHeight, acceptedCount, aggregationCode 1]`)
  in canonical CBOR. It is not ADR-012's `OracleLiveV1` (no thread token, publication policy,
  sequence, or slots) and says so in its name.
- `FeedVerifier.verify(bundle, trust)` verifies every answer under the ADR-047 trust input with
  the ADR-049 verifier, then checks the round: every answer belongs to one chain and genesis;
  the feed answer is the feed's key at `h0`; the feed's `sources` and the observation answers
  correspond one to one at `h0`; when a record is present it is the round's key at `H ≥ h0`,
  `closedAtHeight = h0`, `policySha256` matches the feed value, `status`, `aggregate`, `scale`,
  and `acceptedSources` equal the recomputation, `datumSha256` matches the recomputed datum, the
  record's revision is 1 (else `REWRITTEN`), and the approval consumption fact proves the
  applied message under the certified root. The verification carries the recomputed result, the
  per-source dispositions, and the flags `RECORD_DISAGREES`, `WRONG_POLICY`, `DATUM_MISMATCH`,
  `FEED_PAUSED`, `REWRITTEN`, `UNPROVEN_APPROVAL`. The bundle's trust level is the minimum over
  its answers (the record and the observations sit in different blocks).
- `RoundView.of(bundle)` renders the JSON the portal and console show: status, feed, the source
  table with values and dispositions, the aggregate, the record, the datum, the timeline.

### 2.3 CLI (`yano-feed`, `products/attestation-feed/cli`)

| Command | Purpose |
|---|---|
| `genesis`, `descriptor --demo`, `actor-key` | as `yano-dpp`: the map genesis from a descriptor, the demo descriptor, an actor key from a seed |
| `feed create` / `feed update` | `--feed --spec <json>` as a `feed-admin`; `update` compares and sets on the current revision |
| `feed get` | the feed record with its proof, and the rounds known to the projection |
| `observe` | `--feed --value --observed-at [--note --evidence <file>]` as a `source`; the round is derived from `observedAt` and the feed calendar unless `--round` is given |
| `round propose` | `--feed --round [--height]` as a `feed-operator`; aggregates at the height (default tip), writes the `feed-round-request-v1` document to `--output`, prints the result |
| `round approve` / `round reject` | `--request <file>` as a `publisher`; `approve` recomputes and refuses on disagreement (exit 4) |
| `round apply` | `--request <file>` as any actor; submits the map command, prints the receipt |
| `round get` | `--feed --round [--height] [--output <bundle>] [--json]`; `--round latest` picks the newest closed round |
| `verify` | `--bundle <file> [--members <file> | --anchor-datum-hex <hex>]`; exit 0 anchor-verified, 5 caller-pinned, 6 consistent only, 4 invalid |
| `datum` | `--bundle <file>`: the candidate datum CBOR hex and its SHA-256 for a closed round |
| `simulate` | `--feed --round --sources a,b,c --base <value> [--spread-ppm --outlier <source>]`: the mock source adapter of ADR-046 §4.6, submitting one deterministic observation per named source from its seed |
| `serve`, `gateway` | the two services of §2.4 |

Exit codes, environment (`YANO_FEED_URL`, `YANO_FEED_CHAIN`, `YANO_API_KEY`), seed handling, and
the starter banner follow ADR-051 §2.3.

### 2.4 Services

`yano-feed serve` (the portal, read-only, default 8680): `GET /healthz`, `GET /feeds`,
`GET /feeds/{feedId}` (record, rounds known, the calendar's current round computed from the
portal's clock and labelled as such), `GET /feeds/{feedId}/rounds/{round}[?height=h]` (the
round view), `GET /feeds/{feedId}/rounds/{round}/proof` (the bundle),
`GET /feeds/{feedId}/rounds/{round}/datum`, `GET /feeds/{feedId}/latest[/proof]`. An unknown feed
answers 404 with the absence proven. Every round number is valid on the feed's calendar, so a
round without a record answers 200 as an `OPEN` bundle at the requested height (or the tip)
whose preview aggregation may already say `NO_QUORUM`; `datum` answers 404 for a round that is
not `CLOSED`. Invalid identifiers answer 400; non-GET 405.

`yano-feed gateway` (the signing service, default 8690, loopback unless `--allow-remote`,
`X-Gateway-Token` required): `GET /healthz`, `GET /operator/actors`, `POST /source/observe`,
`POST /operator/feeds`, `POST /operator/rounds/propose|approve|reject|apply`. Seeds stay on the
gateway's machine; the console holds the token only. Error mapping as ADR-051 §2.4.

### 2.5 Console (`products/attestation-feed/ui`)

A SvelteKit static site on the ADR-051 scaffold with three views: **Round** (portal URL, feed id,
round or `latest`; the header shows the status and binding pills; panels show the feed policy,
the source table with each observation's value, time, disposition, and proof binding, the
aggregate recomputed in the browser beside the recorded one, the approval consumption, the
candidate datum hash; one proof row per answer; the bundle is downloadable and verifiable with
the CLI; the location lives in the URL hash `#/feeds/<id>/rounds/<n>`), **Source** (gateway URL
and token; pick a source actor, feed, value, time, note; submit an observation and see the
receipt), **Operator** (propose, approve, reject, apply with the request document in a
textarea), and **About** (the starter notice and what is proven). The Round view reads only the
portal; the browser recomputes the aggregate from the bundle's decoded values with the same
integer rules, so the console never trusts the portal's arithmetic.

### 2.6 Launcher (`products/attestation-feed/harness/feed.sh`)

`up`, `seeds`, `portal`, `gateway`, `demo`, `status`, `env`, `stop`, `clean`, as `dpp.sh`, on
HTTP 7570 and server port 9570 by default. `demo` creates the `coldstore-7` feed (a cold-store
temperature in `degC` at scale 2, so `-1825` is −18.25 °C; one-minute rounds, three sources,
quorum 2, a permitted deviation of 2% or 50 hundredths, whichever is larger), simulates the three
sources for the current round with one outlier, shows a same-organization second approval
refused on apply, then approves from two organizations, applies, exports and verifies the bundle,
and records a `NO_QUORUM` round with a single observation for the next round. A temperature feed
is the consortium pitch ADR-046 §4.6 asks for, and its negative values exercise the signed
arithmetic; the starter is not to be demonstrated as a public price oracle.

## 3. Discovery and data access

The CLI, portal, and gateway pin one chain by `--chain` or `YANO_FEED_CHAIN`, require the
starter profile in the chain's manifest, and read the map's genesis id from the node once, as
ADR-049 §3 does. Round bundles carry chain id, profile, and genesis id; the verifier refuses a
bundle whose answers disagree on any of them.

## 4. Proof story

A verified round bundle proves, at the trust level of its input: that at height `h0` the feed's
policy was `F` and each configured source had exactly the observation shown, or none; that the
recomputation under `F` yields the aggregate and the accepted set; that at height `H` the
consortium recorded exactly that result, that the record was applied once through an approval
round whose consumption is proven, and that the record has not been rewritten. It proves who
observed what and what the consortium agreed to record. It does not prove that any reading is
true, that sources are independent, or that anything reached Cardano (ADR-012 §2.2). It proves
the result at the closing height the proposer declared; whether that height was a fair moment
to close (an observation that arrived one block later is invisible to it) is the consortium's
operating rule, not something configuration can enforce. A consumer who cares re-answers the
round at a later height (`round get --height`) and compares the two recomputations. Under
`--members` the level is `CALLER_PINNED_ROOT`; with an anchor datum for a height only the
answers at that height reach `INDEPENDENTLY_VERIFIED_L1_ANCHOR`, and the bundle's level is the
minimum, so anchored verification of a closed round needs the ADR-047 anchor story per height
(§8).

## 5. Determinism firewall

The profile and client modules depend on `stdlib-contracts`, `role-workflow-contracts`, the trust
registry profile and client, the SDK client, and the Attest client; a classpath firewall test in
each module refuses `yano-appchain-core`, node, and consensus runtime classes. Aggregation and
datum encoding use `BigInteger` and canonical CBOR only; no floating point, no clock, no locale.

## 6. Security and privacy

- Sources sign with their own actor keys on their own machines; the gateway holds the seeds of
  the actors it is given and refuses every request without its token.
- A source can rewrite or write for another source only visibly: the map records the revision
  and the writer, and the verifier excludes the observation and flags it.
- The recorded aggregate cannot be quietly wrong: the verifier recomputes it, and a publisher's
  tool refuses to approve a record it cannot reproduce.
- Values are visible on chain; a feed whose readings are confidential must not use this starter
  (committed values with disclosure, ADR-051's `COMMITTED` claim, would need the aggregation to
  run on disclosed values and is deferred).
- Nothing in the starter connects to Cardano, spends, or signs a transaction.

## 7. Testing

Profile tests: genesis determinism; schema acceptance and rejection (source list bounds, scale,
round seconds, status, negative values); value codecs and canonical-form refusal; key builders
and bounds; the round calendar; aggregation vectors (odd and even counts, ties, an outlier by
ppm and by absolute deviation, quorum lost after outliers, negative values, `NO_QUORUM`); datum
encoding golden bytes; the firewall. Client tests: an in-process three-member map with the demo
genesis running the whole journey (feed created and updated, three observations, a rewrite
flagged `EQUIVOCATED`, a foreign write flagged `FOREIGN_WRITER`, a wrong-round and an
out-of-range observation excluded, propose at `h0`, a late observation after `h0` leaving the
result unchanged, a second approval from the same organization (`publisher-c` beside
`publisher-a`) insufficient (`APPROVAL_NOT_APPROVED` on apply), approval by a publisher whose
recomputation disagrees
refused, two approvals from distinct organizations applied, a `NO_QUORUM` round, a paused feed
refused), bundles verified under every trust input, tampering (a changed value, a stripped
observation, a swapped record, a record with a wrong datum hash, wrong members), historical
bundles, the portal and gateway routes, and goldens for the CLI and console. CLI tests on the
goldens and usage. Console: `npm run check`, `npm test` on the goldens (decoding, aggregation
agreeing with the Java result, bundle checks), `npm run build`. Distribution: the inventory,
JVM-only, and distribution verification tasks.

## 8. Deferred

- The Cardano publication executor, thread-token UTxO model, publication policy, threshold
  authorization, and durable prepare-before-submit journal (ADR-012 §10–13, ADR-010.2), and the
  consumer validator. The candidate datum is the starter's only artifact toward them.
- The aggregation composite component (ADR-013.2) that would turn admission, equivocation,
  round closure, and the circuit breaker into consensus rules; the L1-slot round clock and
  member ticks (ADR-012 §8.1); the round-jump limit (§8.4 step 12); source-auth profiles,
  relays, independence groups, and reporter matrices (§7.2, §8.4); weighted or time-weighted
  aggregation; evidence-root retention and pruning (§8.6, §9).
- Anchored verification of a closed round across its two heights; committed (confidential)
  observations; a feed index beyond the 5,000-command replay bound (an indexer, ADR-050's
  territory).

## 9. Acceptance criteria

1. The cluster test and the launcher's demo run the journey of §2.6 on a real three-member map.
2. A bundle exported by the CLI, the portal, and the console verifies offline at exit 5 under the
   members file and exit 6 bundle-declared, and every tampering case in §7 fails.
3. A publisher's approval is refused when the recomputation disagrees with the request.
4. The console recomputes the aggregate from the bundle and shows it beside the record.
5. `verifyArtifactInventory`, `verifyJvmOnlyBuild`, and `verifyYanoXJvmDistribution` pass with
   `tools/yano-feed`, `product-ui/attestation-feed`, and `examples/attestation-feed` in the
   distribution.
6. The guide `docs/appchain/ATTESTATION_FEED.md` and the docs-site page carry the starter notice,
   the setup, the walkthrough, the proof story, and the deferrals.

## 10. Consequences

Positive: the observation ledger ADR-014 lists becomes installable with a UI, a CLI, and a proof
story, reusing the ADR-049 client and the ADR-051 round machinery unchanged; the aggregation rule
exists as tested, versioned code and vectors the ADR-013.2 component can adopt; the datum
candidate gives the executor work a concrete binding. Negative: the aggregate is not a consensus
result, only an agreed and recomputable one; the round calendar trusts signed business time;
two heights per bundle complicate anchored verification. Neutral: the starter's identifiers
carry `starter` and `candidate` so that ADR-012's names stay free.

## 11. Implementation record

**Gates.** `:products:attestation-feed:profile:test` (16 tests: genesis determinism and the
registry descriptor refusal, the demo consortium's two publishers in one organization, schema
acceptance and rejection including the sixteen-source bound, a paused feed, negative values,
and a hand-built `-2^64` refused, value codecs and the non-canonical refusal, keys, the
calendar, six aggregation vectors, the pinned datum bytes, the firewall),
`:products:attestation-feed:client:test` (10: the round request's round trip and refusals, the
projection, the firewall, and the cluster test on a real three-member governed map with the demo
genesis: round 7 closed on three observations with `source-gamma` an `OUTLIER` after a
same-organization second approval was refused on apply with `APPROVAL_NOT_APPROVED`; round 8
closed on two observations with the third landing after the closing height and the canonical
bundle unchanged, then re-answered at the tip as a `LATER_HEIGHT` comparison that `DIFFERS`;
round 9 `NO_QUORUM`; rounds 10 and 11 open with `EQUIVOCATED`, `FOREIGN_WRITER`,
`OUT_OF_RANGE`, and `WRONG_ROUND`; round 12 a wrong record refused by `approveRound` and pushed
past it through the package-private path, then caught by the verifier as `RECORD_DISAGREES`;
round 15 the honest result with a zeroed datum hash pushed the same way and caught as
`DATUM_MISMATCH`; a paused feed refusing a proposal; `latestRound` probing backwards from the clock's round; bundles
verified `CALLER_PINNED_ROOT` and `INTERNAL_CONSISTENCY_ONLY`; wrong members, a forged
observation, a stripped approval consumption, a relabelled round and feed, and a swapped
observation all failing; the portal and gateway routes with a second feed defined, observed,
proposed, approved, and applied through the gateway), `:products:attestation-feed:cli:test` (6),
the console (`npm run check` 0 errors, `npm test` 16 including the aggregation vectors and the
datum bytes pinned in both languages, `npm run build`), `verifyArtifactInventory`,
`verifyJvmOnlyBuild`, and `verifyYanoXJvmDistribution` pass. The cluster test writes the goldens
(`golden-round.json`, `golden-open-round.json`, `golden-wrong-round.json`,
`golden-members.json`, `golden-round-request.json`) the CLI and console tests pin.

**Live (pre14, the launcher on the showcase's Yano home).** `feed.sh up` started three members
(threshold 2, HTTP 7570); `feed.sh demo` defined `coldstore-7` from genesis knowledge, simulated
the three sources for round 10 (`source-gamma` an outlier at −9.13 °C), proposed at height 4,
had `publisher-a` and `publisher-c` (both `feed-ops`) approve and the apply refused with
`APPROVAL_NOT_APPROVED` at height 8, had `publisher-b` approve and the record applied at height
10 (`CLOSED −18.30 °C` from `source-alpha` and `source-beta`), verified the round at exit 5 with
the candidate datum bound, then recorded round 11 as `NO_QUORUM` at height 15. The portal
listed the feed and its rounds with the calendar's current round, served `latest` (round 11),
the round-10 view, proof, and datum (`bindsRecord` true), and answered 404 and 400 as §2.4
says; the served bundle re-verified offline at exit 5 under the members file and 6
bundle-declared. The gateway refused a request without its token (401), listed the eight demo
actors with their chain roles, and applied an observation. The console, served as a static site
in headless Chrome, opened the latest round (`NO_QUORUM`, proofs `BOUND`, five proof rows
`BOUND`, the record `AGREES` with approval consumption proven), downloaded a bundle the CLI
verified at exit 5, submitted two observations as `source-gamma` and `source-alpha` through the
gateway (round 12, heights 17 and 18), proposed, approved as `publisher-a` and `publisher-b`
(each approval recomputed on the gateway first), applied at height 22, and reopened round 12 as
`CLOSED` with the record `AGREES` and the browser-recomputed datum `BINDS the record`.

**Deviations from the text above.**

| Section | Deviation |
|---|---|
| §2.1 | The demo organizations of the sources are `coldchain-alpha`, `coldchain-beta`, `coldchain-gamma` (the feed is a cold-store temperature, §2.6), not exchanges. |
| §2.2 | `Aggregation` and `FeedDatum` live in the profile module beside the codecs (the CLI and the console pin their vectors); the client holds the chain-facing code. |
| §2.2 | `FeedClient.round(feedId, round, height)` with a height answers everything at that height, which is the comparison bundle §4 describes; without a height a closed round is answered at its declared closing height and its apply height, so the bundle of a closed round never changes. |
| §2.2 | `latestRound` probes with point queries (no proof) and then answers the round it found with proofs; `feeds()` replays finalized commands through the ADR-051 bound. |
| §2.4 | The round path serves the view; the bundle is at `/rounds/{round}/proof` and `/latest/proof`, as the DPP portal does; the text was aligned. |
| §2.5 | The console recomputes the candidate datum too (the browser CBOR helper gained negative integers and the constructor tag) and shows whether it binds the record. |
| §5 | `StdlibContractCbor.decodeArray` admits unsigned integers only, so `FeedValues` carries its own bounded decoder for the signed values; the schema validator accepts them. |

**Findings.** The map answers absence proofs at historical heights for keys present at the tip
(probed before the design was fixed), which is what lets a bundle pin the observations at the
closing height and the record at the apply height. The approval route and its consumption proof
carried the round close unchanged from ADR-051, with the request document generalized to
"the record decoded from the command" so that a publisher recomputes before signing. The
stdlib's canonical CBOR helper rejects negative integers by design (its safe tree admits
unsigned integers only), while the schema language's `INTEGER_ANY` accepts them; a product with
signed values decodes on its own. Nothing in core changed.
