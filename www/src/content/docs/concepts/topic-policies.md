---
title: "Namespaces, writer policies and session keys"
description: "A topic registry gives an app chain a committed policy for who may write each topic. Yano owns registry state, signature verification, activation and…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/appchain/TOPIC_POLICIES.md"
---
A topic registry gives an app chain a committed policy for who may write each
topic. Yano owns registry state, signature verification, activation and replay.
Yano X supplies authoring tools, clients and composite admission rules.

A namespace ends in `/`. `games/` covers `games/neon-rush/rides`, and the nearer
`games/neon-rush/` entry can override individual policy fields. An omitted field
inherits; `writers: open`, an empty delegation list and `schema: none` are
explicit overrides. Retirement is inherited and cannot be undone by a child.
Owner keys manage a namespace. Writer keys authorize messages. Members can
recover ownership through the chain's enabled governance path.

On a protected topic a message body is a canonical CBOR frame containing the
payload and its proof. Direct writes carry the required writer signatures.
Delegated writes carry a short-lived issuer credential and a session-key
signature. Both signatures bind the chain generation, so copying a credential
to a fresh chain does not grant authority. A signed write also binds the topic,
payload hash, nonce and expiry. The host rejects reused statements, revoked
credentials and expired or out-of-scope writes.

A delegation stands in for one writer: its issuer must be an allowed delegator
and a member of a 1-of-n writer set. It cannot satisfy a 2-of-n policy by itself.
The effective lifetime is bounded by both the namespace and chain settings.
Changing the allowed issuers takes effect at the committed activation height.

The host first checks topic policy, then calls machine admission and composite
rules. Machine hooks receive the verified payload through `getBody()`, while
message identity still refers to the original frame. A balance command executes
the same transfer with or without framing; its message ID, receipt and message
proof necessarily follow the actual submitted bytes. Do not strip a frame from
an archived message or synthesize a replacement ID.

Topic payload schemas use the same `yano-cbor-schema-ir-v1` evaluator as
`authenticated-map`. CDDL is compiled by `CddlSchemaCompiler` before submission;
members only evaluate committed IR. Put a topic schema on an opaque machine
such as `ordered-log`. Typed kernels such as `balances` already define their
command format and reject a second topic schema.

## Proofs and trust

An entry-state proof establishes one registry record at a root. It does not
establish a topic's effective policy: ancestors, absence of overrides and the
configuration also matter. `appchain topics resolve` returns a
`topic-policy-v1` effective-policy bundle for a candidate height, anchored to
its parent state's root. It includes the configuration revision and source
entry revisions, including disabled and unregistered outcomes.

The current host exposes entry-state proofs through the generic state proof
API, rather than a separate `topic-entry-v1` typed REST endpoint. Studio labels
them as entry-only proofs. Its downloaded peer responses are not independent
verification. For authorship, `appchain topics verify` requires the message,
frame, generation and complete effective-policy and replay/revocation evidence,
plus independently trusted block/root information. It refuses entry-only data.
A valid signature alone does not establish current authority or finality.

## Preview upgrade boundary

This implementation pins the Yano host at `0.1.0-pre19`, API level 13, with
its matching ordinary JVM ZIP. Qualify it on a fresh app-chain genesis. Existing retained deployments
remain on their exact host, profile and genesis; this change supplies no
in-place migration or reset procedure.

The old collection schema vectors keep their IR bytes and results. The merged
core evaluator also hardens the work budget by charging bytes examined in
literal comparisons. A pathological legacy value can therefore be rejected
at that budget boundary; the regression suite records this case. This is an
intentional prerelease boundary, not a claim that every historical state can
be replayed under the new host. The host also adds a committed clock-skew field
to its consensus profile. Keep old stores with the binary that created them.

See [Protect a topic and give users session keys](/recipes/topic-session-keys/) and
[admission rules](/bindings/admission-rules/).
