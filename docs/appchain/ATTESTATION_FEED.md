# Attestation Feed

> **Experimental starter.** The Attestation Feed is the observation ledger that ADR-046 §4.6
> sequences last and that Yano ADR app-layer/014 lists as available today: ordered, source-signed
> observations, approval-gated round results, and proofs. It runs configuration-only on the stock
> governed `authenticated-map`. It is not the oracle pipeline that ADR app-layer/012 designs: the
> aggregate is recomputed by every verifier rather than computed in consensus, and nothing is
> published to Cardano. It must not be pitched as a public price oracle. The decision record is
> [ADR-052](../../adr/052-data-attestation-feed-starter.md).

The starter gives a consortium a chain where a feed administrator defines feeds (unit, scale,
round calendar, sources, quorum, outlier rule), each source records its observation for a round
under its own key, a feed operator proposes the round's result computed at one height, two
publishers from distinct organizations approve after recomputing it themselves, and the map
records the result once. A round bundle answers the feed policy and every source's observation
with a proof at the closing height and the record with its approval consumption at the apply
height; `yano-feed verify` and the console recompute the round from those answers with the same
integer rules and compare it with the record. A public portal serves rounds; a signing gateway
signs for the console; the `yano-feed` CLI does everything from a terminal.

## What it does

| Journey | How |
|---|---|
| Define a feed | Governed-role write as a `feed-admin`: description, unit, scale, `epochStart` and `roundSeconds` (the calendar), 1–16 source actors, quorum, outlier tolerance (ppm of the median or an absolute amount), value bounds, status |
| Observe | As a `source`: one `PUT_IF_ABSENT` per round under `<feed>/<round>/<source>`; the value is a signed integer at the feed's scale, the time is the source's signed business time |
| Close a round | A `feed-operator` proposes the record computed at a height (the request document carries it); `publisher`s approve only after their own recomputation agrees; the map command carrying the approval reference is applied once |
| Read a round | `yano-feed round get`, the portal (`/feeds/{id}/rounds/{n}`), or the console: the policy, one row per source with its disposition, the recomputed result beside the record, the candidate datum, one proof row per answer |
| Verify offline | `yano-feed verify --bundle round.json [--members keys.json \| --anchor-datum-hex …]` at the ADR-047 trust levels; a record that disagrees with the recomputation fails |
| Simulate sources | `yano-feed simulate`, the mock source adapter of ADR-046 §4.6: deterministic readings around a base, one optional outlier |
| Operate from a browser | The console's Source and Operator views drive the gateway on the operator's machine; the Round view needs no secret |

## Modules

| Module | Role |
|---|---|
| `products/attestation-feed/profile` | `FeedStarterProfile` (collections, policies, schemas, keys, calendar), `FeedValues` (canonical CBOR codecs), `Aggregation` (`feed-aggregation-v1`), `FeedDatum` (`feed-datum-candidate-v1`), `FeedGenesis` (demo descriptor, genesis) |
| `products/attestation-feed/client` | `FeedClient` (writes, round close, bundles), `RoundBundle` and `FeedVerifier`, `RoundView`, `RoundRequest`, `PortalService`, `GatewayService` |
| `products/attestation-feed/cli` | `yano-feed` |
| `products/attestation-feed/ui` | The console (SvelteKit static site) |
| `products/attestation-feed/harness/feed.sh` | The launcher: a three-member starter chain, the demo journey, the portal, the gateway |

The map client, signer, and verifier come from the Trust Registry (`products/trust-registry/client`,
ADR-049); the approval round, request document, bundle, portal, and gateway patterns from the DPP
Starter (ADR-051); the trust input from Attest (ADR-047).

## Data model

Three collections on the governed map, every value canonical CBOR checked by a genesis-bound
schema, no restore:

| Collection | Who writes | Key | Value |
|---|---|---|---|
| `feeds` | `feed-admin` (policy `feed-admin-write`) | feed id (`[a-z0-9][a-z0-9._-]{0,31}`) | `[1, description, unit, scale, epochStart, roundSeconds, sources[], minimumSources, maximumDeviationPpm, maximumDeviationAbsolute, minimumValue, maximumValue, status]` |
| `observations` | `source` (`source-write`) | `<feedId>/<round>/<sourceId>` | `[1, value, observedAt, evidenceSha256, note]` |
| `rounds` | approval policy `round-close`: proposer `feed-operator`, two `publisher`s from distinct organizations | `<feedId>/<round>` | `[1, status, closedAtHeight, aggregate, scale, acceptedSources[], policySha256, datumSha256]` |

