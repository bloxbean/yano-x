# ADR-053: Console signing modes and guided operation

- **Status:** Proposed (2026-09-06)
- **Classification:** `FIRST_PARTY_OPTIONAL`, maturity `preview`, inheriting the devnet posture
  of the products it touches. The DPP starter stays a labelled prototype and the attestation feed
  stays experimental; this decision changes how their consoles are operated, not what they claim.
- **Scope:** `products/trust-registry/{client,cli,ui,harness}` and the operator views of
  `products/{dpp,attestation-feed}/ui`; no consensus, contract, state-machine, profile, or genesis
  change, and no change to any proof, answer, or bundle format
- **Relates to:** ADR-049 (the registry whose console is read-only today), ADR-051 §2.4 and §2.5
  (the operator gateway and console this generalizes), ADR-052 §2.4 (the feed's gateway and
  console), ADR-048 §4 (the Evidence Desk's in-browser actor signer this reuses), ADR-046 §4.2
  (the registry product), ADR-037 (trust levels, which browser signing does not alter)

## 1. Context

Three products now write to a governed `authenticated-map`: the trust registry (ADR-049), the DPP
starter (ADR-051), and the attestation feed (ADR-052). Every write on such a chain carries a
one-use actor authorization signed with the actor's Ed25519 key, so the key has to be somewhere.
The three products answered that question differently, and the difference was never a decision.

| Product | How a write is signed today | Can a browser write? |
|---|---|---|
| Trust registry | `yano-trust` reads a seed file | no |
| DPP starter | `yano-dpp gateway` holds the seeds and signs for a named actor | yes |
| Attestation feed | `yano-feed gateway` holds the seeds and signs for a named actor | yes |
| Evidence Desk (ADR-048) | the browser holds the seed for the session and signs | yes |

Two problems follow. The first is coverage: the registry is the only map product that cannot be
demonstrated from a browser at all, which makes it the odd one out in a portfolio whose other
consoles are the demonstration. The second is honesty about the trust boundary. A gateway that
holds seeds can sign as any actor it holds, so a proof that names `issuer-a` proves that the
gateway signed, not that a person at that organization decided. That is acceptable for a local
demo and both gateways say so in prose, but the consoles do not distinguish it from the case where
the key never left the operator's tab, and the Evidence Desk already shows that the honest case is
implementable in a browser.

A third, smaller problem surfaced in manual testing: an operator view that lists forms is only
usable with a guide open beside it. The DPP console has since been changed to derive the product's
lifecycle from its own public passport and present it as steps with the role each needs; this
decision adopts that as the pattern for map consoles rather than leaving it a one-off.

Sessions, user accounts, and passwords are explicitly not the answer to any of this. The domain
roles already exist on chain, declared in genesis, bound to organizations and to the policies the
chain enforces on every write. A second copy of them inside a console would be a source of truth
that the chain never consults.

## 2. Decision

Give every governed-map console the same two ways to sign, make the active one visible, and give
the registry the gateway and write view the other two products already have.

### 2.1 Two signing modes, named and visible

A console that writes offers exactly two modes and states which one is active in the operator
view's header:

| Mode | Where the key is | What a proof then means | Intended use |
|---|---|---|---|
| `GATEWAY` | in the gateway process, loaded from seed files at start | the gateway signed as this actor | local demos, a single operator running their own gateway |
| `BROWSER_KEY` | in this browser tab, for this session only | the holder of this actor's key signed | showing the honest trust boundary; an actor signing for itself |

`BROWSER_KEY` reuses the Evidence Desk's key handling (ADR-048 §4): the seed is parsed from hex
text or a 32-byte file, imported once through WebCrypto Ed25519, held in a closure, never placed
in component state, storage, logs, or URLs, and zeroed on release. The Evidence Desk's
`sign(statement)` builds a role-workflow statement preimage, so the shared signer gains a
`signBytes(preimage)` variant for the map's `MapActorAuthorizationV1`; the key handling itself is
unchanged. The console shows the derived public key so the operator can confirm it matches the
actor's registered key before signing, and offers an explicit Lock control that releases it. A
browser without WebCrypto Ed25519 is told to use the CLI; the console does not fall back to a
software implementation.

**Submitting a browser-signed write.** Signing is not enough: the command must reach the node, and
the signer first needs the actor's revision and key id, the policy id and revision, the map genesis
id, and the tip height for the authorization deadline. The registry console already holds a node
connection with an API key and reads all of that itself. The DPP and feed consoles today reach only
their portal and gateway, so `BROWSER_KEY` there adds a node connection to the operator view, in
the same shape the Attest, registry, and Evidence Desk consoles use. The alternative, relaying the
signed command through a gateway route, was rejected: it would leave `BROWSER_KEY` depending on the
gateway and its token, which is the dependency this mode exists to remove. The cost is one more
connection form and a node that must allow the console's origin, or be reached same-origin.

The browser signs with the **map genesis id** read from the chain's proven genesis marker, not the
state commitment identity: on a composite runtime the two differ, and actor authorizations are
signed with the component genesis id.

Neither mode is a login. There is no account, no password, no session cookie, and no server-side
notion of a signed-in user. Choosing an actor selects a key, and the chain decides what that key
may do.

**Feasibility.** The encodings a browser must reproduce (`AuthenticatedMapContract` mutations and
commands, `AuthenticatedMapAuthorizationContract.MapActionV1`, `actionCommitment`,
`MapActorAuthorizationV1`, and `encodeCommand`) are Java sources in this repository under
`state-machines/stdlib-contracts`, not opaque distribution classes. The TypeScript port is made
against those sources and pinned to vectors the Java signer produces for a fixed seed, actor,
policy revision, height, and authorization id, so drift on either side fails a test rather than a
node. If a vector cannot be reproduced, `BROWSER_KEY` is deferred and §2.2 lands alone.

### 2.2 The registry gains a gateway and a write view

`yano-trust gateway --seeds <dir> [--bind 127.0.0.1] [--port 8481] [--token-file <f>]` starts a
signing gateway shaped exactly like the DPP and feed gateways: a random bearer token printed once
and required in `X-Gateway-Token`, loopback binding unless `--allow-remote`, seeds read from
owner-only files, and no route that returns a seed. Routes:

| Route | Body | Writes |
|---|---|---|
| `GET /healthz` | | chain id, tip height |
| `GET /operator/actors` | | the actors this gateway holds seeds for, each with the organization and roles read from the chain |
| `POST /operator/status` | `actorId, listId, index, bit, reasonCode?` | a `status` entry |
| `POST /operator/status/revoke` | `actorId, listId, index` | the tombstone |
| `POST /operator/subjects` | `actorId, subjectId, controllerOrganizationId, kind, metadataHashHex` | a `subjects` entry |
| `POST /operator/subjects/revoke` | `actorId, subjectId` | the tombstone |
| `POST /operator/schemas` | `actorId, schemaId, valueBase64` | a `schemas` entry |
| `POST /operator/lists/publish` | `actorId, listId, purpose?, bitLength?` | replays and writes `status-lists` |

Every route returns the same receipt document the CLI prints: message id, applied height, and the
per-mutation collection, key, revision, and status. The gateway signs with
`TrustRegistrySigner.governedCommand`, which is what the CLI already calls, so the service adds a
transport and no new signing path. The `issuers` collection stays out: it is approval-gated and
ADR-049 §8 defers its route until there is a CLI for it.

The registry console gains an **Operator** view with the actor picker, the signing-mode control of
§2.1, and forms for the routes above, guided in the style of §2.3. Its **Look up** and **Status
lists** views are unchanged and still need no secret.

### 2.3 Guided operation instead of a form list

An operator view derives its guidance from what the chain already holds, through pure functions
over the product's own read model, and presents steps rather than forms. Each step carries the
role it needs, its state (`DONE`, `READY`, `NEEDS <role>`, `BLOCKED` behind a named earlier step),
what the chain already holds for it, and a shortcut that switches the signer to an actor holding
the role. One step is marked as the one to do next. Multi-party rounds show their own position:
for DPP certification, propose, then approve until two distinct organizations have signed, then
apply.

The guidance is derived, never authoritative. The console may not gate a write on it: an operator
can run a step the guide marks `NEEDS <role>`, and the chain refuses it with its own error code,
which is the behaviour a demonstration should be able to show. This was implemented for the DPP
console ahead of this ADR and is recorded here as the pattern; the registry adopts it in §2.2.

### 2.4 What stays out

No sessions, accounts, passwords, cookies, or server-side user records in any product. No console
that stores a seed beyond the tab's lifetime, in `localStorage` or anywhere else. No new
authorization concept: roles and policies remain what genesis declares and the chain enforces. No
gateway route that creates actors, because actors are genesis-declared and changing them changes
the genesis id.

## 3. Security

The gateway threat model is unchanged from ADR-051 §6 and now stated in the console: a gateway
holds every seed it was started with and will sign for any of them for anyone holding its token,
so it belongs on the operator's own machine, on loopback, with demo seeds. `BROWSER_KEY` narrows
that boundary to one actor and one tab; it does not widen it, because the console never sends the
seed anywhere and the node still verifies the signature and policy.

A browser-signed write is byte-identical to a CLI-signed one, so no verifier, answer, bundle, or
trust level changes. The signing mode is an operational fact about who held the key, not a
property of the proof, and no console displays it as one.

Demo seeds stay demo seeds: derived from public strings, owner-only on disk, never reused outside
a local demo. The console warns when a pasted seed matches a known demo derivation.

## 4. Testing

- Registry client: gateway routes on the in-process three-member cluster, including the token
  refusal, an unknown actor, a wrong-role write refused by the chain with its error code, and the
  receipt shape; the existing cluster test gains the gateway the way ADR-051's did.
- Registry CLI: `gateway` usage, seed directory validation, and token file handling.
- Registry console: the guided step model, the request bodies, and the read views unchanged.
- Browser signing: the ported command encoding is pinned against vectors produced by the Java
  signer for a fixed seed, actor, policy revision, height, and authorization id, so a drift in
  either direction fails a test rather than a node.
- Live: each console writes in both modes against a running cluster, and the resulting entries
  verify offline at `CALLER_PINNED_ROOT`.

## 5. Deferred

Browser signing for approval-routed writes beyond a single actor statement, an actor-key import
that reads a hardware or wallet-held key, gateway routes for the approval-gated `issuers`
collection, guided operation for the feed's operator view (the pattern is recorded in §2.3 and the
feed console keeps its current round-centred layout), and any notion of an operator identity that
outlives a tab.

## 6. Consequences

Every map product becomes demonstrable from a browser, and the portfolio stops having one product
whose story requires a terminal. The honest trust boundary becomes visible rather than a paragraph
in a guide, which matters for the production posture ADR-046 §9 asks each product to state. The
cost is a third gateway to maintain and a TypeScript encoder that must stay byte-compatible with
the Java contracts, which the vector tests exist to catch.

## 7. Implementation record

To be written when the implementation lands.
