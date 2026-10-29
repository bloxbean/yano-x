# Namespaces, writer policies and session keys

Every message on an app chain names a **topic**, such as
`freight/acme/shipments`. On any chain the topic is a label. On a chain sealed
with a `topics:` block, a **topic registry** in the chain's state also decides
who may write each topic, and every member enforces it before any state machine
sees the message. Yano owns the registry, its checks and its proofs; Yano X
supplies the `appchain topics` commands, the clients and the composite rules.

The examples on this page follow one chain: a freight consortium whose members
share delivery records. The consortium owns the `freight/` namespace, each
member company runs its own namespace below it, and Acme's drivers record
delivery scans on `freight/acme/shipments` from a web app.

## One topic name, used everywhere

<!-- illustration: topic-touchpoints -->

A chain gets a registry from its draft: add `topics:` to the chain
(`./yano.sh appchain new --topics <chain>=members`, or answer the wizard), with
governed membership. It is sealed into the chain's genesis; see
[configure an application](deployment/configure.md). Without it, topics work
as before and anyone who can submit to a node may write any topic.

## Who registers, manages and writes

<!-- illustration: topic-roles -->

| Who | What they may do | How |
|---|---|---|
| **The members**, by a vote | Create a top-level namespace (`create: members`). Always available, also to recover a namespace whose owners lost their keys | `topics init`, `plan`, then `topics approve` on each node |
| **Registrars**, keys named in the draft | Create a top-level namespace alone (`create: registrars`) | `topics init`, `plan`, `sign`, `submit` |
| **Anyone**, first come | Claim an unused single-segment name (`create: anyone`) | the same, signed by the new owners |
| **The owners** of a namespace (k-of-n keys) | Create entries below it, change policies, rotate owners, retire entries, revoke credentials | `topics plan`, `sign`, `submit` |
| **Writers** (k-of-n keys, or anyone when `writers: open`) | Write messages on the topic | the [Java or JavaScript client](TOPIC_SESSION_KEYS.md#use-a-browser-session) |
| **An issuer**: a writer listed under `delegate` | Sign short-lived credentials so users write with session keys | an application service; `topics delegate` for tests |

Names form a tree:

- **A namespace ends in `/`** and covers every topic below it. The longest
  matching entry wins: `freight/acme/` can override single fields of
  `freight/`, and an omitted field inherits. `writers: open`, an empty
  `delegate` list and `schema: none` are explicit overrides. Retirement is
  inherited, and a child cannot undo it.
- **A topic no entry covers** follows the draft's `unregistered:` setting.
  `open`, the default of `appchain new`, accepts it as before. `reject`
  refuses it with `TOPIC_UNREGISTERED`, and the chain then makes blocks only
  from registered topics. Topics starting with `~` are system topics.
- **Changes take effect later**: a committed change activates after the
  chain's activation lag (10 blocks by default), never retroactively, so every
  earlier message stays verifiable under the policy it was written under.

A policy has these fields:

| Field | Meaning |
|---|---|
| `writers` | `open`, or `threshold` of `keys`: the signatures a write needs |
| `delegate` | Writer keys that may issue session credentials. Only with a 1-of-n writer set |
| `maxDelegationMs` | The longest credential lifetime; the chain's `max-delegation-ms` (one hour by default) also caps it |
| `maxPayloadBytes` | The largest payload. It applies to open topics too |
| `schemaCddl` | An optional CBOR schema for the payload (see below) |

Some common policies:

| Use case | Topic | Policy |
|---|---|---|
| Delivery scans from drivers' phones | `freight/acme/shipments` | `writers` 1 of [login service, warehouse system]; the login service in `delegate` gives drivers one-hour session keys; a schema checks `tempC` |
| Payments that need two officers | `finance/payments/approvals` | `writers` 2 of [three officers' keys]; no delegation |
| Readings from plant gateways | `iot/plant-7/readings` | `writers` 1 of [the gateways' keys]; `maxPayloadBytes` 512; a schema |
| Feedback from any partner | `partners/feedback` | `writers: open`; size and schema still apply |
| Scratch or test traffic | not registered | accepted with `unregistered: open`, refused with `reject` |

## A write on a protected topic

<!-- illustration: topic-write-check -->
1. **Log in.** A driver signs in to the delivery app by any means it likes,
   such as the company's SSO, and the app makes a session key that never leaves
   the device.
2. **Issue a credential.** The login service signs a delegation for that
   session key, one scope (`freight/acme/`) and a short time window.
3. **Write.** The app signs a frame (payload plus write proof) and posts it
   to any member.
4. **Check the policy.** The member resolves the effective policy and runs the
   checks in order; the first failure refuses with `400` and a code.
5. **Run the machine.** Only then do the state machine's admission and any
   composite rules run, on the verified payload.
6. **Finalize.** The proposer and every follower repeat the checks; a follower
   rejects a block that breaks them.
<!-- /illustration -->

- **A direct write** carries the policy's threshold of writer signatures
  instead of a credential.
- **A credential stands in for one writer.** Its issuer must be listed under
  `delegate` and belong to a 1-of-n writer set; it cannot satisfy a 2-of-n
  policy by itself. A change to the allowed issuers takes effect at its
  activation height.
- **Signatures bind the chain generation**, so a credential copied to a fresh
  chain grants nothing. A write also binds the topic, payload hash, nonce and
  expiry, and the host rejects reused, revoked, expired or out-of-scope writes.
- **The machine sees the payload, not the frame.** Machine hooks receive the
  verified payload through `getBody()`, while the message id, receipt and
  message proof follow the submitted frame. Do not strip a frame from an
  archived message or make up a replacement id.

| Code | The write was refused because |
|---|---|
| `TOPIC_UNREGISTERED` | no entry covers the topic, and the chain rejects unregistered topics |
| `TOPIC_RETIRED` | the entry is retired |
| `TOPIC_FRAME_INVALID` | the topic is protected and the body is not a canonical frame |
| `TOPIC_PROOF_EXPIRED` | the write proof's `notAfter` has passed or is too far ahead |
| `TOPIC_WRITER_DENIED` | signatures are missing, invalid, below the threshold, or not from writer keys |
| `TOPIC_DELEGATION_INVALID` | the credential's issuer, scope or time window is wrong, or it was revoked |
| `TOPIC_PROOF_REPLAYED` | this write was already used |
| `TOPIC_PAYLOAD_TOO_LARGE` | the payload is above `maxPayloadBytes` |
| `TOPIC_SCHEMA_REJECTED` | the payload is not canonical CBOR or does not match the schema |

Registry commands have their own codes: `TOPIC_COMMAND_INVALID`, `_DENIED`,
`_EXPIRED`, `_STALE`, `_LIMIT` and `_SUPERSEDED`.

## Payload schemas

Topic schemas use the same `yano-cbor-schema-ir-v1` evaluator as
`authenticated-map`. CDDL is compiled by `CddlSchemaCompiler` before
submission; members only evaluate the committed IR. Put a topic
schema on an opaque machine such as `ordered-log`. Typed kernels such as
`balances` already define their command format and reject a second topic
schema. Composite rules can read the verified payload; see
[admission rules](bindings/07-admission-rules.md).

## Read the registry

| You want | CLI | REST, below `/api/v1/app-chain/chains/{chainId}` |
|---|---|---|
| The chain's topic settings | `topics init` reads them | `GET /topics/config` |
| One entry and its revisions | | `GET /topics/entries/{name}` |
| A topic's effective policy at the tip | | `GET /topics/resolve?topic=` |
| The same for a block, with proofs | `topics resolve <topic> [--height <n>]`, verified | `GET /topics/proof?topic=&height=` |
| A schema's IR | | `GET /topics/schemas/{hash}` |
| The outcome of a registry command | | `GET /topics/receipts/{messageId}` |

All of these are reads. In an entry name, encode the trailing slash:
`/topics/entries/freight%2F`. Owners' commands are submitted as ordinary messages on
`~topics/registry`; members' votes go through the privileged
`POST /admin/topics/governance`, which `topics approve` calls on each
operator's own node. See the [REST API](../site/reference-rest-api.md#topic-registry).

## Proofs and trust

An entry-state proof establishes one registry record at a root. It does not
establish a topic's effective policy: ancestors, the absence of overrides and
the configuration also matter. `appchain topics resolve` returns a
`topic-policy-v1` effective-policy bundle for a candidate height, anchored to
its parent state's root. It includes the configuration revision and source
entry revisions, including disabled and unregistered outcomes.

Entry-state proofs come from the generic state proof API, not a separate
`topic-entry-v1` typed endpoint. Studio labels them as entry-only proofs, and
its downloaded peer responses are not independent verification. For
authorship, `appchain topics verify` requires the message, frame, generation
and complete effective-policy, replay, revocation and schema evidence, plus
independently trusted block/root information; the
[how-to](TOPIC_SESSION_KEYS.md#diagnose-and-verify) lists the inputs. It refuses entry-only data. A
valid signature alone does not establish current authority or finality.

## Preview upgrade boundary

This implementation pins the unreleased Yano host at
`0.1.0-pre19-4e8accd-SNAPSHOT`, API level 13, with its matching ordinary JVM
ZIP. Qualify it on a fresh app-chain genesis. Existing retained deployments
remain on their exact host, profile and genesis; this change supplies no
in-place migration or reset procedure.

The old collection schema vectors keep their IR bytes and results. The merged
core evaluator also hardens the work budget by charging bytes examined in
literal comparisons. A pathological legacy value can therefore be rejected
at that budget boundary; the regression suite records this case. This is an
intentional prerelease boundary, not a claim that every historical state can
be replayed under the new host. The host also adds a committed clock-skew field
to its consensus profile. Keep old stores with the binary that created them.

Next: [protect a topic and give users session keys](TOPIC_SESSION_KEYS.md).