A value is a signed integer at the feed's `scale`: `-1825` at scale 2 is −18.25. The calendar is
`start(r) = epochStart + r × roundSeconds`, `end(r) = start(r + 1) − 1`; a source's round is the
one its signed `observedAt` falls in. A round record is `CLOSED` (with the aggregate) or
`NO_QUORUM`; `closedAtHeight` is the height the proposer computed at, `policySha256` the hash of
the feed value it computed under, `datumSha256` the hash of the candidate datum.

**Aggregation, `feed-aggregation-v1`** (ADR app-layer/012 §8.4 with one reporter per source and no
prior-round state). For each configured source, from the observation answered at the closing
height: `ABSENT`, `REVOKED`, `FOREIGN_WRITER` (another actor wrote the key), `EQUIVOCATED`
(revision above 1), `WRONG_ROUND` (`observedAt` outside the window), `OUT_OF_RANGE`, else a
candidate. Fewer candidates than the quorum: `NO_QUORUM`. Otherwise the reference is the lower
median of the candidates sorted by value then source id; a candidate farther than
`max(maximumDeviationAbsolute, floor(|median| × maximumDeviationPpm / 1,000,000))` from it is an
`OUTLIER`; quorum is checked again; the aggregate is the lower median of what remains. Nothing
averages or rounds; every client computes with exact integers.

**The candidate datum, `feed-datum-candidate-v1`**, is Plutus data (constructor 0) over
`[sha256(chainId), sha256(feedId), round, aggregate, scale, roundEnd, closedAtHeight, stateRoot at
closedAtHeight, acceptedCount, 1]`. It is what the deferred Cardano publication executor would
publish (ADR-012 §10–13); its hash sits in the record so the executor has something exact to bind
to. Nothing publishes it.

The demo consortium: `coldchain-alpha`, `coldchain-beta`, `coldchain-gamma` (`source-alpha`,
`source-beta`, `source-gamma`: sources), `feed-ops` (`ops-a`: feed operator; `publisher-a` and
`publisher-c`: publishers, so a second approval from one organization can be shown to be
insufficient), `audit-guild` (`publisher-b`: publisher), and `feed-consortium` (`feed-admin-a`, the
administrator authority). Demo seeds are `sha256("yano-attestation-feed-demo-actor:" + actorId)`,
showcase-only material. The demo feed is `coldstore-7`, a cold-store temperature in `degC` at
scale 2 with one-minute rounds.

## Setup

### Option A: the launcher

The launcher needs an extracted Yano X JVM distribution (`yano.jar`, `plugins/`, `config/`,
`appchain-cluster/cluster.sh`). From the repository, build and extract the distribution and build
the CLI once (add the Yano version properties the repository
currently requires, see `docs/BUILD_AND_TEST.md`):

```bash
./gradlew :distribution:jvm:yanoXJvmDistZip :products:attestation-feed:cli:installDist
unzip -qo distribution/jvm/build/distributions/yano-x-jvm-*.zip -d build/yano-x
export FEED_YANO_HOME=$(echo "$PWD"/build/yano-x/yano-x-jvm-*)
products/attestation-feed/harness/feed.sh up
products/attestation-feed/harness/feed.sh demo
products/attestation-feed/harness/feed.sh portal
products/attestation-feed/harness/feed.sh gateway
```

From the JVM distribution, `examples/attestation-feed/feed.sh up` finds the distribution and
`tools/yano-feed` by itself. `up` derives the launcher's demo member keys, generates the demo
genesis with `yano-feed genesis --demo`, writes a home with one `attestation-feed-chain`, and
starts three members on `http://127.0.0.1:7570..7572/api/v1` (`--http-base`, `--server-base`,
`--nodes`, `--threshold`, `--instance` change that). `demo` runs the journey through the CLI:
`feed-admin-a` defines `coldstore-7` with one-minute rounds starting ten minutes ago; three
simulated sources report the current round with `source-gamma` as an outlier; `ops-a` proposes;
`publisher-a` and `publisher-c` (both `feed-ops`) approve and the apply is refused
(`APPROVAL_NOT_APPROVED`); `publisher-b` approves and the record is applied; the round is
verified under the members file and written to the instance directory; the next round gets one
observation and is recorded as `NO_QUORUM`. `portal` and `gateway` start the two services on
8680 and 8690 (`--portal-port`, `--gateway-port`); the gateway prints its token. `feed.sh env`
prints shell exports:

```bash
eval "$(products/attestation-feed/harness/feed.sh env)"
# YANO_FEED_URL, YANO_FEED_CHAIN, YANO_API_KEY, YANO_FEED_GENESIS_ID, YANO_FEED_SEEDS,
# YANO_FEED_MEMBERS, FEED_PORTAL_URL, FEED_GATEWAY_URL, FEED_GATEWAY_TOKEN
```

`status`, `seeds`, `stop`, and `clean` complete the launcher. Each member disables the devnet
profile's L1 history projection: a starter member needs no L1 history, and members sharing one
home must not share one archive.

### Option B: your own chain

1. Write a descriptor. `yano-feed descriptor --demo --output feed.json` prints the demo
   descriptor to start from; it is the ADR-049 actor descriptor (organizations, actors with roles
   and key proofs, the administrator authority) with no issuers or schemas. For real actors, each
   actor runs `yano-feed actor-key --actor <id> --seed-file <owner-only seed> --chain <chain id>`
   and sends back the public key and key proof; the seed never leaves the actor's machine. Give
   every source the `source` role, the operations team `feed-operator`, the approvers
   `publisher` in at least two organizations, and the administrator `feed-admin`.
2. Generate the genesis: `yano-feed genesis --descriptor feed.json --members <key,...>
   --threshold <n> --chain-index <i>` prints the four `yano.app-chain.chains[i].` properties.
   Every member must use the same descriptor, members, and threshold: the genesis id is part of
   the chain's identity.
3. Configure the chain as `state-machine: authenticated-map` with `membership.mode: governed`
   and the four properties on every node; the launcher's `write_home` and `write_node_configs`
   show the shape.
4. Point the CLI at a node: `--url http://<node>/api/v1 --chain <chain id> --api-key-file
   <file>` (or `YANO_FEED_URL`, `YANO_FEED_CHAIN`, `YANO_API_KEY`). The first write on a chain
   that has no block yet passes `--genesis-id <state.genesis-id>`.
5. Define the first feed with `yano-feed feed create --feed <id> --spec <feed.json>` as a
   `feed-admin`; the specification is the JSON object of the data model above (`epochStart`
   in epoch seconds, `sources` as actor ids, values at the feed's scale).

### Build and serve the console

```bash
./gradlew :products:attestation-feed:ui:frontendBuild   # build/site
```

Serve `products/attestation-feed/ui/build/site` (or `product-ui/attestation-feed` from the
distribution) from any static host. `feed-ui-config.json` next to `index.html` names the portal
the Round view reads (`serviceUrl`) and, for sources and operators only, a gateway
(`gatewayUrl`); remote URLs must be HTTPS, HTTP is allowed on loopback. A link of the form
`#/feeds/<id>/rounds/<n>` opens a round at once.

## Walkthrough on the launcher

With `eval "$(products/attestation-feed/harness/feed.sh env)"` in the shell and `yano-feed` on
the path (`products/attestation-feed/cli/build/install/yano-feed/bin/yano-feed` or
`tools/yano-feed/bin/yano-feed`):

```bash
# The feed and the rounds that have records.
yano-feed feed get --feed coldstore-7

# The newest round with a record, verified under the members file (exit 5), exported.
yano-feed round get --feed coldstore-7 --round latest --members "$YANO_FEED_MEMBERS" --output round.json
yano-feed verify --bundle round.json                              # bundle-declared members: exit 6
yano-feed verify --bundle round.json --members "$YANO_FEED_MEMBERS"   # exit 5
yano-feed datum --bundle round.json                               # the candidate datum, hex and hash

# A source's own observation for the current round, then the open round previewed at the tip.
yano-feed observe --feed coldstore-7 --value -1822 --note "sensor 3" \
  --actor source-beta --seed-file "$YANO_FEED_SEEDS/source-beta.seed"
yano-feed round get --feed coldstore-7 --round <current round>

# Close it: propose at the tip, approve from two organizations (each approval recomputes), apply.
yano-feed round propose --feed coldstore-7 --round <round> --output request.json \
  --actor ops-a --seed-file "$YANO_FEED_SEEDS/ops-a.seed"
yano-feed round approve --request request.json --actor publisher-a --seed-file "$YANO_FEED_SEEDS/publisher-a.seed"
yano-feed round approve --request request.json --actor publisher-b --seed-file "$YANO_FEED_SEEDS/publisher-b.seed"
yano-feed round apply --request request.json

# Re-answer a closed round at a later height and compare (ADR-052 §4).
yano-feed round get --feed coldstore-7 --round <round> --height <later height> --members "$YANO_FEED_MEMBERS"

# Pause the feed: a paused feed closes no rounds.
yano-feed feed get --feed coldstore-7 --json > spec.json   # edit "status": "PAUSED", keep the rest
yano-feed feed update --feed coldstore-7 --spec spec.json --actor feed-admin-a --seed-file "$YANO_FEED_SEEDS/feed-admin-a.seed"
```

The `round get` output lists the feed policy, one line per source with its value, time,
disposition, revision, and writer, the recomputed result, the record with "AGREES" or
"DISAGREES" and "approval consumption proven", the datum hash, and the verification with its
checks. `--round latest` probes backwards from the calendar's current round (by the CLI's clock)
through at most 64 rounds for a record.

Portal routes: `GET /feeds`, `GET /feeds/{id}`, `GET /feeds/{id}/rounds/{n}[?height=]` (the
view), `GET /feeds/{id}/rounds/{n}/proof` (the `feed-round-v1` bundle),
`GET /feeds/{id}/rounds/{n}/datum` (closed rounds only), `GET /feeds/{id}/latest[/proof]`,
`GET /healthz`. An unknown feed answers 404; every round number is valid on the calendar, so a
round without a record answers 200 as `OPEN` with a preview recomputation. Gateway routes
(`X-Gateway-Token` on every request): `GET /operator/actors`, `POST /source/observe`,
`POST /operator/feeds`, and `POST /operator/rounds/propose|approve|reject|apply` (the request
document travels in the `request` field). Every write answers with the message id and the
receipt; an approval whose recomputation disagrees with the request answers 409.

## The console

- **Round** reads the portal only. Enter a feed id and a round (or `latest`); the view shows the
  status and binding pills, the feed policy, a sources table with each observation's value,
  signed time, disposition, revision, writer, and proof binding, the round recomputed in the
  browser beside the recorded one with "AGREES" or "DISAGREES", the approval consumption, the
  candidate datum recomputed and compared with the record's hash, and a proof-rows table. Every
  answer and fact is checked to name the bundle's chain and genesis and to sit at the declared
  heights; "Download round bundle" exports what `yano-feed verify` accepts.
- **Source** connects to a gateway with its token (kept in memory), picks a source actor, and
  submits a reading typed as a decimal at the feed's scale with its signed time; the receipt
  names the round.
- **Operator** defines or updates a feed and runs the round close: propose (the dispositions at
  the closing height are shown), approve (the gateway recomputes first), reject, apply, with the
  request document passed between the operator and the publishers.
- **What this proves** explains the rows.

## Proof story, flags, and trust levels

A verified round bundle says what the consortium agreed about round `r` of feed `F`: at the
closing height `h0` the policy was this and each configured source had exactly this observation
or none; the recomputation under that policy yields this result; at height `H` the consortium
recorded exactly that result, applied once through an approval round whose consumption is
proven, and never rewrote it; the roots were certified by the pinned members and, when
anchored, Cardano carries them. It never says that a reading is true, that sources are
independent, that the closing height was a fair moment (an observation one block later is
invisible to it; re-answer the round at a later height and compare), or that anything reached
Cardano.

Flags: `RECORD_DISAGREES` (the record differs from the recomputation), `WRONG_POLICY` (the
record binds another feed value than the one proven at `h0`), `DATUM_MISMATCH`, `FEED_PAUSED`,
`REWRITTEN` (the record's revision is above 1), `UNPROVEN_APPROVAL` (no approval consumption
proof), `LATER_HEIGHT` (a comparison bundle at another height than the record declares; it is
compared, not verified), `RECORD_REVOKED`. Per-source dispositions are listed under the data
model.

| Exit | Meaning |
|---|---|
| 0 | verified with an independent anchor (or a write applied) |
| 2 | usage |
| 3 | node unavailable or malformed response |
| 4 | invalid: a failed verification, a record that disagrees with the recomputation, a refused approval, or a rejected write |
| 5 | verified with caller-pinned members (`--members`) |
| 6 | consistent under the bundle's own signers (no caller trust) |

## Security notes

- Seeds are read from owner-only files (CLI) or an owner-only directory (gateway, `simulate`)
  and never logged. The gateway belongs on the operator's machine: it binds to loopback by
  default, refuses a non-loopback bind without `--allow-remote`, requires its random token on
  every request, and never returns a seed. The console keeps the token in memory only.
- Direct-role authorizations are one-use, chain- and genesis-scoped, and expire within 100
  blocks; a round-close proposal within 600. A feed update is a compare-and-set on the revision
  read.
- A source cannot be prevented from rewriting its observation or writing another source's key
  by configuration; the ledger records the revision and the writer, and the verifier excludes
  such observations and names them. Values are visible to every member; a feed with
  confidential readings does not belong on this starter.
- The portal is read-only with bounded responses and a bounded replay for feed discovery
  (5,000 commands; observations count); it exposes no node API key. `feed.sh env` prints the
  node API key and the gateway token; treat its output as a secret.
- Nothing in the starter connects to Cardano, spends, or signs a transaction.

## Troubleshooting

- `the chain has no block yet; pass the generated state.genesis-id as --genesis-id`: the first
  write on a fresh chain cannot read the map genesis; pass `--genesis-id`. The launcher's `demo`
  does this.
- A write is `REJECTED` with `UNAUTHORIZED` or `ACTOR_INELIGIBLE`: the actor lacks the
  collection's role; `ALREADY_EXISTS`: the source already observed this round (an honest retry;
  a rewrite has to be deliberate); `APPROVAL_NOT_APPROVED`: the proposal has fewer than two
  publisher approvals from distinct organizations, or it expired.
- `the recomputation ... disagrees with the request; not approving`: an observation landed
  between the proposer's closing height and now is not the cause (the recomputation is at the
  declared height); the request was built from a different chain state or was tampered with.
  Propose again.
- `round ... already has a record`, or `feed ... is paused`: the round is closed, or the feed
  closes no rounds until it is set `ACTIVE` again.
- `--round latest` finds nothing: no round within 64 rounds of the CLI clock's current round
  has a record; pass the round number.
- `--members` verification exits 4 with "finality certificate": the members file does not name
  this chain's members or threshold; `feed.sh up` writes the right one to the instance directory.
- The console cannot reach the portal from a page served over HTTPS: the portal must be served
  over HTTPS too, or the console over HTTP on loopback.

## Tests

- `./gradlew :products:attestation-feed:profile:test`: genesis determinism, schema acceptance
  and rejection (source list bounds, negative values, the signed-integer bound), value codecs,
  keys, the calendar, the aggregation vectors, the datum vector, firewall.
- `./gradlew :products:attestation-feed:client:test`: the request document, the projection,
  and the cluster test on a real three-member governed map (the whole journey including a
  same-organization approval refused, a late observation, a `NO_QUORUM` round, every
  disposition, a wrong record pushed past the approvers and caught by the verifier, a paused
  feed, tampering, the portal and gateway routes); `-PfeedGoldenWrite=true` regenerates the
  goldens the CLI and console tests pin.
- `./gradlew :products:attestation-feed:cli:test`, `npm test` in `products/attestation-feed/ui`
  (the aggregation vectors run in both languages against the same goldens).
